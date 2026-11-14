package api

import (
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

// A decision carries the media token createMediaToken would give the same
// request (API level 28), so a player starts without asking for one: for a
// browser's cookie and a device's bearer session, and not for a cast token,
// which createMediaToken refuses too.
func TestDecisionCarriesMediaToken(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, castServerDeps)
	path := filepath.Join(t.TempDir(), "Clip (2020).mp4")
	if err := os.WriteFile(path, []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"mov","durationMs":5000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2,"default":true}],"subtitles":[]}`
	now := db.FormatTime(time.Now())
	f, err := srv.q.UpsertMediaFile(t.Context(), db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 16, Mtime: now, AddedAt: now, Parsed: "{}",
		Probe: &probeJSON, LastSeenAt: now, SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	decide := func(c *http.Client, header ...string) (reply, playback.Decision) {
		t.Helper()
		var d playback.Decision
		res := doJSON(t, c, http.MethodPost, srv.URL+fmt.Sprintf("/api/files/%d/playback", f.ID),
			`{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]}}`, &d, header...)
		if res.StatusCode != http.StatusOK || d.Mode != playback.ModeDirect {
			t.Fatalf("decision -> %d %s", res.StatusCode, res.Body)
		}
		return res, d
	}
	anon := &http.Client{}
	status := func(path, token string) int {
		t.Helper()
		sep := "?"
		if strings.Contains(path, "?") {
			sep = "&"
		}
		return doJSON(t, anon, http.MethodGet, srv.URL+path+sep+auth.QueryParam+"="+token, "", nil).StatusCode
	}

	_, d := decide(srv.client)
	mt := d.MediaToken
	if mt == nil || !auth.IsMediaToken(mt.Token) || mt.Param != auth.QueryParam || mt.ExpiresAt.Location() != time.UTC {
		t.Fatalf("browser decision's media token = %+v", mt)
	}
	// The same lifetime as createMediaToken's, which is asked after it.
	var separate MediaToken
	if res := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/auth/media-token", "", &separate); res.StatusCode != http.StatusOK {
		t.Fatalf("media token -> %d", res.StatusCode)
	}
	if gap := separate.ExpiresAt.Sub(mt.ExpiresAt); gap < 0 || gap > 5*time.Second || time.Until(mt.ExpiresAt) < auth.MediaTokenTTL-time.Minute {
		t.Errorf("decision's token expires %v, createMediaToken's %v", mt.ExpiresAt, separate.ExpiresAt)
	}
	// The same scope: it opens the decision's stream and nothing but media.
	if code := status(d.URL, mt.Token); code != http.StatusOK {
		t.Errorf("stream with the decision's token -> %d", code)
	}
	if code := status("/api/libraries", mt.Token); code != http.StatusUnauthorized {
		t.Errorf("API route with the decision's token -> %d", code)
	}

	tv := deviceLogin(t, srv.URL, "admin", "password1", "tv-1", "Living room TV")
	if _, d := decide(nil, "Authorization", "Bearer "+tv.Token); d.MediaToken == nil || !auth.IsMediaToken(d.MediaToken.Token) {
		t.Errorf("device decision's media token = %+v", d.MediaToken)
	}

	var ct CastToken
	if res := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/auth/cast-token", "", &ct); res.StatusCode != http.StatusOK {
		t.Fatalf("cast token -> %d", res.StatusCode)
	}
	if res, d := decide(anon, "Authorization", "Bearer "+ct.Token); d.MediaToken != nil || strings.Contains(string(res.Body), `"mediaToken"`) {
		t.Errorf("cast decision has a media token: %s", res.Body)
	}
}

// A failed decision stays failed, and a request without a session gets no
// token, whichever decision route made it.
func TestWithMediaTokenLeavesOthersAlone(t *testing.T) {
	t.Parallel()
	deps := Deps{Auth: &auth.Service{}}
	failed := errors.New("no decision")
	if out, err := withMediaToken(t.Context(), deps)(nil, failed); out != nil || !errors.Is(err, failed) {
		t.Errorf("failed decision -> %v, %v", out, err)
	}
	out, err := withMediaToken(t.Context(), deps)(&PlaybackOutput{}, nil)
	if err != nil || out.Body.MediaToken != nil {
		t.Errorf("no session -> %+v, %v", out.Body.MediaToken, err)
	}
}
