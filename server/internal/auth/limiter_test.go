package auth

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/turnstile"
)

func TestLimiterBackoff(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	l := newLimiter(pairPolicy)
	l.now = func() time.Time { return now }

	for i := 0; i < pairPolicy.free; i++ {
		l.fail("Kid")
		if d := l.wait("kid"); d != 0 {
			t.Fatalf("failure %d locked for %s", i+1, d)
		}
	}
	// Each failure past the free ones doubles the wait, up to the cap.
	for i, want := range []time.Duration{30 * time.Second, time.Minute, 2 * time.Minute, 4 * time.Minute, 8 * time.Minute, 15 * time.Minute, 15 * time.Minute} {
		l.fail(" KID ")
		if d := l.wait("kid"); d != want {
			t.Errorf("extra failure %d: wait %s, want %s", i+1, d, want)
		}
	}
	if d := l.wait("someone-else"); d != 0 {
		t.Errorf("other name locked for %s", d)
	}
	// The lockout ends on its own.
	now = now.Add(15 * time.Minute)
	if d := l.wait("kid"); d != 0 {
		t.Errorf("still locked after the lockout: %s", d)
	}
	// An hour of quiet forgets the record, so the next failure is free.
	now = now.Add(forgetAfter + time.Second)
	l.fail("kid")
	if d := l.wait("kid"); d != 0 {
		t.Errorf("stale record kept: %s", d)
	}
	// Success clears it.
	for i := 0; i < pairPolicy.free+1; i++ {
		l.fail("kid")
	}
	l.succeed("kid")
	if d := l.wait("kid"); d != 0 {
		t.Errorf("locked after success: %s", d)
	}
}

func TestLimiterWindow(t *testing.T) {
	start := time.Unix(1_700_000_000, 0)
	now := start
	l := newLimiter(namePolicy)
	l.now = func() time.Time { return now }
	for i := 0; i < namePolicy.free; i++ {
		now = now.Add(time.Second)
		l.fail("kid")
	}
	if d := l.wait("kid"); d != 0 {
		t.Fatalf("locked within the allowance: %s", d)
	}
	l.fail("kid")
	// Locked until the hour that began with the first failure is up.
	if d := l.wait("kid"); d != start.Add(time.Second+time.Hour).Sub(now) {
		t.Errorf("wait %s", d)
	}
	now = start.Add(time.Hour + time.Second)
	if d := l.wait("kid"); d != 0 {
		t.Errorf("locked after the window: %s", d)
	}
	l.fail("kid") // a new window
	if d := l.wait("kid"); d != 0 {
		t.Errorf("first failure of a new window locked: %s", d)
	}
}

func TestLimiterCap(t *testing.T) {
	l := newLimiter(pairPolicy)
	// Every failure at one instant, as when the flood fits in one tick of a
	// coarse clock (Windows): eviction must still follow the order of failures.
	now := time.Unix(1_700_000_000, 0)
	l.now = func() time.Time { return now }
	for i := 0; i <= pairPolicy.free; i++ {
		l.fail("target")
	}
	for i := range maxTracked + 10 {
		l.fail(fmt.Sprint("junk", i))
	}
	if n := len(l.byID); n > maxTracked {
		t.Errorf("tracking %d names, cap %d", n, maxTracked)
	}
	// A flood of new names evicts the oldest records one at a time; it does
	// not wipe them all.
	if l.byID[limitKey(fmt.Sprint("junk", maxTracked+9))] == nil {
		t.Error("newest record dropped")
	}
	if l.byID["junk100"] == nil {
		t.Error("everything was dropped at the cap")
	}
	// The eleven names over the cap evicted the eleven oldest: target, then
	// junk0 to junk9.
	for _, gone := range []string{"target", "junk0", "junk9"} {
		if l.byID[gone] != nil {
			t.Errorf("%s kept; it failed first", gone)
		}
	}
	if l.byID["junk10"] == nil {
		t.Error("junk10 dropped before older records")
	}
}

func TestAddrKey(t *testing.T) {
	for in, want := range map[string]string{
		"198.51.100.7":            "198.51.100.7",
		"::ffff:198.51.100.7":     "198.51.100.7",
		"2001:db8:1:2:aaaa::1":    "2001:db8:1:2::/64",
		"2001:db8:1:2:bbbb::9999": "2001:db8:1:2::/64",
	} {
		if got := addrKey(netip.MustParseAddr(in)); got != want {
			t.Errorf("%s -> %s, want %s", in, got, want)
		}
	}
	if addrKey(netip.Addr{}) != "" {
		t.Error("unknown address got a key")
	}
}

// from is a context as the netguard middleware leaves it for a client.
func from(ip string) context.Context {
	return netguard.NewContext(context.Background(), netguard.Info{IP: netip.MustParseAddr(ip)})
}

func TestLoginThrottled(t *testing.T) {
	s, _ := setup(t)
	attacker, owner := from("203.0.113.9"), from("198.51.100.7")
	if _, err := s.CreateUser(context.Background(), "kid", "kidpass1", false); err != nil {
		t.Fatal(err)
	}
	for i := 0; i <= pairPolicy.free; i++ {
		if _, _, err := s.Login(attacker, Credentials{Name: "kid", Password: "wrong"}, Client{}); !errors.Is(err, ErrInvalidCredentials) {
			t.Fatalf("failure %d -> %v", i+1, err)
		}
	}
	// Locked from that address now, even with the right password.
	var te *ThrottledError
	if _, _, err := s.Login(attacker, Credentials{Name: "KID", Password: "kidpass1"}, Client{}); !errors.As(err, &te) || te.RetryAfter <= 0 {
		t.Fatalf("locked login -> %v", err)
	}
	// The owner, elsewhere, is not locked out by a stranger's guesses.
	if _, _, err := s.Login(owner, Credentials{Name: "kid", Password: "kidpass1"}, Client{}); err != nil {
		t.Fatalf("owner from another address -> %v", err)
	}
	// Unknown names are throttled the same way.
	for i := 0; i <= pairPolicy.free; i++ {
		_, _, _ = s.Login(attacker, Credentials{Name: "ghost", Password: "wrong"}, Client{})
	}
	if _, _, err := s.Login(attacker, Credentials{Name: "ghost", Password: "wrong"}, Client{}); !errors.As(err, &te) {
		t.Errorf("unknown name -> %v", err)
	}
	// After the lockout, the right password works and clears the record.
	s.limit.now = func() time.Time { return time.Now().Add(time.Hour) }
	if _, _, err := s.Login(attacker, Credentials{Name: "kid", Password: "kidpass1"}, Client{}); err != nil {
		t.Fatalf("login after lockout -> %v", err)
	}
	if d := s.limit.wait("kid|203.0.113.9"); d != 0 {
		t.Errorf("record kept after success: %s", d)
	}
}

func TestLoginThrottledPerAddress(t *testing.T) {
	s, _ := setup(t)
	if _, err := s.CreateUser(context.Background(), "kid", "kidpass1", false); err != nil {
		t.Fatal(err)
	}
	// One address trying a different name each time (spraying) is stopped
	// by the address limit; record its earlier failures directly rather than
	// hash twenty passwords.
	for i := 1; i < addrPolicy.free; i++ {
		s.addrLimit.fail("2001:db8:1:2::/64")
	}
	sprayer := from("2001:db8:1:2::77")
	if _, _, err := s.Login(sprayer, Credentials{Name: "name20", Password: "wrong"}, Client{}); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("20th failure -> %v", err)
	}
	if _, _, err := s.Login(sprayer, Credentials{Name: "name21", Password: "wrong"}, Client{}); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("21st failure -> %v", err)
	}
	var te *ThrottledError
	// Another address in the same /64 counts as the same client.
	if _, _, err := s.Login(from("2001:db8:1:2::99"), Credentials{Name: "kid", Password: "kidpass1"}, Client{}); !errors.As(err, &te) {
		t.Errorf("same /64 -> %v", err)
	}
	if _, _, err := s.Login(from("2001:db8:1:3::1"), Credentials{Name: "kid", Password: "kidpass1"}, Client{}); err != nil {
		t.Errorf("another /64 -> %v", err)
	}
}

func TestLoginThrottledPerName(t *testing.T) {
	s, _ := setup(t)
	if _, err := s.CreateUser(context.Background(), "kid", "kidpass1", false); err != nil {
		t.Fatal(err)
	}
	// Guessing spread over many addresses stops at the name's hourly limit.
	for i := 0; i < namePolicy.free; i++ {
		s.nameLimit.fail("kid")
	}
	if _, _, err := s.Login(from("192.0.2.1"), Credentials{Name: "kid", Password: "wrong"}, Client{}); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("last allowed failure -> %v", err)
	}
	var te *ThrottledError
	if _, _, err := s.Login(from("192.0.2.2"), Credentials{Name: "Kid", Password: "kidpass1"}, Client{}); !errors.As(err, &te) || te.RetryAfter < 50*time.Minute {
		t.Errorf("fresh address after the name's limit -> %v", err)
	}
	if _, _, err := s.Login(from("192.0.2.2"), Credentials{Name: "other", Password: "x"}, Client{}); !errors.Is(err, ErrInvalidCredentials) {
		t.Errorf("other name from that address -> %v", err)
	}
}

type fakeChallenge struct {
	err   error // returned for any token but "ok"
	mu    sync.Mutex
	calls int
	ips   []string
}

func (f *fakeChallenge) Required() bool { return true }

func (f *fakeChallenge) Verify(_ context.Context, token, ip string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	f.ips = append(f.ips, ip)
	if token == "ok" {
		return nil
	}
	return f.err
}

func TestChallengeBeforePassword(t *testing.T) {
	s, _ := setup(t)
	if _, err := s.CreateUser(context.Background(), "kid", "kidpass1", false); err != nil {
		t.Fatal(err)
	}
	ch := &fakeChallenge{err: fmt.Errorf("%w (test)", turnstile.ErrRejected)}
	s.SetChallenge(ch)
	ctx := from("198.51.100.7")

	// Without a passing token the password is not tried, and the failure
	// counts against the address but not the name.
	for i := 0; i < pairPolicy.free+2; i++ {
		if _, _, err := s.Login(ctx, Credentials{Name: "kid", Password: "kidpass1"}, Client{}); !errors.Is(err, turnstile.ErrRejected) {
			t.Fatalf("no token -> %v", err)
		}
	}
	if s.limit.wait("kid|198.51.100.7") != 0 || s.nameLimit.byID["kid"] != nil {
		t.Error("a failed bot check counted against the name")
	}
	if a := s.addrLimit.byID["198.51.100.7"]; a == nil || a.failures != pairPolicy.free+2 {
		t.Errorf("address record = %+v", a)
	}
	if ch.ips[0] != "198.51.100.7" {
		t.Errorf("challenge saw address %q", ch.ips[0])
	}
	if _, _, err := s.Login(ctx, Credentials{Name: "kid", Password: "kidpass1", Challenge: "ok"}, Client{}); err != nil {
		t.Fatalf("with token -> %v", err)
	}

	// When Cloudflare cannot be reached, sign-in is refused (fails closed)
	// and nobody's record grows.
	ch.err = fmt.Errorf("%w (test)", turnstile.ErrUnavailable)
	before := s.addrLimit.byID["198.51.100.7"].failures
	if _, _, err := s.Login(ctx, Credentials{Name: "kid", Password: "kidpass1", Challenge: "x"}, Client{}); !errors.Is(err, turnstile.ErrUnavailable) {
		t.Errorf("unreachable -> %v", err)
	}
	if s.addrLimit.byID["198.51.100.7"].failures != before {
		t.Error("an unreachable check counted as a failure")
	}

	// A throttled client is refused before the check is made.
	for i := 0; i <= addrPolicy.free; i++ {
		s.addrLimit.fail("203.0.113.9")
	}
	calls := ch.calls
	var te *ThrottledError
	if _, _, err := s.Login(from("203.0.113.9"), Credentials{Name: "kid", Password: "kidpass1", Challenge: "ok"}, Client{}); !errors.As(err, &te) {
		t.Errorf("throttled -> %v", err)
	}
	if ch.calls != calls {
		t.Error("asked Cloudflare for a throttled client")
	}
	// Changing your own password while signed in needs no bot check.
	if _, err := s.CheckPassword(ctx, "kid", "kidpass1"); err != nil {
		t.Errorf("check password -> %v", err)
	}
}

func TestSetupOnce(t *testing.T) {
	s, _ := setup(t)
	ch := &fakeChallenge{err: turnstile.ErrRejected}
	s.SetChallenge(ch)
	ctx := context.Background()
	if _, _, err := s.Setup(ctx, Credentials{Name: "admin", Password: "password1"}, Client{}); !errors.Is(err, turnstile.ErrRejected) {
		t.Fatalf("setup without token -> %v", err)
	}
	if _, _, err := s.Setup(ctx, Credentials{Name: "admin", Password: "short", Challenge: "ok"}, Client{}); !errors.As(err, new(*InvalidError)) {
		t.Fatalf("short password -> %v", err)
	}
	// Two setups at once: one admin, and the other is told setup is done.
	var wg sync.WaitGroup
	errs := make([]error, 2)
	for i := range errs {
		wg.Go(func() {
			_, _, errs[i] = s.Setup(ctx, Credentials{Name: fmt.Sprint("admin", i), Password: "password1", Challenge: "ok"}, Client{})
		})
	}
	wg.Wait()
	oneWon := (errs[0] == nil) != (errs[1] == nil)
	otherDone := errors.Is(errs[0], ErrSetupDone) || errors.Is(errs[1], ErrSetupDone)
	if !oneWon || !otherDone {
		t.Errorf("racing setups -> %v, %v", errs[0], errs[1])
	}
	if need, _ := s.SetupRequired(ctx); need {
		t.Error("setup still required")
	}
}
