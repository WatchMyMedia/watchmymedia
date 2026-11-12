package webui

import (
	"bytes"
	"compress/gzip"
	"io"
	"mime"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
)

var script = []byte(strings.Repeat("console.log('watch my media');\n", 200))

func testFiles() fstest.MapFS {
	return fstest.MapFS{
		"index.html":           {Data: []byte("<!doctype html><title>WatchMyMedia</title>" + strings.Repeat(" ", 2000))},
		"assets/app-1a2b.js":   {Data: script},
		"assets/logo-3c4d.png": {Data: bytes.Repeat([]byte{0x89, 'P', 'N', 'G'}, 1000)},
		"favicon.svg":          {Data: []byte("<svg/>")},
	}
}

// response is what a request got, without a body to close.
type response struct {
	StatusCode int
	Header     http.Header
}

func get(t *testing.T, h http.Handler, target string, header ...string) (response, []byte) {
	t.Helper()
	req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, target, nil)
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return response{StatusCode: rec.Code, Header: rec.Header()}, rec.Body.Bytes()
}

func gunzip(t *testing.T, b []byte) []byte {
	t.Helper()
	zr, err := gzip.NewReader(bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	out, err := io.ReadAll(zr)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestAssetGzipped(t *testing.T) {
	h := handler(testFiles())
	res, body := get(t, h, "/assets/app-1a2b.js", "Accept-Encoding", "br, gzip;q=0.8")
	if res.StatusCode != 200 || res.Header.Get("Content-Encoding") != "gzip" || !strings.Contains(res.Header.Get("Vary"), "Accept-Encoding") ||
		!strings.HasPrefix(res.Header.Get("Content-Type"), "text/javascript") || res.Header.Get("Cache-Control") != "public, max-age=31536000, immutable" {
		t.Fatalf("gzip -> %d %v", res.StatusCode, res.Header)
	}
	if !bytes.Equal(gunzip(t, body), script) || len(body) >= len(script) {
		t.Errorf("gzipped body: %d bytes for %d", len(body), len(script))
	}
	gzTag := res.Header.Get("ETag")

	res, body = get(t, h, "/assets/app-1a2b.js", "Accept-Encoding", "gzip;q=0")
	rawTag := res.Header.Get("ETag")
	if res.Header.Get("Content-Encoding") != "" || !bytes.Equal(body, script) || rawTag == "" || strings.HasPrefix(rawTag, "W/") || rawTag == gzTag {
		t.Errorf("raw -> encoding %q, %d bytes, ETag %q (gzip's %q)", res.Header.Get("Content-Encoding"), len(body), rawTag, gzTag)
	}
	// The same bytes again are the same work done once: same tags.
	if res, _ := get(t, h, "/assets/app-1a2b.js", "Accept-Encoding", "gzip"); res.Header.Get("ETag") != gzTag {
		t.Errorf("second gzip ETag %q, want %q", res.Header.Get("ETag"), gzTag)
	}
}

func TestAssetRangeIsRaw(t *testing.T) {
	h := handler(testFiles())
	res, body := get(t, h, "/assets/app-1a2b.js", "Accept-Encoding", "gzip", "Range", "bytes=0-9")
	if res.StatusCode != http.StatusPartialContent || res.Header.Get("Content-Encoding") != "" || !bytes.Equal(body, script[:10]) {
		t.Errorf("range -> %d, encoding %q, %q", res.StatusCode, res.Header.Get("Content-Encoding"), body)
	}
}

func TestAssetNotModified(t *testing.T) {
	h := handler(testFiles())
	res, _ := get(t, h, "/assets/app-1a2b.js", "Accept-Encoding", "gzip")
	if res, body := get(t, h, "/assets/app-1a2b.js", "Accept-Encoding", "gzip", "If-None-Match", res.Header.Get("ETag")); res.StatusCode != http.StatusNotModified || len(body) != 0 {
		t.Errorf("If-None-Match -> %d, %d bytes", res.StatusCode, len(body))
	}
	// index.html is checked every time and answers 304 while unchanged,
	// for the app's routes too.
	res, body := get(t, h, "/shows/12", "Accept-Encoding", "gzip")
	if res.StatusCode != 200 || res.Header.Get("Cache-Control") != "no-cache" || !strings.HasPrefix(res.Header.Get("Content-Type"), "text/html") ||
		!bytes.Contains(gunzip(t, body), []byte("<title>WatchMyMedia")) {
		t.Fatalf("index -> %d %v", res.StatusCode, res.Header)
	}
	if res, _ := get(t, h, "/", "Accept-Encoding", "gzip", "If-None-Match", res.Header.Get("ETag")); res.StatusCode != http.StatusNotModified {
		t.Errorf("index If-None-Match -> %d", res.StatusCode)
	}
}

func TestAssetsNotWorthGzip(t *testing.T) {
	h := handler(testFiles())
	for _, p := range []string{"/assets/logo-3c4d.png", "/favicon.svg"} {
		res, _ := get(t, h, p, "Accept-Encoding", "gzip")
		if res.StatusCode != 200 || res.Header.Get("Content-Encoding") != "" || res.Header.Get("ETag") == "" {
			t.Errorf("%s -> %d, encoding %q, ETag %q", p, res.StatusCode, res.Header.Get("Content-Encoding"), res.Header.Get("ETag"))
		}
	}
}

func TestAPIAndUnbuilt(t *testing.T) {
	if res, _ := get(t, handler(testFiles()), "/api/nope"); res.StatusCode != http.StatusNotFound {
		t.Errorf("/api/nope -> %d", res.StatusCode)
	}
	if res, _ := get(t, handler(fstest.MapFS{".gitkeep": {}}), "/"); res.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("unbuilt -> %d", res.StatusCode)
	}
}

func TestAcceptsGzip(t *testing.T) {
	for h, want := range map[string]bool{"gzip": true, "GZIP": true, "br, gzip": true, "gzip;q=0": false, "gzip; q=0.5": true, "*": true, "*;q=0": false, "br": false, "": false} {
		if got := acceptsGzip(h); got != want {
			t.Errorf("acceptsGzip(%q) = %v", h, got)
		}
	}
}

// The web app's files go out with the same types on every system, whatever
// /etc/mime.types or the Windows registry say.
func TestAssetTypes(t *testing.T) {
	for ext, want := range map[string]string{
		".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".html": "text/html; charset=utf-8",
		".wasm": "application/wasm", ".svg": "image/svg+xml", ".webmanifest": "application/manifest+json",
	} {
		if got := mime.TypeByExtension(ext); got != want {
			t.Errorf("%s: %q, want %q", ext, got, want)
		}
	}
}
