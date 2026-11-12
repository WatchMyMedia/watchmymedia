package auth

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestCastTokens(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	u, err := s.CreateUser(ctx, "kid", "kidpass1", false)
	if err != nil {
		t.Fatal(err)
	}
	session := login(t, s, "kid", "kidpass1")
	tok, exp, err := s.IssueCastToken(ctx, session)
	if err != nil {
		t.Fatal(err)
	}
	if !IsCastToken(tok) || IsMediaToken(tok) || time.Until(exp) > CastTokenTTL || time.Until(exp) < CastTokenTTL-time.Minute {
		t.Errorf("token %q expires %s", tok, exp)
	}
	if got, err := s.AuthenticateCast(ctx, tok); err != nil || got.ID != u.ID {
		t.Fatalf("authenticate -> %+v, %v", got, err)
	}
	// Cast, media and session tokens never stand in for each other, even
	// with the payload copied across: the kind is signed.
	media, _, _ := s.IssueMediaToken(ctx, session)
	if _, err := s.AuthenticateMedia(ctx, tok); !errors.Is(err, ErrBadMediaToken) {
		t.Errorf("cast token as media token -> %v", err)
	}
	if _, err := s.AuthenticateCast(ctx, media); !errors.Is(err, ErrBadCastToken) {
		t.Errorf("media token as cast token -> %v", err)
	}
	if _, err := s.AuthenticateCast(ctx, castTokenPrefix+strings.TrimPrefix(media, mediaTokenPrefix)); !errors.Is(err, ErrBadCastToken) {
		t.Errorf("relabelled media token -> %v", err)
	}
	if _, err := s.Authenticate(ctx, tok); err == nil {
		t.Error("cast token accepted as a session")
	}
	// Renewal gives a fresh token for the same session.
	renewed, _, err := s.RenewCastToken(ctx, tok)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := s.AuthenticateCast(ctx, renewed); err != nil || got.ID != u.ID {
		t.Fatalf("renewed -> %+v, %v", got, err)
	}
	if _, _, err := s.RenewCastToken(ctx, media); !errors.Is(err, ErrBadCastToken) {
		t.Errorf("renewed a media token: %v", err)
	}
	// A correctly signed expired token cannot read a playlist or renew itself.
	sum, err := s.verifySigned(castTokenPrefix, tok)
	if err != nil {
		t.Fatal(err)
	}
	expired, _ := s.issueSigned(castTokenPrefix, -time.Minute, sum)
	if _, err := s.AuthenticateCast(ctx, expired); !errors.Is(err, ErrBadCastToken) {
		t.Fatalf("expired cast token authenticated: %v", err)
	}
	if _, _, err := s.RenewCastToken(ctx, expired); !errors.Is(err, ErrBadCastToken) {
		t.Fatalf("expired cast token renewed: %v", err)
	}
	// Signing the sender out ends the cast and its renewals.
	if err := s.Logout(ctx, session); err != nil {
		t.Fatal(err)
	}
	for _, tk := range []string{tok, renewed} {
		if _, err := s.AuthenticateCast(ctx, tk); !errors.Is(err, ErrBadCastToken) {
			t.Errorf("after logout -> %v", err)
		}
		if _, _, err := s.RenewCastToken(ctx, tk); !errors.Is(err, ErrBadCastToken) {
			t.Errorf("renewed after logout -> %v", err)
		}
	}
	if _, _, err := s.IssueCastToken(ctx, session); err == nil {
		t.Error("issued a cast token for a signed-out session")
	}
}

func TestMiddlewareCastTokens(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	_, _ = s.CreateUser(ctx, "a", "pass1234", true)
	session := login(t, s, "a", "pass1234")
	cast, _, _ := s.IssueCastToken(ctx, session)
	media, _, _ := s.IssueMediaToken(ctx, session)

	exempt := func(p string) bool { return !strings.HasPrefix(p, "/api/") }
	mediaPath := func(p string) bool { return strings.HasSuffix(p, "/stream") || strings.HasSuffix(p, ".m3u8") }
	castRoute := func(method, p string) bool {
		return mediaPath(p) || (method == http.MethodPost && p == "/api/files/1/playback")
	}
	var seenMedia string
	h := s.Middleware(exempt, mediaPath, castRoute, nil)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenMedia, _ = MediaTokenFromContext(r.Context())
		if u, ok := UserFrom(r.Context()); ok {
			_, _ = w.Write([]byte("user:" + u.Name))
		}
	}))
	do := func(method, path, bearer string) int {
		req := httptest.NewRequestWithContext(t.Context(), method, path, nil)
		if bearer != "" {
			req.Header.Set("Authorization", "Bearer "+bearer)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code
	}
	for _, c := range []struct {
		method, path, bearer string
		want                 int
	}{
		{http.MethodPost, "/api/files/1/playback", cast, 200},
		{http.MethodGet, "/api/files/1/playback", cast, 401},
		{http.MethodGet, "/api/libraries", cast, 401},
		{http.MethodGet, "/api/files/1/stream?access_token=" + cast, "", 200},
		{http.MethodGet, "/api/libraries?access_token=" + cast, "", 401},
		{http.MethodGet, "/api/files/1/stream?access_token=" + media, "", 200},
		{http.MethodPost, "/api/files/1/playback", media, 401},
	} {
		if got := do(c.method, c.path, c.bearer); got != c.want {
			t.Errorf("%s %s (bearer %t) -> %d, want %d", c.method, c.path, c.bearer != "", got, c.want)
		}
	}
	// A playlist fetched with a cast token passes that token on.
	if do(http.MethodGet, "/api/playback/x/index.m3u8?access_token="+cast, "") != 200 || seenMedia != cast {
		t.Errorf("playlist token = %q", seenMedia)
	}
	// Without castRoute, cast tokens open nothing.
	off := s.Middleware(exempt, mediaPath, nil, nil)(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/files/1/stream?access_token="+cast, nil)
	rec := httptest.NewRecorder()
	off.ServeHTTP(rec, req)
	if rec.Code != 401 {
		t.Errorf("cast token with casting off -> %d", rec.Code)
	}
}
