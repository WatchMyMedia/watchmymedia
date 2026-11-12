package playback

import (
	"context"
	"errors"
	"testing"
	"testing/synctest"
	"time"
)

func TestLadderPreparationRequiresFreshHeadroom(t *testing.T) {
	now := time.Unix(1_600_000_000, 0)
	for _, tc := range []struct {
		name  string
		work  time.Duration
		age   time.Duration
		slots int
		ready bool
	}{
		{"fast", time.Second, 0, 2, true},
		{"slow", 9 * time.Second, 0, 2, false},
		{"full configured budget", time.Second, 0, 8, false},
		{"expired evidence", time.Second, 31 * time.Second, 2, false},
		{"clock moved backward", time.Second, -time.Second, 2, false},
		{"no measurement", 0, 0, 2, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := ladderPreparationReady(tc.work, 6*time.Second, now.Add(-tc.age), now, tc.slots); got != tc.ready {
				t.Fatalf("ready = %v, want %v", got, tc.ready)
			}
		})
	}
}

func TestWarmRenditionCannotInventFastOrFreshPreparation(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		c := newRenditionCache(1024, 1, 2, time.Minute)
		defer c.close()
		produce := func(context.Context) ([]byte, error) { time.Sleep(3 * time.Second); return []byte("complete"), nil }
		_, release, err := c.get(t.Context(), t.Context(), "startup", produce, func([]byte) error { return nil })
		if err != nil {
			t.Fatal(err)
		}
		release()
		_, release, err = c.get(t.Context(), t.Context(), "startup", produce, func([]byte) error { return nil })
		if err != nil {
			t.Fatal(err)
		}
		work, producedAt := c.preparation("startup")
		if work != 3*time.Second || ladderPreparationReady(work, 6*time.Second, producedAt, time.Now(), 2) {
			t.Fatal("warm cache read replaced the producer's slow measurement")
		}
		release()
		time.Sleep(31 * time.Second)
		_, release, err = c.get(t.Context(), t.Context(), "startup", produce, func([]byte) error { return nil })
		if err != nil {
			t.Fatal(err)
		}
		_, again := c.preparation("startup")
		release()
		if !again.Equal(producedAt) || ladderPreparationReady(time.Second, 6*time.Second, again, time.Now(), 2) {
			t.Fatal("warm cache access refreshed stale producer evidence")
		}
	})
}

func TestLadderKeepsEncoderAtAdjacentAudioBoundary(t *testing.T) {
	// With a six-second caller budget, unknown encoder speed must not turn
	// an ordinary next-segment request into a seek that loses AAC priming.
	s := &Session{}
	s.ladder.Store(&vodFamily{})
	run := &vodRun{seg: 0, reached: 12_000_000, done: make(chan struct{}),
		cancel: func() { t.Fatal("next-segment request restarted the common audio encoder") }}
	v := &vodState{s: s, spec: Spec{SharedOutput: true, ReserveSlot: true},
		opts: VODOptions{FrameRate: 24}, bounds: []int64{0, 6_000_000, 12_000_000, 18_000_000}, run: run}
	if err := v.ensureLocked(2); err != nil || v.run != run {
		t.Fatalf("adjacent segment changed the encoder: %v", err)
	}
}

func TestVODLadderFailedReplacementKeepsExistingFamily(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	src := keyedSample(t, ffmpeg, 24, "0,3,6,12,18,21")
	// Two slots: the admission budget scales with the configured slots, and
	// the existing family then leaves no room for a replacement's two rungs.
	m := NewManager(ffmpeg, t.TempDir(), detectedCaps(ffmpeg, "software"), time.Minute, 2, testLog)
	defer m.StopAll()
	low, high := ladderSpecs(src)
	first, err := m.StartVODLadder(t.Context(), low, high)
	if errors.Is(err, ErrLadderNotReady) {
		// Admission measures this host's encoder; a loaded CI machine can be
		// too slow for any family. That is the budget working, and without a
		// family there is nothing to roll back to.
		t.Skipf("host too slow to admit the family this rollback needs: %v", err)
	}
	if err != nil {
		t.Fatal(err)
	}
	low.StartMs, high.StartMs = 6000, 6000
	// The replacement is refused before either rung starts, and the family
	// that is already playing must be untouched.
	if _, err := m.StartVODLadder(t.Context(), low, high); !errors.Is(err, ErrTooManySessions) {
		t.Fatalf("replacement: %v", err)
	}
	for _, member := range first.ladder.Load().members {
		if _, ok := m.Get(member.ID); !ok {
			t.Fatal("failed replacement stopped existing family")
		}
		if _, _, err := member.VODFile(t.Context(), "v00000.m4s"); err != nil {
			t.Fatal(err)
		}
	}
	if m.Claimed() != 2 {
		t.Fatalf("replacement leaked a claim: %d", m.Claimed())
	}
}
