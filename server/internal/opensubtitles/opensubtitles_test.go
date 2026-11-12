package opensubtitles

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"golang.org/x/time/rate"
)

func TestHash(t *testing.T) {
	dir := t.TempDir()
	// First 64 KiB all 0x01, last 64 KiB all 0x02, 1000 bytes of zeros between.
	size := int64(2*hashChunk + 1000)
	data := make([]byte, size)
	for i := range hashChunk {
		data[i] = 1
		data[size-1-int64(i)] = 2
	}
	path := filepath.Join(dir, "a.mkv")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	want := uint64(size)
	want += 8192 * binary.LittleEndian.Uint64([]byte{1, 1, 1, 1, 1, 1, 1, 1})
	want += 8192 * binary.LittleEndian.Uint64([]byte{2, 2, 2, 2, 2, 2, 2, 2})
	got, err := Hash(path)
	if err != nil || got != fmt.Sprintf("%016x", want) {
		t.Errorf("hash = %q, %v; want %016x", got, err, want)
	}

	// All zeros: the hash is the size, with leading zeros kept.
	zero := filepath.Join(dir, "z.mkv")
	if err := os.WriteFile(zero, make([]byte, 200_000), 0o600); err != nil {
		t.Fatal(err)
	}
	if got, _ := Hash(zero); got != "0000000000030d40" {
		t.Errorf("zero hash = %q", got)
	}

	// Files the API can't hash get none.
	small := filepath.Join(dir, "s.mkv")
	if err := os.WriteFile(small, make([]byte, 1000), 0o600); err != nil {
		t.Fatal(err)
	}
	if got, err := Hash(small); got != "" || err != nil {
		t.Errorf("small hash = %q, %v", got, err)
	}
}

// fakeAPI is enough of the OpenSubtitles API for the client.
type fakeAPI struct {
	logins    atomic.Int32
	searches  atomic.Int32
	remaining int
	expireOne atomic.Bool // answer the next authed request with 401
	lastQuery string
	server    *httptest.Server
}

func newFakeAPI(t *testing.T) *fakeAPI {
	f := &fakeAPI{remaining: 3}
	mux := http.NewServeMux()
	auth := func(w http.ResponseWriter, r *http.Request) bool {
		if r.Header.Get("Api-Key") != "key" || !strings.HasPrefix(r.Header.Get("User-Agent"), "WatchMyMedia v") {
			w.WriteHeader(http.StatusForbidden)
			return false
		}
		if r.Header.Get("Authorization") == "" {
			return true
		}
		if f.expireOne.CompareAndSwap(true, false) || r.Header.Get("Authorization") != fmt.Sprintf("Bearer tok%d", f.logins.Load()) {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"message":"invalid token"}`))
			return false
		}
		return true
	}
	mux.HandleFunc("POST /api/v1/login", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		var in map[string]string
		_ = json.NewDecoder(r.Body).Decode(&in)
		if in["password"] != "right" {
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"message":"Invalid username/password","status":401}`))
			return
		}
		n := f.logins.Add(1)
		_, _ = fmt.Fprintf(w, `{"user":{"allowed_downloads":20,"level":"Sub leecher","vip":false},"base_url":"api.opensubtitles.com","token":"tok%d","status":200}`, n)
	})
	mux.HandleFunc("GET /api/v1/subtitles", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		f.searches.Add(1)
		f.lastQuery = r.URL.RawQuery
		_, _ = w.Write([]byte(`{"data":[
			{"attributes":{"language":"en","download_count":900,"release":"Popular","files":[{"file_id":1}]}},
			{"attributes":{"language":"en","download_count":5,"release":"Exact","moviehash_match":true,"files":[{"file_id":2}]}},
			{"attributes":{"language":"en","download_count":100,"release":"Machine","machine_translated":true,"files":[{"file_id":3}]}},
			{"attributes":{"language":"en","download_count":100,"release":"Two CDs","files":[{"file_id":4},{"file_id":5}]}},
			{"attributes":{"language":"es","download_count":50,"release":"Trusted","from_trusted":true,"files":[{"file_id":6}]}},
			{"attributes":{"language":"es","download_count":800,"release":"AI","ai_translated":true,"files":[{"file_id":7}]}}
		]}`))
	})
	mux.HandleFunc("POST /api/v1/download", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) {
			return
		}
		if f.remaining <= 0 {
			w.WriteHeader(http.StatusNotAcceptable)
			_, _ = w.Write([]byte(`{"requests":21,"remaining":0,"message":"You have downloaded your allowed 20 subtitles for 24h","reset_time_utc":"2026-10-31T00:00:00.000Z"}`))
			return
		}
		f.remaining--
		_, _ = fmt.Fprintf(w, `{"link":"%s/file.srt","file_name":"x.srt","remaining":%d,"reset_time_utc":"2026-10-31T00:00:00.000Z"}`, f.server.URL, f.remaining)
	})
	mux.HandleFunc("GET /file.srt", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("1\n00:00:01,000 --> 00:00:02,000\nHello\n"))
	})
	mux.HandleFunc("GET /api/v1/infos/user", func(w http.ResponseWriter, r *http.Request) {
		if !auth(w, r) || r.Header.Get("Authorization") == "" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_, _ = w.Write([]byte(`{"data":{"allowed_downloads":20,"remaining_downloads":17,"level":"Sub leecher","vip":false}}`))
	})
	f.server = httptest.NewServer(mux)
	t.Cleanup(f.server.Close)
	return f
}

func TestSearchRanksAndFilters(t *testing.T) {
	f := newFakeAPI(t)
	c := newClient(f)
	got, err := c.Search(context.Background(), Query{TMDBID: 603, MovieHash: "0123456789abcdef", Languages: []string{"es", "en"}})
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, r := range got {
		names = append(names, r.Release)
	}
	// Hash match first, then trusted, then human before AI, then downloads;
	// machine translations and multi-file results are dropped.
	if strings.Join(names, ",") != "Exact,Trusted,Popular,AI" {
		t.Errorf("order = %v", names)
	}
	if f.lastQuery != "languages=en,es&moviehash=0123456789abcdef&tmdb_id=603&type=movie" {
		t.Errorf("query = %s", f.lastQuery)
	}
	if !got[0].HashMatch || got[0].FileID != 2 {
		t.Errorf("first = %+v", got[0])
	}

	// An episode searches by its show.
	if _, err := c.Search(context.Background(), Query{ParentTMDBID: 1399, Season: 2, Episode: 3, Languages: []string{"en"}}); err != nil {
		t.Fatal(err)
	}
	if f.lastQuery != "episode_number=3&languages=en&parent_tmdb_id=1399&season_number=2&type=episode" {
		t.Errorf("episode query = %s", f.lastQuery)
	}
}

func TestDownloadSignsInAndCountsQuota(t *testing.T) {
	f := newFakeAPI(t)
	c := newClient(f)
	c.SetCredentials("me", "right")
	got, err := c.Download(context.Background(), 2)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(got.Data), "Hello") || got.Remaining != 2 || f.logins.Load() != 1 {
		t.Errorf("download = %+v, logins %d", got, f.logins.Load())
	}
	if a := c.Cached(); a == nil || a.RemainingDownload != 2 {
		t.Errorf("cached account = %+v", a)
	}

	// A token the API calls invalid is renewed once.
	f.expireOne.Store(true)
	if _, err := c.Download(context.Background(), 2); err != nil {
		t.Fatal(err)
	}
	if f.logins.Load() != 2 {
		t.Errorf("logins = %d, want a second sign-in", f.logins.Load())
	}

	// Out of downloads.
	_, _ = c.Download(context.Background(), 2)
	_, err = c.Download(context.Background(), 2)
	var q *QuotaError
	if !errors.As(err, &q) || !errors.Is(err, ErrQuota) || q.ResetAt.IsZero() {
		t.Errorf("err = %v", err)
	}
}

func TestBadPasswordStopsTrying(t *testing.T) {
	f := newFakeAPI(t)
	c := newClient(f)
	if err := c.Verify(context.Background(), "me", "wrong"); !errors.Is(err, ErrSignIn) {
		t.Errorf("verify = %v", err)
	}
	c.SetCredentials("me", "wrong")
	for range 3 {
		if _, err := c.Download(context.Background(), 2); !errors.Is(err, ErrSignIn) {
			t.Errorf("download = %v", err)
		}
	}
	// Searches still work, signed out.
	if _, err := c.Search(context.Background(), Query{TMDBID: 1, Languages: []string{"en"}}); err != nil {
		t.Errorf("search = %v", err)
	}
	if n := f.logins.Load(); n != 0 {
		t.Errorf("successful logins = %d", n)
	}
	c.SetCredentials("me", "right")
	if a, err := c.Status(context.Background()); err != nil || a.RemainingDownload != 17 {
		t.Errorf("status = %+v, %v", a, err)
	}
}

func TestNoKey(t *testing.T) {
	c := New("", "WatchMyMedia vtest", "")
	if c.Configured() {
		t.Error("configured without a key")
	}
	if _, err := c.Search(context.Background(), Query{}); !errors.Is(err, ErrNoAPIKey) {
		t.Errorf("search = %v", err)
	}
}

// newClient is a client of the fake without the API's rate limit.
func newClient(f *fakeAPI) *Client {
	return NewWith("key", "WatchMyMedia vtest", f.server.URL+"/api/v1", Options{Limiter: rate.NewLimiter(rate.Inf, 0)})
}
