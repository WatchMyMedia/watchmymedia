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

// Bot checks wait on Cloudflare for up to 8 seconds each and hold no
// password slot (Login runs them first), so without a bound any number of
// sign-ins could be waiting on Cloudflare at once. Past this many, a sign-in
// or setup is turned away with a retry rather than queued: real visitors
// rarely overlap, and the bound keeps the requests and goroutines one
// visitor can hold open in check.
const maxChallengeWork = 16

type challengeAdmission struct {
	mu     sync.Mutex
	active int
}

func (s *Service) admitChallenge() (func(), error) {
	a := &s.challenges
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.active >= maxChallengeWork {
		return nil, &ThrottledError{RetryAfter: time.Second, What: "concurrent bot checks"}
	}
	a.active++
	return func() {
		a.mu.Lock()
		defer a.mu.Unlock()
		a.active--
	}, nil
}
