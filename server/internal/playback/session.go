package playback

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"iter"
	"log/slog"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/hwdevice"
)

const (
	segmentSeconds = 4
	PlaylistName   = "index.m3u8"
	// imageSubLookbackMs is how far before a run's start the overlay reads
	// an image subtitle, to find a cue already on screen there.
	imageSubLookbackMs = 30_000
)

// Spec describes one ffmpeg session.
type Spec struct {
	FileID int64
	// PlayID and UserID name the viewing the session belongs to: a new
	// session for the same play replaces the old one, and other viewers of
	// the same file keep theirs.
	PlayID string
	UserID int64
	Path   string
	// Growing supplies the recording's MPEG-TS at StartMs. The factory is
	// called again for a software fallback and is closed before Wait returns.
	Growing func(context.Context, int64) (GrowingInput, error)
	// growingInputStartMs is where the opened reader begins approximately.
	growingInputStartMs int64
	Mode                Mode
	StartMs             int64
	Video               VideoPlan
	Audio               AudioPlan
	MaxBitrate          int64
	// BurnIn is the ffprobe stream index of a subtitle to burn in, or -1.
	BurnIn      int
	BurnInImage bool
	// BurnInSub is the same track counted among the file's subtitle streams
	// only, which is how the subtitles filter's si option addresses it.
	BurnInSub int
	// BurnInFile burns in a sidecar subtitle file instead.
	BurnInFile string
	// VOD, when set, makes a VOD session: one playlist for the whole file,
	// with ffmpeg started wherever segments are wanted (see vod.go).
	VOD *VODOptions
	// Download makes the session an offline download's (OpenDownload):
	// ffmpeg runs at low priority, at most the download limit of them run
	// at once, and a play that finds every slot taken suspends one.
	// SessionID is its fixed ID, so the record finds it again.
	Download  bool
	SessionID string
	// PreservePrevious keeps the same play's current session available while
	// a negotiated replacement prepares and waits for first-frame ack.
	PreservePrevious bool
	// SharedOutput opts a compatible VOD output into complete-segment reuse.
	// It is internal until the A0 cold-rung contract and client opt-in land.
	SharedOutput bool
	sharedKey    string
	// ReserveSlot holds one future VOD producer slot while this session is
	// paused. A ladder may advertise a cold rung only after claiming it.
	ReserveSlot bool
	// run numbers a VOD session's ffmpeg runs, which name their files.
	run int
	// caps pins device/filter choices across this session's VOD restarts.
	caps *Capabilities
	// eventChunk caps one event ffmpeg run; zero is used by argument tests.
	eventChunk time.Duration
}

// GrowingInput supplies a recording transport reader and the approximate
// recording time at its first byte. FFmpeg discards the conservative tail
// between that time and the requested event-run start.
type GrowingInput struct {
	Reader  io.ReadCloser
	StartMs int64
}

type Session struct {
	starting atomic.Bool // a reserved process slot, before ffmpeg starts
	ID       string      `json:"id"`
	FileID   int64       `json:"fileId"`
	PlayID   string      `json:"-"`
	UserID   int64       `json:"-"`
	Mode     Mode        `json:"mode"`
	StartMs  int64       `json:"startMs"`
	Encoder  string      `json:"encoder"`
	Dir      string      `json:"-"`
	Started  time.Time   `json:"started"`
	// Video is the video plan the session runs, which differs from the
	// one asked for when it fell back to software; Fallback then says why.
	Video    VideoPlan `json:"-"`
	Fallback string    `json:"-"`
	// Download is an offline download's session (Spec.Download).
	Download bool `json:"download,omitempty"`
	// yielding is set while a download's ffmpeg is being stopped to make
	// room for a play: it no longer counts against the limit.
	yielding atomic.Bool
	reserved bool
	// producers is protected by Manager.mu. A shared cache flight leases
	// this worker independently of the viewer that supplied it.
	producers    int
	producerDone chan struct{} // closed when the last shared flight releases

	cmd             *ffexec.Cmd
	cancel          context.CancelFunc
	done            chan struct{}
	err             error
	resourceFailure atomic.Pointer[resourceFailure]
	last            atomic.Int64 // unix nanos of the last client access
	// From ffmpeg's -progress output: encoding speed as a multiple of real
	// time (float64 bits), and how far into the file it has got.
	speed        atomic.Uint64
	outMs        atomic.Int64
	progressSeen atomic.Bool
	// runStart is where a VOD session's current ffmpeg run started, in ms;
	// ffmpeg reports progress from there.
	runStart   atomic.Int64
	vod        *vodState
	event      *eventState
	ladder     atomic.Pointer[vodFamily]
	playlistMu sync.Mutex
	playlist   []byte // latest immutable non-VOD playlist
}

// Touch records client activity so the janitor keeps the session alive.
func (s *Session) Touch() {
	now := time.Now().UnixNano()
	if family := s.ladder.Load(); family != nil {
		for _, member := range family.members {
			member.last.Store(now)
		}
		return
	}
	s.last.Store(now)
}

// Progress returns ffmpeg's speed (1 is real time; 0 before it reports) and
// the file position it has encoded up to.
func (s *Session) Progress() (speed float64, positionMs int64) {
	return math.Float64frombits(s.speed.Load()), s.StartMs + s.runStart.Load() + s.outMs.Load()
}

// IsVOD reports whether the session serves a VOD playlist.
func (s *Session) IsVOD() bool { return s.vod != nil }

// InitHash is the SHA-256 of a VOD session's init segment, hex-encoded,
// waiting for the first run to write it. An offline download compares it
// with the one its device already has (PLAN-DOWNLOADS.md §3.3): copied
// video always gives the same, and a hardware encoder may not.
func (s *Session) InitHash(ctx context.Context) (string, error) {
	if s.vod == nil {
		return "", os.ErrNotExist
	}
	b, err := s.vod.Init(ctx)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:]), nil
}

// Segments is how many segments a VOD session's playlist lists; 0 for
// other sessions.
func (s *Session) Segments() int {
	if s.vod == nil {
		return 0
	}
	return s.vod.Segments()
}

// VODFile returns one of a VOD session's files by the name the playlists
// use for it, with its content type. Segments may wait for ffmpeg.
func (s *Session) VODFile(ctx context.Context, name string) ([]byte, string, error) {
	v := s.vod
	if v == nil {
		return nil, "", os.ErrNotExist
	}
	if v.viewerCtx != nil && v.viewerCtx.Err() != nil {
		return nil, "", ErrStopped
	}
	const m3u8 = "application/vnd.apple.mpegurl"
	switch name {
	case VODMaster:
		if family := s.ladder.Load(); family != nil {
			return family.master, m3u8, nil
		}
		return v.Master(), m3u8, nil
	case VODMedia:
		return v.Media(), m3u8, nil
	case VODInit, VODAudioInit:
		audio := name == VODAudioInit
		if audio && !v.opts.SeparateAudio {
			return nil, "", os.ErrNotExist
		}
		b, err := v.sharedInit(ctx)
		if err == nil {
			b, err = v.track(b, true, audio)
		}
		return b, "video/mp4", err
	case VODAudio:
		if !v.opts.SeparateAudio {
			return nil, "", os.ErrNotExist
		}
		return v.AudioMedia(), m3u8, nil
	case VODIFrames:
		t, err := s.IFrameTrack(ctx)
		if err != nil {
			return nil, "", err
		}
		return t.Playlist(v.opts.DurationMs), m3u8, nil
	}
	if m := vodSegmentRe.FindStringSubmatch(name); m != nil {
		audio := m[1] == "a"
		if audio && !v.opts.SeparateAudio {
			return nil, "", os.ErrNotExist
		}
		k, _ := strconv.Atoi(m[2])
		var b []byte
		var err error
		if family := s.ladder.Load(); family != nil {
			b, err = family.segment(ctx, v, k)
		} else {
			b, err = v.Segment(ctx, k)
		}
		if err == nil {
			b, err = v.track(b, false, audio)
		}
		if err == nil {
			if family := s.ladder.Load(); family != nil && s == family.members[0] {
				family.warmNext(k + 1)
			}
		}
		return b, "video/mp4", err
	}
	if m := vodSubPlaylistRe.FindStringSubmatch(name); m != nil {
		b, ok := v.SubtitlePlaylist(m[1])
		if !ok {
			return nil, "", os.ErrNotExist
		}
		return b, m3u8, nil
	}
	if m := vodSubSegmentRe.FindStringSubmatch(name); m != nil {
		k, _ := strconv.Atoi(m[2])
		b, err := v.SubtitleSegment(ctx, m[1], k)
		return b, "text/vtt; charset=utf-8", err
	}
	return nil, "", os.ErrNotExist
}

var (
	vodSegmentRe     = regexp.MustCompile(`^([va])(\d{5})\.m4s$`)
	vodSubPlaylistRe = regexp.MustCompile(`^sub-([sxd]\d+)\.m3u8$`)
	vodSubSegmentRe  = regexp.MustCompile(`^sub-([sxd]\d+)-(\d{5})\.vtt$`)
)

// progressWriter reads ffmpeg's -progress output: blocks of key=value lines.
type progressWriter struct {
	s       *Session
	partial []byte
}

func (w *progressWriter) Write(b []byte) (int, error) {
	data := append(w.partial, b...)
	for {
		i := bytes.IndexByte(data, '\n')
		if i < 0 {
			break
		}
		w.line(string(bytes.TrimSpace(data[:i])))
		data = data[i+1:]
	}
	w.partial = append(w.partial[:0], data...)
	return len(b), nil
}

func (w *progressWriter) line(l string) {
	k, v, ok := strings.Cut(l, "=")
	if !ok {
		return
	}
	switch k {
	case "out_time_us":
		if us, err := strconv.ParseInt(v, 10, 64); err == nil && us >= 0 {
			w.s.outMs.Store(us / 1000)
			w.s.progressSeen.Store(true)
		}
	case "speed":
		if x, err := strconv.ParseFloat(strings.TrimSuffix(strings.TrimSpace(v), "x"), 64); err == nil {
			w.s.speed.Store(math.Float64bits(x))
		}
	}
}

// Err returns ffmpeg's failure once it has exited, else nil.
func (s *Session) Err() error {
	select {
	case <-s.done:
		return s.err
	default:
		return nil
	}
}

// Finished reports whether ffmpeg has exited (successfully or not).
func (s *Session) Finished() bool {
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}

type Manager struct {
	ffmpeg           string
	root             string
	log              *slog.Logger
	idle             time.Duration
	limit            int
	processes        processSlots
	liveReservations int
	renditions       *renditionCache
	// downloads is how many offline download sessions may run ffmpeg at
	// once, inside limit.
	downloads int
	// downloadsNormal runs downloads' ffmpeg at normal priority
	// (DownloadsAtNormalPriority).
	downloadsNormal atomic.Bool
	// ladderTook, when not zero, is how long tests say each rung of a family
	// took to prepare (LadderPreparationTook).
	ladderTook atomic.Int64
	// ctx is every session's parent; shutdown ends it.
	ctx    context.Context
	cancel context.CancelFunc

	// What ffmpeg can do, which may still be being detected (hwcaps.go).
	capsMu      sync.Mutex
	caps        Capabilities
	capsReady   chan struct{} // closed once caps holds a detection
	capsGen     int           // which detection may set caps
	capsCancel  context.CancelFunc
	detectWG    sync.WaitGroup
	capsFile    string              // where the last detection is kept, or ""
	adapterScan hwdevice.Scanner    // configured before serving; live, never cached on disk
	adapters    *hwdevice.Inventory // protected by capsMu

	// vodAhead and vodResume replace the VOD throttle's window in tests.
	vodAhead, vodResume time.Duration
	// Event preparation is bounded independently of a player's HTTP buffer.
	eventAhead, eventResume time.Duration
	resources               ResourceLimits
	resourceMu              sync.Mutex
	liveRoot                string
	stopLive                func(string)

	mu       sync.Mutex
	sessions map[string]*Session
	// retiring workers no longer authorize a viewer, but still own capacity
	// and output until shared flights serving other viewers have finished.
	retiring map[string]*Session
}

// NewManager makes a manager whose sessions write under dir (transcode.dir),
// in its SessionsDir.
func NewManager(ffmpeg, dir string, caps Capabilities, idle time.Duration, limit int, log *slog.Logger) *Manager {
	if idle <= 0 {
		idle = 60 * time.Second
	}
	if limit <= 0 {
		limit = 8
	}
	ctx, cancel := context.WithCancel(context.Background())
	ready := make(chan struct{})
	close(ready)
	return &Manager{ffmpeg: ffmpeg, root: filepath.Join(dir, SessionsDir), log: log, idle: idle, limit: limit, downloads: 1, processes: processSlots{slots: make(chan struct{}, limit)},
		renditions: newRenditionCache(128<<20, limit, 32, 5*time.Minute),
		resources:  DefaultResourceLimits(),
		ctx:        ctx, cancel: cancel, caps: caps, capsReady: ready, sessions: map[string]*Session{}, retiring: map[string]*Session{}}
}

// Limit is how many sessions may run at once.
func (m *Manager) Limit() int { return m.limit }

// SetDownloadLimit sets how many offline download sessions may run ffmpeg
// at once (transcode.max_download_sessions, 1 by default), within Limit.
// Call it before serving.
func (m *Manager) SetDownloadLimit(n int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.downloads = min(max(n, 1), m.limit)
}

// DownloadsAtNormalPriority runs download sessions' ffmpeg at normal
// priority, not low. It is for tests of what a download serves, and the
// server never calls it. Low priority on macOS gives a process little CPU
// while every core is busy: a transcode of 13 seconds at 320x180 that takes
// 0.2 s took 3.9 s under the utility clamp a download runs under
// (ffexec.Cmd.Awaited), and 74 s under the background policy downloads had
// until then. A test that waits on it then slows or fails with the machine's
// load, as TestOfflineHLSDownloads did on the CI runners (2026-11-09).
// TestDownloadSession checks that downloads are at low priority.
func (m *Manager) DownloadsAtNormalPriority() { m.downloadsNormal.Store(true) }

// Running counts the sessions with ffmpeg running, which the limit
// counts: not VOD sessions paused for idleness, nor download sessions
// between requests.
func (m *Manager) Running() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.activeLocked(nil)
}

// Claimed counts running producers and reserved cold-rendition slots. It is
// the admission figure for a manifest even while an encoder is paused.
func (m *Manager) Claimed() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.claimedLocked(nil)
}

// DownloadsRunning counts the download sessions with ffmpeg running.
func (m *Manager) DownloadsRunning() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.downloadsActiveLocked(nil)
}

// ErrStopped is a request for a session that has been stopped, or replaced
// by a newer request for the same play.
var ErrStopped = errors.New("playback session stopped")

// ErrTooManySessions is a session or run that would go over the limit.
var ErrTooManySessions = errors.New("too many playback sessions")

func (m *Manager) tooMany() error {
	return fmt.Errorf("%w (%d)", ErrTooManySessions, m.limit)
}

// reapEvery is how often Run looks for idle sessions.
const reapEvery = 10 * time.Second

// Run reaps idle sessions until ctx is done, then stops them all and
// returns once their ffmpeg has exited (or stopWait has passed). No session
// starts after that.
func (m *Manager) Run(ctx context.Context) {
	t := time.NewTicker(reapEvery)
	defer t.Stop()
	resources := time.NewTicker(250 * time.Millisecond)
	defer resources.Stop()
	for {
		select {
		case <-ctx.Done():
			m.capsMu.Lock()
			m.cancel()
			m.capsMu.Unlock()
			m.StopAll()
			m.renditions.close()
			m.detectWG.Wait()
			return
		case now := <-t.C:
			m.reapOnce(now)
			m.renditions.sweep(now)
		case <-resources.C:
			m.checkResources()
		}
	}
}

// reapOnce stops sessions nobody has fetched from for the idle timeout
// (VOD sessions after VODKeep) and stops the ffmpeg of idle VOD sessions,
// whose playlists stay good: a later request starts it again. It never
// waits on a session's lock, so one stuck session cannot stall the rest.
func (m *Manager) reapOnce(now time.Time) {
	var pause []*Session
	m.mu.Lock()
	for id, s := range m.sessions {
		idle := now.Sub(time.Unix(0, s.last.Load()))
		switch {
		case s.vod != nil && idle > VODKeep:
			m.stopLocked(id)
		case s.vod != nil && idle > m.idle:
			// Running, or paused ahead of the player with its pieces.
			pause = append(pause, s)
		case s.vod == nil && idle > m.idle:
			m.log.Info("playback session idle; stopping", "session", id, "file", s.FileID)
			m.stopLocked(id)
		}
	}
	m.mu.Unlock()
	// Outside m.mu: a VOD session takes its own lock and then m.mu. It looks
	// at the time of the last request again under its lock, so a request
	// that came meanwhile keeps its run.
	for _, s := range pause {
		if s.vod.suspendIdle(now, m.idle) {
			m.log.Info("playback session idle; stopping ffmpeg and deleting its pieces", "session", s.ID, "file", s.FileID)
		}
	}
}

func (m *Manager) Get(id string) (*Session, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.sessions[id]
	return s, ok
}

// Sessions lists the running sessions, oldest first.
func (m *Manager) Sessions() []*Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]*Session, 0, len(m.sessions))
	for _, s := range m.sessions {
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Started.Before(out[j].Started) })
	return out
}

func (m *Manager) Stop(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.stopLocked(id)
}

// StopPlay stops a play's session, if it has one.
func (m *Manager) StopPlay(playID string, userID int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, s := range m.sessions {
		if s.PlayID == playID && s.UserID == userID {
			m.stopLocked(id)
		}
	}
}

// ReleasePlay drops the capacity an ended play reserved for its ladder
// rungs. Its sessions stay, as any VOD session does after a stop, and count
// against the limit only while their ffmpeg runs: without this, a stopped
// family kept its paused rungs' slots until VODKeep.
func (m *Manager) ReleasePlay(playID string, userID int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for s := range m.ownedSessionsLocked() {
		if s.PlayID == playID && s.UserID == userID {
			s.reserved = false
		}
	}
}

// stopWait is how long StopAll waits for ffmpeg to exit.
const stopWait = 10 * time.Second

// StopAll stops every session and waits, up to stopWait, for their ffmpeg
// to exit.
func (m *Manager) StopAll() {
	m.mu.Lock()
	done := make([]<-chan struct{}, 0, len(m.sessions)+len(m.retiring))
	for s := range m.ownedSessionsLocked() {
		done = append(done, s.done)
		if s.producers > 0 {
			done = append(done, s.producerDone)
		}
		// Shutdown cannot defer a worker to a request that is itself waiting
		// for shutdown. All viewers and leased workers stop together.
		s.cancel()
	}
	for id := range m.sessions {
		m.stopLocked(id)
	}
	m.mu.Unlock()
	timeout := time.NewTimer(stopWait)
	defer timeout.Stop()
	for _, d := range done {
		select {
		case <-d:
		case <-timeout.C:
			m.log.Warn("playback sessions still stopping", "after", stopWait)
			return
		}
	}
}

func (m *Manager) stopLocked(id string) {
	s, ok := m.sessions[id]
	if !ok {
		return
	}
	if family := s.ladder.Load(); family != nil {
		for _, member := range family.members {
			m.stopOneLocked(member.ID)
		}
		return
	}
	m.stopOneLocked(id)
}

func (m *Manager) stopOneLocked(id string) {
	s, ok := m.sessions[id]
	if !ok {
		return
	}
	delete(m.sessions, id)
	if s.vod != nil && s.vod.viewerCancel != nil {
		s.vod.viewerCancel()
	}
	if s.producers > 0 {
		m.retiring[id] = s
	} else {
		s.cancel()
	}
	producerDone := s.producerDone
	go func() {
		<-s.done
		if producerDone != nil {
			<-producerDone
		}
		m.removeDir(s.Dir)
	}()
}

// ownedSessionsLocked includes private workers finishing shared media after
// their original viewer stopped. Callers hold m.mu. They must count toward
// capacity and storage enforcement without remaining authorized sessions.
func (m *Manager) ownedSessionsLocked() iter.Seq[*Session] {
	return func(yield func(*Session) bool) {
		for _, group := range []map[string]*Session{m.sessions, m.retiring} {
			for _, s := range group {
				if !yield(s) {
					return
				}
			}
		}
	}
}

// removeDir deletes a session's files. On Windows a folder cannot be
// deleted while a response is still sending one of its files, so a failure
// is retried for a while rather than leaving the folder behind.
func (m *Manager) removeDir(dir string) {
	for range 5 {
		if m.RemoveOutputDir(dir) == nil {
			return
		}
		time.Sleep(2 * time.Second)
	}
}

// Start launches ffmpeg for spec and returns once the playlist has its first
// segment (or ffmpeg fails). Starting a new session for a play stops the
// play's older session, which is how seeking restarts work; anyone else
// watching the same file keeps their session.
//
// When a hardware encoder makes ffmpeg exit before anything has been
// served, the play starts again in software (see softwareSpec): a GPU that
// cannot take one more session, or a driver that refuses a file, then
// costs a slower transcode rather than the play. A failure later in a
// session still ends it, because the client already has the hardware
// encoder's stream.
func (m *Manager) Start(ctx context.Context, spec Spec) (*Session, error) {
	spec = m.pinCaps(spec)
	shareableMode := (spec.Video.Copy && spec.Audio.Copy) || (!spec.Video.Copy && hwaccelOf(m.encoderFor(spec)) == "software")
	if spec.SharedOutput && !spec.Download && spec.VOD != nil && spec.Growing == nil && shareableMode &&
		spec.BurnIn < 0 && spec.BurnInFile == "" {
		key, err := renditionContract(spec)
		if err != nil {
			return nil, err
		}
		spec.sharedKey = key
	}
	s, err := m.start(ctx, spec)
	var exit *ffexec.ExitError
	if err == nil || !errors.As(err, &exit) || ctx.Err() != nil {
		return s, err
	}
	enc := m.encoderFor(spec)
	if hwaccelOf(enc) == "software" {
		return nil, err
	}
	sw, ok := m.softwareSpec(spec)
	if !ok {
		return nil, err
	}
	m.log.Warn("hardware transcode failed; starting again in software", "file", spec.FileID, "encoder", enc, "err", err)
	s, swErr := m.start(ctx, sw)
	if swErr != nil {
		return nil, fmt.Errorf("%w; in software: %w", err, swErr)
	}
	s.Fallback = fmt.Sprintf("%s failed, so the transcode runs in software", enc)
	return s, nil
}

// softwareSpec moves spec to libx264: H.264 at 1080p or below, tone mapped
// on the CPU when the source is HDR (and the filters are there).
func (m *Manager) softwareSpec(spec Spec) (Spec, bool) {
	if !slices.Contains(m.capsFor(spec).Encoders, "libx264") {
		return spec, false
	}
	v := &spec.Video
	v.Codec, v.Encoder = "h264", "libx264"
	v.ToneMap = v.HDR != ""
	if v.Height > defaultMaxTranscodeHeight {
		if v.Width > 0 {
			v.Width = max(v.Width*defaultMaxTranscodeHeight/v.Height&^1, 2)
		}
		v.Height = defaultMaxTranscodeHeight
	}
	return spec, true
}

func (m *Manager) start(ctx context.Context, spec Spec) (*Session, error) {
	if spec.VOD != nil {
		return m.startVOD(ctx, spec)
	}
	return m.startEvent(ctx, spec)
}

// newSessionLocked makes a session for spec and its folder, not yet
// registered: a new session for a play replaces the play's older one, and
// none starts over the limit. Its context ends when it is stopped.
func (m *Manager) newSessionLocked(spec Spec, encoder string) (*Session, context.Context, error) {
	if m.ctx.Err() != nil {
		return nil, nil, ErrStopped
	}
	if !spec.PreservePrevious {
		for id, s := range m.sessions {
			if spec.PlayID != "" && s.PlayID == spec.PlayID && s.UserID == spec.UserID && s.FileID == spec.FileID {
				m.stopLocked(id)
			}
		}
	}
	if (spec.sharedKey == "" || spec.ReserveSlot) && m.claimedLocked(nil) >= m.limit && !m.yieldLocked() {
		return nil, nil, m.tooMany()
	}
	id := newID()
	dir := filepath.Join(m.root, id)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, nil, err
	}
	ctx, cancel := context.WithCancel(m.ctx)
	s := &Session{ID: id, FileID: spec.FileID, PlayID: spec.PlayID, UserID: spec.UserID, Mode: spec.Mode, Encoder: encoder, Video: spec.Video,
		Dir: dir, Started: time.Now(), cancel: cancel, done: make(chan struct{}), reserved: spec.ReserveSlot}
	s.Touch()
	return s, ctx, nil
}

// activeLocked counts sessions with ffmpeg running, other than except: VOD
// sessions that are paused for idleness, and downloads giving way to a
// play, do not count against the limit.
func (m *Manager) activeLocked(except *Session) int {
	n := m.liveReservations
	for s := range m.ownedSessionsLocked() {
		if s != except && s.runningLocked() {
			n++
		}
	}
	return n
}

// claimedLocked includes admitted ladder rungs even while their ffmpeg is
// paused. An unrelated start cannot steal the slot promised by a manifest.
func (m *Manager) claimedLocked(except *Session) int {
	n := m.liveReservations
	for s := range m.ownedSessionsLocked() {
		if s != except && (s.reserved || s.runningLocked()) {
			n++
		}
	}
	return n
}

// runningLocked reports whether the session's ffmpeg counts against the limit.
func (s *Session) runningLocked() bool {
	return !s.yielding.Load() && (s.starting.Load() || (s.vod == nil && s.event == nil) || (s.vod != nil && s.vod.running()) || (s.event != nil && s.event.running()))
}

// downloadsActiveLocked counts the download sessions running, other than except.
func (m *Manager) downloadsActiveLocked(except *Session) int {
	n := 0
	for _, s := range m.sessions {
		if s != except && s.Download && s.runningLocked() {
			n++
		}
	}
	return n
}

// yieldLocked makes room for a play when every slot is taken: it stops the
// newest download session's ffmpeg, as an idle session's is stopped, and
// counts it out at once. The download's next request waits for a slot.
// It reports whether there was a download to stop.
func (m *Manager) yieldLocked() bool {
	var victim *Session
	for _, s := range m.sessions {
		if s.Download && s.runningLocked() && (victim == nil || s.Started.After(victim.Started)) {
			victim = s
		}
	}
	if victim == nil {
		return false
	}
	victim.yielding.Store(true)
	m.log.Info("download session gives way to a play", "session", victim.ID, "file", victim.FileID)
	// Outside m.mu: the session takes its own lock, which its requests
	// hold while they take m.mu.
	go victim.vod.suspend()
	return true
}

// reserveRun atomically claims capacity before a VOD process starts or
// resumes. A play may take a download's slot; a download runs only within
// the download limit and never takes a slot from anything.
func (m *Manager) reserveRun(s *Session) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	switch {
	case s.Download:
		if m.claimedLocked(s) >= m.limit || m.downloadsActiveLocked(s) >= m.downloads {
			return false
		}
		s.yielding.Store(false)
	case m.claimedLocked(s) >= m.limit && !m.yieldLocked():
		return false
	}
	s.starting.Store(true)
	return true
}

// OpenDownload returns the download session spec.SessionID names, making
// it if it is not there (it went after VODKeep, or the server restarted).
// A new session starts no ffmpeg: the first request for a segment does,
// within the download limit, and waits if there is no room.
func (m *Manager) OpenDownload(spec Spec) (*Session, error) {
	if err := m.admitStorage(); err != nil {
		return nil, err
	}
	spec = m.pinCaps(spec)
	if spec.SessionID == "" || spec.VOD == nil {
		return nil, errors.New("a download session needs its ID and a VOD playlist")
	}
	spec.Download, spec.PlayID = true, ""
	m.mu.Lock()
	defer m.mu.Unlock()
	if s, ok := m.sessions[spec.SessionID]; ok {
		return s, nil
	}
	if m.ctx.Err() != nil {
		return nil, ErrStopped
	}
	// A folder of its own: the last session of this ID may still be
	// deleting its folder.
	dir := filepath.Join(m.root, spec.SessionID+"-"+newID())
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(m.ctx)
	s := &Session{ID: spec.SessionID, FileID: spec.FileID, UserID: spec.UserID, Mode: spec.Mode, Encoder: m.encoderFor(spec), Video: spec.Video,
		Dir: dir, Started: time.Now(), Download: true, cancel: cancel, done: make(chan struct{})}
	s.Touch()
	v, err := newVOD(m, s, ctx, spec)
	if err != nil {
		cancel()
		_ = m.RemoveOutputDir(dir)
		return nil, err
	}
	s.vod = v
	m.sessions[s.ID] = s
	go func() {
		<-ctx.Done()
		v.waitStopped()
		close(s.done)
	}()
	m.log.Info("download session made", "session", s.ID, "file", spec.FileID, "mode", spec.Mode, "segments", v.Segments(), "encoder", s.Encoder)
	return s, nil
}

// startVOD makes a VOD session and starts ffmpeg at spec.StartMs, returning
// once the first piece is ready.
func (m *Manager) startVOD(ctx context.Context, spec Spec) (*Session, error) {
	if err := m.admitStorage(); err != nil {
		return nil, err
	}
	encoder := m.encoderFor(spec)
	m.mu.Lock()
	s, sessCtx, err := m.newSessionLocked(spec, encoder)
	if err != nil {
		m.mu.Unlock()
		return nil, err
	}
	id := s.ID
	v, err := newVOD(m, s, sessCtx, spec)
	if err != nil {
		s.cancel()
		m.mu.Unlock()
		_ = m.RemoveOutputDir(s.Dir)
		return nil, err
	}
	s.vod = v
	s.starting.Store(true)
	m.sessions[id] = s
	m.mu.Unlock()
	go func() {
		<-sessCtx.Done()
		v.waitStopped()
		close(s.done)
	}()
	first := v.segmentAt(spec.StartMs * 1000)
	m.log.Info("playback session started", "session", id, "file", spec.FileID, "mode", spec.Mode, "playlist", "vod", "segments", v.Segments(), "first", first, "encoder", encoder)
	if spec.sharedKey != "" {
		if _, _, err := v.sharedStartup(ctx); err != nil {
			m.Stop(id)
			return nil, err
		}
	} else if _, err := v.Segment(ctx, first); err != nil {
		m.Stop(id)
		return nil, err
	}
	if !v.running() {
		s.starting.Store(false)
	}
	return s, nil
}

// waitForPlaylist blocks until the playlist lists at least one segment.
// runCtx is the session's: it ends when the session is stopped, as a newer
// request for the same play does.
func (s *Session) waitForPlaylist(ctx, runCtx context.Context) error {
	deadline := time.NewTimer(30 * time.Second)
	defer deadline.Stop()
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("ffmpeg produced no segments within 30s")
		case <-s.done:
			if s.err != nil {
				return s.err
			}
			if runCtx.Err() != nil {
				return ErrStopped
			}
			return nil // very short file: finished before the first tick
		case <-tick.C:
			if b, err := s.ReadPlaylist(); err == nil && strings.Contains(string(b), "#EXTINF") {
				return nil
			}
		}
	}
}

// BindCapabilities keeps a decision's encoder and its device on one snapshot.
// It carries no new wire data; clients cannot supply or change the snapshot.
func (d Decision) BindCapabilities(spec Spec) Spec { spec.caps = d.caps; return spec }

func (m *Manager) pinCaps(spec Spec) Spec {
	if spec.caps == nil {
		c := m.currentCaps()
		spec.caps = &c
	}
	return spec
}

func (m *Manager) capsFor(spec Spec) Capabilities {
	if spec.caps != nil {
		return *spec.caps
	}
	return m.currentCaps()
}

func (m *Manager) encoderFor(spec Spec) string {
	if spec.Video.Copy {
		return "copy"
	}
	if spec.Video.Encoder != "" {
		return spec.Video.Encoder
	}
	return m.capsFor(spec).Encoder
}

// keepsHDR reports whether the transcode carries HDR through: 10-bit HEVC
// output with no tone mapping.
func keepsHDR(spec Spec) bool {
	return spec.Video.Codec == "hevc" && spec.Video.HDR != "" && !spec.Video.ToneMap
}

// args builds the ffmpeg command line for a session.
func (m *Manager) args(spec Spec, dir string) []string {
	caps := m.capsFor(spec)
	a := ffexec.FFmpegArgs("-y", "-progress", "pipe:1", "-stats_period", "2")
	// The encoder, not caps.HWAccel, decides the path: a session that fell
	// back to software must not touch the GPU.
	enc := m.encoderFor(spec)
	hw := hwaccelOf(enc)
	if hw != caps.HWAccel {
		caps.Device = nil
	}
	p, havePipeline := pipelineOn(hw, caps.Device)
	gpu := false
	// Dolby Vision with no base layer a decoder shows right (profile 5) is
	// tone mapped on the CPU: tonemapx reshapes it through its RPU where
	// ffmpeg has it, and none of the GPU chains here can.
	dvToneMap := !spec.Video.Copy && spec.Video.ToneMap && spec.Video.HDR == "dolbyvision"
	if !spec.Video.Copy && !spec.Video.Deinterlace && hw == caps.HWAccel && havePipeline && caps.GPUFilters && !dvToneMap {
		gpu = (!spec.Video.ToneMap || caps.gpuToneMap) && (p.sizeExpr || spec.Video.Width > 0 || spec.Video.Height == 0)
	}
	deviceInput, deviceOutput := EncoderDeviceArgs(enc, caps.Device)
	if !spec.Video.Copy && !gpu {
		a = append(a, deviceInput...)
	}
	switch {
	case spec.Video.Copy:
	case gpu:
		// Keep decoded frames on the GPU. The device is named so the upload
		// filter can lift software-decoded frames to it.
		a = append(a, p.decode...)
		a = append(a, p.devices...)
	case hw == "videotoolbox":
		// Decode on the media engine anyway; ffmpeg falls back to software
		// for codecs it cannot handle and copies frames back for filters.
		a = append(a, "-hwaccel", "videotoolbox")
	}
	if spec.VOD != nil {
		// Timestamps stay the file's own, so every run's pieces share one
		// timeline (vod.go picks the seek target).
		if spec.StartMs > 0 {
			a = append(a, "-ss", ffexec.Seconds(spec.StartMs))
		}
		a = append(a, "-copyts", "-start_at_zero")
	} else if spec.StartMs > 0 && spec.Growing == nil {
		a = append(a, "-ss", ffexec.Seconds(spec.StartMs))
	}
	if spec.Growing != nil {
		a = append(a, "-fflags", "+genpts+discardcorrupt", "-f", "mpegts", "-i", "pipe:0")
		if lookback := max(spec.StartMs-spec.growingInputStartMs, 0); lookback > 0 {
			a = append(a, "-ss", ffexec.Seconds(lookback))
		}
	} else {
		a = append(a, "-i", spec.Path)
	}

	// An image subtitle's cue is one packet where it starts, which a seek to
	// the run's start skips: a cue already on screen there would wait for
	// the next. The overlay reads the subtitle from the file again, opened
	// up to imageSubLookbackMs earlier and lined up with the first input,
	// whose video and audio keep their timing.
	burnImage := !spec.Video.Copy && spec.BurnIn >= 0 && spec.BurnInImage && caps.Overlay
	subInput := 0
	if burnImage && spec.StartMs > 0 {
		from := max(spec.StartMs-imageSubLookbackMs, 0)
		if spec.VOD == nil {
			// Without -copyts an input's timestamps start at zero where it
			// was seeked to: shift this one back to the first's, and keep
			// what accurate seeking would trim from before zero.
			a = append(a, "-noaccurate_seek", "-ss", ffexec.Seconds(from), "-itsoffset", ffexec.Seconds(from-spec.StartMs))
		} else {
			a = append(a, "-ss", ffexec.Seconds(from))
		}
		a = append(a, "-i", spec.Path)
		subInput = 1
	}

	// --- video ---
	var filters []string
	sharedCFR := spec.VOD != nil && spec.sharedKey != "" && spec.ReserveSlot && spec.VOD.FrameRate > 0
	useFilterComplex := false
	switch {
	case spec.Video.Copy:
		a = append(a, "-map", "0:v:0", "-c:v", "copy")
		switch {
		case spec.Video.KeepDV:
			// Players pick a Dolby Vision decoder from the configuration
			// record, which ffmpeg writes only with -strict unofficial.
			// Profile 5 goes under dvh1, since nothing else can show it;
			// profile 8 stays under the hvc1 its base layer plays as, with
			// the record beside it, as Apple's HLS spec has it (the master
			// playlist's SUPPLEMENTAL-CODECS names it).
			tag := "hvc1"
			if spec.Video.DVProfile == 5 {
				tag = "dvh1"
			}
			a = append(a, "-tag:v", tag, "-strict", "unofficial")
		case spec.Video.StripDV:
			// The base layer alone. Without -strict unofficial the record
			// is not written anyway; dovi_rpu (FFmpeg 7.1) drops the RPU
			// and enhancement layer units too.
			if spec.Video.Codec == "hevc" {
				a = append(a, "-tag:v", "hvc1")
			}
			if caps.doviStrip {
				a = append(a, "-bsf:v", "dovi_rpu=strip=1")
			}
		case spec.Video.Codec == "hevc":
			a = append(a, "-tag:v", "hvc1")
		}
	default:
		burnText := spec.BurnIn >= 0 && !spec.BurnInImage && spec.BurnInFile == "" && caps.LibASS
		burnFile := spec.BurnInFile != "" && caps.LibASS
		if gpu {
			filters = append(filters, gpuChain(p, spec, burnText || burnFile || burnImage)...)
		} else {
			if spec.Video.Deinterlace {
				filters = append(filters, BroadcastDeinterlace)
			}
			reshape := dvToneMap && caps.DoviToneMap
			if reshape {
				// Before scaling, on the frames the RPU was written for.
				filters = append(filters, DoviToneMapFilter)
			}
			switch {
			case spec.Video.Width > 0 && spec.Video.Height > 0:
				filters = append(filters, fmt.Sprintf("scale=%d:%d", spec.Video.Width, spec.Video.Height))
			case spec.Video.Height > 0:
				filters = append(filters, fmt.Sprintf("scale=-2:'min(ih,%d)'", spec.Video.Height))
			}
			if spec.Video.ToneMap && caps.zscale && !reshape {
				if dvToneMap {
					// No RPU filter: read the untagged base layer as HDR10,
					// which zscale needs to run at all. The colours are off,
					// and the decision's reasons say so.
					filters = append(filters, DVBaseAsHDR10Filter)
				}
				filters = append(filters, ToneMapFilters()...)
			}
			if keepsHDR(spec) {
				filters = append(filters, "format=p010le")
			} else {
				filters = append(filters, "format=yuv420p")
			}
		}
		if sharedCFR {
			// Filter on the source timeline, including after distant seeks.
			// Output-side CFR synchronization pads from zero under -copyts.
			filters = append(filters, "fps="+strconv.FormatFloat(spec.VOD.FrameRate, 'g', -1, 64)+":start_time="+ffexec.Seconds(spec.StartMs))
		}
		if burnText {
			filters = append(filters, fmt.Sprintf("subtitles=%s:si=%d", escapeFilterPath(spec.Path), spec.BurnInSub))
		}
		if burnFile {
			filters = append(filters, fmt.Sprintf("subtitles=%s", escapeFilterPath(spec.BurnInFile)))
		}
		upload := ""
		if !gpu || burnText || burnFile || burnImage {
			upload = EncoderUploadFilter(enc, caps.Device, keepsHDR(spec))
		}
		if burnImage {
			useFilterComplex = true
			overlay := fmt.Sprintf("[0:v:0]%s[v];[v][%d:%d]overlay", strings.Join(filters, ","), subInput, spec.BurnIn)
			if upload != "" {
				overlay += "," + upload
			}
			a = append(a, "-filter_complex", overlay+"[out]", "-map", "[out]")
		} else {
			if upload != "" {
				filters = append(filters, upload)
			}
			a = append(a, "-map", "0:v:0", "-vf", strings.Join(filters, ","))
		}
		a = append(a, "-c:v", enc)
		a = append(a, deviceOutput...)

		a = append(a, encoderArgs(enc, spec.MaxBitrate, max(spec.Video.Height, spec.Video.Width*9/16), keepsHDR(spec))...)
		// Keyframes every segment so HLS segments are independent. The
		// expression counts from the run's first frame, which for VOD runs
		// must be on the segment grid: a seeked run starts on a boundary.
		seg := segmentSeconds
		if spec.VOD != nil {
			seg = vodSegmentSeconds
		}
		a = append(a, "-g", strconv.Itoa(seg*30), "-keyint_min", strconv.Itoa(seg*30), "-sc_threshold", "0",
			"-force_key_frames", fmt.Sprintf("expr:gte(t,n_forced*%d)", seg))
		if sharedCFR {
			// Private shared variants promise one frame rate in their master
			// and cache contract. Enforce it on initial and seeked runs; merely
			// requesting CFR otherwise retains the input's frame rate.
			a = append(a, "-fps_mode", "passthrough")
		} else if spec.VOD != nil && spec.StartMs == 0 {
			// A run from the start has its first frame wherever the video
			// starts, which is after zero when the audio begins first. CFR
			// output fills that gap with copies of the first frame, which
			// -copyts otherwise turns off. (-fps_mode, its newer name, needs
			// ffmpeg 5.1.)
			a = append(a, "-vsync", "cfr")
		}
	}
	_ = useFilterComplex

	// --- audio ---
	a = append(a, "-map", fmt.Sprintf("0:%d", spec.Audio.Index))
	if spec.Audio.Copy {
		a = append(a, "-c:a", "copy")
	} else {
		a = append(a, "-c:a", "aac", "-b:a", "192k")
		if spec.Audio.Channels > 0 {
			a = append(a, "-ac", strconv.Itoa(spec.Audio.Channels))
		}
	}
	a = append(a, "-sn")

	// --- HLS output ---
	if spec.VOD != nil {
		// A piece at every keyframe (the segment time is next to nothing),
		// named by run; the server joins pieces into the playlist's segments.
		run := fmt.Sprintf("r%d", spec.run)
		return append(a,
			"-avoid_negative_ts", "disabled",
			"-f", "hls",
			"-hls_time", "0.001",
			"-hls_playlist_type", "event",
			"-hls_list_size", "0",
			"-hls_segment_type", "fmp4",
			"-hls_fmp4_init_filename", run+"-init.mp4",
			"-hls_segment_filename", filepath.Join(dir, run+"-%05d.m4s"),
			"-hls_flags", "independent_segments+temp_file",
			"-start_number", "0",
			filepath.Join(dir, run+".m3u8"),
		)
	}
	if spec.eventChunk > 0 {
		a = append(a, "-t", ffexec.Seconds(spec.eventChunk.Milliseconds()))
	}
	a = append(a,
		"-f", "hls",
		"-hls_time", strconv.Itoa(segmentSeconds),
		"-hls_playlist_type", "event",
		"-hls_list_size", "0",
		"-hls_segment_type", "fmp4",
		"-hls_fmp4_init_filename", "init.mp4",
		"-hls_segment_filename", filepath.Join(dir, "seg%05d.m4s"),
		"-hls_flags", eventHLSFlags(spec),
		"-start_number", "0",
		filepath.Join(dir, PlaylistName),
	)
	return a
}

func eventHLSFlags(spec Spec) string {
	if spec.eventChunk > 0 {
		return "independent_segments+temp_file+append_list+omit_endlist"
	}
	return "independent_segments+temp_file"
}

// transcodeAudioBitrate is what AAC audio is encoded at.
const transcodeAudioBitrate = 192_000

// transcodeBitrate is the video bitrate a transcode is held to: the
// client's cap, else a default for the output height.
func transcodeBitrate(maxBitrate int64, height int) int64 {
	if maxBitrate > 0 {
		return maxBitrate
	}
	switch {
	case height >= 2000:
		return 20_000_000
	case height >= 1000:
		return 8_000_000
	case height >= 700:
		return 4_000_000
	}
	return 2_000_000
}

// encoderArgs tunes encoder for a capped bitrate at the output height; hdr
// asks an HEVC encoder for 10-bit Main 10 output.
func encoderArgs(encoder string, maxBitrate int64, height int, hdr bool) []string {
	maxBitrate = transcodeBitrate(maxBitrate, height)
	rate := strconv.FormatInt(maxBitrate, 10)
	buf := strconv.FormatInt(maxBitrate*2, 10)
	hevcProfile := "main"
	if hdr {
		hevcProfile = "main10"
	}
	if e, ok := findEncoder(encoder); ok {
		return e.args(rate, buf, hevcProfile)
	}
	return []string{"-b:v", rate}
}

// escapeFilterPath quotes a path for use inside an ffmpeg filter graph.
func escapeFilterPath(p string) string {
	r := strings.NewReplacer(`\`, `\\`, `:`, `\:`, `'`, `\'`, `[`, `\[`, `]`, `\]`, `,`, `\,`, `;`, `\;`)
	// Filter options and the enclosing graph each parse quotes. Leave the
	// graph's quoted region around a literal apostrophe, then reopen it; the
	// first escape survives for the option parser. This is argv, not a shell.
	return "'" + strings.ReplaceAll(r.Replace(p), "'", "'\\''") + "'"
}

// NewPlayID returns an ID for a new play, in the format Request.PlayID accepts.
func NewPlayID() string { return newID() }

func newID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
