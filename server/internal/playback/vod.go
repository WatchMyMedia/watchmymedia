package playback

import (
	"bufio"
	"bytes"
	"cmp"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sync/singleflight"

	"watchmymedia/server/internal/ffexec"
)

// VOD sessions serve the whole file as one VOD playlist, so native players
// (AVPlayer above all) show a real timeline and can seek anywhere. The
// playlist lists every segment before any exists: on a fixed grid when the
// video is transcoded (keyframes are forced onto it), on groups of the
// file's own keyframes when it is copied.
//
// Each ffmpeg run cuts a piece at every keyframe. A segment is served as
// the pieces that cover it, joined, with their decode times made absolute
// (see fmp4.go), so pieces from different runs fit together. A request for
// a segment no run is about to reach starts a new run there; the old run
// stops but its pieces stay usable. A run pauses once it is vodAhead past
// the latest segment asked for, and goes on from where it paused when
// requests come within vodResume of it, so ffmpeg works no more than a few
// minutes ahead of the player. The pieces also stay within a byte budget,
// three quarters of the per-session storage limit (vodBudget): a few
// minutes of a high bit rate file is more than that limit, and the storage
// watchdog stops a session past it. At the budget, pieces behind the player
// go first, then those furthest ahead, and the run pauses until the player
// has used up half the budget. After the idle timeout ffmpeg stops and the
// pieces go, but the playlist stays valid for hours and the next request
// starts ffmpeg again where it is needed.
const (
	vodSegmentSeconds = 6
	// vodLookahead is how far ahead of a running ffmpeg a request may be
	// and still wait for it rather than start a new run.
	vodLookahead = 45 * time.Second
	// vodTolerance is how far a piece's start may sit from where it was
	// expected: edit lists store run starts in milliseconds.
	vodTolerance = 25 * time.Millisecond
	// vodKeepBehind is how long pieces are kept behind the segment served last.
	vodKeepBehind = 2 * time.Minute
	// vodAhead is how far past the latest segment asked for a run encodes
	// before it pauses, and how much a seek keeps of what lies ahead of it;
	// vodResume is how close to where a run paused requests come before it
	// goes on.
	vodAhead  = 4 * time.Minute
	vodResume = time.Minute
	vodWait   = 30 * time.Second
	// vodBudgetShare is the share of the per-session storage limit a VOD
	// session's pieces may fill. The rest is room for the piece ffmpeg is
	// writing, the init segments and the playlists, which the storage
	// watchdog counts too.
	vodBudgetShare = 0.75
	// vodBudgetRefill is how close to the end of a run paused at the byte
	// budget the player comes before it goes on, however full the budget.
	vodBudgetRefill = 3 * vodSegmentSeconds * time.Second
	// VODKeep is how long a VOD session stays usable without requests.
	VODKeep = 6 * time.Hour
)

// VODOptions describe the file to a VOD session.
type VODOptions struct {
	DurationMs int64
	// Keyframes are the video keyframes in microseconds from the start of
	// the file; needed when the video is copied.
	Keyframes []int64
	FrameRate float64
	// Bandwidth is the peak bit rate for the master playlist.
	Bandwidth int64
	// VideoRange is SDR, PQ or HLG.
	VideoRange string
	// Codecs is the master playlist's CODECS attribute (HLSCodecs), or ""
	// to leave it out; SupplementalCodecs its SUPPLEMENTAL-CODECS
	// (HLSSupplementalCodecs), written only beside CODECS.
	Codecs             string
	SupplementalCodecs string
	// SeparateAudio serves the audio as its own rendition (audio.m3u8),
	// cut from the muxed segments, and the video without it.
	SeparateAudio bool
	// AudioChannels and AudioLanguage (BCP 47) describe that rendition.
	AudioChannels int
	AudioLanguage string
	Subtitles     []VODSubtitle
	// Subtitle returns a subtitle track as WebVTT in file time.
	Subtitle func(ctx context.Context, id string) ([]byte, error)
	// IFrames offers the file's seek thumbnails as an I-frame stream
	// (iframes.go); nil offers none.
	IFrames *IFrameStream
}

// VODSubtitle is a text subtitle offered as a rendition.
type VODSubtitle struct {
	ID       string
	Name     string
	Language string
	Forced   bool
	Default  bool
}

// File names inside a VOD session, as served under /playback/{session}/.
const (
	VODMaster    = "master.m3u8"
	VODMedia     = "video.m3u8"
	VODInit      = "init.mp4"
	VODAudio     = "audio.m3u8"
	VODAudioInit = "audio-init.mp4"
)

// copyPlan groups keyframes into segments of at least target, all times in
// microseconds. The last boundary is the duration.
func copyPlan(keys []int64, duration, target int64) []int64 {
	b := []int64{0}
	for _, k := range keys {
		last := b[len(b)-1]
		// Skip keyframes too close to the previous cut or to the end, so
		// no segment is a sliver.
		if k-last >= target && duration-k >= target/4 {
			b = append(b, k)
		}
	}
	return append(b, duration)
}

// gridPlan cuts every step microseconds, folding a short tail into the last segment.
func gridPlan(duration, step int64) []int64 {
	b := []int64{0}
	for t := step; duration-t >= step/4; t += step {
		b = append(b, t)
	}
	return append(b, duration)
}

type vodState struct {
	m            *Manager
	s            *Session
	ctx          context.Context
	viewerCtx    context.Context
	viewerCancel context.CancelFunc
	spec         Spec
	opts         VODOptions
	bounds       []int64

	// live counts ffmpeg processes still running, read without mu so the
	// manager can count sessions under its own lock.
	live atomic.Int32
	// joins counts segments joined from pieces (for tests).
	joins atomic.Int64
	// flight shares one join among the requests for a segment, as the
	// video and audio requests of a SeparateAudio session are.
	flight singleflight.Group

	mu       sync.Mutex
	run      *vodRun
	runs     int
	pieces   []*vodPiece // by start time
	init     []byte      // the first run's init segment, without edit lists
	video    uint32
	audio    uint32 // 0 when the output has no audio
	cues     map[string][]vttCue
	warnInit bool
	// videoStart is where the video's first frame is, µs, once a run from
	// the start of the file has said. It is after zero when the audio
	// begins first, as it does in many recordings, and the video is
	// copied; a transcode fills the gap (see Manager.args).
	videoStart int64
	// changed is closed, and replaced, whenever there is something new for
	// requests waiting on it: pieces, the init segment, a run ending.
	changed chan struct{}
	// joined holds the last segments served, newest first.
	joined []joinedSegment
	// A shared copy's first complete segment and init are admitted together.
	// Only the small init stays with this viewer; media stays in the bounded
	// cache and is fetched under this viewer's own route.
	startupIndex      int
	startupInit       []byte
	startupHash       string
	startupVideo      uint32
	startupAudio      uint32
	startupProduction time.Duration
	startupProducedAt time.Time
	// Private ladder requests share one seekable worker per viewer/rung.
	// Serialize cache misses so parallel segment requests cannot cancel one
	// another's cold seek. Cache hits and other viewers remain independent.
	segmentWork chan struct{}
	// head is where the latest segment asked for ends, µs: where the
	// player has buffered to, near enough.
	head int64
	// ahead and resume are vodAhead and vodResume (shorter in tests).
	ahead, resume time.Duration
	// bytes is the size of the indexed pieces' files, and budget how much
	// of that the session keeps (vodBudget); zero is no byte budget.
	bytes, budget int64
}

type vodRun struct {
	n       int
	seg     int
	startUs int64
	cancel  context.CancelFunc
	done    chan struct{}
	// exitErr is ffmpeg's failure, set before done closes.
	exitErr error
	cmd     *ffexec.Cmd
	// Keep final publication separate from mu: a seek waits for done while
	// holding vodState.mu. Only the playlist reader and writer exit use this.
	playlistMu sync.Mutex
	// Under vodState.mu:
	// err is why the server gave up on the run while ffmpeg ran.
	err     error
	init    *fmp4Init
	listed  int
	reached int64 // end of the latest piece indexed, µs
	next    int64 // where the next piece starts, µs
	// trimmed is set once pieces this run made have been deleted, so it
	// no longer holds everything from where it started.
	trimmed bool
	// paused is set when the server stopped the run for being too far
	// ahead of the player: its pieces are good up to reached, and nothing
	// past it was made. full is set as well when that was the byte budget.
	paused, full bool
	// plOffset is how much of the run's playlist has been read, up to the
	// end of plLast, the last piece's line.
	plOffset int64
	plLast   string
}

func (r *vodRun) finished() bool {
	select {
	case <-r.done:
		return true
	default:
		return false
	}
}

// failed is why the run is of no more use: the server gave up on it, or
// ffmpeg failed. Call it under vodState.mu.
func (r *vodRun) failed() error {
	if r.err != nil {
		return r.err
	}
	if r.finished() {
		return r.exitErr
	}
	return nil
}

// passed reports whether the run has indexed pieces up to us.
func (r *vodRun) passed(us int64) bool { return r.reached >= us }

type vodPiece struct {
	start, end int64
	file       string
	run        *vodRun
	// pins counts requests reading the file; a piece deleted meanwhile is
	// dropped, and the last of them deletes the file.
	pins    int
	dropped bool
	size    int64
}

// joinedSegment is a segment as served, kept for the next request for it.
type joinedSegment struct {
	k int
	b []byte
}

// The joined segments a session keeps: enough for the audio request of a
// SeparateAudio segment to find what the video request joined.
const (
	vodJoinedSegments = 3
	vodJoinedBytes    = 64 << 20
)

func newVOD(m *Manager, s *Session, ctx context.Context, spec Spec) (*vodState, error) {
	o := *spec.VOD
	dur := o.DurationMs * 1000
	if dur <= 0 {
		return nil, errors.New("vod: unknown duration")
	}
	v := &vodState{m: m, s: s, ctx: ctx, spec: spec, opts: o, cues: map[string][]vttCue{}, changed: make(chan struct{}),
		ahead: cmp.Or(m.vodAhead, vodAhead), resume: cmp.Or(m.vodResume, vodResume), budget: vodBudget(m.resources.MaxSessionBytes)}
	if spec.SharedOutput && spec.ReserveSlot {
		v.segmentWork = make(chan struct{}, 1)
	}
	if spec.Video.Copy {
		if len(o.Keyframes) < 2 {
			return nil, errors.New("vod: copied video needs its keyframes")
		}
		v.bounds = copyPlan(o.Keyframes, dur, vodSegmentSeconds*1e6)
	} else {
		v.bounds = gridPlan(dur, vodSegmentSeconds*1e6)
	}
	v.startupIndex = v.segmentAt(spec.StartMs * 1000)
	v.viewerCtx, v.viewerCancel = context.WithCancel(ctx)
	return v, nil
}

// Segments is how many segments the playlist lists.
func (v *vodState) Segments() int { return len(v.bounds) - 1 }

// vodBudget is how many bytes of pieces a VOD session keeps under the
// per-session storage limit (zero for none).
func vodBudget(limit int64) int64 {
	if limit <= 0 {
		return 0
	}
	return int64(float64(limit) * vodBudgetShare)
}

func (v *vodState) segmentAt(us int64) int {
	k := sort.Search(len(v.bounds), func(i int) bool { return v.bounds[i] > us }) - 1
	return min(max(k, 0), v.Segments()-1)
}

// waitLocked is what to wait on for something new.
func (v *vodState) waitLocked() <-chan struct{} {
	if v.changed == nil {
		v.changed = make(chan struct{})
	}
	return v.changed
}

// notifyLocked wakes the requests waiting for something new.
func (v *vodState) notifyLocked() {
	if v.changed != nil {
		close(v.changed)
	}
	v.changed = make(chan struct{})
}

// startRunLocked starts ffmpeg at segment k, stopping the current run.
func (v *vodState) startRunLocked(k int) error {
	if v.ctx.Err() != nil {
		return ErrStopped
	}
	if v.run != nil {
		v.run.cancel()
		<-v.run.done // do not overlap processes while seeking
	}
	if !v.m.reserveRun(v.s) {
		return v.m.tooMany()
	}
	defer v.s.starting.Store(false)
	release, err := v.m.processes.acquire(v.ctx)
	if err != nil {
		return err
	}
	v.runs++
	r := &vodRun{n: v.runs, seg: k, startUs: v.bounds[k], next: v.bounds[k], done: make(chan struct{})}
	spec := v.spec
	spec.StartMs = v.seekTarget(k) / 1000
	spec.run = r.n
	args := v.m.args(spec, v.s.Dir)
	ctx, cancel := context.WithCancel(v.ctx)
	r.cancel = cancel
	cmd := ffexec.Command(ctx, v.m.ffmpeg, args...)
	cmd.Dir = v.s.Dir // where the init segment lands on Windows (see Manager.start)
	// A download must never slow a play down, and someone is waiting for it.
	cmd.LowPriority = v.spec.Download && !v.m.downloadsNormal.Load()
	cmd.Awaited = cmd.LowPriority
	r.cmd = cmd
	v.s.outMs.Store(0)
	v.s.runStart.Store(spec.StartMs)
	cmd.Stdout = &progressWriter{s: v.s}
	if err := cmd.Start(); err != nil {
		release()
		cancel()
		if v.ctx.Err() != nil {
			return ErrStopped
		}
		return fmt.Errorf("start ffmpeg: %w", err)
	}
	v.live.Add(1)
	v.run = r
	v.notifyLocked()
	v.m.log.Info("playback run started", "session", v.s.ID, "run", r.n, "segment", k, "start_ms", spec.StartMs, "encoder", v.s.Encoder)
	v.m.log.Debug("ffmpeg", "args", strings.Join(args, " "))
	go func() {
		err := cmd.Wait()
		release()
		if err == nil && ctx.Err() == nil {
			r.playlistMu.Lock()
			err = finishPlaylist(filepath.Join(v.s.Dir, fmt.Sprintf("r%d.m3u8", r.n)))
			r.playlistMu.Unlock()
		}
		if err != nil && ctx.Err() == nil {
			r.exitErr = err
			v.m.log.Error("playback run failed", "session", v.s.ID, "run", r.n, "err", err)
		}
		if ctx.Err() != nil {
			// A run stopped part way leaves the piece it was writing as a
			// .tmp file, which no playlist lists: it would only take room.
			tmps, _ := filepath.Glob(filepath.Join(v.s.Dir, fmt.Sprintf("r%d-*.m4s.tmp", r.n)))
			for _, f := range tmps {
				_ = os.Remove(f)
			}
		}
		v.live.Add(-1)
		close(r.done)
	}()
	go v.watch(r)
	return nil
}

// vodPoll is how often a run's playlist is read for new pieces.
const vodPoll = 100 * time.Millisecond

// watch indexes the run's pieces as ffmpeg finishes them, until the run
// ends or another replaces it, and wakes the requests waiting for them.
// It is the one reader of the run's playlist, however many requests wait.
func (v *vodState) watch(r *vodRun) {
	t := time.NewTicker(vodPoll)
	defer t.Stop()
	for {
		finished := false
		select {
		case <-r.done:
			finished = true
		case <-t.C:
		}
		if !v.refreshRun(r, finished) || finished {
			return
		}
	}
}

// refreshRun indexes what is new of run r, which must still be the
// session's run, and wakes the waiting requests when there is something.
// It reports whether r is still the session's run.
func (v *vodState) refreshRun(r *vodRun, finished bool) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.run != r {
		return false
	}
	listed, init, failed := r.listed, r.init, r.err != nil
	v.refreshLocked()
	if finished || r.listed != listed || r.init != init || (r.err != nil) != failed {
		v.notifyLocked()
	}
	if v.budget > 0 && v.bytes > v.budget {
		// Also once the run has ended: a run that copies the video can make
		// many pieces between two looks at its playlist.
		v.fitBudgetLocked(r)
	}
	if !finished && !r.paused && v.head > 0 && r.reached-v.head > v.ahead.Microseconds() {
		// Far enough ahead of the player: stop, keeping the pieces, until
		// requests come close (requested).
		r.paused = true
		r.cancel()
		v.m.log.Info("playback run paused ahead of the player", "session", v.s.ID, "run", r.n, "reached_ms", r.reached/1000, "requested_ms", v.head/1000)
	}
	return true
}

// fitBudgetLocked brings the session's pieces back within its byte budget
// and pauses run r, the session's run, when it cannot go on. Pieces behind
// the player go first, down to half the budget so that the run has room to
// go on; then, if that is not enough, pieces furthest ahead of the player,
// which a run makes again when the player gets there.
func (v *vodState) fitBudgetLocked(r *vodRun) {
	v.shedBehindLocked(v.budget / 2)
	trimmed := false
	if v.bytes > v.budget {
		kept := len(v.pieces)
		for kept > 0 && v.bytes > v.budget && v.pieces[kept-1].start >= v.head {
			kept--
			v.dropLocked(v.pieces[kept])
			trimmed = true
		}
		clear(v.pieces[kept:])
		v.pieces = v.pieces[:kept]
		// The run goes on, when it does, from the end of what is left of it.
		reached := r.startUs
		for _, p := range v.pieces {
			if p.run == r {
				reached = max(reached, p.end)
			}
		}
		r.reached = min(r.reached, reached)
	}
	// Shedding behind may have made room enough for the run to go on; if
	// not, it pauses, as it does when its own end was cut.
	if !r.paused && (trimmed || v.bytes > v.budget) {
		r.paused, r.full = true, true
		r.cancel()
		v.m.log.Info("playback run paused at the session's byte budget", "session", v.s.ID, "run", r.n, "reached_ms", r.reached/1000,
			"requested_ms", v.head/1000, "bytes", v.bytes, "byte_budget", v.budget)
	}
}

// shedBehindLocked deletes pieces behind the player, oldest first, until
// the session's pieces fit in target bytes or none behind are left. It
// keeps the segment before the latest asked for, whose pieces a request
// may be about to read.
func (v *vodState) shedBehindLocked(target int64) {
	if v.head <= 0 {
		return
	}
	k := v.segmentAt(v.head - 1)
	cutoff := v.bounds[max(k-1, 0)]
	kept := v.pieces[:0]
	for _, p := range v.pieces {
		if v.bytes > target && p.end <= cutoff {
			v.dropLocked(p)
			continue
		}
		kept = append(kept, p)
	}
	clear(v.pieces[len(kept):])
	v.pieces = kept
}

// requested notes that segment k was asked for, and starts a paused run
// again from where it stopped when k is within v.resume of that. A request
// far past it is a seek, which ensureLocked handles.
func (v *vodState) requested(k int) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.head = v.bounds[k+1]
	r := v.run
	if r == nil || !r.paused || r.failed() != nil {
		return
	}
	at := v.bounds[k]
	if at < r.reached-v.resume.Microseconds() || at > r.reached+vodLookahead.Microseconds() {
		return
	}
	if low := v.budget / 2; r.full && v.bytes > low {
		// Paused at the byte budget: go on once there is room for the run
		// to make a fair stretch before it pauses again, rather than
		// restarting ffmpeg for every segment, or once the player is
		// about to run out of pieces.
		v.shedBehindLocked(low)
		if v.bytes > low && at < r.reached-vodBudgetRefill.Microseconds() {
			return
		}
	}
	next := v.segmentAt(r.reached)
	if err := v.startRunLocked(next); err != nil {
		// The pieces there still serve; a request past them tries again.
		v.m.log.Warn("playback run could not go on", "session", v.s.ID, "segment", next, "err", err)
		return
	}
	v.m.log.Info("playback run goes on", "session", v.s.ID, "segment", next, "requested_ms", v.head/1000)
}

// seekTarget is where a run for segment k asks ffmpeg to start, in µs.
// Copied video starts at the keyframe before the target, but Matroska
// seeking lands a keyframe early when the target is right on one (in open
// GOPs at least), so the target sits between the segment's keyframe and
// the next: half way, at most half a second in. A transcode starts on the
// dot.
func (v *vodState) seekTarget(k int) int64 {
	t := v.bounds[k]
	if !v.spec.Video.Copy || t == 0 {
		return t
	}
	keys := v.opts.Keyframes
	i := sort.Search(len(keys), func(i int) bool { return keys[i] > t })
	gap := int64(1_000_000)
	if i < len(keys) {
		gap = keys[i] - t
	}
	return t + min(gap/2, 500_000)
}

// anchor is where a run really started, from its init's edit list (in
// milliseconds) snapped to the keyframe or grid time it must be.
func (v *vodState) anchor(startUs int64) int64 {
	tol := 50 * time.Millisecond.Microseconds()
	if v.spec.Video.Copy {
		keys := v.opts.Keyframes
		i := sort.Search(len(keys), func(i int) bool { return keys[i] >= startUs-tol })
		if i < len(keys) && keys[i] <= startUs+tol {
			return keys[i]
		}
		return startUs
	}
	k := v.segmentAt(startUs + tol)
	if b := v.bounds[k]; startUs >= b-tol && startUs <= b+v.slack() {
		return b
	}
	return startUs
}

// playlistEntry is a piece as the run's playlist lists it.
type playlistEntry struct {
	dur  float64
	name string
}

// newEntriesLocked reads the pieces added to run r's playlist since the
// last read. ffmpeg rewrites the playlist whole each time but only ever
// adds pieces to it; its header can change length (the target duration
// grows), so the read goes on from where it ended only while the line it
// ended on is still there, and starts again from the top otherwise.
func (v *vodState) newEntriesLocked(r *vodRun) []playlistEntry {
	r.playlistMu.Lock()
	defer r.playlistMu.Unlock()
	f, err := openPlaylist(filepath.Join(v.s.Dir, fmt.Sprintf("r%d.m3u8", r.n)))
	if err != nil {
		return nil
	}
	defer func() { _ = f.Close() }()
	if r.plOffset > 0 {
		last := []byte(r.plLast + "\n")
		got := make([]byte, len(last))
		if _, err := f.ReadAt(got, r.plOffset-int64(len(last))); err != nil || !bytes.Equal(got, last) {
			r.plOffset = 0
		}
	}
	if _, err := f.Seek(r.plOffset, io.SeekStart); err != nil {
		return nil
	}
	skip := 0
	if r.plOffset == 0 {
		skip = r.listed // a read from the top passes what is indexed
	}
	var out []playlistEntry
	var dur float64
	pos := r.plOffset
	rd := bufio.NewReader(f)
	for {
		line, err := rd.ReadString('\n')
		if err != nil {
			break // the end, or a line not finished yet
		}
		pos += int64(len(line))
		t := strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(t, "#EXTINF:"):
			d, _, _ := strings.Cut(strings.TrimPrefix(t, "#EXTINF:"), ",")
			dur, _ = strconv.ParseFloat(d, 64)
		case t != "" && !strings.HasPrefix(t, "#"):
			if skip > 0 {
				skip--
			} else {
				out = append(out, playlistEntry{dur, t})
			}
			r.plOffset, r.plLast = pos, strings.TrimSuffix(line, "\n")
		}
	}
	return out
}

// refreshLocked indexes the pieces the current run has finished.
func (v *vodState) refreshLocked() {
	r := v.run
	if r == nil || r.err != nil {
		return
	}
	if r.init == nil {
		// The playlist appears after the first piece, so once it is there
		// the init segment is whole.
		if _, err := os.Stat(filepath.Join(v.s.Dir, fmt.Sprintf("r%d.m3u8", r.n))); err != nil {
			return
		}
		b, err := ReadShared(filepath.Join(v.s.Dir, fmt.Sprintf("r%d-init.mp4", r.n)))
		if err != nil {
			return
		}
		in, err := parseInit(b)
		if err == nil && in.Video == 0 {
			err = errors.New("no video track")
		}
		if err != nil {
			// Nothing this run makes can be served: stop it, and fail the
			// requests waiting for it now rather than when they time out.
			r.err = fmt.Errorf("unusable init segment: %w", err)
			r.cancel()
			v.m.log.Error("playback run failed", "session", v.s.ID, "run", r.n, "err", r.err)
			return
		}
		r.init = &in
		// ffmpeg may have started before the segment asked for (a copy
		// seek lands on a keyframe at or before its target); the pieces
		// count from where it really did.
		r.next = v.anchor(in.Tracks[in.Video].StartUs)
		r.startUs = r.next
		if r.seg == 0 {
			// It was not seeked, so nothing comes before its first piece.
			v.videoStart = r.startUs
		}
		if v.init == nil {
			v.init, v.video, v.audio = in.Stripped, in.Video, in.Audio
		} else if !bytes.Equal(v.init, in.Stripped) && !v.warnInit {
			v.warnInit = true
			v.m.log.Warn("playback run's init segment differs from the first run's", "session", v.s.ID, "run", r.n)
		}
	}
	// A piece starts where the one before it ended, and the first where the
	// run started: ffmpeg's durations are the gaps between the keyframes'
	// own timestamps. The fragments cannot say: the muxer rewrites the
	// first frame's presentation time to the earliest in the piece, which
	// in an open GOP (x265's default) is a leading frame before the keyframe.
	tol := vodTolerance.Microseconds()
	for _, e := range v.newEntriesLocked(r) {
		path := filepath.Join(v.s.Dir, e.name)
		p := &vodPiece{start: r.next, file: path, run: r}
		if st, err := os.Stat(path); err == nil {
			p.size = st.Size()
		}
		p.end = p.start + int64(math.Round(e.dur*1e6))
		r.next = p.end
		r.listed++
		r.reached = max(r.reached, p.end)
		i := sort.Search(len(v.pieces), func(i int) bool { return v.pieces[i].start >= p.start-tol })
		if i < len(v.pieces) && v.pieces[i].start <= p.start+tol {
			_ = os.Remove(path) // already have it from an earlier run
			continue
		}
		v.pieces = slices.Insert(v.pieces, i, p)
		v.bytes += p.size
	}
}

// slack is how late after a boundary a segment's first piece may start: a
// forced keyframe falls on the first frame at or after the grid time.
func (v *vodState) slack() int64 {
	fps := v.opts.FrameRate
	if fps <= 0 {
		fps = 10
	}
	return max(int64(1.2e6/fps), vodTolerance.Microseconds())
}

// chainLocked returns the pieces that cover segment k, if all are there:
// the piece that starts at the boundary (or up to a frame after it) and
// the pieces joined to it up to the next boundary. Segment 0 starts with
// the video's first frame, wherever that is.
func (v *vodState) chainLocked(k int) ([]*vodPiece, bool) {
	tol := vodTolerance.Microseconds()
	from, to := v.bounds[k], v.bounds[k+1]
	if k == 0 && v.videoStart < to {
		from = max(from, v.videoStart)
	}
	last := k == v.Segments()-1
	var out []*vodPiece
	cur, late := from, v.slack()
	i := sort.Search(len(v.pieces), func(i int) bool { return v.pieces[i].start >= cur-tol })
	for i < len(v.pieces) && v.pieces[i].start <= cur+late {
		late = tol // after the first piece, pieces follow each other exactly
		p := v.pieces[i]
		out = append(out, p)
		cur = p.end
		if cur >= to-tol {
			return out, true
		}
		i++
	}
	// The last segment ends where the video does, which may be before the
	// file's duration; it is whole once the run that made it has finished.
	if last && len(out) > 0 {
		r := out[len(out)-1].run
		if r == v.run && r.finished() && !r.paused && r.failed() == nil {
			v.refreshLocked()
			if r.listed > 0 && out[len(out)-1].end >= r.reached {
				return out, true
			}
		}
	}
	return nil, false
}

// ensureLocked starts a run at k unless the current one will reach k soon.
// Starting one is a seek, or a run that ended or was trimmed: pieces more
// than v.ahead past k go, as a run playing from k would not have made them.
func (v *vodState) ensureLocked(k int) error {
	if r := v.run; r != nil && !r.finished() && r.seg <= k && !r.passed(v.bounds[k+1]) {
		reached := max(r.reached, r.startUs)
		wait := time.Duration(v.bounds[k]-reached)*time.Microsecond <= vodLookahead
		if v.spec.SharedOutput && v.spec.ReserveSlot && r.seg != k {
			// A private ladder promises cold-rung capacity. At a slow
			// encoder, 45 seconds of timeline is far longer than the
			// request's readiness budget. Include the segment's end and
			// reserve half the timeout for startup/encoding after a seek.
			// Never restart the run already producing this exact segment.
			speed, _ := v.s.Progress()
			if math.IsNaN(speed) || math.IsInf(speed, 0) || speed <= 0 || speed > 1 {
				speed = 1
			}
			remaining := time.Duration(v.bounds[k+1]-reached) * time.Microsecond
			budget := vodWait
			if v.s.ladder.Load() != nil {
				budget = ladderSegmentDeadline
			}
			// Keep the run already reaching this segment. Restarting at an
			// adjacent boundary throws away AAC priming and opens an audible
			// gap under load. The caller's deadline still bounds the wait.
			adjacent := v.bounds[k] <= reached+v.slack()
			wait = wait && (adjacent || remaining <= time.Duration(float64(budget/2)*speed))
		}
		if wait {
			return nil
		}
	}
	v.dropAheadLocked(k)
	return v.startRunLocked(k)
}

// dropAheadLocked deletes pieces that start more than v.ahead past
// segment k's end.
func (v *vodState) dropAheadLocked(k int) {
	cutoff := v.bounds[k+1] + v.ahead.Microseconds()
	kept := v.pieces[:0]
	for _, p := range v.pieces {
		if p.start > cutoff {
			v.dropLocked(p)
			continue
		}
		kept = append(kept, p)
	}
	clear(v.pieces[len(kept):])
	v.pieces = kept
}

// Segment returns segment k, starting or moving ffmpeg as needed and
// waiting for it. The bytes may be shared with other requests: do not
// change them.
func (v *vodState) Segment(ctx context.Context, k int) ([]byte, error) {
	if k < 0 || k >= v.Segments() {
		return nil, os.ErrNotExist
	}
	if v.spec.sharedKey != "" && v.m.renditions != nil {
		if err := v.sharedSourceUnchanged(); err != nil {
			return nil, err
		}
		// The cache has no viewer authority; the HTTP route still checks the
		// requesting session and counts every response independently.
		v.requested(k)
		if k == v.startupIndex {
			_, segment, err := v.sharedStartup(ctx)
			return segment, err
		}
		v.mu.Lock()
		initHash := v.startupHash
		v.mu.Unlock()
		if initHash == "" {
			if _, _, err := v.sharedStartup(ctx); err != nil {
				return nil, err
			}
			v.mu.Lock()
			initHash = v.startupHash
			v.mu.Unlock()
		}
		key := fmt.Sprintf("%s/init/%s/segment/%d", v.spec.sharedKey, initHash, k)
		b, release, err := v.sharedRendition(ctx, key,
			func(work context.Context) ([]byte, error) {
				b, err := v.segmentUncached(work, k)
				if err != nil {
					return nil, err
				}
				if err := v.sharedSourceUnchanged(); err != nil {
					return nil, err
				}
				if err := v.sharedGeneratedInitMatches(); err != nil {
					return nil, err
				}
				return b, nil
			}, validateRenditionFragment)
		if err == nil {
			// The response keeps one bounded copy after releasing the cache
			// pin; an eviction cannot invalidate bytes while HTTP writes.
			out := bytes.Clone(b)
			release()
			return out, nil
		}
		if !errors.Is(err, ErrStopped) || ctx.Err() != nil || v.viewerCtx.Err() != nil {
			return nil, err
		}
		// A different viewer's producer stopped during this request. This
		// viewer retains its own VOD worker and can make the missing segment.
	}
	return v.segmentUncached(ctx, k)
}

func (v *vodState) segmentUncached(ctx context.Context, k int) ([]byte, error) {
	if v.segmentWork != nil {
		select {
		case v.segmentWork <- struct{}{}:
			defer func() { <-v.segmentWork }()
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-v.ctx.Done():
			return nil, ErrStopped
		}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	v.requested(k)
	if b := v.joinedSegment(k); b != nil {
		return b, nil
	}
	chain, err := v.waitChain(ctx, k)
	if err != nil {
		return nil, err
	}
	defer v.unpin(chain)
	b, err, _ := v.flight.Do(strconv.Itoa(k), func() (any, error) {
		if b := v.joinedSegment(k); b != nil {
			return b, nil
		}
		b, err := joinPieces(chain)
		if err != nil {
			return nil, err
		}
		v.joins.Add(1)
		v.mu.Lock()
		defer v.mu.Unlock()
		v.rememberLocked(k, b)
		return b, nil
	})
	if err != nil {
		return nil, err
	}
	return b.([]byte), nil
}

func (v *vodState) sharedSourceUnchanged() error {
	key, err := renditionContract(v.spec)
	if err != nil {
		return err
	}
	if key != v.spec.sharedKey {
		return errors.New("rendition source or output changed")
	}
	return nil
}

func (v *vodState) sharedGeneratedInitMatches() error {
	v.mu.Lock()
	init := v.init
	want := v.startupHash
	v.mu.Unlock()
	if init == nil || want == "" {
		return nil
	}
	sum := sha256.Sum256(init)
	if fmt.Sprintf("%x", sum) != want {
		return errors.New("rendition init changed across VOD runs")
	}
	return nil
}

func validateRenditionFragment(b []byte) error {
	boxes, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return err
	}
	fragments := 0
	for i, box := range boxes {
		switch box.typ {
		case "moof":
			if i+1 >= len(boxes) || boxes[i+1].typ != "mdat" || boxes[i+1].end == boxes[i+1].body {
				return errors.New("rendition fragment has no complete media payload")
			}
			fragments++
		case "mdat":
			if i == 0 || boxes[i-1].typ != "moof" {
				return errors.New("rendition media payload has no fragment")
			}
		}
	}
	if fragments == 0 {
		return errors.New("rendition segment has no media fragment")
	}
	return nil
}

// waitChain waits until the pieces of segment k are there, starting or
// moving ffmpeg as needed, and returns them pinned: unpin them once read.
func (v *vodState) waitChain(ctx context.Context, k int) ([]*vodPiece, error) {
	deadline := time.NewTimer(vodWait)
	defer deadline.Stop()
	restarted := false
	for {
		chain, wake, err := v.tryChain(k, &restarted)
		if chain != nil || err != nil {
			return chain, err
		}
		select {
		case <-wake:
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-v.ctx.Done():
			return nil, ErrStopped
		case <-deadline.C:
			v.warnNotReady(k)
			return nil, fmt.Errorf("segment %d not ready after %s", k, vodWait)
		}
	}
}

// tryChain returns segment k's pieces, pinned, when they are all there;
// else it makes sure a run is on its way to them and returns what to wait on.
func (v *vodState) tryChain(k int, restarted *bool) ([]*vodPiece, <-chan struct{}, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if chain, ok := v.chainLocked(k); ok {
		for _, p := range chain {
			p.pins++
		}
		v.trimLocked(k)
		return chain, nil, nil
	}
	r := v.run
	if r != nil {
		if err := r.failed(); err != nil {
			v.run = nil
			return nil, nil, err
		}
	}
	// A run that has ended, or gone on past this segment, without making
	// it will not make it by waiting.
	if r != nil && (r.finished() || (r.seg <= k && r.passed(v.bounds[k+1]))) {
		if (r.seg == k && !r.trimmed) || *restarted {
			// It started here and went past this segment without making
			// it: another run would do the same.
			v.m.log.Warn("playback run went past a segment without making it", "session", v.s.ID, "run", r.n, "segment", k, "pieces", r.listed, "reached_ms", r.reached/1000)
			return nil, nil, fmt.Errorf("segment %d could not be made", k)
		}
		// Its pieces of this segment were trimmed, or it started after
		// it: start again here.
		if r.finished() {
			v.run = nil
		}
	}
	err := v.ensureLocked(k)
	if v.run != r {
		*restarted = true
	}
	if err != nil {
		return nil, nil, err
	}
	return nil, v.waitLocked(), nil
}

// unpin lets the chain's pieces go, deleting those dropped meanwhile.
func (v *vodState) unpin(chain []*vodPiece) {
	v.mu.Lock()
	defer v.mu.Unlock()
	for _, p := range chain {
		p.pins--
		if p.pins == 0 && p.dropped {
			_ = os.Remove(p.file)
		}
	}
}

// dropLocked deletes a piece's file, or leaves that to the last request
// reading it, and marks its run as no longer holding all it made.
func (v *vodState) dropLocked(p *vodPiece) {
	v.bytes -= p.size
	p.run.trimmed = true
	if p.pins > 0 {
		p.dropped = true
		return
	}
	_ = os.Remove(p.file)
}

// joinedSegment returns segment k when it was joined lately.
func (v *vodState) joinedSegment(k int) []byte {
	v.mu.Lock()
	defer v.mu.Unlock()
	for _, j := range v.joined {
		if j.k == k {
			return j.b
		}
	}
	return nil
}

// rememberLocked keeps segment k for the next request for it, and as many
// recent ones as fit vodJoinedSegments and vodJoinedBytes.
func (v *vodState) rememberLocked(k int, b []byte) {
	v.joined = slices.Insert(v.joined, 0, joinedSegment{k, b})
	size := 0
	for i, j := range v.joined {
		size += len(j.b)
		if i > 0 && (i >= vodJoinedSegments || size > vodJoinedBytes) {
			v.joined = v.joined[:i]
			break
		}
	}
}

// warnNotReady logs how far ffmpeg had got when a wait for segment k gave
// up: its speed and stderr tell a stalled encoder from a slow one.
func (v *vodState) warnNotReady(k int) {
	v.mu.Lock()
	r := v.run
	if r == nil {
		v.mu.Unlock()
		return
	}
	pieces, reached := r.listed, r.reached
	v.mu.Unlock()
	speed, _ := v.s.Progress()
	v.m.log.Warn("playback segment not ready", "session", v.s.ID, "segment", k, "run", r.n, "pieces", pieces, "reached_ms", reached/1000, "speed", speed, "stderr", r.cmd.Tail())
}

// joinPieces reads the pieces into one buffer and makes their decode times
// absolute in place: one read of each piece into a buffer of the right
// size, and no other copy. The pieces must be pinned.
func joinPieces(chain []*vodPiece) ([]byte, error) {
	total := int64(0)
	for _, p := range chain {
		st, err := os.Stat(p.file)
		if err != nil {
			return nil, err
		}
		total += st.Size()
	}
	b := make([]byte, 0, total)
	for _, p := range chain {
		start := len(b)
		var err error
		if b, err = appendFile(b, p.file); err != nil {
			return nil, err
		}
		n, err := patchFragmentInPlace(b[start:], *p.run.init)
		if err != nil {
			return nil, err
		}
		b = b[:start+n]
	}
	return b, nil
}

// appendFile appends the whole file to b.
func appendFile(b []byte, path string) ([]byte, error) {
	f, err := OpenShared(path)
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	n := int(st.Size())
	b = slices.Grow(b, n)
	if _, err := io.ReadFull(f, b[len(b):len(b)+n]); err != nil {
		return nil, err
	}
	return b[:len(b)+n], nil
}

// trimLocked deletes pieces well behind segment k.
func (v *vodState) trimLocked(k int) {
	cutoff := v.bounds[k] - vodKeepBehind.Microseconds()
	kept := v.pieces[:0]
	for _, p := range v.pieces {
		if p.end < cutoff {
			v.dropLocked(p)
			continue
		}
		kept = append(kept, p)
	}
	clear(v.pieces[len(kept):])
	v.pieces = kept
}

// Init returns the init segment, waiting for the first run to write it.
func (v *vodState) Init(ctx context.Context) ([]byte, error) {
	deadline := time.NewTimer(vodWait)
	defer deadline.Stop()
	for {
		init, wake, err := v.tryInit()
		if init != nil || err != nil {
			return init, err
		}
		select {
		case <-wake:
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-v.ctx.Done():
			return nil, ErrStopped
		case <-deadline.C:
			return nil, errors.New("no init segment yet")
		}
	}
}

func (v *vodState) sharedInit(ctx context.Context) ([]byte, error) {
	if v.spec.sharedKey == "" || v.m.renditions == nil {
		return v.Init(ctx)
	}
	if err := v.sharedSourceUnchanged(); err != nil {
		return nil, err
	}
	v.mu.Lock()
	init := bytes.Clone(v.startupInit)
	v.mu.Unlock()
	if init != nil {
		return init, nil
	}
	init, _, err := v.sharedStartup(ctx)
	return init, err
}

// sharedStartup is one cache flight for the init and the first segment.
// Keeping them together prevents simultaneous starts from choosing different
// viewers as producers for those two files. A cache hit starts no local run.
func (v *vodState) sharedStartup(ctx context.Context) ([]byte, []byte, error) {
	if err := v.sharedSourceUnchanged(); err != nil {
		return nil, nil, err
	}
	k := v.startupIndex
	key := fmt.Sprintf("%s/startup/%d", v.spec.sharedKey, k)
	b, release, err := v.sharedRendition(ctx, key,
		func(work context.Context) ([]byte, error) {
			if err := v.sharedSourceUnchanged(); err != nil {
				return nil, err
			}
			segment, err := v.segmentUncached(work, k)
			if err != nil {
				return nil, err
			}
			init, err := v.Init(work)
			if err != nil {
				return nil, err
			}
			if err := v.sharedSourceUnchanged(); err != nil {
				return nil, err
			}
			return packRenditionStartup(init, segment), nil
		}, func(b []byte) error { return validateRenditionStartup(b, v.opts.SeparateAudio) })
	if err == nil {
		init, segment, _ := unpackRenditionStartup(b)
		init, segment = bytes.Clone(init), bytes.Clone(segment)
		production, producedAt := v.m.renditions.preparation(key)
		release()
		parsed, err := parseInit(init)
		if err != nil || parsed.Video == 0 || (v.opts.SeparateAudio && parsed.Audio == 0) {
			return nil, nil, errors.New("shared rendition startup has incompatible tracks")
		}
		v.mu.Lock()
		if v.startupInit == nil {
			v.startupInit = bytes.Clone(init)
			sum := sha256.Sum256(init)
			v.startupHash = fmt.Sprintf("%x", sum)
		}
		v.startupVideo, v.startupAudio = parsed.Video, parsed.Audio
		v.startupProduction, v.startupProducedAt = production, producedAt
		v.video, v.audio = parsed.Video, parsed.Audio
		v.mu.Unlock()
		return init, segment, nil
	}
	if errors.Is(err, ErrStopped) && ctx.Err() == nil && v.viewerCtx.Err() == nil {
		segment, err := v.segmentUncached(ctx, k)
		if err != nil {
			return nil, nil, err
		}
		init, err := v.Init(ctx)
		if err == nil {
			parsed, parseErr := parseInit(init)
			if parseErr != nil || parsed.Video == 0 || (v.opts.SeparateAudio && parsed.Audio == 0) {
				return nil, nil, errors.New("shared rendition startup has incompatible tracks")
			}
			v.mu.Lock()
			v.startupInit = bytes.Clone(init)
			sum := sha256.Sum256(init)
			v.startupHash = fmt.Sprintf("%x", sum)
			v.startupVideo, v.startupAudio = parsed.Video, parsed.Audio
			v.mu.Unlock()
		}
		return init, segment, err
	}
	return nil, nil, err
}

func packRenditionStartup(init, segment []byte) []byte {
	b := make([]byte, 4, 4+len(init)+len(segment))
	binary.BigEndian.PutUint32(b, uint32(len(init)))
	b = append(b, init...)
	return append(b, segment...)
}

func unpackRenditionStartup(b []byte) ([]byte, []byte, error) {
	if len(b) < 5 {
		return nil, nil, errors.New("rendition startup is truncated")
	}
	n := int(binary.BigEndian.Uint32(b))
	if n == 0 || n >= len(b)-4 {
		return nil, nil, errors.New("rendition startup has invalid lengths")
	}
	return b[4 : 4+n], b[4+n:], nil
}

func validateRenditionStartup(b []byte, requireAudio bool) error {
	init, segment, err := unpackRenditionStartup(b)
	if err != nil {
		return err
	}
	if err := validateRenditionInit(init); err != nil {
		return err
	}
	parsed, err := parseInit(init)
	if err != nil || parsed.Video == 0 || (requireAudio && parsed.Audio == 0) {
		return errors.New("rendition startup has incompatible tracks")
	}
	return validateRenditionFragment(segment)
}

func validateRenditionInit(b []byte) error {
	boxes, err := mp4Boxes(b, 0, len(b))
	if err != nil {
		return err
	}
	ftyp, moov := false, false
	for _, box := range boxes {
		switch box.typ {
		case "ftyp":
			ftyp = true
		case "moov":
			moov = true
		}
	}
	if !ftyp || !moov {
		return errors.New("rendition init is missing file type or movie metadata")
	}
	return nil
}

// tryInit returns the init segment when a run has written it, else starts
// a run if none is on its way and returns what to wait on.
func (v *vodState) tryInit() ([]byte, <-chan struct{}, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.init != nil {
		return v.init, nil, nil
	}
	if v.run == nil {
		if err := v.startRunLocked(0); err != nil {
			return nil, nil, err
		}
	} else if err := v.run.failed(); err != nil {
		return nil, nil, err
	}
	return nil, v.waitLocked(), nil
}

// running reports whether ffmpeg is running for this session.
func (v *vodState) running() bool { return v.live.Load() > 0 }

// suspend stops ffmpeg and deletes the pieces; the next request starts again.
func (v *vodState) suspend() {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.suspendLocked()
}

// suspendIdle suspends the session if no request has come since idle
// before now. It does not wait for the session's lock: a session busy now
// is not idle.
func (v *vodState) suspendIdle(now time.Time, idle time.Duration) bool {
	if !v.mu.TryLock() {
		return false
	}
	defer v.mu.Unlock()
	if now.Sub(time.Unix(0, v.s.last.Load())) <= idle || (v.run == nil && len(v.pieces) == 0) {
		return false
	}
	v.suspendLocked()
	return true
}

func (v *vodState) suspendLocked() {
	v.joined = nil
	var done chan struct{}
	if r := v.run; r != nil {
		r.cancel()
		done = r.done
		v.run = nil
	}
	var files []string
	for _, p := range v.pieces {
		if p.pins > 0 {
			p.dropped = true
		} else {
			files = append(files, p.file)
		}
	}
	v.pieces = nil
	v.bytes = 0
	v.notifyLocked()
	go func() {
		if done != nil {
			<-done
		}
		for _, f := range files {
			_ = os.Remove(f)
		}
	}()
}

// waitStopped waits for the current run's ffmpeg to exit.
func (v *vodState) waitStopped() {
	v.mu.Lock()
	r := v.run
	v.mu.Unlock()
	if r != nil {
		<-r.done
	}
}

// --- playlists ---

// Master is the master playlist: the video and its subtitle renditions.
func (v *vodState) Master() []byte {
	var b strings.Builder
	b.WriteString("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
	names := map[string]int{}
	for _, t := range v.opts.Subtitles {
		name := t.Name
		if name == "" {
			name = "Subtitles"
		}
		if n := names[name]; n > 0 {
			names[name] = n + 1
			name = fmt.Sprintf("%s (%d)", name, n+1)
		} else {
			names[name] = 1
		}
		fmt.Fprintf(&b, "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=%q", name)
		if t.Language != "" {
			fmt.Fprintf(&b, ",LANGUAGE=%q", t.Language)
		}
		// DEFAULT=YES would switch subtitles on by themselves, and a
		// Matroska file's default flag is usually just the muxer's choice;
		// the apps decide from the decision's subtitles[].default instead.
		forced := "NO"
		if t.Forced {
			forced = "YES"
		}
		fmt.Fprintf(&b, ",DEFAULT=NO,AUTOSELECT=YES,FORCED=%s,URI=%q\n", forced, "sub-"+t.ID+".m3u8")
	}
	if v.opts.SeparateAudio {
		b.WriteString(`#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Audio"`)
		if v.opts.AudioLanguage != "" {
			fmt.Fprintf(&b, ",LANGUAGE=%q", v.opts.AudioLanguage)
		}
		if v.opts.AudioChannels > 0 {
			fmt.Fprintf(&b, ",CHANNELS=\"%d\"", v.opts.AudioChannels)
		}
		fmt.Fprintf(&b, ",DEFAULT=YES,AUTOSELECT=YES,URI=%q\n", VODAudio)
	}
	bw := v.opts.Bandwidth
	if bw <= 0 {
		bw = 20_000_000
	}
	fmt.Fprintf(&b, "#EXT-X-STREAM-INF:BANDWIDTH=%d", bw)
	if v.opts.Codecs != "" {
		fmt.Fprintf(&b, ",CODECS=%q", v.opts.Codecs)
		if v.opts.SupplementalCodecs != "" {
			fmt.Fprintf(&b, ",SUPPLEMENTAL-CODECS=%q", v.opts.SupplementalCodecs)
		}
	}
	if v.spec.Video.Width > 0 && v.spec.Video.Height > 0 {
		fmt.Fprintf(&b, ",RESOLUTION=%dx%d", v.spec.Video.Width, v.spec.Video.Height)
	}
	if v.opts.FrameRate > 0 {
		fmt.Fprintf(&b, ",FRAME-RATE=%.3f", v.opts.FrameRate)
	}
	if v.opts.VideoRange != "" {
		fmt.Fprintf(&b, ",VIDEO-RANGE=%s", v.opts.VideoRange)
	}
	if v.opts.SeparateAudio {
		b.WriteString(",AUDIO=\"audio\"")
	}
	if len(v.opts.Subtitles) > 0 {
		b.WriteString(",SUBTITLES=\"subs\"")
	}
	b.WriteString("\n" + VODMedia + "\n")
	if f := v.opts.IFrames; f != nil {
		// Tiles are SDR whatever the video is (PLAN-TRICKPLAY.md decision 9).
		fmt.Fprintf(&b, "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=%d,CODECS=%q,RESOLUTION=%dx%d,VIDEO-RANGE=SDR,URI=%q\n",
			max(f.Bandwidth, 1), f.Codecs, f.Width, f.Height, VODIFrames)
	}
	return []byte(b.String())
}

// AudioMedia is the audio rendition's playlist, on the video's segments.
func (v *vodState) AudioMedia() []byte {
	return v.mediaPlaylist(VODAudioInit, func(k int) string { return fmt.Sprintf("a%05d.m4s", k) })
}

// track returns a muxed segment or init segment cut down to the video
// (audio false) or the audio track, for SeparateAudio sessions; others get
// b as it is.
func (v *vodState) track(b []byte, init, audio bool) ([]byte, error) {
	if !v.opts.SeparateAudio || b == nil {
		return b, nil
	}
	v.mu.Lock()
	id := v.video
	if audio {
		id = v.audio
	}
	if v.spec.sharedKey != "" && v.startupInit != nil {
		id = v.startupVideo
		if audio {
			id = v.startupAudio
		}
	}
	v.mu.Unlock()
	if id == 0 {
		return nil, errors.New("no such track in the output")
	}
	if init {
		return splitInit(b, id)
	}
	return splitFragment(b, id)
}

func (v *vodState) target() int {
	longest := int64(0)
	for i := 1; i < len(v.bounds); i++ {
		longest = max(longest, v.bounds[i]-v.bounds[i-1])
	}
	return int(math.Ceil(float64(longest) / 1e6))
}

// mediaPlaylist lists every segment with name(k).
func (v *vodState) mediaPlaylist(mapURI string, name func(int) string) []byte {
	var b strings.Builder
	fmt.Fprintf(&b, "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n", v.target())
	if mapURI != "" {
		fmt.Fprintf(&b, "#EXT-X-MAP:URI=%q\n", mapURI)
	}
	for k := 0; k < v.Segments(); k++ {
		fmt.Fprintf(&b, "#EXTINF:%.6f,\n%s\n", float64(v.bounds[k+1]-v.bounds[k])/1e6, name(k))
	}
	b.WriteString("#EXT-X-ENDLIST\n")
	return []byte(b.String())
}

// Media is the video's playlist.
func (v *vodState) Media() []byte {
	return v.mediaPlaylist(VODInit, func(k int) string { return fmt.Sprintf("v%05d.m4s", k) })
}

func (v *vodState) subtitle(id string) bool {
	for _, t := range v.opts.Subtitles {
		if t.ID == id {
			return true
		}
	}
	return false
}

// SubtitlePlaylist is a subtitle rendition's playlist, on the video's segments.
func (v *vodState) SubtitlePlaylist(id string) ([]byte, bool) {
	if !v.subtitle(id) {
		return nil, false
	}
	return v.mediaPlaylist("", func(k int) string { return fmt.Sprintf("sub-%s-%05d.vtt", id, k) }), true
}

// SubtitleSegment is a subtitle rendition's segment k.
func (v *vodState) SubtitleSegment(ctx context.Context, id string, k int) ([]byte, error) {
	if !v.subtitle(id) || k < 0 || k >= v.Segments() || v.opts.Subtitle == nil {
		return nil, os.ErrNotExist
	}
	v.mu.Lock()
	cues, ok := v.cues[id]
	v.mu.Unlock()
	if !ok {
		data, err := v.opts.Subtitle(ctx, id)
		if err != nil {
			return nil, err
		}
		cues = parseVTT(data)
		v.mu.Lock()
		v.cues[id] = cues
		v.mu.Unlock()
	}
	return vttSegment(cues, v.bounds[k], v.bounds[k+1]), nil
}
