package livetv

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"slices"
	"time"

	"watchmymedia/server/internal/playback"
)

// This capability is provided by DVR's bounded capture. Inactive subscriptions
// hold the source without queuing bytes; only the producer's fork reads it.
type liveCapture interface {
	io.ReadCloser
	CaptureIdentity() (string, int64)
	Fork(context.Context) (io.ReadCloser, error)
}

type sharedLiveOutput struct {
	producer *session
	refs     int // guarded, like session state, by Sessions.mu
}

// StartSharedOutput is the private A1 qualification entry point. No production
// request field or feature flag enables it. Each viewer still has its own
// authorization, upload reservation, heartbeat and tuner-group membership.
func (m *Sessions) StartSharedOutput(ctx context.Context, owner Owner, channel, quality, request, client string, profile *LiveProfile) (SessionView, error) {
	return m.startPrivateOutput(ctx, owner, channel, quality, request, client, profile, "single")
}

func (m *Sessions) startPrivateOutput(ctx context.Context, owner Owner, channel, quality, request, client string, profile *LiveProfile, kind string) (SessionView, error) {
	if profile == nil || validateLiveProfile(profile) != nil || !m.SupportsAutomatic() {
		return SessionView{}, ErrProfile
	}
	copyProfile := *profile
	copyProfile.SegmentFormats = slices.Clone(profile.SegmentFormats)
	copyProfile.VideoCodecs = slices.Clone(profile.VideoCodecs)
	copyProfile.AudioCodecs = slices.Clone(profile.AudioCodecs)
	copyProfile.HDRTypes = slices.Clone(profile.HDRTypes)
	normalize := func(values []string) []string { slices.Sort(values); return slices.Compact(values) }
	copyProfile.SegmentFormats = normalize(copyProfile.SegmentFormats)
	copyProfile.VideoCodecs = normalize(copyProfile.VideoCodecs)
	copyProfile.AudioCodecs = normalize(copyProfile.AudioCodecs)
	copyProfile.HDRTypes = normalize(copyProfile.HDRTypes)
	return m.startShared(ctx, owner, channel, quality, request, client, &copyProfile, false, "", 0, false, kind)
}

func liveOutputKey(capture string, generation int64, s *session) string {
	// Exact profile equality is conservative. Track mapping is always first
	// video/audio, no burn-in, and the private encoder recipe is software only.
	// A changed recipe needs a new version even if its public profile is equal.
	contract, _ := json.Marshal(struct {
		Recipe, Capture, Kind, Quality string
		Generation                     int64
		Profile                        *LiveProfile
	}{"live-output-v2-software", capture, s.outputKind, s.view.Quality, generation, s.profile})
	return fmt.Sprintf("%x", sha256.Sum256(contract))
}

func (m *Sessions) acquireOutput(s *session) (*sharedLiveOutput, error) {
	capture, ok := s.input.(liveCapture)
	if !ok {
		return nil, ErrSourceUnknown
	}
	for {
		id, generation := capture.CaptureIdentity()
		if id == "" || generation <= 0 {
			return nil, ErrSourceUnknown
		}
		key := liveOutputKey(id, generation, s)
		m.mu.Lock()
		if m.closed || s.ctx.Err() != nil {
			m.mu.Unlock()
			return nil, playback.ErrStopped
		}
		if existing := m.outputs[key]; existing != nil {
			if existing.producer.ctx.Err() == nil {
				existing.refs++
				s.output = existing
				m.mu.Unlock()
				return existing, nil
			}
			done := existing.producer.done
			m.mu.Unlock()
			select {
			case <-s.ctx.Done():
				return nil, s.ctx.Err()
			case <-done:
			}
			// The last reader can retain its output object after the registry
			// removes it. Never let that stale release remove a replacement.
			m.mu.Lock()
			if m.outputs[key] == existing {
				delete(m.outputs, key)
			}
			m.mu.Unlock()
			continue
		}
		ctx, cancel := context.WithCancel(m.ctx)
		body, err := capture.Fork(ctx)
		if err != nil {
			cancel()
			m.mu.Unlock()
			return nil, err
		}
		p := &session{producer: true, ctx: ctx, cancel: cancel, ready: make(chan struct{}), done: make(chan struct{}),
			profile: s.profile, outputKind: s.outputKind, view: SessionView{ID: newID(), Quality: s.view.Quality, State: "starting", StartedAt: time.Now()}}
		fork, ok := body.(liveCapture)
		if !ok {
			_ = body.Close()
			cancel()
			m.mu.Unlock()
			return nil, ErrSourceUnknown
		}
		p.input = &generationInput{liveCapture: fork, id: id, generation: generation}
		output := &sharedLiveOutput{producer: p, refs: 1}
		m.outputs[key], s.output = output, output
		m.wg.Go(func() { m.run(p) })
		m.mu.Unlock()
		return output, nil
	}
}

func (m *Sessions) releaseOutput(output *sharedLiveOutput) {
	m.mu.Lock()
	output.refs--
	last := output.refs == 0
	if last {
		output.producer.cancel()
	}
	m.mu.Unlock()
	if !last {
		return
	}
	<-output.producer.done
	m.mu.Lock()
	for key, current := range m.outputs {
		if current == output {
			delete(m.outputs, key)
			break
		}
	}
	m.mu.Unlock()
}

func (m *Sessions) runSharedViewer(s *session) {
	defer func() {
		if s.output != nil {
			m.releaseOutput(s.output)
		}
		_ = s.input.Close()
		s.cancel()
		if m.opts.OnEnd != nil {
			m.opts.OnEnd(s.view.ID)
		}
		m.mu.Lock()
		if s.view.State != "failed" {
			s.view.State = "stopped"
		}
		if s.output != nil {
			output := s.output.producer
			s.view.Mode, s.view.ModeReason = output.view.Mode, output.view.ModeReason
			s.view.BufferBytes = output.view.BufferBytes
		}
		s.endedAt = time.Now()
		pending := len(m.warnings) > 0
		m.mu.Unlock()
		close(s.done)
		if pending {
			m.rewarn()
		}
	}()
	if !m.authorized(s.ctx, s) {
		m.fail(s, "access_revoked")
		return
	}
	output, err := m.acquireOutput(s)
	if err != nil {
		m.fail(s, "source_unsupported")
		return
	}
	p := output.producer
	select {
	case <-s.ctx.Done():
		return
	case <-p.done:
		m.mu.Lock()
		reason := p.view.Reason
		m.mu.Unlock()
		m.fail(s, reason)
		return
	case <-p.ready:
	}
	m.mu.Lock()
	if s.ctx.Err() == nil && p.ctx.Err() == nil {
		// Viewer readiness is distinct from the producer's original evidence.
		// A late join must never report ReadyAt earlier than its own start.
		readyAt := time.Now()
		s.view.State, s.view.ReadyAt = "playing", &readyAt
		if p.family != nil {
			s.view.Adaptive = p.family.offer()
		}
		close(s.ready)
	}
	m.mu.Unlock()
	select {
	case <-s.ctx.Done():
	case <-p.done:
		m.mu.Lock()
		reason := p.view.Reason
		m.mu.Unlock()
		m.fail(s, reason)
	}
}

var errCaptureChanged = errors.New("live capture timeline changed")

// Never append a reopened tuner timeline to an already advertised output.
// DVR retains the capture independently; only this output fails closed.
type generationInput struct {
	liveCapture
	id         string
	generation int64
}

func (r *generationInput) current() bool {
	id, generation := r.CaptureIdentity()
	return id == r.id && generation == r.generation
}

func (r *generationInput) Read(b []byte) (int, error) {
	if !r.current() {
		return 0, errCaptureChanged
	}
	n, err := r.liveCapture.Read(b)
	if !r.current() {
		return 0, errCaptureChanged
	}
	return n, err
}

func (r *generationInput) Inspect(ctx context.Context, ffprobe string) (SourceInfo, error) {
	inspector, ok := r.liveCapture.(interface {
		Inspect(context.Context, string) (SourceInfo, error)
	})
	if !ok || !r.current() {
		return SourceInfo{}, ErrSourceUnknown
	}
	var info SourceInfo
	var err error
	if fresh, ok := r.liveCapture.(interface {
		InspectFresh(context.Context, string) (SourceInfo, error)
	}); ok {
		info, err = fresh.InspectFresh(ctx, ffprobe)
	} else {
		info, err = inspector.Inspect(ctx, ffprobe)
	}
	if !r.current() {
		return SourceInfo{}, errCaptureChanged
	}
	return info, err
}
