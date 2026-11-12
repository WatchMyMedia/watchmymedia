package auth

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"strings"
	"time"
)

// Offline tokens let an app's downloader fetch one download's media
// (PLAN-DOWNLOADS.md §4.2): the original file, the HLS playlists and
// segments of its download session, or its subtitles. A token names its
// download record and opens only that record's routes, so one seen in a
// log is worth one download for a few hours. It is tied to the device
// session it was made from, like a media token, so signing the device out
// ends it; the app asks for a new one while the record stands.
//
// Format: "o1." + base64url(expiry unix seconds (8 bytes) | session token
// hash (32 bytes) | the record's public ID) + "." + signature. The prefix
// is signed, so no other kind of token passes for one.
const (
	OfflineTokenTTL    = 6 * time.Hour
	offlineTokenPrefix = "o1."
	// maxOfflineID bounds the record ID a token carries.
	maxOfflineID = 64
)

var ErrBadOfflineToken = errors.New("invalid or expired offline token")

// IsOfflineToken reports whether t looks like an offline token.
func IsOfflineToken(t string) bool { return strings.HasPrefix(t, offlineTokenPrefix) }

// IssueOfflineToken makes an offline token for one download record, tied
// to the given session token.
func (s *Service) IssueOfflineToken(ctx context.Context, sessionToken, recordID string) (string, time.Time, error) {
	if recordID == "" || len(recordID) > maxOfflineID {
		return "", time.Time{}, errors.New("offline token: bad record ID")
	}
	if _, err := s.Authenticate(ctx, sessionToken); err != nil {
		return "", time.Time{}, err
	}
	sum := sha256.Sum256([]byte(sessionToken))
	exp := time.Now().Add(OfflineTokenTTL).Truncate(time.Second)
	payload := make([]byte, 8, 8+len(sum)+len(recordID))
	binary.BigEndian.PutUint64(payload, uint64(exp.Unix()))
	payload = append(payload, sum[:]...)
	payload = append(payload, recordID...)
	enc := base64.RawURLEncoding
	return offlineTokenPrefix + enc.EncodeToString(payload) + "." + enc.EncodeToString(s.signAs(offlineTokenPrefix, payload)), exp, nil
}

// AuthenticateOffline checks an offline token and returns its user and the
// download record it opens. Whether the record still stands is the
// caller's to check.
func (s *Service) AuthenticateOffline(ctx context.Context, token string) (User, string, error) {
	rest, ok := strings.CutPrefix(token, offlineTokenPrefix)
	if !ok {
		return User{}, "", ErrBadOfflineToken
	}
	p64, s64, ok := strings.Cut(rest, ".")
	if !ok {
		return User{}, "", ErrBadOfflineToken
	}
	enc := base64.RawURLEncoding
	payload, err1 := enc.DecodeString(p64)
	sig, err2 := enc.DecodeString(s64)
	head := 8 + sha256.Size
	if err1 != nil || err2 != nil || len(payload) <= head || len(payload) > head+maxOfflineID || !hmac.Equal(sig, s.signAs(offlineTokenPrefix, payload)) {
		return User{}, "", ErrBadOfflineToken
	}
	if exp := time.Unix(int64(binary.BigEndian.Uint64(payload[:8])), 0); !time.Now().Before(exp) {
		return User{}, "", ErrBadOfflineToken
	}
	u, err := s.sessionUser(ctx, hex.EncodeToString(payload[8:head]), ErrBadOfflineToken)
	if err != nil {
		return User{}, "", err
	}
	return u, string(payload[head:]), nil
}

type offlineKey struct{}

// Offline is how a request on an offline route authenticated: with the
// offline token for Record, or (Record empty) with the session.
type Offline struct {
	Record string
	Token  string
}

// OfflineFromContext returns the offline token the request authenticated
// with, if it used one, so the route can check it names the record asked
// for and playlists can pass it on to their segments.
func OfflineFromContext(ctx context.Context) (Offline, bool) {
	o, ok := ctx.Value(offlineKey{}).(Offline)
	return o, ok
}
