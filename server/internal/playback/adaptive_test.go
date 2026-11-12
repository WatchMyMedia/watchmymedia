package playback

import (
	"testing"
	"time"
)

func TestAdaptiveObservationCannotInventARendition(t *testing.T) {
	var absent *AdaptiveStream
	if absent.Accepts(720, 4_000_000) {
		t.Fatal("absent offer accepted observation")
	}
	offer := &AdaptiveStream{Renditions: []AdaptiveRendition{{Height: 360, Bitrate: 1_504_000}, {Height: 720, Bitrate: 4_000_000}}}
	if offer.Accepts(1080, 4_000_000) || offer.Accepts(720, 1) || !offer.Accepts(720, 4_000_000) {
		t.Fatal("observation escaped offered contract")
	}
}

func TestPlayerABROwnsRungsAndRecoversOnlyToOriginal(t *testing.T) {
	t.Parallel()
	s := AutoState{PlayerABR: true, OriginalBps: 20_000_000}.NewStream("720", 4_000_000, autoT0)
	age := int64(0)
	for i := range 30 {
		var change *StepChange
		s, change = NextStepWithRecovery(s, AutoSample{At: autoT0.Add(time.Duration(i) * 10 * time.Second), State: "buffering", BufferedMs: 0,
			ThroughputBps: 100_000, ThroughputAgeMs: &age, Stalls: i, DurationMs: 3_600_000})
		if change != nil {
			t.Fatalf("server competed with player ABR: %+v", change)
		}
	}
	var change *StepChange
	for i := 30; i < 90 && change == nil; i++ {
		s, change = NextStepWithRecovery(s, AutoSample{At: autoT0.Add(time.Duration(i) * 10 * time.Second), State: "playing", BufferedMs: 30_000,
			PositionMs: int64(i) * 10_000, ThroughputBps: 50_000_000, ThroughputAgeMs: &age, Stalls: 29, DurationMs: 3_600_000})
	}
	if change == nil || change.To != "original" {
		t.Fatalf("Original recovery missing: %+v", change)
	}
}
