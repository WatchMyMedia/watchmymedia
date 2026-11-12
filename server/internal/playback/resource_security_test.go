package playback

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestVODReservationsRespectTheLimitConcurrently(t *testing.T) {
	m := NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 2, nil)
	for i := range 20 {
		m.sessions[strconv.Itoa(i)] = &Session{vod: &vodState{}}
	}
	var accepted atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for _, s := range m.sessions {
		wg.Go(func() {
			<-start
			if m.reserveRun(s) {
				accepted.Add(1)
			}
		})
	}
	close(start)
	wg.Wait()
	if accepted.Load() != 2 {
		t.Fatalf("reserved %d processes with limit 2", accepted.Load())
	}
}

func TestSubtitleJobsHaveNoUnboundedQueue(t *testing.T) {
	bin, runs := slowFFmpeg(t, "0.1")
	video := filepath.Join(t.TempDir(), "movie.mkv")
	if err := os.WriteFile(video, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	s := NewSubtitles(bin, t.TempDir())
	var pending []*extraction
	for i := range maxSubtitleJobs {
		key := embeddedKey(int64(i), 1, "webvtt")
		pending = append(pending, s.start(video, "", []output{{key, "0:1", "webvtt"}}, time.Hour, true))
	}
	if _, err := s.Embedded(context.Background(), 999, video, 1, "webvtt"); !errors.Is(err, ErrSubtitleBusy) {
		t.Fatalf("excess job: %v", err)
	}
	if countRuns(t, runs) != 0 {
		t.Fatal("delayed jobs or rejected work started ffmpeg")
	}
	for _, job := range pending {
		job.kick()
		<-job.done
	}
	if _, err := s.Embedded(context.Background(), 999, video, 1, "webvtt"); err != nil {
		t.Fatalf("capacity not released: %v", err)
	}
}

func TestLibraryFilesCannotEscapeThroughLinks(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(t.TempDir(), "private.txt")
	if err := os.WriteFile(outside, []byte("synthetic private data"), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "Movie.mp4")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if f, err := OpenLibraryFile(root, link); err == nil {
		_ = f.Close()
		t.Fatal("served link outside library")
	}
	if err := ValidateLibraryPath(root, link); err == nil {
		t.Fatal("allowed parser input outside library")
	}
	if f, err := OpenLibraryFile(root, outside); err == nil {
		_ = f.Close()
		t.Fatal("served stale outside database path")
	}
	if err := os.Symlink(outside, filepath.Join(root, "Movie.en.vtt")); err != nil {
		t.Fatal(err)
	}
	if len(FindSidecars(link)) != 0 {
		t.Fatal("included linked sidecar")
	}
	inside := filepath.Join(root, "regular.mp4")
	if err := os.WriteFile(inside, []byte("synthetic clip"), 0o600); err != nil {
		t.Fatal(err)
	}
	f, err := OpenLibraryFile(root, inside)
	if err != nil {
		t.Fatal(err)
	}
	_ = f.Close()
}
