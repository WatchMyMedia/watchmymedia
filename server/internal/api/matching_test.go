package api

import (
	"fmt"
	"net/http"
	"testing"

	"watchmymedia/server/internal/db"
)

// Searching TMDB for a file and matching it by hand.
func TestFileCandidatesAndMatch(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, withTMDB(t))
	heat, _ := srv.q.GetMediaFileByPath(t.Context(), "/m/Heat (1995).mkv")
	base := fmt.Sprintf("%s/api/files/%d", srv.URL, heat.ID)

	// The parsed title and year by default, or what the person typed.
	var list CandidateList
	if code := doJSON(t, srv.client, http.MethodGet, base+"/candidates", "", &list).StatusCode; code != 200 || len(list.Items) == 0 || list.Items[0].TMDBID != 949 {
		t.Fatalf("candidates -> %d %+v", code, list.Items)
	}
	if code := doJSON(t, srv.client, http.MethodGet, base+"/candidates?q=matrix&year=1999", "", &list).StatusCode; code != 200 || len(list.Items) != 1 || list.Items[0].TMDBID != 603 {
		t.Errorf("typed search -> %d %+v", code, list.Items)
	}
	if code := doJSON(t, srv.client, http.MethodGet, base+"/candidates?q=boom", "", nil).StatusCode; code != http.StatusBadGateway {
		t.Errorf("TMDB down -> %d, want 502", code)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/files/999/candidates", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("missing file -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/libraries/1/candidates?q=heat", "", &list).StatusCode; code != 200 || len(list.Items) != 2 {
		t.Errorf("library candidates -> %d %+v", code, list.Items)
	}

	ch, cancel := srv.bus.Subscribe()
	defer cancel()
	var file MediaFileDetail
	if code := doJSON(t, srv.client, http.MethodPost, base+"/match", `{"tmdbId":949}`, &file).StatusCode; code != 200 || file.MatchStatus != "matched" || !file.MatchLocked || file.MovieID == nil {
		t.Fatalf("match -> %d %+v", code, file)
	}
	if e := <-ch; e.Type != "library.changed" {
		t.Errorf("event %q, want library.changed", e.Type)
	}
	m, err := srv.q.GetMovie(t.Context(), *file.MovieID)
	if err != nil || m.TmdbID != 949 || deref(m.ContentRating) != "R" {
		t.Errorf("movie = %+v (%v)", m, err)
	}
	if code := doJSON(t, srv.client, http.MethodPost, base+"/match", `{"tmdbId":12345}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("unknown TMDB movie -> %d, want 422", code)
	}
}

// Without a TMDB key, everything that needs it says so.
func TestMatchingNeedsTMDB(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	for _, c := range []struct{ method, path, body string }{
		{http.MethodGet, "/api/files/1/candidates", ""},
		{http.MethodPost, "/api/files/1/match", `{"tmdbId":949}`},
		{http.MethodGet, "/api/libraries/1/candidates?q=heat", ""},
		{http.MethodPost, "/api/libraries/1/match", ""},
		{http.MethodPost, "/api/libraries/1/lineup", `{"fileIds":[1],"tmdbId":949}`},
	} {
		if code := doJSON(t, srv.client, c.method, srv.URL+c.path, c.body, nil).StatusCode; code != http.StatusServiceUnavailable {
			t.Errorf("%s %s -> %d, want 503", c.method, c.path, code)
		}
	}
	movie, err := srv.q.UpsertMovie(t.Context(), db.UpsertMovieParams{LibraryID: 1, TmdbID: 949, Title: "Heat", Genres: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	// The preview used to reach the matcher without the check, and panic.
	if code := doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/movies/%d/rematch?tmdbId=603", srv.URL, movie.ID), "", nil).StatusCode; code != http.StatusServiceUnavailable {
		t.Errorf("movie rematch preview -> %d, want 503", code)
	}
}

// Moving a movie's and a show's files to another TMDB item.
func TestRematchRoutes(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, withTMDB(t))
	ctx := t.Context()
	heat, _ := srv.q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	base := fmt.Sprintf("%s/api/files/%d", srv.URL, heat.ID)
	var file MediaFileDetail
	if code := doJSON(t, srv.client, http.MethodPost, base+"/match", `{"tmdbId":950}`, &file).StatusCode; code != 200 || file.MovieID == nil {
		t.Fatalf("match -> %d", code)
	}
	movie := fmt.Sprintf("%s/api/movies/%d/rematch", srv.URL, *file.MovieID)
	var plan RematchPreviewOutput
	if code := doJSON(t, srv.client, http.MethodGet, movie+"?tmdbId=949", "", &plan.Body).StatusCode; code != 200 {
		t.Errorf("movie preview -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodPost, movie, `{"tmdbId":950}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("rematch to the same movie -> %d, want 422", code)
	}
	if code := doJSON(t, srv.client, http.MethodPost, movie, `{"tmdbId":949}`, nil).StatusCode; code != 200 {
		t.Fatalf("movie rematch -> %d", code)
	}
	if f, _ := srv.q.GetMediaFile(ctx, heat.ID); f.MovieID == nil {
		t.Error("file lost its movie")
	} else if m, _ := srv.q.GetMovie(ctx, *f.MovieID); m.TmdbID != 949 {
		t.Errorf("file's movie is TMDB %d, want 949", m.TmdbID)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/movies/999/rematch?tmdbId=949", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("missing movie preview -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/movies/999/rematch", `{"tmdbId":949}`, nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("missing movie rematch -> %d", code)
	}

	// A show matched by hand to 1396, then moved to the same-name 1397.
	tv, _ := srv.q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	ep, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: "/t/Harbor Lights/Season 1/Harbor Lights - S01E01.mkv", Size: 1,
		Mtime: "2026-01-01T00:00:00Z", AddedAt: "2026-01-01T00:00:00Z", LastSeenAt: "2026-01-01T00:00:00Z",
		Parsed: `{"kind":"episode","title":"Harbor Lights","season":1,"episodes":[1]}`})
	if err != nil {
		t.Fatal(err)
	}
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/api/files/%d/match", srv.URL, ep.ID), `{"tmdbId":1396}`, nil).StatusCode; code != 200 {
		t.Fatalf("episode match -> %d", code)
	}
	show, err := srv.q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: tv.ID, TmdbID: 1396})
	if err != nil {
		t.Fatal(err)
	}
	showURL := fmt.Sprintf("%s/api/shows/%d/rematch", srv.URL, show.ID)
	if code := doJSON(t, srv.client, http.MethodGet, showURL+"?tmdbId=1397", "", &plan.Body).StatusCode; code != 200 || plan.Body.Files != 1 {
		t.Errorf("show preview -> %d %+v", code, plan.Body)
	}
	if code := doJSON(t, srv.client, http.MethodPost, showURL, `{"tmdbId":1396}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("rematch to the same show -> %d, want 422", code)
	}
	if code := doJSON(t, srv.client, http.MethodPost, showURL, `{"tmdbId":1397}`, nil).StatusCode; code != 200 {
		t.Fatalf("show rematch -> %d", code)
	}
	if _, err := srv.q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: tv.ID, TmdbID: 1397}); err != nil {
		t.Errorf("no show 1397 after the rematch: %v", err)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/shows/999/rematch?tmdbId=1397", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("missing show preview -> %d", code)
	}
}
