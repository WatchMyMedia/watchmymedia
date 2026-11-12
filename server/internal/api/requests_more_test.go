package api

import (
	"io"
	"log/slog"
	"net/http"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/seerr"
	"watchmymedia/server/internal/integrations/seerrtest"
)

func withIntegrations(d *Deps) {
	d.Integrations = integrations.New(d.Queries, &pathRecorder{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

// harborLights puts show 1396 in a TV library with S01E01 on disk, and
// matches the movie library's Heat to 949.
func harborLights(t *testing.T, srv *testEnv) {
	t.Helper()
	ctx := t.Context()
	heat, _ := srv.q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	movie, err := srv.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: heat.LibraryID, TmdbID: 949, Title: "Heat", Genres: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: heat.ID}); err != nil {
		t.Fatal(err)
	}
	tv, _ := srv.q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	show, _ := srv.q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 1396, Title: "Harbor Lights", Genres: "[]"})
	s1, _ := srv.q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 2})
	s2, _ := srv.q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 2, Name: "Season 2", EpisodeCount: 1})
	aired, later := "2008-01-20", "2999-01-01"
	ep, _ := srv.q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: show.ID, Number: 1, Title: "Pilot", AirDate: &aired})
	_, _ = srv.q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: show.ID, Number: 2, Title: "Tide", AirDate: &aired})
	_, _ = srv.q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s2.ID, ShowID: show.ID, Number: 1, Title: "Return", AirDate: &later})
	now := db.FormatTime(time.Now())
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: "/t/Harbor Lights/S01E01.mkv", Size: 1, Mtime: now, AddedAt: now, LastSeenAt: now, Parsed: "{}"})
	if err != nil {
		t.Fatal(err)
	}
	_ = srv.q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: f.ID})
	_ = srv.q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", ID: f.ID})
}

// Requests from TMDB, without Seerr: where each result stands against the
// library, and each season of a show.
func TestRequestsFromTMDB(t *testing.T) {
	t.Parallel()
	tmdb := withTMDB(t)
	srv := newTestServerWith(t, func(d *Deps) { tmdb(d); withIntegrations(d) })
	harborLights(t, srv)

	var found RequestableList
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/search?q=heat", "", &found).StatusCode; code != 200 || len(found.Items) != 2 {
		t.Fatalf("search -> %d %+v", code, found.Items)
	}
	if r := found.Items[0]; r.TMDBID != 949 || r.State != "available" || r.MovieID == 0 {
		t.Errorf("Heat (1995) = %+v, want available in the library", r)
	}
	if r := found.Items[1]; r.TMDBID != 950 || r.State != "requestable" {
		t.Errorf("Heat (1986) = %+v, want requestable", r)
	}

	var show Requestable
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/lookup?mediaType=tv&tmdbId=1396", "", &show).StatusCode; code != 200 || show.State != "available" || show.ShowID == 0 {
		t.Fatalf("lookup show -> %d %+v", code, show)
	}
	if len(show.Seasons) != 2 || show.Seasons[0].State != "partial" || show.Seasons[1].State != "unaired" {
		t.Errorf("seasons = %+v, want season 1 partial and season 2 unaired", show.Seasons)
	}
	var movie Requestable
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/lookup?mediaType=movie&tmdbId=603", "", &movie).StatusCode; code != 200 || movie.State != "requestable" || movie.Title != "The Matrix" {
		t.Errorf("lookup movie -> %d %+v", code, movie)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/lookup?mediaType=movie&tmdbId=12345", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("unknown to TMDB -> %d, want 404", code)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/lookup?mediaType=tv&tmdbId=500", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("unknown show -> %d, want 404", code)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/lookup?mediaType=movie&tmdbId=500", "", nil).StatusCode; code != http.StatusBadGateway {
		t.Errorf("TMDB failing -> %d, want 502", code)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/search?q=boom", "", nil).StatusCode; code != http.StatusBadGateway {
		t.Errorf("TMDB down -> %d, want 502", code)
	}
}

func TestRequestsNeedTMDBAndIntegrations(t *testing.T) {
	t.Parallel()
	off := newTestServer(t)
	for _, p := range []string{"/api/requests/search?q=heat", "/api/requests/lookup?mediaType=movie&tmdbId=949", "/api/requests/mine", "/api/requests/inbox", "/api/requests/settings"} {
		if code := doJSON(t, off.client, http.MethodGet, off.URL+p, "", nil).StatusCode; code != http.StatusServiceUnavailable {
			t.Errorf("%s without integrations -> %d, want 503", p, code)
		}
	}
	noKey := newTestServerWith(t, withIntegrations)
	for _, p := range []string{"/api/requests/search?q=heat", "/api/requests/lookup?mediaType=movie&tmdbId=949"} {
		if code := doJSON(t, noKey.client, http.MethodGet, noKey.URL+p, "", nil).StatusCode; code != http.StatusServiceUnavailable {
			t.Errorf("%s without a TMDB key -> %d, want 503", p, code)
		}
	}
	var opts RequestOptions
	if code := doJSON(t, off.client, http.MethodGet, off.URL+"/api/requests/options", "", &opts).StatusCode; code != 200 || opts.Backend != "none" || !opts.Problems {
		t.Errorf("options without integrations -> %d %+v", code, opts)
	}
}

// A show looked up in Seerr: its seasons against the library.
func TestSeerrSeasonStates(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, withIntegrations)
	harborLights(t, srv)
	app := seerrtest.New(t)
	app.Items = []*seerrtest.Item{{MediaType: "tv", TMDBID: 1396, Title: "Harbor Lights", Date: "2008-01-20", Seasons: []seerr.Season{
		{SeasonNumber: 1, Name: "Season 1", EpisodeCount: 2, AirDate: "2008-01-20"},
		{SeasonNumber: 2, Name: "Season 2", EpisodeCount: 1, AirDate: "2999-01-01"},
		{SeasonNumber: 3, Name: "Season 3", EpisodeCount: 8, AirDate: "2010-01-01"},
	}}}
	app.Users = append(app.Users, seerr.User{ID: 2, DisplayName: "admin", Username: "admin", Permissions: seerr.PermRequest})
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/integrations", `{"kind":"seerr","name":"Seerr","url":"`+app.URL+`","apiKey":"`+app.Key+`"}`, nil).StatusCode; code != http.StatusCreated {
		t.Fatalf("connect Seerr -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodPatch, srv.URL+"/api/users/1", `{"seerrUserId":2}`, nil).StatusCode; code != 200 {
		t.Fatalf("link -> %d", code)
	}
	var show Requestable
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/lookup?mediaType=tv&tmdbId=1396", "", &show).StatusCode; code != 200 || show.State != "available" {
		t.Fatalf("lookup -> %d %+v", code, show)
	}
	want := []string{"partial", "unaired", "missing"}
	if len(show.Seasons) != len(want) {
		t.Fatalf("seasons = %+v", show.Seasons)
	}
	for i, s := range show.Seasons {
		if s.State != want[i] {
			t.Errorf("season %d = %s, want %s", s.Number, s.State, want[i])
		}
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/requests/lookup?mediaType=tv&tmdbId=4242", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("unknown to Seerr -> %d, want 404", code)
	}
}

func TestSeasonState(t *testing.T) {
	t.Parallel()
	have := func(available, aired int64) db.ListSeasonAvailabilityByTMDBRow {
		return db.ListSeasonAvailabilityByTMDBRow{AvailableCount: available, AiredCount: aired}
	}
	today := "2026-11-01"
	cases := []struct {
		name    string
		have    db.ListSeasonAvailabilityByTMDBRow
		taken   bool
		airDate string
		want    string
	}{
		{"every aired episode", have(8, 8), false, "2020-01-01", "available"},
		{"more than aired", have(9, 8), true, "2020-01-01", "available"},
		{"asked for", have(0, 8), true, "2020-01-01", "requested"},
		{"some, asked for", have(3, 8), true, "2020-01-01", "requested"},
		{"some", have(3, 8), false, "2020-01-01", "partial"},
		{"none, later", have(0, 0), false, "2027-01-01", "unaired"},
		{"none, no date", have(0, 0), false, "", "unaired"},
		{"none, aired", have(0, 8), false, "2020-01-01", "missing"},
	}
	for _, c := range cases {
		if got := seasonState(c.have, c.taken, c.airDate, today); got != c.want {
			t.Errorf("%s: %s, want %s", c.name, got, c.want)
		}
	}
}
