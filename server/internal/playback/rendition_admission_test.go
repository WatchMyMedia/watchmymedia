package playback

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

func TestReservedColdRungsKeepTheirProcessClaims(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 12, "0,3,6.5,9")
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 2, testLog)
	t.Cleanup(m.StopAll)
	keys, err := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", m.log).Get(context.Background(), src, 1, "x")
	if err != nil {
		t.Fatal(err)
	}
	spec := Spec{FileID: 1, Path: src, Mode: ModeRemux, BurnIn: -1, ReserveSlot: true,
		Video: VideoPlan{Codec: "mpeg4", Copy: true, Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 12_000, Keyframes: keys, FrameRate: 24}}
	spec.PlayID, spec.UserID = "one", 1
	one, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	spec.PlayID, spec.UserID = "two", 2
	two, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	one.vod.suspend()
	two.vod.suspend()
	m.mu.Lock()
	claims := m.claimedLocked(nil)
	m.mu.Unlock()
	if claims != 2 {
		t.Fatalf("cold rung claims = %d, want 2", claims)
	}
	spec.PlayID, spec.UserID = "three", 3
	if _, err := m.Start(context.Background(), spec); !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("a third rung stole claimed capacity: %v", err)
	}
	if release, err := m.ReserveLive(context.Background()); !errors.Is(err, ErrTooManySessions) {
		if release != nil {
			release()
		}
		t.Fatalf("live start stole claimed capacity: %v", err)
	}
	m.Stop(one.ID)
	if _, err := m.Start(context.Background(), spec); err != nil {
		t.Fatalf("released claim did not admit another rung: %v", err)
	}
}
