// Package webui serves the built React application embedded in the binary.
// `make web` copies web/dist into ./dist before `go build`; without that step
// only .gitkeep is embedded and the handler reports the UI as not built.
package webui

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"io/fs"
	"mime"
	"net/http"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed all:dist
var dist embed.FS

func init() {
	// The same types on every system: Go lets /etc/mime.types and the
	// Windows registry override its own table (Windows says
	// application/javascript for .js), and browsers compile WebAssembly as
	// it streams in only when it comes as application/wasm. .webmanifest
	// and .ico are not in Go's table, and slim containers ship no
	// /etc/mime.types.
	for ext, typ := range map[string]string{
		".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8",
		".css": "text/css; charset=utf-8", ".json": "application/json", ".svg": "image/svg+xml", ".wasm": "application/wasm",
		".woff2": "font/woff2", ".webmanifest": "application/manifest+json", ".ico": "image/x-icon",
	} {
		_ = mime.AddExtensionType(ext, typ)
	}
}

// Handler serves static assets with single-page-app fallback: any path that
// is not a file resolves to index.html so client-side routing works.
func Handler() http.Handler {
	sub, err := fs.Sub(dist, "dist")
	if err != nil {
		panic(err)
	}
	return handler(sub)
}

func handler(files fs.FS) http.Handler {
	_, indexErr := fs.Stat(files, "index.html")
	built := indexErr == nil
	cache := &assets{files: files, byName: map[string]*asset{}}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/") {
			http.NotFound(w, r)
			return
		}
		if !built {
			http.Error(w, "web UI not built: run `make web` (or use the Docker image)", http.StatusServiceUnavailable)
			return
		}
		p := strings.TrimPrefix(path.Clean(r.URL.Path), "/")
		if info, err := fs.Stat(files, p); p == "" || err != nil || info.IsDir() {
			// The app shell: always checked with the server, which answers
			// 304 while it is the same.
			p = "index.html"
		}
		switch {
		case p == "index.html":
			w.Header().Set("Cache-Control", "no-cache")
		case strings.HasPrefix(p, "assets/"):
			// Vite emits content-hashed filenames under assets/.
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		cache.serve(w, r, p)
	})
}

// assets holds each file the app has asked for, read, hashed and gzipped
// once: gzipping the subtitle renderer's 2 MB of WebAssembly took 47 ms of
// CPU on every request.
type assets struct {
	files  fs.FS
	mu     sync.Mutex
	byName map[string]*asset
}

type asset struct {
	once  sync.Once
	err   error
	ctype string
	raw   []byte
	tag   string // strong ETag of raw
	gz    []byte // nil when gzip would not help
}

// compressible are the types worth gzipping; images and fonts are
// compressed already.
var compressible = []string{"text/", "application/javascript", "application/json", "application/manifest+json", "application/wasm", "image/svg+xml"}

func (c *assets) get(name string) *asset {
	c.mu.Lock()
	a, ok := c.byName[name]
	if !ok {
		a = &asset{}
		c.byName[name] = a
	}
	c.mu.Unlock()
	a.once.Do(func() { a.err = a.load(c.files, name) })
	return a
}

func (a *asset) load(files fs.FS, name string) error {
	raw, err := fs.ReadFile(files, name)
	if err != nil {
		return err
	}
	a.raw = raw
	sum := sha256.Sum256(raw)
	a.tag = `"` + hex.EncodeToString(sum[:16]) + `"`
	a.ctype = mime.TypeByExtension(path.Ext(name))
	if a.ctype == "" {
		a.ctype = http.DetectContentType(raw)
	}
	if len(raw) < 1024 || !worthGzip(a.ctype) {
		return nil
	}
	var buf bytes.Buffer
	zw, err := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	if err != nil {
		return err
	}
	if _, err := zw.Write(raw); err != nil {
		return err
	}
	if err := zw.Close(); err != nil {
		return err
	}
	if buf.Len() < len(raw) {
		a.gz = buf.Bytes()
	}
	return nil
}

func worthGzip(ctype string) bool {
	for _, c := range compressible {
		if strings.HasPrefix(ctype, c) {
			return true
		}
	}
	return false
}

// serve answers with the file: gzipped when the client takes gzip and asks
// for the whole file, as is otherwise (byte ranges refer to the plain
// file). Each form has its own strong ETag, and http.ServeContent answers
// If-None-Match with 304.
func (c *assets) serve(w http.ResponseWriter, r *http.Request, name string) {
	a := c.get(name)
	if a.err != nil {
		if errors.Is(a.err, fs.ErrNotExist) {
			http.NotFound(w, r)
			return
		}
		http.Error(w, "could not read "+name, http.StatusInternalServerError)
		return
	}
	h := w.Header()
	h.Set("Content-Type", a.ctype)
	content, tag := a.raw, a.tag
	if a.gz != nil {
		h.Add("Vary", "Accept-Encoding")
		if r.Header.Get("Range") == "" && acceptsGzip(r.Header.Get("Accept-Encoding")) {
			content, tag = a.gz, strings.TrimSuffix(a.tag, `"`)+`-gzip"`
			h.Set("Content-Encoding", "gzip")
		}
	}
	h.Set("ETag", tag)
	http.ServeContent(w, r, name, time.Time{}, bytes.NewReader(content))
}

// acceptsGzip reads an Accept-Encoding header: gzip, or *, without q=0.
func acceptsGzip(header string) bool {
	for part := range strings.SplitSeq(header, ",") {
		coding, params, _ := strings.Cut(part, ";")
		coding = strings.ToLower(strings.TrimSpace(coding))
		if coding != "gzip" && coding != "*" {
			continue
		}
		q := 1.0
		if v, ok := strings.CutPrefix(strings.TrimSpace(params), "q="); ok {
			if f, err := strconv.ParseFloat(strings.TrimSpace(v), 64); err == nil {
				q = f
			}
		}
		if q > 0 {
			return true
		}
	}
	return false
}
