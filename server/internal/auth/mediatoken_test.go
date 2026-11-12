package auth

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestMediaTokens(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	u, err := s.CreateUser(ctx, "kid", "kidpass1", false)
	if err != nil {
		t.Fatal(err)
	}
	session := login(t, s, "kid", "kidpass1")
	tok, exp, err := s.IssueMediaToken(ctx, session)
	if err != nil {
		t.Fatal(err)
	}
	if !IsMediaToken(tok) || IsMediaToken(session) || time.Until(exp) > MediaTokenTTL || time.Until(exp) < MediaTokenTTL-time.Minute {
		t.Errorf("token %q expires %s", tok, exp)
	}
	if got, err := s.AuthenticateMedia(ctx, tok); err != nil || got.ID != u.ID {
		t.Fatalf("authenticate -> %+v, %v", got, err)
	}
	// A media token is not a session token, and vice versa.
	if _, err := s.Authenticate(ctx, tok); err == nil {
		t.Error("media token accepted as a session")
	}
	if _, err := s.AuthenticateMedia(ctx, session); !errors.Is(err, ErrBadMediaToken) {
		t.Errorf("session token accepted as media token: %v", err)
	}
	// Tampering breaks the signature.
	flip := []byte(tok)
	i := len(mediaTokenPrefix) + 3
	if flip[i] == 'A' {
		flip[i] = 'B'
	} else {
		flip[i] = 'A'
	}
	for _, bad := range []string{string(flip), tok + "x", strings.TrimSuffix(tok, tok[len(tok)-4:]), "m1.", "m1.a.b", ""} {
		if _, err := s.AuthenticateMedia(ctx, bad); !errors.Is(err, ErrBadMediaToken) {
			t.Errorf("%q -> %v", bad, err)
		}
	}
	// Another server's key does not verify.
	other, _ := setup(t)
	if _, err := other.AuthenticateMedia(ctx, tok); !errors.Is(err, ErrBadMediaToken) {
		t.Errorf("foreign key -> %v", err)
	}
	// An expired token fails even with a good signature.
	sum := sha256.Sum256([]byte(session))
	payload := make([]byte, 8, 40)
	binary.BigEndian.PutUint64(payload, uint64(time.Now().Add(-time.Second).Unix()))
	payload = append(payload, sum[:]...)
	enc := base64.RawURLEncoding
	expired := mediaTokenPrefix + enc.EncodeToString(payload) + "." + enc.EncodeToString(s.sign(payload))
	if _, err := s.AuthenticateMedia(ctx, expired); !errors.Is(err, ErrBadMediaToken) {
		t.Errorf("expired -> %v", err)
	}
	// Signing out revokes it.
	if err := s.Logout(ctx, session); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AuthenticateMedia(ctx, tok); !errors.Is(err, ErrBadMediaToken) {
		t.Errorf("after logout -> %v", err)
	}
	if _, _, err := s.IssueMediaToken(ctx, session); err == nil {
		t.Error("issued a token for a signed-out session")
	}
	// The key survives a restart when stored.
	if err := s.SetMediaKey("zz"); err == nil {
		t.Error("bad key accepted")
	}
}
