package api

import (
	"bytes"
	"compress/gzip"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
)

func TestETagOnLibraryReads(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	// No transparent gzip: the test reads Content-Encoding itself.
	plain := &http.Client{Jar: srv.client.Jar, Transport: &http.Transport{DisableCompression: true}}
	get := func(path string, header ...string) (reply, string) {
		t.Helper()
		res := doJSON(t, plain, http.MethodGet, srv.URL+path, "", nil, header...)
		if res.Header.Get("Content-Encoding") != "gzip" {
			return res, string(res.Body)
		}
		zr, err := gzip.NewReader(bytes.NewReader(res.Body))
		if err != nil {
			t.Fatal(err)
		}
		b, _ := io.ReadAll(zr)
		return res, string(b)
	}

	res, body := get("/api/home")
	tag := res.Header.Get("ETag")
	if res.StatusCode != http.StatusOK || !strings.HasPrefix(tag, `W/"`) || res.Header.Get("Cache-Control") != "private, no-cache" || body == "" {
		t.Fatalf("home -> %d, ETag %q, Cache-Control %q", res.StatusCode, tag, res.Header.Get("Cache-Control"))
	}
	// The tag is of the body, so the gzipped reply carries the same one.
	if res, gzBody := get("/api/home", "Accept-Encoding", "gzip"); res.Header.Get("Content-Encoding") != "gzip" || res.Header.Get("ETag") != tag || gzBody != body {
		t.Errorf("gzipped home: encoding %q, ETag %q, same body %v", res.Header.Get("Content-Encoding"), res.Header.Get("ETag"), gzBody == body)
	}
	for _, inm := range []string{tag, strings.TrimPrefix(tag, "W/"), `"other", ` + tag, "*"} {
		res, b := get("/api/home", "If-None-Match", inm, "Accept-Encoding", "gzip")
		if res.StatusCode != http.StatusNotModified || b != "" || res.Header.Get("Content-Encoding") != "" || res.Header.Get("ETag") != tag {
			t.Errorf("If-None-Match %s -> %d, %d bytes, encoding %q", inm, res.StatusCode, len(b), res.Header.Get("Content-Encoding"))
		}
	}
	if res, _ := get("/api/home", "If-None-Match", `W/"stale"`); res.StatusCode != http.StatusOK {
		t.Errorf("stale tag -> %d, want 200", res.StatusCode)
	}

	// A change to what the page shows changes the tag.
	f, _ := srv.q.GetMediaFileByPath(t.Context(), "/m/Heat (1995).mkv")
	movie, err := srv.q.UpsertMovie(t.Context(), db.UpsertMovieParams{LibraryID: f.LibraryID, TmdbID: 949, Title: "Heat", Genres: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.q.SetMediaFileMatch(t.Context(), db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	if res, _ := get("/api/home", "If-None-Match", tag); res.StatusCode != http.StatusOK || res.Header.Get("ETag") == tag {
		t.Errorf("after a match -> %d with ETag %q", res.StatusCode, res.Header.Get("ETag"))
	}

	for _, path := range []string{"/api/libraries", "/api/libraries/1/items", "/api/libraries/1/rows", "/api/movies/1", "/api/watch/continue", "/api/search?q=heat"} {
		if res, _ := get(path); res.StatusCode != http.StatusOK || res.Header.Get("ETag") == "" {
			t.Errorf("%s -> %d, ETag %q", path, res.StatusCode, res.Header.Get("ETag"))
		}
	}
	// Errors and other routes get none.
	for _, path := range []string{"/api/movies/999", "/api/health", "/api/auth/me", "/api/events-not-a-route"} {
		if res, _ := get(path); res.Header.Get("ETag") != "" {
			t.Errorf("%s -> %d with ETag %q", path, res.StatusCode, res.Header.Get("ETag"))
		}
	}
	for _, path := range []string{"/api/events", "/api/files/1/stream", "/api/playback/x/video.m3u8", "/api/images/w342/a.jpg"} {
		if etagged(path) && !strings.HasPrefix(path, Prefix+"/libraries/") {
			t.Errorf("%s would get an ETag", path)
		}
	}
}

func TestETagMatches(t *testing.T) {
	t.Parallel()
	cases := []struct {
		header string
		want   bool
	}{
		{`W/"a"`, true}, {`"a"`, true}, {`"b", W/"a"`, true}, {"*", true}, {`"b"`, false}, {"", false}, {`W/"ab"`, false},
	}
	for _, c := range cases {
		if got := etagMatches(c.header, `W/"a"`); got != c.want {
			t.Errorf("etagMatches(%q) = %v, want %v", c.header, got, c.want)
		}
	}
}

// A response gzipped already (the web app's files) is not gzipped again.
func TestCompressSkipsEncodedResponses(t *testing.T) {
	t.Parallel()
	var gz bytes.Buffer
	zw := gzip.NewWriter(&gz)
	_, _ = zw.Write([]byte(strings.Repeat("let x = 1;\n", 500)))
	_ = zw.Close()
	h := compress(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		w.Header().Set("Content-Encoding", "gzip")
		_, _ = w.Write(gz.Bytes())
	}))
	req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/assets/app.js", nil)
	req.Header.Set("Accept-Encoding", "gzip")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if !bytes.Equal(rec.Body.Bytes(), gz.Bytes()) || rec.Header().Get("Content-Encoding") != "gzip" {
		t.Errorf("body %d bytes (want the %d sent), encoding %q", rec.Body.Len(), gz.Len(), rec.Header().Get("Content-Encoding"))
	}
}

func TestRequestLoggerLevels(t *testing.T) {
	t.Parallel()
	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelInfo}))
	h := requestLogger(log)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "missing") {
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	for _, p := range []string{"/api/playback/abc/seg-3.ts", "/api/files/7/stream", "/api/images/w342/p.jpg", "/api/home", "/api/playback/missing/video.m3u8"} {
		h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequestWithContext(t.Context(), http.MethodGet, p, nil))
	}
	out := buf.String()
	for _, quiet := range []string{"seg-3.ts", "/files/7/stream", "p.jpg"} {
		if strings.Contains(out, quiet) {
			t.Errorf("%s logged at Info:\n%s", quiet, out)
		}
	}
	for _, loud := range []string{"path=/api/home", "missing/video.m3u8"} {
		if !strings.Contains(out, loud) {
			t.Errorf("%s not logged at Info:\n%s", loud, out)
		}
	}
}
