package playback

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"
)

// runState reads the session's run under its lock.
func runState(s *Session) (paused bool, reached int64, pieces int) {
	s.vod.mu.Lock()
	defer s.vod.mu.Unlock()
	if r := s.vod.run; r != nil {
		paused, reached = r.paused, r.reached
	}
	return paused, reached, len(s.vod.pieces)
}

// A run pauses once it is far enough ahead of the player and goes on as
// the player catches up, so playing to the end works across the pauses;
// a seek back drops the pieces far ahead of it.
func TestVODThrottle(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	t.Parallel()
	ctx := context.Background()
	src := keyedSampleAt(t, ffmpeg, "24", 180, "0")
	m := testManager(t, ffmpeg, "software")
	m.vodAhead, m.vodResume = 30*time.Second, 12*time.Second
	spec := vodTranscodeSpec(src)
	spec.VOD.DurationMs = 180_000
	s, err := m.Start(ctx, spec)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Stop(s.ID)
	if s.vod.Segments() != 30 {
		t.Fatalf("segments = %d", s.vod.Segments())
	}

	// Segment 0 asked for (to 6 s): ffmpeg stops some 30 s past it.
	eventually(t, "the run did not pause", func() bool { p, _, _ := runState(s); return p && !s.vod.running() })
	_, reached, _ := runState(s)
	if reached < 36e6 || reached > 90e6 {
		t.Errorf("paused at %d ms, want a little past 36000", reached/1000)
	}

	// Playing on from there needs the run to go on, more than once.
	order := make([]int, 30)
	for k := range order {
		order[k] = k
	}
	f, _ := fetchAll(t, s, order)
	pts, _ := packets(t, ffmpeg, f, "v:0")
	checkContinuous(t, pts, 0, 179.9)
	if s.vod.runs < 2 {
		t.Errorf("runs = %d; the run should have gone on after its pause", s.vod.runs)
	}

	// A play that starts at 90 s pauses some 30 s on; a seek back to
	// 30 s drops what lies more than 30 s past it, all of that run's pieces.
	late := spec
	late.StartMs = 90_000
	s2, err := m.Start(ctx, late)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Stop(s2.ID)
	eventually(t, "the run at 90 s did not pause", func() bool { p, _, _ := runState(s2); return p && !s2.vod.running() })
	s2.vod.waitStopped()
	ahead := func() []string {
		s2.vod.mu.Lock()
		defer s2.vod.mu.Unlock()
		var out []string
		for _, p := range s2.vod.pieces {
			if p.run.n == 1 {
				out = append(out, p.file)
			}
		}
		return out
	}
	before := ahead()
	if len(before) == 0 {
		t.Fatal("the run at 90 s made no pieces")
	}
	const back = 5
	if _, _, err := s2.VODFile(ctx, fmt.Sprintf("v%05d.m4s", back)); err != nil {
		t.Fatal(err)
	}
	if left := ahead(); len(left) != 0 {
		t.Errorf("pieces past 66 s left after the seek back: %v", left)
	}
	for _, f := range before {
		if _, err := os.Stat(f); !os.IsNotExist(err) {
			t.Errorf("%s is still on disk", f)
		}
	}
	for k := back + 1; k < 30; k++ {
		if b, _, err := s2.VODFile(ctx, fmt.Sprintf("v%05d.m4s", k)); err != nil || len(b) == 0 {
			t.Fatalf("segment %d after the seek back: %v", k, err)
		}
	}
}
