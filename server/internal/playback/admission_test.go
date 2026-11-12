package playback

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestLiveAndFileAdmissionShareTheLimit(t *testing.T) {
	m := NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 1, testLog)
	release, err := m.ReserveLive(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	m.mu.Lock()
	_, _, err = m.newSessionLocked(Spec{FileID: 1}, "libx264")
	m.mu.Unlock()
	if !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("file bypassed live reservation: %v", err)
	}
	if _, err := m.ReserveLive(t.Context()); !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("live bypassed live reservation: %v", err)
	}
	release()
	release()
	if m.Running() != 0 {
		t.Fatal("reservation not released exactly once")
	}
	s := fakeSession(t, m, "file", true, time.Now())
	m.mu.Lock()
	m.sessions[s.ID] = s
	m.mu.Unlock()
	if _, err := m.ReserveLive(t.Context()); !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("live bypassed file reservation: %v", err)
	}
	m.StopAll()
}

func TestProcessHandoverWaitsForActualExit(t *testing.T) {
	slots := processSlots{slots: make(chan struct{}, 2)}
	var active, peak atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for range 20 {
		wg.Go(func() {
			<-start
			release, err := slots.acquire(t.Context())
			if err != nil {
				t.Error(err)
				return
			}
			n := active.Add(1)
			for p := peak.Load(); n > p && !peak.CompareAndSwap(p, n); p = peak.Load() {
			}
			active.Add(-1)
			release()
		})
	}
	close(start)
	wg.Wait()
	if peak.Load() > 2 || len(slots.slots) != 0 {
		t.Fatalf("processes=%d slots=%d", peak.Load(), len(slots.slots))
	}
	one, _ := slots.acquire(t.Context())
	two, _ := slots.acquire(t.Context())
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := slots.acquire(ctx); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	// Removing a logical session never releases these process slots: only
	// the process owner calling its release after Wait makes room.
	if len(slots.slots) != 2 {
		t.Fatal("canceled waiter freed another process")
	}
	one()
	two()
}
