package auth

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"testing"
	"time"
	"watchmymedia/server/internal/netguard"
)

type admissionGate struct {
	entered chan struct{}
	release chan struct{}
}

func (*admissionGate) Required() bool { return true }
func (g *admissionGate) Verify(ctx context.Context, _, _ string) error {
	g.entered <- struct{}{}
	select {
	case <-g.release:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func TestConcurrentLoginAdmission(t *testing.T) {
	s, _ := setup(t)
	ctx := from("203.0.113.7")
	var te *ThrottledError
	key, _, _ := throttleKeys(ctx, "missing-0")
	release, err := s.admitPassword(ctx, key)
	if err != nil {
		t.Fatal(err)
	}
	// One check per name and address at a time.
	if _, _, err := s.Login(ctx, Credentials{Name: "MISSING-0", Password: "wrong-password"}, Client{}); !errors.As(err, &te) {
		t.Fatalf("same pair admitted concurrently: %v", err)
	}
	if _, err := s.CheckPassword(ctx, "missing-0", "wrong-password"); !errors.As(err, &te) {
		t.Fatalf("same pair admitted concurrently to CheckPassword: %v", err)
	}
	releases := []func(){release}
	for i := 1; i < maxPasswordWork; i++ {
		r, err := s.admitPassword(ctx, fmt.Sprintf("missing-%d|203.0.113.7", i))
		if err != nil {
			t.Fatal(err)
		}
		releases = append(releases, r)
	}
	// With every slot taken, nothing else hashes a password.
	if _, _, err := s.Login(ctx, Credentials{Name: "different", Password: "wrong-password"}, Client{}); !errors.As(err, &te) {
		t.Fatalf("global limit: %v", err)
	}
	if _, err := s.CheckPassword(ctx, "different", "wrong-password"); !errors.As(err, &te) {
		t.Fatalf("password-only bypass: %v", err)
	}
	if _, err := s.CreateUser(ctx, "different", "new-password", false); !errors.As(err, &te) {
		t.Fatalf("hashing bypass: %v", err)
	}
	for _, r := range releases {
		r()
	}
	if _, err := s.CheckPassword(ctx, "missing-0", "wrong-password"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("slot not released: %v", err)
	}
}

// A bot check waiting on Cloudflare holds no password slot, so sign-ins
// with junk tokens cannot turn away everyone else's password checks.
func TestSlowChallengeHoldsNoPasswordSlot(t *testing.T) {
	s, _ := setup(t)
	if _, err := s.CreateUser(context.Background(), "kid", "kidpass1", false); err != nil {
		t.Fatal(err)
	}
	waiting := maxPasswordWork + 1
	gate := &admissionGate{make(chan struct{}, waiting), make(chan struct{})}
	s.SetChallenge(gate)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ctx = netguard.NewContext(ctx, netguard.Info{IP: netip.MustParseAddr("203.0.113.7")})
	done := make(chan error, waiting)
	for i := range waiting {
		go func() {
			_, _, err := s.Login(ctx, Credentials{Name: fmt.Sprintf("missing-%d", i), Password: "wrong-password"}, Client{})
			done <- err
		}()
		select {
		case <-gate.entered:
		case <-ctx.Done():
			t.Fatal("login never reached the bot check")
		}
	}
	owner := from("198.51.100.7")
	if _, err := s.CheckPassword(owner, "kid", "kidpass1"); err != nil {
		t.Errorf("password check while sign-ins wait on the bot check: %v", err)
	}
	if _, err := s.CreateUser(owner, "another", "new-password", false); err != nil {
		t.Errorf("new account while sign-ins wait on the bot check: %v", err)
	}
	close(gate.release)
	// Released together, they then compete for the slots themselves.
	for range waiting {
		var te *ThrottledError
		if err := <-done; !errors.Is(err, ErrInvalidCredentials) && !errors.As(err, &te) {
			t.Fatal(err)
		}
	}
}

func TestPairingIPv6PrefixAdmission(t *testing.T) {
	s, _ := setup(t)
	for i := 1; i <= pairStartsPerIP+1; i++ {
		a := netip.MustParseAddr("2001:db8:1234:5678::").As16()
		a[15] = byte(i)
		ctx := netguard.NewContext(context.Background(), netguard.Info{IP: netip.AddrFrom16(a)})
		_, _, _, err := s.StartPairing(ctx, Client{Name: "Test TV"})
		if i <= pairStartsPerIP && err != nil {
			t.Fatal(err)
		}
		var te *ThrottledError
		if i > pairStartsPerIP && !errors.As(err, &te) {
			t.Fatalf("/64 allowance bypassed: %v", err)
		}
	}
}

// Sign-ins waiting on the bot check are bounded: past maxChallengeWork the
// next is turned away at once, without calling Cloudflare, and a slot frees
// as soon as a check returns.
func TestBotChecksAreBounded(t *testing.T) {
	s, _ := setup(t)
	gate := &admissionGate{make(chan struct{}, maxChallengeWork+1), make(chan struct{})}
	s.SetChallenge(gate)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	done := make(chan error, maxChallengeWork+1)
	for i := range maxChallengeWork {
		go func() {
			c := netguard.NewContext(ctx, netguard.Info{IP: netip.MustParseAddr(fmt.Sprintf("203.0.113.%d", i+1))})
			_, _, err := s.Login(c, Credentials{Name: fmt.Sprintf("missing-%d", i), Password: "wrong-password"}, Client{})
			done <- err
		}()
		select {
		case <-gate.entered:
		case <-ctx.Done():
			t.Fatal("login never reached the bot check")
		}
	}
	over := netguard.NewContext(ctx, netguard.Info{IP: netip.MustParseAddr("198.51.100.9")})
	_, _, err := s.Login(over, Credentials{Name: "one-more", Password: "wrong-password"}, Client{})
	var te *ThrottledError
	if !errors.As(err, &te) || te.What != "concurrent bot checks" {
		t.Fatalf("sign-in past the bound: %v, want a throttle for concurrent bot checks", err)
	}
	select {
	case <-gate.entered:
		t.Fatal("the turned-away sign-in still called the bot check")
	default:
	}
	close(gate.release)
	for range maxChallengeWork {
		<-done
	}
	// Every slot is free again.
	if release, err := s.admitChallenge(); err != nil {
		t.Fatalf("after the checks returned: %v", err)
	} else {
		release()
	}
}
