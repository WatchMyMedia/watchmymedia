package guide

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// TestLineupRefreshDuringSaveDoesNotDeadlock drives the three locks the
// server wires together: a tuner refresh (livetv.Service.mu) tells the
// listings its lineup changed (Service.mu); a stored fetch, under
// Service.mu, has the recorder reconcile, and a recording that starts
// meanwhile takes the tuner lock (ProtectRecording). The OnSaved hook here
// stands in for the recorder: it starts a refresh, waits until the refresh
// is reading the lineup with the tuner lock held, then takes the tuner lock
// as a starting recording does. While Refresh told the listings under its
// lock, each side waited on the other's lock for good.
func TestLineupRefreshDuringSaveDoesNotDeadlock(t *testing.T) {
	var (
		mu      sync.Mutex
		armed   bool
		reading = make(chan struct{})
	)
	serve := func(h http.Handler) *httptest.Server {
		return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/lineup.json" {
				mu.Lock()
				signal := armed
				armed = false
				mu.Unlock()
				if signal {
					close(reading)
				}
			}
			h.ServeHTTP(w, r)
		}))
	}
	e := newEnv(t, serve)
	e.live.OnLineup(e.g.LineupChanged)
	sources, err := e.live.Sources(e.ctx)
	if err != nil || len(sources) != 1 {
		t.Fatalf("sources=%v err=%v", sources, err)
	}
	refreshed := make(chan error, 1)
	e.g.OnSaved(func(ctx context.Context) {
		mu.Lock()
		armed = true
		mu.Unlock()
		go func() {
			_, err := e.live.Refresh(ctx, sources[0].ID)
			refreshed <- err
		}()
		<-reading
		// The refresh now holds the tuner lock and will tell the listings,
		// whose lock this fetch holds.
		e.live.ProtectRecording("dvr:test")
	})
	e.on()
	fetched := make(chan struct{})
	go func() { e.g.fetch(e.ctx); close(fetched) }()
	timeout := time.After(10 * time.Second)
	select {
	case <-fetched:
	case <-timeout:
		t.Fatal("listings fetch, tuner refresh and recorder deadlocked")
	}
	select {
	case err := <-refreshed:
		if err != nil {
			t.Fatal(err)
		}
	case <-timeout:
		t.Fatal("tuner refresh never finished")
	}
	if st := e.status(); st.LastSuccessAt == nil {
		t.Fatalf("fetch did not succeed: %+v", st)
	}
}
