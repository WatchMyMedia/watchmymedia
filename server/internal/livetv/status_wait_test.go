package livetv

import (
	"context"
	"net/http/httptest"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/settings"
)

// A status read that waits returns as soon as the session plays, fails or
// stops, else when its wait (at most MaxStatusWait) runs out; without a
// wait it answers at once. It runs on a synctest bubble's clock, over an
// in-memory tuner, so the waits take no time.
func TestStatusWaitReturnsWhenStartingEnds(t *testing.T) {
	t.Parallel()
	synctest.Test(t, func(t *testing.T) {
		_, q := dbtest.Open(t)
		tuner := httptest.NewTestServer(t, hdhrtest.New(nil))
		t.Cleanup(tuner.Close)
		service := New(q, settings.New(q), func(a string) (*hdhomerun.Client, error) {
			return hdhomerun.New(a, hdhrtest.Transport(tuner.URL, tuner.Client().Transport))
		})
		if _, err := service.Add(t.Context(), "tuner.test", ""); err != nil {
			t.Fatal(err)
		}
		sources, err := q.ListLiveSources(t.Context())
		if err != nil || len(sources) != 1 {
			t.Fatalf("sources = %+v, %v", sources, err)
		}
		channels, err := service.Channels(t.Context(), false)
		if err != nil {
			t.Fatal(err)
		}
		owner := Owner{UserID: 1, SignInID: "signin-1", Name: "Viewer", Admin: true}
		m := NewSessions(service, nil, SessionOptions{Dir: t.TempDir(), Validate: func(context.Context, string) (Owner, error) { return owner, nil }})
		t.Cleanup(m.Close)
		// The sessions below have no run of their own to end them on Close.
		t.Cleanup(func() {
			m.mu.Lock()
			clear(m.items)
			m.mu.Unlock()
		})
		// starting puts a session that is tuning, as Start would leave it.
		starting := func(id string) *session {
			ctx, cancel := context.WithCancel(m.ctx)
			s := &session{owner: owner, sourceID: sources[0].ID, address: sources[0].Address, deviceID: sources[0].DeviceID,
				ctx: ctx, cancel: cancel, done: make(chan struct{}), ready: make(chan struct{}),
				view: SessionView{ID: id, Channel: channels[0], State: "starting", StartedAt: time.Now()}}
			m.mu.Lock()
			m.items[id] = s
			m.mu.Unlock()
			return s
		}
		read := func(id string, wait time.Duration) (SessionView, time.Duration) {
			t.Helper()
			start := time.Now()
			v, err := m.StatusWait(t.Context(), owner, id, wait)
			if err != nil {
				t.Fatalf("%s: %v", id, err)
			}
			return v, time.Since(start)
		}
		after := func(d time.Duration, f func()) { time.AfterFunc(d, f) }

		s := starting("unchanged")
		if v, took := read("unchanged", 0); v.State != "starting" || took != 0 {
			t.Errorf("no wait -> %s after %v, want starting at once", v.State, took)
		}
		if v, took := read("unchanged", 3*time.Second); v.State != "starting" || took != 3*time.Second {
			t.Errorf("3 s wait -> %s after %v", v.State, took)
		}
		if v, took := read("unchanged", time.Hour); v.State != "starting" || took != MaxStatusWait {
			t.Errorf("an hour's wait -> %s after %v, want the %v cap", v.State, took, MaxStatusWait)
		}

		after(2*time.Second, func() {
			m.mu.Lock()
			s.view.State = "playing"
			close(s.ready)
			m.mu.Unlock()
		})
		if v, took := read("unchanged", MaxStatusWait); v.State != "playing" || took != 2*time.Second {
			t.Errorf("played after 2 s -> %s after %v", v.State, took)
		}
		// A session past starting answers at once, wait or not.
		if v, took := read("unchanged", MaxStatusWait); v.State != "playing" || took != 0 {
			t.Errorf("playing -> %s after %v", v.State, took)
		}

		failing := starting("failing")
		after(time.Second, func() { m.fail(failing, "stream_stalled") })
		if v, took := read("failing", MaxStatusWait); v.State != "failed" || v.Reason != "stream_stalled" || took != time.Second {
			t.Errorf("failed after 1 s -> %s %s after %v", v.State, v.Reason, took)
		}
		stopping := starting("stopping")
		after(4*time.Second, func() { m.stop(stopping, "user") })
		if v, took := read("stopping", MaxStatusWait); v.State != "stopping" || took != 4*time.Second {
			t.Errorf("stopped after 4 s -> %s after %v", v.State, took)
		}

		// A player that goes away stops the wait, and is told what was known.
		starting("abandoned")
		ctx, cancel := context.WithCancel(t.Context())
		after(time.Second, cancel)
		start := time.Now()
		if v, err := m.StatusWait(ctx, owner, "abandoned", MaxStatusWait); err != nil || v.State != "starting" || time.Since(start) != time.Second {
			t.Errorf("caller gone -> %s %v after %v", v.State, err, time.Since(start))
		}
		// Another sign-in's session is not found, wait or not.
		other := owner
		other.SignInID = "signin-2"
		if _, err := m.StatusWait(t.Context(), other, "unchanged", MaxStatusWait); err == nil {
			t.Error("another sign-in read the session")
		}
	})
}
