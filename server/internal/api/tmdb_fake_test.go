package api

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/tmdb"
)

// fakeTMDB answers the TMDB calls the matcher and requests make, with a
// small fictional catalogue: two movies called Heat, The Matrix, and the
// show Harbor Lights (1396) with two seasons, and a same-name show (1397).
// A query containing "boom" fails as TMDB being down would.
func fakeTMDB(t *testing.T) *httptest.Server {
	t.Helper()
	releases := map[string]any{"results": []any{
		map[string]any{"iso_3166_1": "US", "release_dates": []any{map[string]any{"certification": "R", "type": 3}}},
	}}
	ratings := map[string]any{"results": []any{map[string]any{"iso_3166_1": "US", "rating": "TV-14"}}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		q := strings.ToLower(r.URL.Query().Get("query"))
		if strings.Contains(q, "boom") {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		var body any
		switch r.URL.Path {
		case "/search/movie":
			switch {
			case strings.Contains(q, "heat"):
				body = map[string]any{"results": []any{
					map[string]any{"id": 949, "title": "Heat", "release_date": "1995-12-15", "popularity": 50},
					map[string]any{"id": 950, "title": "Heat", "release_date": "1986-12-05", "popularity": 5},
				}}
			case strings.Contains(q, "matrix"):
				body = map[string]any{"results": []any{
					map[string]any{"id": 603, "title": "The Matrix", "release_date": "1999-03-30", "popularity": 80},
				}}
			default:
				body = map[string]any{"results": []any{}}
			}
		case "/search/tv":
			if strings.Contains(q, "harbor") {
				body = map[string]any{"results": []any{
					map[string]any{"id": 1396, "name": "Harbor Lights", "first_air_date": "2008-01-20", "popularity": 300, "origin_country": []string{"US"}},
				}}
			} else {
				body = map[string]any{"results": []any{}}
			}
		case "/movie/949":
			body = map[string]any{"id": 949, "title": "Heat", "release_date": "1995-12-15", "runtime": 170, "genres": []any{}, "release_dates": releases}
		case "/movie/603":
			body = map[string]any{"id": 603, "title": "The Matrix", "release_date": "1999-03-30", "runtime": 136, "genres": []any{}, "release_dates": releases}
		case "/movie/950":
			body = map[string]any{"id": 950, "title": "Heat", "release_date": "1986-12-05", "runtime": 101, "genres": []any{}, "release_dates": releases}
		case "/movie/949/release_dates", "/movie/950/release_dates", "/movie/603/release_dates":
			body = releases
		case "/tv/1396", "/tv/1397":
			id := 1396
			if r.URL.Path == "/tv/1397" {
				id = 1397
			}
			body = map[string]any{"id": id, "name": "Harbor Lights", "first_air_date": "2008-01-20", "status": "Returning Series", "genres": []any{},
				"content_ratings": ratings,
				"seasons": []any{
					map[string]any{"season_number": 1, "name": "Season 1", "episode_count": 2, "air_date": "2008-01-20"},
					map[string]any{"season_number": 2, "name": "Season 2", "episode_count": 1, "air_date": "2999-01-01"},
				}}
		case "/tv/1396/content_ratings", "/tv/1397/content_ratings":
			body = ratings
		case "/tv/1396/season/1", "/tv/1397/season/1":
			body = map[string]any{"season_number": 1, "name": "Season 1", "episodes": []any{
				map[string]any{"episode_number": 1, "season_number": 1, "name": "Pilot", "air_date": "2008-01-20"},
				map[string]any{"episode_number": 2, "season_number": 1, "name": "Tide", "air_date": "2008-01-27"},
			}}
		case "/tv/1396/season/2", "/tv/1397/season/2":
			body = map[string]any{"season_number": 2, "name": "Season 2", "episodes": []any{
				map[string]any{"episode_number": 1, "season_number": 2, "name": "Return", "air_date": "2999-01-01"},
			}}
		case "/movie/500":
			w.WriteHeader(http.StatusInternalServerError)
			return
		default:
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_ = json.NewEncoder(w).Encode(body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

// withTMDB gives a test server a TMDB client and matcher on fakeTMDB.
func withTMDB(t *testing.T) func(*Deps) {
	url := fakeTMDB(t).URL
	return func(d *Deps) {
		d.TMDB = tmdb.New("key", url)
		d.Matcher = metadata.New(d.Queries, d.TMDB, d.Bus, slog.New(slog.NewTextHandler(io.Discard, nil)), metadata.Options{Workers: 1, Watch: d.Watch})
	}
}
