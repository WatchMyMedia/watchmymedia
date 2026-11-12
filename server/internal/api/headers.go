package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5/middleware"

	"watchmymedia/server/internal/turnstile"
)

// contentSecurityPolicy covers the web UI. Scripts, styles and media come
// from this server only. 'wasm-unsafe-eval' and blob: workers are for the
// ASS subtitle renderer; blob: media is for hls.js (Media Source
// Extensions); inline styles are set by React and the player. While
// Turnstile is on, its script and frame may also come from Cloudflare; while
// casting is on, the Google Cast sender SDK's scripts come from gstatic;
// while YouTube trailers are on, YouTube's player may be framed (a plain
// iframe: no YouTube script runs in the page).
func contentSecurityPolicy(withTurnstile, withCast, withYouTube bool) string {
	script, frames := "", ""
	if withTurnstile {
		script, frames = " "+turnstile.Origin, " "+turnstile.Origin
	}
	if withYouTube {
		frames += " " + youTubeEmbedOrigin
	}
	frame := ""
	if frames != "" {
		frame = "frame-src" + frames + "; "
	}
	if withCast {
		script += " " + castSenderOrigin
	}
	return "default-src 'self'; " +
		"script-src 'self' 'wasm-unsafe-eval'" + script + "; " +
		frame +
		"style-src 'self' 'unsafe-inline'; " +
		"img-src 'self' data: blob:; " +
		"media-src 'self' blob:; " +
		"font-src 'self' data: blob:; " +
		"worker-src 'self' blob:; " +
		"connect-src 'self'; " +
		"object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
}

// castSenderOrigin serves the Google Cast Web Sender SDK. It loads its
// later scripts with the page's own scheme, so on http://localhost (a secure
// context, where casting works) they come over plain HTTP: no scheme here
// allows both.
const castSenderOrigin = "www.gstatic.com"

// youTubeEmbedOrigin serves YouTube's player in its privacy-enhanced mode,
// which the web app's trailer page frames (PLAN-TRAILERS.md §6.1).
const youTubeEmbedOrigin = "https://www.youtube-nocookie.com"

// securityHeaders sets browser protections on every response. The policy
// is left off /api, whose responses are data (and whose docs page loads
// its viewer from a CDN). youtube says whether YouTube trailers are on now.
func securityHeaders(hsts bool, ts *turnstile.Verifier, cast bool, youtube func() bool) func(http.Handler) http.Handler {
	// The policies by [Turnstile][YouTube], made once.
	var csp [2][2]string
	for i, withTurnstile := range []bool{false, true} {
		for j, withYouTube := range []bool{false, true} {
			csp[i][j] = contentSecurityPolicy(withTurnstile, cast, withYouTube)
		}
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			h := w.Header()
			h.Set("X-Content-Type-Options", "nosniff")
			h.Set("X-Frame-Options", "DENY")
			h.Set("Referrer-Policy", "same-origin")
			h.Set("Cross-Origin-Opener-Policy", "same-origin")
			h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")
			if hsts {
				h.Set("Strict-Transport-Security", "max-age=31536000")
			}
			if !strings.HasPrefix(r.URL.Path, Prefix+"/") {
				h.Set("Content-Security-Policy", csp[b2i(ts.Required())][b2i(youtube != nil && youtube())])
			}
			if unbuffered(r.URL.Path) {
				// nginx (SWAG and others) buffers responses by default, which
				// holds back live events and makes players wait on video.
				h.Set("X-Accel-Buffering", "no")
			}
			next.ServeHTTP(w, r)
		})
	}
}

// unbuffered lists responses a reverse proxy should pass through as they
// are written: the event stream, file streams and HLS sessions.
func unbuffered(p string) bool {
	return p == Prefix+"/events" || strings.HasSuffix(p, "/stream") || strings.HasPrefix(p, Prefix+"/playback/")
}

// compressedTypes are the responses worth gzipping: API JSON and the web
// UI's pages, scripts, styles and subtitle renderer. Video and artwork are
// compressed already.
var compressedTypes = []string{
	"application/json", "application/problem+json", "application/openapi+json",
	"text/html", "text/css", "text/javascript", "application/javascript",
	"image/svg+xml", "application/manifest+json", "application/wasm",
}

// compress gzips text responses for clients that accept it. Streams,
// artwork and range requests (whose byte ranges refer to the uncompressed
// file) pass through untouched, and so do responses that already have a
// Content-Encoding, such as the web app's precompressed files. Reads of
// library data get an ETag first (withETag), from the body before gzip.
func compress(next http.Handler) http.Handler {
	zipped := middleware.Compress(5, compressedTypes...)(withETag(next))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if unbuffered(r.URL.Path) || strings.HasPrefix(r.URL.Path, Prefix+"/images/") || r.Header.Get("Range") != "" {
			next.ServeHTTP(w, r)
			return
		}
		zipped.ServeHTTP(w, r)
	})
}

// etagged lists the reads of library data that clients fetch again and
// again: home, the libraries and what is in them, a movie, show or
// season, Continue watching and search. Every route under these is JSON.
func etagged(p string) bool {
	switch p {
	case Prefix + "/home", Prefix + "/libraries", Prefix + "/watch/continue", Prefix + "/search":
		return true
	}
	return strings.HasPrefix(p, Prefix+"/libraries/") || strings.HasPrefix(p, Prefix+"/movies/") || strings.HasPrefix(p, Prefix+"/shows/")
}

// withETag gives a successful JSON read a weak ETag, a hash of its body,
// and answers 304 Not Modified, without the body, when the request's
// If-None-Match already names it. The body depends on who asks (their
// watch marks), which the hash covers, so the reply is marked private and
// to be checked every time rather than reused.
func withETag(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || !etagged(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}
		buf := &bufferedResponse{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(buf, r)
		h := w.Header()
		ctype, _, _ := strings.Cut(h.Get("Content-Type"), ";")
		if buf.status == http.StatusOK && (ctype == "application/json" || strings.HasSuffix(ctype, "+json")) {
			sum := sha256.Sum256(buf.body.Bytes())
			tag := `W/"` + hex.EncodeToString(sum[:16]) + `"`
			h.Set("ETag", tag)
			h.Set("Cache-Control", "private, no-cache")
			if etagMatches(r.Header.Get("If-None-Match"), tag) {
				// No Content-Type, so nothing after this compresses the empty reply.
				h.Del("Content-Type")
				h.Del("Content-Length")
				w.WriteHeader(http.StatusNotModified)
				return
			}
		}
		w.WriteHeader(buf.status)
		_, _ = w.Write(buf.body.Bytes())
	})
}

// etagMatches compares If-None-Match with tag the weak way RFC 9110 asks of
// it: W/ is ignored, and * matches anything.
func etagMatches(header, tag string) bool {
	tag = strings.TrimPrefix(tag, "W/")
	for c := range strings.SplitSeq(header, ",") {
		c = strings.TrimSpace(c)
		if c == "*" || strings.TrimPrefix(c, "W/") == tag {
			return true
		}
	}
	return false
}

// bufferedResponse holds a handler's status and body, keeping its headers
// on the real response.
type bufferedResponse struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
	body        bytes.Buffer
}

func (b *bufferedResponse) WriteHeader(code int) {
	if !b.wroteHeader {
		b.status, b.wroteHeader = code, true
	}
}

func (b *bufferedResponse) Write(p []byte) (int, error) {
	b.wroteHeader = true
	return b.body.Write(p)
}

// b2i is 1 for true, for indexing by a flag.
func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}
