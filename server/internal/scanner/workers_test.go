package scanner

import (
	"context"
	"fmt"
	"path/filepath"
	"sync"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/probe"
)

// slowProber takes a while over each file and notes how many it had at once.
type slowProber struct {
	mu        sync.Mutex
	now, most int
}

func (p *slowProber) Probe(context.Context, string) (*probe.MediaInfo, error) {
	p.mu.Lock()
	p.now++
	p.most = max(p.most, p.now)
	p.mu.Unlock()
	time.Sleep(time.Second)
	p.mu.Lock()
	p.now--
	p.mu.Unlock()
	return &probe.MediaInfo{Container: "matroska"}, nil
}

// Scans of two libraries at once probe no more files together than Workers.
func TestWorkersAcrossLibraries(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		s, q, _, root, movies := setup(t)
		sp := &slowProber{}
		s.probe = sp
		tvRoot := t.TempDir()
		tv, err := q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: tvRoot})
		if err != nil {
			t.Fatal(err)
		}
		for i := range 6 {
			writeOld(t, filepath.Join(root, fmt.Sprintf("Movie %d (2000).mkv", i)), "a")
			writeOld(t, filepath.Join(tvRoot, "Show", fmt.Sprintf("Show - S01E%02d.mkv", i+1)), "a")
		}
		ch, cancel := s.bus.Subscribe()
		defer cancel()
		s.Request(movies.ID)
		s.Request(tv.ID)
		for finished := 0; finished < 2; {
			if _, ok := (<-ch).Data.(ScanFinished); ok {
				finished++
			}
		}
		for _, id := range []int64{movies.ID, tv.ID} {
			if n, _ := q.CountMediaFiles(t.Context(), db.CountMediaFilesParams{LibraryID: id}); n != 6 {
				t.Errorf("library %d has %d files", id, n)
			}
		}
		if sp.most != s.opts.Workers {
			t.Errorf("%d probes at once, want %d", sp.most, s.opts.Workers)
		}
	})
}
