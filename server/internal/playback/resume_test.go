package playback

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/probe"
)

// longGOPClip is 120 s of H.264 at 25 fps with a keyframe every 10 s and
// two B-frames, and AC-3 sound: a copied-video event session's hard case.
func longGOPClip(t *testing.T, ffmpeg string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "clip.mp4")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=120",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=120",
		"-c:v", "libx264", "-preset", "veryfast", "-g", "250", "-keyint_min", "250", "-sc_threshold", "0", "-bf", "2",
		"-c:a", "ac3", "-shortest", path).CombinedOutput(); err != nil {
		t.Skipf("ffmpeg cannot make the clip (libx264 and ac3 needed): %v\n%s", err, b)
	}
	return path
}

func needFFprobe(t *testing.T) {
	t.Helper()
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}
}

// The finder reads only around a time and finds the keyframes either side,
// with the B-frame delay ffmpeg's -t has to allow for.
func TestKeyframeFinderReadsAroundATime(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	needFFprobe(t)
	clip := longGOPClip(t, ffmpeg)
	f := NewKeyframeFinder(&probe.Prober{}, clip, nil)
	ctx := t.Context()
	for _, tc := range []struct {
		name     string
		find     func(context.Context, int64) (Keyframe, error)
		at, want int64
	}{
		{"before, between", f.AtOrBefore, 25_000_000, 20_000_000},
		{"before, on one", f.AtOrBefore, 40_000_000, 40_000_000},
		{"before, near the start", f.AtOrBefore, 3_000_000, 0},
		{"after, between", f.AtOrAfter, 52_000_000, 60_000_000},
		{"after, on one", f.AtOrAfter, 60_000_000, 60_000_000},
	} {
		started := time.Now()
		k, err := tc.find(ctx, tc.at)
		took := time.Since(started)
		if err != nil || k.Us != tc.want {
			t.Errorf("%s %d µs: %+v %v, want %d", tc.name, tc.at, k, err, tc.want)
		}
		if tc.want > 0 && k.LeadUs != 80_000 {
			t.Errorf("%s: lead %d µs, want two frames (80000)", tc.name, k.LeadUs)
		}
		t.Logf("%s took %s", tc.name, took)
		if took > time.Second {
			t.Errorf("%s took %s: the lookup should read only around the time", tc.name, took)
		}
	}
	if _, err := f.AtOrAfter(ctx, 115_000_000); !errors.Is(err, ErrNoKeyframe) {
		t.Errorf("after the last keyframe: %v, want ErrNoKeyframe", err)
	}
}

// A copied-video event session asked to start between keyframes starts on
// the one before and says so, and each 32-second run ends just before a
// keyframe the next run starts on: every listed segment begins on a source
// keyframe at the time the playlist gives it, so the player's clock (the
// session's start plus playlist time) is the file's, window after window.
// Before, the clip measured 5 s, then 2 s, then 6 s ahead (PLAN-RESUME.md §1.4).
func TestEventCopyRunsStartAndEndOnKeyframes(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	needFFprobe(t)
	clip := longGOPClip(t, ffmpeg)
	keys, err := (&probe.Prober{}).Keyframes(t.Context(), clip)
	if err != nil {
		t.Fatal(err)
	}
	m := testManager(t, ffmpeg, "software")
	m.KeyframeLookupsMayTake(10 * time.Second)
	t.Cleanup(m.StopAll)
	s, err := m.Start(context.Background(), Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: clip, Mode: ModeAudio, StartMs: 25_000,
		Video: VideoPlan{Codec: "h264", Copy: true}, Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1,
		Keyframes: NewKeyframeFinder(&probe.Prober{}, clip, nil)})
	if err != nil {
		t.Fatal(err)
	}
	if s.StartMs != 20_000 {
		t.Fatalf("session starts at %d ms, want the keyframe at 20000", s.StartMs)
	}
	s.event.keepBehind = 0
	var p eventPlaylist
	for window := 0; ; window++ {
		deadline := time.Now().Add(30 * time.Second)
		for (s.event.running() || s.event.starting) && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
		}
		raw, err := s.ReadPlaylist()
		if err != nil {
			t.Fatal(err)
		}
		p = parseEventPlaylist(raw)
		if window == 2 || strings.Contains(string(raw), "#EXT-X-ENDLIST") {
			break
		}
		s.EventSegmentRequested(p.entries[len(p.entries)-1].name)
		time.Sleep(200 * time.Millisecond)
	}
	if p.endMs < 70_000 {
		t.Fatalf("listed only %d ms over three windows", p.endMs)
	}
	startMs := int64(0)
	for _, entry := range p.entries {
		at := (s.StartMs + startMs) * 1000
		i, found := slices.BinarySearch(keys, at)
		near := found || (i < len(keys) && keys[i]-at < 40_000) || (i > 0 && at-keys[i-1] < 40_000)
		if !near {
			t.Errorf("%s starts at %d ms of the file by the playlist, which is no keyframe", entry.name, at/1000)
		}
		startMs = entry.endMs
	}
	if t.Failed() {
		raw, _ := s.ReadPlaylist()
		t.Logf("playlist:\n%s", raw)
	}
}

// waitingFinder answers when its context ends: a disk that does not.
type waitingFinder struct{}

func (waitingFinder) AtOrBefore(ctx context.Context, _ int64) (Keyframe, error) {
	<-ctx.Done()
	return Keyframe{}, ctx.Err()
}

func (waitingFinder) AtOrAfter(ctx context.Context, _ int64) (Keyframe, error) {
	<-ctx.Done()
	return Keyframe{}, ctx.Err()
}

// startOnly finds a start but no end, as near the end of a file.
type startOnly struct{ at int64 }

func (f startOnly) AtOrBefore(context.Context, int64) (Keyframe, error) {
	return Keyframe{Us: f.at}, nil
}

func (startOnly) AtOrAfter(context.Context, int64) (Keyframe, error) {
	return Keyframe{}, ErrNoKeyframe
}

// A slow lookup never holds the viewer's start up for more than its bound:
// the session starts as asked, as before.
func TestEventStartPlanGivesUpInTime(t *testing.T) {
	t.Parallel()
	started := time.Now()
	startUs, end, ok := eventStartPlan(t.Context(), waitingFinder{}, 25_000, 32*time.Second, eventStartLookup)
	if took := time.Since(started); took > eventStartLookup+200*time.Millisecond {
		t.Errorf("gave up after %s", took)
	}
	if ok || startUs != 25_000_000 || end != nil {
		t.Errorf("plan %d %v %v, want the asked-for start", startUs, end, ok)
	}
	startUs, end, ok = eventStartPlan(t.Context(), startOnly{at: 20_000_000}, 25_000, 32*time.Second, eventStartLookup)
	if !ok || startUs != 20_000_000 || end != nil {
		t.Errorf("start without an end: %d %v %v, want 20 s and no end", startUs, end, ok)
	}
}

// The run's limit leaves the keyframe that ends it to the next run: one
// millisecond before its decode time, from the run's start. On the clip,
// -t 39.919 from 20 s gave four 10-second segments and no leaked frames.
func TestEventRunLengthStopsBeforeTheKeyframe(t *testing.T) {
	t.Parallel()
	if got := eventRunLength(20_000_000, Keyframe{Us: 60_000_000, LeadUs: 80_000}); got != 39_919_000 {
		t.Errorf("run length %d µs, want 39919000", got)
	}
	if got := ceilMs(10_051_708); got != 10_052 {
		t.Errorf("ceilMs = %d, want 10052: rounding down would seek to the keyframe before", got)
	}
}

// The served playlist says where the asked-for frame is, even after the
// head is trimmed, and FFmpeg's own append source never carries the tag.
func TestEventPlaylistSaysWhereToStart(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	s := &Session{Dir: dir, StartMs: 20_000}
	e := &eventState{s: s, keepBehind: 4 * time.Second, wantMs: 25_000, m: NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 1, testLog)}
	s.event = e
	for i := range 4 {
		if err := os.WriteFile(filepath.Join(dir, fmt.Sprintf("seg%05d.m4s", i)), []byte("piece"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	playlist := []byte("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4.000000,\nseg00000.m4s\n#EXTINF:4.000000,\nseg00001.m4s\n#EXTINF:4.000000,\nseg00002.m4s\n#EXTINF:4.000000,\nseg00003.m4s\n")
	if err := os.WriteFile(filepath.Join(dir, PlaylistName), playlist, 0o600); err != nil {
		t.Fatal(err)
	}
	// Served stays short of what is prepared, so no next run is started.
	served := func() string {
		t.Helper()
		b, err := s.EventPlaylist()
		if err != nil {
			t.Fatal(err)
		}
		return string(b)
	}
	if got := served(); !strings.Contains(got, "#EXT-X-VERSION:7\n#EXT-X-START:TIME-OFFSET=5.000,PRECISE=YES\n") {
		t.Fatalf("served:\n%s", got)
	}
	// Trimming 4 s of the head leaves the frame 1 s into what is listed.
	e.servedMs.Store(8_000)
	e.prune(parseEventPlaylist(playlist))
	if got := served(); e.evictedMs.Load() != 4_000 || !strings.Contains(got, "TIME-OFFSET=1.000,") {
		t.Errorf("after trimming %d ms:\n%s", e.evictedMs.Load(), got)
	}
	e.servedMs.Store(12_000)
	e.prune(parseEventPlaylist(playlist))
	if got := served(); !strings.Contains(got, "TIME-OFFSET=0.000,") {
		t.Errorf("trimmed past the frame:\n%s", got)
	}
	e.compactPlaylist()
	raw, err := s.ReadPlaylist()
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "EXT-X-START") {
		t.Errorf("the append source carries the tag:\n%s", raw)
	}
}

// Trimming counts from the viewer as well as from what was fetched: a
// player that buffers far ahead no longer has the playlist's head trimmed
// past its picture.
func TestEventTrimmingNeverPassesTheViewer(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	m := NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 1, testLog)
	s := &Session{ID: "s1", Dir: dir, PlayID: "play", UserID: 7, StartMs: 20_000}
	e := &eventState{s: s, keepBehind: 4 * time.Second, m: m}
	s.event = e
	m.mu.Lock()
	m.sessions[s.ID] = s
	m.mu.Unlock()
	var b strings.Builder
	b.WriteString("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n")
	for i := range 15 {
		name := fmt.Sprintf("seg%05d.m4s", i)
		if err := os.WriteFile(filepath.Join(dir, name), []byte("piece"), 0o600); err != nil {
			t.Fatal(err)
		}
		b.WriteString("#EXTINF:4.000000,\n" + name + "\n")
	}
	p := parseEventPlaylist([]byte(b.String()))
	// Another user's play, and one with no ID, are not this viewer.
	m.NoteViewer("play", 8, 60_000)
	m.NoteViewer("", 7, 60_000)
	if e.viewerSeen.Load() {
		t.Fatal("noted another play's position")
	}
	// The player has fetched to 60 s and shows 20 s (40 s of the file).
	m.NoteViewer("play", 7, 40_000)
	if e.viewerMs.Load() != 20_000 {
		t.Fatalf("viewer at %d ms of the session, want 20000", e.viewerMs.Load())
	}
	e.servedMs.Store(60_000)
	e.prune(p)
	if got := e.evictedMs.Load(); got != 8_000 {
		t.Errorf("trimmed through %d ms, want 8000: no further than 10 s before the viewer", got)
	}
	// Watching on, the viewer lets the trim follow the fetched edge again.
	m.NoteViewer("play", 7, 90_000)
	e.prune(p)
	if got := e.evictedMs.Load(); got != 56_000 {
		t.Errorf("trimmed through %d ms, want 56000: 4 s behind the fetched edge", got)
	}
}
