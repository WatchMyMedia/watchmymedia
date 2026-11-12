package images

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"

	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/artwork"
	"watchmymedia/server/internal/httpx"
)

func TestServeCachesAndValidates(t *testing.T) {
	var fetches atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fetches.Add(1)
		if r.URL.Path == "/w342/abc.jpg" {
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write([]byte("JPEGDATA"))
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer upstream.Close()

	c := New(filepath.Join(t.TempDir(), "img"), upstream.URL)
	r := chi.NewRouter()
	r.Get("/api/images/{size}/{file}", c.Handler())
	srv := httptest.NewServer(r)
	defer srv.Close()

	get := func(p string) (int, string) {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+p, nil)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		buf := make([]byte, 64)
		n, _ := res.Body.Read(buf)
		return res.StatusCode, string(buf[:n])
	}
	if code, body := get("/api/images/w342/abc.jpg"); code != 200 || body != "JPEGDATA" {
		t.Fatalf("first fetch = %d %q", code, body)
	}
	if code, _ := get("/api/images/w342/abc.jpg"); code != 200 {
		t.Fatal("second fetch failed")
	}
	if fetches.Load() != 1 {
		t.Errorf("upstream fetched %d times, want 1 (cached)", fetches.Load())
	}
	if code, _ := get("/api/images/w342/missing.jpg"); code != 404 {
		t.Errorf("missing upstream -> %d", code)
	}
	if code, _ := get("/api/images/w999/abc.jpg"); code != 404 {
		t.Errorf("bad size -> %d", code)
	}
	if code, _ := get("/api/images/w342/..%2F..%2Fetc.jpg"); code != 404 {
		t.Errorf("traversal -> %d", code)
	}
}

// slowUpstream answers each request for an image once released, counting
// the requests.
type slowUpstream struct {
	*httptest.Server
	fetches atomic.Int32
	release chan struct{}
}

func newSlowUpstream(t *testing.T, status int, body string) *slowUpstream {
	u := &slowUpstream{release: make(chan struct{})}
	u.Server = httptest.NewTestServer(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u.fetches.Add(1)
		select {
		case <-u.release:
		case <-r.Context().Done():
			return
		}
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	return u
}

func (u *slowUpstream) cache(t *testing.T) *Cache {
	hc := u.Client() // which sets URL
	c := New(t.TempDir(), u.URL)
	c.http = hc
	return c
}

func TestRequestsShareOneDownload(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		up := newSlowUpstream(t, http.StatusOK, "JPEGDATA")
		c := up.cache(t)

		// The first client leaves while the image downloads; the second
		// still gets it, from the same download.
		first, leave := context.WithCancel(t.Context())
		var firstErr, secondErr error
		var path string
		var wg sync.WaitGroup
		wg.Go(func() { _, firstErr = c.ensure(first, "w342", "abc.jpg") })
		synctest.Wait()
		wg.Go(func() { path, secondErr = c.ensure(t.Context(), "w342", "abc.jpg") })
		synctest.Wait()
		leave()
		synctest.Wait()
		close(up.release)
		wg.Wait()
		if !errors.Is(firstErr, context.Canceled) {
			t.Errorf("first client = %v", firstErr)
		}
		if secondErr != nil {
			t.Fatalf("second client = %v", secondErr)
		}
		if b, _ := os.ReadFile(path); string(b) != "JPEGDATA" {
			t.Errorf("cached %q", b)
		}
		if n := up.fetches.Load(); n != 1 {
			t.Errorf("upstream fetched %d times, want 1", n)
		}
	})
}

func TestUpstreamFailuresAreNotNotFound(t *testing.T) {
	up := newSlowUpstream(t, http.StatusServiceUnavailable, "busy")
	close(up.release)
	c := up.cache(t)
	r := chi.NewRouter()
	r.Get("/api/images/{size}/{file}", c.Handler())
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/images/w342/abc.jpg", nil))
	if w.Code != http.StatusBadGateway {
		t.Errorf("upstream 503 -> %d, want 502", w.Code)
	}
}

// An image over the limit fails rather than being cut, and nothing is
// cached: cached files are served as immutable.
func TestOversizedImageIsNotCached(t *testing.T) {
	up := newSlowUpstream(t, http.StatusOK, "0123456789ABCDEF")
	close(up.release)
	c := up.cache(t)
	dir := c.dir
	c.maxBytes = 10
	if _, err := c.ensure(context.Background(), "w342", "big.jpg"); !errors.Is(err, httpx.ErrTooLarge) {
		t.Errorf("err = %v", err)
	}
	left, _ := filepath.Glob(filepath.Join(dir, "w342", "*"))
	hidden, _ := filepath.Glob(filepath.Join(dir, "w342", ".*"))
	if len(left)+len(hidden) != 0 {
		t.Errorf("left behind %v %v", left, hidden)
	}
}

// A picture an admin chose is scaled from the server's own copy, once for
// a burst of requests, and never asked of TMDB (PLAN-POSTERS.md).
func TestCustomPictures(t *testing.T) {
	var fetches atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fetches.Add(1)
		w.WriteHeader(http.StatusNotFound)
	}))
	defer upstream.Close()

	dir := t.TempDir()
	store := artwork.New(filepath.Join(dir, "artwork"))
	img := image.NewRGBA(image.Rect(0, 0, 800, 1200))
	var raw bytes.Buffer
	if err := jpeg.Encode(&raw, img, nil); err != nil {
		t.Fatal(err)
	}
	name, err := store.Save(&raw)
	if err != nil {
		t.Fatal(err)
	}
	c := New(filepath.Join(dir, "images"), upstream.URL).WithCustom(store)
	r := chi.NewRouter()
	r.Get("/api/images/{size}/{file}", c.Handler())
	srv := httptest.NewServer(r)
	defer srv.Close()

	get := func(p string) (int, int) {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, srv.URL+p, nil)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Error(err)
			return 0, 0
		}
		defer func() { _ = res.Body.Close() }()
		if res.StatusCode != http.StatusOK {
			return res.StatusCode, 0
		}
		cfg, err := jpeg.DecodeConfig(res.Body)
		if err != nil {
			t.Error(err)
		}
		return res.StatusCode, cfg.Width
	}
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			if code, width := get("/api/images/w342/" + name); code != http.StatusOK || width != 342 {
				t.Errorf("w342: %d, %d wide", code, width)
			}
		})
	}
	wg.Wait()
	if code, width := get("/api/images/original/" + name); code != http.StatusOK || width != 800 {
		t.Errorf("original: %d, %d wide", code, width)
	}
	if code, _ := get("/api/images/w342/c-000000000000000000000000.jpg"); code != http.StatusNotFound {
		t.Errorf("a picture that is gone: %d", code)
	}
	if n := fetches.Load(); n != 0 {
		t.Errorf("TMDB was asked %d times for the server's own pictures", n)
	}

	// Without a store, such a name is not found rather than fetched.
	plain := chi.NewRouter()
	plain.Get("/api/images/{size}/{file}", New(filepath.Join(dir, "plain"), upstream.URL).Handler())
	rec := httptest.NewRecorder()
	plain.ServeHTTP(rec, httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/images/w342/"+name, nil))
	if rec.Code != http.StatusNotFound || fetches.Load() != 0 {
		t.Errorf("without a store: %d, %d fetches", rec.Code, fetches.Load())
	}
}
