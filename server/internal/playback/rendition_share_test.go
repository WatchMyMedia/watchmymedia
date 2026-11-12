package playback

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

// Two viewers may reuse finished media without sharing a seekable VOD worker.
// A later uncached segment belongs to the requesting viewer's own worker.
func TestSharedVODCopyKeepsViewersIndependent(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 30, "0,1.7,3.1,6.9,7.4,12,15.5,16,21.3,27")
	m := testManager(t, ffmpeg, "software")
	t.Cleanup(m.StopAll)
	keys, err := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", m.log).Get(context.Background(), src, 1, "x")
	if err != nil {
		t.Fatal(err)
	}
	spec := Spec{FileID: 1, PlayID: "first", UserID: 1, Path: src, Mode: ModeRemux, BurnIn: -1, SharedOutput: true,
		Video: VideoPlan{Codec: "mpeg4", Copy: true, Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 30_000, Keyframes: keys, FrameRate: 24}}
	first, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	spec.PlayID, spec.UserID = "second", 2
	second, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	if first.ID == second.ID || first.UserID == second.UserID {
		t.Fatal("viewers share a session or owner")
	}
	if second.vod.run != nil {
		t.Fatal("a warm segment started the second viewer's ffmpeg")
	}
	for _, name := range []string{VODInit, "v00000.m4s"} {
		a, _, err := first.VODFile(context.Background(), name)
		if err != nil {
			t.Fatal(err)
		}
		b, _, err := second.VODFile(context.Background(), name)
		if err != nil || !bytes.Equal(a, b) {
			t.Fatalf("%s differs or failed: %v", name, err)
		}
	}
	// This segment is cold for both viewers; one seek can start a new run
	// without moving or stopping the other viewer's session.
	_, _, err = second.VODFile(context.Background(), "v00002.m4s")
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.Get(first.ID); !ok {
		t.Fatal("second viewer's seek stopped the first viewer")
	}
	_, segments := fetchAll(t, first, []int{0, 1, 2, 3})
	if len(segments) != first.Segments() {
		t.Fatalf("first viewer lost segments: %d", len(segments))
	}
	m.Stop(first.ID)
	if _, ok := m.Get(second.ID); !ok {
		t.Fatal("stopping the first viewer stopped the second")
	}
	for k := 0; k < second.Segments(); k++ {
		name := fmt.Sprintf("v%05d.m4s", k)
		if b, _, err := second.VODFile(context.Background(), name); err != nil || len(b) == 0 {
			t.Fatalf("second viewer %s after first stopped: %v", name, err)
		}
	}
	changed := time.Now().Add(time.Hour)
	if err := os.Chtimes(src, changed, changed); err != nil {
		t.Fatal(err)
	}
	if _, _, err := second.VODFile(context.Background(), "v00000.m4s"); err == nil {
		t.Fatal("a changed source reused the previous rendition")
	}
}

func TestSharedVODCopyConcurrentStartsUseOneWorker(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 30, "0,1.7,3.1,6.9,7.4,12,15.5,16,21.3,27")
	m := testManager(t, ffmpeg, "software")
	t.Cleanup(m.StopAll)
	keys, err := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", m.log).Get(context.Background(), src, 1, "x")
	if err != nil {
		t.Fatal(err)
	}
	base := Spec{FileID: 1, Path: src, Mode: ModeRemux, BurnIn: -1, SharedOutput: true,
		Video: VideoPlan{Codec: "mpeg4", Copy: true, Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 30_000, Keyframes: keys, FrameRate: 24}}
	var wg sync.WaitGroup
	var sessions [2]*Session
	var failures [2]error
	for i := range sessions {
		wg.Go(func() {
			spec := base
			spec.UserID = int64(i + 1)
			spec.PlayID = fmt.Sprintf("play-%d", i)
			sessions[i], failures[i] = m.Start(context.Background(), spec)
		})
	}
	wg.Wait()
	for i, err := range failures {
		if err != nil {
			t.Fatalf("viewer %d: %v", i, err)
		}
	}
	runs := sessions[0].vod.runs + sessions[1].vod.runs
	if runs != 1 {
		t.Fatalf("start workers = %d, want 1", runs)
	}
	for i, s := range sessions {
		init, _, err := s.VODFile(context.Background(), VODInit)
		if err != nil {
			t.Fatal(err)
		}
		segment, _, err := s.VODFile(context.Background(), "v00000.m4s")
		if err != nil {
			t.Fatal(err)
		}
		file := filepath.Join(t.TempDir(), fmt.Sprintf("viewer-%d.mp4", i))
		if err := os.WriteFile(file, append(init, segment...), 0o600); err != nil {
			t.Fatal(err)
		}
		if video, _ := packets(t, ffmpeg, file, "v:0"); len(video) == 0 {
			t.Fatalf("viewer %d received no decodable video packets", i)
		}
	}
}

func TestSharedVODSoftwareTranscodeSurvivesProducerStop(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6.5,9,12,18,21")
	m := testManager(t, ffmpeg, "software")
	t.Cleanup(m.StopAll)
	spec := Spec{FileID: 1, PlayID: "first", UserID: 1, Path: src, Mode: ModeTranscode, BurnIn: -1, SharedOutput: true,
		Video: VideoPlan{Codec: "h264", Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 24_000, FrameRate: 24}}
	first, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	spec.UserID, spec.PlayID = 2, "second"
	second, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	if second.vod.runs != 0 {
		t.Fatalf("warm software start launched %d duplicate runs", second.vod.runs)
	}
	m.Stop(first.ID)
	order := make([]int, second.Segments())
	for i := range order {
		order[i] = i
	}
	file, _ := fetchAll(t, second, order)
	pts, _ := packets(t, ffmpeg, file, "v:0")
	checkContinuous(t, pts, 0, 23.9)
}
