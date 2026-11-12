package auth

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
)

// Media tokens let players that cannot send headers or cookies (TV apps,
// casting receivers) fetch media URLs with ?access_token=. Unlike a session
// token, a media token only works on media routes, expires after
// MediaTokenTTL, and stops working when the session it came from signs
// out. Anyone who sees a media URL in a log gets a few hours of playback
// at most, never the account.
//
// Format: "m1." + base64url(expiry unix seconds (8 bytes) | session token
// hash (32 bytes)) + "." + base64url(first 16 bytes of HMAC-SHA256).
const (
	MediaTokenTTL    = 6 * time.Hour
	mediaTokenPrefix = "m1."
	mediaKeyBytes    = 32
	mediaSigBytes    = 16
)

var ErrBadMediaToken = errors.New("invalid or expired media token")

// NewMediaKey returns a random signing key, hex-encoded for storage.
func NewMediaKey() string {
	b := make([]byte, mediaKeyBytes)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand does not fail on supported platforms
	}
	return hex.EncodeToString(b)
}

// SetMediaKey sets the signing key (hex). Without one, a random key is used
// and media tokens stop working when the server restarts.
func (s *Service) SetMediaKey(hexKey string) error {
	k, err := hex.DecodeString(hexKey)
	if err != nil || len(k) < 16 {
		return errors.New("media token key must be at least 16 hex-encoded bytes")
	}
	s.mediaKey = k
	return nil
}

func (s *Service) sign(payload []byte) []byte { return s.signAs(mediaTokenPrefix, payload) }

// signAs signs a payload for one kind of token. The prefix is part of the
// signed bytes, so a token of one kind never verifies as another.
func (s *Service) signAs(prefix string, payload []byte) []byte {
	m := hmac.New(sha256.New, s.mediaKey)
	m.Write([]byte(prefix))
	m.Write(payload)
	return m.Sum(nil)[:mediaSigBytes]
}

// IssueMediaToken makes a media token tied to the given session token.
func (s *Service) IssueMediaToken(ctx context.Context, sessionToken string) (string, time.Time, error) {
	if _, err := s.Authenticate(ctx, sessionToken); err != nil {
		return "", time.Time{}, err
	}
	sum := sha256.Sum256([]byte(sessionToken))
	tok, exp := s.issueSigned(mediaTokenPrefix, MediaTokenTTL, sum[:])
	return tok, exp, nil
}

// issueSigned makes a token of the given kind for the session whose token
// hashes to sum.
func (s *Service) issueSigned(prefix string, ttl time.Duration, sum []byte) (string, time.Time) {
	exp := time.Now().Add(ttl).Truncate(time.Second)
	payload := make([]byte, 8, 8+len(sum))
	binary.BigEndian.PutUint64(payload, uint64(exp.Unix()))
	payload = append(payload, sum...)
	enc := base64.RawURLEncoding
	return prefix + enc.EncodeToString(payload) + "." + enc.EncodeToString(s.signAs(prefix, payload)), exp
}

// IsMediaToken reports whether t looks like a media token.
func IsMediaToken(t string) bool { return strings.HasPrefix(t, mediaTokenPrefix) }

// AuthenticateMedia checks a media token and returns its user.
func (s *Service) AuthenticateMedia(ctx context.Context, token string) (User, error) {
	sum, err := s.verifySigned(mediaTokenPrefix, token)
	if err != nil {
		return User{}, ErrBadMediaToken
	}
	return s.sessionUser(ctx, hex.EncodeToString(sum), ErrBadMediaToken)
}

// verifySigned checks a token's kind, signature and expiry and returns the
// hash of the session it was made from.
func (s *Service) verifySigned(prefix, token string) ([]byte, error) {
	rest, ok := strings.CutPrefix(token, prefix)
	if !ok {
		return nil, ErrBadMediaToken
	}
	p64, s64, ok := strings.Cut(rest, ".")
	if !ok {
		return nil, ErrBadMediaToken
	}
	enc := base64.RawURLEncoding
	payload, err1 := enc.DecodeString(p64)
	sig, err2 := enc.DecodeString(s64)
	if err1 != nil || err2 != nil || len(payload) != 8+sha256.Size || !hmac.Equal(sig, s.signAs(prefix, payload)) {
		return nil, ErrBadMediaToken
	}
	if exp := time.Unix(int64(binary.BigEndian.Uint64(payload[:8])), 0); !time.Now().Before(exp) {
		return nil, ErrBadMediaToken
	}
	return payload[8:], nil
}

// sessionUser returns the user of the live session whose token has the
// hex hash given, or bad when it has signed out or expired since.
func (s *Service) sessionUser(ctx context.Context, hash string, bad error) (User, error) {
	row, err := s.q.GetSession(ctx, db.GetSessionParams{TokenHash: hash, ExpiresAt: db.FormatTime(time.Now())})
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, bad // signed out since
	}
	if err != nil {
		return User{}, err
	}
	return sessionOwner(row), nil
}
