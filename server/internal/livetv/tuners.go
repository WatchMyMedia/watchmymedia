package livetv

import (
	"context"
	"io"
	"time"

	"watchmymedia/server/internal/livetv/hdhomerun"
)

// OpenTuner is the common identity-checked opening path for viewers and DVR.
// No ffmpeg process or transcode reservation is needed to copy a broadcast.
func (s *Service) OpenTuner(ctx context.Context, sourceID, channelID string) (io.ReadCloser, error) {
	source, err := s.q.GetLiveSource(ctx, sourceID)
	if err != nil {
		return nil, err
	}
	ch, err := s.q.GetLiveChannel(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if source.Enabled == 0 || ch.Enabled == 0 || ch.Protected != 0 || ch.SourceID != source.ID {
		return nil, ErrDisabled
	}
	return s.openTuner(ctx, source.Address, source.DeviceID, ch.StreamPath)
}
func (s *Service) openTuner(ctx context.Context, address, deviceID, path string) (io.ReadCloser, error) {
	client, err := s.client(address)
	if err != nil {
		return nil, err
	}
	tuneCtx, cancel := context.WithTimeout(ctx, TuneTimeout)
	device, err := client.Discover(tuneCtx)
	cancel()
	if err != nil {
		return nil, err
	}
	if device.ID != deviceID {
		return nil, hdhomerun.ErrIdentity
	}
	body, err := client.Open(ctx, path)
	if err != nil {
		return nil, err
	}
	reader := &tunerReader{ReadCloser: body}
	reader.cancel = context.AfterFunc(ctx, func() { _ = body.Close() })
	return reader, nil
}

type tunerReader struct {
	io.ReadCloser
	cancel func() bool
}

func (r *tunerReader) Read(p []byte) (int, error) {
	timer := time.AfterFunc(TuneTimeout, func() { _ = r.ReadCloser.Close() })
	defer timer.Stop()
	return r.ReadCloser.Read(p)
}
func (r *tunerReader) Close() error {
	if r.cancel != nil {
		r.cancel()
	}
	return r.ReadCloser.Close()
}

// ReserveViewerSource claims one physical tuner for a shared channel group.
// Joining that group claims no additional tuner. Recording reservations use
// the same admission lock and may later take over a viewer-only group.
func (s *Service) ReserveViewerSource(ctx context.Context, id, sourceID string) (func(), error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	source, err := s.q.GetLiveSource(ctx, sourceID)
	if err != nil || source.Enabled == 0 {
		return nil, ErrDisabled
	}
	if s.recorders == nil {
		s.recorders = map[string]string{}
	}
	count := 0
	for _, current := range s.recorders {
		if current == sourceID {
			count++
		}
	}
	if s.viewers != nil {
		s.viewers.mu.Lock()
		for _, v := range s.viewers.items {
			if v.sourceID == sourceID && v.endedAt.IsZero() && !v.shared {
				count++
			}
		}
		s.viewers.mu.Unlock()
	}
	if count >= int(source.TunerCount) {
		return nil, hdhomerun.Busy
	}
	s.recorders[id] = sourceID
	return func() {
		s.mu.Lock()
		delete(s.recorders, id)
		delete(s.recorderTakeovers, id)
		delete(s.recorderChannels, id)
		delete(s.recorderTaken, id)
		s.mu.Unlock()
	}, nil
}

// ReserveRecording atomically counts recordings and viewers under the same
// admission lock. It reserves before asking the newest viewer to stop, then
// waits for that stream to close before the recorder may open the device.
func (s *Service) ReserveRecording(ctx context.Context, id, sourceID string, warnings ...RecordingWarning) (func(), error) {
	s.mu.Lock()
	source, err := s.q.GetLiveSource(ctx, sourceID)
	if err != nil || source.Enabled == 0 {
		s.mu.Unlock()
		return nil, ErrDisabled
	}
	if s.recorders == nil {
		s.recorders = map[string]string{}
	}
	count := 0
	for _, source := range s.recorders {
		if source == sourceID {
			count++
		}
	}
	var victim *session
	victimWarned := false
	borrowed := ""
	if s.viewers != nil {
		s.viewers.mu.Lock()
		for _, v := range s.viewers.items {
			if v.sourceID != sourceID || !v.endedAt.IsZero() || v.parentID != "" {
				continue
			}
			candidate := ""
			if !v.shared {
				count++
			} else {
				candidate = s.borrowedLocked(v)
				if candidate == "" {
					continue
				}
			}
			// The viewer warned about this recording is the one who gives
			// way; otherwise the newest viewer does.
			if !v.recordingClaimed {
				warned := len(warnings) > 0 && v.view.TakeoverRecordingID == warnings[0].ID
				if victim == nil || warned && !victimWarned || warned == victimWarned && v.newerThan(victim) {
					victim, borrowed, victimWarned = v, candidate, warned
				}
			}
		}
		if count >= int(source.TunerCount) && victim != nil {
			for _, v := range s.viewers.items {
				if v != victim && (borrowed == "" || !v.shared || v.sourceID != victim.sourceID || v.view.Channel.ID != victim.view.Channel.ID) {
					continue
				}
				v.recordingClaimed = true
				if len(warnings) > 0 {
					w := warnings[0]
					v.view.TakeoverAt = &w.At
					v.view.TakeoverTitle = w.Title
					v.view.TakeoverRecordingID = w.ID
					v.view.TakeoverCanCancel = v.owner.Admin || w.UserID != nil && *w.UserID == v.owner.UserID
				}
			}
		}
		s.viewers.mu.Unlock()
	}
	if count >= int(source.TunerCount) && victim == nil {
		s.mu.Unlock()
		return nil, hdhomerun.Busy
	}
	takeover := s.recorderTakeovers[borrowed]
	if count >= int(source.TunerCount) && borrowed != "" {
		if s.recorderTaken == nil {
			s.recorderTaken = map[string]bool{}
		}
		s.recorderTaken[borrowed] = true
	}
	s.recorders[id] = sourceID
	s.mu.Unlock()
	release := func() {
		s.mu.Lock()
		delete(s.recorders, id)
		delete(s.recorderTakeovers, id)
		delete(s.recorderChannels, id)
		delete(s.recorderTaken, id)
		s.mu.Unlock()
	}
	if count >= int(source.TunerCount) {
		if borrowed != "" && takeover != nil {
			takeover()
		} else {
			s.viewers.stop(victim, "recording")
			select {
			case <-ctx.Done():
				release()
				return nil, ctx.Err()
			case <-victim.done:
			}
		}
	}
	return release, nil
}

// borrowedLocked identifies a stream retained only by viewers after recording
// ends. It can be taken over like any other viewer's tuner, never while an
// output still writes to it. The caller holds Service.mu.
func (s *Service) borrowedLocked(v *session) string {
	for id, ch := range s.recorderChannels {
		if ch == v.view.Channel.ID && s.recorders[id] == v.sourceID && s.recorderTakeovers[id] != nil {
			return id
		}
	}
	return ""
}
func (s *Service) BorrowedRecording(id, channel string, takeover func()) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.recorderTakeovers == nil {
		s.recorderTakeovers = map[string]func(){}
		s.recorderChannels = map[string]string{}
	}
	s.recorderTakeovers[id] = takeover
	s.recorderChannels[id] = channel
}
func (s *Service) ProtectRecording(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.recorderTaken[id] {
		return false
	}
	delete(s.recorderTakeovers, id)
	delete(s.recorderChannels, id)
	return true
}
func (m *Sessions) StopSharedChannel(channel, reason string) {
	m.mu.Lock()
	var items []*session
	for _, v := range m.items {
		if v.shared && v.view.Channel.ID == channel && v.endedAt.IsZero() {
			items = append(items, v)
		}
	}
	m.mu.Unlock()
	for _, v := range items {
		m.stop(v, reason)
	}
	for _, v := range items {
		<-v.done
	}
}

type RecordingWarning struct {
	SourceID, ChannelID, ID, Title string
	At                             time.Time
	UserID                         *int64
}

// RecordingWarnings warns the viewers who will give way to the recordings
// about to start. The list is kept and applied again whenever a viewer
// starts or stops before then, so the viewer who loses the tuner is always
// the one who was warned.
func (m *Sessions) RecordingWarnings(warnings []RecordingWarning) {
	m.service.mu.Lock()
	defer m.service.mu.Unlock()
	m.mu.Lock()
	defer m.mu.Unlock()
	m.warnings = warnings
	m.warnLocked(time.Now(), true)
}

// Several starts can share the same clock tick, particularly on Windows.
// Map iteration must not decide which viewer's group loses its tuner.
func (v *session) newerThan(other *session) bool {
	return other == nil || v.view.StartedAt.After(other.view.StartedAt) ||
		(v.view.StartedAt.Equal(other.view.StartedAt) && v.startOrder > other.startOrder)
}

// rewarn applies the kept warnings again after a viewer stopped.
func (m *Sessions) rewarn() {
	m.service.mu.Lock()
	defer m.service.mu.Unlock()
	m.mu.Lock()
	defer m.mu.Unlock()
	m.warnLocked(time.Now(), false)
}

// warnLocked runs with Service.mu and m.mu held. Unless forced it does
// nothing while no warning is kept, so viewers come and go cheaply.
func (m *Sessions) warnLocked(now time.Time, force bool) {
	if !force && len(m.warnings) == 0 {
		return
	}
	warnings := make([]RecordingWarning, 0, len(m.warnings))
	for _, w := range m.warnings {
		if w.At.After(now) {
			warnings = append(warnings, w)
		}
	}
	m.warnings = warnings
	for _, v := range m.items {
		if !v.endedAt.IsZero() || v.view.State == "stopping" {
			continue
		}
		v.view.TakeoverAt = nil
		v.view.TakeoverTitle = ""
		v.view.TakeoverRecordingID = ""
		v.view.TakeoverCanCancel = false
	}
	used := map[string]bool{}
	planned := map[string]int64{}
	channels := map[string]bool{}
	for _, w := range warnings {
		key := w.SourceID + ":" + w.ChannelID
		if channels[key] || m.sharing != nil && m.sharing(w.ChannelID) {
			continue
		}
		channels[key] = true
		capacity := int64(0)
		source, err := m.service.q.GetLiveSource(context.Background(), w.SourceID)
		if err == nil {
			capacity = source.TunerCount
		}
		holders := planned[w.SourceID]
		planned[w.SourceID]++
		for _, source := range m.service.recorders {
			if source == w.SourceID {
				holders++
			}
		}
		var victim *session
		victimGroup := ""
		for _, v := range m.items {
			if v.parentID == "" && v.sourceID == w.SourceID && v.endedAt.IsZero() && (!v.shared || m.service.borrowedLocked(v) != "") {
				if !v.shared {
					holders++
				}
				group := v.view.ID
				if v.shared {
					group = m.service.borrowedLocked(v)
				}
				if !used[group] && !v.recordingClaimed && v.newerThan(victim) {
					victim = v
					victimGroup = group
				}
			}
		}
		if holders >= capacity && victim != nil {
			used[victimGroup] = true
			for _, member := range m.items {
				group := member.view.ID
				if member.shared {
					group = m.service.borrowedLocked(member)
				}
				if group != victimGroup || !member.endedAt.IsZero() {
					continue
				}
				member.view.TakeoverAt = &w.At
				member.view.TakeoverTitle = w.Title
				member.view.TakeoverRecordingID = w.ID
				member.view.TakeoverCanCancel = member.owner.Admin || w.UserID != nil && *w.UserID == member.owner.UserID
			}
		}
	}
}
func (m *Sessions) SetRecordingInput(sharing func(string) bool, open func(context.Context, string) (io.ReadCloser, error)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sharing = sharing
	m.openRecording = open
}
