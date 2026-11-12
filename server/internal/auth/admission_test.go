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
	gate := &admissionGate{make(chan struct{}, maxPasswordWork), make(chan struct{})}
	s.SetChallenge(gate)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ctx = netguard.NewContext(ctx, netguard.Info{IP: netip.MustParseAddr("203.0.113.7")})
	done := make(chan error, maxPasswordWork)
	for i := range maxPasswordWork {
		go func() {
			_, _, err := s.Login(ctx, Credentials{Name: fmt.Sprintf("missing-%d", i), Password: "wrong-password"}, Client{})
			done <- err
		}()
		select {
		case <-gate.entered:
		case <-ctx.Done():
			t.Fatal("login never reached gate")
		}
		if i == 0 {
			_, _, err := s.Login(ctx, Credentials{Name: "missing-0", Password: "wrong-password"}, Client{})
			if _, ok := errors.AsType[*ThrottledError](err); !ok {
				t.Fatalf("same pair admitted concurrently: %v", err)
			}
		}
	}
	var te *ThrottledError
	if _, _, err := s.Login(ctx, Credentials{Name: "different", Password: "wrong-password"}, Client{}); !errors.As(err, &te) {
		t.Fatalf("global limit: %v", err)
	}
	if _, err := s.CheckPassword(ctx, "different", "wrong-password"); !errors.As(err, &te) {
		t.Fatalf("password-only bypass: %v", err)
	}
	if _, err := s.CreateUser(ctx, "different", "new-password", false); !errors.As(err, &te) {
		t.Fatalf("hashing bypass: %v", err)
	}
	close(gate.release)
	for range maxPasswordWork {
		if err := <-done; !errors.Is(err, ErrInvalidCredentials) {
			t.Fatal(err)
		}
	}
	s.SetChallenge(nil)
	if _, err := s.CheckPassword(ctx, "missing-0", "wrong-password"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("slot not released: %v", err)
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
