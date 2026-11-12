package metadata

import (
	"testing"
	"time"

	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/tmdb"
)

// A scan that added or changed files leaves library.changed to matching, so
// the run sends it once it is done: after matching, with nothing to match
// (a file replaced in place), and with nothing matched.
func TestScanTriggeredRunAnnounces(t *testing.T) {
	e := setup(t)
	e.m.opts.StartupDelay = time.Millisecond
	ctx := t.Context()
	ch, cancel := e.bus.Subscribe()
	defer cancel()
	go e.m.Run(ctx)
	// Let the startup run go by first.
	waitEvent(t, ch, "match.finished")

	for _, c := range []struct {
		name string
		add  func()
	}{
		{"matched", func() {
			e.file(e.mov, "/m/The Matrix (1999).mkv", `{"kind":"movie","title":"The Matrix","year":1999}`)
		}},
		{"nothing pending", func() {}},
		{"unmatched", func() {
			e.file(e.mov, "/m/Unknown Thing (2001).mkv", `{"kind":"movie","title":"Unknown Thing","year":2001}`)
		}},
	} {
		c.add()
		e.bus.Publish(events.Event{Type: "scan.finished", Data: scanner.ScanFinished{LibraryID: e.mov.ID, Added: 1}})
		if ev := waitEvent(t, ch, "library.changed"); ev.Data.(scanner.LibraryChanged).LibraryID != e.mov.ID {
			t.Errorf("%s: library.changed for %+v", c.name, ev.Data)
		}
	}
}

func waitEvent(t *testing.T, ch <-chan events.Event, kind string) events.Event {
	t.Helper()
	deadline := time.After(5 * time.Second)
	for {
		select {
		case e := <-ch:
			if e.Type == kind {
				return e
			}
		case <-deadline:
			t.Fatalf("no %s", kind)
		}
	}
}

// Without a TMDB key the scan announces the change itself, and matching
// does not announce it a second time.
func TestScanAnnouncedNotRepeated(t *testing.T) {
	e := setup(t)
	m := New(e.q, tmdb.New("", "http://127.0.0.1:0"), e.bus, e.m.log, Options{Workers: 1})
	ctx := t.Context()
	ch, cancel := e.bus.Subscribe()
	defer cancel()
	go m.Run(ctx)
	e.bus.Publish(events.Event{Type: "scan.finished", Data: scanner.ScanFinished{LibraryID: e.mov.ID, Added: 1, Announced: true}})
	deadline := time.After(500 * time.Millisecond)
	for {
		select {
		case ev := <-ch:
			if ev.Type == "library.changed" {
				t.Fatal("library.changed sent again by matching")
			}
		case <-deadline:
			return
		}
	}
}
