package livetv

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/playback"
)

const (
	Lease             = time.Minute
	PauseAllowance    = 5 * time.Minute
	InactiveAllowance = 4*time.Hour + time.Minute
	TuneTimeout       = 20 * time.Second
	terminalRetention = 2 * time.Minute
)

var (
	ErrOwner   = errors.New("live session not found")
	ErrRequest = errors.New("request ID was already used for a different channel, quality or live profile")
	ErrQuality = errors.New("choose 1080, 720, 480 or 360 for Live TV")
	mediaName  = regexp.MustCompile(`^segment-[0-9]{9}\.(ts|m4s)$`)
)

type Owner struct {
	UserID   int64
	SignInID string
	Name     string
	Admin    bool
}
type SessionView struct {
	Adaptive              *playback.AdaptiveStream `json:"adaptive,omitempty" required:"false"`
	RenditionHeight       int                      `json:"renditionHeight,omitempty" required:"false"`
	RenditionBitrate      int64                    `json:"renditionBitrate,omitempty" required:"false"`
	TakeoverAt            *time.Time               `json:"takeoverAt,omitempty" required:"false"`
	TakeoverTitle         string                   `json:"takeoverTitle,omitempty" required:"false"`
	TakeoverRecordingID   string                   `json:"takeoverRecordingId,omitempty" required:"false"`
	TakeoverCanCancel     bool                     `json:"takeoverCanCancel,omitempty" required:"false"`
	ID                    string                   `json:"id"`
	Channel               Channel                  `json:"channel"`
	State                 string                   `json:"state" doc:"starting, playing, stopping, stopped or failed. Unknown states are not playable."`
	Reason                string                   `json:"reason,omitempty"`
	Quality               string                   `json:"quality"`
	Mode                  string                   `json:"mode,omitempty" required:"false" doc:"Live output mode: original, remux, audio or video; absent on older servers."`
	ModeReason            string                   `json:"modeReason,omitempty" required:"false" doc:"Why that mode was selected; absent on older servers."`
	Automatic             bool                     `json:"automatic,omitempty" required:"false" doc:"API level 26: this logical live session may switch output generations."`
	ActualQuality         string                   `json:"actualQuality,omitempty" required:"false" doc:"Current live output step while Automatic is selected."`
	OutputGeneration      uint64                   `json:"outputGeneration,omitempty" required:"false" doc:"Current acknowledged output generation; the session ID and tuner stay stable."`
	Directive             *LiveDirective           `json:"directive,omitempty" required:"false" doc:"Prepared optional Automatic output; acknowledge after its first frame."`
	SwitchStatus          string                   `json:"switchStatus,omitempty" required:"false" doc:"Why a live Automatic switch is waiting or failed."`
	URL                   string                   `json:"url" doc:"Same-server rolling HLS playlist; send the original sign-in or its media token."`
	UserID                int64                    `json:"userId"`
	UserName              string                   `json:"userName"`
	ClientName            string                   `json:"clientName"`
	StartedAt             time.Time                `json:"startedAt"`
	ReadyAt               *time.Time               `json:"readyAt,omitempty"`
	LastSeenAt            time.Time                `json:"lastSeenAt"`
	Paused                bool                     `json:"paused"`
	PauseRemainingSeconds int                      `json:"pauseRemainingSeconds"`
	BufferSeconds         int                      `json:"bufferSeconds"`
	BufferBytes           int64                    `json:"bufferBytes"`
}
type session struct {
	shared                            bool
	producer                          bool
	outputKind                        string
	output                            *sharedLiveOutput
	startOrder                        uint64 // admission order breaks equal clock timestamps
	recordingClaimed                  bool
	view                              SessionView
	owner                             Owner
	requestID                         string
	sourceID, address, deviceID, path string
	ctx                               context.Context
	cancel                            context.CancelFunc
	done                              chan struct{}
	ready                             chan struct{}
	pausedAt, interactedAt, endedAt   time.Time
	playlist                          []byte
	family                            *liveFamily
	input                             io.ReadCloser
	profile                           *LiveProfile
	sourceInfo                        SourceInfo
	parentID                          string
	auto                              *liveAuto
	outputAt                          atomic.Int64
	encoderSpeed                      atomic.Uint64
	encoderProgressAt                 atomic.Int64
}
type SessionOptions struct {
	FFmpeg, FFprobe, Dir string
	ShareSources         bool
	Clock                func() time.Time // deterministic Automatic policy tests; nil uses time.Now
	Validate             func(context.Context, string) (Owner, error)
	OnAdmit              func(context.Context, string, string) error      // session ID, quality
	OnAdmitDemand        func(context.Context, string, int64, bool) error // Automatic child, total bits/s and remote flag
	OnEnd                func(string)
	Log                  *slog.Logger
}

func (m *Sessions) now() time.Time {
	if m.opts.Clock != nil {
		return m.opts.Clock()
	}
	return time.Now()
}

type Sessions struct {
	sharing       func(string) bool
	openRecording func(context.Context, string) (io.ReadCloser, error)
	joinSource    func(context.Context, string, string) (io.ReadCloser, error)
	// warnings are the recordings about to take a tuner (RecordingWarnings).
	warnings   []RecordingWarning
	service    *Service
	playback   *playback.Manager
	opts       SessionOptions
	ctx        context.Context
	cancel     context.CancelFunc
	mu         sync.Mutex
	items      map[string]*session
	requests   map[string]string
	outputs    map[string]*sharedLiveOutput
	closed     bool
	startOrder uint64
	wg         sync.WaitGroup
}

func NewSessions(service *Service, manager *playback.Manager, opts SessionOptions) *Sessions {
	if opts.Log == nil {
		opts.Log = slog.New(slog.DiscardHandler)
	}
	ctx, cancel := context.WithCancel(context.Background())
	m := &Sessions{service: service, playback: manager, opts: opts, ctx: ctx, cancel: cancel, items: map[string]*session{}, requests: map[string]string{}, outputs: map[string]*sharedLiveOutput{}}
	service.changed = m.StopMatching
	service.viewers = m
	return m
}

func (m *Sessions) Start(ctx context.Context, owner Owner, channelID, quality, requestID, clientName string) (SessionView, error) {
	return m.StartWithProfile(ctx, owner, channelID, quality, requestID, clientName, nil)
}

// StartWithProfile allows API-level-25 clients to opt into live source copy.
// Without a profile the stable fixed-quality conversion remains in force.
func (m *Sessions) StartWithProfile(ctx context.Context, owner Owner, channelID, quality, requestID, clientName string, profile *LiveProfile) (SessionView, error) {
	if err := validateLiveProfile(profile); err != nil {
		return SessionView{}, err
	}
	if m.opts.ShareSources {
		return m.startShared(ctx, owner, channelID, quality, requestID, clientName, profile, false, "", 0, false, "")
	}
	if quality == "" {
		quality = "1080"
	}
	if quality != "1080" && quality != "720" && quality != "480" && quality != "360" {
		return SessionView{}, ErrQuality
	}
	if owner.SignInID == "" || requestID == "" || len(requestID) > 64 || len(clientName) > 128 {
		return SessionView{}, ErrOwner
	}
	m.service.mu.Lock()
	defer m.service.mu.Unlock()
	if !m.service.Allowed(ctx, owner.Admin) {
		return SessionView{}, ErrDisabled
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return SessionView{}, playback.ErrStopped
	}
	key := owner.SignInID + "/" + requestID
	if id, ok := m.requests[key]; ok {
		s := m.items[id]
		if s.view.Channel.ID != channelID || s.view.Quality != quality || !reflect.DeepEqual(s.profile, profile) {
			return SessionView{}, ErrRequest
		}
		return m.viewLocked(s, time.Now()), nil
	}
	if len(m.items) >= 2048 {
		return SessionView{}, playback.ErrTooManySessions
	}
	channel, err := m.service.q.GetLiveChannel(ctx, channelID)
	if err != nil {
		return SessionView{}, err
	}
	if channel.Enabled == 0 || channel.Available == 0 || channel.Protected != 0 {
		return SessionView{}, ErrDisabled
	}
	source, err := m.service.q.GetLiveSource(ctx, channel.SourceID)
	if err != nil {
		return SessionView{}, err
	}
	if source.Enabled == 0 {
		return SessionView{}, ErrDisabled
	}
	n := int64(0)
	own := 0
	for _, s := range m.items {
		if s.owner.SignInID == owner.SignInID && s.parentID == "" {
			own++
		}
		if s.sourceID == source.ID && s.endedAt.IsZero() && !s.shared {
			n++
		}
	}
	if own >= 64 {
		return SessionView{}, playback.ErrTooManySessions
	}
	for _, srcID := range m.service.recorders {
		if srcID == source.ID {
			n++
		}
	}
	shared := m.sharing != nil && m.sharing(channelID)
	if !shared && n >= source.TunerCount {
		return SessionView{}, hdhomerun.Busy
	}
	if err := m.playback.CheckStorage(); err != nil {
		return SessionView{}, err
	}
	id := newID()
	if m.opts.OnAdmit != nil {
		if err := m.opts.OnAdmit(ctx, id, quality); err != nil {
			return SessionView{}, err
		}
	}
	now := time.Now()
	m.startOrder++
	runCtx, cancel := context.WithCancel(m.ctx)
	s := &session{shared: shared, owner: owner, requestID: requestID, sourceID: source.ID, address: source.Address, deviceID: source.DeviceID, path: channel.StreamPath, ctx: runCtx, cancel: cancel, done: make(chan struct{}), ready: make(chan struct{}), interactedAt: now, profile: profile}
	s.startOrder = m.startOrder
	s.view = SessionView{ID: id, Channel: Channel{ID: channel.ID, SourceID: source.ID, Number: channel.Number, Name: channel.Name, Enabled: true, Available: true}, State: "starting", Quality: quality, UserID: owner.UserID, UserName: owner.Name, ClientName: clientName, StartedAt: now, LastSeenAt: now, BufferSeconds: SegmentSeconds * WindowSegments}
	s.view.URL = "/api/live-tv/sessions/" + s.view.ID + "/hls/live.m3u8"
	m.items[s.view.ID] = s
	m.requests[key] = s.view.ID
	// A viewer who starts while a recording is about to take a tuner is
	// warned like the others: the newest viewer is the one who gives way.
	m.warnLocked(now, false)
	m.wg.Go(func() { m.run(s) })
	return m.viewLocked(s, now), nil
}

// StartAutomatic is an explicit API-level-26 opt-in. Keeping an anchor
// subscriber while output generations overlap requires the shared source
// owner; servers where that staged feature is off continue fixed Live TV.
func (m *Sessions) StartAutomatic(ctx context.Context, owner Owner, channelID, requestID, clientName string, profile *LiveProfile, initialBps int64, remote bool) (SessionView, error) {
	if profile == nil || validateLiveProfile(profile) != nil || initialBps < 0 || initialBps > 1_000_000_000_000 {
		return SessionView{}, ErrProfile
	}
	if !m.SupportsAutomatic() {
		return SessionView{}, ErrQuality
	}
	return m.startShared(ctx, owner, channelID, playback.QualityAuto, requestID, clientName, profile, true, "", initialBps, remote, "")
}

func (m *Sessions) SupportsAutomatic() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.opts.ShareSources && m.joinSource != nil && !m.closed
}

func (m *Sessions) viewLocked(s *session, now time.Time) SessionView {
	v := s.view
	if s.output != nil && s.endedAt.IsZero() && s.ctx.Err() == nil {
		p := s.output.producer
		v.Mode, v.ModeReason = p.view.Mode, p.view.ModeReason
		v.BufferBytes = p.view.BufferBytes
		if p.family != nil {
			v.Adaptive = p.family.offer()
		}
	}
	v.PauseRemainingSeconds = int(PauseAllowance.Seconds())
	if !s.pausedAt.IsZero() {
		v.PauseRemainingSeconds = max(0, int((PauseAllowance - now.Sub(s.pausedAt)).Seconds()))
	}
	return v
}

// authorized checks current account, source and channel policy, in addition
// to the original sign-in identity. It never extends the sign-in's expiry.
func (m *Sessions) authorized(ctx context.Context, s *session) bool {
	owner, err := m.opts.Validate(ctx, s.owner.SignInID)
	if err != nil || owner.UserID != s.owner.UserID || !m.service.Allowed(ctx, owner.Admin) {
		return false
	}
	source, err := m.service.q.GetLiveSource(ctx, s.sourceID)
	if err != nil || source.Enabled == 0 || source.DeviceID != s.deviceID || source.Address != s.address {
		return false
	}
	channel, err := m.service.q.GetLiveChannel(ctx, s.view.Channel.ID)
	// A failed refresh doesn't affect a healthy stream. A missing lineup
	// channel can finish its current stream; disabling/protection revokes it.
	return err == nil && channel.Enabled != 0 && channel.Protected == 0
}
func (m *Sessions) find(owner Owner, id string) (*session, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.items[id]
	if !ok || owner.SignInID != s.owner.SignInID || owner.UserID != s.owner.UserID {
		return nil, ErrOwner
	}
	return s, nil
}
func (m *Sessions) Status(ctx context.Context, owner Owner, id string) (SessionView, error) {
	s, err := m.find(owner, id)
	if err != nil {
		return SessionView{}, err
	}
	if !m.authorized(ctx, s) {
		m.stop(s, "access_revoked")
		return SessionView{}, ErrDisabled
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.viewLocked(s, time.Now()), nil
}
func (m *Sessions) Heartbeat(ctx context.Context, owner Owner, id string, paused, interacted bool) (SessionView, error) {
	if _, err := m.Status(ctx, owner, id); err != nil {
		return SessionView{}, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	s := m.items[id]
	if s == nil {
		return SessionView{}, ErrOwner
	}
	now := time.Now()
	if s.endedAt.IsZero() && s.view.State != "stopping" {
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
	return m.viewLocked(s, now), nil
}
func (m *Sessions) Stop(owner Owner, id string) error {
	s, err := m.find(owner, id)
	if err != nil {
		return err
	}
	m.stop(s, "stopped")
	<-s.done
	awaitSourceRelease(s)
	return nil
}
func (m *Sessions) AdminStop(id string) error {
	m.mu.Lock()
	s := m.items[id]
	m.mu.Unlock()
	if s == nil {
		return ErrOwner
	}
	m.stop(s, "stopped_by_admin")
	<-s.done
	awaitSourceRelease(s)
	return nil
}

// StopForStorage releases a rolling HLS session selected by the shared
// temporary-storage monitor. The live session retains its terminal reason.
func (m *Sessions) StopForStorage(id string) {
	m.mu.Lock()
	s := m.items[id]
	if s == nil {
		for _, output := range m.outputs {
			if output.producer.view.ID == id {
				s = output.producer
				break
			}
		}
	}
	m.mu.Unlock()
	if s == nil {
		return
	}
	m.stop(s, "storage_limit")
	<-s.done
	awaitSourceRelease(s)
}

func awaitSourceRelease(s *session) {
	if reader, ok := s.input.(interface{ WaitReleased() }); ok {
		reader.WaitReleased()
	}
}
func (m *Sessions) stop(s *session, reason string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s.endedAt.IsZero() && s.view.State != "stopping" && s.view.State != "failed" {
		s.view.State = "stopping"
		s.view.Reason = reason
		s.cancel()
	}
}
func (m *Sessions) StopMatching(source, channel, reason string) {
	m.mu.Lock()
	var items []*session
	for _, s := range m.items {
		if (source == "" || s.sourceID == source) && (channel == "" || s.view.Channel.ID == channel) {
			items = append(items, s)
		}
	}
	m.mu.Unlock()
	for _, s := range items {
		m.stop(s, reason)
	}
	for _, s := range items {
		<-s.done
	}
}
func (m *Sessions) Activity(owner Owner, admin bool) []SessionView {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []SessionView{}
	for _, s := range m.items {
		if s.parentID == "" && s.endedAt.IsZero() && (admin || s.owner.SignInID == owner.SignInID) {
			out = append(out, m.viewLocked(s, time.Now()))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.Before(out[j].StartedAt) })
	return out
}

// Media opens only completed playlist/segment names, checking ownership and
// current policy for every request, including a cached playlist's children.
func (m *Sessions) Media(ctx context.Context, owner Owner, id, name string) (io.ReadSeekCloser, error) {
	if name != "live.m3u8" && name != "init.mp4" && !mediaName.MatchString(name) && !liveFamilyName(name) {
		return nil, ErrOwner
	}
	s, err := m.find(owner, id)
	if err != nil {
		return nil, err
	}
	if !m.authorized(ctx, s) {
		m.stop(s, "access_revoked")
		return nil, ErrDisabled
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if s.view.State != "playing" || s.ctx.Err() != nil {
		return nil, ErrOwner
	}
	output := s
	if s.output != nil {
		output = s.output.producer
		if output.view.State != "playing" || output.ctx.Err() != nil {
			return nil, ErrOwner
		}
	}
	if liveFamilyName(name) {
		if output.family == nil {
			return nil, ErrOwner
		}
		if b, ok := output.family.playlists[name]; ok {
			s.view.LastSeenAt = time.Now()
			return playlistReader{bytes.NewReader(b)}, nil
		}
		if strings.HasSuffix(name, ".m3u8") {
			return nil, ErrOwner
		}
	}
	if name == "live.m3u8" {
		s.view.LastSeenAt = time.Now()
		return playlistReader{bytes.NewReader(output.playlist)}, nil
	}
	dir := filepath.Join(m.opts.Dir, output.view.ID)
	f, err := playback.OpenShared(filepath.Join(dir, name))
	if err != nil {
		return nil, err
	}
	s.view.LastSeenAt = time.Now()
	return &segmentReader{File: f, closed: func() {
		m.mu.Lock()
		ended := !output.endedAt.IsZero()
		m.mu.Unlock()
		if ended {
			// Windows may defer deleting a held segment until its reader
			// closes. The final reader finishes cleanup without a timer.
			_ = m.playback.RemoveOutputDir(dir)
		}
	}}, nil
}

type playlistReader struct{ *bytes.Reader }

func (playlistReader) Close() error { return nil }

type segmentReader struct {
	*os.File
	closed func()
	once   sync.Once
	err    error
}

func (r *segmentReader) Close() error {
	r.once.Do(func() {
		r.err = r.File.Close()
		r.closed()
	})
	return r.err
}

func (m *Sessions) Run(ctx context.Context) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			m.Close()
			return
		case now := <-ticker.C:
			m.sweep(now)
		}
	}
}
func (m *Sessions) sweep(now time.Time) {
	m.mu.Lock()
	var items []*session
	for id, s := range m.items {
		if !s.endedAt.IsZero() {
			if now.Sub(s.endedAt) > terminalRetention {
				delete(m.items, id)
				delete(m.requests, s.owner.SignInID+"/"+s.requestID)
			}
		} else {
			items = append(items, s)
		}
	}
	m.mu.Unlock()
	for _, s := range items {
		m.mu.Lock()
		reason := ""
		switch {
		case now.Sub(s.view.LastSeenAt) > Lease:
			reason = "lease_expired"
		case !s.pausedAt.IsZero() && now.Sub(s.pausedAt) > PauseAllowance:
			reason = "pause_expired"
		case now.Sub(s.interactedAt) > InactiveAllowance:
			reason = "inactive"
		}
		m.mu.Unlock()
		if reason == "" && !m.authorized(m.ctx, s) {
			reason = "access_revoked"
		}
		if reason != "" {
			m.stop(s, reason)
		}
	}
}
func (m *Sessions) Close() {
	m.mu.Lock()
	m.closed = true
	m.cancel()
	m.mu.Unlock()
	m.StopMatching("", "", "server_stopped")
	m.wg.Wait()
}

func (m *Sessions) fail(s *session, reason string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s.view.State != "stopping" && s.view.State != "failed" {
		s.view.State = "failed"
		s.view.Reason = reason
	}
	s.cancel()
}

type streamReader struct {
	io.Reader
	ended atomic.Bool
}

func (r *streamReader) Read(p []byte) (int, error) {
	n, err := r.Reader.Read(p)
	if err != nil {
		r.ended.Store(true)
	}
	return n, err
}

// ffmpeg progress is separate from HTTP delivery. A slow encoder or missing
// source segments must not make a viewer's measured link look slow or fast.
type liveProgressWriter struct {
	s       *session
	partial []byte
}

func (w *liveProgressWriter) Write(b []byte) (int, error) {
	data := append(w.partial, b...)
	for {
		i := bytes.IndexByte(data, '\n')
		if i < 0 {
			break
		}
		line := strings.TrimSpace(string(data[:i]))
		if value, ok := strings.CutPrefix(line, "speed="); ok {
			if speed, err := strconv.ParseFloat(strings.TrimSuffix(value, "x"), 64); err == nil &&
				speed > 0 && !math.IsNaN(speed) && !math.IsInf(speed, 0) {
				w.s.encoderSpeed.Store(math.Float64bits(speed))
				w.s.encoderProgressAt.Store(time.Now().UnixNano())
			}
		}
		data = data[i+1:]
	}
	// FFmpeg normally emits short lines. Malformed or unbounded output cannot
	// grow session memory; the next complete line can still be parsed.
	if len(data) > 256 {
		data = nil
	}
	w.partial = append(w.partial[:0], data...)
	return len(b), nil
}

func (m *Sessions) run(s *session) {
	// Bound the whole tune, including encoder detection and upstream headers,
	// rather than starting the deadline only after ffmpeg has launched.
	startup := time.AfterFunc(time.Until(s.view.StartedAt.Add(TuneTimeout)), func() {
		m.mu.Lock()
		defer m.mu.Unlock()
		if s.view.State == "starting" {
			s.view.State = "failed"
			s.view.Reason = "stream_stalled"
			s.cancel()
		}
	})
	defer startup.Stop()
	dir := filepath.Join(m.opts.Dir, s.view.ID)
	defer func() {
		// Admission may fail before body is assigned. The joined shared-source
		// subscriber still belongs to this output and must be released.
		if s.input != nil {
			_ = s.input.Close()
		}
		if !s.producer && m.opts.OnEnd != nil {
			m.opts.OnEnd(s.view.ID)
		}
		s.cancel()
		m.mu.Lock()
		if s.view.State != "failed" {
			s.view.State = "stopped"
		}
		s.endedAt = time.Now()
		if s.producer {
			// Publish the terminal cause before closing the producer's done channel.
			// A caller stopping its viewer after storage cleanup must not race the
			// observer goroutine and replace the failure with an ordinary stop.
			for _, viewer := range m.items {
				if viewer.output != nil && viewer.output.producer == s && viewer.ctx.Err() == nil && viewer.endedAt.IsZero() {
					viewer.view.State, viewer.view.Reason = "failed", s.view.Reason
					viewer.cancel()
				}
			}
		}
		s.playlist = nil
		s.family = nil
		pending := len(m.warnings) > 0
		m.mu.Unlock()
		_ = m.playback.RemoveOutputDir(dir)
		close(s.done)
		if pending {
			// Whoever was warned has gone: warn the viewer who now gives way.
			m.rewarn()
		}
	}()
	release, err := m.playback.ReserveLive(s.ctx)
	if err != nil {
		reason := "server_busy"
		if errors.Is(err, playback.ErrStorageBudget) {
			reason = "storage_limit"
		}
		m.fail(s, reason)
		return
	}
	defer release()
	if s.outputKind == "ladder" {
		releaseRung, err := m.playback.ReserveLive(s.ctx)
		if err != nil {
			reason := "server_busy"
			if errors.Is(err, playback.ErrStorageBudget) {
				reason = "storage_limit"
			}
			m.fail(s, reason)
			return
		}
		defer releaseRung()
	}
	if !s.producer && !m.authorized(s.ctx, s) {
		m.fail(s, "access_revoked")
		return
	}
	var body io.ReadCloser
	if s.input != nil {
		body = s.input
	} else if s.shared {
		body, err = m.openRecording(s.ctx, s.view.Channel.ID)
	} else {
		body, err = m.service.openTuner(s.ctx, s.address, s.deviceID, s.path)
	}
	if err != nil {
		if reason, ok := errors.AsType[hdhomerun.StreamError](err); ok {
			m.fail(s, string(reason))
		} else {
			m.fail(s, "tuner_unavailable")
		}
		return
	}
	if s.profile != nil {
		if _, ok := body.(interface {
			Inspect(context.Context, string) (SourceInfo, error)
		}); !ok {
			body = newInspectedTuner(body)
		}
	}
	defer func() { _ = body.Close() }()
	closeOnCancel := context.AfterFunc(s.ctx, func() { _ = body.Close() })
	defer closeOnCancel()
	plan := chooseLivePlan(s.profile, SourceInfo{}, s.view.Quality)
	inspected := SourceInfo{}
	if s.profile != nil {
		if inspector, ok := body.(interface {
			Inspect(context.Context, string) (SourceInfo, error)
		}); ok {
			if source, err := inspector.Inspect(s.ctx, m.opts.FFprobe); err == nil {
				inspected = source
				plan = chooseLivePlan(s.profile, source, s.view.Quality)
			}
		}
	}
	if s.ctx.Err() != nil {
		return
	}
	if s.producer && inspected.VideoCodec == "" {
		m.fail(s, "source_unsupported")
		return
	}
	var family *liveFamily
	if s.outputKind == "ladder" {
		family, err = newLiveFamily(inspected, s.profile, s.view.Quality)
		if err != nil {
			m.fail(s, "source_unsupported")
			return
		}
		plan = LivePlan{Mode: "video", Reason: "adaptive_family", SegmentFormat: "fmp4"}
	}
	m.mu.Lock()
	s.family = family
	s.sourceInfo = inspected
	s.view.Mode, s.view.ModeReason = plan.Mode, plan.Reason
	m.mu.Unlock()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		m.fail(s, "storage_error")
		return
	}
	var caps playback.Capabilities
	if plan.Mode == "video" {
		caps = m.playback.CapabilitiesContext(s.ctx)
		if s.ctx.Err() != nil {
			return
		}
		if plan.hdrType != "" && (plan.hdrType == "unknown" || !caps.CPUToneMap()) {
			m.fail(s, "source_unsupported")
			return
		}
	}
	encoder := "libx264"
	if !s.producer {
		encoder = selectLiveEncoder(s.ctx, m.opts.FFmpeg, caps, s.profile != nil)
	}
	step := playback.StepByID(s.view.Quality)
	if s.producer && s.profile != nil {
		if s.profile.MaxHeight > 0 {
			step.MaxHeight = min(step.MaxHeight, s.profile.MaxHeight)
		}
		if s.profile.MaxBitrate > 0 {
			step.MaxBitrate = min(step.MaxBitrate, s.profile.MaxBitrate)
		}
	}
	deviceIndex := caps.Device
	if encoder != caps.Encoder {
		deviceIndex = nil
	}
	args := HLSArgsForPlan(dir, plan, encoder, step.MaxHeight, int(step.MaxBitrate/1000), deviceIndex)
	if family != nil {
		args = family.args(dir)
	}
	if s.parentID != "" || s.producer {
		args = append(args[:len(args)-1], "-progress", "pipe:1", "-stats_period", "0.5", "-nostats", args[len(args)-1])
	}
	cmd := ffexec.Command(s.ctx, m.opts.FFmpeg, args...)
	if s.parentID != "" || s.producer {
		cmd.Stdout = &liveProgressWriter{s: s}
	}
	reader := &streamReader{Reader: body}
	input, err := cmd.StdinPipe()
	if err != nil {
		m.fail(s, "encoder_failed")
		return
	}
	defer func() { _ = input.Close() }()
	if err := cmd.Start(); err != nil {
		m.opts.Log.Error("live encoder could not start", "session", s.view.ID, "encoder", encoder, "err", err)
		m.fail(s, "encoder_failed")
		return
	}
	// A tuner can stall just as ffmpeg exits. Feeding through exec.Cmd.Stdin
	// makes Wait wait for its blocked copy goroutine, delaying tuner release.
	// Own the copier so process exit closes the upstream and joins both sides.
	copied := make(chan struct{})
	go func() {
		if _, err := io.Copy(input, reader); errors.Is(err, errCaptureChanged) {
			m.opts.Log.Warn("live output stopped after capture generation changed", "session", s.view.ID)
		} else if err != nil && s.producer && s.ctx.Err() == nil {
			m.opts.Log.Warn("private live encoder input ended", "session", s.view.ID,
				"encoder_speed", math.Float64frombits(s.encoderSpeed.Load()),
				"progress_age", time.Since(time.Unix(0, s.encoderProgressAt.Load())))
		}
		_ = input.Close()
		close(copied)
	}()
	done := make(chan string, 1)
	go func() {
		err := cmd.Wait()
		// Keep bounded ffmpeg diagnostics in server logs, never public reasons.
		// The encoder sees pipe:0, not the tuner's address or credentials.
		if err != nil && s.ctx.Err() == nil {
			m.opts.Log.Error("live encoder failed", "session", s.view.ID, "encoder", encoder, "err", err)
		}
		reason := "encoder_failed"
		if reader.ended.Load() {
			reason = "signal_lost"
		}
		_ = body.Close()
		_ = input.Close()
		<-copied
		done <- reason
	}()
	ticker := time.NewTicker(250 * time.Millisecond)
	defer ticker.Stop()
	lastOutput := time.Now()
	lastPlaylist := ""
	lastDisk := time.Time{}
	lastDiagnostic := time.Time{}
	for {
		select {
		case <-s.ctx.Done():
			_ = body.Close()
			<-done
			return
		case reason := <-done:
			m.fail(s, reason)
			return
		case now := <-ticker.C:
			b, err := takePlaylist(dir)
			if family != nil {
				b, err = family.update(dir)
				if err != nil {
					m.opts.Log.Warn("live family stopped before publishing invalid output", "session", s.view.ID, "err", err,
						"encoder_speed", math.Float64frombits(s.encoderSpeed.Load()), "stderr", cmd.Tail())
					m.fail(s, "ladder_not_ready")
					continue
				}
				if now.Sub(lastDiagnostic) >= 15*time.Second && s.encoderProgressAt.Load() != 0 {
					lastDiagnostic = now
					m.opts.Log.Debug("private live encoder progress", "session", s.view.ID,
						"encoder_speed", math.Float64frombits(s.encoderSpeed.Load()),
						"progress_age", time.Since(time.Unix(0, s.encoderProgressAt.Load())))
				}
			}
			if err == nil && (strings.Contains(string(b), "#EXTINF:") || family != nil && len(b) > 0) && (string(b) != lastPlaylist || family != nil && family.advanced) {
				lastOutput = now
				s.outputAt.Store(now.UnixNano())
				lastPlaylist = string(b)
				m.mu.Lock()
				s.playlist = b
				if family != nil {
					family.playlists = family.next
				}
				if s.view.State == "starting" && (family != nil || liveStartupReady(b)) {
					s.view.State = "playing"
					startup.Stop()
					s.view.ReadyAt = &now
					close(s.ready)
				}
				m.mu.Unlock()
			}
			outputTimeout := TuneTimeout
			if family != nil && lastPlaylist != "" {
				outputTimeout = 3 * SegmentSeconds * time.Second
			}
			if now.Sub(lastOutput) > outputTimeout {
				m.fail(s, "stream_stalled")
			}
			if now.Sub(lastDisk) >= time.Second {
				size, err := directoryBytes(dir)
				lastDisk = now
				m.mu.Lock()
				s.view.BufferBytes = size
				m.mu.Unlock()
				if err != nil || size > DiskLimit {
					m.fail(s, "storage_limit")
				}
			}
		}
	}
}

// Negotiating clients only use hardware after the actual interlaced live HLS
// pipeline has encoded faster than real time on the selected adapter. Legacy
// sessions retain their previous encoder selection.
func selectLiveEncoder(ctx context.Context, ffmpeg string, caps playback.Capabilities, negotiated bool) string {
	encoder := caps.Encoder
	// These encoders accept software frames after broadcast deinterlacing.
	// VAAPI/QSV need a different upload pipeline; use the known CPU path.
	switch encoder {
	case "h264_videotoolbox", "h264_nvenc", "h264_amf":
		if negotiated && !liveEncoderWorks(ctx, ffmpeg, caps) {
			return "libx264"
		}
		return encoder
	default:
		return "libx264"
	}
}
func directoryBytes(dir string) (int64, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0, err
	}
	var total int64
	for _, e := range entries {
		info, err := e.Info()
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return 0, err
		}
		total += info.Size()
	}
	return total, nil
}

// WaitReady waits for a published startup buffer or a terminal
// startup outcome. HTTP creation remains asynchronous; fixture tools can
// use this without polling or opening a second tuner for a probe.
func (m *Sessions) WaitReady(ctx context.Context, owner Owner, id string) (SessionView, error) {
	s, err := m.find(owner, id)
	if err != nil {
		return SessionView{}, err
	}
	select {
	case <-ctx.Done():
		return SessionView{}, ctx.Err()
	case <-s.ready:
	case <-s.done:
	}
	return m.Status(ctx, owner, id)
}

// WaitEnded joins a terminal live outcome for fixture recovery checks. It does
// not change ownership or stop an otherwise healthy viewer.
func (m *Sessions) WaitEnded(ctx context.Context, owner Owner, id string) (SessionView, error) {
	s, err := m.find(owner, id)
	if err != nil {
		return SessionView{}, err
	}
	select {
	case <-ctx.Done():
		return SessionView{}, ctx.Err()
	case <-s.done:
	}
	return m.Status(ctx, owner, id)
}
