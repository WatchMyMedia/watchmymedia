package activity

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"log/slog"
	"net/netip"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time      { return c.t }
func (c *clock) add(d time.Duration) { c.t = c.t.Add(d) }
func quiet() *slog.Logger            { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func movie(id int64, title string) Item {
	return Item{Type: "movie", ItemID: id, MovieID: new(id), Title: title, Year: new(int64(1995))}
}
func noMin() Config { return Config{Group: true} }
func episode(id, show int64, showTitle, title string, s, e int64) Item {
	return Item{Type: "episode", ItemID: id, ShowID: new(show), Title: title, ShowTitle: showTitle, SeasonNumber: new(s), EpisodeNumber: new(e)}
}

func openDB(t *testing.T) *sql.DB {
	t.Helper()
	conn, _ := dbtest.Open(t)
	if _, err := conn.ExecContext(t.Context(), `INSERT INTO users (id, name, password_hash, is_admin) VALUES (1, 'ann', 'x', 1), (2, 'bob', 'x', 0)`); err != nil {
		t.Fatal(err)
	}
	return conn
}

func newTracker(t *testing.T, cfg Config) (*Tracker, *clock, *sql.DB) {
	t.Helper()
	conn := openDB(t)
	c := &clock{t: time.Date(2026, 10, 29, 20, 0, 0, 0, time.UTC)}
	return New(conn, events.NewBus(), quiet(), Options{Now: c.now, Location: time.UTC, Config: cfg}), c, conn
}

type playOpts struct {
	user     int64
	item     Item
	from     time.Duration // start position
	length   time.Duration // time spent playing
	ip       string
	device   string
	mode     string
	watched  time.Duration // watchedAtMs; 0 means none
	duration time.Duration
}

// play runs a whole play on the fake clock: start, a report every 10 s
// while playing, stop.
func play(t *testing.T, tr *Tracker, c *clock, o playOpts) string {
	t.Helper()
	ctx := context.Background()
	if o.ip == "" {
		o.ip = "192.168.1.20"
	}
	if o.device == "" {
		o.device = "dev-" + string(rune('a'+o.user))
	}
	if o.mode == "" {
		o.mode = "direct"
	}
	if o.duration == 0 {
		o.duration = 2 * time.Hour
	}
	names := map[int64]string{1: "ann", 2: "bob"}
	id, err := tr.Begin(ctx, Start{
		UserID: o.user, UserName: names[o.user], RecordHistory: true, Device: DeviceFrom(o.device, "", "", "", "", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"),
		IP: netip.MustParseAddr(o.ip), FileID: 0, Item: o.item, DurationMs: o.duration.Milliseconds(), WatchedAtMs: o.watched.Milliseconds(),
		PositionMs: o.from.Milliseconds(), Stream: Stream{Mode: o.mode},
	})
	if err != nil {
		t.Fatal(err)
	}
	pos := o.from
	beat := func(state string) {
		t.Helper()
		if _, err := tr.Heartbeat(ctx, id, o.user, 0, Report{PositionMs: pos.Milliseconds(), State: state}); err != nil {
			t.Fatal(err)
		}
	}
	beat(StatePlaying)
	for played := time.Duration(0); played < o.length; {
		step := min(10*time.Second, o.length-played)
		c.add(step)
		played += step
		pos += step
		beat(StatePlaying)
	}
	beat(StateStopped)
	return id
}

type row struct {
	id, groupID, groupCount, groupPlayed, played, paused, buffering, groupLatest, completed int64
	reason, state                                                                           string
	stopped                                                                                 sql.NullString
}

func readRow(t *testing.T, conn *sql.DB, publicID string) (row, bool) {
	t.Helper()
	var r row
	var gid sql.NullInt64
	var reason sql.NullString
	err := conn.QueryRowContext(t.Context(), `SELECT id, group_id, group_count, group_played_ms, played_ms, paused_ms, buffering_ms, group_latest, completed, end_reason, state, stopped_at FROM plays WHERE public_id = ?`, publicID).
		Scan(&r.id, &gid, &r.groupCount, &r.groupPlayed, &r.played, &r.paused, &r.buffering, &r.groupLatest, &r.completed, &reason, &r.state, &r.stopped)
	if errors.Is(err, sql.ErrNoRows) {
		return r, false
	}
	if err != nil {
		t.Fatal(err)
	}
	r.groupID, r.reason = gid.Int64, reason.String
	return r, true
}

func TestAccounting(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := context.Background()
	id, err := tr.Begin(ctx, Start{UserID: 1, UserName: "ann", RecordHistory: true, Device: DeviceFrom("d1", "", "", "", "", ""), Item: movie(1, "Heat"), DurationMs: 3_600_000, Stream: Stream{Mode: "direct"}})
	if err != nil {
		t.Fatal(err)
	}
	beat := func(d time.Duration, pos int64, state string) {
		t.Helper()
		c.add(d)
		if dir, err := tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: pos, State: state}); err != nil || dir != nil {
			t.Fatalf("heartbeat: %v %v", dir, err)
		}
	}
	beat(2*time.Second, 0, StatePlaying)       // 2 s loading counts as buffering
	beat(10*time.Second, 10_000, StatePlaying) // +10 s played
	beat(10*time.Second, 20_000, StatePaused)  // +10 s played, now paused
	beat(30*time.Second, 20_000, StatePaused)  // +30 s paused
	beat(5*time.Minute, 20_000, StatePlaying)  // a silent tab: paused time is capped at 60 s
	beat(10*time.Second, 30_000, StatePlaying) // +10 s played
	// Someone else's report for this play is not accepted.
	if _, err := tr.Heartbeat(ctx, id, 2, 0, Report{State: StatePlaying}); !errors.Is(err, ErrUnknownPlay) {
		t.Errorf("foreign heartbeat: %v", err)
	}
	live, sum := tr.Live(Viewer{UserID: 1})
	if len(live) != 1 || live[0].PlayedMs != 30_000 || live[0].PausedMs != 90_000 || sum.Direct != 1 {
		t.Fatalf("live = %+v %+v", live, sum)
	}
	if others, _ := tr.Live(Viewer{UserID: 2}); len(others) != 0 {
		t.Errorf("bob sees ann's play")
	}
	beat(10*time.Second, 40_000, StateStopped)
	r, ok := readRow(t, conn, id)
	if !ok {
		t.Fatal("play not stored")
	}
	if r.played != 40_000 || r.paused != 90_000 || r.buffering != 2_000 || r.reason != EndStopped || r.state != StateStopped || r.groupLatest != 1 || r.groupCount != 1 {
		t.Errorf("row = %+v", r)
	}
	if n := tr.Playing(); n != 0 {
		t.Errorf("still playing: %d", n)
	}
	var plays, played int64
	if err := conn.QueryRowContext(t.Context(), `SELECT SUM(plays), SUM(played_ms) FROM activity_daily`).Scan(&plays, &played); err != nil || plays != 1 || played != 40_000 {
		t.Errorf("daily = %d, %d (%v)", plays, played, err)
	}
}

func TestMinPlay(t *testing.T) {
	tr, c, conn := newTracker(t, DefaultConfig())
	short := play(t, tr, c, playOpts{user: 1, item: movie(1, "Heat"), length: time.Minute})
	if _, ok := readRow(t, conn, short); ok {
		t.Error("a one-minute play was kept")
	}
	// A short play that reached the credits counts.
	done := play(t, tr, c, playOpts{user: 1, item: movie(2, "Ronin"), from: 100 * time.Minute, length: time.Minute, watched: 100*time.Minute + 30*time.Second})
	if r, ok := readRow(t, conn, done); !ok || r.completed != 1 {
		t.Errorf("completed short play: %+v %v", r, ok)
	}
	long := play(t, tr, c, playOpts{user: 1, item: movie(3, "Collateral"), length: 3 * time.Minute})
	if _, ok := readRow(t, conn, long); !ok {
		t.Error("a three-minute play was dropped")
	}
}

func TestTimeoutEndsAtLastReport(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := context.Background()
	id, _ := tr.Begin(ctx, Start{UserID: 1, UserName: "ann", RecordHistory: true, Item: movie(1, "Heat"), Stream: Stream{Mode: "direct"}})
	c.add(time.Second)
	_, _ = tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: 0, State: StatePlaying})
	c.add(10 * time.Second)
	_, _ = tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: 10_000, State: StatePlaying})
	last := c.now()
	c.add(Timeout - time.Second)
	tr.reap(ctx)
	if tr.Playing() != 1 {
		t.Fatal("reaped too early")
	}
	c.add(2 * time.Second)
	tr.reap(ctx)
	if tr.Playing() != 0 {
		t.Fatal("not reaped")
	}
	r, ok := readRow(t, conn, id)
	if !ok || r.reason != EndTimeout || r.stopped.String != db.FormatTime(last) || r.played != 10_000 {
		t.Errorf("row = %+v", r)
	}
	// The player comes back: its play is gone, so the caller starts another.
	if _, err := tr.Heartbeat(ctx, id, 1, 0, Report{State: StatePlaying}); !errors.Is(err, ErrUnknownPlay) {
		t.Errorf("heartbeat after timeout: %v", err)
	}
}

func TestRecoverAfterCrash(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := context.Background()
	id, _ := tr.Begin(ctx, Start{UserID: 1, UserName: "ann", RecordHistory: true, Item: movie(1, "Heat"), Stream: Stream{Mode: "remux", Encoder: "copy"}})
	c.add(time.Second)
	_, _ = tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: 0, State: StatePlaying})
	for range 4 { // 40 s: the 30 s write happens
		c.add(10 * time.Second)
		_, _ = tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: 1, State: StatePlaying})
	}
	// The server dies here; a new one starts on the same database.
	tr2 := New(conn, nil, quiet(), Options{Now: c.now, Location: time.UTC, Config: noMin()})
	if err := tr2.Recover(ctx); err != nil {
		t.Fatal(err)
	}
	r, ok := readRow(t, conn, id)
	if !ok || r.reason != EndTimeout || !r.stopped.Valid || r.played < 30_000 || r.groupLatest != 1 {
		t.Errorf("recovered row = %+v", r)
	}
	// A play that continues the recovered one takes its stream details.
	id2, err := tr2.Begin(ctx, Start{UserID: 1, UserName: "ann", RecordHistory: true, Item: movie(1, "Heat"), Continues: id})
	if err != nil {
		t.Fatal(err)
	}
	if live, _ := tr2.Live(Viewer{UserID: 1, Admin: true}); len(live) != 1 || live[0].ID != id2 || live[0].Stream.Mode != "remux" {
		t.Errorf("continued play = %+v", live)
	}
}

func TestGrouping(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	heat := movie(1, "Heat")
	a := play(t, tr, c, playOpts{user: 1, item: heat, length: 30 * time.Minute})
	c.add(time.Hour)
	// Picks up 15 s before where the last one stopped: same group.
	b := play(t, tr, c, playOpts{user: 1, item: heat, from: 30*time.Minute - 15*time.Second, length: 20 * time.Minute})
	ra, _ := readRow(t, conn, a)
	rb, _ := readRow(t, conn, b)
	if ra.groupLatest != 0 || rb.groupLatest != 1 || rb.groupID != ra.id || rb.groupCount != 2 || rb.groupPlayed != ra.played+rb.played {
		t.Errorf("grouped rows a=%+v b=%+v", ra, rb)
	}
	// Bob's play of the same film is his own.
	bob := play(t, tr, c, playOpts{user: 2, item: heat, from: 50 * time.Minute, length: 5 * time.Minute})
	if r, _ := readRow(t, conn, bob); r.groupCount != 1 {
		t.Errorf("bob joined ann's group: %+v", r)
	}
	// Starting over from the beginning is a new viewing.
	again := play(t, tr, c, playOpts{user: 1, item: heat, from: 0, length: 5 * time.Minute})
	if r, _ := readRow(t, conn, again); r.groupCount != 1 || r.groupID != r.id {
		t.Errorf("restart joined the group: %+v", r)
	}
	// After a play that finished, the next one is new too.
	done := play(t, tr, c, playOpts{user: 1, item: movie(2, "Ronin"), length: 3 * time.Minute, watched: time.Minute})
	next := play(t, tr, c, playOpts{user: 1, item: movie(2, "Ronin"), from: 3 * time.Minute, length: 3 * time.Minute})
	rd, _ := readRow(t, conn, done)
	rn, _ := readRow(t, conn, next)
	if rd.completed != 1 || rn.groupCount != 1 {
		t.Errorf("after a finished play: done=%+v next=%+v", rd, rn)
	}
	// With grouping off, every play is its own row.
	tr.SetConfig(Config{})
	off := play(t, tr, c, playOpts{user: 1, item: heat, from: 5 * time.Minute, length: 5 * time.Minute})
	if r, _ := readRow(t, conn, off); r.groupCount != 1 {
		t.Errorf("grouped with grouping off: %+v", r)
	}
	// The daily totals count a group once.
	var plays int64
	if err := conn.QueryRowContext(t.Context(), `SELECT SUM(plays) FROM activity_daily`).Scan(&plays); err != nil || plays != 6 {
		t.Errorf("daily plays = %d (%v), want 6", plays, err)
	}
}

func TestTerminate(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := context.Background()
	id, _ := tr.Begin(ctx, Start{UserID: 2, UserName: "bob", RecordHistory: true, Item: movie(1, "Heat"), FileID: 0, Stream: Stream{Mode: "direct"}})
	c.add(10 * time.Second)
	_, _ = tr.Heartbeat(ctx, id, 2, 0, Report{PositionMs: 10_000, State: StatePlaying})
	if !tr.AddBytes(id, 1000) {
		t.Fatal("bytes refused before stop")
	}
	if err := tr.Terminate(ctx, id, "Bedtime"); err != nil {
		t.Fatal(err)
	}
	dir, err := tr.Heartbeat(ctx, id, 2, 0, Report{PositionMs: 11_000, State: StatePlaying})
	if err != nil || dir == nil || dir.Action != "stop" || dir.Message != "Bedtime" {
		t.Errorf("directive = %+v, %v", dir, err)
	}
	var te *TerminatedError
	if err := tr.Check(id); !errors.As(err, &te) || te.Message != "Bedtime" {
		t.Errorf("check = %v", err)
	}
	if tr.AddBytes(id, 1000) {
		t.Error("bytes still allowed")
	}
	if err := tr.Continue(ctx, id, 2, 0, Stream{Mode: "transcode"}, 0, 0); !errors.As(err, &te) {
		t.Errorf("continue after stop = %v", err)
	}
	if r, _ := readRow(t, conn, id); r.reason != EndTerminated {
		t.Errorf("row = %+v", r)
	}
}

func TestRecordHistoryOff(t *testing.T) {
	tr, _, conn := newTracker(t, noMin())
	ctx := context.Background()
	id, err := tr.Begin(ctx, Start{UserID: 2, UserName: "bob", RecordHistory: false, Item: movie(1, "Heat"), Stream: Stream{Mode: "direct"}})
	if err != nil {
		t.Fatal(err)
	}
	if live, _ := tr.Live(Viewer{Admin: true}); len(live) != 1 {
		t.Error("an unrecorded play is missing from Now playing")
	}
	if _, ok := readRow(t, conn, id); ok {
		t.Error("an unrecorded play was written")
	}
}

func TestEvents(t *testing.T) {
	tr, c, _ := newTracker(t, noMin())
	ch, cancel := tr.bus.Subscribe()
	defer cancel()
	play(t, tr, c, playOpts{user: 2, item: movie(1, "Heat"), length: 20 * time.Second})
	var kinds []string
	for len(ch) > 0 {
		e := <-ch
		// Admins get them from any network; other users only their own.
		if e.To == nil || e.To.UserID != 2 || !e.For(2, false, false) || e.For(1, false, true) || !e.For(1, true, true) || !e.For(1, true, false) {
			t.Errorf("%s audience = %+v", e.Type, e.To)
		}
		kinds = append(kinds, e.Type)
	}
	if len(kinds) < 3 || kinds[0] != "activity.started" || kinds[len(kinds)-1] != "activity.stopped" {
		t.Errorf("events = %v", kinds)
	}
}

func TestDeviceFrom(t *testing.T) {
	for ua, want := range map[string]string{
		"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36":                   "Chrome on macOS",
		"Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1": "Safari on iPhone",
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0":           "Edge on Windows",
		"Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0":                                                                  "Firefox on Linux",
		"": "Unknown device",
	} {
		if d := DeviceFrom("", "", "", "", "", ua); d.Name != want || d.Key == "" {
			t.Errorf("%q -> %+v, want %s", ua, d, want)
		}
	}
	if d := DeviceFrom("abc", "Living room", "WatchMyMedia Web", "", "1.0", "Mozilla/5.0 (Macintosh)"); d.Key != "abc" || d.Name != "Living room" || d.Platform != "macOS" {
		t.Errorf("client-named device = %+v", d)
	}
}

func mustAddr(s string) netip.Addr { return netip.MustParseAddr(s) }

// A heartbeat's write that lands after its play finished leaves the
// finished row as it is.
func TestLateWriteLeavesFinishedPlay(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := t.Context()
	id := play(t, tr, c, playOpts{user: 1, item: movie(1, "Heat"), length: 10 * time.Minute})
	done, ok := readRow(t, conn, id)
	if !ok || done.state != "stopped" || !done.stopped.Valid {
		t.Fatalf("finished play %+v", done)
	}
	// The snapshot a heartbeat took before the play stopped.
	tr.write(ctx, values{row: done.id, state: StatePlaying, lastSeen: c.now(), playedMs: 1})
	if after, _ := readRow(t, conn, id); after != done {
		t.Errorf("late write changed the finished play:\n%+v\n%+v", done, after)
	}
}
