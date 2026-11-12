package auth

import (
	"errors"
	"testing"
	"testing/synctest"
	"time"
)

func TestConfirmationLifetimeAndSessionIsolation(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, _ := setup(t)
		ctx := t.Context()
		u, err := s.CreateUser(ctx, "Robin", "fictional-password", true)
		if err != nil {
			t.Fatal(err)
		}
		a, err := s.CreateSession(ctx, u.ID, Client{})
		if err != nil {
			t.Fatal(err)
		}
		b, err := s.CreateSession(ctx, u.ID, Client{})
		if err != nil {
			t.Fatal(err)
		}
		u, err = s.Authenticate(ctx, a.Token)
		if err != nil {
			t.Fatal(err)
		}
		other, err := s.Authenticate(ctx, b.Token)
		if err != nil {
			t.Fatal(err)
		}
		if err := s.ConfirmPassword(ctx, u, "fictional-password"); err != nil {
			t.Fatal(err)
		}
		if !s.Confirmed(u) || s.Confirmed(other) {
			t.Fatal("confirmation leaked across sessions")
		}
		time.Sleep(ConfirmationTTL - time.Second)
		if !s.Confirmed(u) {
			t.Fatal("confirmation expired early")
		}
		time.Sleep(time.Second)
		if s.Confirmed(u) || len(s.confirmed) != 0 {
			t.Fatal("expired confirmation survived")
		}
	})
}

func TestConfirmationRevocation(t *testing.T) {
	t.Parallel()
	for _, action := range []string{"logout", "revoke", "password", "delete"} {
		t.Run(action, func(t *testing.T) {
			s, _ := setup(t)
			ctx := t.Context()
			u, err := s.CreateUser(ctx, "Robin", "fictional-password", true)
			if err != nil {
				t.Fatal(err)
			}
			g, err := s.CreateSession(ctx, u.ID, Client{})
			if err != nil {
				t.Fatal(err)
			}
			u, err = s.Authenticate(ctx, g.Token)
			if err != nil {
				t.Fatal(err)
			}
			if err := s.ConfirmPassword(ctx, u, "wrong"); !errors.Is(err, ErrInvalidCredentials) {
				t.Fatal(err)
			}
			if s.Confirmed(u) {
				t.Fatal("wrong password confirmed")
			}
			if err := s.ConfirmPassword(ctx, u, "fictional-password"); err != nil {
				t.Fatal(err)
			}
			// A monotonic deadline pauses during macOS sleep. This security
			// window must expire by wall time, as durable sign-ins do.
			expiry := s.confirmed[u.SignInID].expires
			if expiry != expiry.Round(0) {
				t.Fatal("confirmation can be extended by server sleep")
			}
			switch action {
			case "logout":
				err = s.Logout(ctx, g.Token)
			case "revoke":
				err = s.Revoke(ctx, g.SessionID)
			case "password":
				err = s.ChangePassword(ctx, u.ID, "new-fictional-password", g.Token)
			case "delete":
				err = s.DeleteUser(ctx, u.ID)
			}
			if err != nil {
				t.Fatal(err)
			}
			if s.Confirmed(u) {
				t.Fatal("confirmation survived " + action)
			}
			if action != "password" {
				if err := s.ConfirmPassword(ctx, u, "fictional-password"); err == nil {
					t.Fatal("revoked session confirmed")
				}
			}
		})
	}
}
