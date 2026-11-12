package segments

import (
	"context"
	"log/slog"
	"path/filepath"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/playback"
)

// A library change while a run is in progress is not lost: Run keeps
// reading the bus (which drops events for a subscriber that falls behind)
// and runs again Delay after the run ends, not at the next 6-hour tick.
func TestDetectorRunsAgainAfterChangesDuringARun(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		bus := events.NewBus()
		d := NewDetector(nil, nil, bus, Decoder{}, slog.New(slog.DiscardHandler))
		d.Delay, d.Every = time.Minute, 6*time.Hour
		var runs atomic.Int32
		release := make(chan struct{})
		d.runOnce = func(ctx context.Context) error {
			runs.Add(1)
			select {
			case <-release:
			case <-ctx.Done():
			}
			return nil
		}
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() {
			d.Run(ctx)
			close(done)
		}()
		time.Sleep(time.Minute)
		synctest.Wait()
		if n := runs.Load(); n != 1 {
			t.Fatalf("runs after Delay = %d", n)
		}
		// More events than the bus holds for a subscriber, then the change,
		// as a scan sends them: a few at a time.
		for i := range 100 {
			bus.Publish(events.Event{Type: "scan.progress"})
			if i%10 == 9 {
				synctest.Wait()
			}
		}
		bus.Publish(events.Event{Type: "match.finished"})
		synctest.Wait()
		release <- struct{}{}
		synctest.Wait()
		if n := runs.Load(); n != 1 {
			t.Fatalf("ran again before Delay: %d", n)
		}
		time.Sleep(time.Minute)
		synctest.Wait()
		if n := runs.Load(); n != 2 {
			t.Errorf("runs after a change during the first = %d, want 2", n)
		}
		cancel()
		<-done
		// Nothing changed during the second run: no third.
		if n := runs.Load(); n != 2 {
			t.Errorf("runs = %d", n)
		}
	})
}

// Analysed is true for the version of the file and the algorithm that
// Replace recorded, and false once either changes.
func TestAnalysed(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	conn := dbtest.OpenPath(t, filepath.Join(t.TempDir(), "t.db"))
	q := db.New(conn)
	lib, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/tv"})
	now := db.FormatTime(time.Now())
	f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: "/tv/a.mkv", Size: 10, Mtime: now, AddedAt: now, Parsed: "{}", LastSeenAt: now, SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	v := FileVersion{ID: f.ID, Mtime: now, Size: 10}
	if Analysed(ctx, q, v, "chapter", 1) {
		t.Fatal("analysed before any run")
	}
	if err := Replace(ctx, conn, q, v, "chapter", 1, []playback.Segment{{Kind: "intro", StartMs: 0, EndMs: 30_000}}); err != nil {
		t.Fatal(err)
	}
	for name, c := range map[string]struct {
		v      FileVersion
		source string
		algo   int64
		want   bool
	}{
		"same":            {v, "chapter", 1, true},
		"newer algorithm": {v, "chapter", 2, false},
		"other source":    {v, "fingerprint", 1, false},
		"file changed":    {FileVersion{ID: f.ID, Mtime: now, Size: 11}, "chapter", 1, false},
		"file rewritten":  {FileVersion{ID: f.ID, Mtime: "2020-01-01T00:00:00Z", Size: 10}, "chapter", 1, false},
	} {
		if got := Analysed(ctx, q, c.v, c.source, c.algo); got != c.want {
			t.Errorf("%s: %v, want %v", name, got, c.want)
		}
	}
	if rows, _ := q.ListFileSegments(ctx, f.ID); len(rows) != 1 || rows[0].Kind != "intro" {
		t.Errorf("stored = %+v", rows)
	}
}
