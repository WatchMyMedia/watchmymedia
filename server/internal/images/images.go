// Package images caches TMDB artwork on disk and serves it. Posters and
// backdrops are fetched lazily on first request, so a library of thousands
// of items costs nothing until someone looks at it.
package images

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/artwork"
	"watchmymedia/server/internal/httpx"
	"watchmymedia/server/internal/memo"
)

var allowedSizes = map[string]bool{
	"w92": true, "w154": true, "w185": true, "w300": true, "w342": true,
	"w500": true, "w780": true, "w1280": true, "original": true,
}

var fileRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}\.(jpg|jpeg|png|webp)$`)

type Cache struct {
	dir     string
	baseURL string
	http    *http.Client
	// maxBytes bounds an image; TMDB's largest originals are a few MB.
	maxBytes int64

	// flight shares one download of an image among the requests for it.
	flight memo.Group

	// custom holds the pictures admins chose in place of TMDB's
	// (PLAN-POSTERS.md); nil serves none.
	custom *artwork.Store
}

// WithCustom also serves the pictures in store, scaled to the size asked
// for, under the names store gives them. They are never looked up on TMDB.
func (c *Cache) WithCustom(store *artwork.Store) *Cache {
	c.custom = store
	return c
}

// Dir is the folder the sizes are cached under, one folder per size.
func (c *Cache) Dir() string { return c.dir }

// fetchTimeout bounds a download, which every request waiting for it
// shares.
const fetchTimeout = 30 * time.Second

// New caches under dir, fetching misses from baseURL (TMDB's image host).
func New(dir, baseURL string) *Cache {
	return &Cache{
		dir:      dir,
		baseURL:  strings.TrimRight(baseURL, "/"),
		http:     &http.Client{Timeout: fetchTimeout},
		maxBytes: 32 << 20,
		flight:   memo.Group{Timeout: fetchTimeout},
	}
}

// Handler serves GET /api/images/{size}/{file}. {file} is a TMDB path
// without its leading slash, e.g. "abc123.jpg".
func (c *Cache) Handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		size, file := chi.URLParam(r, "size"), chi.URLParam(r, "file")
		if !allowedSizes[size] || !fileRe.MatchString(file) {
			http.NotFound(w, r)
			return
		}
		local, err := c.ensure(r.Context(), size, file)
		switch {
		case errors.Is(err, errUpstreamNotFound):
			http.NotFound(w, r)
			return
		case err != nil && r.Context().Err() != nil:
			return // the client went away
		case err != nil:
			http.Error(w, "image unavailable: "+err.Error(), http.StatusBadGateway)
			return
		}
		w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		http.ServeFile(w, r, local) //nolint:gosec // G703: size and file passed allowedSizes and fileRe above
	}
}

var errUpstreamNotFound = errors.New("image not found upstream")

// ensure returns the local path of the image, downloading it if needed.
// Concurrent requests for the same image share one download, which runs
// apart from the request that started it: a client that leaves doesn't
// fail the others, and each gets the download's own error. size and file
// have passed allowedSizes and fileRe (Handler).
//
//nolint:gosec // G703: the path is built only from checked size and file
func (c *Cache) ensure(ctx context.Context, size, file string) (string, error) {
	local := filepath.Join(c.dir, size, file)
	if _, err := os.Stat(local); err == nil {
		return local, nil
	}
	key := size + "/" + file
	return memo.Do(ctx, &c.flight, key, func(ctx context.Context) (string, error) {
		if _, err := os.Stat(local); err == nil {
			return local, nil // a download that just finished
		}
		if artwork.IsName(file) {
			return local, c.scale(size, file, local)
		}
		return local, c.download(ctx, c.baseURL+"/"+key, local)
	})
}

// scale makes one size of a picture an admin chose. A picture that is gone,
// as after a restore without it, is not found rather than asked of TMDB.
func (c *Cache) scale(size, file, local string) error {
	if c.custom == nil {
		return errUpstreamNotFound
	}
	width := 0 // "original": as stored
	if size != "original" {
		width, _ = strconv.Atoi(strings.TrimPrefix(size, "w"))
	}
	err := c.custom.Scaled(file, width, local)
	if errors.Is(err, os.ErrNotExist) {
		return errUpstreamNotFound
	}
	return err
}

// download fetches url to local. url is the configured image host and a
// checked size and file, as is local under the cache folder.
//
//nolint:gosec // G703, G704: see above
func (c *Cache) download(ctx context.Context, url, local string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	res, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode == http.StatusNotFound {
		return errUpstreamNotFound
	}
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("upstream returned %d", res.StatusCode)
	}
	if err := os.MkdirAll(filepath.Dir(local), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(local), ".download-*")
	if err != nil {
		return err
	}
	// Cached files are served as immutable: one cut short, or the start of
	// one too large, must never be kept.
	if _, err := httpx.CopyLimited(tmp, res.Body, c.maxBytes); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmp.Name())
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmp.Name())
		return err
	}
	return os.Rename(tmp.Name(), local)
}
