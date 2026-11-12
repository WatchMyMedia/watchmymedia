package playback

import (
	"context"
	"sync"
)

// sharedRendition separates the requesting viewer's lifetime from the worker
// producing bytes for all waiters. The cache cancels work when its last waiter
// leaves; stopping only the worker's original viewer cannot cancel a peer.
func (v *vodState) sharedRendition(ctx context.Context, key string,
	produce func(context.Context) ([]byte, error), validate func([]byte) error) ([]byte, func(), error) {
	request, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(v.viewerCtx, cancel)
	defer func() { stop(); cancel() }()
	if v.viewerCtx.Err() != nil {
		return nil, nil, ErrStopped
	}
	b, release, err := v.m.renditions.get(request, v.m.ctx, key, func(work context.Context) ([]byte, error) {
		releaseWorker, err := v.m.leaseRendition(v.s)
		if err != nil {
			return nil, err
		}
		defer releaseWorker()
		return produce(work)
	}, validate)
	if v.viewerCtx.Err() != nil {
		if release != nil {
			release()
		}
		return nil, nil, ErrStopped
	}
	return b, release, err
}

// leaseRendition keeps an already admitted worker alive until its shared
// flight ends. A stopped viewer's worker becomes private to the manager, and
// its claim stays counted until the last flight releases it. No new flight
// may borrow a worker after its viewer stopped.
func (m *Manager) leaseRendition(s *Session) (func(), error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.sessions[s.ID] != s || m.ctx.Err() != nil || s.vod.ctx.Err() != nil {
		return nil, ErrStopped
	}
	if s.producers == 0 {
		s.producerDone = make(chan struct{})
	}
	s.producers++
	var once sync.Once
	return func() {
		once.Do(func() {
			m.mu.Lock()
			defer m.mu.Unlock()
			s.producers--
			if s.producers == 0 {
				close(s.producerDone)
			}
			if s.producers == 0 && m.retiring[s.ID] == s {
				delete(m.retiring, s.ID)
				s.cancel()
			}
		})
	}, nil
}
