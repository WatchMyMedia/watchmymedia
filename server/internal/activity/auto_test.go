package activity

import (
	"context"
	"errors"
	"net/netip"
	"testing"
	"time"
)

// beginAuto starts a play from a remote address at the quality given, as
// the playback decision would: quality auto at a step, or a fixed one.
func beginAuto(t *testing.T, tr *Tracker, quality, step string, bitrate int64) string {
	t.Helper()
	id, err := tr.Begin(context.Background(), Start{
		UserID: 1, UserName: "ann", RecordHistory: true, Device: DeviceFrom("phone-1", "Pixel", "", "android", "", ""),
		IP: netip.MustParseAddr("203.0.113.9"), Item: movie(7, "Heat"), DurationMs: (2 * time.Hour).Milliseconds(),
		Stream: Stream{Mode: "direct", Quality: quality, Step: step, StepReason: "no measurement yet", Stream: MediaInfo{Bitrate: bitrate}},
	})
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestAutomaticStepsDown(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := context.Background()
	id := beginAuto(t, tr, "auto", "original", 40_000_000)
	beat := func(state string, posMs, bufMs int64) *Directive {
		t.Helper()
		zero := 0
		d, err := tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: posMs, State: state, BufferedMs: bufMs, ThroughputBps: 6_000_000, Stalls: zero})
		if err != nil {
			t.Fatal(err)
		}
		return d
	}

	// Still loading 10 s after the decision: down to the step 6 Mbps carries.
	c.add(5 * time.Second)
	if d := beat(StateBuffering, 0, 500); d != nil {
		t.Fatalf("directive after 5 s: %+v", d)
	}
	c.add(5 * time.Second)
	d := beat(StateBuffering, 0, 800)
	if d == nil || d.Action != "quality" || d.Quality != "720" || d.Message == "" {
		t.Fatalf("got %+v, want a step down to 720", d)
	}
	h := tr.AutoHint(id, 1, 0, "phone-1", netip.MustParseAddr("203.0.113.9"))
	if h.Step != "720" || h.Reason == "" {
		t.Fatalf("hint before the reload: %+v", h)
	}

	// The step is sent again only if the player has not acted on it.
	c.add(5 * time.Second)
	if d := beat(StateBuffering, 0, 900); d != nil {
		t.Fatalf("sent again after 5 s: %+v", d)
	}
	c.add(10 * time.Second)
	if d := beat(StateBuffering, 0, 900); d == nil || d.Quality != "720" {
		t.Fatalf("not sent again after 15 s: %+v", d)
	}

	// The player reloads at 720p.
	if err := tr.Continue(ctx, id, 1, 0, Stream{Mode: "transcode", Quality: "auto", Step: "720", StepReason: d.Message, Stream: MediaInfo{Bitrate: 4_192_000}}, (2 * time.Hour).Milliseconds(), 0); err != nil {
		t.Fatal(err)
	}
	for i := range 6 {
		c.add(5 * time.Second)
		if d := beat(StatePlaying, int64(i*5000), 20_000); d != nil {
			t.Fatalf("directive at 720p: %+v", d)
		}
	}
	plays, _ := tr.Live(Viewer{Admin: true})
	if len(plays) != 1 {
		t.Fatalf("%d plays", len(plays))
	}
	a := plays[0].Auto
	if !a.On || a.Step != "720" || len(a.Switches) != 1 || a.Switches[0].From != "original" || a.Switches[0].To != "720" || a.Estimate != 6_000_000 {
		t.Fatalf("live auto: %+v", a)
	}
	if plays[0].Throughput != 6_000_000 || plays[0].Stream.Step != "720" {
		t.Fatalf("live play: throughput %d, step %q", plays[0].Throughput, plays[0].Stream.Step)
	}

	// The device's next play from the same address starts from what this one
	// measured; from another address it knows nothing.
	if h := tr.AutoHint("", 1, 9, "phone-1", netip.MustParseAddr("203.0.113.9")); h.Step != "" || h.Estimate != 6_000_000 {
		t.Fatalf("next play's hint: %+v", h)
	}
	if h := tr.AutoHint("", 1, 9, "phone-1", netip.MustParseAddr("198.51.100.4")); h != (AutoHint{}) {
		t.Fatalf("another address: %+v", h)
	}

	if _, err := tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: 30_000, State: StateStopped}); err != nil {
		t.Fatal(err)
	}
	r, _ := readRow(t, conn, id)
	group, err := tr.Group(ctx, r.groupID, 0)
	if err != nil || len(group) != 1 || group[0].Detail == nil || group[0].Detail.Auto == nil {
		t.Fatalf("history: %+v, %v", group, err)
	}
	if got := group[0].Detail.Auto; !got.On || got.Step != "720" || len(got.Switches) != 1 || group[0].Detail.Stream.Step != "720" {
		t.Fatalf("history auto: %+v", got)
	}

	c.add(linkKeep)
	tr.reap(ctx)
	if h := tr.AutoHint("", 1, 9, "phone-1", netip.MustParseAddr("203.0.113.9")); h != (AutoHint{}) {
		t.Fatalf("a link measured 6 hours ago: %+v", h)
	}
}

func TestNegotiatedRecoveryGenerationAndFailure(t *testing.T) {
	tr, clock, _ := newTracker(t, noMin())
	ctx := context.Background()
	id, err := tr.Begin(ctx, Start{UserID: 1, UserName: "fixture viewer", FileID: 7,
		Device: DeviceFrom("phone-1", "Fixture phone", "", "android", "", ""), IP: netip.MustParseAddr("203.0.113.9"),
		Item: movie(7, "Fixture film"), DurationMs: 3_600_000,
		Stream: Stream{Mode: "transcode", Quality: "auto", Step: "720", AutoRecovery: true, OriginalBps: 6_000_000,
			Stream: MediaInfo{Bitrate: 4_192_000}}})
	if err != nil {
		t.Fatal(err)
	}
	zero := int64(0)
	var dir *Directive
	for sec := 5; sec <= 240; sec += 5 {
		clock.add(5 * time.Second)
		dir, err = tr.Heartbeat(ctx, id, 1, 7, Report{PositionMs: int64(sec * 1000), State: StatePlaying,
			BufferedMs: 30_000, ThroughputBps: 12_000_000, ThroughputAgeMs: &zero, Stalls: 0})
		if err != nil {
			t.Fatal(err)
		}
		if sec < 240 && dir != nil {
			t.Fatalf("early directive at %d: %+v", sec, dir)
		}
	}
	if dir == nil || dir.Quality != "original" || dir.Generation != 1 {
		t.Fatalf("recovery: %+v", dir)
	}
	if tr.ValidateSwitch(id, 2, 7, 1) || tr.ValidateSwitch(id, 1, 7, 2) || !tr.ValidateSwitch(id, 1, 7, 1) {
		t.Fatal("generation accepted for another viewer or stale directive")
	}
	ip := netip.MustParseAddr("203.0.113.9")
	if h := tr.AutoHint(id, 1, 7, "phone-1", ip); h.Step != "720" {
		t.Fatalf("unrelated request changed step: %+v", h)
	}
	if h := tr.AutoHintForSwitch(id, 1, 7, "phone-1", ip, 1); h.Step != "original" {
		t.Fatalf("switch did not select original: %+v", h)
	}
	next := Stream{Mode: "direct", Quality: "auto", Step: "original", AutoRecovery: true, OriginalBps: 6_000_000, Stream: MediaInfo{Bitrate: 6_000_000}}
	if err := tr.ContinueWithSwitch(ctx, id, 1, 7, next, 3_600_000, 0, 2); !errors.Is(err, ErrStaleSwitch) {
		t.Fatalf("stale continue: %v", err)
	}
	if err := tr.ContinueWithSwitch(ctx, id, 1, 7, next, 3_600_000, 0, 1); err != nil {
		t.Fatal(err)
	}
	if tr.ValidateSwitch(id, 1, 7, 1) {
		t.Fatal("accepted duplicate generation")
	}
	if _, err := tr.Heartbeat(ctx, id, 1, 7, Report{PositionMs: 240_000, State: StatePlaying, SwitchFailedGeneration: 1}); err != nil {
		t.Fatal(err)
	}
	plays, _ := tr.Live(Viewer{Admin: true})
	if len(plays) != 1 || plays[0].Stream.Step != "720" || plays[0].Auto.Switches[0].Outcome != "failed" {
		t.Fatalf("failed switch displaced old stream: %+v", plays)
	}
	for sec := 245; sec <= 300; sec += 5 {
		clock.add(5 * time.Second)
		dir, err = tr.Heartbeat(ctx, id, 1, 7, Report{PositionMs: int64(sec * 1000), State: StatePlaying,
			BufferedMs: 30_000, ThroughputBps: 12_000_000, ThroughputAgeMs: &zero, Stalls: 0})
		if err != nil || dir != nil {
			t.Fatalf("retried failed rung after %d seconds: %+v, %v", sec, dir, err)
		}
	}
}

func TestNegotiatedRecoveryWaitsForCapacity(t *testing.T) {
	tr, clock, _ := newTracker(t, noMin())
	checks := 0
	tr.upgradeReady = func(_ string, remote bool, bitrate int64) bool {
		checks++
		if !remote || bitrate != 6_000_000 {
			t.Fatalf("admission saw remote=%v bitrate=%d", remote, bitrate)
		}
		return false
	}
	id, err := tr.Begin(context.Background(), Start{UserID: 1, FileID: 7, IP: netip.MustParseAddr("203.0.113.9"),
		Stream: Stream{Mode: "transcode", Quality: "auto", Step: "720", AutoRecovery: true, OriginalBps: 6_000_000,
			Stream: MediaInfo{Bitrate: 4_192_000}}, DurationMs: 3_600_000})
	if err != nil {
		t.Fatal(err)
	}
	zero := int64(0)
	for sec := 5; sec <= 300; sec += 5 {
		clock.add(5 * time.Second)
		dir, err := tr.Heartbeat(context.Background(), id, 1, 7, Report{PositionMs: int64(sec * 1000), State: StatePlaying,
			BufferedMs: 30_000, ThroughputBps: 12_000_000, ThroughputAgeMs: &zero, Stalls: 0})
		if err != nil || dir != nil {
			t.Fatalf("resource-blocked upgrade at %d: %+v, %v", sec, dir, err)
		}
	}
	if checks == 0 {
		t.Fatal("capacity was never checked")
	}
}

func TestNegotiatedRecoverySpacesConcurrentUpgrades(t *testing.T) {
	tr, clock, _ := newTracker(t, noMin())
	ids := make([]string, 2)
	for i := range ids {
		id, err := tr.Begin(context.Background(), Start{UserID: int64(i + 1), FileID: int64(i + 7),
			Stream: Stream{Mode: "transcode", Quality: "auto", Step: "720", AutoRecovery: true,
				OriginalBps: 6_000_000, Stream: MediaInfo{Bitrate: 4_192_000}}, DurationMs: 3_600_000})
		if err != nil {
			t.Fatal(err)
		}
		ids[i] = id
	}
	zero := int64(0)
	for sec := 5; sec <= 255; sec += 5 {
		clock.add(5 * time.Second)
		for i, id := range ids {
			dir, err := tr.Heartbeat(context.Background(), id, int64(i+1), int64(i+7), Report{
				PositionMs: int64(sec * 1000), State: StatePlaying, BufferedMs: 30_000,
				ThroughputBps: 12_000_000, ThroughputAgeMs: &zero, Stalls: 0})
			if err != nil {
				t.Fatal(err)
			}
			if i == 0 && sec == 240 && (dir == nil || dir.Quality != "original") {
				t.Fatalf("first upgrade at %ds: %+v", sec, dir)
			}
			if i == 1 && sec < 255 && dir != nil {
				t.Fatalf("second upgrade proposed too early at %ds: %+v", sec, dir)
			}
			if i == 1 && sec == 255 && (dir == nil || dir.Quality != "original") {
				t.Fatalf("second upgrade not proposed after spacing at %ds: %+v", sec, dir)
			}
		}
	}
}

func TestAutomaticShadow(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := context.Background()
	id := beginAuto(t, tr, "original", "", 40_000_000)
	stalls := 0
	beat := func(state string, posMs int64) *Directive {
		t.Helper()
		d, err := tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: posMs, State: state, Stalls: stalls, ThroughputBps: 6_000_000})
		if err != nil {
			t.Fatal(err)
		}
		return d
	}
	c.add(2 * time.Second)
	beat(StatePlaying, 0)
	c.add(30 * time.Second)
	stalls = 1
	// A fixed quality is never told to change, but Now playing shows what
	// Automatic would have done.
	if d := beat(StateBuffering, 30_000); d != nil {
		t.Fatalf("directive for a fixed quality: %+v", d)
	}
	c.add(5 * time.Second)
	stalls = 2
	beat(StateBuffering, 30_000)
	plays, _ := tr.Live(Viewer{Admin: true})
	a := plays[0].Auto
	if a.On || a.Step != "original" || len(a.Switches) != 1 || a.Switches[0].To != "720" {
		t.Fatalf("shadow: %+v", a)
	}
	if plays[0].Stalls != 2 {
		t.Errorf("stalls %d", plays[0].Stalls)
	}
	if _, err := tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: 30_000, State: StateStopped}); err != nil {
		t.Fatal(err)
	}
	r, _ := readRow(t, conn, id)
	group, _ := tr.Group(ctx, r.groupID, 0)
	if len(group) != 1 || group[0].Detail.Auto == nil || group[0].Detail.Auto.On || len(group[0].Detail.Auto.Switches) != 1 {
		t.Fatalf("history shadow: %+v", group[0].Detail)
	}
}

// TestAutomaticEndOfFile plays the last minute of a film at Automatic, as a
// TV direct playing it reports: the buffer holds the rest of the file and
// shrinks to nothing. That is no reason to step down, and history keeps the
// direct play.
func TestAutomaticEndOfFile(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := context.Background()
	id := beginAuto(t, tr, "auto", "original", 8_000_000)
	end := (2 * time.Hour).Milliseconds()
	beat := func(state string, posMs int64) *Directive {
		t.Helper()
		d, err := tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: posMs, State: state, BufferedMs: min(30_000, end-posMs), Stalls: 0})
		if err != nil {
			t.Fatal(err)
		}
		return d
	}
	c.add(2 * time.Second)
	beat(StatePlaying, end-60_000)
	for pos := end - 55_000; pos < end; pos += 5_000 {
		c.add(5 * time.Second)
		if d := beat(StatePlaying, pos); d != nil {
			t.Fatalf("directive %s from the end: %+v", time.Duration(end-pos)*time.Millisecond, d)
		}
	}
	if _, err := tr.Heartbeat(ctx, id, 1, 0, Report{PositionMs: end, State: StateStopped}); err != nil {
		t.Fatal(err)
	}
	r, _ := readRow(t, conn, id)
	group, err := tr.Group(ctx, r.groupID, 0)
	if err != nil || len(group) != 1 || group[0].Detail == nil || group[0].Detail.Auto == nil {
		t.Fatalf("history: %+v, %v", group, err)
	}
	if got := group[0].Detail; got.Stream.Mode != "direct" || got.Auto.Step != "original" || len(got.Auto.Switches) != 0 {
		t.Fatalf("history: %s at %s, switches %+v", got.Stream.Mode, got.Auto.Step, got.Auto.Switches)
	}
}
