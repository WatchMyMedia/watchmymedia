package auth

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestPairing(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	u, _ := s.CreateUser(ctx, "a", "pass1234", false)
	approval, _ := pairingApprover(t, s, u)
	code, secret, exp, err := s.StartPairing(ctx, Client{DeviceKey: "tv-1", Name: "Living room TV", Platform: "androidtv"})
	if err != nil {
		t.Fatal(err)
	}
	if len(code) != pairCodeLen || strings.ContainsAny(code, "01OI") || len(secret) != 64 || time.Until(exp) > PairingTTL {
		t.Errorf("code %q secret %q exp %s", code, secret, exp)
	}
	// Waiting for approval.
	if st, err := s.ClaimPairing(ctx, secret); err != nil || st.Approved {
		t.Errorf("claim before approval = %+v %v", st, err)
	}
	if _, err := s.ClaimPairing(ctx, "nope"); !errors.Is(err, ErrPairingNotFound) {
		t.Errorf("claim with a wrong secret -> %v", err)
	}
	// The approval page finds the device by its code, typed loosely.
	loose := strings.ToLower(code[:3]) + "-" + code[3:]
	pend, err := s.PendingPairing(approval, loose)
	if err != nil || pend.Client.Name != "Living room TV" || pend.Approved {
		t.Fatalf("pending = %+v %v", pend, err)
	}
	if _, err := s.ApprovePairing(approval, "ZZZZZZ"); !errors.Is(err, ErrPairingNotFound) {
		t.Errorf("approve unknown -> %v", err)
	}
	if _, err := s.ApprovePairing(approval, loose); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ApprovePairing(approval, code); !errors.Is(err, ErrPairingUsed) {
		t.Errorf("approve twice -> %v", err)
	}
	st, err := s.ClaimPairing(ctx, secret)
	if err != nil || !st.Approved || st.User.ID != u.ID || st.Grant.Kind != KindDevice || st.Grant.Token == "" {
		t.Fatalf("claim = %+v %v", st, err)
	}
	if got, err := s.Authenticate(ctx, st.Grant.Token); err != nil || got.ID != u.ID {
		t.Errorf("paired token -> %+v %v", got, err)
	}
	list, _ := s.Sessions(ctx, u.ID, "")
	var paired Session
	for _, session := range list {
		if session.ID == st.Grant.SessionID {
			paired = session
		}
	}
	if len(list) != 2 || paired.Kind != KindDevice || paired.Name != "Living room TV" || paired.DeviceID != "tv-1" {
		t.Errorf("sessions = %+v", list)
	}
	// Single use.
	if _, err := s.ClaimPairing(ctx, secret); !errors.Is(err, ErrPairingNotFound) {
		t.Errorf("second claim -> %v", err)
	}
	if _, err := s.PendingPairing(approval, code); !errors.Is(err, ErrPairingNotFound) {
		t.Errorf("code after use -> %v", err)
	}
}

func TestPairingLimits(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	u, _ := s.CreateUser(ctx, "a", "pass1234", false)
	approval, _ := pairingApprover(t, s, u)
	for i := range pairStartsPerIP {
		if _, _, _, err := s.StartPairing(ctx, Client{}); err != nil {
			t.Fatalf("start %d: %v", i, err)
		}
	}
	var te *ThrottledError
	if _, _, _, err := s.StartPairing(ctx, Client{}); !errors.As(err, &te) || te.RetryAfter <= 0 || !strings.Contains(err.Error(), "pairing") {
		t.Errorf("start over the limit -> %v", err)
	}
	// Guessing codes locks the guesser out, whether looking codes up or
	// approving them, and another person is not held up.
	for i := 0; i <= pairPolicy.free; i++ {
		guess := "ZZZZZ" + string(rune('A'+i))
		if i%2 == 0 {
			_, _ = s.PendingPairing(approval, guess)
		} else {
			_, _ = s.ApprovePairing(approval, guess)
		}
	}
	if _, err := s.ApprovePairing(approval, "ZZZZZZ"); !errors.As(err, &te) {
		t.Errorf("approving after guessing -> %v", err)
	}
	if _, err := s.PendingPairing(approval, "ZZZZZZ"); !errors.As(err, &te) {
		t.Errorf("looking up after guessing -> %v", err)
	}
	other, _ := s.CreateUser(ctx, "b", "pass1234", false)
	otherApproval, _ := pairingApprover(t, s, other)
	if _, err := s.PendingPairing(otherApproval, "ZZZZZZ"); !errors.Is(err, ErrPairingNotFound) {
		t.Errorf("another person looking up -> %v", err)
	}
	if _, err := s.PendingPairing(context.Background(), "ZZZZZZ"); !errors.Is(err, ErrUnauthenticated) {
		t.Errorf("looking up without signing in -> %v", err)
	}
	// Expired pairings are gone.
	code, secret, _, err := func() (string, string, time.Time, error) {
		s.pair.mu.Lock()
		s.pair.starts = map[string][]time.Time{}
		s.pair.mu.Unlock()
		return s.StartPairing(ctx, Client{})
	}()
	if err != nil {
		t.Fatal(err)
	}
	s.pair.mu.Lock()
	s.pair.byCode[code].ExpiresAt = time.Now().Add(-time.Second)
	s.pair.mu.Unlock()
	if _, err := s.ClaimPairing(ctx, secret); !errors.Is(err, ErrPairingNotFound) {
		t.Errorf("expired claim -> %v", err)
	}
}
