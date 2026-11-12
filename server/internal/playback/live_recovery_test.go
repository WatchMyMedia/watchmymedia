package playback

import (
	"testing"
	"time"
)

func TestLiveRecoveryUsesOutputSegmentDurationWithoutChangingFilePolicy(t *testing.T) {
	for _, tc := range []struct {
		name      string
		segmentMs int64
		bufferMs  int64
		file      bool
		wantUp    bool
	}{
		{"near live edge", 2_000, 6_000, false, true},
		{"two complete segments", 2_000, 4_000, false, true},
		{"less than two segments", 2_000, 3_999, false, false},
		{"short segments retain a four-second floor", 1_000, 3_000, false, false},
		{"long copied GOP needs more buffer", 6_000, 6_000, false, false},
		{"two long segments", 6_000, 12_000, false, true},
		{"unknown duration stays conservative", 0, 6_000, false, false},
		{"invalid duration stays conservative", -1, 6_000, false, false},
		{"implausible duration stays conservative", 61_000, 6_000, false, false},
		{"file still needs twenty seconds", 2_000, 6_000, true, false},
		{"file with sufficient buffer", 2_000, 20_000, true, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			state := AutoState{}.NewStream("480", 2_192_000, autoT0)
			age := int64(0)
			var up *StepChange
			for sec := 5; sec <= 240; sec += 5 {
				in := AutoSample{At: autoT0.Add(time.Duration(sec) * time.Second), State: "playing",
					PositionMs: int64(sec * 1000), BufferedMs: tc.bufferMs, ThroughputBps: 50_000_000,
					ThroughputAgeMs: &age, Stalls: 0}
				var change *StepChange
				if tc.file {
					state, change = NextStepWithRecovery(state, in)
				} else {
					state, change = NextLiveStepWithRecovery(state, in, tc.segmentMs)
				}
				if change != nil {
					if change.NeedBps == 0 || change.To != "720" || sec != 240 {
						t.Fatalf("unexpected or early change at %ds: %+v", sec, change)
					}
					up = change
				}
			}
			if (up != nil) != tc.wantUp {
				t.Fatalf("recovery = %+v, want upgrade %v", up, tc.wantUp)
			}
		})
	}
}

func TestLiveRecoveryRetainsEvidenceAndFailureGuards(t *testing.T) {
	for _, name := range []string{"missing age", "stale age", "paced", "slow encoder", "paused", "insufficient bandwidth", "recent failure", "recent stall"} {
		t.Run(name, func(t *testing.T) {
			state := AutoState{}.NewStream("480", 2_192_000, autoT0)
			if name == "recent failure" {
				state.MarkFailure("720", autoT0.Add(180*time.Second))
			}
			for sec := 5; sec <= 300; sec += 5 {
				age := int64(0)
				in := AutoSample{At: autoT0.Add(time.Duration(sec) * time.Second), State: "playing",
					PositionMs: int64(sec * 1000), BufferedMs: 6_000, ThroughputBps: 50_000_000,
					ThroughputAgeMs: &age, Stalls: 0}
				switch name {
				case "missing age":
					in.ThroughputAgeMs = nil
				case "stale age":
					age = 25_000
				case "paced":
					in.ServerPaced = true
				case "slow encoder":
					in.TranscodeSpeed = 0.7
				case "paused":
					in.State = "paused"
				case "insufficient bandwidth":
					in.ThroughputBps = 3_000_000
				case "recent stall":
					if sec >= 190 {
						in.Stalls = 1
					}
				}
				var change *StepChange
				state, change = NextLiveStepWithRecovery(state, in, 2_000)
				if change != nil && change.NeedBps > 0 {
					t.Fatalf("upgraded with %s at %ds: %+v", name, sec, change)
				}
			}
		})
	}
}
