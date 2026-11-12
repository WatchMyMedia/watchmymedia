package playback

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

// Stopping the server stops the transcodes, waits for ffmpeg to exit, and
// starts nothing afterwards.
func TestRunStopsSessionsOnShutdown(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	t.Parallel()
	clip := sample(t, ffmpeg)
	m := testManager(t, ffmpeg, "software")
	ctx, cancel := context.WithCancel(context.Background())
	ran := make(chan struct{})
	go func() {
		m.Run(ctx)
		close(ran)
	}()
	spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: clip, Mode: ModeRemux, Video: VideoPlan{Codec: "mpeg4", Copy: true}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	s, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case <-ran:
	case <-time.After(stopWait + 5*time.Second):
		t.Fatal("Run did not return")
	}
	select {
	case <-s.done:
	default:
		t.Error("Run returned before ffmpeg exited")
	}
	if _, err := m.Start(context.Background(), spec); err == nil {
		t.Error("a session started after shutdown")
	}
}

// blockingFFmpeg is a stand-in ffmpeg or ffprobe that counts its runs and
// then waits to be killed. It execs sleep rather than forking it: on macOS
// a child forked in the instant its process group is killed can miss the
// signal (ffmpeg starts no processes, so this is the test's own problem).
func blockingFFmpeg(t *testing.T) (bin, runs string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("needs a shell")
	}
	dir := t.TempDir()
	bin, runs = filepath.Join(dir, "ffmpeg"), filepath.Join(dir, "runs")
	if err := os.WriteFile(bin, []byte("#!/bin/sh\necho run >> \""+runs+"\"\nexec sleep 30\n"), 0o700); err != nil { //nolint:gosec // G306: a script, which must be executable
		t.Fatal(err)
	}
	return bin, runs
}

// Closing stops a background keyframe scan's ffprobe and waits for it.
func TestKeyframesCloseStopsScans(t *testing.T) {
	bin, runs := blockingFFmpeg(t)
	t.Parallel()
	src := filepath.Join(t.TempDir(), "movie.mp4")
	if err := os.WriteFile(src, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	k := NewKeyframes(bin, "", slog.New(slog.DiscardHandler))
	if _, err := k.Get(context.Background(), src, 1, "x"); !errors.Is(err, ErrKeyframesPending) {
		t.Fatalf("first ask: %v", err)
	}
	for deadline := time.Now().Add(5 * time.Second); countRuns(t, runs) == 0; {
		if time.Now().After(deadline) {
			t.Fatal("the scan did not start")
		}
		time.Sleep(10 * time.Millisecond)
	}
	start := time.Now()
	k.Close()
	if d := time.Since(start); d > 5*time.Second {
		t.Errorf("Close took %v", d)
	}
}

// Closing stops extractions, delayed ones included, and waits for them.
func TestSubtitlesCloseStopsExtractions(t *testing.T) {
	bin, runs := blockingFFmpeg(t)
	t.Parallel()
	video := filepath.Join(t.TempDir(), "v.mkv")
	if err := os.WriteFile(video, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	subs := NewSubtitles(bin, filepath.Join(t.TempDir(), "subs"))
	subs.PrepareDelay = time.Hour
	subs.Prepare(1, video, []Track{{2, "webvtt"}})
	running := subs.start(video, "", []output{{embeddedKey(2, 2, "webvtt"), "0:2", "webvtt"}}, 0, true)
	for deadline := time.Now().Add(5 * time.Second); countRuns(t, runs) == 0; {
		if time.Now().After(deadline) {
			t.Fatal("the extraction did not start")
		}
		time.Sleep(10 * time.Millisecond)
	}
	start := time.Now()
	subs.Close()
	if d := time.Since(start); d > 5*time.Second {
		t.Errorf("Close took %v", d)
	}
	<-running.done
	if running.err == nil {
		t.Error("a stopped extraction reports success")
	}
	if n := countRuns(t, runs); n != 1 {
		t.Errorf("ffmpeg ran %d times; the delayed extraction should not have run", n)
	}
}
