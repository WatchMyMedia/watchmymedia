package playback

import (
	"fmt"
	"math"
	"strings"
	"time"
)

// Automatic quality (PLAN-AUTO-QUALITY.md): a play asks for quality "auto",
// the server picks the step it starts at, and heartbeats feed NextStep,
// which says when the connection cannot keep up and which step to drop to.
// Existing clients only step down within a play. A separately negotiated
// recovery policy may propose a higher step after fresh evidence.

// QualityAuto is the quality a player sends for Automatic.
const QualityAuto = "auto"

// Step is one rung of the quality ladder. The clients keep the same list.
type Step struct {
	ID         string
	MaxWidth   int
	MaxHeight  int
	MaxBitrate int64 // bits per second; 0 for the original
}

// Ladder is every step, best first.
var Ladder = []Step{
	{ID: "original"},
	{ID: "2160", MaxWidth: 3840, MaxHeight: 2160, MaxBitrate: 20_000_000},
	{ID: "1080", MaxWidth: 1920, MaxHeight: 1080, MaxBitrate: 8_000_000},
	{ID: "720", MaxWidth: 1280, MaxHeight: 720, MaxBitrate: 4_000_000},
	{ID: "480", MaxWidth: 854, MaxHeight: 480, MaxBitrate: 2_000_000},
	{ID: "360", MaxWidth: 640, MaxHeight: 360, MaxBitrate: 1_000_000},
}

// StepByID finds a step; unknown IDs are the original.
func StepByID(id string) Step {
	for _, s := range Ladder {
		if s.ID == id {
			return s
		}
	}
	return Ladder[0]
}

// Cap narrows a profile to the step, keeping the tighter of each limit, as
// the clients do for a fixed quality.
func (s Step) Cap(p Profile) Profile {
	tighter := func(a, b int64) int64 {
		if a <= 0 || (b > 0 && b < a) {
			return b
		}
		return a
	}
	p.MaxWidth = int(tighter(int64(p.MaxWidth), int64(s.MaxWidth)))
	p.MaxHeight = int(tighter(int64(p.MaxHeight), int64(s.MaxHeight)))
	p.MaxBitrate = tighter(p.MaxBitrate, s.MaxBitrate)
	return p
}

// The margins over an estimate a step needs. Original carries the file's
// average bitrate, and its peaks run well above that; a transcode is held
// to its cap with a 2x buffer.
const (
	originalMargin  = 1.4
	transcodeMargin = 1.25
)

func fits(s Step, estimate, fileBps int64) bool {
	if s.MaxBitrate == 0 {
		return fileBps > 0 && float64(estimate) >= originalMargin*float64(fileBps)
	}
	return float64(estimate) >= transcodeMargin*float64(s.MaxBitrate)
}

// StartStep picks where an Automatic play starts. Local plays and plays
// with nothing measured start at the original. Otherwise it is the highest
// step that the device's estimate carries, or that it has already played
// smoothly (proven, in bits per second), among the steps that would
// actually lower the bitrate.
func StartStep(local bool, estimate, proven, fileBps int64) (Step, string) {
	carries := func(s Step) bool {
		need := s.MaxBitrate
		if need == 0 {
			need = fileBps
		}
		return (proven > 0 && need <= proven) || fits(s, estimate, fileBps)
	}
	measured := "measured " + mbps(max(estimate, proven))
	switch {
	case local:
		return Ladder[0], "on the local network"
	case estimate <= 0 && proven <= 0:
		return Ladder[0], "no measurement yet"
	case fileBps <= 0 || carries(Ladder[0]):
		return Ladder[0], measured
	}
	for _, s := range Ladder[1:] {
		if s.MaxBitrate < fileBps && carries(s) {
			return s, measured
		}
	}
	return Ladder[len(Ladder)-1], measured
}

// AutoSample is a play at one heartbeat.
type AutoSample struct {
	At         time.Time
	State      string // playing, paused or buffering
	PositionMs int64
	BufferedMs int64
	// DurationMs is the file's length; 0 when it is not known.
	DurationMs int64
	// ThroughputBps is the player's own estimate of the link; 0 when the
	// platform has none.
	ThroughputBps int64
	// ThroughputAgeMs is supplied only by clients that opt in to recovery.
	// A missing age is not evidence for an upgrade.
	ThroughputAgeMs *int64
	ServerPaced     bool
	// Stalls counts rebuffers since the decision, as the player reports
	// them; -1 when it does not, and the policy infers them.
	Stalls int
	// DeliveredBps is what the server sent the play over the last 10 s.
	DeliveredBps int64
	// TranscodeSpeed is ffmpeg's speed for the play's session; 0 when there
	// is none or it has not reported.
	TranscodeSpeed float64
}

// AutoState is what the policy keeps for a play between heartbeats.
type AutoState struct {
	Step      string // the step the current stream was decided at
	StreamBps int64  // what the current stream needs
	DecidedAt time.Time
	// Estimate is the link speed smoothed over about 20 s; 0 is unknown.
	Estimate int64
	// Server is set when the last step down was the transcoder's doing.
	Server bool
	// Floor is set once the lowest step stalls too.
	Floor bool
	// Proven is the highest stream bitrate that played for autoProven
	// without a stall.
	Proven int64
	// OriginalBps is the source's total bitrate, including audio.
	OriginalBps int64
	PlayerABR   bool // player selects rungs; server may only propose Original

	firstFrame time.Time
	switched   time.Time
	stalls     int
	lastStall  time.Time
	openStall  time.Time
	draining   int
	prev       *AutoSample
	// latest is the newest link sample, which a sudden drop reaches before
	// the smoothed estimate does.
	latest        int64
	recoverySince time.Time
	failedAt      [6]time.Time
}

// StepChange is a step down for the player to make.
type StepChange struct {
	From, To string
	Reason   string
	NeedBps  int64 // total bitrate required by a proposed higher rung
	// Server says the transcoder, not the network, could not keep up.
	Server bool
}

// The policy's timings and thresholds (PLAN-AUTO-QUALITY.md §4.2).
const (
	autoSmoothing       = 20 * time.Second
	autoSlowStart       = 8 * time.Second
	autoSettle          = 10 * time.Second
	autoGap             = 30 * time.Second
	autoStallRepeat     = 2 * time.Minute
	autoStallLong       = 4 * time.Second
	autoDrainBuffer     = 20_000
	autoDrainFill       = 0.9
	autoDrainBeats      = 3
	autoShortBuffer     = 10_000
	autoDrainFresh      = 10_000
	autoProven          = 2 * time.Minute
	autoEndSlack        = 5_000
	autoRecoverEvidence = time.Minute
	autoRecoverGap      = 3 * time.Minute
	autoRecoverFailure  = 10 * time.Minute
	autoRecoverBuffer   = 20_000
	autoRecoverFresh    = 20_000
)

// holdsRest says the player has the rest of the file, give or take the
// frames by which a player's idea of the length and the probe's differ.
// From there its buffer can only shrink, by a second each second, whatever
// the link: that is the end of the file coming, not a drain, and it is not a
// measurement of the link. Without this every play that reached the end
// stepped down a few seconds before it, and saved a slow link for the next.
func (in AutoSample) holdsRest() bool {
	return in.DurationMs > 0 && in.PositionMs+in.BufferedMs >= in.DurationMs-autoEndSlack
}

// NewStream starts the state over for a new decision at step, which needs
// streamBps. The estimate, and when the play last stalled and switched,
// carry over.
func (s AutoState) NewStream(step string, streamBps int64, at time.Time) AutoState {
	if s.Step != "" && s.Step != step {
		s.switched = at
	}
	s.Step, s.StreamBps, s.DecidedAt = step, streamBps, at
	s.firstFrame, s.openStall, s.prev = time.Time{}, time.Time{}, nil
	s.stalls, s.draining = 0, 0
	s.recoverySince = time.Time{}
	return s
}

// NextStep takes a heartbeat and says whether to step down.
func NextStep(s AutoState, in AutoSample) (AutoState, *StepChange) {
	fill, seeked := s.observe(in)
	prevState := ""
	if s.prev != nil {
		prevState = s.prev.State
	}
	sample := in
	s.prev = &sample
	if seeked {
		s.openStall, s.draining = time.Time{}, 0
	}
	if in.State == "paused" {
		s.openStall, s.draining = time.Time{}, 0
		return s, nil
	}
	if s.firstFrame.IsZero() {
		if in.State == "playing" {
			s.firstFrame = in.At
			return s, nil
		}
		if in.At.Sub(s.DecidedAt) >= autoSlowStart {
			return s.stepDown(in, "slow to start")
		}
		return s, nil
	}

	// A stall: the player's count went up or, from players that do not
	// count, buffering after playing that no seek explains.
	stalled := false
	if in.Stalls >= 0 {
		stalled = in.Stalls > s.stalls
		s.stalls = in.Stalls
	} else {
		stalled = in.State == "buffering" && prevState == "playing" && !seeked
	}
	if in.holdsRest() {
		// With the rest of the file buffered, the link has nothing left to
		// fall behind on, and a new stream would only start for the last
		// few seconds.
		stalled, s.openStall = false, time.Time{}
	}
	settling := in.At.Sub(s.firstFrame) < autoSettle
	switch {
	case stalled && !settling:
		repeat := !s.lastStall.IsZero() && in.At.Sub(s.lastStall) < autoStallRepeat
		s.lastStall = in.At
		if repeat || (s.Estimate > 0 && s.Estimate < s.StreamBps) {
			return s.stepDown(in, "stalled")
		}
		if in.State == "buffering" {
			s.openStall = in.At
		}
		return s, nil
	case !s.openStall.IsZero() && in.State == "buffering":
		if in.At.Sub(s.openStall) >= autoStallLong {
			return s.stepDown(in, "stalled")
		}
		return s, nil
	}
	s.openStall = time.Time{}
	if !s.PlayerABR && in.State == "playing" && !stalled {
		since := s.firstFrame
		if s.lastStall.After(since) {
			since = s.lastStall
		}
		if in.At.Sub(since) >= autoProven {
			s.Proven = max(s.Proven, s.StreamBps)
		}
	}
	// A player with a recent, unpaced transfer far above this stream's need
	// may intentionally let its buffer fall before asking for another HLS
	// segment. That sawtooth is not evidence of a slower connection. A stale
	// sample, short buffer or slow encoder cannot excuse sustained draining.
	fastFreshTransfer := in.ThroughputAgeMs != nil && *in.ThroughputAgeMs <= autoDrainFresh &&
		!in.ServerPaced && s.StreamBps > 0 && in.BufferedMs >= autoShortBuffer &&
		float64(in.ThroughputBps) >= 1.6*float64(s.StreamBps) &&
		(in.TranscodeSpeed == 0 || in.TranscodeSpeed >= 1)

	switch {
	case in.holdsRest():
		s.draining = 0
	case in.State == "playing" && fill >= 0 && fill < autoDrainFill && in.BufferedMs < autoDrainBuffer && !fastFreshTransfer:
		s.draining++
	case fill >= 0:
		s.draining = 0
	}
	if s.draining >= autoDrainBeats && !settling && (s.switched.IsZero() || in.At.Sub(s.switched) >= autoGap) {
		return s.stepDown(in, "buffer draining")
	}
	return s, nil
}

// NextStepWithRecovery retains the existing step-down policy, then observes
// whether an opted-in play has enough fresh, unpaced transfer evidence to try
// one higher useful rung. Admission and switch acknowledgement are separate:
// a proposal must not stop a working stream.
func NextStepWithRecovery(s AutoState, in AutoSample) (AutoState, *StepChange) {
	return nextStepWithRecovery(s, in, autoRecoverBuffer)
}

// NextLiveStepWithRecovery accounts for the shorter buffer at a live edge.
// Two output segments (at least four seconds) must remain playable throughout
// the evidence window. Unknown segment duration keeps the file policy's more
// conservative requirement. This duration comes from the server's playlist,
// never from a client-supplied threshold.
func NextLiveStepWithRecovery(s AutoState, in AutoSample, segmentMs int64) (AutoState, *StepChange) {
	buffer := int64(autoRecoverBuffer)
	if segmentMs > 0 && segmentMs <= 60_000 {
		buffer = max(4_000, segmentMs*2)
	}
	return nextStepWithRecovery(s, in, buffer)
}

func nextStepWithRecovery(s AutoState, in AutoSample, bufferMs int64) (AutoState, *StepChange) {
	if in.ServerPaced || in.ThroughputAgeMs == nil || *in.ThroughputAgeMs > autoRecoverFresh {
		in.ThroughputBps = 0
	}
	s, down := NextStep(s, in)
	if down != nil {
		return s, down
	}
	if in.State != "playing" || in.holdsRest() || in.BufferedMs < bufferMs ||
		in.ThroughputBps <= 0 || in.TranscodeSpeed > 0 && in.TranscodeSpeed < 1 ||
		!s.openStall.IsZero() || !s.lastStall.IsZero() && in.At.Sub(s.lastStall) < autoRecoverGap {
		s.recoverySince = time.Time{}
		return s, nil
	}
	lastSwitch := s.switched
	if lastSwitch.IsZero() {
		lastSwitch = s.DecidedAt
	}
	if in.At.Sub(lastSwitch) < autoRecoverGap {
		s.recoverySince = time.Time{}
		return s, nil
	}
	to, need := s.nextHigher()
	if to.ID == "" || need <= 0 || float64(in.ThroughputBps) < 1.6*float64(need) {
		s.recoverySince = time.Time{}
		return s, nil
	}
	for i, rung := range Ladder {
		if rung.ID == to.ID && !s.failedAt[i].IsZero() && in.At.Sub(s.failedAt[i]) < autoRecoverFailure {
			s.recoverySince = time.Time{}
			return s, nil
		}
	}
	if s.recoverySince.IsZero() {
		s.recoverySince = in.At
		return s, nil
	}
	if in.At.Sub(s.recoverySince) < autoRecoverEvidence {
		return s, nil
	}
	// Keep the evidence until NewStream accepts the proposal. Shared upgrade
	// spacing or resource admission may ask this play to wait another heartbeat.
	return s, &StepChange{From: s.Step, To: to.ID, Reason: "connection recovered, measured " + mbps(in.ThroughputBps), NeedBps: need}
}

// nextHigher skips a transcode when the original source is already cheaper
// than that rung. For a transcode, include a conservative audio allowance.
func (s AutoState) nextHigher() (Step, int64) {
	if s.PlayerABR {
		if s.OriginalBps > 0 && s.Step != "original" {
			return Ladder[0], s.OriginalBps
		}
		return Step{}, 0
	}
	for i, rung := range Ladder {
		if rung.ID != s.Step || i == 0 {
			continue
		}
		candidate := Ladder[i-1]
		if s.OriginalBps > 0 && (candidate.ID == "original" || s.OriginalBps <= candidate.MaxBitrate) {
			return Ladder[0], s.OriginalBps
		}
		return candidate, candidate.MaxBitrate + 256_000
	}
	return Step{}, 0
}

// MarkFailure backs off a rung whose preparation or playback failed.
func (s *AutoState) MarkFailure(step string, at time.Time) {
	for i, rung := range Ladder {
		if rung.ID == step {
			s.failedAt[i] = at
			break
		}
	}
	s.recoverySince = time.Time{}
}

// observe works out the fill rate since the last heartbeat (-1 when there is
// none to compare) and folds a new link sample into the estimate.
func (s *AutoState) observe(in AutoSample) (fill float64, seeked bool) {
	fill = -1
	p := s.prev
	if p != nil {
		dt := in.At.Sub(p.At)
		moved := in.PositionMs - p.PositionMs
		if moved < 0 || time.Duration(moved)*time.Millisecond > 2*dt+5*time.Second {
			seeked = true
		} else if dt >= 2*time.Second && p.State != "paused" && in.State != "paused" {
			gained := float64(moved + in.BufferedMs - p.BufferedMs)
			fill = math.Max(gained, 0) / float64(dt.Milliseconds())
		}
	}
	// The player's own estimate is the link. Without one, the buffer says
	// something only while it is short: draining, what arrives is all the
	// link carries; growing, the link carries at least that much. A full
	// buffer only says the stream keeps up.
	var sample int64
	lower := false
	switch {
	case in.ThroughputBps > 0:
		sample = in.ThroughputBps
	case fill < 0 || in.BufferedMs >= autoDrainBuffer || in.holdsRest():
	case fill < 1 && in.BufferedMs < autoShortBuffer && in.DeliveredBps > 0:
		sample = in.DeliveredBps
	case fill < autoDrainFill:
		sample = int64(fill * float64(s.StreamBps))
	case fill > 1.1:
		sample, lower = int64(fill*float64(s.StreamBps)), true
	}
	switch {
	case sample <= 0:
		return fill, seeked
	case in.TranscodeSpeed > 0 && in.TranscodeSpeed < 1 && in.ThroughputBps == 0:
		// A transcode behind real time makes the fill rate the encoder's.
		return fill, seeked
	case lower && sample <= s.Estimate:
		return fill, seeked
	case !lower:
		s.latest = sample
	}
	switch {
	case s.Estimate <= 0 || p == nil:
		s.Estimate = sample
		return fill, seeked
	}
	a := 1 - math.Exp(-float64(in.At.Sub(p.At))/float64(autoSmoothing))
	s.Estimate += int64(a * float64(sample-s.Estimate))
	return fill, seeked
}

// stepDown drops to the highest step the estimate carries among those that
// lower the bitrate, or one step without an estimate. A transcoder behind
// real time is the server's problem: one step down, so it has less to do.
func (s AutoState) stepDown(in AutoSample, why string) (AutoState, *StepChange) {
	if s.PlayerABR {
		s.recoverySince = time.Time{}
		return s, nil
	}
	s.openStall, s.draining = time.Time{}, 0
	s.recoverySince = time.Time{}
	for i, step := range Ladder {
		if step.ID == s.Step {
			s.failedAt[i] = in.At
			break
		}
	}
	if s.Proven >= s.StreamBps {
		// What played smoothly before no longer does.
		s.Proven = 0
	}
	server := in.TranscodeSpeed > 0 && in.TranscodeSpeed < 1
	var candidates []Step
	for _, st := range Ladder[1:] {
		if s.StreamBps <= 0 || st.MaxBitrate < s.StreamBps {
			if cur := StepByID(s.Step); cur.MaxBitrate == 0 || st.MaxBitrate < cur.MaxBitrate {
				candidates = append(candidates, st)
			}
		}
	}
	if len(candidates) == 0 {
		s.Floor = true
		return s, nil
	}
	est := s.Estimate
	if s.latest > 0 && s.latest < est {
		est = s.latest
	}
	to := candidates[0]
	if est > 0 && !server {
		to = candidates[len(candidates)-1]
		for _, st := range candidates {
			if fits(st, est, 0) {
				to = st
				break
			}
		}
	}
	reasons := []string{why}
	switch {
	case server:
		reasons = append(reasons, fmt.Sprintf("the server transcodes at %.1fx", in.TranscodeSpeed))
	case est > 0:
		reasons = append(reasons, "measured "+mbps(est))
	}
	s.Server = server
	return s, &StepChange{From: s.Step, To: to.ID, Reason: strings.Join(reasons, ", "), Server: server}
}

// mbps formats bits per second for reasons: "3.1 Mbps".
func mbps(bps int64) string {
	return fmt.Sprintf("%.1f Mbps", float64(bps)/1e6)
}
