package auth

import (
	"errors"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

func TestSignInIdentityTracksMediaAndRevocationWithoutRenewal(t *testing.T) {
	t.Parallel()
	s, q := setup(t)
	ctx := t.Context()
	u, err := s.CreateUser(ctx, "Meadow Viewer", "fictional-password", true)
	if err != nil {
		t.Fatal(err)
	}
	one, err := s.CreateSession(ctx, u.ID, Client{Kind: KindBrowser})
	if err != nil {
		t.Fatal(err)
	}
	two, err := s.CreateSession(ctx, u.ID, Client{Kind: KindBrowser})
	if err != nil {
		t.Fatal(err)
	}
	media, _, err := s.IssueMediaToken(ctx, one.Token)
	if err != nil {
		t.Fatal(err)
	}
	for _, authenticate := range []func() (User, error){
		func() (User, error) { return s.Authenticate(ctx, one.Token) },
		func() (User, error) { return s.AuthenticateMedia(ctx, media) },
		func() (User, error) { return s.ValidateSignIn(ctx, one.SessionID) },
	} {
		got, err := authenticate()
		if err != nil || got.ID != u.ID || got.SignInID != one.SessionID || got.SignInID == two.SessionID {
			t.Fatalf("lost original sign-in identity: %+v %v", got, err)
		}
	}
	// Background policy checks must neither extend expiry nor update activity.
	lastSeen, expires := db.FormatTime(time.Now().Add(-time.Hour)), db.FormatTime(time.Now().Add(time.Minute))
	if err := q.TouchSession(ctx, db.TouchSessionParams{TokenHash: hashToken(one.Token), LastSeenAt: lastSeen, ExpiresAt: expires}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ValidateSignIn(ctx, one.SessionID); err != nil {
		t.Fatal(err)
	}
	row, err := q.GetSession(ctx, db.GetSessionParams{TokenHash: hashToken(one.Token), ExpiresAt: db.FormatTime(time.Now())})
	if err != nil || row.ExpiresAt != expires || row.LastSeenAt != lastSeen {
		t.Fatal("background validation renewed the sign-in")
	}
	if err := s.Revoke(ctx, one.SessionID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ValidateSignIn(ctx, one.SessionID); !errors.Is(err, ErrUnauthenticated) {
		t.Fatalf("revoked sign-in remained valid: %v", err)
	}
	if _, err := s.AuthenticateMedia(ctx, media); !errors.Is(err, ErrBadMediaToken) {
		t.Fatalf("revoked media token remained valid: %v", err)
	}
	if _, err := s.ValidateSignIn(ctx, two.SessionID); err != nil {
		t.Fatalf("revocation affected a different sign-in: %v", err)
	}
	if err := q.TouchSession(ctx, db.TouchSessionParams{TokenHash: hashToken(two.Token), LastSeenAt: lastSeen, ExpiresAt: lastSeen}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ValidateSignIn(ctx, two.SessionID); !errors.Is(err, ErrUnauthenticated) {
		t.Fatalf("expired sign-in remained valid: %v", err)
	}
}
