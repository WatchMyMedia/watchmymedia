package livetv

import (
	"context"
	"io"
	"reflect"
	"time"

	"watchmymedia/server/internal/playback"
)

// startShared joins a physical channel source before publishing the viewer.
// The join runs outside Service.mu: the DVR scheduler takes its own lock
// before tuner admission, while source changes take Service.mu first.
func (m *Sessions) startShared(ctx context.Context, owner Owner, channelID, quality, requestID, clientName string, profile *LiveProfile, automatic bool, parentID string, initialBps int64, remote bool, outputKind string) (SessionView, error) {
	if quality == "" {
		quality = "1080"
	}
	if quality != "1080" && quality != "720" && quality != "480" && quality != "360" && (!automatic || quality != "auto") {
		return SessionView{}, ErrQuality
	}
	if owner.SignInID == "" || requestID == "" || len(requestID) > 64 || len(clientName) > 128 {
		return SessionView{}, ErrOwner
	}
	key := owner.SignInID + "/" + requestID
	type prepared struct {
		view SessionView
		s    *session
		join func(context.Context, string, string) (io.ReadCloser, error)
	}
	p, err := func() (prepared, error) {
		m.service.mu.Lock()
		defer m.service.mu.Unlock()
		if !m.service.Allowed(ctx, owner.Admin) {
			return prepared{}, ErrDisabled
		}
		m.mu.Lock()
		defer m.mu.Unlock()
		if m.closed {
			return prepared{}, playback.ErrStopped
		}
		if id, ok := m.requests[key]; ok {
			s := m.items[id]
			if s.view.Channel.ID != channelID || s.view.Quality != quality || !reflect.DeepEqual(s.profile, profile) || s.outputKind != outputKind {
				return prepared{}, ErrRequest
			}
			return prepared{view: m.viewLocked(s, time.Now())}, nil
		}
		if len(m.items) >= 2048 {
			return prepared{}, playback.ErrTooManySessions
		}
		channel, err := m.service.q.GetLiveChannel(ctx, channelID)
		if err != nil {
			return prepared{}, err
		}
		if channel.Enabled == 0 || channel.Available == 0 || channel.Protected != 0 {
			return prepared{}, ErrDisabled
		}
		source, err := m.service.q.GetLiveSource(ctx, channel.SourceID)
		if err != nil {
			return prepared{}, err
		}
		if source.Enabled == 0 || m.joinSource == nil {
			return prepared{}, ErrDisabled
		}
		own := 0
		for _, existing := range m.items {
			if existing.owner.SignInID == owner.SignInID && existing.parentID == "" {
				own++
			}
		}
		if own >= 64 {
			return prepared{}, playback.ErrTooManySessions
		}
		if err := m.playback.CheckStorage(); err != nil {
			return prepared{}, err
		}
		id := newID()
		if !automatic {
			if parentID != "" && m.opts.OnAdmitDemand != nil {
				parent := m.items[parentID]
				if parent == nil || parent.auto == nil {
					return prepared{}, ErrOwner
				}
				if err := m.opts.OnAdmitDemand(ctx, id, liveDemand(quality, profile, parent.auto.source), parent.auto.remote); err != nil {
					return prepared{}, err
				}
			} else if m.opts.OnAdmit != nil {
				if err := m.opts.OnAdmit(ctx, id, quality); err != nil {
					return prepared{}, err
				}
			}
		}
		now := time.Now()
		m.startOrder++
		runCtx, cancel := context.WithCancel(m.ctx)
		s := &session{shared: true, owner: owner, requestID: requestID, sourceID: source.ID,
			address: source.Address, deviceID: source.DeviceID, path: channel.StreamPath,
			ctx: runCtx, cancel: cancel, done: make(chan struct{}), ready: make(chan struct{}), interactedAt: now, profile: profile, parentID: parentID}
		s.startOrder = m.startOrder
		s.outputKind = outputKind
		if automatic {
			s.auto = &liveAuto{adaptive: outputKind == "adaptive", initialBps: initialBps, remote: remote, proposals: make(chan playback.StepChange, 1)}
		}
		s.view = SessionView{ID: id, Channel: Channel{ID: channel.ID, SourceID: source.ID, Number: channel.Number,
			Name: channel.Name, Enabled: true, Available: true}, State: "starting", Quality: quality,
			UserID: owner.UserID, UserName: owner.Name, ClientName: clientName,
			StartedAt: now, LastSeenAt: now, BufferSeconds: SegmentSeconds * WindowSegments}
		s.view.URL = "/api/live-tv/sessions/" + id + "/hls/live.m3u8"
		s.view.Automatic = automatic
		return prepared{s: s, join: m.joinSource}, nil
	}()
	if err != nil || p.s == nil {
		return p.view, err
	}
	s := p.s
	body, err := p.join(s.ctx, s.sourceID, channelID)
	if err != nil {
		s.cancel()
		if m.opts.OnEnd != nil {
			m.opts.OnEnd(s.view.ID)
		}
		return SessionView{}, err
	}
	m.service.mu.Lock()
	m.mu.Lock()
	var result SessionView
	var reject error
	if m.closed {
		reject = playback.ErrStopped
	} else if id, ok := m.requests[key]; ok {
		current := m.items[id]
		if current.view.Channel.ID != channelID || current.view.Quality != quality || !reflect.DeepEqual(current.profile, profile) || current.outputKind != outputKind {
			reject = ErrRequest
		} else {
			result = m.viewLocked(current, time.Now())
		}
	} else {
		own := 0
		for _, existing := range m.items {
			if existing.owner.SignInID == owner.SignInID && existing.parentID == "" {
				own++
			}
		}
		if own >= 64 || len(m.items) >= 2048 {
			reject = playback.ErrTooManySessions
		} else {
			s.input = body
			m.items[s.view.ID] = s
			m.requests[key] = s.view.ID
			m.warnLocked(time.Now(), false)
			if automatic {
				m.wg.Go(func() { m.runAutomatic(s) })
			} else if outputKind != "" {
				m.wg.Go(func() { m.runSharedViewer(s) })
			} else {
				m.wg.Go(func() { m.run(s) })
			}
			result = m.viewLocked(s, time.Now())
		}
	}
	m.mu.Unlock()
	m.service.mu.Unlock()
	if s.input == nil {
		_ = body.Close()
		s.cancel()
		if m.opts.OnEnd != nil {
			m.opts.OnEnd(s.view.ID)
		}
	}
	return result, reject
}

// SetSharedSourceInput enables viewer-first channel capture after the DVR
// service is constructed. The rollout flag in SessionOptions controls use.
func (m *Sessions) SetSharedSourceInput(join func(context.Context, string, string) (io.ReadCloser, error)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.joinSource = join
}
