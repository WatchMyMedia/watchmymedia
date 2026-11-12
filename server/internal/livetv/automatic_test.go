package livetv

import (
	"context"
	"math"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/playback"
)

func TestLiveProgressWriterBoundsAndClassifiesEncoderSpeed(t *testing.T) {
	t.Parallel()
	s := &session{}
	w := &liveProgressWriter{s: s}
	for _, chunk := range []string{"frame=120\nspe", "ed=0.7x\nprogress=continue\n"} {
		if _, err := w.Write([]byte(chunk)); err != nil {
			t.Fatal(err)
		}
	}
	if got := math.Float64frombits(s.encoderSpeed.Load()); got != 0.7 || time.Since(time.Unix(0, s.encoderProgressAt.Load())) > time.Second {
		t.Fatalf("encoder progress: %.1fx at %d", got, s.encoderProgressAt.Load())
	}
	_, _ = w.Write([]byte("speed=NaNx\nspeed=Infinityx\n" + strings.Repeat("a", 300)))
	if len(w.partial) != 0 || math.Float64frombits(s.encoderSpeed.Load()) != 0.7 {
		t.Fatal("malformed progress changed speed or retained unbounded output")
	}
}

func TestLiveAutomaticHoldsNetworkDecisionWhileOutputWaits(t *testing.T) {
	t.Parallel()
	s := &session{}
	if liveOutputFresh(s) || liveOutputFresh(nil) {
		t.Fatal("an output without a produced segment is fresh")
	}
	s.outputAt.Store(time.Now().UnixNano())
	if !liveOutputFresh(s) {
		t.Fatal("recent live output appears stalled")
	}
	s.outputAt.Store(time.Now().Add(-7 * time.Second).UnixNano())
	if liveOutputFresh(s) {
		t.Fatal("delayed segment production was treated as network evidence")
	}
}

// A player that reported its family failed cannot keep playing it. When the
// single-output replacement is never acknowledged, the play ends instead of
// staying on the family while later failure reports are ignored.
func TestLiveFamilyFallbackTimeoutEndsThePlay(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	m := &Sessions{items: map[string]*session{}}
	family := &playback.AdaptiveStream{Renditions: []playback.AdaptiveRendition{{Height: 360}, {Height: 720}}}
	s := &session{cancel: cancel, auto: &liveAuto{ladderDisabled: true, currentID: "family", pendingID: "replacement", pendingAt: time.Now().Add(-time.Minute), pendingTo: "720"}}
	s.view = SessionView{State: "playing", Adaptive: family, Directive: &LiveDirective{Generation: 1, To: "720"}}
	m.tickAutomatic(s, time.Now())
	if s.view.State != "failed" || s.view.Reason != "output_failed" || ctx.Err() == nil {
		t.Fatalf("stuck on a failed family: state %q reason %q", s.view.State, s.view.Reason)
	}

	// An ordinary step that times out keeps the output that still plays.
	ctx, cancel = context.WithCancel(t.Context())
	defer cancel()
	s = &session{cancel: cancel, auto: &liveAuto{currentID: "single", pendingID: "higher", pendingAt: time.Now().Add(-time.Minute), pendingTo: "1080"}}
	s.view = SessionView{State: "playing", Directive: &LiveDirective{Generation: 1, To: "1080"}}
	m.items["single"] = &session{view: SessionView{State: "playing"}}
	m.tickAutomatic(s, time.Now())
	if s.view.State != "playing" || ctx.Err() != nil || s.view.SwitchStatus != "acknowledgement_timeout" {
		t.Fatalf("timed-out step ended a playing output: %+v", s.view)
	}
}
