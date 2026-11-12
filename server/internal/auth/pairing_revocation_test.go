package auth

import (
	"context"
	"errors"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

func pairingApprover(t *testing.T, s *Service, u User) (context.Context, Grant) {
	t.Helper()
	g, err := s.CreateSession(context.Background(), u.ID, Client{})
	if err != nil {
		t.Fatal(err)
	}
	return context.WithValue(context.Background(), sessionKey{}, g.Token), g
}

func TestPairingApprovalRevocation(t *testing.T) {
	for _, action := range []string{"password", "password-keep-approver", "logout", "revoke", "expire", "delete"} {
		t.Run(action, func(t *testing.T) {
			s, q := setup(t)
			ctx := context.Background()
			if _, err := s.CreateUser(ctx, "owner", "owner-password", true); err != nil {
				t.Fatal(err)
			}
			u, err := s.CreateUser(ctx, "viewer", "viewer-password", false)
			if err != nil {
				t.Fatal(err)
			}
			approval, grant := pairingApprover(t, s, u)
			code, secret, _, err := s.StartPairing(ctx, Client{Name: "Test TV"})
			if err != nil {
				t.Fatal(err)
			}
			if _, err := s.ApprovePairing(approval, code); err != nil {
				t.Fatal(err)
			}
			switch action {
			case "password":
				err = s.ChangePassword(ctx, u.ID, "new-password", "")
			case "password-keep-approver":
				err = s.ChangePassword(ctx, u.ID, "new-password", grant.Token)
			case "logout":
				err = s.Logout(ctx, grant.Token)
			case "revoke":
				err = s.Revoke(ctx, grant.SessionID)
			case "expire":
				err = q.TouchSession(ctx, db.TouchSessionParams{TokenHash: hashToken(grant.Token), ExpiresAt: db.FormatTime(time.Now().Add(-time.Second))})
			case "delete":
				err = s.DeleteUser(ctx, u.ID)
				if err == nil {
					var replacement User
					replacement, err = s.CreateUser(ctx, "replacement-admin", "replacement-password", true)
					if replacement.ID <= u.ID {
						t.Fatal("account identity was reused")
					}
				}
			}
			if err != nil {
				t.Fatal(err)
			}
			if _, err := s.ClaimPairing(ctx, secret); !errors.Is(err, ErrPairingNotFound) {
				t.Fatalf("claim after %s: %v", action, err)
			}
			if action != "password-keep-approver" {
				if _, err := s.ApprovePairing(approval, code); !errors.Is(err, ErrUnauthenticated) {
					t.Fatalf("stale approver: %v", err)
				}
			}
		})
	}
}
