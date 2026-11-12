package activity

import (
	"context"
	"database/sql"
	"log/slog"
	"net/netip"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
)

// Sessions is the part of the playback manager the tracker uses: transcoder
// progress for Now playing, and stopping a play's ffmpeg.
type Sessions interface {
	Get(id string) (*playback.Session, bool)
	StopPlay(playID string, userID int64)
	// ReleasePlay returns capacity an ended play reserved but no longer needs.
	ReleasePlay(playID string, userID int64)
	Stop(id string)
}

// Options configure a Tracker. Everything is optional.
type Options struct {
	Sessions Sessions
	// UpgradeReady is a fast, nonblocking budget observation. Final admission
	// still happens atomically when the new playback request arrives.
	UpgradeReady func(playID string, remote bool, bitrate int64) bool
	Config       Config
	// OnEnd releases resources reserved for a play. It runs outside t.mu.
	OnEnd func(playID string)
	// Location is the time zone for days and hours in charts; the server's by default.
	Location *time.Location
	// Now is the clock, for tests.
	Now func() time.Time
}

// Tracker keeps the live plays in memory and writes them to the plays
// table: at start, on every state change and every 30 seconds, so a crash
// loses at most that much. Plays left open by a crash are closed at
// startup, at their last report.
type Tracker struct {
	db           *sql.DB
	bus          *events.Bus
	log          *slog.Logger
	sessions     Sessions
	loc          *time.Location
	now          func() time.Time
	onEnd        func(playID string)
	upgradeReady func(playID string, remote bool, bitrate int64) bool
	// Serializes upgrade proposals across file plays. Protected by mu.
	nextUpgradeAt time.Time

	mu sync.Mutex
	// Orders registration of a live play against account deletion cleanup.
	userMu     sync.RWMutex
	cfg        Config
	plays      map[string]*live
	terminated map[string]termination
	// links holds each device's measured link per address, for where its
	// next Automatic play starts.
	links map[string]deviceLink

	// version changes whenever history does, so cached statistics expire.
	version atomic.Int64
	cache   statsCache
}

type termination struct {
	message string
	at      time.Time
}

// New makes a tracker. conn may be nil, in which case nothing is recorded
// and only Now playing works.
func New(conn *sql.DB, bus *events.Bus, log *slog.Logger, opts Options) *Tracker {
	if log == nil {
		log = slog.Default()
	}
	if opts.Location == nil {
		opts.Location = time.Local
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	return &Tracker{
		db: conn, bus: bus, log: log, sessions: opts.Sessions, loc: opts.Location, now: opts.Now, onEnd: opts.OnEnd, upgradeReady: opts.UpgradeReady,
		cfg: opts.Config, plays: map[string]*live{}, terminated: map[string]termination{}, links: map[string]deviceLink{},
	}
}

// Config returns the settings in force.
func (t *Tracker) Config() Config {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.cfg
}

// SetConfig applies changed settings to plays that end from now on.
func (t *Tracker) SetConfig(c Config) {
	t.mu.Lock()
	t.cfg = c
	t.mu.Unlock()
	t.version.Add(1)
}

// live is a play in progress.
type live struct {
	id       string
	row      int64 // plays.id; 0 when the user's history is off
	userID   int64
	userName string
	device   Device
	ip       netip.Addr
	network  string
	fileID   int64
	item     Item
	stream   Stream

	state          string
	startedAt      time.Time
	lastSeen       time.Time
	stateAt        time.Time // when time was last counted towards a state
	playedMs       int64
	pausedMs       int64
	bufferingMs    int64
	startPos       int64
	pos            int64
	maxPos         int64
	bufferedMs     int64
	durationMs     int64
	watchedAtMs    int64
	reported       bool
	completed      bool
	bytes          int64
	pacedUntil     time.Time
	paceGeneration uint64
	rate           rate
	auto           autoPlay

	lastWrite   time.Time
	lastPublish time.Time
}

// apply counts the time since the last report towards the state the play
// was in, then takes the new report. Time counts only up to twice the
// reporting interval, so a tab that went quiet cannot pile it up.
func (p *live) apply(now time.Time, r Report) (changed bool) {
	elapsed := now.Sub(p.stateAt)
	limit := 2 * playInterval
	if p.state != StatePlaying {
		limit = 2 * pauseInterval
	}
	elapsed = min(max(elapsed, 0), limit)
	switch p.state {
	case StatePlaying:
		p.playedMs += elapsed.Milliseconds()
	case StatePaused:
		p.pausedMs += elapsed.Milliseconds()
	case StateBuffering:
		p.bufferingMs += elapsed.Milliseconds()
	}
	p.stateAt, p.lastSeen = now, now
	if r.PositionMs >= 0 {
		if !p.reported {
			p.startPos = r.PositionMs
		}
		p.pos = r.PositionMs
		p.maxPos = max(p.maxPos, r.PositionMs)
	}
	p.reported = true
	p.bufferedMs = r.BufferedMs
	if p.watchedAtMs > 0 && p.pos >= p.watchedAtMs {
		p.completed = true
	}
	switch r.State {
	case StatePlaying, StatePaused, StateBuffering:
		if r.State != p.state {
			p.state = r.State
			return true
		}
	}
	return false
}

// position estimates where a playing play is now, between reports.
func (p *live) position(now time.Time) int64 {
	pos := p.pos
	if p.state == StatePlaying {
		pos += min(now.Sub(p.stateAt), 2*playInterval).Milliseconds()
	}
	if p.durationMs > 0 {
		pos = min(pos, p.durationMs)
	}
	return pos
}

// Start is a new play.
type Start struct {
	// ID is the play's ID, made by the caller so a stream can be started
	// under it first; empty makes one.
	ID       string
	UserID   int64
	UserName string
	// RecordHistory is the user's switch; off, the play shows in Now
	// playing but is not written.
	RecordHistory bool
	Device        Device
	IP            netip.Addr
	FileID        int64
	Item          Item
	DurationMs    int64
	WatchedAtMs   int64
	PositionMs    int64
	Stream        Stream
	// Continues names a play that ended (it timed out, or the server
	// restarted) whose player is still going; when Stream is empty the new
	// play takes that one's stream details.
	Continues string
}

// Begin starts a play and returns its ID.
func (t *Tracker) Begin(ctx context.Context, s Start) (string, error) {
	t.userMu.RLock()
	defer t.userMu.RUnlock()
	if t.db != nil {
		var id int64
		if err := t.db.QueryRowContext(ctx, "SELECT id FROM users WHERE id = ?", s.UserID).Scan(&id); err != nil {
			return "", err
		}
	}
	now := t.now()
	if s.Stream.Mode == "" && s.Continues != "" {
		s.Stream = t.lastStream(ctx, s.Continues, s.UserID)
	}
	if s.Stream.Mode == "" {
		s.Stream.Mode = string(playback.ModeDirect)
	}
	if s.Stream.Reasons == nil {
		s.Stream.Reasons = []string{}
	}
	if s.ID == "" {
		s.ID = playback.NewPlayID()
	}
	p := &live{
		id: s.ID, userID: s.UserID, userName: s.UserName, device: s.Device, ip: s.IP.Unmap(),
		network: netguard.Classify(s.IP), fileID: s.FileID, item: s.Item, stream: s.Stream,
		state: StateBuffering, startedAt: now, lastSeen: now, stateAt: now,
		startPos: s.PositionMs, pos: s.PositionMs, maxPos: s.PositionMs, durationMs: s.DurationMs, watchedAtMs: s.WatchedAtMs,
		lastWrite: now, lastPublish: now,
	}
	t.mu.Lock()
	link := t.link(p.device.Key, p.ip, now)
	t.mu.Unlock()
	p.startAuto(now, link)
	if s.RecordHistory && t.db != nil {
		row, device, err := t.insert(ctx, p)
		if err != nil {
			return "", err
		}
		p.row, p.device.ID = row, device
	}
	t.mu.Lock()
	t.plays[p.id] = p
	t.mu.Unlock()
	t.publish("activity.started", p)
	return p.id, nil
}

// Continue records a new playback decision for a running play: a seek,
// track or quality change that restarted the stream.
func (t *Tracker) Continue(ctx context.Context, playID string, userID, fileID int64, s Stream, durationMs, watchedAtMs int64) error {
	return t.ContinueWithSwitch(ctx, playID, userID, fileID, s, durationMs, watchedAtMs, 0)
}

// ContinueWithSwitch commits a negotiated decision only while its directive
// is current. The old session remains available until first-frame ack.
func (t *Tracker) ContinueWithSwitch(ctx context.Context, playID string, userID, fileID int64, s Stream, durationMs, watchedAtMs int64, generation uint64) error {
	t.mu.Lock()
	if term, ok := t.terminated[playID]; ok {
		t.mu.Unlock()
		return &TerminatedError{Message: term.message}
	}
	p, ok := t.plays[playID]
	if !ok || p.userID != userID || p.fileID != fileID {
		t.mu.Unlock()
		return ErrUnknownPlay
	}
	if generation != 0 && (!p.auto.recovery || p.auto.pending == nil || p.auto.pending.Generation != generation || p.auto.pending.Quality != s.Step) {
		t.mu.Unlock()
		return ErrStaleSwitch
	}
	now := t.now()
	if s.Reasons == nil {
		s.Reasons = []string{}
	}
	old := p.stream
	p.stream, p.durationMs, p.watchedAtMs, p.lastSeen = s, durationMs, watchedAtMs, now
	p.continueAuto(now, old, generation)
	p.lastWrite, p.lastPublish = now, now
	v := p.values()
	t.mu.Unlock()
	t.write(ctx, v)
	t.publish("activity.updated", p)
	return nil
}

// Heartbeat takes a player's report. A stop ends the play. The returned
// directive, if any, tells the player to stop or, at Automatic quality, to
// drop to a lower step.
func (t *Tracker) Heartbeat(ctx context.Context, playID string, userID, fileID int64, r Report) (*Directive, error) {
	speed := t.transcodeSpeed(playID)
	t.mu.Lock()
	if term, ok := t.terminated[playID]; ok {
		t.mu.Unlock()
		return &Directive{Action: "stop", Message: term.message}, nil
	}
	p, ok := t.plays[playID]
	if !ok || p.userID != userID || p.fileID != fileID {
		t.mu.Unlock()
		return nil, ErrUnknownPlay
	}
	now := t.now()
	changed := p.apply(now, r)
	if r.State == StateStopped || r.State == StateClosed {
		delete(t.plays, playID)
		t.mu.Unlock()
		reason := EndStopped
		switch {
		case p.durationMs > 0 && p.pos >= p.durationMs-2000:
			reason = EndEnded
		case r.State == StateClosed:
			reason = EndClosed
		}
		t.end(ctx, p, reason, now, "")
		return nil, nil
	}
	retireSession := ""
	if r.SwitchFailedGeneration != 0 {
		a := &p.auto
		if a.pending != nil && a.pending.Generation == r.SwitchFailedGeneration {
			a.state.MarkFailure(a.pending.Quality, now)
			a.markSwitch(r.SwitchFailedGeneration, "failed")
			a.pending = nil
			changed = true
		} else if a.activeGeneration == r.SwitchFailedGeneration && a.previous != nil {
			a.state.MarkFailure(p.stream.Step, now)
			a.markSwitch(r.SwitchFailedGeneration, "failed")
			oldAvailable := a.previous.SessionID == ""
			if !oldAvailable && t.sessions != nil {
				_, oldAvailable = t.sessions.Get(a.previous.SessionID)
			}
			if oldAvailable {
				retireSession = p.stream.SessionID
				p.stream = *a.previous
				a.state = a.state.NewStream(p.stream.autoStep(), p.stream.Stream.Bitrate, now)
				a.reason = p.stream.StepReason
			} else {
				a.reason = "previous stream expired during quality switch"
			}
			a.previous, a.activeGeneration = nil, 0
			changed = true
		}
	} else if r.SwitchGeneration != 0 && r.State == StatePlaying && p.auto.activeGeneration == r.SwitchGeneration && p.auto.previous != nil {
		retireSession = p.auto.previous.SessionID
		p.auto.markSwitch(r.SwitchGeneration, "applied")
		p.auto.previous, p.auto.activeGeneration = nil, 0
		changed = true
	}
	dir, stepped := t.autoBeat(p, now, r, speed)
	changed = changed || stepped
	var v *values
	if changed || now.Sub(p.lastWrite) >= writeEvery {
		p.lastWrite = now
		x := p.values()
		v = &x
	}
	publish := changed || now.Sub(p.lastPublish) >= publishEvery
	if publish {
		p.lastPublish = now
	}
	t.mu.Unlock()
	if retireSession != "" && t.sessions != nil {
		t.sessions.Stop(retireSession)
	}
	if v != nil {
		t.write(ctx, *v)
	}
	if publish {
		t.publish("activity.updated", p)
	}
	return dir, nil
}

// WatchedAt returns the point a running play counts as watched from and the
// duration it was worked out for, so progress reports need not work it out
// again; ok is false unless the play runs for this user and file.
func (t *Tracker) WatchedAt(playID string, userID, fileID int64) (watchedAtMs, durationMs int64, ok bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	p, ok := t.plays[playID]
	if !ok || p.userID != userID || p.fileID != fileID {
		return 0, 0, false
	}
	return p.watchedAtMs, p.durationMs, true
}

// Known reports whether a play is running for this user and file: nil if
// so, a TerminatedError if an admin stopped it, else ErrUnknownPlay.
func (t *Tracker) Known(playID string, userID, fileID int64) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if term, ok := t.terminated[playID]; ok {
		return &TerminatedError{Message: term.message}
	}
	if p, ok := t.plays[playID]; ok && p.userID == userID && p.fileID == fileID {
		return nil
	}
	return ErrUnknownPlay
}

// Check says whether bytes may still be served to a play: not after an
// admin stopped it.
func (t *Tracker) Check(playID string) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if term, ok := t.terminated[playID]; ok {
		return &TerminatedError{Message: term.message}
	}
	return nil
}

// CheckStream rejects attribution to a different viewer or file. Unknown
// IDs remain playable for older clients resuming after a server restart.
func (t *Tracker) CheckStream(playID string, userID, fileID int64) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if term, ok := t.terminated[playID]; ok {
		return &TerminatedError{Message: term.message}
	}
	if p, ok := t.plays[playID]; ok && (p.userID != userID || p.fileID != fileID) {
		return ErrUnknownPlay
	}
	return nil
}

// ForgetUser follows the durable account deletion. Pending plays may not
// repopulate memory afterwards, and existing streams stop at their next write.
func (t *Tracker) ForgetUser(id int64) {
	t.userMu.Lock()
	defer t.userMu.Unlock()
	t.mu.Lock()
	var stopped []string
	for key, p := range t.plays {
		if p.userID == id {
			delete(t.plays, key)
			t.terminated[key] = termination{message: "Account deleted.", at: t.now()}
			stopped = append(stopped, key)
		}
	}
	t.mu.Unlock()
	if t.onEnd != nil {
		for _, play := range stopped {
			t.onEnd(play)
		}
	}
	if t.sessions != nil {
		for _, play := range stopped {
			t.sessions.StopPlay(play, id)
		}
	}
	t.version.Add(1)
}

// AddBytes counts bytes sent for a play, and reports false once the play
// has been stopped by an admin so the sender can cut the stream.
func (t *Tracker) AddBytes(playID string, n int64) bool {
	now := t.now()
	t.mu.Lock()
	defer t.mu.Unlock()
	if _, ok := t.terminated[playID]; ok {
		return false
	}
	if p, ok := t.plays[playID]; ok {
		p.bytes += n
		p.rate.add(now.Unix(), n)
	}
	return true
}

// MarkServerPaced distinguishes delivery under the configured server
// ceiling from evidence of the viewer's spare connection capacity.
func (t *Tracker) MarkServerPaced(playID string, generation uint64) {
	t.mu.Lock()
	if p := t.plays[playID]; p != nil {
		p.pacedUntil = t.now().Add(35 * time.Second)
		p.paceGeneration = generation
	}
	t.mu.Unlock()
}

// Terminate stops a play for an admin: ffmpeg stops, bytes are refused, and
// the player is told to exit with the message.
func (t *Tracker) Terminate(ctx context.Context, playID, message string) error {
	t.mu.Lock()
	p, ok := t.plays[playID]
	if !ok {
		t.mu.Unlock()
		return ErrUnknownPlay
	}
	now := t.now()
	delete(t.plays, playID)
	t.terminated[playID] = termination{message: message, at: now}
	t.mu.Unlock()
	if t.sessions != nil {
		t.sessions.StopPlay(playID, p.userID)
	}
	t.end(ctx, p, EndTerminated, now, message)
	return nil
}

// end writes a play's final state. A play that ended by itself keeps the
// time up to its last report.
func (t *Tracker) end(ctx context.Context, p *live, reason string, at time.Time, message string) {
	if t.onEnd != nil {
		t.onEnd(p.id)
	}
	if t.sessions != nil {
		t.sessions.ReleasePlay(p.id, p.userID)
	}
	t.publishStop(p, reason, message)
	if p.row == 0 {
		return
	}
	v := p.values()
	// The play is out of memory now, so its row must be finished even if
	// the request that ended it goes away (a page closing, say).
	if err := t.finish(context.WithoutCancel(ctx), &v, at, reason); err != nil {
		t.log.Error("could not finish play", "play", p.id, "err", err)
	}
}

// Run closes plays left open by a previous run, then ends plays that stop
// reporting and trims old history until ctx is done. Plays still running at
// shutdown stay open and are closed at the next start.
func (t *Tracker) Run(ctx context.Context) {
	if err := t.Recover(ctx); err != nil {
		t.log.Error("could not close plays left open", "err", err)
	}
	t.trimLogged(ctx)
	tick := time.NewTicker(15 * time.Second)
	defer tick.Stop()
	trim := time.NewTicker(6 * time.Hour)
	defer trim.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			t.reap(ctx)
		case <-trim.C:
			t.trimLogged(ctx)
		}
	}
}

func (t *Tracker) trimLogged(ctx context.Context) {
	if n, err := t.Trim(ctx); err != nil {
		t.log.Error("could not trim old plays", "err", err)
	} else if n > 0 {
		t.log.Info("trimmed old plays", "plays", n)
	}
}

// reap ends plays that have not reported for Timeout, at their last report.
func (t *Tracker) reap(ctx context.Context) {
	now := t.now()
	var gone []*live
	t.mu.Lock()
	for id, p := range t.plays {
		if now.Sub(p.lastSeen) > Timeout {
			delete(t.plays, id)
			gone = append(gone, p)
		}
	}
	for id, term := range t.terminated {
		if now.Sub(term.at) > time.Hour {
			delete(t.terminated, id)
		}
	}
	t.pruneLinks(now)
	t.mu.Unlock()
	for _, p := range gone {
		t.end(ctx, p, EndTimeout, p.lastSeen, "")
	}
}

// Playing reports how many plays are running.
func (t *Tracker) Playing() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return len(t.plays)
}

// Viewer is who is asking: admins on an admin network see everyone, others
// themselves (and, with the household setting, what others play).
type Viewer struct {
	UserID int64
	Admin  bool
}

// LivePlay is a play in progress, as Now playing shows it.
type LivePlay struct {
	ID               string     `json:"id"`
	User             UserRef    `json:"user"`
	Device           Device     `json:"device"`
	IP               string     `json:"ip,omitempty" doc:"Shown to admins and to the person watching."`
	Network          string     `json:"network" enum:"local,tailnet,remote"`
	State            string     `json:"state" enum:"playing,paused,buffering"`
	StartedAt        time.Time  `json:"startedAt"`
	LastSeenAt       time.Time  `json:"lastSeenAt"`
	PositionMs       int64      `json:"positionMs"`
	BufferedMs       int64      `json:"bufferedMs" doc:"How far ahead of the position the player has buffered."`
	DurationMs       int64      `json:"durationMs"`
	PlayedMs         int64      `json:"playedMs"`
	PausedMs         int64      `json:"pausedMs"`
	FileID           int64      `json:"fileId"`
	Item             Item       `json:"item"`
	Stream           Stream     `json:"stream"`
	BytesSent        int64      `json:"bytesSent"`
	Bandwidth        int64      `json:"bandwidth" doc:"Bits per second over the last 30 seconds."`
	ServerPaced      bool       `json:"serverPaced" required:"false" doc:"The server's configured upload ceiling applied to recent delivery; bandwidth then is not a spare-link measurement."`
	UploadGeneration uint64     `json:"uploadGeneration" required:"false" doc:"Upload policy and contention version at the last ceiling-governed delivery."`
	Transcode        *Transcode `json:"transcode,omitempty"`
	// What the player measures, for Automatic quality. Not required, so
	// clients generated from this document still read older servers.
	Throughput int64    `json:"throughput" required:"false" doc:"The player's own estimate of its link, in bits per second; 0 when it sends none."`
	Stalls     int      `json:"stalls" required:"false" doc:"Rebuffers the player reported in this play."`
	StallMs    int64    `json:"stallMs" required:"false" doc:"Time those rebuffers took."`
	StartupMs  int64    `json:"startupMs" required:"false" doc:"From the first decision to its first frame; 0 when not reported."`
	Auto       LiveAuto `json:"auto" required:"false"`
}

// Transcode is how ffmpeg is keeping up.
type Transcode struct {
	Speed       float64               `json:"speed" doc:"Encoding speed as a multiple of real time; 0 before ffmpeg reports."`
	PositionMs  int64                 `json:"positionMs" doc:"File position ffmpeg has encoded up to."`
	Preparation *playback.Preparation `json:"preparation,omitempty" required:"false" doc:"Complete server-prepared ranges and lead; separate from the player's bufferedMs. Available on newer servers."`
}

// UserRef names a user.
type UserRef struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

// Summary totals the live plays.
type Summary struct {
	Streams   int        `json:"streams"`
	Direct    int        `json:"direct"`
	Remux     int        `json:"remux"`
	Audio     int        `json:"audio"`
	Transcode int        `json:"transcode"`
	Bandwidth Bandwidths `json:"bandwidth" doc:"Bits per second over the last 30 seconds, by network."`
}

type Bandwidths struct {
	Local   int64 `json:"local"`
	Tailnet int64 `json:"tailnet"`
	Remote  int64 `json:"remote"`
}

// Live lists the plays the viewer may see, oldest first, with totals over
// the same plays.
func (t *Tracker) Live(v Viewer) ([]LivePlay, Summary) {
	now := t.now()
	t.mu.Lock()
	household := t.cfg.Household
	var ps []*live
	for _, p := range t.plays {
		if v.Admin || household || p.userID == v.UserID {
			ps = append(ps, p)
		}
	}
	out := make([]LivePlay, 0, len(ps))
	var sum Summary
	for _, p := range ps {
		lp := p.view(now)
		if !v.Admin && p.userID != v.UserID {
			lp.IP = ""
		}
		out = append(out, lp)
		sum.Streams++
		switch playback.Mode(p.stream.Mode) {
		case playback.ModeDirect:
			sum.Direct++
		case playback.ModeRemux:
			sum.Remux++
		case playback.ModeAudio:
			sum.Audio++
		case playback.ModeTranscode:
			sum.Transcode++
		}
		switch p.network {
		case netguard.NetLocal:
			sum.Bandwidth.Local += lp.Bandwidth
		case netguard.NetTailnet:
			sum.Bandwidth.Tailnet += lp.Bandwidth
		default:
			sum.Bandwidth.Remote += lp.Bandwidth
		}
	}
	t.mu.Unlock()
	for i := range out {
		if sid := ps[i].stream.SessionID; sid != "" && t.sessions != nil {
			if s, ok := t.sessions.Get(sid); ok {
				speed, pos := s.Progress()
				prepared := s.Preparation()
				out[i].Transcode = &Transcode{Speed: speed, PositionMs: pos, Preparation: &prepared}
			}
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.Before(out[j].StartedAt) })
	return out, sum
}

func (p *live) view(now time.Time) LivePlay {
	lp := LivePlay{
		ID: p.id, User: UserRef{ID: p.userID, Name: p.userName}, Device: p.device, Network: p.network, State: p.state,
		StartedAt: p.startedAt, LastSeenAt: p.lastSeen, PositionMs: p.position(now), BufferedMs: p.bufferedMs, DurationMs: p.durationMs,
		PlayedMs: p.playedMs, PausedMs: p.pausedMs, FileID: p.fileID, Item: p.item, Stream: p.stream, BytesSent: p.bytes,
		Bandwidth:        p.rate.bitsPerSecond(now.Unix(), p.startedAt.Unix(), 30),
		ServerPaced:      now.Before(p.pacedUntil),
		UploadGeneration: p.paceGeneration,
		Throughput:       p.auto.throughput, Stalls: p.auto.stallsBefore + p.auto.stalls, StallMs: p.auto.stallMsBefore + p.auto.stallMs,
		StartupMs: p.auto.startupMs,
		Auto:      LiveAuto{PlayAuto: p.auto.played(), Estimate: p.auto.state.Estimate},
	}
	if p.ip.IsValid() {
		lp.IP = p.ip.String()
	}
	return lp
}

// ActivityEvent tells clients a play started, changed or ended; they fetch
// GET /api/activity for the details they may see.
type ActivityEvent struct {
	PlayID     string `json:"playId"`
	UserID     int64  `json:"userId"`
	State      string `json:"state" enum:"playing,paused,buffering,stopped"`
	PositionMs int64  `json:"positionMs"`
	// Set on activity.stopped.
	Reason  string `json:"reason,omitempty" enum:",stopped,ended,closed,timeout,terminated"`
	Message string `json:"message,omitempty"`
}

// The event stream names each event by its payload's type.
type (
	Started ActivityEvent
	Updated ActivityEvent
	Stopped ActivityEvent
)

func (t *Tracker) audience(userID int64) *events.Audience {
	if t.Config().Household {
		return nil
	}
	return &events.Audience{UserID: userID, Anywhere: true}
}

func (t *Tracker) publish(kind string, p *live) {
	if t.bus == nil {
		return
	}
	t.mu.Lock()
	e := ActivityEvent{PlayID: p.id, UserID: p.userID, State: p.state, PositionMs: p.pos}
	t.mu.Unlock()
	var data any = Updated(e)
	if kind == "activity.started" {
		data = Started(e)
	}
	t.bus.Publish(events.Event{Type: kind, Data: data, To: t.audience(p.userID)})
}

func (t *Tracker) publishStop(p *live, reason, message string) {
	t.version.Add(1)
	if t.bus == nil {
		return
	}
	e := Stopped{PlayID: p.id, UserID: p.userID, State: StateStopped, PositionMs: p.pos, Reason: reason, Message: message}
	t.bus.Publish(events.Event{Type: "activity.stopped", Data: e, To: t.audience(p.userID)})
}

// rate counts bytes per second over the last 30 seconds.
type rate struct {
	sec [32]int64
	n   [32]int64
}

func (r *rate) add(now, bytes int64) {
	i := now % int64(len(r.sec))
	if r.sec[i] != now {
		r.sec[i], r.n[i] = now, 0
	}
	r.n[i] += bytes
}

// bitsPerSecond averages over the last window seconds (at most 30), or since
// the play started if that is sooner.
func (r *rate) bitsPerSecond(now, started, window int64) int64 {
	window = min(max(now-started, 1), window, 30)
	var sum int64
	for i := range r.sec {
		if age := now - r.sec[i]; age >= 0 && age < window {
			sum += r.n[i]
		}
	}
	return sum * 8 / window
}
