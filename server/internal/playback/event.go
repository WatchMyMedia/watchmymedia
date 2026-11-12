package playback

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/ffexec"
)

// Event runs stop at a finite preparation window. A viewer fetching the end
// of a window starts the next run in the same session and HLS timeline.
// FFmpeg appends to its previous playlist and marks the new run with a
// discontinuity. A stopped viewer never starts another run.
const (
	eventAhead  = 32 * time.Second
	eventResume = 12 * time.Second
)

type eventState struct {
	m          *Manager
	s          *Session
	ctx        context.Context
	spec       Spec
	window     time.Duration
	resume     time.Duration
	keepBehind time.Duration
	mu         sync.Mutex
	starting   bool
	active     atomic.Bool
	final      bool
	stopRun    context.CancelFunc
	runDone    chan struct{}
	closed     sync.Once
	preparedMs atomic.Int64
	servedMs   atomic.Int64
	evictedMs  atomic.Int64
	baseMs     atomic.Int64 // start of the compacted FFmpeg append playlist
}

func (e *eventState) running() bool { return e.active.Load() }

func (m *Manager) startEvent(ctx context.Context, spec Spec) (*Session, error) {
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
	s.StartMs = spec.StartMs
	e := &eventState{m: m, s: s, ctx: sessCtx, spec: spec, window: m.eventAhead, resume: m.eventResume, keepBehind: m.resources.EventKeepBehind}
	if e.window <= 0 {
		e.window = m.resources.EventAhead
		if e.window <= 0 {
			e.window = eventAhead
		}
	}
	if e.resume <= 0 || e.resume >= e.window {
		e.resume = min(e.window/4, eventResume)
	}
	s.event = e
	s.starting.Store(true)
	m.sessions[s.ID] = s
	m.mu.Unlock()
	go e.shutdown()
	if err := e.startRun(ctx); err != nil {
		m.Stop(s.ID)
		return nil, err
	}
	m.log.Info("playback session started", "session", s.ID, "file", spec.FileID, "mode", spec.Mode, "start_ms", spec.StartMs, "encoder", encoder)
	if err := s.waitForPlaylist(ctx, sessCtx); err != nil {
		m.Stop(s.ID)
		return nil, err
	}
	return s, nil
}

func (e *eventState) shutdown() {
	<-e.ctx.Done()
	e.mu.Lock()
	stop, done := e.stopRun, e.runDone
	e.mu.Unlock()
	if stop != nil {
		stop()
	}
	if done != nil {
		<-done
	}
	e.closed.Do(func() { close(e.s.done) })
}

// stoppedOr reports a run its session's stop or replacement interrupted as
// ErrStopped, as a VOD run does, rather than the cancellation it caused.
func (e *eventState) stoppedOr(err error) error {
	if e.ctx.Err() != nil {
		return ErrStopped
	}
	return err
}

func (e *eventState) startRun(caller context.Context) error {
	e.mu.Lock()
	if e.final || e.starting || e.active.Load() || e.ctx.Err() != nil {
		e.mu.Unlock()
		return nil
	}
	e.starting = true
	e.s.starting.Store(true)
	startMs := e.preparedMs.Load()
	e.mu.Unlock()
	defer func() {
		e.mu.Lock()
		e.starting = false
		e.s.starting.Store(false)
		e.mu.Unlock()
	}()
	if err := e.m.admitStorage(); err != nil {
		return err
	}
	if !e.m.reserveRun(e.s) {
		return e.m.tooMany()
	}
	runCtx, cancel := context.WithCancel(e.ctx)
	release, err := e.m.processes.acquire(runCtx)
	if err != nil {
		cancel()
		return e.stoppedOr(err)
	}
	if caller.Err() != nil {
		release()
		cancel()
		return caller.Err()
	}
	spec := e.spec
	spec.StartMs += startMs
	spec.eventChunk = e.window
	var input io.ReadCloser
	if spec.Growing != nil {
		opened, openErr := spec.Growing(runCtx, spec.StartMs)
		input, spec.growingInputStartMs, err = opened.Reader, opened.StartMs, openErr
		if err != nil {
			release()
			cancel()
			return fmt.Errorf("open recording: %w", err)
		}
	}
	if runCtx.Err() != nil {
		if input != nil {
			_ = input.Close()
		}
		release()
		cancel()
		return ErrStopped
	}
	args := e.m.args(spec, e.s.Dir)
	if startMs > 0 {
		if err := e.ensureAppendSource(); err != nil {
			if input != nil {
				_ = input.Close()
			}
			release()
			cancel()
			return err
		}
	}
	cmd := ffexec.Command(runCtx, e.m.ffmpeg, args...)
	cmd.Stdout = &progressWriter{s: e.s}
	cmd.Dir = e.s.Dir
	var pipe io.WriteCloser
	if input != nil {
		pipe, err = cmd.StdinPipe()
		if err != nil {
			_ = input.Close()
			release()
			cancel()
			return err
		}
	}
	e.s.runStart.Store(startMs)
	e.s.outMs.Store(0)
	e.s.progressSeen.Store(false)
	if err := cmd.Start(); err != nil {
		if input != nil {
			_ = input.Close()
			_ = pipe.Close()
		}
		release()
		cancel()
		return e.stoppedOr(fmt.Errorf("start ffmpeg: %w", err))
	}
	var copied chan struct{}
	if input != nil {
		copied = make(chan struct{})
		go func() { _, _ = io.Copy(pipe, input); _ = pipe.Close(); close(copied) }()
	}
	done := make(chan struct{})
	e.mu.Lock()
	e.stopRun, e.runDone, e.s.cmd = cancel, done, cmd
	e.active.Store(true)
	e.mu.Unlock()
	e.m.log.Debug("ffmpeg event run", "session", e.s.ID, "from_ms", spec.StartMs, "args", strings.Join(args, " "))
	go e.wait(runCtx, cmd, input, copied, release, cancel, done, startMs)
	return nil
}

func (e *eventState) wait(runCtx context.Context, cmd *ffexec.Cmd, input io.ReadCloser, copied <-chan struct{}, release func(), cancel context.CancelFunc, done chan struct{}, startMs int64) {
	err := cmd.Wait()
	if input != nil {
		_ = input.Close()
		<-copied
	}
	release()
	// FFmpeg can leave a complete .tmp playlist on Windows even after a
	// successful exit. Readers are excluded during that last publication.
	if err == nil && runCtx.Err() == nil {
		e.s.playlistMu.Lock()
		err = publishEventPlaylist(filepath.Join(e.s.Dir, PlaylistName))
		e.s.playlistMu.Unlock()
	}
	var prepared int64
	if err == nil && runCtx.Err() == nil {
		if b, readErr := e.s.ReadPlaylist(); readErr == nil {
			prepared = parseEventPlaylistAt(b, e.baseMs.Load()).endMs
		}
		if prepared == 0 {
			err = errors.New("ffmpeg produced no event segments")
		}
		if err == nil {
			e.compactPlaylist()
		}
	}
	e.mu.Lock()
	e.active.Store(false)
	if e.runDone == done {
		e.stopRun = nil
	}
	if err != nil && runCtx.Err() == nil {
		e.s.err = err
		e.final = true
	} else if runCtx.Err() == nil && e.ctx.Err() == nil {
		// A short run reached source EOF. At the exact window boundary an
		// additional empty run resolves the ambiguity without ending early.
		e.final = prepared-startMs < e.window.Milliseconds()-1000
	}
	final, failed := e.final, e.s.err != nil
	e.mu.Unlock()
	close(done)
	cancel()
	if final {
		if !failed {
			if finishErr := e.finishPlaylist(); finishErr != nil {
				e.mu.Lock()
				e.s.err = finishErr
				e.mu.Unlock()
			}
		}
		e.closed.Do(func() { close(e.s.done) })
	} else if e.ctx.Err() == nil && e.servedMs.Load() >= e.preparedMs.Load()-e.resume.Milliseconds() {
		go e.resumeRun()
	}
}

func (e *eventState) resumeRun() {
	// A request queued this goroutine while FFmpeg was still publishing an
	// earlier window. Check the latest complete end again: otherwise a late
	// goroutine can prepare another entire window for a paused viewer.
	if e.preparedMs.Load() == 0 || e.servedMs.Load() < e.preparedMs.Load()-e.resume.Milliseconds() {
		return
	}
	if err := e.startRun(e.ctx); err != nil && e.ctx.Err() == nil {
		if errors.Is(err, ErrTooManySessions) || errors.Is(err, ErrStorageBudget) {
			e.m.log.Warn("event continuation waits for resources", "session", e.s.ID, "err", err)
			return
		}
		e.mu.Lock()
		e.s.err = err
		e.final = true
		e.mu.Unlock()
		e.closed.Do(func() { close(e.s.done) })
	}
}

func (e *eventState) finishPlaylist() error {
	e.s.playlistMu.Lock()
	defer e.s.playlistMu.Unlock()
	path := filepath.Join(e.s.Dir, PlaylistName)
	b, err := ReadShared(path)
	if errors.Is(err, os.ErrNotExist) {
		b, err = e.s.playlist, nil
	}
	if err != nil {
		return err
	}
	if len(b) == 0 {
		return errors.New("missing final event playlist")
	}
	if bytes.Contains(b, []byte("#EXT-X-ENDLIST")) {
		return nil
	}
	b = append(bytes.TrimRight(b, "\r\n"), []byte("\n#EXT-X-ENDLIST\n")...)
	tmp := path + ".final"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	e.s.playlist = b
	return nil
}

func (e *eventState) ensureAppendSource() error {
	e.s.playlistMu.Lock()
	defer e.s.playlistMu.Unlock()
	path := filepath.Join(e.s.Dir, PlaylistName)
	if _, err := os.Stat(path); err == nil {
		return nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if len(e.s.playlist) == 0 {
		return errors.New("missing event playlist for continuation")
	}
	tmp := path + ".resume"
	if err := os.WriteFile(tmp, e.s.playlist, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

func (e *eventState) observePlaylist(b []byte) {
	end := parseEventPlaylistAt(b, e.baseMs.Load()).endMs
	for {
		old := e.preparedMs.Load()
		if end <= old || e.preparedMs.CompareAndSwap(old, end) {
			return
		}
	}
}

// EventSegmentRequested records only a listed segment's end. It never lets
// an arbitrary guessed future name trigger unbounded encoding.
func (s *Session) EventSegmentRequested(name string) {
	e := s.event
	if e == nil || !strings.HasPrefix(name, "seg") {
		return
	}
	b, err := s.ReadPlaylist()
	if err != nil {
		return
	}
	p := parseEventPlaylistAt(b, e.baseMs.Load())
	end, ok := p.segments[name]
	if !ok {
		return
	}
	for {
		old := e.servedMs.Load()
		if end <= old || e.servedMs.CompareAndSwap(old, end) {
			break
		}
	}
	e.prune(p)
	if end >= e.preparedMs.Load()-e.resume.Milliseconds() {
		go e.resumeRun()
	}
}

type eventPlaylist struct {
	endMs    int64
	segments map[string]int64
	entries  []eventEntry
}

type eventEntry struct {
	name  string
	endMs int64
}

func parseEventPlaylist(b []byte) eventPlaylist { return parseEventPlaylistAt(b, 0) }

func parseEventPlaylistAt(b []byte, baseMs int64) eventPlaylist {
	p := eventPlaylist{endMs: baseMs, segments: make(map[string]int64)}
	var duration float64
	for raw := range strings.SplitSeq(string(b), "\n") {
		line := strings.TrimSpace(raw)
		if field, ok := strings.CutPrefix(line, "#EXTINF:"); ok {
			value, _, _ := strings.Cut(field, ",")
			duration, _ = strconv.ParseFloat(value, 64)
		} else if strings.HasPrefix(line, "seg") && strings.HasSuffix(line, ".m4s") {
			p.endMs += int64(duration*1000 + .5)
			p.segments[line] = p.endMs
			p.entries = append(p.entries, eventEntry{line, p.endMs})
			duration = 0
		}
	}
	return p
}

func (e *eventState) prune(p eventPlaylist) {
	if e.keepBehind <= 0 {
		return
	}
	cutoff := e.servedMs.Load() - e.keepBehind.Milliseconds()
	for _, entry := range p.entries {
		if entry.endMs <= e.evictedMs.Load() {
			continue
		}
		if entry.endMs > cutoff {
			break
		}
		if err := os.Remove(filepath.Join(e.s.Dir, entry.name)); err != nil && !errors.Is(err, os.ErrNotExist) {
			e.m.log.Warn("could not prune event segment", "session", e.s.ID, "err", err)
			break
		}
		e.evictedMs.Store(entry.endMs)
	}
}

// EventPlaylist keeps the HTTP manifest within the retained range. The
// FFmpeg-owned append source stays untouched; only complete, already-served
// pieces are removed, and older clients seek by replacing their session.
func (s *Session) EventPlaylist() ([]byte, error) {
	b, err := s.ReadPlaylist()
	if err != nil || s.event == nil {
		return b, err
	}
	e := s.event
	if e.servedMs.Load() >= e.preparedMs.Load()-e.resume.Milliseconds() && !e.running() {
		go e.resumeRun()
	}
	return filterEventPlaylist(b, e.evictedMs.Load(), e.baseMs.Load()), nil
}

func filterEventPlaylist(raw []byte, evictedMs, baseMs int64) []byte {
	lines := strings.Split(string(raw), "\n")
	var target, mapping, independent string
	var mediaSequence, discontinuitySequence int64
	endMs := baseMs
	var pendingDiscontinuity, final bool
	type item struct {
		inf, name     string
		discontinuity bool
		end           int64
	}
	items := make([]item, 0)
	inf := ""
	for _, line := range lines {
		switch {
		case strings.HasPrefix(line, "#EXT-X-TARGETDURATION:"):
			target = line
		case strings.HasPrefix(line, "#EXT-X-MAP:"):
			mapping = line
		case strings.HasPrefix(line, "#EXT-X-INDEPENDENT-SEGMENTS"):
			independent = line
		case strings.HasPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"):
			mediaSequence, _ = strconv.ParseInt(strings.TrimPrefix(line, "#EXT-X-MEDIA-SEQUENCE:"), 10, 64)
		case strings.HasPrefix(line, "#EXT-X-DISCONTINUITY-SEQUENCE:"):
			discontinuitySequence, _ = strconv.ParseInt(strings.TrimPrefix(line, "#EXT-X-DISCONTINUITY-SEQUENCE:"), 10, 64)
		case line == "#EXT-X-DISCONTINUITY":
			pendingDiscontinuity = true
		case strings.HasPrefix(line, "#EXTINF:"):
			inf = line
		case strings.HasPrefix(line, "seg") && strings.HasSuffix(line, ".m4s"):
			value, _, _ := strings.Cut(strings.TrimPrefix(inf, "#EXTINF:"), ",")
			seconds, _ := strconv.ParseFloat(value, 64)
			endMs += int64(seconds*1000 + .5)
			items = append(items, item{inf, line, pendingDiscontinuity, endMs})
			pendingDiscontinuity, inf = false, ""
		case line == "#EXT-X-ENDLIST":
			final = true
		}
	}
	var b strings.Builder
	b.WriteString("#EXTM3U\n#EXT-X-VERSION:7\n")
	if target != "" {
		b.WriteString(target + "\n")
	}
	cut := 0
	for cut < len(items) && items[cut].end <= evictedMs {
		if items[cut].discontinuity {
			discontinuitySequence++
		}
		cut++
	}
	fmt.Fprintf(&b, "#EXT-X-MEDIA-SEQUENCE:%d\n", mediaSequence+int64(cut))
	if discontinuitySequence > 0 {
		fmt.Fprintf(&b, "#EXT-X-DISCONTINUITY-SEQUENCE:%d\n", discontinuitySequence)
	}
	if independent != "" {
		b.WriteString(independent + "\n")
	}
	if mapping != "" {
		b.WriteString(mapping + "\n")
	}
	for _, it := range items[cut:] {
		if it.discontinuity {
			b.WriteString("#EXT-X-DISCONTINUITY\n")
		}
		b.WriteString(it.inf + "\n" + it.name + "\n")
	}
	if final {
		b.WriteString("#EXT-X-ENDLIST\n")
	}
	return []byte(b.String())
}

// Once a run has exited, compact the append source too. Keeping FFmpeg's
// playlist forever would otherwise grow the session's memory and metadata
// on a long recording even after its old media files were pruned.
func (e *eventState) compactPlaylist() {
	evicted := e.evictedMs.Load()
	if evicted <= e.baseMs.Load() {
		return
	}
	e.s.playlistMu.Lock()
	defer e.s.playlistMu.Unlock()
	if len(e.s.playlist) == 0 {
		return
	}
	trimmed := filterEventPlaylist(e.s.playlist, evicted, e.baseMs.Load())
	path := filepath.Join(e.s.Dir, PlaylistName)
	tmp := path + ".resume"
	if err := os.WriteFile(tmp, trimmed, 0o600); err != nil {
		e.m.log.Warn("could not compact event playlist", "session", e.s.ID, "err", err)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		e.m.log.Warn("could not publish compacted event playlist", "session", e.s.ID, "err", err)
		return
	}
	e.s.playlist = trimmed
	e.baseMs.Store(evicted)
}

// publishEventPlaylist accepts only a complete temporary playlist whose
// listed media exists. The immutable snapshot/rename discipline is the same
// as the final playlist path on Windows.
func publishEventPlaylist(path string) error {
	tmp := path + ".tmp"
	b, err := ReadShared(tmp)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	p := parseEventPlaylist(b)
	if len(p.segments) == 0 {
		return errors.New("unfinished event playlist")
	}
	last := p.entries[len(p.entries)-1]
	if _, err := os.Stat(filepath.Join(filepath.Dir(path), last.name)); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
