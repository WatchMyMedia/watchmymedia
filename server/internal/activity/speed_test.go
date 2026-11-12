package activity

import (
	"context"
	"database/sql"
	"fmt"
	"math/rand/v2"
	"os"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/netguard"

	"net/netip"
)

var words = strings.Fields(`the a of and night day house dark light blue red king queen city river road last first lost found
	star moon sun fire water stone iron glass shadow ghost heart blood wolf bear fox crow storm rain snow winter summer spring
	autumn north south east west line edge end beginning home world story secret promise game war peace time love hate truth
	lie dream sleep wake black white gold silver green young old new little big long short high low deep wild quiet loud
	broken hidden silent fallen rising burning frozen golden empty crimson hollow distant lonely bright savage gentle brave
	mountain ocean forest desert island garden bridge tower castle valley harbor station kingdom empire border signal echo
	mirror window door key letter map clock bell crown sword shield arrow anchor compass lantern candle thread needle`)

func phrase(r *rand.Rand, n int) string {
	ws := make([]string, n)
	for i := range ws {
		w := words[r.IntN(len(words))]
		ws[i] = strings.ToUpper(w[:1]) + w[1:]
	}
	return strings.Join(ws, " ")
}

// seedHistory fills the database with n finished plays over five years
// from 12 users, 2,000 movies and 18,000 episodes, the way a busy household
// would have made them: mostly one sitting, sometimes two or three for one
// viewing, mostly at home and direct play.
func seedHistory(tb testing.TB, conn *sql.DB, n int, end time.Time) {
	tb.Helper()
	ctx := context.Background()
	r := rand.New(rand.NewPCG(1, 2)) //nolint:gosec // G404: a fixed seed, so every run seeds the same history
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	exec := func(q string, args ...any) {
		if _, err := tx.ExecContext(ctx, q, args...); err != nil {
			tb.Fatal(err)
		}
	}
	for u := 3; u <= 12; u++ {
		exec(`INSERT INTO users (id, name, password_hash) VALUES (?, ?, 'x')`, u, fmt.Sprintf("user%02d", u))
	}
	platforms := []string{"macOS", "iPhone", "Windows", "Android"}
	for u := 1; u <= 12; u++ {
		for d := range 3 {
			p := platforms[(u+d)%len(platforms)]
			exec(`INSERT INTO devices (id, user_id, device_key, name, product, platform, last_ip) VALUES (?, ?, ?, ?, 'WatchMyMedia Web', ?, '')`,
				u*10+d, u, fmt.Sprintf("k%d-%d", u, d), "Chrome on "+p, p)
		}
	}
	type item struct {
		id, show  int64
		typ       string
		duration  int64
		libraryID int64
	}
	var items []item
	id := int64(0)
	for m := 1; m <= 2000; m++ {
		id++
		exec(`INSERT INTO history_items (id, item_type, item_id, movie_id, library_id, title, year) VALUES (?, 'movie', ?, ?, 1, ?, ?)`,
			id, m, m, phrase(r, 1+r.IntN(3)), 1950+r.IntN(75))
		items = append(items, item{id: id, typ: "movie", duration: int64(85+r.IntN(70)) * 60_000, libraryID: 1})
	}
	ep := 0
	for s := 1; s <= 300; s++ {
		show := phrase(r, 1+r.IntN(3))
		for e := range 60 {
			id++
			ep++
			exec(`INSERT INTO history_items (id, item_type, item_id, show_id, library_id, title, show_title, season_number, episode_number) VALUES (?, 'episode', ?, ?, 2, ?, ?, ?, ?)`,
				id, ep, s, phrase(r, 1+r.IntN(4)), show, 1+e/10, 1+e%10)
			items = append(items, item{id: id, show: int64(s), typ: "episode", duration: int64(22+r.IntN(40)) * 60_000, libraryID: 2})
		}
	}
	modes := []string{"direct", "direct", "direct", "direct", "direct", "direct", "direct", "remux", "remux", "audio", "transcode", "transcode"}
	ins, err := tx.PrepareContext(ctx, `INSERT INTO plays (public_id, user_id, user_name, device_id, history_item_id, library_id, item_type, show_id,
		state, started_at, stopped_at, last_seen_at, played_ms, paused_ms, start_position_ms, end_position_ms, max_position_ms, duration_ms, completed, end_reason,
		mode, ip, network, local_day, local_hour, local_weekday, group_id, group_latest, group_started_at, group_played_ms, group_count)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'stopped', ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 'stopped', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = ins.Close() }()
	span := 5 * 365 * 24 * time.Hour
	start := end.Add(-span)
	step := span / time.Duration(n)
	row := int64(0)
	for row < int64(n) {
		it := items[r.IntN(len(items))]
		if r.IntN(10) < 7 { // episodes are most of what gets watched
			it = items[2000+r.IntN(len(items)-2000)]
		}
		user := int64(1 + r.IntN(12))
		device := user*10 + int64(r.IntN(3))
		ip := fmt.Sprintf("192.168.1.%d", 10+user)
		switch r.IntN(10) {
		case 0:
			ip = fmt.Sprintf("203.0.%d.%d", r.IntN(256), r.IntN(256))
		case 1:
			ip = fmt.Sprintf("100.64.%d.%d", r.IntN(4), r.IntN(256))
		}
		mode := modes[r.IntN(len(modes))]
		sittings := 1
		if r.IntN(100) < 15 {
			sittings = 2 + r.IntN(2)
		}
		groupID := row + 1
		groupStarted := ""
		var groupPlayed, pos int64
		for s := 0; s < sittings && row < int64(n); s++ {
			row++
			started := start.Add(time.Duration(row) * step)
			if groupStarted == "" {
				groupStarted = db.FormatTime(started)
			}
			played := min(it.duration-pos, int64(5+r.IntN(60))*60_000)
			if s < sittings-1 {
				played = min(played, it.duration/int64(sittings))
			}
			stopped := started.Add(time.Duration(played) * time.Millisecond)
			groupPlayed += played
			from := pos
			pos += played
			last := s == sittings-1
			completed := 0
			if pos >= it.duration*92/100 {
				completed = 1
			}
			var show any
			if it.show > 0 {
				show = it.show
			}
			loc := started.UTC()
			_, err := ins.ExecContext(ctx, strconv.FormatInt(row, 36)+"x", user, fmt.Sprintf("user%02d", user), device, it.id, it.libraryID, it.typ, show,
				db.FormatTime(started), db.FormatTime(stopped), db.FormatTime(stopped), played, from, pos, pos, it.duration, completed,
				mode, ip, netguard.Classify(netip.MustParseAddr(ip)), loc.Format(time.DateOnly), loc.Hour(), int(loc.Weekday()),
				groupID, db.BoolInt(last), groupStarted, groupPlayed, s+1)
			if err != nil {
				tb.Fatal(err)
			}
		}
	}
	exec(`INSERT INTO activity_daily (local_day, user_id, item_type, mode, network, plays, played_ms)
		SELECT local_day, COALESCE(user_id, 0), item_type, mode, network, SUM(group_latest), SUM(played_ms) FROM plays GROUP BY 1, 2, 3, 4, 5`)
	exec(`INSERT INTO activity_monthly (local_month, user_id, item_type, mode, network, plays, played_ms)
		SELECT substr(local_day, 1, 7), user_id, item_type, mode, network, SUM(plays), SUM(played_ms) FROM activity_daily GROUP BY 1, 2, 3, 4, 5`)
	if err := tx.Commit(); err != nil {
		tb.Fatal(err)
	}
}

// TestHistorySpeed seeds a busy household's history and times each kind
// of history and statistics query. The budgets are 10 ms at the 95th
// percentile for history and search and 50 ms for statistics (which are
// also cached), with the two exceptions noted below. `make bench` runs it with 250,000 plays and fails when
// the 95th percentile is over budget. Plain `go test` (CI) uses 50,000 plays
// and fails only when the median is over five times the budget, ten on
// Windows, since shared runners are slow and noisy.
func TestHistorySpeed(t *testing.T) {
	if testing.Short() {
		t.Skip("seeds a large history")
	}
	if raceDetector {
		t.Skip("times queries, which the race detector slows tens of times")
	}
	n, strict := 50_000, os.Getenv("WMM_ACTIVITY_STRICT") == "1"
	if v, err := strconv.Atoi(os.Getenv("WMM_ACTIVITY_PLAYS")); err == nil && v > 0 {
		n = v
	}
	conn := openDB(t)
	end := time.Date(2026, 10, 29, 20, 0, 0, 0, time.UTC)
	begin := time.Now()
	seedHistory(t, conn, n, end)
	t.Logf("seeded %d plays in %v", n, time.Since(begin).Round(time.Millisecond))
	tr := New(conn, nil, quiet(), Options{Location: time.UTC, Now: func() time.Time { return end }, Config: DefaultConfig()})
	ctx := context.Background()

	// A cursor deep into the history, as after scrolling a long way.
	deep := ""
	for range 200 {
		p, err := tr.History(ctx, Filter{Cursor: deep})
		if err != nil {
			t.Fatal(err)
		}
		deep = p.NextCursor
	}
	fresh := func(f func() error) func() error {
		return func() error {
			tr.version.Add(1) // defeat the statistics cache: time the queries
			return f()
		}
	}
	history := func(f Filter) func() error {
		return func() error { _, err := tr.History(ctx, f); return err }
	}
	cases := []struct {
		name   string
		budget time.Duration
		run    func() error
	}{
		{"newest 50, grouped", 10 * time.Millisecond, history(Filter{})},
		{"newest 50, every play", 10 * time.Millisecond, history(Filter{Ungrouped: true})},
		{"page 201 by cursor", 10 * time.Millisecond, history(Filter{Cursor: deep})},
		{"one user, 30 days, transcodes", 10 * time.Millisecond, history(Filter{UserID: 3, From: "2026-09-30", Mode: "transcode"})},
		{"search a title word", 10 * time.Millisecond, history(Filter{Q: "winter"})},
		{"search two words", 10 * time.Millisecond, history(Filter{Q: "dark river"})},
		// One or two letters cannot use trigrams: LIKE reads every title, twice.
		{"search two letters", 25 * time.Millisecond, history(Filter{Q: "ow"})},
		{"search SxxEyy", 10 * time.Millisecond, history(Filter{Q: "s02e03"})},
		{"search a user", 10 * time.Millisecond, history(Filter{Q: "user07"})},
		{"search an address prefix", 10 * time.Millisecond, history(Filter{Q: "203.0.1"})},
		{"search with no match", 10 * time.Millisecond, history(Filter{Q: "zzzqqq"})},
		{"one show's plays", 10 * time.Millisecond, history(Filter{ShowID: 42, Limit: 10})},
		{"one movie's plays", 10 * time.Millisecond, history(Filter{MovieID: 42, Limit: 10})},
		{"cards, 30 days", 50 * time.Millisecond, fresh(func() error { _, err := tr.Cards(ctx, StatsQuery{Days: 30}); return err })},
		// 250,000 plays put 50,000 in a year, five times a busy household's.
		{"cards, 365 days", 60 * time.Millisecond, fresh(func() error { _, err := tr.Cards(ctx, StatsQuery{Days: 365}); return err })},
		{"series by day, 90 days", 50 * time.Millisecond, fresh(func() error { _, err := tr.Series(ctx, SeriesQuery{Days: 90, Split: "type"}); return err })},
		{"series by month, all time", 50 * time.Millisecond, fresh(func() error {
			_, err := tr.Series(ctx, SeriesQuery{Split: "mode", Bucket: "month"})
			return err
		})},
		{"heatmap, 365 days", 50 * time.Millisecond, fresh(func() error { _, err := tr.Heatmap(ctx, 365, false, 0); return err })},
		{"users", 50 * time.Millisecond, fresh(func() error { _, err := tr.Users(ctx); return err })},
	}
	// Outside make bench the median is judged, not the 95th percentile: of 25
	// runs on a shared runner that is one or two slow ones. On GitHub's
	// Windows runner a title search took 30 ms at the median and 95 ms at the
	// 95th, against 6.4 and 9 ms on an M-series Mac. That VM also runs these
	// queries three to five times slower than a Mac at the median, so it gets
	// twice the slack.
	slack := time.Duration(5)
	if runtime.GOOS == "windows" {
		slack = 10
	}
	if strict {
		slack = 1
	}
	for _, c := range cases {
		if err := c.run(); err != nil { // warm up
			t.Fatalf("%s: %v", c.name, err)
		}
		times := make([]time.Duration, 0, 25)
		for range 25 {
			s := time.Now()
			if err := c.run(); err != nil {
				t.Fatalf("%s: %v", c.name, err)
			}
			times = append(times, time.Since(s))
		}
		slices.Sort(times)
		p50, p95 := times[len(times)/2], times[len(times)*95/100]
		t.Logf("%-32s p50 %8v  p95 %8v", c.name, p50.Round(10*time.Microsecond), p95.Round(10*time.Microsecond))
		judged, which := p50, "median"
		if strict {
			judged, which = p95, "p95"
		}
		if judged > c.budget*slack {
			t.Errorf("%s: %s %v over its %v budget", c.name, which, judged, c.budget*slack)
		}
	}
}
