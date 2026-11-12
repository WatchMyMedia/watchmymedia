package auth

import (
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"time"
)

// Sign-in throttling. Failures are counted three ways, so that guessing is
// slow without letting a stranger lock the owner of an account out:
//
//   - per name and client address (pairPolicy): 5 failures, then waits that
//     double from 30 seconds to 15 minutes. Someone guessing one account
//     from one place stops here, while its owner signing in from elsewhere
//     is not held up.
//   - per client address, across names (addrPolicy): 20 failures, then the
//     same waits. This stops one address trying many names (password
//     spraying, credential stuffing). Failed bot checks count here too.
//   - per name, across addresses (namePolicy): 50 failures in an hour, then
//     nobody may try that name until the hour is up. This bounds guessing
//     spread over many addresses; one address alone cannot reach it.
//
// The address is the real client's, which netguard resolves through trusted
// proxies; IPv6 addresses count by /64, which one client usually holds whole.
// Behind a proxy missing from network.trusted_proxies, or Docker Desktop's
// port forwarding, every client shares one address and the per-address
// limits apply to everyone together.
type policy struct {
	free int           // failures allowed before any wait
	base time.Duration // first wait, doubled for each further failure
	max  time.Duration
	// window, when set, counts failures per fixed window from the first
	// one and, past free, locks until the window ends (base and max unused).
	window time.Duration
}

var (
	pairPolicy = policy{free: 5, base: 30 * time.Second, max: 15 * time.Minute}
	addrPolicy = policy{free: 20, base: 30 * time.Second, max: 15 * time.Minute}
	namePolicy = policy{free: 50, window: time.Hour}
)

const (
	forgetAfter = time.Hour // a backoff record is dropped this long after its last failure
	maxTracked  = 10000     // cap on records per limiter, so junk names cannot grow memory without bound
)

// ThrottledError means too many recent attempts: failed sign-ins for a name
// unless What says otherwise.
type ThrottledError struct {
	RetryAfter time.Duration
	What       string
}

func (e *ThrottledError) Error() string {
	what := e.What
	if what == "" {
		what = "failed sign-ins"
	}
	return fmt.Sprintf("too many %s; try again in %s", what, e.RetryAfter.Round(time.Second))
}

type attempt struct {
	failures    int
	first, last time.Time
	lockedUntil time.Time
	seq         uint64 // order of the last failure, for records whose last is the same time
}

type limiter struct {
	policy
	mu   sync.Mutex
	byID map[string]*attempt
	now  func() time.Time
	seq  uint64
}

func newLimiter(p policy) *limiter {
	return &limiter{policy: p, byID: map[string]*attempt{}, now: time.Now}
}

func limitKey(name string) string { return strings.ToLower(strings.TrimSpace(name)) }

// addrKey is the key a client address counts under: the address for IPv4,
// its /64 for IPv6, or "" when the address is unknown.
func addrKey(a netip.Addr) string {
	a = a.Unmap()
	switch {
	case !a.IsValid():
		return ""
	case a.Is4():
		return a.String()
	}
	return netip.PrefixFrom(a, 64).Masked().String()
}

func (l *limiter) stale(a *attempt, now time.Time) bool {
	if l.window > 0 {
		return now.Sub(a.first) >= l.window
	}
	return now.Sub(a.last) > forgetAfter
}

// wait reports how long the key must wait before another attempt.
func (l *limiter) wait(key string) time.Duration {
	l.mu.Lock()
	defer l.mu.Unlock()
	a := l.byID[limitKey(key)]
	if a == nil {
		return 0
	}
	if d := a.lockedUntil.Sub(l.now()); d > 0 {
		return d
	}
	return 0
}

// fail records a failed attempt and starts a lockout once the free
// failures are used up.
func (l *limiter) fail(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	key = limitKey(key)
	a := l.byID[key]
	if a == nil || l.stale(a, now) {
		if a == nil && len(l.byID) >= maxTracked {
			l.pruneLocked(now)
		}
		a = &attempt{first: now}
		l.byID[key] = a
	}
	a.failures++
	a.last = now
	l.seq++
	a.seq = l.seq
	over := a.failures - l.free
	switch {
	case over <= 0:
	case l.window > 0:
		a.lockedUntil = a.first.Add(l.window)
	default:
		d := l.max
		if over <= 10 { // 30s << 5 already exceeds the cap
			if s := l.base << (over - 1); s < l.max {
				d = s
			}
		}
		a.lockedUntil = now.Add(d)
	}
}

// succeed clears the key's record.
func (l *limiter) succeed(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.byID, limitKey(key))
}

// pruneLocked drops stale records or, if none are stale, the one whose last
// failure is oldest. Dropping only one keeps a flood of new names from
// wiping out the lockouts that matter. Records failed in the same clock
// tick, common on Windows, go in the order they failed, not map order.
func (l *limiter) pruneLocked(now time.Time) {
	var oldest string
	for k, a := range l.byID {
		if l.stale(a, now) {
			delete(l.byID, k)
		} else if oldest == "" || olderThan(a, l.byID[oldest]) {
			oldest = k
		}
	}
	if len(l.byID) >= maxTracked {
		delete(l.byID, oldest)
	}
}

func olderThan(a, b *attempt) bool {
	if !a.last.Equal(b.last) {
		return a.last.Before(b.last)
	}
	return a.seq < b.seq
}
