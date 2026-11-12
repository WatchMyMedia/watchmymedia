package tmdb_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/time/rate"

	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

func TestSearchMoviesSendsKeyAndYear(t *testing.T) {
	f := tmdbtest.New(t)
	f.Handle("/search/movie", func(r *http.Request) any {
		if r.URL.Query().Get("year") != "1999" || r.URL.Query().Get("query") != "The Matrix" {
			t.Errorf("unexpected request %s", r.URL)
		}
		return map[string]any{"results": []any{map[string]any{"id": 603, "title": "The Matrix", "release_date": "1999-03-30", "popularity": 80.5}}, "total_results": 1}
	})
	res, err := f.Client().SearchMovies(context.Background(), "The Matrix", 1999)
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || res[0].ID != 603 || tmdb.Year(res[0].ReleaseDate) != 1999 {
		t.Errorf("results = %+v", res)
	}
	if n := f.Count("/search/movie?The Matrix&1999"); n != 1 {
		t.Errorf("requests = %d", n)
	}
}

func TestBearerToken(t *testing.T) {
	f := tmdbtest.New(t)
	f.SetKey("eyJabc.def.ghi")
	f.Handle("/tv/1", func(r *http.Request) any {
		if r.URL.Query().Has("api_key") {
			t.Error("api_key must not be sent with a bearer token")
		}
		return map[string]any{"id": 1, "name": "X", "seasons": []any{}}
	})
	// The fake answers 401 unless the token came as a bearer token.
	if _, err := f.Client().GetTV(context.Background(), 1); err != nil {
		t.Fatal(err)
	}
}

func TestErrors(t *testing.T) {
	f := tmdbtest.New(t)
	f.Handle("/movie/2", func(*http.Request) any { return tmdbtest.Reply{Status: http.StatusUnauthorized} })
	f.Handle("/movie/3", func(*http.Request) any {
		return tmdbtest.Reply{Status: http.StatusInternalServerError, Body: `{"status_message":"boom"}`}
	})
	f.Handle("/movie/4", func(*http.Request) any { return tmdbtest.Reply{Body: `{"id": 4, "title": `} })
	c := f.Client()
	ctx := context.Background()
	if _, err := c.GetMovie(ctx, 1); !errors.Is(err, tmdb.ErrNotFound) {
		t.Errorf("404 -> %v", err)
	}
	if _, err := c.GetMovie(ctx, 2); !errors.Is(err, tmdb.ErrUnauthorized) {
		t.Errorf("401 -> %v", err)
	}
	// A 5xx is tried twice more before it is an error.
	if _, err := c.GetMovie(ctx, 3); err == nil || err.Error() != "tmdb: /movie/3 returned 500: boom" {
		t.Errorf("500 -> %v", err)
	}
	if n := f.Count("/movie/3"); n != 3 {
		t.Errorf("500 asked %d times, want 3", n)
	}
	if _, err := c.GetMovie(ctx, 4); err == nil || !strings.Contains(err.Error(), "decode /movie/4") {
		t.Errorf("malformed JSON -> %v", err)
	}
	if n := f.Count("/movie/4"); n != 1 {
		t.Errorf("malformed JSON asked %d times, want 1", n)
	}
	if _, err := tmdb.New("", "").GetMovie(ctx, 1); !errors.Is(err, tmdb.ErrNoAPIKey) {
		t.Errorf("no key -> %v", err)
	}
}

func TestRetryOn5xx(t *testing.T) {
	f := tmdbtest.New(t)
	f.Handle("/tv/5/season/1", func(*http.Request) any {
		if f.Count("/tv/5/season/1") == 1 {
			return tmdbtest.Reply{Status: http.StatusBadGateway}
		}
		return map[string]any{"id": 5, "season_number": 1, "episodes": []any{map[string]any{"id": 9, "episode_number": 1, "name": "Pilot"}}}
	})
	s, err := f.Client().GetSeason(context.Background(), 5, 1)
	if err != nil || len(s.Episodes) != 1 {
		t.Fatalf("season = %+v, %v", s, err)
	}
}

func TestRetryOn429(t *testing.T) {
	f := tmdbtest.New(t)
	f.Handle("/tv/5/season/1", func(*http.Request) any {
		if f.Count("/tv/5/season/1") == 1 {
			return tmdbtest.Reply{Status: http.StatusTooManyRequests, Header: http.Header{"Retry-After": {"0"}}}
		}
		return map[string]any{"id": 5, "season_number": 1, "episodes": []any{map[string]any{"id": 9, "episode_number": 1, "name": "Pilot"}}}
	})
	s, err := f.Client().GetSeason(context.Background(), 5, 1)
	if err != nil {
		t.Fatal(err)
	}
	if n := f.Count("/tv/5/season/1"); n != 2 || len(s.Episodes) != 1 || s.Episodes[0].Name != "Pilot" {
		t.Errorf("calls = %d season = %+v", n, s)
	}
}

func TestRateLimitedTooOften(t *testing.T) {
	f := tmdbtest.New(t)
	f.Handle("/tv/5", func(*http.Request) any {
		return tmdbtest.Reply{Status: http.StatusTooManyRequests, Header: http.Header{"Retry-After": {"0"}},
			Body: `{"status_message":"Your request count is over the allowed limit."}`}
	})
	_, err := f.Client().GetTV(context.Background(), 5)
	if err == nil || !strings.Contains(err.Error(), "returned 429") {
		t.Errorf("err = %v", err)
	}
	if n := f.Count("/tv/5"); n != 4 {
		t.Errorf("asked %d times, want 4 (three retries)", n)
	}
}

// Retry-After may be an HTTP date. The backoff is an hour here, so the call
// only finishes in time if the date was read.
func TestRetryAfterAsADate(t *testing.T) {
	f := tmdbtest.New(t)
	f.Handle("/tv/5", func(*http.Request) any {
		if f.Count("/tv/5") == 1 {
			past := time.Now().Add(-time.Minute).UTC().Format(http.TimeFormat)
			return tmdbtest.Reply{Status: http.StatusTooManyRequests, Header: http.Header{"Retry-After": {past}}}
		}
		return map[string]any{"id": 5, "name": "Show"}
	})
	c := tmdb.NewWith(f.APIKey(), f.URL, tmdb.Options{Limiter: rate.NewLimiter(rate.Inf, 0), Backoff: time.Hour})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if tv, err := c.GetTV(ctx, 5); err != nil || tv.Name != "Show" {
		t.Errorf("show = %+v, %v", tv, err)
	}
}

func TestRetryOnTimeout(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			<-r.Context().Done() // the first answer never comes
			return
		}
		_, _ = w.Write([]byte(`{"id":5,"name":"Show"}`))
	}))
	defer srv.Close()
	c := tmdb.NewWith("key", srv.URL, tmdb.Options{Limiter: rate.NewLimiter(rate.Inf, 0), Backoff: time.Millisecond,
		HTTP: &http.Client{Timeout: 100 * time.Millisecond}})
	if tv, err := c.GetTV(context.Background(), 5); err != nil || tv.Name != "Show" || calls.Load() != 2 {
		t.Errorf("show = %+v, %v after %d calls", tv, err, calls.Load())
	}
}

// Verify counts against the client's own rate budget.
func TestVerifySharesTheLimiter(t *testing.T) {
	f := tmdbtest.New(t)
	f.JSON("/configuration", map[string]any{"images": map[string]any{}})
	lim := rate.NewLimiter(rate.Every(time.Hour), 1)
	c := tmdb.NewWith("other", f.URL, tmdb.Options{Limiter: lim})
	if err := c.Verify(context.Background(), f.APIKey()); err != nil {
		t.Fatal(err)
	}
	if lim.Tokens() >= 1 {
		t.Errorf("Verify left the client's budget untouched (%v tokens)", lim.Tokens())
	}
}

func TestCertifications(t *testing.T) {
	f := tmdbtest.New(t)
	f.Handle("/movie/603", func(r *http.Request) any {
		if r.URL.Query().Get("append_to_response") != "credits,release_dates,videos" {
			t.Errorf("append_to_response = %q", r.URL.Query().Get("append_to_response"))
		}
		return tmdbtest.Reply{Body: `{"id":603,"title":"The Matrix","release_dates":{"results":[
			{"iso_3166_1":"GB","release_dates":[{"certification":"15","type":3}]},
			{"iso_3166_1":"US","release_dates":[{"certification":"","type":1},{"certification":"NR","type":4},{"certification":"R","type":3}]}]}}`}
	})
	f.Handle("/tv/1396", func(*http.Request) any {
		return tmdbtest.Reply{Body: `{"id":1396,"name":"Breaking Bad","content_ratings":{"results":[{"iso_3166_1":"DE","rating":"16"},{"iso_3166_1":"US","rating":"TV-MA"}]}}`}
	})
	f.Handle("/movie/11/release_dates", func(*http.Request) any {
		return tmdbtest.Reply{Body: `{"id":11,"results":[{"iso_3166_1":"US","release_dates":[{"certification":"","type":3},{"certification":"PG","type":5}]}]}`}
	})
	f.Handle("/tv/2/content_ratings", func(*http.Request) any {
		return tmdbtest.Reply{Body: `{"id":2,"results":[{"iso_3166_1":"FR","rating":"12"}]}`}
	})
	c := f.Client()
	ctx := context.Background()
	m, err := c.GetMovie(ctx, 603)
	if err != nil {
		t.Fatal(err)
	}
	if got := m.ReleaseDates.Certification("US"); got != "R" {
		t.Errorf("movie certification = %q, want the theatrical R", got)
	}
	s, err := c.GetTV(ctx, 1396)
	if err != nil {
		t.Fatal(err)
	}
	if got := s.ContentRatings.Certification("US"); got != "TV-MA" {
		t.Errorf("show certification = %q", got)
	}
	rd, err := c.GetMovieReleaseDates(ctx, 11)
	if err != nil {
		t.Fatal(err)
	}
	if got := rd.Certification("US"); got != "PG" {
		t.Errorf("release dates certification = %q, want the first one given", got)
	}
	cr, err := c.GetTVContentRatings(ctx, 2)
	if err != nil {
		t.Fatal(err)
	}
	if got := cr.Certification("US"); got != "" {
		t.Errorf("no US rating = %q", got)
	}
}

// The key travels in the query, and the http.Client's errors print the URL:
// a failed call must not put the key in the error, which is logged and
// stored as a file's match error.
func TestTransportErrorHidesTheKey(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	addr := srv.URL
	srv.Close() // nothing listens there now
	_, err := tmdb.New("secret-key-123", addr).GetMovie(context.Background(), 603)
	if err == nil {
		t.Fatal("no error from a closed server")
	}
	if msg := err.Error(); strings.Contains(msg, "secret-key-123") || !strings.Contains(msg, "/movie/603") {
		t.Errorf("error = %q", msg)
	}

	hang := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	defer hang.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	_, err = tmdb.New("secret-key-123", hang.URL).GetMovie(ctx, 603)
	if !errors.Is(err, context.DeadlineExceeded) || strings.Contains(err.Error(), "secret-key-123") {
		t.Errorf("deadline error = %v", err)
	}
}

// GetMovie asks for the videos with the rest of the details, in English or
// no language; GetMovieVideos asks for them alone.
func TestMovieVideos(t *testing.T) {
	f := tmdbtest.New(t)
	video := map[string]any{"key": "abcdefghijk", "name": "Official Trailer", "site": "YouTube", "type": "Trailer",
		"official": true, "iso_639_1": "en", "size": 2160, "published_at": "2024-06-01T16:00:00.000Z"}
	f.Handle("/movie/700", func(r *http.Request) any {
		q := r.URL.Query()
		if q.Get("append_to_response") != "credits,release_dates,videos" || q.Get("include_video_language") != "en,null" {
			t.Errorf("unexpected request %s", r.URL)
		}
		return map[string]any{"id": 700, "title": "Film", "videos": map[string]any{"results": []any{video}}}
	})
	f.Handle("/movie/700/videos", func(r *http.Request) any {
		if r.URL.Query().Get("include_video_language") != "en,null" {
			t.Errorf("unexpected request %s", r.URL)
		}
		return map[string]any{"id": 700, "results": []any{video, map[string]any{"key": "x", "site": "Vimeo", "type": "Clip", "iso_639_1": nil}}}
	})
	want := tmdb.Video{Key: "abcdefghijk", Name: "Official Trailer", Site: "YouTube", Type: "Trailer", Official: true, Language: "en", Size: 2160, PublishedAt: "2024-06-01T16:00:00.000Z"}
	m, err := f.Client().GetMovie(context.Background(), 700)
	if err != nil {
		t.Fatal(err)
	}
	if len(m.Videos.Results) != 1 || m.Videos.Results[0] != want {
		t.Errorf("GetMovie videos = %+v", m.Videos.Results)
	}
	v, err := f.Client().GetMovieVideos(context.Background(), 700)
	if err != nil {
		t.Fatal(err)
	}
	if len(v.Results) != 2 || v.Results[0] != want || v.Results[1].Language != "" {
		t.Errorf("GetMovieVideos = %+v", v.Results)
	}
}
