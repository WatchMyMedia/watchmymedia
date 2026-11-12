package subtitles

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/settings"
)

// fakeProvider is OpenSubtitles with two results for any search. Downloads
// wait for release when it is not nil.
type fakeProvider struct {
	srv       *httptest.Server
	searches  atomic.Int32
	downloads atomic.Int32
	release   chan struct{}
}

func newFakeProvider(t *testing.T) *fakeProvider {
	t.Helper()
	p := &fakeProvider{}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/subtitles", func(w http.ResponseWriter, r *http.Request) {
		p.searches.Add(1)
		time.Sleep(50 * time.Millisecond) // long enough for searches to overlap
		_, _ = w.Write([]byte(`{"data":[
			{"attributes":{"language":"en","download_count":10,"release":"Clip.1080p","moviehash_match":true,"files":[{"file_id":77}]}},
			{"attributes":{"language":"en","download_count":99,"release":"Clip.720p","files":[{"file_id":78}]}}]}`))
	})
	mux.HandleFunc("POST /api/v1/download", func(w http.ResponseWriter, r *http.Request) {
		p.downloads.Add(1)
		if p.release != nil {
			<-p.release
		}
		_, _ = fmt.Fprintf(w, `{"link":"%s/f.srt","remaining":4}`, p.srv.URL)
	})
	mux.HandleFunc("GET /f.srt", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("1\n00:00:01,000 --> 00:00:03,000\nOnline\n"))
	})
	p.srv = httptest.NewServer(mux)
	t.Cleanup(p.srv.Close)
	return p
}

// newTestService returns services on one database, with a file to search for.
func newTestService(t *testing.T, p *fakeProvider, n int) ([]*Service, int64) {
	t.Helper()
	ctx := context.Background()
	conn := dbtest.OpenPath(t, filepath.Join(t.TempDir(), "t.db"))
	q := db.New(conn)
	dir := t.TempDir()
	path := filepath.Join(dir, "Clip (2020).mp4")
	if err := os.WriteFile(path, make([]byte, 300_000), 0o600); err != nil {
		t.Fatal(err)
	}
	lib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: dir})
	if err != nil {
		t.Fatal(err)
	}
	now := db.FormatTime(time.Now())
	f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: path, Size: 300_000, Mtime: now, AddedAt: now, Parsed: "{}", LastSeenAt: now, SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	var out []*Service
	downloads := t.TempDir()
	for range n {
		client := opensubtitles.New("key", "WatchMyMedia vtest", p.srv.URL+"/api/v1")
		out = append(out, New(q, settings.New(q), client, downloads, slog.New(slog.DiscardHandler)))
	}
	return out, f.ID
}

// Searches for a file are shared while they run and reused for a day.
func TestSearchIsShared(t *testing.T) {
	t.Parallel()
	p := newFakeProvider(t)
	svcs, file := newTestService(t, p, 1)
	s := svcs[0]
	var wg sync.WaitGroup
	for range 4 {
		wg.Go(func() {
			if got := s.Search(context.Background(), file, nil); len(got.Results) != 2 || got.Error != "" || got.Results[0].ID != "77" {
				t.Errorf("search = %+v", got)
			}
		})
	}
	wg.Wait()
	if n := p.searches.Load(); n != 1 {
		t.Errorf("provider searched %d times for overlapping searches", n)
	}
	s.Search(context.Background(), file, nil)
	if n := p.searches.Load(); n != 1 {
		t.Errorf("a search within the day asked the provider again (%d)", n)
	}
	// Other languages are another search.
	s.Search(context.Background(), file, []string{"es"})
	if n := p.searches.Load(); n != 2 {
		t.Errorf("searches = %d, want 2", n)
	}
	// After a day it asks again.
	s.mu.Lock()
	for _, c := range s.searches {
		c.at = c.at.Add(-searchTTL - time.Minute)
	}
	s.mu.Unlock()
	s.Search(context.Background(), file, nil)
	if n := p.searches.Load(); n != 3 {
		t.Errorf("searches after a day = %d, want 3", n)
	}
}

// Two clicks on a result at once spend one download; a later click is
// served from what was kept; a kept file gone from disk is downloaded again.
func TestDownloadOnce(t *testing.T) {
	t.Parallel()
	p := newFakeProvider(t)
	p.release = make(chan struct{})
	svcs, file := newTestService(t, p, 1)
	s := svcs[0]
	ctx := context.Background()
	s.Search(ctx, file, nil)
	rows := make([]db.DownloadedSubtitle, 2)
	var wg sync.WaitGroup
	for i := range rows {
		wg.Go(func() {
			var err error
			if rows[i], err = s.Download(ctx, file, "77", 0); err != nil {
				t.Errorf("click %d: %v", i, err)
			}
		})
	}
	for p.downloads.Load() == 0 {
		time.Sleep(time.Millisecond)
	}
	time.Sleep(20 * time.Millisecond) // the second click joins meanwhile
	close(p.release)
	wg.Wait()
	if n := p.downloads.Load(); n != 1 || rows[0].ID != rows[1].ID || rows[0].ID == 0 {
		t.Fatalf("%d downloads, rows %d and %d", n, rows[0].ID, rows[1].ID)
	}
	again, err := s.Download(ctx, file, "77", 0)
	if err != nil || again.ID != rows[0].ID || p.downloads.Load() != 1 {
		t.Errorf("later click: %v, row %d, %d downloads", err, again.ID, p.downloads.Load())
	}
	if err := os.Remove(again.Path); err != nil {
		t.Fatal(err)
	}
	if fresh, err := s.Download(ctx, file, "77", 0); err != nil || p.downloads.Load() != 2 {
		t.Errorf("after the file went: %v, %+v, %d downloads", err, fresh, p.downloads.Load())
	}
	if _, err := s.Download(ctx, file, "12345", 0); !errors.Is(err, ErrNotFound) {
		t.Errorf("unsearched result: %v", err)
	}
}

// Two servers on one database downloading one result keep one row.
func TestDownloadKeptMeanwhile(t *testing.T) {
	t.Parallel()
	p := newFakeProvider(t)
	p.release = make(chan struct{})
	svcs, file := newTestService(t, p, 2)
	ctx := context.Background()
	rows := make([]db.DownloadedSubtitle, 2)
	errs := make([]error, 2)
	var wg sync.WaitGroup
	for i, s := range svcs {
		s.Search(ctx, file, nil)
		wg.Go(func() { rows[i], errs[i] = s.Download(ctx, file, "77", 0) })
	}
	for p.downloads.Load() < 2 {
		time.Sleep(time.Millisecond)
	}
	close(p.release)
	wg.Wait()
	if errs[0] != nil || errs[1] != nil || rows[0].ID != rows[1].ID {
		t.Errorf("rows %d %d, errors %v %v", rows[0].ID, rows[1].ID, errs[0], errs[1])
	}
}
