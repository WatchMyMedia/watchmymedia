package playback

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
)

// slowFFmpeg is a stand-in ffmpeg that takes a while, counts its runs, and
// writes one cue to every output it is given (the argument after -f's).
func slowFFmpeg(t *testing.T, delay string) (bin, runs string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("needs a shell")
	}
	dir := t.TempDir()
	runs = filepath.Join(dir, "runs")
	bin = filepath.Join(dir, "ffmpeg")
	script := `#!/bin/sh
echo run >> "` + runs + `"
sleep ` + delay + `
while [ $# -gt 0 ]; do
  if [ "$1" = "-f" ]; then shift; shift; printf 'WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n' > "$1"; fi
  shift
done
`
	if err := os.WriteFile(bin, []byte(script), 0o700); err != nil { //nolint:gosec // G306: a script, which must be executable
		t.Fatal(err)
	}
	return bin, runs
}

func countRuns(t *testing.T, runs string) int {
	b, _ := os.ReadFile(runs)
	return strings.Count(string(b), "run")
}

func TestExtractionOutlivesTheRequest(t *testing.T) {
	t.Parallel()
	bin, runs := slowFFmpeg(t, "0.4")
	video := filepath.Join(t.TempDir(), "v.mkv")
	if err := os.WriteFile(video, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	subs := NewSubtitles(bin, filepath.Join(t.TempDir(), "subs"))

	// The first request gives up; the extraction carries on.
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	if _, err := subs.Embedded(ctx, 1, video, 2, "webvtt"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("first request: %v", err)
	}
	// Two more arrive meanwhile and share the same run.
	var wg sync.WaitGroup
	for range 2 {
		wg.Go(func() {
			b, err := subs.Embedded(context.Background(), 1, video, 2, "webvtt")
			if err != nil || !strings.Contains(string(b), "Hi") {
				t.Errorf("waiting request: %q, %v", b, err)
			}
		})
	}
	wg.Wait()
	if n := countRuns(t, runs); n != 1 {
		t.Errorf("ffmpeg ran %d times, want 1", n)
	}
	// Then it is cached.
	if _, err := subs.Embedded(context.Background(), 1, video, 2, "webvtt"); err != nil || countRuns(t, runs) != 1 {
		t.Errorf("cached: %v, runs %d", err, countRuns(t, runs))
	}
}

func TestPrepareReadsOnceForAllTracks(t *testing.T) {
	t.Parallel()
	bin, runs := slowFFmpeg(t, "0.1")
	video := filepath.Join(t.TempDir(), "v.mkv")
	if err := os.WriteFile(video, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	subs := NewSubtitles(bin, filepath.Join(t.TempDir(), "subs"))
	subs.PrepareDelay = time.Hour // only a request starts it early
	subs.Prepare(1, video, []Track{{2, "webvtt"}, {3, "webvtt"}, {3, "ass"}, {2, "webvtt"}})
	if n := countRuns(t, runs); n != 0 {
		t.Fatalf("ran before the delay: %d", n)
	}
	// Asking for one track starts the shared run at once.
	if _, err := subs.Embedded(context.Background(), 1, video, 3, "ass"); err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"1-s2.vtt", "1-s3.vtt", "1-s3.ass"} {
		if _, err := os.Stat(filepath.Join(subs.dir, key)); err != nil {
			t.Errorf("%s not cached: %v", key, err)
		}
	}
	if n := countRuns(t, runs); n != 1 {
		t.Errorf("ffmpeg ran %d times, want 1", n)
	}
	// Nothing left to prepare.
	subs.Prepare(1, video, []Track{{2, "webvtt"}})
	subs.mu.Lock()
	jobs := len(subs.jobs)
	subs.mu.Unlock()
	if jobs != 0 {
		t.Errorf("prepared again: %d jobs", jobs)
	}
}

// A track cached since the caller looked is not extracted again.
func TestStartLooksAtTheCacheAgain(t *testing.T) {
	bin, runs := slowFFmpeg(t, "0.1")
	t.Parallel()
	video := filepath.Join(t.TempDir(), "v.mkv")
	if err := os.WriteFile(video, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	subs := NewSubtitles(bin, filepath.Join(t.TempDir(), "subs"))
	key := embeddedKey(1, 2, "webvtt")
	if err := os.MkdirAll(subs.dir, 0o755); err != nil {
		t.Fatal(err)
	}
	// As a run that finished just now leaves it.
	if err := os.WriteFile(filepath.Join(subs.dir, key), []byte("WEBVTT\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	e := subs.start(video, "", []output{{key, "0:2", "webvtt"}}, 0, true)
	select {
	case <-e.done:
	default:
		t.Fatal("waits for a run")
	}
	if e.err != nil || countRuns(t, runs) != 0 {
		t.Errorf("err %v, runs %d", e.err, countRuns(t, runs))
	}
}

// Reading the video for its subtitles runs at low priority, so it waits for
// playback's reads of the same disk; converting a small subtitle file does not.
func TestVideoReadsRunAtLowPriority(t *testing.T) {
	t.Parallel()
	if runtime.GOOS == "windows" {
		t.Skip("needs a shell")
	}
	if _, err := exec.LookPath("nice"); err != nil {
		t.Skip("no nice")
	}
	dir := t.TempDir()
	niceness := filepath.Join(dir, "niceness")
	bin := filepath.Join(dir, "ffmpeg")
	script := `#!/bin/sh
ps -o nice= -p $$ | tr -d ' ' >> "` + niceness + `"
while [ $# -gt 0 ]; do
  if [ "$1" = "-f" ]; then shift; shift; printf 'WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n' > "$1"; fi
  shift
done
`
	if err := os.WriteFile(bin, []byte(script), 0o700); err != nil { //nolint:gosec // G306: a script, which must be executable
		t.Fatal(err)
	}
	video := filepath.Join(dir, "v.mkv")
	srt := filepath.Join(dir, "d1.srt")
	for _, f := range []string{video, srt} {
		if err := os.WriteFile(f, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	// The niceness this test runs at: 0 at a terminal, -10 on GitHub's macOS
	// runners. Low priority adds 15, up to 19 on Linux and 20 on macOS.
	own, err := exec.Command("ps", "-o", "nice=", "-p", strconv.Itoa(os.Getpid())).Output()
	if err != nil {
		t.Fatal(err)
	}
	base, err := strconv.Atoi(strings.TrimSpace(string(own)))
	if err != nil {
		t.Fatalf("niceness %q: %v", own, err)
	}
	subs := NewSubtitles(bin, filepath.Join(dir, "subs"))
	if _, err := subs.Embedded(context.Background(), 1, video, 2, "webvtt"); err != nil {
		t.Fatal(err)
	}
	if _, err := subs.Downloaded(context.Background(), 1, srt, "webvtt"); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(niceness)
	if err != nil {
		t.Fatal(err)
	}
	got := strings.Fields(string(b))
	if len(got) != 2 {
		t.Fatalf("niceness of the video read, then the file conversion = %q, want two values", got)
	}
	read, _ := strconv.Atoi(got[0])
	if read < min(base+15, 19) || got[1] != strconv.Itoa(base) {
		t.Errorf("niceness of the video read, then the file conversion = %q, want [%d %d]", got, min(base+15, 19), base)
	}
}

func TestWindows1252Sidecar(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	dir := t.TempDir()
	srt := filepath.Join(dir, "movie.es.srt")
	// "Café, ¡señor!" in Windows-1252, which ffmpeg refuses as UTF-8.
	if err := os.WriteFile(srt, []byte("1\r\n00:00:01,000 --> 00:00:03,000\r\nCaf\xe9, \xa1se\xf1or!\r\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	subs := NewSubtitles(ffmpeg, filepath.Join(dir, "subs"))
	vtt, err := subs.Sidecar(context.Background(), 1, 0, Sidecar{Path: srt, Codec: "subrip"}, "webvtt")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(vtt), "Café, ¡señor!") {
		t.Errorf("vtt = %q", vtt)
	}
}

func TestShiftSubtitle(t *testing.T) {
	t.Parallel()
	vtt := "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nGone\n\n00:01:59.500 --> 00:02:01.000 line:90%\nSpans\n\n00:02:10.000 --> 00:02:12.250\nLater\n"
	got := string(ShiftSubtitle([]byte(vtt), "webvtt", 120_000))
	want := "WEBVTT\n\n00:00:00.000 --> 00:00:01.000 line:90%\nSpans\n\n00:00:10.000 --> 00:00:12.250\nLater\n\n"
	if got != want {
		t.Errorf("vtt:\n%q\nwant\n%q", got, want)
	}
	ass := "[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Gone\nDialogue: 0,0:02:10.00,0:02:12.25,Default,,0,0,0,,Later, with, commas\n"
	gotASS := string(ShiftSubtitle([]byte(ass), "ass", 120_000))
	wantASS := "[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:10.00,0:00:12.25,Default,,0,0,0,,Later, with, commas\n"
	if gotASS != wantASS {
		t.Errorf("ass:\n%q\nwant\n%q", gotASS, wantASS)
	}
	if string(ShiftSubtitle([]byte(vtt), "webvtt", 0)) != vtt {
		t.Error("no shift changed the track")
	}
}
