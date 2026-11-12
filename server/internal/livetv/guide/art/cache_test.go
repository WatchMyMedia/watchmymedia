package art

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"image/jpeg"
	"io/fs"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/go-chi/chi/v5"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/livetv/guide/guidetest"
)

const host = "https://img.hdhomerun.com"

// env is a cache on a fake picture host, with a clock the test moves.
type env struct {
	t    *testing.T
	q    *db.Queries
	c    *Cache
	pics *guidetest.Pictures
	h    http.Handler
	dir  string
	log  *bytes.Buffer
	unix atomic.Int64
}

func newEnv(t *testing.T) *env {
	t.Helper()
	_, q := dbtest.Open(t)
	pics := guidetest.NewPictures()
	srv := httptest.NewServer(pics)
	t.Cleanup(srv.Close)
	e := &env{t: t, q: q, pics: pics, dir: filepath.Join(t.TempDir(), "live-tv-art"), log: &bytes.Buffer{}}
	// Real time, as files' dates are compared with it.
	e.unix.Store(time.Now().Unix())
	// The redirect's target is the same fake, so a followed redirect
	// would show in its counts.
	tr := guidetest.Transport(map[string]string{"img.hdhomerun.com": srv.URL, "pictures.example.net": srv.URL})
	e.c = New(q, e.dir, tr, slog.New(slog.NewTextHandler(e.log, nil)), e.now)
	r := chi.NewRouter()
	r.Get(Path+"{id}", e.c.Handler())
	e.h = r
	return e
}

func (e *env) now() time.Time          { return time.Unix(e.unix.Load(), 0) }
func (e *env) advance(d time.Duration) { e.unix.Add(int64(d / time.Second)) }

// register lists u's picture as used until the given time, as a refresh
// does, and returns its key.
func (e *env) register(u string, until time.Time) string {
	e.t.Helper()
	id := Key(u)
	if err := e.q.UpsertGuideArt(e.t.Context(), db.UpsertGuideArtParams{ID: id, Url: u, UsedUntil: until.Unix()}); err != nil {
		e.t.Fatal(err)
	}
	return id
}

func (e *env) get(id string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, httptest.NewRequestWithContext(e.t.Context(), http.MethodGet, Path+id, nil))
	return w
}

func (e *env) row(id string) db.LiveTvGuideArt {
	e.t.Helper()
	r, err := e.q.GetGuideArt(e.t.Context(), id)
	if err != nil {
		e.t.Fatalf("row %s: %v", id, err)
	}
	return r
}

func (e *env) onDisk(id string) bool {
	_, err := os.Stat(filepath.Join(e.dir, id))
	return err == nil
}

// files are the names in the folder, downloads in progress included.
func (e *env) files() []string {
	e.t.Helper()
	entries, err := os.ReadDir(e.dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		e.t.Fatal(err)
	}
	var names []string
	for _, f := range entries {
		names = append(names, f.Name())
	}
	return names
}

func (e *env) fetch(id string) {
	e.t.Helper()
	if w := e.get(id); w.Code != http.StatusOK {
		e.t.Fatalf("%s: %d %s", id, w.Code, w.Body)
	}
}

func TestTitleIsFetchedOnceThenServedFromDisk(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	id := e.register(host+"/titles/SH0001.jpg", e.now().Add(24*time.Hour))

	var wg sync.WaitGroup
	codes := make([]int, 8)
	for i := range codes {
		wg.Go(func() { codes[i] = e.get(id).Code })
	}
	wg.Wait()
	for i, code := range codes {
		if code != http.StatusOK {
			t.Errorf("concurrent request %d = %d", i, code)
		}
	}

	w := e.get(id)
	if w.Code != http.StatusOK {
		t.Fatalf("again = %d", w.Code)
	}
	if got := w.Header().Get("Content-Type"); got != "image/jpeg" {
		t.Errorf("Content-Type = %q", got)
	}
	if got := w.Header().Get("Cache-Control"); got != "private, max-age=86400" {
		t.Errorf("Cache-Control = %q", got)
	}
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(w.Body.Bytes()))
	if err != nil || cfg.Width != 480 || cfg.Height != 270 {
		t.Errorf("served %dx%d, %v", cfg.Width, cfg.Height, err)
	}
	if n := e.pics.Requests("/titles/SH0001.jpg"); n != 1 {
		t.Errorf("fetched %d times, want 1", n)
	}
	r := e.row(id)
	if r.Bytes != int64(w.Body.Len()) || r.ContentType != "image/jpeg" || r.Width != 480 || r.Height != 270 {
		t.Errorf("recorded %d bytes %q %dx%d", r.Bytes, r.ContentType, r.Width, r.Height)
	}
	if r.LastUsedAt != e.now().Unix() {
		t.Errorf("last used %d, want %d", r.LastUsedAt, e.now().Unix())
	}
	if !e.onDisk(id) {
		t.Error("not in the folder")
	}

	// Serving is recorded at most once an hour.
	fetched := r.LastUsedAt
	e.advance(10 * time.Minute)
	e.fetch(id)
	if got := e.row(id).LastUsedAt; got != fetched {
		t.Errorf("touched after 10 minutes: %d", got)
	}
	e.advance(time.Hour)
	e.fetch(id)
	if got := e.row(id).LastUsedAt; got != e.now().Unix() {
		t.Errorf("not touched after 70 minutes: %d, want %d", got, e.now().Unix())
	}
	if n := e.pics.Requests("/titles/SH0001.jpg"); n != 1 {
		t.Errorf("fetched %d times, want 1", n)
	}
}

func TestLogoIsAPNG(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	id := e.register(host+"/channels/US12345.png", e.now().Add(time.Hour))
	w := e.get(id)
	if w.Code != http.StatusOK || w.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("logo = %d %q", w.Code, w.Header().Get("Content-Type"))
	}
	if r := e.row(id); r.Width != 360 || r.Height != 270 || r.ContentType != "image/png" {
		t.Errorf("recorded %q %dx%d", r.ContentType, r.Width, r.Height)
	}
}

func TestBadPicturesAreNotKept(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name string
		want int
		why  string // in the log
	}{
		{"big.jpg", http.StatusBadGateway, "larger than allowed"},
		{"page.jpg", http.StatusBadGateway, "not a JPEG, PNG or WebP picture (text/html"},
		{"elsewhere.jpg", http.StatusBadGateway, "redirected off the picture host"},
		{"gone.jpg", http.StatusNotFound, "missing upstream"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			e := newEnv(t)
			// The query stands for anything that mustn't reach the log.
			id := e.register(host+"/titles/"+tc.name+"?k=secret", e.now().Add(time.Hour))
			w := e.get(id)
			if w.Code != tc.want {
				t.Fatalf("= %d %s, want %d", w.Code, w.Body, tc.want)
			}
			if strings.Contains(w.Body.String(), "hdhomerun") {
				t.Errorf("the reply names the address: %s", w.Body)
			}
			if r := e.row(id); r.Bytes != 0 || r.ContentType != "" {
				t.Errorf("recorded %d bytes %q", r.Bytes, r.ContentType)
			}
			if f := e.files(); len(f) != 0 {
				t.Errorf("left in the folder: %v", f)
			}
			if n := e.pics.Requests("/x.jpg"); n != 0 {
				t.Errorf("followed the redirect off the host %d times", n)
			}
			log := e.log.String()
			if !strings.Contains(log, "level=WARN") || !strings.Contains(log, tc.why) ||
				!strings.Contains(log, "/titles/"+tc.name) || strings.Contains(log, "secret") {
				t.Errorf("log: %s", log)
			}

			// Asking again soon is answered from memory.
			if w := e.get(id); w.Code != tc.want {
				t.Errorf("again = %d", w.Code)
			}
			if n := e.pics.Requests("/titles/" + tc.name); n != 1 {
				t.Errorf("fetched %d times, want 1", n)
			}
		})
	}
}

func TestSlowPictureIsBounded(t *testing.T) {
	t.Parallel()
	synctest.Test(t, func(t *testing.T) {
		_, q := dbtest.Open(t)
		pics := guidetest.NewPictures()
		// The in-memory server takes every request its client sends.
		srv := httptest.NewTestServer(t, pics)
		c := New(q, t.TempDir(), srv.Client().Transport, slog.New(slog.DiscardHandler), nil)
		r := chi.NewRouter()
		r.Get(Path+"{id}", c.Handler())
		u := host + "/titles/slow.jpg"
		id := Key(u)
		if err := q.UpsertGuideArt(t.Context(), db.UpsertGuideArtParams{ID: id, Url: u, UsedUntil: time.Now().Add(time.Hour).Unix()}); err != nil {
			t.Fatal(err)
		}
		get := func(ctx context.Context, w *httptest.ResponseRecorder) {
			r.ServeHTTP(w, httptest.NewRequestWithContext(ctx, http.MethodGet, Path+id, nil))
		}

		// The first client leaves while the picture downloads, and gets
		// nothing; the second waits out the shared download's bound.
		first, leave := context.WithCancel(t.Context())
		left, waited := httptest.NewRecorder(), httptest.NewRecorder()
		start := time.Now()
		var wg sync.WaitGroup
		wg.Go(func() { get(first, left) })
		synctest.Wait()
		wg.Go(func() { get(t.Context(), waited) })
		synctest.Wait()
		leave()
		wg.Wait()
		if left.Body.Len() != 0 || left.Header().Get("Content-Type") != "" {
			t.Errorf("the client that left got %d %q", left.Code, left.Body)
		}
		if waited.Code != http.StatusBadGateway {
			t.Errorf("slow picture = %d, want 502", waited.Code)
		}
		if took := time.Since(start); took < fetchTimeout || took > fetchTimeout+time.Second {
			t.Errorf("took %v, want %v", took, fetchTimeout)
		}
		if n := pics.Requests("/titles/slow.jpg"); n != 1 {
			t.Errorf("fetched %d times, want 1", n)
		}

		// A failure is remembered for a minute, then tried again.
		w := httptest.NewRecorder()
		get(t.Context(), w)
		if w.Code != http.StatusBadGateway || pics.Requests("/titles/slow.jpg") != 1 {
			t.Errorf("at once = %d after %d fetches", w.Code, pics.Requests("/titles/slow.jpg"))
		}
		time.Sleep(failedFor)
		get(t.Context(), httptest.NewRecorder())
		if n := pics.Requests("/titles/slow.jpg"); n != 2 {
			t.Errorf("after a minute fetched %d times, want 2", n)
		}
	})
}

func TestAddressesOffTheHostAreNeverFetched(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	for _, u := range []string{
		"https://pictures.example.net/titles/x.jpg",
		"http://img.hdhomerun.com/titles/x.jpg",
		"https://img.hdhomerun.com:8443/titles/x.jpg",
		"https://user@img.hdhomerun.com/titles/x.jpg",
	} {
		id := e.register(u, e.now().Add(time.Hour))
		if w := e.get(id); w.Code != http.StatusNotFound {
			t.Errorf("%s = %d, want 404", u, w.Code)
		}
	}
	if n := e.pics.Requests("/titles/x.jpg"); n != 0 {
		t.Errorf("fetched %d times", n)
	}
}

func TestUnknownPicturesAreNotFound(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	for _, id := range []string{Key(host + "/titles/unlisted.jpg"), "not-a-key", "..%2F..%2Fetc%2Fpasswd", strings.Repeat("A", 24)} {
		if w := e.get(id); w.Code != http.StatusNotFound {
			t.Errorf("%s = %d, want 404", id, w.Code)
		}
	}
	if n := e.pics.Requests("/titles/unlisted.jpg"); n != 0 {
		t.Errorf("fetched an unlisted picture %d times", n)
	}
}

func TestSweepDropsPicturesUnusedForAWeek(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	old := e.register(host+"/titles/OLD.jpg", e.now().Add(-8*24*time.Hour))
	recent := e.register(host+"/titles/RECENT.jpg", e.now().Add(-6*24*time.Hour))
	current := e.register(host+"/titles/CURRENT.jpg", e.now().Add(24*time.Hour))
	for _, id := range []string{old, recent, current} {
		e.fetch(id)
	}
	// What a crash can leave: a download cut off an hour ago, one under
	// way, and a picture no row records.
	cutOff := filepath.Join(e.dir, tempPrefix+"1")
	underWay := filepath.Join(e.dir, tempPrefix+"2")
	unrecorded := filepath.Join(e.dir, Key(host+"/titles/LOST.jpg"))
	for _, p := range []string{cutOff, underWay, unrecorded} {
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	twoHours := e.now().Add(-2 * time.Hour)
	if err := os.Chtimes(cutOff, twoHours, twoHours); err != nil {
		t.Fatal(err)
	}

	if err := e.c.Sweep(t.Context()); err != nil {
		t.Fatal(err)
	}
	if _, err := e.q.GetGuideArt(t.Context(), old); !errors.Is(err, sql.ErrNoRows) {
		t.Errorf("the old picture's row: %v", err)
	}
	if e.onDisk(old) {
		t.Error("the old picture is still in the folder")
	}
	for _, id := range []string{recent, current} {
		if e.row(id).Bytes == 0 || !e.onDisk(id) {
			t.Errorf("%s was swept", id)
		}
	}
	for p, want := range map[string]bool{cutOff: false, underWay: true, unrecorded: false} {
		if _, err := os.Stat(p); (err == nil) != want {
			t.Errorf("%s kept = %v, want %v", filepath.Base(p), err == nil, want)
		}
	}
}

func TestFolderLimitEvictsLeastRecentlyServed(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	until := e.now().Add(30 * 24 * time.Hour)
	p1 := e.register(host+"/titles/P1.jpg", until)
	p2 := e.register(host+"/titles/P2.jpg", until)
	p3 := e.register(host+"/titles/P3.jpg", until)
	p4 := e.register(host+"/titles/P4.jpg", until)
	for _, id := range []string{p1, p2, p3} {
		e.fetch(id)
		e.advance(time.Hour)
	}
	e.fetch(p1) // served again: P2 is now the least recently served
	s1, s2, s3 := e.row(p1).Bytes, e.row(p2).Bytes, e.row(p3).Bytes

	kept := func(want map[string]bool) {
		t.Helper()
		for id, k := range want {
			r := e.row(id)
			if (r.Bytes > 0) != k || e.onDisk(id) != k {
				t.Errorf("%s: %d bytes, on disk %v; want kept %v", id, r.Bytes, e.onDisk(id), k)
			}
		}
	}

	// Sweep keeps the folder within the limit.
	e.c.maxTotal = s1 + s2 + s3 - 1
	if err := e.c.Sweep(t.Context()); err != nil {
		t.Fatal(err)
	}
	kept(map[string]bool{p1: true, p2: false, p3: true})

	// So does a download.
	e.c.maxTotal = s1 + s3 + s3/2
	e.advance(time.Hour)
	e.fetch(p4)
	kept(map[string]bool{p1: true, p3: false, p4: true})
	if total, err := e.q.GuideArtBytes(t.Context()); err != nil || total > e.c.maxTotal {
		t.Errorf("total %d over %d (%v)", total, e.c.maxTotal, err)
	}

	// An evicted picture is fetched again when asked for.
	e.fetch(p2)
	if n := e.pics.Requests("/titles/P2.jpg"); n != 2 {
		t.Errorf("P2 fetched %d times, want 2", n)
	}
}

func TestClearDeletesEverything(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	id := e.register(host+"/posters/MV0001.jpg", e.now().Add(time.Hour))
	e.fetch(id)
	if r := e.row(id); r.Width != 320 || r.Height != 480 {
		t.Errorf("poster %dx%d", r.Width, r.Height)
	}
	if err := e.c.Clear(t.Context()); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(e.dir); !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("the folder: %v", err)
	}
	if _, err := e.q.GetGuideArt(t.Context(), id); !errors.Is(err, sql.ErrNoRows) {
		t.Errorf("the row: %v", err)
	}
	if w := e.get(id); w.Code != http.StatusNotFound {
		t.Errorf("after Clear = %d", w.Code)
	}
}
