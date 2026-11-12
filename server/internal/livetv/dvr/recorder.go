package dvr

import (
	"context"
	"crypto/rand"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv"
)

type output struct {
	mu     sync.Mutex
	r      record
	file   *os.File
	done   chan struct{}
	stop   bool
	reason string
	// p is the stream this output was added to; finish detaches it there.
	p *pipeline
	// finishing stays set from finish's first step to its last write, so a
	// tick or a cancel never mistakes the closing writer for a lost one.
	finishing bool
	// The stream's last bytes and a gap still open, recorded when the
	// stream comes back or the recording ends.
	lastData  time.Time
	gapStart  *time.Time
	gapReason string
}

// minGap is the shortest interruption worth naming as a missing part.
const minGap = 2 * time.Second

// lose opens a gap at the last bytes written, unless one is open already.
func (out *output) lose(now time.Time, reason string) {
	if out.gapStart != nil {
		return
	}
	at := out.lastData
	if at.IsZero() {
		at = now
	}
	out.gapStart = &at
	out.gapReason = reason
}

// closeGap records the open gap, if any, as ending at the given time.
func (out *output) closeGap(at time.Time) {
	if out.gapStart == nil {
		return
	}
	if at.Sub(*out.gapStart) >= minGap {
		out.r.Gaps = append(out.r.Gaps, Gap{Start: *out.gapStart, End: at, Reason: out.gapReason})
	}
	out.gapStart = nil
}

type pipeline struct {
	id   string
	done chan struct{}
	key  sourceKey
	// A viewer-first group claims its tuner before publication. Recordings
	// joining it inherit that claim; the copy loop releases it on exit.
	reserved    func()
	startedAt   time.Time
	predecessor <-chan struct{}
	generation  atomic.Int64
	formatMu    sync.Mutex
	format      *sourceFormat
	inspecting  chan struct{}
	ctx         context.Context
	cancel      context.CancelFunc
	mu          sync.Mutex
	outputs     map[string]*output
	subscribers map[*liveReader]bool
	// closed is set under mu when the copy loop ends; nothing joins it then.
	closed bool
	// attempt ends the current tuner stream without ending the pipeline,
	// and reopen skips the retry wait: a changed address is tried at once.
	attempt atomic.Pointer[context.CancelFunc]
	reopen  atomic.Bool
}

const liveCaptureChunk = 64 << 10

var errLiveReaderBacklog = errors.New("live capture reader exceeded its bounded queue")

type liveReader struct {
	// Private shared encoders coalesce transport reads so the 64-entry queue
	// has a predictable byte budget regardless of TCP/HTTP fragmentation.
	coalesce    bool
	assembling  []byte // pipeline.mu; distinct from Read's pending bytes
	cancelCause context.CancelCauseFunc
	chunks      chan []byte
	pending     []byte
	ctx         context.Context
	cancel      context.CancelFunc
	close       func()
	once        sync.Once
	active      atomic.Bool
	last        atomic.Bool
	done        <-chan struct{}
	source      *pipeline
}

// CaptureIdentity distinguishes a reopened physical stream from the old timeline.
// Fork transfers no authority: Sessions retains each viewer's separate lease.
func (r *liveReader) CaptureIdentity() (string, int64) {
	return r.source.id, r.source.generation.Load()
}

func (r *liveReader) Fork(ctx context.Context) (io.ReadCloser, error) {
	if r.ctx.Err() != nil {
		return nil, ErrMissing
	}
	return r.source.subscribeChunks(ctx, true)
}

func (r *liveReader) Read(p []byte) (int, error) {
	r.active.Store(true)
	if len(r.pending) == 0 {
		select {
		case <-r.ctx.Done():
			return 0, context.Cause(r.ctx)
		case b, ok := <-r.chunks:
			if !ok {
				return 0, io.EOF
			}
			r.pending = b
		}
	}
	n := copy(p, r.pending)
	r.pending = r.pending[n:]
	return n, nil
}
func (r *liveReader) Close() error { r.once.Do(func() { r.cancel(); r.close() }); return nil }

// WaitReleased joins capture only when this reader removed its final owner.
// Source-policy mutations stop viewers while holding livetv.Service.mu, so
// Close itself must never wait for the copy loop's reservation release.
func (r *liveReader) WaitReleased() {
	if r.last.Load() {
		<-r.done
	}
}
func (s *Service) sharing(channelID string) bool {
	value, ok := s.shared.Load(channelID)
	return ok && value.(*pipeline).ctx.Err() == nil
}
func (s *Service) LiveInput(ctx context.Context, channelID string) (io.ReadCloser, error) {
	// Source mutation holds livetv.Service.mu while it stops sessions. It
	// must never wait for this callback to acquire the DVR schedule mutex:
	// scheduling takes those locks in the opposite order for tuner admission.
	value, ok := s.shared.Load(channelID)
	if !ok {
		return nil, ErrMissing
	}
	p := value.(*pipeline)
	return p.subscribe(ctx)
}

func (p *pipeline) subscribe(ctx context.Context) (io.ReadCloser, error) {
	return p.subscribeChunks(ctx, false)
}

func (p *pipeline) subscribeChunks(ctx context.Context, coalesce bool) (io.ReadCloser, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.ctx.Err() != nil || ctx.Err() != nil || p.closed {
		return nil, ErrMissing
	}
	ctx, cancel := context.WithCancelCause(ctx)
	r := &liveReader{ctx: ctx, cancel: func() { cancel(context.Canceled) }, cancelCause: cancel,
		chunks: make(chan []byte, 64), source: p, coalesce: coalesce}
	r.done = p.done
	r.close = func() {
		p.mu.Lock()
		delete(p.subscribers, r)
		last := len(p.subscribers) == 0 && len(p.outputs) == 0
		if last {
			r.last.Store(true)
			p.cancel()
		}
		p.mu.Unlock()
	}
	p.subscribers[r] = true
	return r, nil
}

// offer runs under pipeline.mu. A full queue rejects only this reader. The
// producer path holds at most 4 MiB queued plus bounded assembly and read chunks.
func (r *liveReader) offer(data []byte) bool {
	if !r.coalesce {
		select {
		case r.chunks <- append([]byte(nil), data...):
			return true
		default:
			return false
		}
	}
	for len(data) > 0 {
		if r.assembling == nil {
			r.assembling = make([]byte, 0, liveCaptureChunk)
		}
		n := min(liveCaptureChunk-len(r.assembling), len(data))
		r.assembling = append(r.assembling, data[:n]...)
		data = data[n:]
		if len(r.assembling) == liveCaptureChunk {
			select {
			case r.chunks <- r.assembling:
				r.assembling = nil
			default:
				return false
			}
		}
	}
	return true
}

// JoinLive supplies the same bounded capture to viewers before or after a
// recording begins. The first viewer reserves capacity synchronously, so
// simultaneous starts cannot open a second tuner for this channel.
func (s *Service) JoinLive(ctx context.Context, sourceID, channelID string) (io.ReadCloser, error) {
	key := sourceKey{sourceID, channelID}
	for {
		s.mu.Lock()
		if s.closed || ctx.Err() != nil {
			s.mu.Unlock()
			return nil, ErrMissing
		}
		if p := s.pipes[key]; p != nil {
			if p.ctx.Err() == nil {
				if reader, err := p.subscribe(ctx); err == nil {
					s.mu.Unlock()
					return reader, nil
				}
			}
			// The previous group's reservation stays claimed until capture
			// exits. Join after that release instead of returning a transient
			// 404 or opening a second physical source.
			done := p.done
			s.mu.Unlock()
			select {
			case <-done:
				continue
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		}
		id := "live:" + rand.Text()
		release, err := s.live.ReserveViewerSource(ctx, id, sourceID)
		if err != nil {
			s.mu.Unlock()
			return nil, err
		}
		runCtx, cancel := context.WithCancel(s.ctx)
		p := &pipeline{id: id, done: make(chan struct{}), key: key, ctx: runCtx, cancel: cancel, startedAt: time.Now(),
			reserved: release, outputs: map[string]*output{}, subscribers: map[*liveReader]bool{}}
		p.generation.Store(1)
		reader, err := p.subscribe(ctx)
		if err != nil {
			cancel()
			release()
			s.mu.Unlock()
			return nil, err
		}
		s.pipes[key] = p
		s.shared.Store(channelID, p)
		s.live.BorrowedRecording(id, channelID, func() {
			if s.sessions != nil {
				s.sessions.StopSharedChannel(channelID, "recording")
			}
			p.cancel()
			<-p.done
		})
		s.wg.Go(func() { s.copy(p) })
		s.opts.Log.Info("live source group started", "source", sourceID, "channel", channelID, "group", id)
		s.mu.Unlock()
		return reader, nil
	}
}
func (s *Service) sourcesChanged(source, channel, reason string) {
	// Who may watch Live TV is the viewers' policy; recordings keep their
	// tuners when it changes.
	if reason == "access_changed" {
		return
	}
	// This listener runs synchronously under livetv.Service.mu. Pipeline
	// identity is immutable, so it needs only the concurrent index. A changed
	// address, a disabled channel or a removed tuner ends the current stream
	// only: the copy loop reopens it, retrying until the end and naming the
	// gap, rather than ending each recording for good.
	s.shared.Range(func(_, value any) bool {
		p := value.(*pipeline)
		if (source == "" || p.key.source == source) && (channel == "" || p.key.channel == channel) {
			p.reopen.Store(true)
			if stop := p.attempt.Load(); stop != nil {
				(*stop)()
			}
		}
		return true
	})
	// A tuner count may have changed with the device: plan again.
	s.replan.Store(true)
	s.signal()
}
func (s *Service) Run(ctx context.Context) {
	defer s.Close()
	_ = s.Reconcile(ctx)
	// One timer covers warnings, starts, ends, disk checks and midnight rule
	// expansion. The normal UI never polls recordings or waits on this loop.
	timer := time.NewTimer(0)
	defer timer.Stop()
	lastDay := ""
	var bus <-chan events.Event
	if s.opts.Bus != nil {
		var unsub func()
		bus, unsub = s.opts.Bus.Subscribe()
		defer unsub()
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.ctx.Done():
			return
		case <-s.wake:
		case e := <-bus:
			if e.Type != "scan.finished" && e.Type != "library.changed" && e.Type != "match.finished" {
				continue
			}
		case <-timer.C:
		}
		now := s.opts.Now()
		day := now.UTC().Format("2006-01-02")
		if day != lastDay {
			_ = s.Reconcile(ctx)
			lastDay = day
		}
		next := s.tick(ctx, now)
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
		// Go timers count monotonic time, which stops while the computer
		// sleeps, so one long wait could wake hours after a start. Waiting
		// at most a minute rereads the wall clock; clients never poll.
		wait := min(max(time.Millisecond, next.Sub(s.opts.Now())), time.Minute)
		timer.Reset(wait)
	}
}
func (s *Service) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		s.wg.Wait()
		return
	}
	s.closed = true
	s.cancel()
	for _, p := range s.pipes {
		p.cancel()
	}
	s.mu.Unlock()
	s.wg.Wait()
}
func (s *Service) tick(ctx context.Context, now time.Time) time.Time {
	next := now.UTC().Truncate(24 * time.Hour).Add(24 * time.Hour)
	config, err := s.Settings(ctx)
	if err != nil {
		return now.Add(30 * time.Second)
	}
	if s.replan.Swap(false) {
		s.mu.Lock()
		_ = s.plan(ctx)
		s.mu.Unlock()
	}
	rows, err := s.q.ListDVRPending(ctx)
	if err != nil {
		return now.Add(30 * time.Second)
	}
	warnings := []livetv.RecordingWarning{}
	replan := false
	for _, row := range rows {
		r, err := readRecord(row)
		if err != nil {
			continue
		}
		s.mu.Lock()
		out := s.running[r.ID]
		s.mu.Unlock()
		if out != nil {
			out.mu.Lock()
			stop := out.stop
			reason := out.reason
			finishing := out.finishing
			r = out.r
			out.mu.Unlock()
			if finishing {
				// finish is sealing and renaming the file; it writes the
				// final state itself.
				next = minTime(next, now.Add(10*time.Second))
				continue
			}
			if !config.Enabled && reason == "" {
				reason = "stopped"
			}
			if stop || !r.until().After(now) || !config.Enabled {
				s.finish(out, reason)
				continue
			}
			next = minTime(next, r.until())
			next = minTime(next, now.Add(10*time.Second))
			if s.opts.FreeBytes != nil {
				free, e := s.opts.FreeBytes(filepath.Dir(r.PartialPath))
				if e != nil || free < uint64(config.MinFreeBytes) {
					s.finish(out, "disk_full")
				} else {
					s.checkpoint(ctx, out)
				}
			}
			continue
		}
		if r.State == "recording" {
			if r.Reason == "stop_requested" {
				r.Gaps = append(r.Gaps, Gap{Start: now, End: r.until(), Reason: "stopped"})
				s.finishRecovered(ctx, r)
				continue
			}
			// Crash recovery is driven by durable ownership, not a new schedule.
			// Keep the existing partial bytes and describe the interruption.
			r.Gaps = append(r.Gaps, Gap{Start: unix(row.UpdatedAt), End: minTime(now, r.until()), Reason: "server_restarted"})
			if r.until().After(now) && config.Enabled {
				s.begin(ctx, r, true)
				next = minTime(next, now.Add(10*time.Second))
			} else {
				s.finishRecovered(ctx, r)
			}
			continue
		}
		if (r.State == "gone" || r.State == "conflict") && !r.until().After(now) {
			// Its time has passed without recording: it leaves Upcoming and
			// says why under Recorded, instead of waiting there for good.
			if r.State == "gone" {
				r.Reason = "listing_gone"
			}
			r.State = "failed"
			_ = put(ctx, s.q, r, now.Unix())
			s.publish()
			continue
		}
		if r.State != "scheduled" || !config.Enabled {
			continue
		}
		if !r.until().After(now) {
			r.State = "failed"
			r.Reason = "missed"
			_ = put(ctx, s.q, r, now.Unix())
			s.publish()
			replan = true
			continue
		}
		if !r.from().After(now) {
			s.begin(ctx, r, false)
			next = minTime(next, now.Add(10*time.Second))
			continue
		}
		warningAt := r.from().Add(-time.Duration(config.WarningSeconds) * time.Second)
		if !warningAt.After(now) {
			warnings = append(warnings, livetv.RecordingWarning{SourceID: r.SourceID, ChannelID: r.ChannelID, ID: r.ID, Title: r.Title, At: r.from(), UserID: r.UserID})
		} else {
			next = minTime(next, warningAt)
		}
		next = minTime(next, r.from())
	}
	if replan {
		// A recording that missed its start frees its tuner for a clash.
		s.mu.Lock()
		_ = s.plan(ctx)
		s.mu.Unlock()
	}
	if s.sessions != nil {
		s.sessions.RecordingWarnings(warnings)
	}
	if s.link(ctx) {
		next = minTime(next, now.Add(10*time.Second))
	}
	return next
}
func valueTime(p *time.Time, fallback time.Time) time.Time {
	if p == nil {
		return fallback
	}
	return *p
}
func minTime(a, b time.Time) time.Time {
	if a.Before(b) {
		return a
	}
	return b
}
func (s *Service) begin(ctx context.Context, r record, resume bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	current, err := s.get(ctx, r.ID)
	if err != nil || s.closed {
		return
	}
	if !resume && current.State != "scheduled" {
		return
	}
	if resume && (current.State != "recording" || current.Reason == "stop_requested") {
		return
	}
	if !resume {
		r = current
	}

	config, err := s.Settings(ctx)
	if err != nil {
		return
	}
	// A resume that can't carry on keeps what was recorded before the
	// restart: it is finished as partial, never failed with its bytes
	// left in the hidden file.
	fail := func(reason string) {
		if resume {
			if now := s.opts.Now(); now.Before(r.until()) {
				r.Gaps = append(r.Gaps, Gap{Start: now, End: r.until(), Reason: reason})
			}
			s.finishRecovered(ctx, r)
			return
		}
		s.failStart(ctx, r, reason)
	}
	if resume {
		if _, err := os.Stat(r.PartialPath); errors.Is(err, os.ErrNotExist) {
			if _, err := os.Stat(r.Path); err == nil {
				s.finishRecovered(ctx, r)
				return
			}
		}
	}
	if !resume {
		r.LibraryID = config.TVLibraryID
		if r.Kind == "movie" {
			if config.MoviePath == "" || config.MovieLibraryID == 0 {
				fail("folder_unavailable")
				return
			}
			r.LibraryID = config.MovieLibraryID
		}
	}
	lib, err := s.q.GetLibrary(ctx, r.LibraryID)
	if err == nil && ((r.Kind == "movie") != (lib.Type == "movie")) {
		err = ErrUnavailable
	}
	if err != nil {
		fail("folder_unavailable")
		return
	}

	free, err := s.opts.FreeBytes(lib.Path)
	if err != nil || free < uint64(config.MinFreeBytes) {
		fail("disk_full")
		return
	}
	// A new recording must also fit; a resumed one only needs the space a
	// running recording needs, since stopping it keeps what it has.
	expected := uint64(max(0, r.until().Sub(s.opts.Now()).Seconds()) * 1_000_000)
	if !resume && free-uint64(config.MinFreeBytes) < expected {
		fail("disk_full")
		return
	}
	if !resume {
		r.Path, err = s.recordingPath(ctx, lib, r)
		if err != nil {
			fail("folder_unavailable")
			return
		}
		r.PartialPath = filepath.Join(filepath.Dir(r.Path), "."+filepath.Base(r.Path)+".partial")
	}
	if err = makeParent(lib.Path, r.PartialPath); err != nil {
		fail("folder_unavailable")
		return
	}
	if resume {
		if err = s.validateOwned(ctx, r, r.PartialPath, false); err != nil {
			fail("output_changed")
			return
		}
	}
	flags := os.O_CREATE | os.O_WRONLY
	if resume {
		flags |= os.O_APPEND
	} else {
		flags |= os.O_EXCL
	}
	file, err := os.OpenFile(r.PartialPath, flags, 0o600)
	if err != nil {
		fail("folder_unavailable")
		return
	}
	now := s.opts.Now()
	if r.StartedAt == nil {
		r.StartedAt = &now
	}
	if !resume && now.After(r.Start.Add(5*time.Second)) {
		r.Gaps = append(r.Gaps, Gap{Start: r.Start, End: now, Reason: "started_late"})
	}
	r.State = "recording"
	r.Reason = ""
	r.EndedAt = nil
	if info, e := file.Stat(); e == nil {
		r.Bytes = info.Size()
	}
	if err = put(ctx, s.q, r, now.Unix()); err != nil {
		_ = file.Close()
		return
	}
	// lastData starts at the start, so a wait for a tuner reads as a gap.
	out := &output{r: r, file: file, done: make(chan struct{}), lastData: now}
	key := sourceKey{r.SourceID, r.ChannelID}
	s.running[r.ID] = out
	// Join the channel's stream only while it still runs: its copy loop
	// marks it closed under p.mu before collecting the outputs it finishes.
	p := s.pipes[key]
	joined := false
	if p != nil && p.ctx.Err() == nil && s.live.ProtectRecording(p.id) {
		p.mu.Lock()
		if !p.closed && p.ctx.Err() == nil {
			p.outputs[r.ID] = out
			joined = true
		}
		p.mu.Unlock()
	}
	out.p = p
	if !joined {
		var predecessor <-chan struct{}
		if p != nil {
			predecessor = p.done
		}
		runCtx, cancel := context.WithCancel(s.ctx)
		p = &pipeline{id: "dvr:" + rand.Text(), done: make(chan struct{}), key: key, ctx: runCtx, cancel: cancel,
			predecessor: predecessor, outputs: map[string]*output{r.ID: out}, subscribers: map[*liveReader]bool{}}
		p.generation.Store(1)
		out.p = p
		s.pipes[key] = p
		s.shared.Store(r.ChannelID, p)
		s.live.ProtectRecording(p.id)
		s.wg.Go(func() { s.copy(p) })
	}
	s.publish()
}

// failStart runs under s.mu. A recording that can't start frees its place
// in the plan, so a clash it was winning can record instead.
func (s *Service) failStart(ctx context.Context, r record, reason string) {
	r.State = "failed"
	r.Reason = reason
	now := s.opts.Now()
	r.EndedAt = &now
	_ = put(ctx, s.q, r, now.Unix())
	_ = s.plan(ctx)
	s.publish()
}
func (s *Service) copy(p *pipeline) {
	defer close(p.done)
	defer func() {
		p.cancel()
		p.mu.Lock()
		p.closed = true
		for r := range p.subscribers {
			close(r.chunks)
		}
		outputs := make([]*output, 0, len(p.outputs))
		for _, out := range p.outputs {
			outputs = append(outputs, out)
		}
		p.mu.Unlock()
		for _, out := range outputs {
			s.finish(out, "signal_lost")
		}
		s.mu.Lock()
		if s.pipes[p.key] == p {
			delete(s.pipes, p.key)
			if current, ok := s.shared.Load(p.key.channel); ok && current == p {
				s.shared.Delete(p.key.channel)
			}
		}
		s.mu.Unlock()
		if p.reserved != nil {
			s.opts.Log.Info("live source group ended", "source", p.key.source, "channel", p.key.channel,
				"group", p.id, "duration", time.Since(p.startedAt))
		}
	}()
	if p.predecessor != nil {
		select {
		case <-p.predecessor:
		case <-p.ctx.Done():
			return
		}
	}
	// lost opens a gap on every output, from its last bytes.
	lost := func(reason string) {
		now := s.opts.Now()
		p.mu.Lock()
		for _, out := range p.outputs {
			out.mu.Lock()
			out.lose(now, reason)
			out.mu.Unlock()
		}
		p.mu.Unlock()
	}
	wait := func() bool {
		if p.reopen.Swap(false) {
			return p.ctx.Err() == nil
		}
		timer := time.NewTimer(30 * time.Second)
		defer timer.Stop()
		select {
		case <-p.ctx.Done():
			return false
		case <-timer.C:
			return true
		}
	}
	release := p.reserved
	for release == nil && p.ctx.Err() == nil {
		var err error
		p.mu.Lock()
		warning := livetv.RecordingWarning{}
		for _, out := range p.outputs {
			out.mu.Lock()
			r := out.r
			out.mu.Unlock()
			warning = livetv.RecordingWarning{SourceID: r.SourceID, ChannelID: r.ChannelID, ID: r.ID, Title: r.Title, At: r.from(), UserID: r.UserID}
			break
		}
		p.mu.Unlock()
		release, err = s.live.ReserveRecording(p.ctx, p.id, p.key.source, warning)
		if err == nil {
			break
		}
		lost("tuners_busy")
		if !wait() {
			return
		}
	}
	if release == nil {
		return
	}
	defer release()
	buffer := make([]byte, 64<<10)
	attemptNumber := 0
	for p.ctx.Err() == nil {
		if attemptNumber > 0 {
			p.generation.Add(1)
			p.formatMu.Lock()
			p.format = nil
			p.formatMu.Unlock()
		}
		attemptNumber++
		// Each attempt has its own context: a tuner change ends this stream
		// and the loop reopens it, while the pipeline and its files go on.
		attempt, stop := context.WithCancel(p.ctx)
		p.attempt.Store(&stop)
		body, err := s.live.OpenTuner(attempt, p.key.source, p.key.channel)
		if err == nil {
			for {
				n, readErr := body.Read(buffer)
				if n > 0 {
					now := s.opts.Now()
					p.mu.Lock()
					for _, out := range p.outputs {
						out.mu.Lock()
						out.closeGap(now)
						written, e := out.file.Write(buffer[:n])
						out.r.Bytes += int64(written)
						out.lastData = now
						if e != nil || written != n {
							out.stop = true
							out.reason = "disk_full"
							// The scheduler finalizes and persists the failure.
						}
						out.mu.Unlock()
					}
					for reader := range p.subscribers {
						if !reader.active.Load() {
							continue
						}
						if !reader.offer(buffer[:n]) {
							s.opts.Log.Warn("live source reader exceeded buffered capacity", "group", p.id, "coalesced", reader.coalesce)
							reader.cancelCause(errLiveReaderBacklog)
							delete(p.subscribers, reader)
						}
					}
					if len(p.outputs) == 0 && len(p.subscribers) == 0 {
						p.cancel()
					}
					p.mu.Unlock()
				}
				if readErr != nil || p.ctx.Err() != nil {
					break
				}
			}
			_ = body.Close()
		}
		p.attempt.Store(nil)
		stop()
		if p.ctx.Err() != nil {
			return
		}
		lost("signal_lost")
		if !wait() {
			return
		}
	}
}

// finish ends one output: seal, publish and record the final state. It
// stays in s.running, marked finishing, until that state is written, so a
// tick or a cancel meanwhile neither restarts nor overwrites it.
func (s *Service) finish(out *output, reason string) {
	s.mu.Lock()
	out.mu.Lock()
	if s.running[out.r.ID] != out || out.finishing {
		out.mu.Unlock()
		s.mu.Unlock()
		return
	}
	out.finishing = true
	out.mu.Unlock()
	p := out.p
	if p != nil {
		p.mu.Lock()
		delete(p.outputs, out.r.ID)
		borrowed := len(p.outputs) == 0 && len(p.subscribers) > 0
		p.mu.Unlock()
		if borrowed {
			s.live.BorrowedRecording(p.id, p.key.channel, func() {
				if s.sessions != nil {
					s.sessions.StopSharedChannel(p.key.channel, "recording")
				}
				p.cancel()
				<-p.done
			})
		}
	}
	s.mu.Unlock()
	now := s.opts.Now()
	out.mu.Lock()
	out.closeGap(minTime(now, out.r.until()))
	r := out.r
	out.mu.Unlock()
	done := func() {
		s.mu.Lock()
		if s.running[r.ID] == out {
			delete(s.running, r.ID)
		}
		_ = s.plan(context.Background())
		s.mu.Unlock()
		close(out.done)
	}
	// Hold the same file through writer close: Windows finalizes its write
	// timestamp on close, while a raw pathname could name a replacement.
	written := r.Bytes
	r.FileMtimeNano = 0
	if seal, err := sealOutput(out.file, r.PartialPath); err == nil {
		defer func() { _ = seal.Close() }()
		r.Bytes = seal.info.Size()
		r.FileMtimeNano = seal.info.ModTime().UnixNano()
	} else {
		// The writer is closed either way. A file system that refuses the
		// sync still has the bytes: measure the closed file by its path,
		// accepting it only at exactly the length this writer wrote.
		r.Bytes = written
		_ = s.measureClosed(context.Background(), &r)
	}
	r.EndedAt = &now
	if s.ctx.Err() != nil {
		// On shutdown keep the durable state and partial path. Startup appends
		// rather than mistaking this interruption for a completed broadcast.
		_ = put(context.Background(), s.q, r, now.Unix())
		s.mu.Lock()
		if s.running[r.ID] == out {
			delete(s.running, r.ID)
		}
		s.mu.Unlock()
		close(out.done)
		return
	}
	if r.Bytes == 0 {
		r.State = "failed"
		r.Reason = reason
		_ = os.Remove(r.PartialPath)
	} else {
		r.State = "done"
		r.Reason = reason
		if reason != "" && now.Before(r.until()) {
			r.Gaps = append(r.Gaps, Gap{Start: now, End: r.until(), Reason: reason})
		}
		if reason != "" || len(r.Gaps) > 0 {
			r.State = "partial"
		}
		if err := s.publishSealed(context.Background(), &r); err != nil {
			r.State = "partial"
			r.Reason = "finish_pending"
		} else if s.opts.Scanner != nil {
			s.opts.Scanner.RequestPaths(r.LibraryID, []string{r.Path})
		}
	}
	_ = put(context.Background(), s.q, r, now.Unix())
	done()
	if p != nil {
		p.mu.Lock()
		if len(p.outputs) == 0 && len(p.subscribers) == 0 {
			p.cancel()
		}
		p.mu.Unlock()
	}
	s.publish()
	s.signal()
}
func (s *Service) finishRecovered(ctx context.Context, r record) {
	now := s.opts.Now()
	r.EndedAt = &now
	partial, err := os.Lstat(r.PartialPath)
	switch {
	case err == nil:
		// An extant partial is authoritative. A colliding final never donates
		// its bytes or fingerprint to this recording's ownership row.
		if !partial.Mode().IsRegular() || s.validateOwned(ctx, r, r.PartialPath, false) != nil {
			r.State = "failed"
			r.Reason = "output_changed"
			break
		}
		if r.Reason == "finish_pending" && (r.FileMtimeNano == 0 || s.validateOwned(ctx, r, r.PartialPath, true) != nil) {
			r.State = "failed"
			r.Reason = "output_changed"
			break
		}
		r.Bytes = partial.Size()
		r.FileMtimeNano = partial.ModTime().UnixNano()
		if r.Bytes == 0 {
			r.State = "failed"
			r.Reason = "server_restarted"
			break
		}
		r.State = "partial"
		r.Reason = "server_restarted"
		if err = s.publishSealed(ctx, &r); err != nil {
			r.Reason = "finish_pending"
		} else if s.opts.Scanner != nil {
			s.opts.Scanner.RequestPaths(r.LibraryID, []string{r.Path})
		}
	case errors.Is(err, os.ErrNotExist):
		// A crash after atomic publication but before its final DB write is
		// reconciled only by the sealed identity committed before that rename.
		if s.publishedMatches(ctx, r) {
			r.State = "partial"
			r.Reason = "server_restarted"
			if s.opts.Scanner != nil {
				s.opts.Scanner.RequestPaths(r.LibraryID, []string{r.Path})
			}
		} else {
			r.State = "failed"
			r.Reason = "output_missing"
		}
	default:
		r.State = "failed"
		r.Reason = "folder_unavailable"
	}
	_ = put(ctx, s.q, r, now.Unix())
	s.publish()
}
func (s *Service) Source(ctx context.Context, id string) (Source, error) {
	r, err := s.get(ctx, id)
	if err != nil {
		return Source{}, err
	}
	s.mu.Lock()
	out := s.running[id]
	s.mu.Unlock()
	var result Source
	if out != nil {
		out.mu.Lock()
		r = out.r
		out.mu.Unlock()
		result = Source{Path: r.PartialPath, Recording: r.Recording, Done: out.done}
	}
	if out == nil {
		if r.Reason == "finish_pending" {
			return Source{}, ErrUnavailable
		}
		if r.State != "done" && r.State != "partial" {
			return Source{}, ErrUnavailable
		}
		done := make(chan struct{})
		close(done)
		result = Source{Path: r.Path, Recording: r.Recording, Done: done}
	}
	if err = s.validateOwned(ctx, r, result.Path, out == nil); err != nil {
		// Publication may race a seek. Re-read once after the writer closes.
		if out != nil {
			select {
			case <-out.done:
				return s.Source(ctx, id)
			default:
			}
		}
		return Source{}, ErrUnavailable
	}
	return result, nil
}

// Activity is an in-memory snapshot for tuner holders and local power policy.
// It never scans recording history or touches the filesystem.
func (s *Service) Activity() []Recording {
	s.mu.Lock()
	outputs := make([]*output, 0, len(s.running))
	for _, out := range s.running {
		outputs = append(outputs, out)
	}
	s.mu.Unlock()
	items := make([]Recording, 0, len(outputs))
	for _, out := range outputs {
		out.mu.Lock()
		r := out.r.Recording
		r.Gaps = append([]Gap{}, r.Gaps...)
		out.mu.Unlock()
		items = append(items, r)
	}
	return items
}

func (s *Service) checkpoint(ctx context.Context, out *output) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running[out.r.ID] != out {
		return
	}
	out.mu.Lock()
	r := out.r
	finishing := out.finishing
	out.mu.Unlock()
	if finishing {
		return
	}
	_ = put(ctx, s.q, r, s.opts.Now().Unix())
}
