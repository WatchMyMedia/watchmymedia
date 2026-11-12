package playback

import (
	"context"
	"sync"
	"time"
)

// processSlots is the final process admission gate shared by event, VOD,
// downloads and live television. Logical session replacement/yielding can
// release an admission reservation before the previous process has exited;
// this gate keeps the actual processes bounded through that handover.
// A slot is returned only after Wait, including on cancellation.
type processSlots struct{ slots chan struct{} }

func (p *processSlots) acquire(ctx context.Context) (func(), error) {
	timer := time.NewTimer(stopWait)
	defer timer.Stop()
	select {
	case p.slots <- struct{}{}:
		var once sync.Once
		release := func() { once.Do(func() { <-p.slots }) }
		if ctx.Err() != nil {
			release()
			return nil, ctx.Err()
		}
		return release, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-timer.C:
		return nil, ErrTooManySessions
	}
}

// ReserveLive reserves capacity before opening a tuner. The returned release
// must run after the upstream is closed and the encoder is joined. Live TV
// uses the same process gate as file playback, including VOD restarts.
func (m *Manager) ReserveLive(ctx context.Context) (func(), error) {
	if err := m.admitStorage(); err != nil {
		return nil, err
	}
	m.mu.Lock()
	if m.ctx.Err() != nil {
		m.mu.Unlock()
		return nil, ErrStopped
	}
	if m.claimedLocked(nil) >= m.limit && !m.yieldLocked() {
		m.mu.Unlock()
		return nil, m.tooMany()
	}
	m.liveReservations++
	m.mu.Unlock()
	release, err := m.processes.acquire(ctx)
	if err != nil {
		m.mu.Lock()
		m.liveReservations--
		m.mu.Unlock()
		return nil, err
	}
	var once sync.Once
	return func() { once.Do(func() { release(); m.mu.Lock(); m.liveReservations--; m.mu.Unlock() }) }, nil
}
