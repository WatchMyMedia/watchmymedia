package api

import (
	"fmt"
	"net/http"
	"reflect"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/trailers"
)

// getMovie lists a movie's YouTube trailers while Trailers from YouTube is
// on (the default), and the web app may then frame YouTube's player; off,
// neither, and the setting is kept.
func TestMovieTrailersFollowTheSetting(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	stored := trailers.Encode([]trailers.Trailer{{Key: "trailerKey1", Name: "Official Trailer", Type: "Trailer"}, {Key: "teaserKey01", Name: "Teaser", Type: "Teaser"}})
	movie, err := srv.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 949, Title: "Heat", Genres: "[]", Credits: "[]", Trailers: stored})
	if err != nil {
		t.Fatal(err)
	}
	heat, _ := srv.q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	_ = srv.q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: heat.ID})
	bare, err := srv.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 950, Title: "No Trailers", Genres: "[]", Credits: "[]", Trailers: "[]"})
	if err != nil {
		t.Fatal(err)
	}

	get := func(id int64) (MovieDetail, map[string]any) {
		t.Helper()
		var m MovieDetail
		res := doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/movies/%d", srv.URL, id), "", &m)
		var raw map[string]any
		doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/movies/%d", srv.URL, id), "", &raw)
		if res.StatusCode != http.StatusOK {
			t.Fatalf("getMovie -> %d", res.StatusCode)
		}
		return m, raw
	}
	csp := func() string {
		return doJSON(t, srv.client, http.MethodGet, srv.URL+"/", "", nil).Header.Get("Content-Security-Policy")
	}
	setting := func() bool {
		var s Settings
		doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &s)
		return s.TrailersYouTube
	}

	want := []MovieTrailer{{Source: "youtube", Name: "Official Trailer", Key: "trailerKey1"}, {Source: "youtube", Name: "Teaser", Key: "teaserKey01"}}
	if m, _ := get(movie.ID); !reflect.DeepEqual(m.Trailers, want) {
		t.Errorf("trailers = %+v", m.Trailers)
	}
	if _, raw := get(bare.ID); raw["trailers"] != nil {
		t.Errorf("a movie without trailers lists %v", raw["trailers"])
	}
	if !setting() {
		t.Error("Trailers from YouTube is off by default")
	}
	if c := csp(); !strings.Contains(c, "frame-src https://www.youtube-nocookie.com;") {
		t.Errorf("CSP while on: %q", c)
	}

	var s Settings
	if res := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings", `{"trailersYouTube":false}`, &s); res.StatusCode != http.StatusOK || s.TrailersYouTube {
		t.Fatalf("turning off -> %d, %v", res.StatusCode, s.TrailersYouTube)
	}
	if _, raw := get(movie.ID); raw["trailers"] != nil {
		t.Errorf("trailers while off: %v", raw["trailers"])
	}
	if c := csp(); strings.Contains(c, "frame-src") || strings.Contains(c, "youtube") {
		t.Errorf("CSP while off: %q", c)
	}
	if v, err := srv.q.GetSetting(ctx, settings.KeyTrailersYouTube); err != nil || v != "0" || setting() {
		t.Errorf("stored setting = %q, %v", v, err)
	}
	// Another setting saved leaves it alone.
	doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings", `{"backupKeep":3}`, &s)
	if s.TrailersYouTube {
		t.Error("saving another setting turned it on")
	}

	doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings", `{"trailersYouTube":true}`, &s)
	if m, _ := get(movie.ID); !s.TrailersYouTube || !reflect.DeepEqual(m.Trailers, want) {
		t.Errorf("on again: %v, %+v", s.TrailersYouTube, m.Trailers)
	}
	if c := csp(); !strings.Contains(c, "frame-src https://www.youtube-nocookie.com;") {
		t.Errorf("CSP on again: %q", c)
	}
}

// A server whose setting is off starts with it off.
func TestTrailersSettingReadAtStart(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		if err := d.Settings.Set(t.Context(), settings.KeyTrailersYouTube, "0"); err != nil {
			t.Fatal(err)
		}
	})
	var s Settings
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &s)
	if s.TrailersYouTube {
		t.Error("on at start")
	}
	if c := doJSON(t, srv.client, http.MethodGet, srv.URL+"/", "", nil).Header.Get("Content-Security-Policy"); strings.Contains(c, "youtube") {
		t.Errorf("CSP: %q", c)
	}
}

// YouTube's frame sits beside Turnstile's in one frame-src.
func TestContentSecurityPolicyFrames(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		turnstile, youtube bool
		frame              string
	}{
		{false, false, ""},
		{true, false, "frame-src https://challenges.cloudflare.com; "},
		{false, true, "frame-src https://www.youtube-nocookie.com; "},
		{true, true, "frame-src https://challenges.cloudflare.com https://www.youtube-nocookie.com; "},
	} {
		csp := contentSecurityPolicy(c.turnstile, false, c.youtube)
		if c.frame == "" && strings.Contains(csp, "frame-src") || c.frame != "" && !strings.Contains(csp, c.frame) {
			t.Errorf("turnstile %v, youtube %v: %q", c.turnstile, c.youtube, csp)
		}
		if strings.Count(csp, "frame-src") > 1 {
			t.Errorf("two frame-src: %q", csp)
		}
	}
}
