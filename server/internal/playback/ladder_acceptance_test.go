//go:build (darwin || linux) && streamingacceptance

package playback

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
)

// A cold request inside the ordinary 45-second lookahead must not wait for
// that entire timeline at 0.7x when an admitted encoder can seek there now.
func TestReservedVODSlowEncoderColdSeek(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 96, "0,3,6,9,12,18,21")
	bin := slowLadderFFmpeg(t, ffmpeg)
	m := NewManager(bin, t.TempDir(), Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software"}, time.Minute, 2, testLog)
	t.Cleanup(m.StopAll)
	low, high := ladderSpecs(src)
	low.VOD.DurationMs, high.VOD.DurationMs = 96_000, 96_000
	low.SharedOutput, low.ReserveSlot = true, true
	high.SharedOutput, high.ReserveSlot = true, true
	low.PreservePrevious, high.PreservePrevious = true, true
	first, err := m.Start(t.Context(), low)
	if err != nil {
		t.Fatal(err)
	}
	higher, err := m.Start(t.Context(), high)
	if err != nil {
		t.Fatal(err)
	}
	higher.vod.mu.Lock()
	higher.vod.refreshLocked()
	reached := higher.vod.run.reached
	k := higher.vod.segmentAt(reached + 30*time.Second.Microseconds())
	higher.vod.mu.Unlock()
	ctx, cancel := context.WithTimeout(t.Context(), 25*time.Second)
	defer cancel()
	started := time.Now()
	type result struct {
		k   int
		b   []byte
		err error
	}
	results := make(chan result, 2)
	for _, target := range []int{k, k + 1} {
		go func() {
			segment, _, err := higher.VODFile(ctx, fmt.Sprintf("v%05d.m4s", target))
			results <- result{target, segment, err}
		}()
	}
	init, _, err := higher.VODFile(ctx, VODInit)
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		got := <-results
		if got.err != nil {
			t.Fatalf("parallel cold segment %d inside lookahead at 0.7x exceeded readiness: %v", got.k, got.err)
		}
		file := filepath.Join(t.TempDir(), "cold.mp4")
		if err := os.WriteFile(file, append(append([]byte(nil), init...), got.b...), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := ffexec.Command(t.Context(), ffmpeg, ffexec.FFmpegArgs("-i", file, "-frames:v", "3", "-f", "null", "-")...).Run(); err != nil {
			t.Fatalf("cold segment after slow seek did not decode: %v", err)
		}
	}
	if m.Claimed() != 2 {
		t.Fatalf("slow cold seek lost its two reserved slots: %d", m.Claimed())
	}
	t.Logf("cold segment %d from %.1fs reached in %s at a 0.7x input rate", k, float64(reached)/1e6, time.Since(started).Round(time.Millisecond))
	m.Stop(first.ID)
	m.Stop(higher.ID)
	if m.Claimed() != 0 {
		t.Fatal("slow cold seek leaked a capacity claim")
	}
}

func TestVODLadderRejectsSlowPreparationBeforeManifest(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6,12,18,21")
	m := NewManager(slowLadderFFmpeg(t, ffmpeg), t.TempDir(),
		Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software"}, time.Minute, 2, testLog)
	defer m.StopAll()
	low, high := ladderSpecs(src)
	started := time.Now()
	if s, err := m.StartVODLadder(t.Context(), low, high); s != nil || !errors.Is(err, ErrLadderNotReady) {
		t.Fatalf("slow encoder received a master: session=%v error=%v", s, err)
	}
	m.StopAll()
	if m.Claimed() != 0 || len(m.Sessions()) != 0 {
		t.Fatal("readiness rejection retained claims")
	}
	if elapsed := time.Since(started); elapsed > 5*time.Second {
		t.Fatalf("readiness rejection took %s", elapsed)
	}
}

func slowLadderFFmpeg(t *testing.T, ffmpeg string) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "slow-ffmpeg")
	if err := os.WriteFile(bin, []byte("#!/bin/sh\nexec '"+strings.ReplaceAll(ffmpeg, "'", "'\\''")+"' -readrate 0.7 \"$@\"\n"), 0o700); err != nil { //nolint:gosec // executable test wrapper
		t.Fatal(err)
	}
	return bin
}

func TestVODLadderBoundsSlowdownAfterAdmission(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 96, "0,3,6,12,18,21")
	bin := slowLadderFFmpeg(t, ffmpeg)
	writeWrapper := func(slow bool) {
		t.Helper()
		args := ""
		if slow {
			args = " -readrate 0.7"
		}
		if err := os.WriteFile(bin, []byte("#!/bin/sh\nexec '"+strings.ReplaceAll(ffmpeg, "'", "'\\''")+"'"+args+" \"$@\"\n"), 0o700); err != nil { //nolint:gosec // executable test wrapper
			t.Fatal(err)
		}
	}
	writeWrapper(false)
	m := NewManager(bin, t.TempDir(), Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software"}, time.Minute, 2, testLog)
	defer m.StopAll()
	low, high := ladderSpecs(src)
	low.VOD.DurationMs, high.VOD.DurationMs = 96_000, 96_000
	first, err := m.StartVODLadder(t.Context(), low, high)
	if err != nil {
		t.Fatal(err)
	}
	higher := first.ladder.Load().members[1]
	higher.vod.suspend()
	writeWrapper(true)
	started := time.Now()
	if _, _, err := higher.VODFile(t.Context(), "v00010.m4s"); !errors.Is(err, ErrLadderNotReady) {
		t.Fatalf("slow cold segment: %v", err)
	}
	if elapsed := time.Since(started); elapsed > 8*time.Second {
		t.Fatalf("slow segment exceeded bounded response: %s", elapsed)
	}
	writeWrapper(false)
	higher.vod.suspend()
	if _, _, err := higher.VODFile(t.Context(), "v00010.m4s"); err != nil {
		t.Fatalf("failed cold work poisoned the retry: %v", err)
	}
	m.StopAll()
	if m.Claimed() != 0 {
		t.Fatal("slowdown leaked a claim")
	}
}

// Stop the viewer whose actual slow encoder is making a shared cold segment.
// The peer must receive that worker's output, without starting a replacement.
func TestSharedVODSlowProducerSurvivesViewerStop(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 96, "0,3,6,9,12,18,21")
	m := NewManager(slowLadderFFmpeg(t, ffmpeg), t.TempDir(),
		Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}, HWAccel: "software"}, time.Minute, 2, testLog)
	t.Cleanup(m.StopAll)
	spec, _ := ladderSpecs(src)
	spec.VOD.DurationMs = 96_000
	spec.SharedOutput, spec.ReserveSlot = true, true
	first, err := m.Start(t.Context(), spec)
	if err != nil {
		t.Fatal(err)
	}
	spec.UserID, spec.PlayID = 2, "peer"
	peer, err := m.Start(t.Context(), spec)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(t.Context(), 25*time.Second)
	defer cancel()
	type result struct {
		media []byte
		err   error
	}
	request := func(s *Session) <-chan result {
		out := make(chan result, 1)
		go func() {
			b, _, err := s.VODFile(ctx, "v00010.m4s")
			out <- result{b, err}
		}()
		return out
	}
	original := request(first)
	waitFollowers := func(want int) {
		t.Helper()
		for {
			first.vod.mu.Lock()
			changed := first.vod.waitLocked()
			first.vod.mu.Unlock()
			if m.RenditionStats().Followers >= want {
				return
			}
			select {
			case <-changed:
			case <-ctx.Done():
				t.Fatalf("%d requests did not join the cold producer: %v", want, ctx.Err())
			}
		}
	}
	waitFollowers(1)
	follower := request(peer)
	waitFollowers(2)
	m.Stop(first.ID)
	if got := <-original; !errors.Is(got.err, ErrStopped) {
		t.Fatalf("stopped viewer request: %v", got.err)
	}
	got := <-follower
	if got.err != nil {
		t.Fatalf("peer lost the in-flight cold segment: %v", got.err)
	}
	peer.vod.mu.Lock()
	runs := peer.vod.runs
	peer.vod.mu.Unlock()
	if runs != 0 {
		t.Fatalf("peer started %d replacement encoders", runs)
	}
	init, _, err := peer.VODFile(ctx, VODInit)
	if err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(t.TempDir(), "shared-cold.mp4")
	if err := os.WriteFile(file, append(init, got.media...), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-i", file, "-frames:v", "3", "-f", "null", "-")...).Run(); err != nil {
		t.Fatalf("shared cold segment did not decode: %v", err)
	}
	m.StopAll()
	if m.Claimed() != 0 {
		t.Fatal("shared cold segment leaked capacity")
	}
}
