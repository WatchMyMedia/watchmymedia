package playback

import (
	"fmt"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/probe"
)

var autoT0 = time.Date(2026, 10, 30, 21, 0, 0, 0, time.UTC)

func TestStartStep(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name                      string
		local                     bool
		estimate, proven, fileBps int64
		want                      string
	}{
		{"local network", true, 2_000_000, 0, 40_000_000, "original"},
		{"nothing measured", false, 0, 0, 40_000_000, "original"},
		{"fast enough", false, 60_000_000, 0, 40_000_000, "original"},
		{"6 Mbps for a 4K remux", false, 6_000_000, 0, 40_000_000, "720"},
		{"12 Mbps for a 4K remux", false, 12_000_000, 0, 40_000_000, "1080"},
		{"proven to carry the file", false, 0, 40_000_000, 40_000_000, "original"},
		{"proven at 720p", false, 0, 4_192_000, 40_000_000, "720"},
		{"proven at 480p", false, 0, 2_192_000, 40_000_000, "480"},
		{"skips steps that would not lower the bitrate", false, 5_000_000, 0, 6_000_000, "720"},
		{"too slow for anything", false, 500_000, 0, 10_000_000, "360"},
		{"file bitrate unknown", false, 3_000_000, 0, 0, "original"},
	}
	for _, c := range cases {
		got, reason := StartStep(c.local, c.estimate, c.proven, c.fileBps)
		if got.ID != c.want || reason == "" {
			t.Errorf("%s: got %s (%q), want %s", c.name, got.ID, reason, c.want)
		}
	}
}

func TestStepCap(t *testing.T) {
	t.Parallel()
	p := Profile{MaxWidth: 3840, MaxHeight: 2160}
	got := StepByID("720").Cap(p)
	if got.MaxWidth != 1280 || got.MaxHeight != 720 || got.MaxBitrate != 4_000_000 {
		t.Errorf("720 on a 4K profile: %+v", got)
	}
	p = Profile{MaxWidth: 1280, MaxHeight: 720, MaxBitrate: 3_000_000}
	if got := StepByID("1080").Cap(p); got.MaxWidth != 1280 || got.MaxBitrate != 3_000_000 {
		t.Errorf("1080 on a tighter profile: %+v", got)
	}
	if got := StepByID("original").Cap(p); got.MaxWidth != p.MaxWidth || got.MaxHeight != p.MaxHeight || got.MaxBitrate != p.MaxBitrate {
		t.Errorf("original changed the profile: %+v", got)
	}
	if StepByID("nonsense").ID != "original" {
		t.Error("unknown steps should be the original")
	}
}

func TestDecisionBitrate(t *testing.T) {
	t.Parallel()
	info := mkInfo("mov", "h264", 8, "", "aac", 2)
	info.Audio[0].BitRate = 160_000
	if d := Decide(info, ".mp4", Request{Profile: webProfile()}, nil, Capabilities{}); d.Mode != ModeDirect || d.Bitrate != 8_000_000 {
		t.Errorf("direct play: %s at %d", d.Mode, d.Bitrate)
	}
	p := StepByID("720").Cap(webProfile())
	if d := Decide(info, ".mp4", Request{Profile: p}, nil, Capabilities{}); d.Mode != ModeTranscode || d.Bitrate != 4_000_000+160_000 {
		t.Errorf("720p: %s at %d", d.Mode, d.Bitrate)
	}
	dts := mkInfo("matroska", "h264", 8, "", "dts", 6)
	dts.Video[0].BitRate = 7_000_000
	if d := Decide(dts, ".mkv", Request{Profile: webProfile()}, nil, Capabilities{}); d.Mode != ModeAudio || d.Bitrate != 7_000_000+transcodeAudioBitrate {
		t.Errorf("audio transcode: %s at %d", d.Mode, d.Bitrate)
	}
}

// beat is a heartbeat at second s of the play.
func beat(s int, state string, posS, bufS float64) AutoSample {
	return AutoSample{At: autoT0.Add(time.Duration(s) * time.Second), State: state,
		PositionMs: int64(posS * 1000), BufferedMs: int64(bufS * 1000), Stalls: -1}
}

func TestAutomaticRecoveryRequiresSustainedFreshUnpacedEvidence(t *testing.T) {
	zero := int64(0)
	state := AutoState{OriginalBps: 6_000_000}.NewStream("720", 4_192_000, autoT0)
	var change *StepChange
	for sec := 5; sec <= 240; sec += 5 {
		in := AutoSample{At: autoT0.Add(time.Duration(sec) * time.Second), State: "playing", PositionMs: int64(sec * 1000),
			BufferedMs: 30_000, DurationMs: 3_600_000, ThroughputBps: 12_000_000, ThroughputAgeMs: &zero, Stalls: 0}
		state, change = NextStepWithRecovery(state, in)
		if sec < 240 && change != nil {
			t.Fatalf("upgraded after %d seconds: %+v", sec, change)
		}
	}
	if change == nil || change.To != "original" {
		t.Fatalf("expected direct original after sustained recovery, got %+v", change)
	}
}

func TestAutomaticRecoveryEventuallyReturnsToDirectOriginal(t *testing.T) {
	zero := int64(0)
	state := AutoState{OriginalBps: 8_100_000}.NewStream("480", 2_000_000, autoT0)
	want := []string{"720", "1080", "original"}
	next := 0
	for sec := 5; sec <= 720; sec += 5 {
		in := AutoSample{At: autoT0.Add(time.Duration(sec) * time.Second), State: "playing", PositionMs: int64(sec * 1000),
			BufferedMs: 30_000, DurationMs: 3_600_000, ThroughputBps: 50_000_000, ThroughputAgeMs: &zero, Stalls: 0}
		var change *StepChange
		state, change = NextStepWithRecovery(state, in)
		if change == nil {
			continue
		}
		if next >= len(want) || change.To != want[next] || sec != (next+1)*240 {
			t.Fatalf("recovery switch %d at %ds: got %+v, want %s at %ds", next, sec, change, want[next], (next+1)*240)
		}
		state = state.NewStream(change.To, change.NeedBps, in.At)
		next++
	}
	if next != len(want) {
		t.Fatalf("got %d recovery switches, want %d ending at direct Original", next, len(want))
	}
}

func TestNegotiatedAutomaticDoesNotMistakeFastHLSBufferSawtoothForSlowLink(t *testing.T) {
	for _, tc := range []struct {
		name       string
		bps        int64
		ageMs      int64
		encoder    float64
		shouldDrop bool
	}{
		{name: "fresh fast link", bps: 50_000_000},
		{name: "stale sample", bps: 50_000_000, ageMs: 25_000, shouldDrop: true},
		{name: "slow link", bps: 5_000_000, shouldDrop: true},
		{name: "slow encoder", bps: 50_000_000, encoder: 0.7, shouldDrop: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			state := AutoState{OriginalBps: 8_100_000}.NewStream("720", 4_000_000, autoT0)
			var change *StepChange
			for sec := 5; sec <= 50 && change == nil; sec += 5 {
				in := AutoSample{At: autoT0.Add(time.Duration(sec) * time.Second), State: "playing", PositionMs: int64(sec * 1000),
					BufferedMs: int64(30_000 - sec*400), DurationMs: 3_600_000, ThroughputBps: tc.bps,
					ThroughputAgeMs: &tc.ageMs, TranscodeSpeed: tc.encoder, Stalls: 0}
				state, change = NextStepWithRecovery(state, in)
			}
			if (change != nil) != tc.shouldDrop {
				t.Fatalf("unexpected buffer-drain decision: %+v", change)
			}
		})
	}
}

func TestAutomaticRecoveryRejectsStalePacedAndRecentFailure(t *testing.T) {
	for _, tc := range []struct {
		name   string
		age    *int64
		paced  bool
		failed bool
	}{
		{name: "missing age"},
		{name: "stale age", age: func() *int64 { v := int64(25_000); return &v }()},
		{name: "server paced", age: func() *int64 { v := int64(0); return &v }(), paced: true},
		{name: "recent failure", age: func() *int64 { v := int64(0); return &v }(), failed: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			state := AutoState{OriginalBps: 6_000_000}.NewStream("720", 4_192_000, autoT0)
			if tc.failed {
				state.failedAt[0] = autoT0.Add(3 * time.Minute)
			}
			for sec := 5; sec <= 300; sec += 5 {
				in := AutoSample{At: autoT0.Add(time.Duration(sec) * time.Second), State: "playing", PositionMs: int64(sec * 1000),
					BufferedMs: 30_000, DurationMs: 3_600_000, ThroughputBps: 12_000_000, ThroughputAgeMs: tc.age, ServerPaced: tc.paced, Stalls: 0}
				var change *StepChange
				state, change = NextStepWithRecovery(state, in)
				if change != nil {
					t.Fatalf("upgraded with %s at %d: %+v", tc.name, sec, change)
				}
			}
		})
	}
}

func TestNextStepRules(t *testing.T) {
	t.Parallel()
	start := AutoState{}.NewStream("original", 6_000_000, autoT0)

	t.Run("slow start", func(t *testing.T) {
		s, ch := NextStep(start, beat(5, "buffering", 0, 0.5))
		if ch != nil {
			t.Fatalf("stepped down after 5 s: %+v", ch)
		}
		// Nothing arrived, so nothing is measured: one step.
		_, ch = NextStep(s, beat(9, "buffering", 0, 0.5))
		if ch == nil || ch.To != "720" || !strings.Contains(ch.Reason, "slow to start") {
			t.Fatalf("got %+v, want 720 for a slow start", ch)
		}
		// Half a second of media in 4 s is 0.75 Mbps: straight to 360p.
		_, ch = NextStep(s, beat(9, "buffering", 0, 1))
		if ch == nil || ch.To != "360" || !strings.Contains(ch.Reason, "0.8 Mbps") {
			t.Fatalf("got %+v, want 360 for a slow start at 0.75 Mbps", ch)
		}
	})

	t.Run("a 6 Mbps 1080p file goes to 720p, not 1080p at 8", func(t *testing.T) {
		s := start
		s.Estimate = 5_000_000
		_, ch := s.stepDown(beat(30, "buffering", 20, 0), "stalled")
		if ch == nil || ch.To != "720" {
			t.Fatalf("got %+v", ch)
		}
	})

	t.Run("no estimate is one step", func(t *testing.T) {
		s := AutoState{}.NewStream("original", 40_000_000, autoT0)
		_, ch := s.stepDown(beat(30, "buffering", 20, 0), "stalled")
		if ch == nil || ch.To != "2160" {
			t.Fatalf("got %+v", ch)
		}
	})

	t.Run("paused never steps", func(t *testing.T) {
		s, _ := NextStep(start, beat(2, "playing", 0, 3))
		for i := 1; i <= 10; i++ {
			var ch *StepChange
			if s, ch = NextStep(s, beat(2+i*30, "paused", 1, 0)); ch != nil {
				t.Fatalf("stepped while paused: %+v", ch)
			}
		}
	})

	t.Run("a seek is not a stall", func(t *testing.T) {
		s, _ := NextStep(start, beat(2, "playing", 0, 30))
		s, _ = NextStep(s, beat(20, "playing", 18, 30))
		s, _ = NextStep(s, beat(25, "playing", 23, 30))
		s, ch := NextStep(s, beat(30, "buffering", 600, 0))
		if ch != nil || !s.openStall.IsZero() {
			t.Fatalf("a seek counted as a stall: %+v", ch)
		}
	})

	t.Run("a reported stall that clears in time", func(t *testing.T) {
		s, _ := NextStep(start, beat(2, "playing", 0, 30))
		in := beat(30, "buffering", 28, 0)
		in.Stalls = 1
		s, ch := NextStep(s, in)
		if ch != nil {
			t.Fatalf("stepped on the first short stall: %+v", ch)
		}
		in = beat(33, "playing", 28.5, 3)
		in.Stalls = 1
		if _, ch = NextStep(s, in); ch != nil {
			t.Fatalf("stepped after the stall cleared: %+v", ch)
		}
	})

	t.Run("a second stall soon after", func(t *testing.T) {
		s, _ := NextStep(start, beat(2, "playing", 0, 30))
		in := beat(30, "buffering", 28, 0)
		in.Stalls = 1
		s, _ = NextStep(s, in)
		in = beat(35, "playing", 30, 3)
		in.Stalls = 1
		s, _ = NextStep(s, in)
		in = beat(80, "buffering", 72, 0)
		in.Stalls = 2
		if _, ch := NextStep(s, in); ch == nil {
			t.Fatal("no step down on the second stall within 2 minutes")
		}
	})

	t.Run("the transcoder behind real time", func(t *testing.T) {
		s := AutoState{}.NewStream("1080", 8_192_000, autoT0)
		s.Estimate = 50_000_000
		s, _ = NextStep(s, beat(2, "playing", 0, 3))
		in := beat(40, "buffering", 38, 0)
		in.Stalls, in.TranscodeSpeed = 1, 0.7
		s, _ = NextStep(s, in)
		in = beat(45, "buffering", 38, 0)
		in.Stalls, in.TranscodeSpeed = 1, 0.7
		s, ch := NextStep(s, in)
		if ch == nil || ch.To != "720" || !ch.Server || !s.Server || !strings.Contains(ch.Reason, "0.7x") {
			t.Fatalf("got %+v", ch)
		}
	})

	t.Run("the buffer shrinking to the end of the file", func(t *testing.T) {
		// A 22-minute episode: 20 s from the end the player has the rest,
		// and its buffer falls by 5 s every beat until it ends.
		tail := func(duration int64) (AutoState, *StepChange) {
			s, _ := NextStep(start, beat(2, "playing", 1250, 30))
			for i, pos := range []float64{1295, 1300, 1305, 1310, 1315} {
				in := beat(50+i*5, "playing", pos, 1320-pos)
				in.DeliveredBps, in.DurationMs = 200_000, duration
				var ch *StepChange
				if s, ch = NextStep(s, in); ch != nil {
					return s, ch
				}
			}
			return s, nil
		}
		s, ch := tail(1_320_000)
		if ch != nil || s.Estimate != 0 {
			t.Fatalf("got %+v and an estimate of %s at the end of the file", ch, mbps(s.Estimate))
		}
		// A player 3 s short of the probe's length still holds the rest.
		if s, ch = tail(1_323_000); ch != nil || s.Estimate != 0 {
			t.Fatalf("got %+v and an estimate of %s 3 s short of the end", ch, mbps(s.Estimate))
		}
		// The same buffer with more of the file to come is a drain.
		if _, ch = tail(2_640_000); ch == nil || !strings.Contains(ch.Reason, "draining") {
			t.Fatalf("got %+v, want a drain mid-file", ch)
		}
	})

	t.Run("a stall in the last seconds", func(t *testing.T) {
		s, _ := NextStep(start, beat(2, "playing", 1200, 30))
		in := beat(80, "buffering", 1278, 0)
		in.Stalls, in.DurationMs = 1, 1_320_000
		s, _ = NextStep(s, in)
		in = beat(85, "playing", 1280, 30)
		in.Stalls, in.DurationMs = 1, 1_320_000
		s, _ = NextStep(s, in)
		in = beat(120, "buffering", 1317, 0)
		in.Stalls, in.DurationMs = 2, 1_320_000
		if _, ch := NextStep(s, in); ch != nil {
			t.Fatalf("stepped down 3 s from the end: %+v", ch)
		}
	})

	t.Run("the floor", func(t *testing.T) {
		s := AutoState{}.NewStream("360", 1_192_000, autoT0)
		s, _ = NextStep(s, beat(2, "playing", 0, 3))
		in := beat(40, "buffering", 38, 0)
		in.Stalls = 1
		s, _ = NextStep(s, in)
		in = beat(45, "buffering", 38, 0)
		in.Stalls = 1
		s, ch := NextStep(s, in)
		if ch != nil || !s.Floor {
			t.Fatalf("got %+v, floor %v", ch, s.Floor)
		}
	})
}

// simPlay plays a file through a simulated player and link, beating every
// 5 s, and follows every step change the way a client would: a new stream
// at the same position with an empty buffer.
type simPlay struct {
	linkBps func(sec int) int64
	fileBps int64
	reports bool    // the player reports throughput and stalls
	target  float64 // seconds of buffer the player keeps
	seconds int
	// durationS is where the file ends, 0 for a file longer than the run:
	// the player buffers nothing past it, and the play stops there.
	durationS float64
	start     string
	switches  []*StepChange
	// stalled lists the seconds the player was stalled, after its first frame.
	stalled []int
	final   AutoState
	// beforeEnd is the estimate 40 s before the end of the file.
	beforeEnd int64
	log       []string
}

func streamBpsFor(step string, fileBps int64) int64 {
	if st := StepByID(step); st.MaxBitrate > 0 && st.MaxBitrate < fileBps {
		return st.MaxBitrate + transcodeAudioBitrate
	}
	return fileBps
}

func (sp *simPlay) run() {
	if sp.target == 0 {
		sp.target = 30
	}
	if sp.start == "" {
		sp.start = "original"
	}
	stream := streamBpsFor(sp.start, sp.fileBps)
	state := AutoState{}.NewStream(sp.start, stream, autoT0)
	var pos, buf float64
	playing, started, stalls := false, false, 0
	delivered := make([]int64, 10)
	for sec := 1; sec <= sp.seconds; sec++ {
		link := sp.linkBps(sec)
		got := int64(0)
		room := sp.target - buf
		if sp.durationS > 0 {
			room = min(room, sp.durationS-pos-buf)
		}
		if room > 0 {
			add := min(float64(link)/float64(stream), room)
			buf += add
			got = int64(add * float64(stream))
		}
		delivered[sec%10] = got
		if playing {
			pos++
			buf--
			if sp.durationS > 0 && pos >= sp.durationS {
				break
			}
			if buf <= 0 {
				buf, playing = 0, false
				stalls++
			}
		} else if buf >= 2.5 {
			playing, started = true, true
		}
		if started && !playing {
			sp.stalled = append(sp.stalled, sec)
		}
		if sec%5 != 0 {
			continue
		}
		in := AutoSample{At: autoT0.Add(time.Duration(sec) * time.Second), State: "buffering", PositionMs: int64(pos * 1000), BufferedMs: int64(buf * 1000),
			DurationMs: int64(sp.durationS * 1000), Stalls: -1}
		if playing {
			in.State = "playing"
		}
		var sum int64
		for _, d := range delivered {
			sum += d
		}
		in.DeliveredBps = sum / 10
		if sp.reports {
			in.ThroughputBps, in.Stalls = link, stalls
		}
		var ch *StepChange
		state, ch = NextStep(state, in)
		if sp.durationS > 0 && sp.durationS-pos > 40 {
			sp.beforeEnd = state.Estimate
		}
		if ch != nil {
			sp.log = append(sp.log, fmt.Sprintf("%ds %s→%s (%s)", sec, ch.From, ch.To, ch.Reason))
			sp.switches = append(sp.switches, ch)
			stream = streamBpsFor(ch.To, sp.fileBps)
			state = state.NewStream(ch.To, stream, in.At)
			buf, playing, started, stalls = 0, false, false, 0
		}
	}
	sp.final = state
	sp.log = append(sp.log, fmt.Sprintf("stalled %d s, estimate %s, proven %s", len(sp.stalled), mbps(state.Estimate), mbps(state.Proven)))
}

func constant(bps int64) func(int) int64 { return func(int) int64 { return bps } }

func stalledAfter(stalled []int, sec int) int {
	n := 0
	for _, s := range stalled {
		if s > sec {
			n++
		}
	}
	return n
}

func TestNextStepSimulated(t *testing.T) {
	t.Parallel()
	for _, reports := range []bool{true, false} {
		name := map[bool]string{true: "reporting", false: "server only"}[reports]
		t.Run(name, func(t *testing.T) {
			t.Run("a fast link keeps the original", func(t *testing.T) {
				sp := &simPlay{linkBps: constant(80_000_000), fileBps: 40_000_000, reports: reports, seconds: 600}
				sp.run()
				t.Log(strings.Join(sp.log, "; "))
				if len(sp.switches) != 0 || len(sp.stalled) != 0 {
					t.Fatalf("switches %+v, stalled %d s", sp.switches, len(sp.stalled))
				}
				if sp.final.Proven != 40_000_000 {
					t.Errorf("proven %d, want the file's bitrate", sp.final.Proven)
				}
			})
			t.Run("6 Mbps for a 4K remux settles and holds", func(t *testing.T) {
				sp := &simPlay{linkBps: constant(6_000_000), fileBps: 40_000_000, reports: reports, seconds: 600}
				sp.run()
				t.Log(strings.Join(sp.log, "; "))
				if len(sp.switches) == 0 || len(sp.switches) > 3 {
					t.Fatalf("switches %+v", sp.switches)
				}
				if last := sp.switches[len(sp.switches)-1].To; last != "720" && last != "480" {
					t.Errorf("settled at %s", last)
				}
				if n := stalledAfter(sp.stalled, 120); n != 0 {
					t.Errorf("still stalled %d s after the first two minutes", n)
				}
			})
			t.Run("the link drops mid-play", func(t *testing.T) {
				link := func(sec int) int64 {
					if sec < 180 {
						return 50_000_000
					}
					return 6_000_000
				}
				sp := &simPlay{linkBps: link, fileBps: 15_000_000, reports: reports, seconds: 600}
				sp.run()
				t.Log(strings.Join(sp.log, "; "))
				if len(sp.switches) != 1 || sp.switches[0].To != "720" {
					t.Fatalf("switches %+v", sp.switches)
				}
				if len(sp.stalled) != 0 {
					t.Errorf("stalled %d s", len(sp.stalled))
				}
				if sp.final.Proven > 4_192_000 {
					t.Errorf("proven %d still carries the original after the drop", sp.final.Proven)
				}
			})
			t.Run("1.5 Mbps reaches 360p", func(t *testing.T) {
				sp := &simPlay{linkBps: constant(1_500_000), fileBps: 10_000_000, reports: reports, seconds: 600}
				sp.run()
				t.Log(strings.Join(sp.log, "; "))
				if len(sp.switches) == 0 || sp.switches[len(sp.switches)-1].To != "360" {
					t.Fatalf("switches %+v", sp.switches)
				}
				if n := stalledAfter(sp.stalled, 180); n != 0 {
					t.Errorf("still stalled %d s after three minutes", n)
				}
			})
			t.Run("the end of the file is not a drain", func(t *testing.T) {
				// The player holds the rest of the file, so its buffer
				// shrinks to nothing over the last 20 s whatever the link.
				for _, link := range []int64{80_000_000, 12_000_000} {
					sp := &simPlay{linkBps: constant(link), fileBps: 8_000_000, reports: reports, durationS: 1320, seconds: 1400}
					sp.run()
					t.Log(strings.Join(sp.log, "; "))
					if len(sp.switches) != 0 || len(sp.stalled) != 0 {
						t.Fatalf("%s link: switches %+v, stalled %d s", mbps(link), sp.switches, len(sp.stalled))
					}
					if sp.final.Estimate < sp.beforeEnd {
						t.Errorf("%s link: the estimate fell from %s to %s at the end", mbps(link), mbps(sp.beforeEnd), mbps(sp.final.Estimate))
					}
				}
			})
			t.Run("too slow for the floor", func(t *testing.T) {
				sp := &simPlay{linkBps: constant(700_000), fileBps: 10_000_000, reports: reports, seconds: 900}
				sp.run()
				t.Log(strings.Join(sp.log, "; "))
				if !sp.final.Floor || sp.final.Step != "360" {
					t.Fatalf("final %s, floor %v, switches %+v", sp.final.Step, sp.final.Floor, sp.switches)
				}
			})
		})
	}
}

func TestDecisionBitrateUnknown(t *testing.T) {
	t.Parallel()
	info := &probe.MediaInfo{Video: []probe.VideoStream{{Codec: "h264", Width: 1920, Height: 1080}}, Audio: []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2}}}
	if d := Decide(info, ".mp4", Request{Profile: webProfile()}, nil, Capabilities{}); d.Bitrate != 0 {
		t.Errorf("bitrate %d for a file without one", d.Bitrate)
	}
}
