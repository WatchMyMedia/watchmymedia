package activity

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"watchmymedia/server/internal/db"
)

// CountCap is where history totals stop counting: past it the total shows
// as "10,000+", which keeps the broadest searches fast.
const CountCap = 10_000

// Filter selects history. Zero values mean no filter.
type Filter struct {
	// Q matches titles (any part of three or more characters, or SxxEyy),
	// user and device names, and the start of an IP address.
	Q         string
	UserID    int64
	DeviceID  int64
	Type      string // movie, episode or file
	LibraryID int64
	Mode      string
	Network   string
	// From and To are days in the server's time zone (YYYY-MM-DD), inclusive.
	From, To  string
	Completed *bool
	MovieID   int64
	ShowID    int64
	EpisodeID int64
	// Ungrouped lists every play instead of one row per group.
	Ungrouped bool
	Cursor    string
	Limit     int
	// noTotal skips counting, for callers that only want the newest rows.
	noTotal bool
}

// HistoryRow is a play, or for a group its newest play with the group's totals.
type HistoryRow struct {
	ID             int64      `json:"id" doc:"The play; for a group, its newest play."`
	GroupID        int64      `json:"groupId"`
	GroupCount     int64      `json:"groupCount" doc:"Plays in the group; resumed plays of an item join the one they pick up."`
	StartedAt      time.Time  `json:"startedAt"`
	GroupStartedAt time.Time  `json:"groupStartedAt"`
	StoppedAt      time.Time  `json:"stoppedAt"`
	User           UserRef    `json:"user"`
	Device         *Device    `json:"device,omitempty"`
	IP             string     `json:"ip"`
	Network        string     `json:"network" enum:"local,tailnet,remote"`
	Item           Item       `json:"item"`
	FileID         *int64     `json:"fileId,omitempty"`
	Mode           string     `json:"mode" enum:"direct,remux,audio,transcode"`
	PlayedMs       int64      `json:"playedMs" doc:"Time played; for a group, across the group."`
	PausedMs       int64      `json:"pausedMs"`
	PositionMs     int64      `json:"positionMs" doc:"Where it stopped."`
	DurationMs     int64      `json:"durationMs"`
	Percent        int        `json:"percent" doc:"Furthest position reached, as a share of the duration."`
	Completed      bool       `json:"completed"`
	EndReason      string     `json:"endReason" enum:"stopped,ended,closed,timeout,terminated"`
	Detail         *PlayExtra `json:"detail,omitempty"`
}

// PlayExtra is the stream detail of one play.
type PlayExtra struct {
	Stream          Stream    `json:"stream"`
	Auto            *PlayAuto `json:"auto,omitempty" doc:"Automatic quality's steps down, or for a fixed quality those it would have taken."`
	BytesSent       int64     `json:"bytesSent"`
	BufferingMs     int64     `json:"bufferingMs"`
	StartPositionMs int64     `json:"startPositionMs"`
}

// Page is one page of history.
type Page struct {
	Items      []HistoryRow `json:"items" nullable:"false"`
	NextCursor string       `json:"nextCursor,omitempty" doc:"Pass back as cursor for the next page; absent on the last page."`
	Total      int64        `json:"total" doc:"Matching rows, counted up to 10,000."`
	// TotalCapped is set when there are more than Total.
	TotalCapped bool  `json:"totalCapped"`
	PlayedMs    int64 `json:"playedMs" doc:"Time played across the matching rows; 0 when the total is capped."`
}

var (
	seRe  = regexp.MustCompile(`^s(\d{1,2})(?:e(\d{1,3}))?$`)
	ip4Re = regexp.MustCompile(`^\d{1,3}(\.\d{0,3}){1,3}$`)
	ip6Re = regexp.MustCompile(`^[0-9a-f]{0,4}(:[0-9a-f]{0,4}){1,7}$`)
)

// where builds the WHERE clause for f, without the cursor.
func (t *Tracker) where(f Filter) (string, []any) {
	var conds []string
	var args []any
	add := func(c string, a ...any) {
		conds = append(conds, c)
		args = append(args, a...)
	}
	if f.Ungrouped {
		add("p.stopped_at IS NOT NULL")
	} else {
		add("p.group_latest = 1")
	}
	if f.UserID != 0 {
		add("p.user_id = ?", f.UserID)
	}
	if f.DeviceID != 0 {
		add("p.device_id = ?", f.DeviceID)
	}
	if f.Type != "" {
		add("p.item_type = ?", f.Type)
	}
	if f.LibraryID != 0 {
		add("p.library_id = ?", f.LibraryID)
	}
	if f.Mode != "" {
		add("p.mode = ?", f.Mode)
	}
	if f.Network != "" {
		add("p.network = ?", f.Network)
	}
	if f.Completed != nil {
		add("p.completed = ?", db.BoolInt(*f.Completed))
	}
	if from, ok := t.dayStart(f.From, 0); ok {
		add("p.started_at >= ?", from)
	}
	if to, ok := t.dayStart(f.To, 1); ok {
		add("p.started_at < ?", to)
	}
	switch {
	case f.EpisodeID != 0:
		add("p.history_item_id IN (SELECT id FROM history_items WHERE item_type = 'episode' AND item_id = ?)", f.EpisodeID)
	case f.MovieID != 0:
		add("p.history_item_id IN (SELECT id FROM history_items WHERE movie_id = ?)", f.MovieID)
	case f.ShowID != 0:
		add("p.history_item_id IN (SELECT id FROM history_items WHERE show_id = ?)", f.ShowID)
	}
	return strings.Join(conds, " AND "), args
}

// An arm is one way a search can match: a list of IDs for a column of
// plays, or a range of addresses.
type arm struct {
	col    string
	ids    []int64
	lo, hi string
	// sub is the lookup itself, used instead of a long list of IDs.
	sub     string
	subArgs []any
}

// maxListed is the most IDs an arm carries as a list; past it the lookup
// runs again as a subquery, which SQLite evaluates once per statement.
const maxListed = 500

// cond renders the arm. With scan set the column is written +col, which
// keeps SQLite from using its index, so the query walks plays newest first
// and tests each row: the right plan when matches are common.
func (a arm) cond(scan bool) (string, []any) {
	col := a.col
	if scan {
		col = "+" + col
	}
	if a.sub != "" {
		return col + " IN (" + a.sub + ")", a.subArgs
	}
	if a.ids != nil {
		return col + " IN (SELECT value FROM json_each(?))", []any{jsonText(a.ids)}
	}
	return "(" + col + " >= ? AND " + col + " < ?)", []any{a.lo, a.hi}
}

// arms resolves a search against the small tables first: titles through
// history_items (trigrams for three or more characters, LIKE below that,
// and SxxEyy), users and devices by name, and an address prefix as a range
// on ip. Alternatives that match nothing are left out, so most searches
// become a single index lookup on plays.
func (t *Tracker) arms(ctx context.Context, q string) ([]arm, error) {
	q = strings.ToLower(strings.TrimSpace(q))
	if q == "" {
		return nil, nil
	}
	var terms, short []string
	season, episode := int64(-1), int64(-1)
	for w := range strings.FieldsSeq(q) {
		if m := seRe.FindStringSubmatch(w); m != nil {
			season, _ = strconv.ParseInt(m[1], 10, 64)
			if m[2] != "" {
				episode, _ = strconv.ParseInt(m[2], 10, 64)
			}
			continue
		}
		if utf8.RuneCountInString(w) >= 3 {
			terms = append(terms, `"`+strings.ReplaceAll(w, `"`, `""`)+`"`)
		} else {
			short = append(short, w)
		}
	}
	var conds []string
	var args []any
	if len(terms) > 0 {
		conds = append(conds, "h.id IN (SELECT rowid FROM history_items_fts WHERE history_items_fts MATCH ?)")
		args = append(args, strings.Join(terms, " "))
	} else if len(short) > 0 {
		pat := "%" + likeEscape(strings.Join(short, " ")) + "%"
		conds = append(conds, `(h.title LIKE ? ESCAPE '\' OR h.show_title LIKE ? ESCAPE '\')`)
		args = append(args, pat, pat)
	}
	if season >= 0 {
		conds = append(conds, "h.season_number = ?")
		args = append(args, season)
		if episode >= 0 {
			conds = append(conds, "h.episode_number = ?")
			args = append(args, episode)
		}
	}
	var out []arm
	add := func(col, query string, args ...any) error {
		ids, err := t.ids(ctx, query, args...)
		switch {
		case len(ids) > maxListed:
			out = append(out, arm{col: col, sub: query, subArgs: args})
		case len(ids) > 0:
			out = append(out, arm{col: col, ids: ids})
		}
		return err
	}
	if len(conds) > 0 {
		if err := add("p.history_item_id", "SELECT h.id FROM history_items h WHERE "+strings.Join(conds, " AND "), args...); err != nil {
			return nil, err
		}
	}
	if season < 0 {
		pat := "%" + likeEscape(q) + "%"
		if err := add("p.user_id", `SELECT id FROM users WHERE name LIKE ? ESCAPE '\'`, pat); err != nil {
			return nil, err
		}
		if err := add("p.device_id", `SELECT id FROM devices WHERE name LIKE ? ESCAPE '\' OR platform LIKE ? ESCAPE '\' OR product LIKE ? ESCAPE '\'`, pat, pat, pat); err != nil {
			return nil, err
		}
	}
	if ip4Re.MatchString(q) || (strings.Contains(q, ":") && ip6Re.MatchString(q)) {
		// Addresses are stored as text, so a typed prefix is a range.
		out = append(out, arm{col: "p.ip", lo: q, hi: q[:len(q)-1] + string(rune(q[len(q)-1]+1))})
	}
	return out, nil
}

// ids runs a lookup, stopping once it has more than maxListed.
func (t *Tracker) ids(ctx context.Context, query string, args ...any) ([]int64, error) {
	rows, err := t.db.QueryContext(ctx, query+" LIMIT ?", append(args, maxListed+1)...)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	var out []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

func likeEscape(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

// dayStart is the UTC instant a local day starts, plus offset days.
func (t *Tracker) dayStart(day string, offset int) (string, bool) {
	if day == "" {
		return "", false
	}
	d, err := time.ParseInLocation(time.DateOnly, day, t.loc)
	if err != nil {
		return "", false
	}
	return db.FormatTime(d.AddDate(0, 0, offset)), true
}

type cursor struct {
	started string
	id      int64
}

func encodeCursor(c cursor) string {
	return base64.RawURLEncoding.EncodeToString([]byte(c.started + "|" + strconv.FormatInt(c.id, 10)))
}

func decodeCursor(s string) (cursor, bool) {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return cursor{}, false
	}
	started, id, ok := strings.Cut(string(b), "|")
	if !ok {
		return cursor{}, false
	}
	n, err := strconv.ParseInt(id, 10, 64)
	return cursor{started: started, id: n}, err == nil
}

// ErrBadCursor is returned for a cursor this server did not make.
var ErrBadCursor = errors.New("invalid cursor")

const rowColumns = `p.id, COALESCE(p.group_id, p.id), p.group_count, p.started_at, COALESCE(p.group_started_at, p.started_at), COALESCE(p.stopped_at, p.last_seen_at),
	p.user_id, p.user_name, p.device_id, COALESCE(d.name, ''), COALESCE(d.product, ''), COALESCE(d.platform, ''), p.ip, p.network, p.media_file_id, p.mode,
	p.played_ms, p.group_played_ms, p.paused_ms, p.end_position_ms, p.max_position_ms, p.duration_ms, p.completed, COALESCE(p.end_reason, ''),
	h.item_type, h.item_id, h.movie_id, h.show_id, h.library_id, h.title, h.show_title, h.season_number, h.episode_number, h.year, h.poster_path, h.art_path,
	p.reasons, p.source, p.stream, p.encoder, p.quality, p.auto, p.bytes_sent, p.buffering_ms, p.start_position_ms`

func scanRow(rows *sql.Rows, grouped, detail bool) (HistoryRow, error) {
	var (
		r                                HistoryRow
		started, groupStarted, stopped   string
		userID, deviceID, fileID         sql.NullInt64
		devName, devProduct, devPlatform string
		played, groupPlayed, maxPos      int64
		completed                        int64
		reasons, source, stream, auto    string
		extra                            PlayExtra
	)
	err := rows.Scan(&r.ID, &r.GroupID, &r.GroupCount, &started, &groupStarted, &stopped,
		&userID, &r.User.Name, &deviceID, &devName, &devProduct, &devPlatform, &r.IP, &r.Network, &fileID, &r.Mode,
		&played, &groupPlayed, &r.PausedMs, &r.PositionMs, &maxPos, &r.DurationMs, &completed, &r.EndReason,
		&r.Item.Type, &r.Item.ItemID, &r.Item.MovieID, &r.Item.ShowID, &r.Item.LibraryID, &r.Item.Title, &r.Item.ShowTitle,
		&r.Item.SeasonNumber, &r.Item.EpisodeNumber, &r.Item.Year, &r.Item.PosterPath, &r.Item.ArtPath,
		&reasons, &source, &stream, &extra.Stream.Encoder, &extra.Stream.Quality, &auto, &extra.BytesSent, &extra.BufferingMs, &extra.StartPositionMs)
	if err != nil {
		return r, err
	}
	r.StartedAt, r.GroupStartedAt, r.StoppedAt = db.ParseTime(started), db.ParseTime(groupStarted), db.ParseTime(stopped)
	r.User.ID = userID.Int64
	if deviceID.Valid {
		r.Device = &Device{ID: deviceID.Int64, Name: devName, Product: devProduct, Platform: devPlatform}
	}
	if fileID.Valid {
		r.FileID = &fileID.Int64
	}
	if r.Item.Type == "episode" {
		id := r.Item.ItemID
		r.Item.EpisodeID = &id
	}
	r.PlayedMs = played
	if grouped {
		r.PlayedMs = groupPlayed
	} else {
		r.GroupCount = 1
	}
	if r.DurationMs > 0 {
		r.Percent = int(min(100, maxPos*100/r.DurationMs))
	}
	r.Completed = completed == 1
	if r.Completed {
		r.Percent = 100
	}
	if detail {
		extra.Stream.Mode = r.Mode
		_ = json.Unmarshal([]byte(reasons), &extra.Stream.Reasons)
		if extra.Stream.Reasons == nil {
			extra.Stream.Reasons = []string{}
		}
		_ = json.Unmarshal([]byte(source), &extra.Stream.Source)
		_ = json.Unmarshal([]byte(stream), &extra.Stream.Stream)
		if auto != "" {
			var a PlayAuto
			if json.Unmarshal([]byte(auto), &a) == nil {
				extra.Auto = &a
				if a.On {
					extra.Stream.Step = a.Step
				}
			}
		}
		r.Detail = &extra
	}
	return r, nil
}

// denseAt is the number of matches above which a search walks plays newest
// first; below it the matches are collected through their indexes and sorted.
const denseAt = 2000

// History returns a page of history, newest first.
func (t *Tracker) History(ctx context.Context, f Filter) (Page, error) {
	page := Page{Items: []HistoryRow{}}
	if t.db == nil {
		return page, nil
	}
	if f.Limit <= 0 || f.Limit > 500 {
		f.Limit = 50
	}
	var c cursor
	if f.Cursor != "" {
		var ok bool
		if c, ok = decodeCursor(f.Cursor); !ok {
			return page, ErrBadCursor
		}
	}
	base, args := t.where(f)
	arms, err := t.arms(ctx, f.Q)
	if err != nil {
		return page, err
	}
	if f.Q != "" && len(arms) == 0 {
		return page, nil
	}

	// The total, capped. A search needs it to choose its plan, so it is
	// counted on every page; otherwise only on the first.
	if (f.Cursor == "" && !f.noTotal) || len(arms) > 0 {
		played := "p.group_played_ms"
		if f.Ungrouped {
			played = "p.played_ms"
		}
		inner := `SELECT p.id, ` + played + ` AS x FROM plays p WHERE ` + base
		var q string
		var qargs []any
		if len(arms) == 0 {
			q, qargs = inner, args
		} else {
			// One index lookup per arm, each capped (SQLite would otherwise
			// finish the first arm before the outer LIMIT applies), then
			// deduplicated.
			parts := make([]string, len(arms))
			for i, a := range arms {
				cond, cargs := a.cond(false)
				parts[i] = "SELECT * FROM (" + inner + " AND " + cond + " LIMIT ?)"
				qargs = append(append(append(qargs, args...), cargs...), CountCap+1)
			}
			q = strings.Join(parts, " UNION ")
		}
		var n, ms int64
		if err := t.db.QueryRowContext(ctx, `SELECT COUNT(*), COALESCE(SUM(x), 0) FROM (`+q+` LIMIT ?)`, append(qargs, CountCap+1)...).Scan(&n, &ms); err != nil {
			return page, err
		}
		if f.Cursor == "" && !f.noTotal {
			page.Total, page.PlayedMs = n, ms
			if n > CountCap {
				page.Total, page.TotalCapped, page.PlayedMs = CountCap, true, 0
			}
		}
		if len(arms) > 0 {
			var conds []string
			var cargs []any
			if n > denseAt {
				for _, a := range arms {
					cond, a := a.cond(true)
					conds, cargs = append(conds, cond), append(cargs, a...)
				}
				base += " AND (" + strings.Join(conds, " OR ") + ")"
			} else {
				for _, a := range arms {
					cond, a := a.cond(false)
					conds = append(conds, `SELECT p.id FROM plays p WHERE `+base+` AND `+cond)
					cargs = append(append(cargs, args...), a...)
				}
				base = "p.id IN (" + strings.Join(conds, " UNION ") + ")"
			}
			args = cargs
		}
	}
	if f.Cursor != "" {
		base += " AND (p.started_at, p.id) < (?, ?)"
		args = append(args, c.started, c.id)
	}
	// The page is picked from plays alone, so the planner walks an index of
	// plays rather than starting from the joined tables.
	//nolint:gosec // G202: the clauses joined in are fixed text; every value is a bound argument.
	rows, err := t.db.QueryContext(ctx, `
		WITH page AS MATERIALIZED (
			SELECT p.id FROM plays p WHERE `+base+`
			ORDER BY p.started_at DESC, p.id DESC LIMIT ?
		)
		SELECT `+rowColumns+`
		FROM page JOIN plays p ON p.id = page.id
		JOIN history_items h ON h.id = p.history_item_id
		LEFT JOIN devices d ON d.id = p.device_id
		ORDER BY p.started_at DESC, p.id DESC`, append(args, f.Limit+1)...)
	if err != nil {
		return page, err
	}
	defer func() { _ = rows.Close() }()
	for rows.Next() {
		r, err := scanRow(rows, !f.Ungrouped, false)
		if err != nil {
			return page, err
		}
		page.Items = append(page.Items, r)
	}
	if err := rows.Err(); err != nil {
		return page, err
	}
	if len(page.Items) > f.Limit {
		page.Items = page.Items[:f.Limit]
		last := page.Items[len(page.Items)-1]
		page.NextCursor = encodeCursor(cursor{started: db.FormatTime(last.StartedAt), id: last.ID})
	}
	return page, nil
}

// Group returns every play of a group, oldest first, with stream detail.
// userID limits it to one user's plays (0 for any).
func (t *Tracker) Group(ctx context.Context, groupID, userID int64) ([]HistoryRow, error) {
	out := []HistoryRow{}
	if t.db == nil {
		return out, nil
	}
	q := `SELECT ` + rowColumns + `
		FROM plays p JOIN history_items h ON h.id = p.history_item_id LEFT JOIN devices d ON d.id = p.device_id
		WHERE p.group_id = ? AND p.stopped_at IS NOT NULL`
	args := []any{groupID}
	if userID != 0 {
		q += " AND p.user_id = ?"
		args = append(args, userID)
	}
	rows, err := t.db.QueryContext(ctx, q+" ORDER BY p.started_at, p.id", args...)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	for rows.Next() {
		r, err := scanRow(rows, false, true)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// Each calls fn for every row matching f, newest first, up to max rows;
// for exports.
func (t *Tracker) Each(ctx context.Context, f Filter, max int, fn func(HistoryRow) error) error {
	f.Limit, f.Cursor = 500, ""
	n := 0
	for {
		page, err := t.History(ctx, f)
		if err != nil {
			return err
		}
		for _, r := range page.Items {
			if n >= max {
				return nil
			}
			if err := fn(r); err != nil {
				return err
			}
			n++
		}
		if page.NextCursor == "" {
			return nil
		}
		f.Cursor = page.NextCursor
	}
}
