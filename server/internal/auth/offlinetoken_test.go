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

func TestOfflineTokens(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	u, err := s.CreateUser(ctx, "kid", "kidpass1", false)
	if err != nil {
		t.Fatal(err)
	}
	session := login(t, s, "kid", "kidpass1")
	tok, exp, err := s.IssueOfflineToken(ctx, session, "rec1")
	if err != nil {
		t.Fatal(err)
	}
	if !IsOfflineToken(tok) || IsMediaToken(tok) || IsCastToken(tok) || time.Until(exp) > OfflineTokenTTL || time.Until(exp) < OfflineTokenTTL-time.Minute {
		t.Errorf("token %q expires %s", tok, exp)
	}
	got, record, err := s.AuthenticateOffline(ctx, tok)
	if err != nil || got.ID != u.ID || record != "rec1" {
		t.Fatalf("authenticate -> %+v %q, %v", got, record, err)
	}
	// No other kind passes for an offline token, nor it for them.
	media, _, _ := s.IssueMediaToken(ctx, session)
	if _, _, err := s.AuthenticateOffline(ctx, media); !errors.Is(err, ErrBadOfflineToken) {
		t.Errorf("media token as offline -> %v", err)
	}
	if _, _, err := s.AuthenticateOffline(ctx, offlineTokenPrefix+strings.TrimPrefix(media, mediaTokenPrefix)); !errors.Is(err, ErrBadOfflineToken) {
		t.Errorf("relabelled media token -> %v", err)
	}
	if _, err := s.AuthenticateMedia(ctx, mediaTokenPrefix+strings.TrimPrefix(tok, offlineTokenPrefix)); err == nil {
		t.Error("relabelled offline token passed as a media token")
	}
	if _, err := s.Authenticate(ctx, tok); err == nil {
		t.Error("offline token accepted as a session")
	}
	// Signing the device out ends its tokens.
	if err := s.Logout(ctx, session); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.AuthenticateOffline(ctx, tok); !errors.Is(err, ErrBadOfflineToken) {
		t.Errorf("after logout -> %v", err)
	}
	if _, _, err := s.IssueOfflineToken(ctx, session, "rec1"); err == nil {
		t.Error("issued an offline token for a signed-out session")
	}
}

// PLAN-DOWNLOADS.md §4.2: an offline token opens its own record's media
// routes and nothing else; media and cast tokens never open offline routes.
func TestMiddlewareOfflineTokens(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	_, _ = s.CreateUser(ctx, "a", "pass1234", false)
	session := login(t, s, "a", "pass1234")
	own, _, _ := s.IssueOfflineToken(ctx, session, "own")
	media, _, _ := s.IssueMediaToken(ctx, session)
	cast, _, _ := s.IssueCastToken(ctx, session)

	exempt := func(p string) bool { return !strings.HasPrefix(p, "/api/") }
	offlineRoute := func(p string) (string, bool) {
		id, rest, ok := strings.Cut(strings.TrimPrefix(p, "/api/offline/"), "/")
		if !strings.HasPrefix(p, "/api/offline/") || !ok || (rest != "stream" && !strings.HasPrefix(rest, "hls/") && !strings.HasPrefix(rest, "subtitles/")) {
			return "", false
		}
		return id, true
	}
	mediaPath := func(p string) bool {
		return !strings.HasPrefix(p, "/api/offline/") && (strings.HasSuffix(p, "/stream") || strings.HasSuffix(p, ".m3u8"))
	}
	castRoute := func(_, p string) bool { return mediaPath(p) }
	var seen Offline
	h := s.Middleware(exempt, mediaPath, castRoute, offlineRoute)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen, _ = OfflineFromContext(r.Context())
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
	q := func(tok string) string { return "?access_token=" + tok }
	for _, c := range []struct {
		method, path, bearer string
		want                 int
	}{
		{http.MethodGet, "/api/offline/own/stream" + q(own), "", 200},
		{http.MethodHead, "/api/offline/own/stream" + q(own), "", 200},
		{http.MethodGet, "/api/offline/own/hls/master.m3u8" + q(own), "", 200},
		{http.MethodGet, "/api/offline/own/subtitles/x0.vtt" + q(own), "", 200},
		{http.MethodGet, "/api/offline/own/stream", own, 200},
		// Another record's routes, the API and the plain media routes.
		{http.MethodGet, "/api/offline/other/stream" + q(own), "", 401},
		{http.MethodGet, "/api/offline/downloads" + q(own), "", 401},
		{http.MethodGet, "/api/libraries" + q(own), "", 401},
		{http.MethodGet, "/api/libraries", own, 401},
		{http.MethodGet, "/api/files/1/stream" + q(own), "", 401},
		{http.MethodPost, "/api/offline/own/stream" + q(own), "", 401},
		// Media and cast tokens never open an offline route.
		{http.MethodGet, "/api/offline/own/stream" + q(media), "", 401},
		{http.MethodGet, "/api/offline/own/stream" + q(cast), "", 401},
		{http.MethodGet, "/api/offline/own/stream", cast, 401},
		// The device's session does.
		{http.MethodGet, "/api/offline/own/stream", session, 200},
	} {
		if got := do(c.method, c.path, c.bearer); got != c.want {
			t.Errorf("%s %s (bearer %t) -> %d, want %d", c.method, c.path, c.bearer != "", got, c.want)
		}
	}
	if do(http.MethodGet, "/api/offline/own/hls/video.m3u8"+q(own), "") != 200 || seen.Record != "own" || seen.Token != own {
		t.Errorf("context = %+v", seen)
	}
	// Signing out ends the token.
	_ = s.Logout(ctx, session)
	if got := do(http.MethodGet, "/api/offline/own/stream"+q(own), ""); got != 401 {
		t.Errorf("after logout -> %d", got)
	}
}
