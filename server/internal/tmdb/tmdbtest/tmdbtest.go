// Package tmdbtest is a fake TMDB for tests: the routes a test sets,
// answered as JSON, with every request counted. Titles and IDs in tests are
// fictional.
package tmdbtest

import (
	"encoding/json"
	"fmt"
	"maps"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"golang.org/x/time/rate"

	"watchmymedia/server/internal/tmdb"
)

// Handler answers a request with a value sent as JSON, a Reply, or nil for
// a 404.
type Handler func(r *http.Request) any

// Reply is an answer other than a 200 with JSON: a status, headers and a
// body sent as it is.
type Reply struct {
	Status int
	Header http.Header
	Body   string
}

// Server is a fake TMDB. Routes may be added between requests.
type Server struct {
	*httptest.Server

	mu       sync.Mutex
	key      string
	routes   map[string]Handler
	fallback Handler
	requests map[string]int
	total    int
	inMemory bool
}

// Key is the API key the fake accepts unless SetKey changes it.
const Key = "test-key"

// New starts a fake that answers 404 to everything until routes are set.
func New(t testing.TB) *Server {
	t.Helper()
	s := &Server{key: Key, routes: map[string]Handler{}, requests: map[string]int{}}
	s.Server = httptest.NewServer(http.HandlerFunc(s.serve))
	t.Cleanup(s.Close)
	return s
}

// NewTest is New on an in-memory network, for a synctest bubble, where a
// real listener would keep the bubble from ever being idle. Only Client
// reaches it.
func NewTest(t testing.TB) *Server {
	t.Helper()
	s := &Server{key: Key, routes: map[string]Handler{}, requests: map[string]int{}, inMemory: true}
	s.Server = httptest.NewTestServer(t, http.HandlerFunc(s.serve))
	t.Cleanup(s.Close)
	return s
}

// Client is a client of the fake with the fake's key, no rate limit and
// millisecond backoffs.
func (s *Server) Client() *tmdb.Client {
	opts := tmdb.Options{Limiter: rate.NewLimiter(rate.Inf, 0), Backoff: time.Millisecond}
	if s.inMemory {
		opts.HTTP = s.Server.Client()
	}
	return tmdb.NewWith(s.APIKey(), s.URL, opts)
}

// SetKey makes the fake accept another key, given as the api_key parameter
// or, for a v4 token ("eyJ…"), as a bearer token.
func (s *Server) SetKey(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.key = key
}

// APIKey is the key the fake accepts.
func (s *Server) APIKey() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.key
}

// Handle answers requests for path with h.
func (s *Server) Handle(path string, h Handler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.routes[path] = h
}

// JSON answers requests for path with v.
func (s *Server) JSON(path string, v any) { s.Handle(path, func(*http.Request) any { return v }) }

// Fallback answers requests no route takes; without it they get a 404.
func (s *Server) Fallback(h Handler) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.fallback = h
}

// Count is how many requests the fake got for key: a path ("/tv/1396"), or
// for a search, the path, the query and the year ("/search/tv?Name&2024",
// "/search/tv?Name&" without one).
func (s *Server) Count(key string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.requests[key]
}

// Total is how many requests the fake got.
func (s *Server) Total() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.total
}

// RequestKey is what Count counts a request under.
func RequestKey(r *http.Request) string {
	key := r.URL.Path
	if q := r.URL.Query().Get("query"); q != "" {
		key += "?" + q + "&" + r.URL.Query().Get("first_air_date_year") + r.URL.Query().Get("year")
	}
	return key
}

func (s *Server) serve(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	s.requests[RequestKey(r)]++
	s.total++
	key, h := s.key, s.routes[r.URL.Path]
	if h == nil {
		h = s.fallback
	}
	s.mu.Unlock()
	if r.URL.Query().Get("api_key") != key && r.Header.Get("Authorization") != "Bearer "+key {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"status_code":7,"status_message":"Invalid API key: You must be granted a valid key."}`))
		return
	}
	var v any
	if h != nil {
		v = h(r)
	}
	switch v := v.(type) {
	case nil:
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"status_code":34,"status_message":"The resource you requested could not be found."}`))
	case Reply:
		maps.Copy(w.Header(), v.Header)
		if v.Status == 0 {
			v.Status = http.StatusOK
		}
		w.WriteHeader(v.Status)
		_, _ = w.Write([]byte(v.Body))
	default:
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(v)
	}
}

// Pages answers requests for path with TMDB's paged reply: pages[n-1] as
// the results for ?page=n (1 when absent), and total_pages len(pages). A
// page past the last answers with no results, as TMDB does.
func (s *Server) Pages(path string, pages ...[]any) {
	s.Handle(path, func(r *http.Request) any {
		n, err := strconv.Atoi(r.URL.Query().Get("page"))
		if err != nil || n < 1 {
			n = 1
		}
		results := []any{}
		if n <= len(pages) {
			results = pages[n-1]
		}
		return map[string]any{"page": n, "total_pages": len(pages), "total_results": 20 * len(pages), "results": results}
	})
}

// Movie is a movie as TMDB's lists give it.
func Movie(id int, title, releaseDate string) map[string]any {
	return map[string]any{"id": id, "media_type": "movie", "title": title, "release_date": releaseDate, "poster_path": fmt.Sprintf("/poster%d.jpg", id)}
}

// Show is a show as TMDB's lists give it.
func Show(id int, name, firstAirDate string) map[string]any {
	return map[string]any{"id": id, "media_type": "tv", "name": name, "first_air_date": firstAirDate, "poster_path": fmt.Sprintf("/poster%d.jpg", id)}
}
