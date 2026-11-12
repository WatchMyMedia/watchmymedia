package auth

import (
	"context"
	"sync"
	"time"
)

// Two Argon2 jobs use at most 128 MiB of working memory. Reject excess work
// instead of building an unbounded queue. One login per name/address can be
// in flight, so failures are recorded before that pair may try again.
const maxPasswordWork = 2

type passwordAdmission struct {
	mu     sync.Mutex
	active int
	keys   map[string]bool
}

func (s *Service) admitPassword(ctx context.Context, key string) (func(), error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	a := &s.passwords
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.active >= maxPasswordWork || (key != "" && a.keys[key]) {
		return nil, &ThrottledError{RetryAfter: time.Second, What: "concurrent password checks"}
	}
	if a.keys == nil {
		a.keys = make(map[string]bool)
	}
	a.active++
	if key != "" {
		a.keys[key] = true
	}
	return func() {
		a.mu.Lock()
		defer a.mu.Unlock()
		a.active--
		delete(a.keys, key)
	}, nil
}
