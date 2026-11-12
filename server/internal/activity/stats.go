package activity

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/db"
)

// Statistics read the covering index on plays (named, so the planner does
// not trade the date range for an index that matches GROUP BY) and the
// daily totals, and are cached until history next changes.

// StatsQuery picks the period and scope of the statistics cards.
type StatsQuery struct {
	Days      int // 0 is all time
	ByTime    bool
	UserID    int64
	LibraryID int64
}

// StatItem is one entry of a top list.
type StatItem struct {
	Title      string    `json:"title"`
	Subtitle   string    `json:"subtitle,omitempty"`
	PosterPath string    `json:"posterPath,omitempty"`
	MovieID    *int64    `json:"movieId,omitempty"`
	ShowID     *int64    `json:"showId,omitempty"`
	UserID     *int64    `json:"userId,omitempty"`
	DeviceID   *int64    `json:"deviceId,omitempty"`
	Plays      int64     `json:"plays"`
	PlayedMs   int64     `json:"playedMs"`
	Users      int64     `json:"users" doc:"Different people who watched."`
	LastAt     time.Time `json:"lastAt"`
}

// Peak is the most streams running at once.
type Peak struct {
	Count int        `json:"count"`
	At    *time.Time `json:"at,omitempty"`
}

// Peaks splits peak concurrency by stream mode, as Tautulli does.
type Peaks struct {
	All       Peak `json:"all"`
	Direct    Peak `json:"direct"`
	Remux     Peak `json:"remux"`
	Transcode Peak `json:"transcode" doc:"Audio-only and full transcodes."`
}

// Cards are the statistics page's top lists for a period.
type Cards struct {
	Plays         int64        `json:"plays"`
	PlayedMs      int64        `json:"playedMs"`
	TopShows      []StatItem   `json:"topShows" nullable:"false"`
	TopMovies     []StatItem   `json:"topMovies" nullable:"false"`
	PopularShows  []StatItem   `json:"popularShows" nullable:"false"`
	PopularMovies []StatItem   `json:"popularMovies" nullable:"false"`
	TopUsers      []StatItem   `json:"topUsers" nullable:"false"`
	TopDevices    []StatItem   `json:"topDevices" nullable:"false"`
	Peak          Peaks        `json:"peak"`
	Recent        []HistoryRow `json:"recent" nullable:"false"`
}

const topN = 5

type statsCache struct {
	mu      sync.Mutex
	version int64
	m       map[string]any
}

// cached returns the value stored under key while history has not changed,
// else computes it.
func cached[T any](t *Tracker, key string, fn func() (T, error)) (T, error) {
	v := t.version.Load()
	c := &t.cache
	c.mu.Lock()
	if c.version != v || c.m == nil || len(c.m) > 500 {
		c.version, c.m = v, map[string]any{}
	}
	if x, ok := c.m[key]; ok {
		c.mu.Unlock()
		return x.(T), nil
	}
	c.mu.Unlock()
	x, err := fn()
	if err != nil {
		return x, err
	}
	c.mu.Lock()
	if c.version == v {
		c.m[key] = x
	}
	c.mu.Unlock()
	return x, nil
}

// since is the earliest start in the period, as a stored timestamp.
func (t *Tracker) since(days int) string {
	if days <= 0 {
		return ""
	}
	return db.FormatTime(t.now().AddDate(0, 0, -days))
}

// scope is the shared WHERE clause for a period, user and library.
func (t *Tracker) scope(q StatsQuery) (string, []any) {
	w := "p.started_at >= ?"
	args := []any{t.since(q.Days)}
	if q.UserID != 0 {
		w += " AND p.user_id = ?"
		args = append(args, q.UserID)
	}
	if q.LibraryID != 0 {
		w += " AND p.library_id = ?"
		args = append(args, q.LibraryID)
	}
	return w, args
}

// Cards computes the top lists for a period.
func (t *Tracker) Cards(ctx context.Context, q StatsQuery) (Cards, error) {
	return cached(t, fmt.Sprintf("cards:%+v", q), func() (Cards, error) { return t.cards(ctx, q) })
}

func (t *Tracker) cards(ctx context.Context, q StatsQuery) (Cards, error) {
	c := Cards{TopShows: []StatItem{}, TopMovies: []StatItem{}, PopularShows: []StatItem{}, PopularMovies: []StatItem{},
		TopUsers: []StatItem{}, TopDevices: []StatItem{}, Recent: []HistoryRow{}}
	if t.db == nil {
		return c, nil
	}
	where, args := t.scope(q)
	shows, movies, users, devices, err := t.scan(ctx, where, args, &c)
	if err != nil {
		return c, err
	}
	if c.TopShows, err = t.describe(ctx, "show", top(shows, q.ByTime, false)); err != nil {
		return c, err
	}
	if c.PopularShows, err = t.describe(ctx, "show", top(shows, q.ByTime, true)); err != nil {
		return c, err
	}
	if c.TopMovies, err = t.describe(ctx, "movie", top(movies, q.ByTime, false)); err != nil {
		return c, err
	}
	if c.PopularMovies, err = t.describe(ctx, "movie", top(movies, q.ByTime, true)); err != nil {
		return c, err
	}
	if c.TopUsers, err = t.describe(ctx, "user", top(users, q.ByTime, false)); err != nil {
		return c, err
	}
	if c.TopDevices, err = t.describe(ctx, "device", top(devices, q.ByTime, false)); err != nil {
		return c, err
	}
	recent, err := t.History(ctx, Filter{UserID: q.UserID, LibraryID: q.LibraryID, Limit: 8, noTotal: true})
	if err != nil {
		return c, err
	}
	c.Recent = recent.Items
	return c, nil
}

type agg struct {
	key      sql.NullInt64
	plays    int64
	playedMs int64
	users    int64
	last     string
}

// scan reads the period's plays from the covering index once, totalling
// them per show, movie, user and device and finding the peak concurrency.
func (t *Tracker) scan(ctx context.Context, where string, args []any, c *Cards) (shows, movies, users, devices []agg, err error) {
	//nolint:gosec // G202: the clauses joined in are fixed text; every value is a bound argument.
	rows, err := t.db.QueryContext(ctx, `
		SELECT p.user_id, p.history_item_id, p.show_id, p.device_id, p.item_type = 'movie',
			CASE p.mode WHEN 'direct' THEN 0 WHEN 'remux' THEN 1 ELSE 2 END, p.played_ms, p.group_latest, p.started_at, p.stopped_at
		FROM plays p INDEXED BY plays_stats_idx WHERE `+where, args...)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	defer func() { _ = rows.Close() }()
	type acc struct {
		agg
		viewers map[int64]struct{}
	}
	bys := [4]map[int64]*acc{{}, {}, {}, {}}
	count := func(m map[int64]*acc, key sql.NullInt64, user int64, plays, ms int64, at string) {
		a := m[key.Int64]
		if a == nil {
			a = &acc{key: key, viewers: map[int64]struct{}{}}
			m[key.Int64] = a
		}
		a.plays += plays
		a.playedMs += ms
		a.viewers[user] = struct{}{}
		a.last = max(a.last, at)
	}
	var edges []edge
	for rows.Next() {
		var (
			user, show, device  sql.NullInt64
			item, played, plays int64
			isMovie             bool
			mode                int
			at                  string
			stopped             sql.NullString
		)
		if err := rows.Scan(&user, &item, &show, &device, &isMovie, &mode, &played, &plays, &at, &stopped); err != nil {
			return nil, nil, nil, nil, err
		}
		c.Plays += plays
		c.PlayedMs += played
		switch {
		case show.Valid:
			count(bys[0], show, user.Int64, plays, played, at)
		case isMovie:
			count(bys[1], sql.NullInt64{Int64: item, Valid: true}, user.Int64, plays, played, at)
		}
		count(bys[2], user, user.Int64, plays, played, at)
		if device.Valid {
			count(bys[3], device, user.Int64, plays, played, at)
		}
		if stopped.Valid && stopped.String > at {
			edges = append(edges, edge{at, 1, mode}, edge{stopped.String, -1, mode})
		}
	}
	if err := rows.Err(); err != nil {
		return nil, nil, nil, nil, err
	}
	c.Peak = peaks(edges)
	var out [4][]agg
	for i, m := range bys {
		for _, a := range m {
			a.users = int64(len(a.viewers))
			out[i] = append(out[i], a.agg)
		}
	}
	return out[0], out[1], out[2], out[3], nil
}

// top orders by plays or time, or by different viewers for "most popular",
// and keeps the first few.
func top(as []agg, byTime, popular bool) []agg {
	as = append([]agg(nil), as...)
	sort.SliceStable(as, func(i, j int) bool {
		a, b := as[i], as[j]
		if popular && a.users != b.users {
			return a.users > b.users
		}
		if byTime && a.playedMs != b.playedMs {
			return a.playedMs > b.playedMs
		}
		if a.plays != b.plays {
			return a.plays > b.plays
		}
		if a.playedMs != b.playedMs {
			return a.playedMs > b.playedMs
		}
		return a.last > b.last
	})
	var out []agg
	for _, a := range as {
		if len(out) == topN {
			break
		}
		if a.plays == 0 && a.playedMs == 0 {
			continue
		}
		out = append(out, a)
	}
	return out
}

// describe names the entries of a top list.
func (t *Tracker) describe(ctx context.Context, kind string, as []agg) ([]StatItem, error) {
	out := make([]StatItem, 0, len(as))
	for _, a := range as {
		it := StatItem{Plays: a.plays, PlayedMs: a.playedMs, Users: a.users, LastAt: db.ParseTime(a.last)}
		id := a.key.Int64
		var err error
		switch kind {
		case "show":
			it.ShowID = &id
			err = t.db.QueryRowContext(ctx, `SELECT show_title, poster_path FROM history_items WHERE show_id = ? ORDER BY updated_at DESC LIMIT 1`, id).
				Scan(&it.Title, &it.PosterPath)
		case "movie":
			var year sql.NullInt64
			err = t.db.QueryRowContext(ctx, `SELECT movie_id, title, year, poster_path FROM history_items WHERE id = ?`, id).
				Scan(&it.MovieID, &it.Title, &year, &it.PosterPath)
			if year.Valid {
				it.Subtitle = fmt.Sprint(year.Int64)
			}
		case "user":
			if a.key.Valid {
				it.UserID = &id
				err = t.db.QueryRowContext(ctx, `SELECT name FROM users WHERE id = ?`, id).Scan(&it.Title)
			}
			if !a.key.Valid || errors.Is(err, sql.ErrNoRows) {
				it.Title, err = "Deleted user", nil
			}
		case "device":
			it.DeviceID = &id
			var user string
			err = t.db.QueryRowContext(ctx, `SELECT d.name, d.platform, u.name FROM devices d JOIN users u ON u.id = d.user_id WHERE d.id = ?`, id).
				Scan(&it.Title, &it.Subtitle, &user)
			if err == nil {
				it.Subtitle = strings.Trim(user+" · "+it.Subtitle, " ·")
			}
		}
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, nil
}

// An edge is a play starting (d 1) or ending (d -1); mode is 0 for direct
// play, 1 for direct stream and 2 for a transcode.
type edge struct {
	at   string
	d    int
	mode int
}

// peaks finds the most plays running at once, overall and by mode, by
// sweeping their start and end times.
func peaks(edges []edge) Peaks {
	var ps Peaks
	// Ends sort before starts at the same instant, so back-to-back plays do
	// not count as overlapping.
	sort.Slice(edges, func(i, j int) bool {
		if edges[i].at != edges[j].at {
			return edges[i].at < edges[j].at
		}
		return edges[i].d < edges[j].d
	})
	var all, direct, remux, trans int
	bump := func(p *Peak, n int, at string) {
		if n > p.Count {
			tm := db.ParseTime(at)
			p.Count, p.At = n, &tm
		}
	}
	for _, e := range edges {
		all += e.d
		bump(&ps.All, all, e.at)
		switch e.mode {
		case 0:
			direct += e.d
			bump(&ps.Direct, direct, e.at)
		case 1:
			remux += e.d
			bump(&ps.Remux, remux, e.at)
		default:
			trans += e.d
			bump(&ps.Transcode, trans, e.at)
		}
	}
	return ps
}

// SeriesQuery picks a time series from the daily totals.
type SeriesQuery struct {
	Days   int // 0 is all time
	ByTime bool
	// Split is type, mode, user or network.
	Split string
	// Bucket is day or month.
	Bucket string
	UserID int64
}

// Series is a time series per split value, over continuous buckets.
type Series struct {
	Buckets []string     `json:"buckets" nullable:"false" doc:"Days (YYYY-MM-DD) or months (YYYY-MM), oldest first."`
	Lines   []SeriesLine `json:"lines" nullable:"false"`
}

type SeriesLine struct {
	Key    string  `json:"key"`
	Label  string  `json:"label"`
	Values []int64 `json:"values" nullable:"false" doc:"Plays, or milliseconds played, per bucket."`
}

var splitColumns = map[string]string{"type": "item_type", "mode": "mode", "user": "user_id", "network": "network"}

var splitLabels = map[string]string{
	"movie": "Movies", "episode": "Episodes", "file": "Other files",
	"direct": "Direct play", "remux": "Direct stream", "audio": "Audio transcode", "transcode": "Transcode",
	"local": "Home network", "tailnet": "Tailnet", "remote": "Internet",
}

// Series reads the daily totals.
func (t *Tracker) Series(ctx context.Context, q SeriesQuery) (Series, error) {
	return cached(t, fmt.Sprintf("series:%+v", q), func() (Series, error) { return t.series(ctx, q) })
}

func (t *Tracker) series(ctx context.Context, q SeriesQuery) (Series, error) {
	out := Series{Buckets: []string{}, Lines: []SeriesLine{}}
	col, ok := splitColumns[q.Split]
	if !ok {
		col = "item_type"
	}
	if t.db == nil {
		return out, nil
	}
	month := q.Bucket == "month"
	table, bucket := "activity_daily", "local_day"
	today, _, _ := t.localTime(t.now())
	from := ""
	if q.Days > 0 {
		from, _, _ = t.localTime(t.now().AddDate(0, 0, -(q.Days - 1)))
	} else if err := t.db.QueryRowContext(ctx, `SELECT COALESCE(MIN(local_day), ?) FROM activity_daily`, today).Scan(&from); err != nil {
		return out, err
	}
	where := "local_day >= ?"
	args := []any{from}
	if month {
		table, bucket, where, args = "activity_monthly", "local_month", "local_month >= ?", []any{from[:7]}
	}
	if q.UserID != 0 {
		where += " AND user_id = ?"
		args = append(args, q.UserID)
	}
	value := "SUM(plays)"
	if q.ByTime {
		value = "SUM(played_ms)"
	}
	rows, err := t.db.QueryContext(ctx, `SELECT `+bucket+`, CAST(`+col+` AS TEXT), `+value+` FROM `+table+` WHERE `+where+` GROUP BY 1, 2`, args...)
	if err != nil {
		return out, err
	}
	defer func() { _ = rows.Close() }()
	vals := map[string]map[string]int64{}
	for rows.Next() {
		var b, k string
		var v int64
		if err := rows.Scan(&b, &k, &v); err != nil {
			return out, err
		}
		if vals[k] == nil {
			vals[k] = map[string]int64{}
		}
		vals[k][b] += v
	}
	if err := rows.Err(); err != nil {
		return out, err
	}
	// Continuous buckets, so gaps show as zero.
	start, err := time.ParseInLocation(time.DateOnly, from, t.loc)
	if err != nil {
		return out, err
	}
	end, _ := time.ParseInLocation(time.DateOnly, today, t.loc)
	if month {
		for d := time.Date(start.Year(), start.Month(), 1, 0, 0, 0, 0, t.loc); !d.After(end); d = d.AddDate(0, 1, 0) {
			out.Buckets = append(out.Buckets, d.Format("2006-01"))
		}
	} else {
		for d := start; !d.After(end); d = d.AddDate(0, 0, 1) {
			out.Buckets = append(out.Buckets, d.Format(time.DateOnly))
		}
	}
	keys := make([]string, 0, len(vals))
	totals := map[string]int64{}
	for k, m := range vals {
		keys = append(keys, k)
		for _, v := range m {
			totals[k] += v
		}
	}
	// Biggest first, so the largest series sits at the bottom of a stack.
	sort.Slice(keys, func(i, j int) bool {
		if totals[keys[i]] != totals[keys[j]] {
			return totals[keys[i]] > totals[keys[j]]
		}
		return keys[i] < keys[j]
	})
	for _, k := range keys {
		line := SeriesLine{Key: k, Label: splitLabels[k], Values: make([]int64, len(out.Buckets))}
		if q.Split == "user" {
			line.Label = t.userName(ctx, k)
		}
		if line.Label == "" {
			line.Label = k
		}
		for i, b := range out.Buckets {
			line.Values[i] = vals[k][b]
		}
		out.Lines = append(out.Lines, line)
	}
	return out, nil
}

func (t *Tracker) userName(ctx context.Context, id string) string {
	var name string
	if err := t.db.QueryRowContext(ctx, `SELECT name FROM users WHERE id = ?`, id).Scan(&name); err != nil {
		return "Deleted user"
	}
	return name
}

// Heatmap is plays (or time) by weekday and hour, in the server's time zone.
type Heatmap struct {
	// Values[weekday][hour], weekday 0 is Sunday.
	Values [][]int64 `json:"values" nullable:"false" doc:"Seven rows (Sunday first) of 24 hours."`
	Max    int64     `json:"max"`
}

// Heatmap counts plays by the weekday and hour they started.
func (t *Tracker) Heatmap(ctx context.Context, days int, byTime bool, userID int64) (Heatmap, error) {
	return cached(t, fmt.Sprintf("heat:%d:%v:%d", days, byTime, userID), func() (Heatmap, error) {
		h := Heatmap{Values: make([][]int64, 7)}
		for i := range h.Values {
			h.Values[i] = make([]int64, 24)
		}
		if t.db == nil {
			return h, nil
		}
		value := "COUNT(*)"
		if byTime {
			value = "SUM(played_ms)"
		}
		where := "started_at >= ? AND stopped_at IS NOT NULL"
		args := []any{t.since(days)}
		if userID != 0 {
			where += " AND user_id = ?"
			args = append(args, userID)
		}
		rows, err := t.db.QueryContext(ctx, `SELECT local_weekday, local_hour, `+value+` FROM plays WHERE `+where+` GROUP BY 1, 2`, args...)
		if err != nil {
			return h, err
		}
		defer func() { _ = rows.Close() }()
		for rows.Next() {
			var wd, hr int
			var v int64
			if err := rows.Scan(&wd, &hr, &v); err != nil {
				return h, err
			}
			if wd >= 0 && wd < 7 && hr >= 0 && hr < 24 {
				h.Values[wd][hr] = v
				h.Max = max(h.Max, v)
			}
		}
		return h, rows.Err()
	})
}
