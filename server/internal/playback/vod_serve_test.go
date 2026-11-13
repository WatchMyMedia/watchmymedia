package playback

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec/ffexectest"
)

// fakeVODFFmpeg is a stand-in ffmpeg for VOD runs: it runs script with
// $dir and $run set to the session folder and the run's name (r1, r2…),
// then sleeps until it is killed.
func fakeVODFFmpeg(t *testing.T, script string) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("needs a shell")
	}
	bin := filepath.Join(t.TempDir(), "ffmpeg")
	body := "#!/bin/sh\nfor a; do last=$a; done\ndir=$(dirname \"$last\"); run=$(basename \"$last\" .m3u8)\n" + script + "\nexec sleep 60\n"
	if err := ffexectest.WriteScript(bin, body); err != nil {
		t.Fatal(err)
	}
	return bin
}

func vodTranscodeSpec(path string) Spec {
	return Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: path, Mode: ModeTranscode, BurnIn: -1,
		Video: VideoPlan{Codec: "h264", Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 60_000, FrameRate: 24}}
}

// An init segment the server cannot use fails the request at once, not
// after vodWait, and stops the ffmpeg that wrote it.
func TestVODUnusableInitFailsAtOnce(t *testing.T) {
	t.Parallel()
	bin := fakeVODFFmpeg(t, `printf 'not an mp4' > "$dir/$run-init.mp4"
printf '#EXTM3U\n#EXTINF:6.000000,\n%s-00000.m4s\n' "$run" > "$last"`)
	m := NewManager(bin, t.TempDir(), Capabilities{Encoder: "libx264", HWAccel: "software"}, time.Minute, 4, slog.New(slog.DiscardHandler))
	start := time.Now()
	_, err := m.Start(context.Background(), vodTranscodeSpec("/m/movie.mkv"))
	if err == nil || !strings.Contains(err.Error(), "unusable init segment") {
		t.Fatalf("err = %v", err)
	}
	if d := time.Since(start); d > 5*time.Second {
		t.Errorf("took %v", d)
	}
	if len(m.Sessions()) != 0 {
		t.Error("the failed session is still listed")
	}
}

// Stopping a session ends the requests waiting on it with ErrStopped and
// kills its ffmpeg.
func TestVODStopWhileWaiting(t *testing.T) {
	t.Parallel()
	bin := fakeVODFFmpeg(t, "") // never writes a piece
	m := NewManager(bin, t.TempDir(), Capabilities{Encoder: "libx264", HWAccel: "software"}, time.Minute, 4, slog.New(slog.DiscardHandler))
	errc := make(chan error, 1)
	go func() {
		_, err := m.Start(context.Background(), vodTranscodeSpec("/m/movie.mkv"))
		errc <- err
	}()
	var s *Session
	for deadline := time.Now().Add(5 * time.Second); s == nil || !s.vod.running(); {
		if time.Now().After(deadline) {
			t.Fatal("ffmpeg did not start")
		}
		time.Sleep(10 * time.Millisecond)
		if list := m.Sessions(); len(list) == 1 {
			s = list[0]
		}
	}
	m.Stop(s.ID)
	select {
	case err := <-errc:
		if !errors.Is(err, ErrStopped) {
			t.Errorf("waiting request: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the waiting request did not end")
	}
	select {
	case <-s.done:
	case <-time.After(10 * time.Second):
		t.Fatal("ffmpeg was not stopped")
	}
}

// The video and audio requests for one segment of a SeparateAudio session,
// and repeats of them, share one read of its pieces.
func TestVODConcurrentRequestsShareOneJoin(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	t.Parallel()
	ctx := context.Background()
	src := keyedSample(t, ffmpeg, 30, "0,1.7,3.1,6.9,7.4,12,15.5,16,21.3,27")
	m := testManager(t, ffmpeg, "software")
	keys, err := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", m.log).Get(ctx, src, 1, "x")
	if err != nil {
		t.Fatal(err)
	}
	s, err := m.Start(ctx, Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeRemux, BurnIn: -1,
		Video: VideoPlan{Codec: "mpeg4", Copy: true, Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 30000, Keyframes: keys, FrameRate: 24, SeparateAudio: true}})
	if err != nil {
		t.Fatal(err)
	}
	defer m.Stop(s.ID)
	before := s.vod.joins.Load()
	var wg sync.WaitGroup
	got := make([][]byte, 8)
	for i := range got {
		wg.Go(func() {
			name := fmt.Sprintf("%c00002.m4s", "va"[i%2])
			b, _, err := s.VODFile(ctx, name)
			if err != nil || len(b) == 0 {
				t.Errorf("%s: %v (%d bytes)", name, err, len(b))
			}
			got[i] = b
		})
	}
	wg.Wait()
	if n := s.vod.joins.Load() - before; n != 1 {
		t.Errorf("segment 2 joined %d times, want 1", n)
	}
	for i := 2; i < len(got); i++ {
		if !bytes.Equal(got[i], got[i%2]) {
			t.Errorf("request %d got other bytes", i)
		}
	}
	if bytes.Equal(got[0], got[1]) {
		t.Error("the video and audio renditions are the same")
	}
}

// The run's playlist is read from where the last read ended, and from the
// top again when ffmpeg's rewrite moved what came before.
func TestVODPlaylistReadsOnlyNewPieces(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	v := &vodState{s: &Session{Dir: dir}}
	r := &vodRun{n: 1}
	path := filepath.Join(dir, "r1.m3u8")
	write := func(target int, pieces int, tail string) {
		t.Helper()
		var b strings.Builder
		fmt.Fprintf(&b, "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MAP:URI=\"r1-init.mp4\"\n", target)
		for i := range pieces {
			fmt.Fprintf(&b, "#EXTINF:%d.000000,\nr1-%05d.m4s\n", i+1, i)
		}
		b.WriteString(tail)
		if err := os.WriteFile(path, []byte(b.String()), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	read := func() string {
		var names []string
		for _, e := range v.newEntriesLocked(r) {
			names = append(names, fmt.Sprintf("%s/%g", e.name, e.dur))
			r.listed++
		}
		return strings.Join(names, " ")
	}
	write(3, 3, "")
	if got := read(); got != "r1-00000.m4s/1 r1-00001.m4s/2 r1-00002.m4s/3" {
		t.Errorf("first read: %s", got)
	}
	// Same header: the read goes on from the end of the last one. A line
	// not yet finished waits for the next read.
	write(3, 4, "#EXTINF:5.000000,\nr1-0000")
	if got := read(); got != "r1-00003.m4s/4" {
		t.Errorf("second read: %s", got)
	}
	// A longer target duration moves every line: read from the top.
	write(10, 6, "#EXT-X-ENDLIST\n")
	// A final Windows publish may leave the complete rewrite at .tmp.
	if err := os.Rename(path, path+".tmp"); err != nil {
		t.Fatal(err)
	}
	if err := finishPlaylist(path); err != nil {
		t.Fatal(err)
	}
	if got := read(); got != "r1-00004.m4s/5 r1-00005.m4s/6" {
		t.Errorf("after the header grew: %s", got)
	}
	if got := read(); got != "" {
		t.Errorf("nothing new: %s", got)
	}
}

// A tfdt too short for its version is an error, not a panic.
func TestPatchMoofShortTfdt(t *testing.T) {
	t.Parallel()
	box := func(typ string, body []byte) []byte {
		b := []byte{0, 0, 0, byte(8 + len(body))}
		return append(append(b, typ...), body...)
	}
	tfhd := box("tfhd", []byte{0, 2, 0, 0, 0, 0, 0, 1})
	init := fmp4Init{Tracks: map[uint32]fmp4Track{1: {Timescale: 1000, Offset: 10_000}}}
	for _, body := range [][]byte{nil, {1, 0, 0, 0, 0, 0, 0, 1}, {0, 0, 0, 0, 1}, {2, 0, 0, 0, 0, 0, 0, 1}} {
		moof := box("moof", box("traf", append(append([]byte{}, tfhd...), box("tfdt", body)...)))
		if err := patchMoof(moof, init); err == nil {
			t.Errorf("tfdt %v: no error", body)
		}
	}
	moof := box("moof", box("traf", append(append([]byte{}, tfhd...), box("tfdt", []byte{0, 0, 0, 0, 0, 0, 0, 5})...)))
	if err := patchMoof(moof, init); err != nil || moof[len(moof)-1] != 5+0x10 || moof[len(moof)-2] != 0x27 {
		t.Errorf("version 0: %v, % x", err, moof[len(moof)-4:])
	}
}
