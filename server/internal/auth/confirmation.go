package auth

import (
	"context"
	"time"
)

const ConfirmationTTL = 10 * time.Minute

type confirmation struct {
	userID  int64
	expires time.Time
}

// ConfirmPassword checks the current account through the sign-in limiter,
// without Turnstile, and confirms only this live sign-in. Revocation and password
// changes are serialized with recording the confirmation.
func (s *Service) ConfirmPassword(ctx context.Context, u User, password string) error {
	key, _, _ := throttleKeys(ctx, u.Name)
	release, err := s.admitPassword(ctx, key)
	if err != nil {
		return err
	}
	defer release()
	if err := s.throttled(ctx, u.Name); err != nil {
		return err
	}
	checked, err := s.checkPassword(ctx, u.Name, password)
	if err != nil {
		return err
	}
	s.grantMu.Lock()
	defer s.grantMu.Unlock()
	live, err := s.ValidateSignIn(ctx, u.SignInID)
	if err != nil {
		return err
	}
	row, err := s.q.GetUser(ctx, u.ID)
	if err != nil {
		return err
	}
	if live.ID != u.ID || checked.ID != u.ID || row.PasswordHash != checked.PasswordHash {
		return ErrInvalidCredentials
	}
	s.pruneConfirmations()
	if s.confirmed == nil {
		s.confirmed = make(map[string]confirmation)
	}
	// Wall time keeps a sleeping server from extending the ten-minute window.
	s.confirmed[u.SignInID] = confirmation{u.ID, time.Now().UTC().Add(ConfirmationTTL)}
	return nil
}

func (s *Service) Confirmed(u User) bool {
	s.grantMu.Lock()
	defer s.grantMu.Unlock()
	s.pruneConfirmations()
	c, ok := s.confirmed[u.SignInID]
	return ok && c.userID == u.ID
}

// Called with grantMu held. No background worker or durable secrets are needed.
func (s *Service) pruneConfirmations() {
	now := time.Now()
	for id, c := range s.confirmed {
		if !now.Before(c.expires) {
			delete(s.confirmed, id)
		}
	}
}

func (s *Service) forgetConfirmations(userID int64) {
	for id, c := range s.confirmed {
		if c.userID == userID {
			delete(s.confirmed, id)
		}
	}
}
