package auth

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"time"
)

// Cast tokens let a Google Cast receiver, a web page running on a TV that
// may not be yours, carry on a play for the person who cast to it: ask for
// a playback decision, fetch the media, report progress and preferences,
// and renew itself. That is more than a media token allows and far less
// than a session. A cast token is signed like a media token and tied to the
// same sender session, so signing that phone or browser out ends the cast
// at the receiver's next request; the receiver renews it at half-life, which
// keeps a long marathon going without a long-lived token.
//
// Format: "c1." + the media token payload + "." + signature; the prefix is
// signed, so neither kind passes for the other.
const (
	CastTokenTTL    = 12 * time.Hour
	castTokenPrefix = "c1."
)

var ErrBadCastToken = errors.New("invalid or expired cast token")

// IsCastToken reports whether t looks like a cast token.
func IsCastToken(t string) bool { return strings.HasPrefix(t, castTokenPrefix) }

// IssueCastToken makes a cast token tied to the given session token.
func (s *Service) IssueCastToken(ctx context.Context, sessionToken string) (string, time.Time, error) {
	if _, err := s.Authenticate(ctx, sessionToken); err != nil {
		return "", time.Time{}, err
	}
	sum := sha256.Sum256([]byte(sessionToken))
	tok, exp := s.issueSigned(castTokenPrefix, CastTokenTTL, sum[:])
	return tok, exp, nil
}

// RenewCastToken swaps a good cast token for a fresh one tied to the same
// session, as long as that session is still signed in.
func (s *Service) RenewCastToken(ctx context.Context, token string) (string, time.Time, error) {
	sum, err := s.verifySigned(castTokenPrefix, token)
	if err != nil {
		return "", time.Time{}, ErrBadCastToken
	}
	if _, err := s.sessionUser(ctx, hex.EncodeToString(sum), ErrBadCastToken); err != nil {
		return "", time.Time{}, err
	}
	tok, exp := s.issueSigned(castTokenPrefix, CastTokenTTL, sum)
	return tok, exp, nil
}

// AuthenticateCast checks a cast token and returns its user. Which routes
// it opens is the middleware's castRoute.
func (s *Service) AuthenticateCast(ctx context.Context, token string) (User, error) {
	sum, err := s.verifySigned(castTokenPrefix, token)
	if err != nil {
		return User{}, ErrBadCastToken
	}
	return s.sessionUser(ctx, hex.EncodeToString(sum), ErrBadCastToken)
}
