package metadata

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/tmdb"
)

// setupBusy is setupWith for a database another writer holds: the matcher
// writes through a connection that waits 10 ms for the write lock where the
// server's waits 5 s, so a test of what happens when that runs out takes
// milliseconds. The test's own reads and writes, and the lock it holds, go
// through the usual connection.
func setupBusy(t *testing.T, client *tmdb.Client, opts Options) env {
	t.Helper()
	path := filepath.Join(t.TempDir(), "t.db")
	conn := dbtest.OpenPath(t, path)
	quick, err := db.OpenBusy(path, 10*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = quick.Close() })
	ctx := context.Background()
	q := db.New(conn)
	mov, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"})
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	bus := events.NewBus()
	m := New(db.New(quick), client, bus, slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError})), opts)
	file := func(lib db.Library, path, parsed string) db.MediaFile {
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: path, Size: 1, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
			Parsed: parsed, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	return env{m: m, q: q, conn: conn, bus: bus, mov: mov, tv: tv, ctx: ctx, file: file}
}

// hold takes the database's write lock, as a scan or an import writing a
// long batch does, and returns what lets go of it. Letting go twice is
// letting go once.
func (e env) hold(t *testing.T) (release func()) {
	t.Helper()
	tx, err := e.conn.BeginTx(e.ctx, &sql.TxOptions{})
	if err != nil {
		t.Fatal(err)
	}
	var once sync.Once
	release = func() { once.Do(func() { _ = tx.Rollback() }) }
	t.Cleanup(release)
	return release
}

// state is a file's match status and error as stored now.
func (e env) state(t *testing.T, f db.MediaFile) (status, matchError string) {
	t.Helper()
	row, err := e.q.GetMediaFile(e.ctx, f.ID)
	if err != nil {
		t.Fatal(err)
	}
	return row.MatchStatus, deref(row.MatchError)
}

// busyFiles are three episodes of three shows the fake TMDB knows, each
// needing its show stored and its own result recorded.
func busyFiles(e env) (files map[string]db.MediaFile, want map[string]int64) {
	return map[string]db.MediaFile{
		"conners":  e.addFile(e.tv, "The Connors/The.Conners.S01E02.720p.HDTV.x264-AVS.mkv"),
		"rivals":   e.addFile(e.tv, "Rivals (2023)/Rivals.2024.S01E01.1080p.WEB.H264.mkv"),
		"crashing": e.addFile(e.tv, "Crashing US/Crashing.S02E02.720p.HDTV.x264-BATV.mkv"),
	}, map[string]int64{"conners": 500, "rivals": 501, "crashing": 502}
}

// TestMatchWaitsOutALockedDatabase holds the database for longer than a
// statement waits and lets go when the matcher's first write has found it
// locked. Before, that write's file was recorded as failed with "database
// is locked" as the reason TMDB gave, and waited a day; now the write is
// tried again and every file is matched.
func TestMatchWaitsOutALockedDatabase(t *testing.T) {
	t.Parallel()
	e := setupBusy(t, newSearchTMDB(t).Client(), Options{Workers: 2, BusyWait: time.Minute})
	files, want := busyFiles(e)
	release := e.hold(t)
	var found atomic.Int32
	e.m.onBusy = func() {
		found.Add(1)
		release()
	}
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Matched != len(files) || sum.Failed != 0 || sum.Unmatched != 0 {
		t.Errorf("summary = %+v, want every file matched", sum)
	}
	for name, f := range files {
		if got := e.showOf(t, f); got != want[name] {
			status, matchError := e.state(t, f)
			t.Errorf("%s: show %d, want %d (%s %q)", name, got, want[name], status, matchError)
		}
	}
	if found.Load() == 0 {
		t.Error("no write found the database locked: the test held nothing")
	}
}

// TestMatchLeavesFilesForTheNextRun keeps the database locked for the whole
// run, longer than the matcher goes on trying. Nothing can be written, so
// each file stays as it was, waiting for its match, and the run counts it
// as failed; the next run, with the database free, matches them all.
func TestMatchLeavesFilesForTheNextRun(t *testing.T) {
	t.Parallel()
	e := setupBusy(t, newSearchTMDB(t).Client(), Options{Workers: 2, BusyWait: 30 * time.Millisecond})
	files, want := busyFiles(e)
	release := e.hold(t)
	var gaveUp atomic.Int32
	e.m.onGiveUp = func() { gaveUp.Add(1) }
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Failed != len(files) || sum.Matched != 0 || sum.Unmatched != 0 {
		t.Errorf("summary = %+v, want every file failed", sum)
	}
	if n := int(gaveUp.Load()); n != len(files) {
		t.Errorf("gave up on %d writes, want one for each of %d files", n, len(files))
	}
	for name, f := range files {
		if status, matchError := e.state(t, f); status != StatusPending || matchError != "" {
			t.Errorf("%s is %s %q after a run that could write nothing, want it still pending", name, status, matchError)
		}
	}

	// The next run waits as the server's does: its two workers hold the
	// database from each other for longer than this test's 30 ms when the
	// race detector slows them.
	release()
	e.m.opts.BusyWait = time.Minute
	sum, err = e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Matched != len(files) || sum.Failed != 0 {
		t.Errorf("the next run's summary = %+v, want every file matched", sum)
	}
	for name, f := range files {
		if got := e.showOf(t, f); got != want[name] {
			status, matchError := e.state(t, f)
			t.Errorf("%s after the next run: show %d, want %d (%s %q)", name, got, want[name], status, matchError)
		}
	}
}

// TestMatchNotesWhyAFileWaits lets go of the database only once the
// matcher has given up on a file. The file is still waiting for its match,
// and now says why; the next run matches it and the note goes.
func TestMatchNotesWhyAFileWaits(t *testing.T) {
	t.Parallel()
	e := setupBusy(t, newSearchTMDB(t).Client(), Options{Workers: 1, BusyWait: 30 * time.Millisecond})
	f := e.addFile(e.tv, "Rivals (2023)/Rivals.2024.S01E01.1080p.WEB.H264.mkv")
	e.m.onGiveUp = e.hold(t)
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Failed != 1 || sum.Matched != 0 {
		t.Errorf("summary = %+v, want the one file failed", sum)
	}
	if status, matchError := e.state(t, f); status != StatusPending || matchError != BusyNote {
		t.Errorf("the file is %s %q, want pending with the note", status, matchError)
	}

	e.m.onGiveUp, e.m.opts.BusyWait = nil, time.Minute
	if sum, err = e.m.MatchLibrary(e.ctx, e.tv, false); err != nil || sum.Matched != 1 {
		t.Fatalf("the next run: %+v, %v", sum, err)
	}
	if status, matchError := e.state(t, f); status != StatusMatched || matchError != "" || e.showOf(t, f) != 501 {
		t.Errorf("after the next run the file is %s %q, show %d", status, matchError, e.showOf(t, f))
	}
}

// TestMatchWriteStopsWithItsContext checks that a write waiting for a
// locked database ends when its run does, with the run's error, not a
// minute later.
func TestMatchWriteStopsWithItsContext(t *testing.T) {
	t.Parallel()
	e := setupBusy(t, newSearchTMDB(t).Client(), Options{Workers: 1, BusyWait: time.Minute})
	e.hold(t)
	ctx, cancel := context.WithCancel(e.ctx)
	defer cancel()
	e.m.onBusy = cancel
	ran := 0
	err := e.m.write(ctx, func(*db.Queries) error {
		ran++
		return nil
	})
	if !errors.Is(err, context.Canceled) || ran != 0 {
		t.Errorf("write returned %v after running %d times, want the context's error and no run", err, ran)
	}
}
