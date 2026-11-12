package activity

import (
	"net/netip"
	"time"

	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
)

// Automatic quality on the tracker (PLAN-AUTO-QUALITY.md §4). Every play
// runs the policy at its heartbeats: for real when the viewer picked
// Automatic, and otherwise in shadow, to show what Automatic would have
// done. Each device's last measured link, per address, is kept for where its
// next play starts.

const (
	// linkKeep is how long a device's measured link counts for its next play.
	linkKeep = 6 * time.Hour
	// resendEvery repeats a step change the player has not acted on, in
	// case the reply that carried it was lost.
	resendEvery = 15 * time.Second
	// deliveredWindow is the seconds of sent bytes the policy averages.
	deliveredWindow = 10
	// Leave time for one admitted upgrade to start before proposing another.
	upgradeSpacing = 15 * time.Second
)

// autoPlay is a play's Automatic quality.
type autoPlay struct {
	on       bool
	recovery bool
	state    playback.AutoState
	reason   string // why the stream is at its step
	switches []AutoSwitch
	// would is set once the shadow has recorded a step down for the
	// current stream; it records one per stream.
	would bool
	// pending is a step change sent and not yet followed by a decision.
	pending          *Directive
	pendingAt        time.Time
	pendingSince     time.Time
	pendingUp        bool
	generation       uint64
	activeGeneration uint64
	previous         *Stream

	throughput             int64
	stalls, stallsBefore   int
	stallMs, stallMsBefore int64
	startupMs              int64
}

// deviceLink is a device's link as last measured from one address.
type deviceLink struct {
	estimate, proven int64
	at               time.Time
}

func linkKey(device string, ip netip.Addr) string {
	return device + "|" + ip.Unmap().String()
}

// link returns a device's measured link from an address, if recent. t.mu is held.
func (t *Tracker) link(device string, ip netip.Addr, now time.Time) deviceLink {
	if l, ok := t.links[linkKey(device, ip)]; ok && now.Sub(l.at) < linkKeep {
		return l
	}
	return deviceLink{}
}

// autoStep is the step the play's stream is at: the one decided for
// Automatic, else the fixed quality picked.
func (s Stream) autoStep() string {
	if s.Quality == playback.QualityAuto && s.Step != "" {
		return s.Step
	}
	return playback.StepByID(s.Quality).ID
}

// startAuto sets up a new play from its first decision and what is known of
// the device's link.
func (p *live) startAuto(now time.Time, l deviceLink) {
	p.auto = autoPlay{on: p.stream.Quality == playback.QualityAuto, recovery: p.stream.AutoRecovery, reason: p.stream.StepReason}
	p.auto.state = playback.AutoState{Estimate: l.estimate, Proven: l.proven, OriginalBps: p.stream.OriginalBps, PlayerABR: p.stream.Adaptive != nil}.NewStream(p.stream.autoStep(), p.stream.Stream.Bitrate, now)
}

// continueAuto takes a new decision in the play: a seek, a track or quality
// change, or the step change it was told to make.
func (p *live) continueAuto(now time.Time, old Stream, generation uint64) {
	a := &p.auto
	if generation == 0 && a.pending != nil && a.pending.Generation != 0 {
		a.markSwitch(a.pending.Generation, "canceled")
	}
	if generation == 0 && a.activeGeneration != 0 {
		a.markSwitch(a.activeGeneration, "canceled")
	}
	a.on = p.stream.Quality == playback.QualityAuto
	a.recovery = p.stream.AutoRecovery
	a.state.OriginalBps = p.stream.OriginalBps
	a.state.PlayerABR = p.stream.Adaptive != nil
	step := p.stream.autoStep()
	if step != a.state.Step {
		a.state.Floor = false
	}
	a.state = a.state.NewStream(step, p.stream.Stream.Bitrate, now)
	if p.stream.StepReason != "" {
		a.reason = p.stream.StepReason
	}
	a.stallsBefore, a.stallMsBefore = a.stallsBefore+a.stalls, a.stallMsBefore+a.stallMs
	a.stalls, a.stallMs = 0, 0
	a.pending, a.would = nil, false
	if generation != 0 {
		a.activeGeneration = generation
		a.previous = &old
	} else {
		a.activeGeneration = 0
		a.previous = nil
	}
}

// autoBeat runs the policy on a heartbeat. It returns the step change to
// send the player, if any, and whether Now playing should hear of it.
// t.mu is held.
func (t *Tracker) autoBeat(p *live, now time.Time, r Report, speed float64) (directive *Directive, changed bool) {
	a := &p.auto
	renditionChanged := p.stream.Adaptive.Accepts(r.RenditionHeight, r.RenditionBitrate) && (p.stream.Stream.Height != r.RenditionHeight || p.stream.Stream.Bitrate != r.RenditionBitrate)
	defer func() { changed = changed || renditionChanged }()
	if p.stream.Adaptive.Accepts(r.RenditionHeight, r.RenditionBitrate) {
		p.stream.Stream.Height, p.stream.Stream.Bitrate = r.RenditionHeight, r.RenditionBitrate
		a.state.StreamBps = r.RenditionBitrate
		for _, rung := range p.stream.Adaptive.Renditions {
			if rung.Height == r.RenditionHeight {
				p.stream.Stream.Width = rung.Width
			}
		}
	}
	if r.ThroughputBps > 0 {
		a.throughput = r.ThroughputBps
	}
	if r.Stalls >= 0 {
		a.stalls, a.stallMs = r.Stalls, r.StallMs
	}
	if r.StartupMs > 0 && a.startupMs == 0 {
		a.startupMs = r.StartupMs
	}
	if a.activeGeneration != 0 {
		return nil, false
	}
	if a.pending != nil {
		if a.recovery && a.pendingUp && now.Sub(a.pendingSince) >= 45*time.Second {
			a.state.MarkFailure(a.pending.Quality, now)
			a.markSwitch(a.pending.Generation, "failed")
			a.pending = nil
			return nil, true
		}
		if now.Sub(a.pendingAt) < resendEvery {
			return nil, false
		}
		a.pendingAt = now
		return a.pending, false
	}
	in := playback.AutoSample{
		At: now, State: p.state, PositionMs: p.pos, BufferedMs: r.BufferedMs, DurationMs: p.durationMs,
		ThroughputBps: r.ThroughputBps, ThroughputAgeMs: r.ThroughputAgeMs, ServerPaced: now.Before(p.pacedUntil), Stalls: r.Stalls, TranscodeSpeed: speed,
		DeliveredBps: p.rate.bitsPerSecond(now.Unix(), p.startedAt.Unix(), deliveredWindow),
	}
	before := a.state
	var ch *playback.StepChange
	if a.on && a.recovery {
		a.state, ch = playback.NextStepWithRecovery(a.state, in)
	} else {
		a.state, ch = playback.NextStep(a.state, in)
	}
	measured := a.state.Estimate != before.Estimate || a.state.Proven != before.Proven
	if measured && !a.state.Server && !now.Before(p.pacedUntil) && (a.state.Estimate > 0 || a.state.Proven > 0) {
		t.links[linkKey(p.device.Key, p.ip)] = deviceLink{estimate: a.state.Estimate, proven: a.state.Proven, at: now}
	}
	if ch == nil {
		return nil, a.state.Floor != before.Floor
	}
	if ch.NeedBps > 0 {
		if now.Before(t.nextUpgradeAt) || (t.upgradeReady != nil && !t.upgradeReady(p.id, p.network != netguard.NetLocal, ch.NeedBps)) {
			return nil, false
		}
		t.nextUpgradeAt = now.Add(upgradeSpacing)
	}
	sw := AutoSwitch{At: now, From: ch.From, To: ch.To, Reason: ch.Reason, Server: ch.Server}
	if !a.on {
		if a.would {
			return nil, false
		}
		a.would = true
		a.switches = append(a.switches, sw)
		return nil, true
	}
	if !a.recovery {
		a.reason = ch.Reason
	}
	a.pending, a.pendingAt = &Directive{Action: "quality", Quality: ch.To, Message: switchMessage(ch)}, now
	if a.recovery {
		a.generation++
		a.pending.Generation = a.generation
		sw.Generation, sw.Outcome = a.generation, "proposed"
	}
	a.pendingSince = now
	a.pendingUp = isHigher(ch.From, ch.To)
	a.switches = append(a.switches, sw)
	return a.pending, true
}

func isHigher(from, to string) bool {
	fromIndex, toIndex := -1, -1
	for i, rung := range playback.Ladder {
		if rung.ID == from {
			fromIndex = i
		}
		if rung.ID == to {
			toIndex = i
		}
	}
	return fromIndex >= 0 && toIndex >= 0 && toIndex < fromIndex
}

func (a *autoPlay) markSwitch(generation uint64, outcome string) {
	for i := len(a.switches) - 1; i >= 0; i-- {
		if a.switches[i].Generation == generation {
			a.switches[i].Outcome = outcome
			return
		}
	}
}

// switchMessage is what the player may show for a step change.
func switchMessage(ch *playback.StepChange) string {
	if ch.To == "original" {
		return "Connection recovered; playing the original file."
	}
	label := ch.To + "p"
	if ch.To == "2160" {
		label = "4K"
	}
	if ch.Server {
		return "Lowered to " + label + " so the server can keep up."
	}
	for i, rung := range playback.Ladder {
		if rung.ID == ch.From {
			for _, higher := range playback.Ladder[:i] {
				if higher.ID == ch.To {
					return "Raised to " + label + " for your connection."
				}
			}
		}
	}
	return "Lowered to " + label + " for your connection."
}

// AutoHint is what deciding an Automatic play needs to know.
type AutoHint struct {
	// Step is set for a play already at Automatic: the step it is at, or
	// the one it was just told to drop to, and why.
	Step, Reason string
	// Estimate and Proven are the link as last measured, by this play or
	// by this device from this address, in bits per second.
	Estimate, Proven int64
}

// AutoHint says where an Automatic decision should be: the running play's
// step when it continues one, else what is known of the device's link.
func (t *Tracker) AutoHint(playID string, userID, fileID int64, device string, ip netip.Addr) AutoHint {
	return t.AutoHintForSwitch(playID, userID, fileID, device, ip, 0)
}

func (t *Tracker) AutoHintForSwitch(playID string, userID, fileID int64, device string, ip netip.Addr, generation uint64) AutoHint {
	t.mu.Lock()
	defer t.mu.Unlock()
	if p, ok := t.plays[playID]; ok && p.userID == userID && p.fileID == fileID {
		a := p.auto
		h := AutoHint{Estimate: a.state.Estimate, Proven: a.state.Proven}
		if a.on {
			h.Step, h.Reason = a.state.Step, a.reason
			if a.pending != nil && (!a.recovery || generation == a.pending.Generation) {
				h.Step = a.pending.Quality
				h.Reason = a.pending.Message
			}
		}
		return h
	}
	l := t.link(device, ip, t.now())
	return AutoHint{Estimate: l.estimate, Proven: l.proven}
}

// ValidateSwitch rejects a stale, duplicate or foreign negotiated decision.
func (t *Tracker) ValidateSwitch(playID string, userID, fileID int64, generation uint64) bool {
	if generation == 0 {
		return true
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	p := t.plays[playID]
	return p != nil && p.userID == userID && p.fileID == fileID && p.auto.recovery &&
		p.auto.pending != nil && p.auto.pending.Generation == generation
}

// record is what history keeps: nothing for a fixed quality that
// Automatic would have left alone.
func (a *autoPlay) record() *PlayAuto {
	if !a.on && len(a.switches) == 0 {
		return nil
	}
	r := a.played()
	return &r
}

func (a *autoPlay) played() PlayAuto {
	return PlayAuto{On: a.on, Step: a.state.Step, Floor: a.state.Floor, Switches: append([]AutoSwitch{}, a.switches...)}
}

// pruneLinks forgets links measured too long ago. t.mu is held.
func (t *Tracker) pruneLinks(now time.Time) {
	for k, l := range t.links {
		if now.Sub(l.at) >= linkKeep {
			delete(t.links, k)
		}
	}
}

// transcodeSpeed is ffmpeg's speed for a play's session; 0 without one.
func (t *Tracker) transcodeSpeed(playID string) float64 {
	if t.sessions == nil {
		return 0
	}
	t.mu.Lock()
	sid := ""
	if p, ok := t.plays[playID]; ok {
		sid = p.stream.SessionID
	}
	t.mu.Unlock()
	if sid == "" {
		return 0
	}
	s, ok := t.sessions.Get(sid)
	if !ok {
		return 0
	}
	speed, _ := s.Progress()
	return speed
}
