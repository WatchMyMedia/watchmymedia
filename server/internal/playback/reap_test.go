package playback

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"testing/synctest"
	"time"
)

// fakeSession is a session with no ffmpeg behind it: stopping it closes
// done at once. A VOD one has a running run and one piece on disk.
func fakeSession(t *testing.T, m *Manager, id string, vod bool, last time.Time) *Session {
	t.Helper()
	done := make(chan struct{})
	s := &Session{ID: id, Dir: filepath.Join(t.TempDir(), id), done: done}
	s.cancel = sync.OnceFunc(func() { close(done) })
	s.last.Store(last.UnixNano())
	if err := os.MkdirAll(s.Dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if vod {
		v := &vodState{m: m, s: s, ctx: context.Background(), bounds: gridPlan(60e6, 6e6)}
		r := &vodRun{done: make(chan struct{})}
		r.cancel = sync.OnceFunc(func() { close(r.done) })
		piece := filepath.Join(s.Dir, "r1-00000.m4s")
		if err := os.WriteFile(piece, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
		v.run, v.pieces = r, []*vodPiece{{start: 0, end: 6e6, file: piece, run: r}}
		v.live.Store(1)
		s.vod = v
	}
	m.mu.Lock()
	m.sessions[id] = s
	m.mu.Unlock()
	return s
}

// hasRun reports whether the VOD session has a run, under its lock.
func hasRun(s *Session) bool {
	s.vod.mu.Lock()
	defer s.vod.mu.Unlock()
	return s.vod.run != nil
}

func registered(m *Manager, id string) bool {
	_, ok := m.Get(id)
	return ok
}

func eventually(t *testing.T, what string, ok func() bool) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); !ok(); {
		if time.Now().After(deadline) {
			t.Fatal(what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestReapOnce(t *testing.T) {
	t.Parallel()
	now := time.Now()
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{}, time.Minute, 8, slog.New(slog.DiscardHandler))
	fresh := fakeSession(t, m, "fresh", false, now.Add(-30*time.Second))
	idle := fakeSession(t, m, "idle", false, now.Add(-2*time.Minute))
	vodFresh := fakeSession(t, m, "vod-fresh", true, now.Add(-30*time.Second))
	vodIdle := fakeSession(t, m, "vod-idle", true, now.Add(-2*time.Minute))
	vodOld := fakeSession(t, m, "vod-old", true, now.Add(-VODKeep-time.Minute))
	// A session whose lock is held (a request reading, or a bug) is passed
	// over rather than waited for.
	vodBusy := fakeSession(t, m, "vod-busy", true, now.Add(-2*time.Minute))
	vodBusy.vod.mu.Lock()
	defer vodBusy.vod.mu.Unlock()

	reaped := make(chan struct{})
	go func() {
		m.reapOnce(now)
		close(reaped)
	}()
	select {
	case <-reaped:
	case <-time.After(5 * time.Second):
		t.Fatal("reapOnce waited on a busy session")
	}

	if !registered(m, fresh.ID) || registered(m, idle.ID) {
		t.Errorf("event sessions: fresh kept %v, idle kept %v", registered(m, fresh.ID), registered(m, idle.ID))
	}
	eventually(t, "the idle session's folder was not removed", func() bool {
		_, err := os.Stat(idle.Dir)
		return os.IsNotExist(err)
	})
	// An idle VOD session stays, without ffmpeg or pieces.
	if !registered(m, vodIdle.ID) || vodIdle.vod.run != nil || len(vodIdle.vod.pieces) != 0 {
		t.Errorf("idle VOD session: registered %v, run %v, pieces %d", registered(m, vodIdle.ID), vodIdle.vod.run, len(vodIdle.vod.pieces))
	}
	eventually(t, "the paused session's pieces were not deleted", func() bool {
		_, err := os.Stat(filepath.Join(vodIdle.Dir, "r1-00000.m4s"))
		return os.IsNotExist(err)
	})
	if vodFresh.vod.run == nil {
		t.Error("a VOD session in use was paused")
	}
	if registered(m, vodOld.ID) {
		t.Error("a VOD session unused for longer than VODKeep is still there")
	}
	if !registered(m, vodBusy.ID) || vodBusy.vod.run == nil {
		t.Error("the busy session was touched")
	}
}

// The janitor looks again under the session's lock: a request after the
// tick keeps its run.
func TestSuspendIdleChecksAgain(t *testing.T) {
	t.Parallel()
	now := time.Now()
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{}, time.Minute, 8, slog.New(slog.DiscardHandler))
	s := fakeSession(t, m, "s", true, now.Add(-2*time.Minute))
	s.Touch() // a request since the tick
	if s.vod.suspendIdle(now, m.idle) || s.vod.run == nil {
		t.Error("a session used since the tick was paused")
	}
	s.last.Store(now.Add(-2 * time.Minute).UnixNano())
	if !s.vod.suspendIdle(now, m.idle) || s.vod.run != nil {
		t.Error("an idle session was not paused")
	}
}

// Run reaps on its ticker and, when its context ends, stops every session
// and returns.
func TestRunReapsOnItsTicker(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m := NewManager("ffmpeg", t.TempDir(), Capabilities{}, time.Minute, 8, slog.New(slog.DiscardHandler))
		s := fakeSession(t, m, "s", false, time.Now())
		v := fakeSession(t, m, "v", true, time.Now())
		ctx, cancel := context.WithCancel(t.Context())
		ran := make(chan struct{})
		go func() {
			m.Run(ctx)
			close(ran)
		}()
		time.Sleep(55 * time.Second) // ticks at 10s … 50s
		synctest.Wait()
		if !registered(m, s.ID) || !hasRun(v) {
			t.Fatal("reaped before the idle timeout")
		}
		time.Sleep(20 * time.Second) // the tick at 70s
		synctest.Wait()
		if registered(m, s.ID) || hasRun(v) {
			t.Errorf("after the idle timeout: event session kept %v, VOD run %v", registered(m, s.ID), hasRun(v))
		}
		cancel()
		<-ran
		if registered(m, v.ID) {
			t.Error("Run returned with a session left")
		}
		select {
		case <-v.done:
		default:
			t.Error("Run returned before the session stopped")
		}
	})
}
