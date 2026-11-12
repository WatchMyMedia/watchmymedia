package art

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/httpx"
	"watchmymedia/server/internal/memo"
)

const (
	// maxPicture bounds one picture. The largest the feed named was 82 KB
	// (PLAN-LIVE-TV-GUIDE.md §7.1).
	maxPicture = 1 << 20
	// maxFolder bounds the folder. Two days' pictures came to about 40 MB.
	maxFolder = 256 << 20
	// keepFor is how long a picture stays after the last programme or
	// channel using it ends.
	keepFor = 7 * 24 * time.Hour
	// touchEvery is how often serving a picture is recorded, so a guide's
	// hundreds of requests don't each write a row.
	touchEvery = time.Hour
	// fetchTimeout bounds a download, which every request waiting for it
	// shares.
	fetchTimeout = 30 * time.Second
	// missingFor and failedFor are how long a picture the host didn't have,
	// or failed to send, is answered from memory rather than asked for
	// again: a guide asks for a hundred pictures at once, and again on
	// every scroll.
	missingFor = time.Hour
	failedFor  = time.Minute
	// cacheControl lets a client keep a picture for a day, as the host
	// does. The picture at a feed URL can change, unlike TMDB's.
	cacheControl = "private, max-age=86400"
	// tempPrefix starts the name of a download in progress.
	tempPrefix = ".download-"
)

// types are the pictures kept, as http.DetectContentType names them.
var types = map[string]bool{"image/jpeg": true, "image/png": true, "image/webp": true}

var (
	errNotFound   = errors.New("picture not found upstream")
	errRefused    = errors.New("picture address not allowed")
	errGone       = errors.New("picture no longer listed")
	errRedirect   = errors.New("redirected off the picture host")
	errNotPicture = errors.New("not a JPEG, PNG or WebP picture")
)

// Cache keeps fetched pictures under its folder, each in a file named by
// its key, and their sizes and use in live_tv_guide_art.
type Cache struct {
	q    *db.Queries
	dir  string
	http *http.Client
	log  *slog.Logger
	now  func() time.Time

	// maxBytes bounds one picture and maxTotal the folder; tests lower
	// them.
	maxBytes, maxTotal int64

	// flight shares one download of a picture among the requests for it,
	// and failed remembers the ones that failed for a while.
	flight memo.Group
	failed *memo.Map[string, error]

	// mu serialises changes to the folder and to the rows' bytes: a
	// download's rename and record, eviction, Sweep and Clear.
	mu sync.Mutex
}

// New caches under dir. transport carries the picture requests; nil is the
// standard one. A nil log is slog.Default and a nil now is time.Now.
func New(q *db.Queries, dir string, transport http.RoundTripper, log *slog.Logger, now func() time.Time) *Cache {
	if log == nil {
		log = slog.Default()
	}
	if now == nil {
		now = time.Now
	}
	return &Cache{
		q:        q,
		dir:      dir,
		http:     &http.Client{Transport: transport, Timeout: fetchTimeout, CheckRedirect: checkRedirect},
		log:      log,
		now:      now,
		maxBytes: maxPicture,
		maxTotal: maxFolder,
		flight:   memo.Group{Timeout: fetchTimeout},
		failed:   memo.NewMap[string, error](0, 4096),
	}
}

// checkRedirect follows a redirect only to an address Allowed accepts: the
// same host list, over HTTPS.
func checkRedirect(req *http.Request, via []*http.Request) error {
	if len(via) >= 3 {
		return errors.New("too many redirects")
	}
	if !Allowed(req.URL.String()) {
		return errRedirect
	}
	return nil
}

// Handler serves GET /api/live-tv/art/{id}. The first request for a
// picture downloads it; later ones are served from the folder.
func (c *Cache) Handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		id := chi.URLParam(r, "id")
		if !ValidKey(id) {
			http.NotFound(w, r)
			return
		}
		row, err := c.q.GetGuideArt(ctx, id)
		switch {
		case errors.Is(err, sql.ErrNoRows):
			http.NotFound(w, r)
			return
		case err != nil && ctx.Err() != nil:
			return // the client went away
		case err != nil:
			c.log.Warn("live TV picture lookup failed", "err", err)
			http.Error(w, "picture unavailable", http.StatusInternalServerError)
			return
		}
		if row.Bytes > 0 {
			if f, ok := c.open(id); ok {
				c.touch(ctx, row)
				serve(w, r, f, row.ContentType)
				return
			}
		}
		ctype, err := c.ensure(ctx, row)
		if err == nil {
			if f, ok := c.open(id); ok {
				serve(w, r, f, ctype)
				return
			}
			err = errGone // evicted at once; rare enough to fail
		}
		switch {
		case ctx.Err() != nil:
			return // the client went away
		case errors.Is(err, errNotFound), errors.Is(err, errRefused), errors.Is(err, errGone):
			http.NotFound(w, r)
		default:
			http.Error(w, "picture unavailable", http.StatusBadGateway)
		}
	}
}

// path is where id's picture is kept. Only a checked key names a file, so
// a path never leaves the folder.
func (c *Cache) path(id string) (string, bool) {
	if c.dir == "" || !ValidKey(id) {
		return "", false
	}
	return filepath.Join(c.dir, id), true
}

// open opens id's picture, if it is on disk.
func (c *Cache) open(id string) (*os.File, bool) {
	p, ok := c.path(id)
	if !ok {
		return nil, false
	}
	f, err := os.Open(p)
	if err != nil {
		return nil, false
	}
	return f, true
}

// serve sends f, which it closes, as a picture of type ctype.
func serve(w http.ResponseWriter, r *http.Request, f *os.File, ctype string) {
	defer func() { _ = f.Close() }()
	info, err := f.Stat()
	if err != nil {
		http.Error(w, "picture unavailable", http.StatusInternalServerError)
		return
	}
	h := w.Header()
	h.Set("Content-Type", ctype)
	h.Set("Cache-Control", cacheControl)
	h.Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", info.ModTime(), f)
}

// touch records that row's picture was served, at most once every
// touchEvery, for least-recently-served eviction.
func (c *Cache) touch(ctx context.Context, row db.LiveTvGuideArt) {
	now := c.now().Unix()
	if now-row.LastUsedAt < int64(touchEvery/time.Second) {
		return
	}
	if err := c.q.TouchGuideArt(ctx, db.TouchGuideArtParams{LastUsedAt: now, ID: row.ID}); err != nil && ctx.Err() == nil {
		c.log.Warn("live TV picture use not recorded", "err", err)
	}
}

// ensure downloads row's picture and returns its type. Concurrent requests
// for one picture share a download, which runs apart from the request that
// started it: a client that leaves fails nobody else.
func (c *Cache) ensure(ctx context.Context, row db.LiveTvGuideArt) (string, error) {
	if err, ok := c.failed.Get(row.ID); ok {
		return "", err
	}
	if !Allowed(row.Url) {
		c.log.Warn("live TV picture address refused", "url", logURL(row.Url))
		c.failed.SetFor(row.ID, errRefused, missingFor)
		return "", errRefused
	}
	return memo.Do(ctx, &c.flight, row.ID, func(ctx context.Context) (string, error) {
		ctype, err := c.download(ctx, row.ID, row.Url)
		switch {
		case errors.Is(err, errGone):
		case errors.Is(err, errNotFound):
			c.log.Warn("live TV picture missing upstream", "url", logURL(row.Url))
			c.failed.SetFor(row.ID, err, missingFor)
		case err != nil:
			c.log.Warn("live TV picture unavailable", "url", logURL(row.Url), "err", httpx.Redact(err))
			c.failed.SetFor(row.ID, err, failedFor)
		}
		return ctype, err
	})
}

// picture is what a download measured.
type picture struct {
	bytes         int64
	contentType   string
	width, height int
}

// download fetches rawURL, which passed Allowed, into id's file and records
// it.
//
//nolint:gosec // G703, G704: rawURL passed Allowed; the files are under the folder, named by a checked key
func (c *Cache) download(ctx context.Context, id, rawURL string) (string, error) {
	row, err := c.q.GetGuideArt(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", errGone
	}
	if err != nil {
		return "", err
	}
	local, ok := c.path(id)
	if !ok {
		return "", errors.New("no picture folder")
	}
	if row.Bytes > 0 {
		if _, err := os.Stat(local); err == nil {
			return row.ContentType, nil // a download that just finished
		}
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "image/jpeg, image/png, image/webp")
	res, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer func() { _ = res.Body.Close() }()
	switch {
	case res.StatusCode == http.StatusNotFound || res.StatusCode == http.StatusGone:
		return "", errNotFound
	case res.StatusCode != http.StatusOK:
		return "", fmt.Errorf("upstream returned %d", res.StatusCode)
	}

	if err := os.MkdirAll(c.dir, 0o755); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(c.dir, tempPrefix+"*")
	if err != nil {
		return "", err
	}
	// Nothing cut short, too large or not a picture is kept.
	p, err := c.receive(tmp, res.Body)
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err == nil {
		err = c.keep(ctx, id, tmp.Name(), local, p)
	}
	if err != nil {
		_ = os.Remove(tmp.Name())
		return "", err
	}
	return p.contentType, nil
}

// receive copies body into tmp and measures it. The first bytes must be a
// JPEG, PNG or WebP picture, whatever the host called it.
func (c *Cache) receive(tmp *os.File, body io.Reader) (picture, error) {
	n, err := httpx.CopyLimited(tmp, body, c.maxBytes)
	if err != nil {
		return picture{}, err
	}
	if _, err := tmp.Seek(0, io.SeekStart); err != nil {
		return picture{}, err
	}
	head := make([]byte, 512)
	m, err := io.ReadFull(tmp, head)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
		return picture{}, err
	}
	p := picture{bytes: n, contentType: http.DetectContentType(head[:m])}
	if !types[p.contentType] {
		return picture{}, fmt.Errorf("%w (%s)", errNotPicture, p.contentType)
	}
	// The feed's declared sizes are wrong (§7.1), so they are measured.
	// The standard library reads no WebP, whose size stays unknown.
	var cfg image.Config
	switch p.contentType {
	case "image/webp":
		return p, nil
	case "image/jpeg":
		_, err = tmp.Seek(0, io.SeekStart)
		if err == nil {
			cfg, err = jpeg.DecodeConfig(tmp)
		}
	case "image/png":
		_, err = tmp.Seek(0, io.SeekStart)
		if err == nil {
			cfg, err = png.DecodeConfig(tmp)
		}
	}
	if err != nil {
		return picture{}, fmt.Errorf("%w: %w", errNotPicture, err)
	}
	p.width, p.height = cfg.Width, cfg.Height
	return p, nil
}

// keep moves a download into place and records it, then keeps the folder
// within its limit.
func (c *Cache) keep(ctx context.Context, id, tmp, local string, p picture) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	// Listings may have been turned off, or the picture swept, meanwhile.
	if _, err := c.q.GetGuideArt(ctx, id); errors.Is(err, sql.ErrNoRows) {
		return errGone
	} else if err != nil {
		return err
	}
	if err := os.Rename(tmp, local); err != nil {
		return err
	}
	err := c.q.GuideArtFetched(ctx, db.GuideArtFetchedParams{
		Bytes: p.bytes, ContentType: p.contentType, Width: int64(p.width), Height: int64(p.height),
		LastUsedAt: c.now().Unix(), ID: id,
	})
	if err != nil {
		_ = os.Remove(local)
		return err
	}
	if err := c.evict(ctx, id); err != nil {
		c.log.Warn("live TV pictures not kept within their limit", "err", err)
	}
	return nil
}

// evict removes the least recently served pictures, apart from keep, until
// the folder is within its limit. The caller holds c.mu.
func (c *Cache) evict(ctx context.Context, keep string) error {
	total, err := c.q.GuideArtBytes(ctx)
	if err != nil || total <= c.maxTotal {
		return err
	}
	cached, err := c.q.ListGuideArtCached(ctx)
	if err != nil {
		return err
	}
	var errs []error
	for _, r := range cached {
		if total <= c.maxTotal {
			break
		}
		if r.ID == keep {
			continue
		}
		if err := c.remove(r.ID); err != nil {
			errs = append(errs, err)
			continue
		}
		if err := c.q.EvictGuideArt(ctx, r.ID); err != nil {
			return errors.Join(append(errs, err)...)
		}
		total -= r.Bytes
	}
	return errors.Join(errs...)
}

// remove deletes id's file, if there is one.
func (c *Cache) remove(id string) error {
	p, ok := c.path(id)
	if !ok {
		return nil
	}
	if err := os.Remove(p); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}

// Sweep removes pictures unused for a week and keeps the folder under its
// size limit.
func (c *Cache) Sweep(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	unused, err := c.q.ListGuideArtUnused(ctx, c.now().Add(-keepFor).Unix())
	if err != nil {
		return err
	}
	var errs []error
	for _, id := range unused {
		if err := c.remove(id); err != nil {
			errs = append(errs, err)
			continue
		}
		if err := c.q.DeleteGuideArt(ctx, id); err != nil {
			return errors.Join(append(errs, err)...)
		}
	}
	return errors.Join(append(errs, c.evict(ctx, ""), c.tidy(ctx))...)
}

// tidy removes what a crash can leave in the folder: downloads cut off and
// pictures no row records. The caller holds c.mu, so no download is being
// moved into place.
func (c *Cache) tidy(ctx context.Context) error {
	if c.dir == "" {
		return nil
	}
	entries, err := os.ReadDir(c.dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	cached, err := c.q.ListGuideArtCached(ctx)
	if err != nil {
		return err
	}
	kept := make(map[string]bool, len(cached))
	for _, r := range cached {
		kept[r.ID] = true
	}
	stale := c.now().Add(-time.Hour)
	var errs []error
	for _, e := range entries {
		name := e.Name()
		switch {
		case ValidKey(name):
			if kept[name] {
				continue
			}
		case strings.HasPrefix(name, tempPrefix):
			if info, err := e.Info(); err != nil || info.ModTime().After(stale) {
				continue
			}
		default:
			continue
		}
		if err := os.Remove(filepath.Join(c.dir, name)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

// Clear deletes every picture, for when listings are turned off.
func (c *Cache) Clear(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.failed.Clear()
	err := c.q.DeleteAllGuideArt(ctx)
	if c.dir != "" {
		err = errors.Join(err, os.RemoveAll(c.dir))
	}
	return err
}

// logURL cuts a picture's address to its scheme, host and path for the log.
func logURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return "(address hidden)"
	}
	return (&url.URL{Scheme: u.Scheme, Host: u.Host, Path: u.Path}).String()
}
