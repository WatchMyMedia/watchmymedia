package plexsync

import (
	"fmt"
	"strconv"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex/plextest"
)

// countWrites puts a trigger on every ordinary table that counts the rows a
// statement inserts, updates or deletes, by table, so a test can see what a
// run wrote. writes reads the counts and resets them.
func (e *env) countWrites() {
	e.t.Helper()
	ctx := e.t.Context()
	if _, err := e.conn.ExecContext(ctx, "CREATE TABLE test_writes(tbl TEXT PRIMARY KEY, n INTEGER NOT NULL)"); err != nil {
		e.t.Fatal(err)
	}
	var names string
	if err := e.conn.QueryRowContext(ctx, "SELECT group_concat(name,',') FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'test_writes' AND sql NOT LIKE 'CREATE VIRTUAL%'").Scan(&names); err != nil {
		e.t.Fatal(err)
	}
	for table := range strings.SplitSeq(names, ",") {
		for _, op := range []string{"INSERT", "UPDATE", "DELETE"} {
			stmt := fmt.Sprintf("CREATE TRIGGER test_writes_%s_%s AFTER %s ON %q BEGIN INSERT INTO test_writes(tbl,n) VALUES(%s,1) ON CONFLICT(tbl) DO UPDATE SET n=n+1; END", table, strings.ToLower(op), op, table, "'"+table+"'")
			// A table that refuses a trigger (a full-text index's own) is skipped.
			_, _ = e.conn.ExecContext(ctx, stmt)
		}
	}
}

func (e *env) writes() (int, string) {
	e.t.Helper()
	ctx := e.t.Context()
	var total int
	var detail string
	if err := e.conn.QueryRowContext(ctx, "SELECT COALESCE(SUM(n),0),COALESCE(group_concat(tbl||'='||n,' '),'') FROM (SELECT tbl,n FROM test_writes ORDER BY tbl)").Scan(&total, &detail); err != nil {
		e.t.Fatal(err)
	}
	if _, err := e.conn.ExecContext(ctx, "DELETE FROM test_writes"); err != nil {
		e.t.Fatal(err)
	}
	return total, detail
}

// scheduledRun starts the connection's next scheduled sync now and runs it.
func (e *env) scheduledRun(c PlexConnection) PlexRun {
	e.t.Helper()
	ctx := e.t.Context()
	if _, err := e.conn.ExecContext(ctx, "UPDATE plex_connections SET next_sync_at='' WHERE id=?", c.ID); err != nil {
		e.t.Fatal(err)
	}
	if err := e.svc.tick(ctx); err != nil {
		e.t.Fatal(err)
	}
	runs, err := e.svc.Runs(ctx, c.ID)
	if err != nil || len(runs) == 0 {
		e.t.Fatalf("runs %v %v", runs, err)
	}
	return runs[0]
}

// loadLibrary gives the movies library and the owner's Plex section n
// matched, watched movies, plus one unmatched one, and connects with sync
// on after a first, manual import.
func loadLibrary(t *testing.T, n int) (*env, PlexConnection, []string) {
	t.Helper()
	e := newEnv(t)
	items := make([]string, n)
	if err := e.q.Transaction(t.Context(), func(q *db.Queries) error {
		for i := range items {
			tmdb := int64(2000000 + i)
			if _, err := q.UpsertMovie(t.Context(), db.UpsertMovieParams{LibraryID: e.movies, TmdbID: tmdb, Title: fmt.Sprintf("Fictional load film %d", i), Genres: "[]", Credits: "[]"}); err != nil {
				return err
			}
			items[i] = plextest.MovieJSON(strconv.Itoa(3000000+i), fmt.Sprintf("Fictional load film %d", i), strconv.FormatInt(tmdb, 10), "", 1)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	// Watched in Plex, not in this library yet.
	items = append(items, plextest.MovieJSON("4000000", "Fictional missing film", "2999999", "", 1))
	e.f.SetItems("owner", "1", items...)
	c := e.connect()
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	first := e.tickRun(c, e.previewRun(c, true))
	if first.Status != "complete" || int(first.Counts.Marked) != n || first.Counts.Unmatched != 1 {
		t.Fatalf("first import %+v", first)
	}
	return e, c, items
}

// A scheduled sync with nothing changed in Plex or here writes next to
// nothing: the run's own row and a history checkpoint, not a row per item.
func TestScheduledSyncWithNothingChangedWritesAlmostNothing(t *testing.T) {
	e, c, _ := loadLibrary(t, 300)
	e.countWrites()
	r := e.scheduledRun(c)
	n, detail := e.writes()
	t.Logf("no-change scheduled run wrote %d rows: %s", n, detail)
	if r.Status != "complete" || r.Counts.Marked != 0 || r.Counts.AlreadyWatched != 300 || r.Counts.Unmatched != 1 {
		t.Fatalf("scheduled run %+v", r)
	}
	if n > 30 {
		t.Fatalf("a scheduled run with nothing changed wrote %d rows: %s", n, detail)
	}
	// The identities are read by the primary key, without statistics.
	var a, b, d int
	var plan string
	if err := e.conn.QueryRowContext(t.Context(), "EXPLAIN QUERY PLAN SELECT remote_key,fingerprint FROM plex_source_items WHERE machine_identifier=? AND source_epoch=? AND user_id=? AND section_id=?", "m", "e", e.admin, 1).Scan(&a, &b, &d, &plan); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(plan, "SEARCH plex_source_items USING INDEX sqlite_autoindex_plex_source_items_1 (machine_identifier=? AND source_epoch=? AND user_id=? AND section_id=?)") {
		t.Fatalf("plan %s", plan)
	}
}

// One item watched in Plex since the last run is the one item written.
func TestScheduledSyncWritesOnlyTheChangedItem(t *testing.T) {
	e, c, items := loadLibrary(t, 300)
	ctx := t.Context()
	// Watched here before; now unwatched in Plex and here, then watched in
	// Plex again: the next run marks it.
	id, err := e.q.MatchMovieByTMDB(ctx, db.MatchMovieByTMDBParams{LibraryID: e.movies, TmdbID: 2000007})
	if err != nil || len(id) != 1 {
		t.Fatal(id, err)
	}
	if _, err := e.conn.ExecContext(ctx, "DELETE FROM watch_state WHERE item_id=?; DELETE FROM watch_guards WHERE item_id=?", id[0], id[0]); err != nil {
		t.Fatal(err)
	}
	// And a film the library gained, which Plex had watched all along.
	if _, err := e.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: e.movies, TmdbID: 2999999, Title: "Fictional missing film", Genres: "[]", Credits: "[]"}); err != nil {
		t.Fatal(err)
	}
	e.f.SetItems("owner", "1", items...)
	e.countWrites()
	r := e.scheduledRun(c)
	n, detail := e.writes()
	t.Logf("scheduled run with two changes wrote %d rows: %s", n, detail)
	if r.Status != "complete" || r.Counts.Marked != 2 || r.Counts.AlreadyWatched != 299 || r.Counts.Unmatched != 0 {
		t.Fatalf("scheduled run %+v", r)
	}
	if e.watchRow(e.admin, id[0]) == nil {
		t.Fatal("changed item not marked")
	}
	if n > 40 {
		t.Fatalf("a scheduled run with two changes wrote %d rows: %s", n, detail)
	}
}

// A manual import still goes through every item, as the first one did.
func TestManualImportStillChecksEveryItem(t *testing.T) {
	e, c, _ := loadLibrary(t, 300)
	e.countWrites()
	r := e.tickRun(c, e.previewRun(c, false))
	n, detail := e.writes()
	t.Logf("manual run wrote %d rows: %s", n, detail)
	if r.Status != "complete" || r.Counts.AlreadyWatched != 300 || r.Counts.Unmatched != 1 {
		t.Fatalf("manual run %+v", r)
	}
	if n < 301 {
		t.Fatalf("manual import skipped items: %d writes", n)
	}
}

// TestScheduledSyncNoChangeCost times a scheduled sync of 10,000 matched,
// already imported items with nothing changed. Diagnostic: it logs the time
// and fails only on the writes, not on the clock.
func TestScheduledSyncNoChangeCost(t *testing.T) {
	if testing.Short() {
		t.Skip("10,000-item scheduled sync; run without -short")
	}
	e, c, _ := loadLibrary(t, 10000)
	e.countWrites()
	if _, err := e.conn.ExecContext(t.Context(), "CREATE TABLE test_probe(at TEXT)"); err != nil {
		t.Fatal(err)
	}
	// Another writer, as a play's progress report would be: the longest
	// it waits is about the longest the run holds the write lock.
	stop, probed := make(chan struct{}), make(chan time.Duration)
	go func() {
		longest := time.Duration(0)
		defer func() { probed <- longest }()
		for {
			select {
			case <-stop:
				return
			case <-time.After(5 * time.Millisecond):
			}
			at := time.Now()
			if _, err := e.conn.ExecContext(t.Context(), "INSERT INTO test_probe(at) VALUES('x')"); err != nil {
				return
			}
			longest = max(longest, time.Since(at))
		}
	}()
	start := time.Now()
	r := e.scheduledRun(c)
	took := time.Since(start)
	close(stop)
	longest := <-probed
	n, detail := e.writes()
	t.Logf("10,000 items, nothing changed: scheduled run took %s, another writer waited at most %s, and the run wrote %d rows: %s", took, longest, n, detail)
	if r.Status != "complete" || r.Counts.AlreadyWatched != 10000 {
		t.Fatalf("scheduled run %+v", r)
	}
	if n > 30 {
		t.Fatalf("wrote %d rows", n)
	}
}

// Plex refreshing one film's metadata (here, finding its IMDb ID) changes
// that item's identity. The item is skipped and counted unavailable; every
// other item still syncs and the connection is not paused.
func TestOneItemChangedInPlexIsSkippedNotPausingTheConnection(t *testing.T) {
	e, c, items := loadLibrary(t, 20)
	ctx := t.Context()
	items[5] = plextest.MovieJSON("3000005", "Fictional load film 5", "2000005", "tt2000005", 1)
	e.f.SetItems("owner", "1", items...)
	// And one item that does need marking, to show the rest went ahead.
	id, err := e.q.MatchMovieByTMDB(ctx, db.MatchMovieByTMDBParams{LibraryID: e.movies, TmdbID: 2000007})
	if err != nil || len(id) != 1 {
		t.Fatal(id, err)
	}
	if _, err := e.conn.ExecContext(ctx, "DELETE FROM watch_state WHERE item_id=?; DELETE FROM watch_guards WHERE item_id=?", id[0], id[0]); err != nil {
		t.Fatal(err)
	}
	r := e.scheduledRun(c)
	if r.Status != "complete" || r.Counts.Unavailable != 1 || r.Counts.Marked != 1 || r.Counts.AlreadyWatched != 18 {
		t.Fatalf("scheduled run %+v", r)
	}
	if e.watchRow(e.admin, id[0]) == nil {
		t.Fatal("the rest of the library was not synced")
	}
	saved, err := e.q.GetPlexConnection(ctx, c.ID)
	if err != nil || saved.SyncEnabled != 1 || saved.Problem != "" {
		t.Fatalf("connection paused for one item: %+v %v", saved, err)
	}
	if !strings.Contains(e.logs.String(), "changed in Plex") {
		t.Fatal("skipped item not reported")
	}
	// It stays skipped, and does not pause later runs either.
	r = e.scheduledRun(c)
	if r.Status != "complete" || r.Counts.Unavailable != 1 || r.Counts.AlreadyWatched != 19 {
		t.Fatalf("next scheduled run %+v", r)
	}
}

// When most of the items Plex names have become other items, the server was
// rebuilt or restored: the connection pauses as before, writing nothing.
func TestMostItemsChangedInPlexStillPausesTheConnection(t *testing.T) {
	e, c, items := loadLibrary(t, 20)
	for i := range 20 {
		items[i] = plextest.MovieJSON(strconv.Itoa(3000000+i), "Fictional other film", strconv.Itoa(2500000+i), "", 1)
	}
	e.f.SetItems("owner", "1", items...)
	r := e.scheduledRun(c)
	if r.Status != "failed" || r.Reason != "source_identity_changed" {
		t.Fatalf("scheduled run %+v", r)
	}
	saved, err := e.q.GetPlexConnection(t.Context(), c.ID)
	if err != nil || saved.SyncEnabled != 0 || saved.Problem != "source_identity_changed" {
		t.Fatalf("rebuilt source did not pause: %+v %v", saved, err)
	}
}

// The weekly full pass over history reads events imported before. One whose
// film Plex has since refreshed is skipped, not a reason to pause.
func TestHistoryEventWhoseItemChangedIsSkipped(t *testing.T) {
	e, c := runEnv(t)
	e.seedHistory()
	r := e.tickRun(c, e.previewRun(c, true))
	if r.Status != "complete" || r.Counts.HistoryImported != 1 {
		t.Fatalf("first run %+v", r)
	}
	e.f.Set(func(f *plextest.Fake) {
		ev := f.HistoryEvents["owner"]["1"][0]
		ev.Item.GUID = "plex://movie/fictional-beacon"
		f.HistoryEvents["owner"]["1"][0] = ev
	})
	now := e.svc.now().Add(8 * 24 * time.Hour)
	e.svc.now = func() time.Time { return now }
	r = e.scheduledRun(c)
	if r.Status != "complete" || r.Counts.HistorySkipped != 1 || r.Counts.HistoryImported != 0 {
		t.Fatalf("weekly pass %+v", r)
	}
	saved, err := e.q.GetPlexConnection(t.Context(), c.ID)
	if err != nil || saved.SyncEnabled != 1 || saved.Problem != "" {
		t.Fatalf("connection paused for one history event: %+v %v", saved, err)
	}
}
