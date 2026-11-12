package integrations

import (
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations/arr"
	"watchmymedia/server/internal/integrations/seerr"
)

// A Sonarr that doesn't answer costs a page one stateWait, not one for each
// call it makes, and then nothing until stateFresh has passed; once a check
// of it has failed, pages don't ask it at all.
func TestSlowAppCostsAPageOneWait(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		var calls atomic.Int32
		app := httptest.NewTestServer(t, http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
			calls.Add(1)
			select { // longer than any page waits
			case <-r.Context().Done():
			case <-time.After(time.Minute):
			}
		}))
		hc := app.Client() // which sets URL
		cl, err := arr.New(arr.Sonarr, app.URL, "key", hc)
		if err != nil {
			t.Fatal(err)
		}
		s := New(nil, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
		lib := filepath.FromSlash("/tv")
		now := time.Now()
		c := &conn{row: db.Integration{ID: 1, Name: "Sonarr"}, arr: cl, status: Status{OK: true, CheckedAt: &now},
			idx: &index{series: map[int]arr.Series{5: {ID: 5, TMDBID: 800001, Path: "/tv/Harbor Lights"}},
				byFolder: map[string]int{}, byTMDB: map[int][]int{800001: {5}}}}
		s.conns[1] = c
		page := func() time.Duration {
			t.Helper()
			start := time.Now()
			if st := s.Series(t.Context(), Item{LibraryPath: lib, TMDBID: 800001}); st == nil {
				t.Fatal("no Sonarr state for the show")
			}
			return time.Since(start)
		}

		if d := page(); d != stateWait {
			t.Errorf("first page waited %v, want one stateWait (%v)", d, stateWait)
		}
		if n := calls.Load(); n != 2 {
			t.Errorf("calls = %d, want the episodes and the queue", n)
		}
		if d := page(); d != 0 || calls.Load() != 2 {
			t.Errorf("second page waited %v after %d calls", d, calls.Load())
		}
		time.Sleep(stateFresh)
		if d := page(); d != stateWait || calls.Load() != 4 {
			t.Errorf("page after stateFresh waited %v after %d calls", d, calls.Load())
		}

		s.mu.Lock()
		c.status.OK, c.down = false, errors.New("Sonarr timed out")
		s.mu.Unlock()
		time.Sleep(stateFresh)
		if d := page(); d != 0 || calls.Load() != 4 {
			t.Errorf("page of an app whose check failed waited %v after %d calls", d, calls.Load())
		}
		select {
		case id := <-s.refresh:
			if id != 1 {
				t.Errorf("refresh asked for %d", id)
			}
		default:
			t.Error("the app was not checked again")
		}
	})
}

// The same for Seerr's record of an item, which every item on a request
// page asks for.
func TestSlowSeerrCostsAPageOneWait(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		var calls atomic.Int32
		app := httptest.NewTestServer(t, http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
			calls.Add(1)
			select {
			case <-r.Context().Done():
			case <-time.After(time.Minute):
			}
		}))
		hc := app.Client() // which sets URL
		s := New(nil, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
		now := time.Now()
		c, err := s.newConn(db.Integration{ID: 2, Kind: "seerr", Name: "Seerr", Url: app.URL, PathMap: "[]", Targets: "[]"})
		if err != nil {
			t.Fatal(err)
		}
		c.seerr = seerr.New(app.URL, "key", hc)
		c.status = Status{OK: true, CheckedAt: &now}
		s.conns[2] = c
		lookup := func() time.Duration {
			start := time.Now()
			if _, err := s.seerrMedia(t.Context(), c, "movie", 700001); err == nil {
				t.Error("a lookup that timed out worked")
			}
			return time.Since(start)
		}
		if d := lookup(); d != stateWait {
			t.Errorf("first lookup waited %v", d)
		}
		if d := lookup(); d != 0 || calls.Load() != 1 {
			t.Errorf("second lookup waited %v after %d calls", d, calls.Load())
		}
		time.Sleep(stateFresh)
		if d := lookup(); d != stateWait || calls.Load() != 2 {
			t.Errorf("lookup after stateFresh waited %v after %d calls", d, calls.Load())
		}
	})
}
