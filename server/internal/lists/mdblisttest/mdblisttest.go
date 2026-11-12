// Package mdblisttest is a fake MDBList API for tests: lists a test adds,
// answered as MDBList answers them, with the key checked and every request
// counted. Point lists.NewMDBList at its URL with Key. Titles and IDs in
// tests are fictional.
package mdblisttest

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
)

// Key is the API key the fake accepts unless SetKey changes it.
const Key = "mdb-test-key"

// Item is a title on a fake list. TMDB 0 leaves the ids.tmdb out, so the
// title must be found by IMDb or TheTVDB ID.
type Item struct {
	Title     string
	Year      int
	MediaType string // movie or show
	TMDB      int
	IMDb      string
	TVDB      int
}

// List is a fake list.
type List struct {
	ID    int
	User  string
	Slug  string
	Name  string
	Items []Item
}

// Server is a fake MDBList.
type Server struct {
	*httptest.Server
	mu       sync.Mutex
	key      string
	lists    []List
	status   int // answer every request with this status when set
	requests map[string]int
	total    int
}

// New starts a fake with no lists.
func New(t testing.TB) *Server {
	t.Helper()
	s := &Server{key: Key, requests: map[string]int{}}
	s.Server = httptest.NewServer(http.HandlerFunc(s.serve))
	t.Cleanup(s.Close)
	return s
}

// SetKey makes the fake accept another key.
func (s *Server) SetKey(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.key = key
}

// Add adds or replaces a list.
func (s *Server) Add(l List) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.lists {
		if s.lists[i].ID == l.ID {
			s.lists[i] = l
			return
		}
	}
	s.lists = append(s.lists, l)
}

// Fail answers every request with status (429 with a Retry-After of an
// hour) until Fail(0).
func (s *Server) Fail(status int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.status = status
}

// Count is how many requests the fake got for path.
func (s *Server) Count(path string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.requests[path]
}

// Total is how many requests the fake got.
func (s *Server) Total() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.total
}

func (s *Server) serve(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	s.requests[r.URL.Path]++
	s.total++
	key, status, all := s.key, s.status, append([]List(nil), s.lists...)
	s.mu.Unlock()
	reply := func(code int, v any) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(code)
		_ = json.NewEncoder(w).Encode(v)
	}
	if status == http.StatusTooManyRequests {
		w.Header().Set("Retry-After", "3600")
		reply(status, map[string]string{"error": "Daily API limit exceeded!"})
		return
	}
	if status != 0 {
		reply(status, map[string]string{"error": http.StatusText(status)})
		return
	}
	if r.URL.Query().Get("apikey") != key {
		reply(http.StatusUnauthorized, map[string]string{"error": "Invalid API key"})
		return
	}
	path := strings.Trim(r.URL.Path, "/")
	if path == "user" {
		reply(http.StatusOK, map[string]any{"user_id": 3, "username": "tester", "api_requests": 1000, "api_requests_count": s.Total()})
		return
	}
	parts := strings.Split(path, "/")
	if len(parts) < 2 || parts[0] != "lists" {
		reply(http.StatusNotFound, map[string]string{"error": "Not found"})
		return
	}
	parts = parts[1:]
	items := false
	if parts[len(parts)-1] == "items" {
		items, parts = true, parts[:len(parts)-1]
	}
	var found *List
	for i, l := range all {
		if (len(parts) == 1 && strconv.Itoa(l.ID) == parts[0]) || (len(parts) == 2 && l.User == parts[0] && l.Slug == parts[1]) {
			found = &all[i]
		}
	}
	if found == nil {
		reply(http.StatusNotFound, map[string]string{"error": "List not found"})
		return
	}
	if !items {
		reply(http.StatusOK, []any{map[string]any{"id": found.ID, "user_name": found.User, "slug": found.Slug, "name": found.Name, "mediatype": "movie", "items": len(found.Items)}})
		return
	}
	limit, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || limit <= 0 {
		limit = 1000
	}
	movies, shows := []any{}, []any{}
	for i, it := range found.Items {
		if i == limit {
			break
		}
		v := map[string]any{"id": it.TMDB, "rank": i + 1, "title": it.Title, "release_year": it.Year, "mediatype": it.MediaType,
			"imdb_id": it.IMDb, "tvdb_id": nil, "adult": 0}
		ids := map[string]any{"mdblist": fmt.Sprintf("m%d", i), "imdb": it.IMDb, "tvdb": nil}
		if it.TVDB > 0 {
			v["tvdb_id"], ids["tvdb"] = it.TVDB, it.TVDB
		}
		if it.TMDB > 0 {
			ids["tmdb"] = it.TMDB
			v["poster"] = fmt.Sprintf("https://image.tmdb.org/t/p/w342/p%d.jpg", it.TMDB)
		}
		v["ids"] = ids
		if it.MediaType == "show" {
			shows = append(shows, v)
		} else {
			movies = append(movies, v)
		}
	}
	reply(http.StatusOK, map[string]any{"movies": movies, "shows": shows})
}
