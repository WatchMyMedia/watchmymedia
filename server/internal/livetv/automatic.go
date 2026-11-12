package livetv

import (
	"context"
	"errors"
	"fmt"
	"math"
	"slices"
	"time"

	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/upload"
)

var ErrStaleLiveSwitch = errors.New("live quality switch generation is stale")

// LiveDirective names a prepared, separately authorized output. The viewer
// keeps its current URL until this generation plays its first frame, then
// acknowledges it on the logical session's heartbeat.
type LiveDirective struct {
	Adaptive   *playback.AdaptiveStream `json:"adaptive,omitempty" required:"false"`
	Generation uint64                   `json:"generation"`
	From       string                   `json:"from"`
	To         string                   `json:"to"`
	Reason     string                   `json:"reason"`
	URL        string                   `json:"url"`
	Mode       string                   `json:"mode"`
	ModeReason string                   `json:"modeReason"`
}

type LiveMeasurement struct {
	AdaptiveFailed         bool
	RenditionHeight        int
	RenditionBitrate       int64
	State                  string
	BufferedMs             int64
	ThroughputBps          int64
	ThroughputAgeMs        *int64
	Stalls                 *int
	SwitchGeneration       uint64
	SwitchFailedGeneration uint64
	ServerPaced            bool
	Remote                 bool // derived from the current request, never trusted from a client field
}

type liveAuto struct {
	adaptive       bool
	ladderDisabled bool
	initialBps     int64
	remote         bool
	proposals      chan playback.StepChange
	policy         playback.AutoState
	source         SourceInfo
	currentID      string
	pendingID      string
	pendingAt      time.Time
	pendingTo      string
	preparing      bool
	nextGen        uint64
	blockedTo      string
	blockedAt      time.Time
}

func autoActual(quality, mode string) string {
	if mode == "original" || mode == "remux" || mode == "audio" {
		return "original"
	}
	return quality
}

func autoNeed(step string, source SourceInfo) int64 {
	if step == "original" {
		return source.Bitrate
	}
	return playback.StepByID(step).MaxBitrate
}

func autoOutputQuality(step string) string {
	if step == "original" {
		return "1080"
	}
	return step
}

func liveDemand(quality string, profile *LiveProfile, source SourceInfo) int64 {
	bps := playback.StepByID(quality).MaxBitrate
	if source.Bitrate > 0 {
		plan := chooseLivePlan(profile, source, quality)
		if plan.Mode == "original" || plan.Mode == "remux" || plan.Mode == "audio" {
			bps = source.Bitrate
		}
	}
	bps += 500_000 // audio, HLS framing and broadcast variation
	return bps + bps/4
}

func liveOutputFresh(output *session) bool {
	if output != nil && output.output != nil {
		output = output.output.producer
	}
	return output != nil && time.Since(time.Unix(0, output.outputAt.Load())) <= 6*time.Second
}

func initialLiveQuality(bps int64, source SourceInfo) string {
	if bps <= 0 || source.Bitrate <= 0 {
		return "1080"
	}
	step, _ := playback.StartStep(false, bps, 0, source.Bitrate)
	switch step.ID {
	case "720", "480", "360":
		return step.ID
	default:
		return "1080"
	}
}

// The logical session owns one idle subscriber, so starting or stopping a
// child output never releases or retunes the physical channel. Each output is
// a normal fixed live session with its own authorized HLS URL and process slot.
func (m *Sessions) runAutomatic(s *session) {
	anchor := s.input
	startup := time.AfterFunc(time.Until(s.view.StartedAt.Add(TuneTimeout)), func() {
		m.mu.Lock()
		if s.view.State == "starting" {
			s.view.State, s.view.Reason = "failed", "stream_stalled"
			s.cancel()
		}
		m.mu.Unlock()
	})
	defer startup.Stop()
	defer func() {
		s.cancel()
		m.mu.Lock()
		ids := []string{s.auto.currentID, s.auto.pendingID}
		if s.view.State != "failed" {
			s.view.State = "stopped"
		}
		s.endedAt = time.Now()
		pendingWarning := len(m.warnings) > 0
		m.mu.Unlock()
		for _, id := range ids {
			if id != "" {
				_ = m.Stop(s.owner, id)
			}
		}
		if anchor != nil {
			_ = anchor.Close()
			awaitSourceRelease(s)
		}
		if m.opts.OnEnd != nil {
			m.opts.OnEnd(s.view.ID)
		}
		close(s.done)
		if pendingWarning {
			m.rewarn()
		}
	}()
	if anchor == nil {
		m.fail(s, "tuner_unavailable")
		return
	}
	closeOnCancel := context.AfterFunc(s.ctx, func() { _ = anchor.Close() })
	defer closeOnCancel()
	source := SourceInfo{}
	if inspector, ok := anchor.(interface {
		Inspect(context.Context, string) (SourceInfo, error)
	}); ok {
		if inspected, err := inspector.Inspect(s.ctx, m.opts.FFprobe); err == nil {
			source = inspected
		}
	}
	quality := initialLiveQuality(s.auto.initialBps, source)
	m.mu.Lock()
	s.auto.source = source
	m.mu.Unlock()
	child, err := m.startAutoOutput(s, quality)
	if err != nil {
		m.fail(s, autoStartFailure(err))
		return
	}
	if source.Bitrate <= 0 {
		m.mu.Lock()
		if output := m.items[child.ID]; output != nil {
			source = output.sourceInfo
		}
		m.mu.Unlock()
	}
	now := m.now()
	actual := autoActual(quality, child.Mode)
	if actual == "original" && source.Bitrate <= 0 {
		actual = quality
	}
	originalBps := int64(0)
	if source.Bitrate > 0 {
		mode := chooseLivePlan(s.profile, source, "1080").Mode
		if mode == "original" || mode == "remux" || mode == "audio" {
			originalBps = source.Bitrate
		}
	}
	m.mu.Lock()
	s.auto.source = source
	s.auto.currentID = child.ID
	s.auto.nextGen = 1
	s.auto.policy = playback.AutoState{OriginalBps: originalBps}.NewStream(actual, autoNeed(actual, source), now)
	s.view.Adaptive = child.Adaptive
	s.auto.policy.PlayerABR = child.Adaptive != nil
	s.view.URL, s.view.Mode, s.view.ModeReason = child.URL, child.Mode, child.ModeReason
	s.view.ActualQuality, s.view.OutputGeneration = actual, 1
	s.view.BufferBytes = child.BufferBytes
	s.view.State, s.view.ReadyAt = "playing", &now
	startup.Stop()
	close(s.ready)
	m.mu.Unlock()
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case change := <-s.auto.proposals:
			m.prepareAutoOutput(s, change)
		case now := <-ticker.C:
			m.tickAutomatic(s, now)
		}
	}
}

func autoStartFailure(err error) string {
	switch {
	case errors.Is(err, upload.ErrLimit), errors.Is(err, playback.ErrTooManySessions):
		return "server_busy"
	case errors.Is(err, playback.ErrStorageBudget):
		return "storage_limit"
	default:
		return "switch_failed"
	}
}

func (m *Sessions) startAutoOutput(s *session, quality string) (SessionView, error) {
	m.mu.Lock()
	kind := ""
	if s.auto.adaptive && !s.auto.ladderDisabled && s.profile != nil && s.profile.MaxAudioChannels != 1 && slices.Contains(s.profile.SegmentFormats, "fmp4") && slices.Contains(s.profile.VideoCodecs, "h264") && slices.Contains(s.profile.AudioCodecs, "aac") && chooseLivePlan(s.profile, s.auto.source, quality).Mode == "video" {
		kind = "ladder"
	}
	m.mu.Unlock()
	return m.startAutoOutputKind(s, quality, kind)
}

func (m *Sessions) startAutoOutputKind(s *session, quality, kind string) (SessionView, error) {
	m.mu.Lock()
	remote := s.auto.remote
	m.mu.Unlock()
	request := "auto-" + newID()
	child, err := m.startShared(s.ctx, s.owner, s.view.Channel.ID, quality, request, s.view.ClientName,
		s.profile, false, s.view.ID, 0, remote, kind)
	if err != nil {
		return SessionView{}, err
	}
	ctx, cancel := context.WithTimeout(s.ctx, TuneTimeout)
	defer cancel()
	ready, err := m.WaitReady(ctx, s.owner, child.ID)
	if err != nil || ready.State != "playing" {
		_ = m.Stop(s.owner, child.ID)
		if err == nil {
			switch ready.Reason {
			case "server_busy":
				err = playback.ErrTooManySessions
			case "storage_limit":
				err = playback.ErrStorageBudget
			default:
				err = fmt.Errorf("live output %s: %s", child.ID, ready.Reason)
			}
		}
		if kind == "ladder" && s.ctx.Err() == nil {
			m.mu.Lock()
			s.auto.ladderDisabled = true
			m.mu.Unlock()
			return m.startAutoOutputKind(s, quality, "")
		}
		return SessionView{}, err
	}
	return ready, nil
}

func (m *Sessions) prepareAutoOutput(s *session, change playback.StepChange) {
	if inspector, ok := s.input.(interface {
		Inspect(context.Context, string) (SourceInfo, error)
	}); ok {
		source, err := inspector.Inspect(s.ctx, m.opts.FFprobe)
		m.mu.Lock()
		if err == nil {
			s.auto.source = source
		} else {
			s.auto.source = SourceInfo{}
		}
		s.auto.policy.OriginalBps = 0
		if s.auto.source.Bitrate > 0 {
			mode := chooseLivePlan(s.profile, s.auto.source, "1080").Mode
			if mode == "original" || mode == "remux" || mode == "audio" {
				s.auto.policy.OriginalBps = s.auto.source.Bitrate
			}
		}
		m.mu.Unlock()
	}
	m.mu.Lock()
	a := s.auto
	current := a.currentID
	streamBps := a.policy.StreamBps
	forceReplace := a.ladderDisabled && s.view.Adaptive != nil
	m.mu.Unlock()
	quality := autoOutputQuality(change.To)
	if quality != "1080" && quality != "720" && quality != "480" && quality != "360" {
		m.rejectAutoOutput(s, change.To, "unsupported_step")
		return
	}
	child, err := m.startAutoOutput(s, quality)
	stoppedOld := false
	// A downgrade must still work at a full process or upload budget. Stop
	// only this viewer's old output, keep the anchor tuner, then retry once.
	down := forceReplace || playback.StepByID(quality).MaxBitrate < streamBps && change.To != "original"
	if err != nil && down && (errors.Is(err, playback.ErrTooManySessions) || errors.Is(err, upload.ErrLimit)) && current != "" {
		m.mu.Lock()
		if a.currentID == current {
			a.currentID = ""
			s.view.State = "starting"
			s.view.SwitchStatus = "switching_at_capacity"
			stoppedOld = true
		}
		m.mu.Unlock()
		if stoppedOld {
			_ = m.Stop(s.owner, current)
			child, err = m.startAutoOutput(s, quality)
		}
	}
	if err != nil {
		switch {
		case stoppedOld:
			m.fail(s, "switch_failed")
		case forceReplace:
			// The player cannot play the family it is on, and nothing can
			// replace it: end the play rather than leave it on a dead output.
			m.fail(s, "output_failed")
		default:
			status := "preparation_failed"
			if errors.Is(err, playback.ErrTooManySessions) || errors.Is(err, upload.ErrLimit) {
				status = "waiting_capacity"
			}
			m.rejectAutoOutput(s, change.To, status)
		}
		return
	}
	m.mu.Lock()
	if output := m.items[child.ID]; output != nil && output.sourceInfo.Bitrate > 0 {
		a.source = output.sourceInfo
		mode := chooseLivePlan(s.profile, a.source, "1080").Mode
		a.policy.OriginalBps = 0
		if mode == "original" || mode == "remux" || mode == "audio" {
			a.policy.OriginalBps = a.source.Bitrate
		}
	}
	m.mu.Unlock()
	if change.To == "original" && child.Mode != "original" && child.Mode != "remux" && child.Mode != "audio" {
		_ = m.Stop(s.owner, child.ID)
		m.rejectAutoOutput(s, change.To, "source_changed")
		return
	}
	m.mu.Lock()
	if s.ctx.Err() != nil || s.view.State == "stopping" {
		m.mu.Unlock()
		_ = m.Stop(s.owner, child.ID)
		return
	}
	a.nextGen++
	a.pendingID, a.pendingAt, a.pendingTo, a.preparing = child.ID, time.Now(), change.To, false
	s.view.Directive = &LiveDirective{Generation: a.nextGen, From: change.From, To: change.To,
		Adaptive: child.Adaptive, Reason: change.Reason, URL: child.URL, Mode: child.Mode, ModeReason: child.ModeReason}
	s.view.State = "playing" // the prepared URL must be loadable before its first-frame acknowledgement
	s.view.SwitchStatus = "ready"
	m.mu.Unlock()
}

func (m *Sessions) rejectAutoOutput(s *session, to, status string) {
	m.mu.Lock()
	s.auto.preparing = false
	s.auto.policy.MarkFailure(to, m.now())
	s.auto.blockedTo, s.auto.blockedAt = to, m.now()
	s.view.SwitchStatus = status
	m.mu.Unlock()
}

func (m *Sessions) tickAutomatic(s *session, now time.Time) {
	m.mu.Lock()
	a := s.auto
	current := m.items[a.currentID]
	if current != nil {
		s.view.BufferBytes = current.view.BufferBytes
		if current.output != nil {
			s.view.BufferBytes = current.output.producer.view.BufferBytes
		}
	}
	dead := (current == nil || current.view.State == "stopped" || current.view.State == "failed") && a.pendingID == ""
	timedOut := a.pendingID != "" && now.Sub(a.pendingAt) > 30*time.Second
	pending := ""
	if timedOut {
		pending = a.pendingID
		a.pendingID, a.pendingTo = "", ""
		a.policy.MarkFailure(s.view.Directive.To, now)
		a.blockedTo, a.blockedAt = s.view.Directive.To, m.now()
		s.view.Directive = nil
		s.view.SwitchStatus = "acknowledgement_timeout"
		// A family the player reported failed has no other way back.
		dead = a.currentID == "" || a.ladderDisabled && s.view.Adaptive != nil
	}
	m.mu.Unlock()
	if pending != "" {
		_ = m.Stop(s.owner, pending)
	}
	if dead {
		m.mu.Lock()
		recover := s.view.Adaptive != nil && !a.ladderDisabled
		if recover {
			a.ladderDisabled = true
			a.preparing = true
		}
		m.mu.Unlock()
		if recover {
			m.prepareAutoOutput(s, playback.StepChange{From: s.view.ActualQuality, To: "720", Reason: "adaptive output failed"})
			return
		}
		m.fail(s, "output_failed")
	}
}

// HeartbeatAutomatic observes the player without applying finite-file end
// rules. A synthetic monotonic playhead lets the established buffer/stall
// policy work at the live edge. Only first-frame acknowledgement promotes a
// prepared output; duplicate and stale generations cannot replace it.
func (m *Sessions) HeartbeatAutomatic(ctx context.Context, owner Owner, id string, paused, interacted bool, measure LiveMeasurement) (SessionView, error) {
	if _, err := m.Status(ctx, owner, id); err != nil {
		return SessionView{}, err
	}
	m.mu.Lock()
	s := m.items[id]
	if s == nil {
		m.mu.Unlock()
		return SessionView{}, ErrOwner
	}
	if s.auto == nil {
		m.mu.Unlock()
		if measure.SwitchGeneration != 0 || measure.SwitchFailedGeneration != 0 {
			return SessionView{}, ErrStaleLiveSwitch
		}
		return m.Heartbeat(ctx, owner, id, paused, interacted)
	}
	a := s.auto
	if s.view.Adaptive.Accepts(measure.RenditionHeight, measure.RenditionBitrate) {
		s.view.RenditionHeight, s.view.RenditionBitrate = measure.RenditionHeight, measure.RenditionBitrate
		a.policy.StreamBps = measure.RenditionBitrate
	}
	now := m.now()
	a.remote = measure.Remote
	if measure.AdaptiveFailed && s.view.Adaptive != nil && !a.ladderDisabled && !a.preparing && a.pendingID == "" {
		a.ladderDisabled, a.preparing = true, true
		select {
		case a.proposals <- playback.StepChange{From: s.view.ActualQuality, To: "720", Reason: "adaptive playback failed"}:
		default:
			// Another change is queued; the next report asks again.
			a.ladderDisabled, a.preparing = false, false
		}
	}
	if measure.SwitchGeneration != 0 && measure.SwitchFailedGeneration != 0 {
		m.mu.Unlock()
		return SessionView{}, ErrStaleLiveSwitch
	}
	old, failed := "", ""
	if generation := measure.SwitchGeneration; generation != 0 {
		if generation != s.view.OutputGeneration {
			if s.view.Directive == nil || s.view.Directive.Generation != generation || a.pendingID == "" {
				m.mu.Unlock()
				return SessionView{}, ErrStaleLiveSwitch
			}
			child := m.items[a.pendingID]
			if child == nil || child.view.State != "playing" {
				m.mu.Unlock()
				return SessionView{}, ErrStaleLiveSwitch
			}
			old = a.currentID
			a.currentID, a.pendingID, a.pendingTo = a.pendingID, "", ""
			actual := autoActual(child.view.Quality, child.view.Mode)
			if actual == "original" && a.source.Bitrate <= 0 {
				actual = child.view.Quality
			}
			a.policy = a.policy.NewStream(actual, autoNeed(actual, a.source), now)
			s.view.Adaptive = child.view.Adaptive
			s.view.RenditionHeight, s.view.RenditionBitrate = 0, 0
			a.policy.PlayerABR = child.view.Adaptive != nil
			s.view.URL, s.view.Mode, s.view.ModeReason = child.view.URL, child.view.Mode, child.view.ModeReason
			s.view.ActualQuality, s.view.OutputGeneration = actual, generation
			s.view.BufferBytes, s.view.State = child.view.BufferBytes, "playing"
			s.view.Directive, s.view.SwitchStatus = nil, ""
		}
	}
	if generation := measure.SwitchFailedGeneration; generation != 0 {
		if s.view.Directive == nil || s.view.Directive.Generation != generation || a.pendingID == "" {
			m.mu.Unlock()
			return SessionView{}, ErrStaleLiveSwitch
		}
		failed = a.pendingID
		a.pendingID, a.pendingTo = "", ""
		a.policy.MarkFailure(s.view.Directive.To, now)
		a.blockedTo, a.blockedAt = s.view.Directive.To, now
		s.view.Directive, s.view.SwitchStatus = nil, "playback_failed"
		if a.currentID == "" {
			s.view.State, s.view.Reason = "failed", "switch_failed"
			s.cancel()
		}
	}
	if s.endedAt.IsZero() && s.view.State != "stopping" && s.view.State != "failed" {
		s.view.LastSeenAt = now
		s.view.Paused = paused
		if paused && s.pausedAt.IsZero() {
			s.pausedAt = now
		} else if !paused {
			s.pausedAt = time.Time{}
		}
		if interacted {
			s.interactedAt = now
		}
	}
	currentOutput := m.items[a.currentID]
	outputFresh := liveOutputFresh(currentOutput)
	if currentOutput != nil && currentOutput.output != nil {
		currentOutput = currentOutput.output.producer
	}
	if !paused && s.view.State == "playing" && !a.preparing && a.pendingID == "" && s.view.Directive == nil && !outputFresh {
		s.view.SwitchStatus = "source_wait"
	} else if outputFresh && s.view.SwitchStatus == "source_wait" {
		s.view.SwitchStatus = ""
	}
	if !paused && s.view.State == "playing" && !a.preparing && a.pendingID == "" && s.view.Directive == nil && outputFresh {
		state := measure.State
		if state != "playing" && state != "buffering" {
			state = "playing"
		}
		stalls := -1
		if measure.Stalls != nil {
			stalls = *measure.Stalls
		}
		sample := playback.AutoSample{At: now, State: state, PositionMs: now.Sub(s.view.StartedAt).Milliseconds(),
			BufferedMs: measure.BufferedMs, ThroughputBps: measure.ThroughputBps,
			ThroughputAgeMs: measure.ThroughputAgeMs, ServerPaced: measure.ServerPaced, Stalls: stalls}
		if time.Since(time.Unix(0, currentOutput.encoderProgressAt.Load())) <= 3*time.Second {
			sample.TranscodeSpeed = math.Float64frombits(currentOutput.encoderSpeed.Load())
		}
		var change *playback.StepChange
		a.policy, change = playback.NextLiveStepWithRecovery(a.policy, sample, liveTargetDurationMs(currentOutput.playlist))
		if change != nil && change.To != "2160" &&
			(a.blockedTo != change.To || now.Sub(a.blockedAt) >= 15*time.Second) {
			a.preparing = true
			s.view.SwitchStatus = "preparing"
			select {
			case a.proposals <- *change:
			default:
				a.preparing = false
			}
		}
	}
	current, pending := a.currentID, a.pendingID
	view := m.viewLocked(s, now)
	m.mu.Unlock()
	for _, childID := range []string{current, pending} {
		if childID != "" {
			_, _ = m.Heartbeat(ctx, owner, childID, paused, interacted)
		}
	}
	if old != "" && old != current {
		_ = m.Stop(owner, old)
	}
	if failed != "" {
		_ = m.Stop(owner, failed)
		if current == "" {
			m.fail(s, "switch_failed")
		}
	}
	return view, nil
}
