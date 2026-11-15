package playback

import (
	"context"
	"errors"
	"sync"
	"time"

	"watchmymedia/server/internal/probe"
)

// An event session of copied video starts on a keyframe and ends each run
// just before one (PLAN-RESUME.md §3.1, §3.2). ffmpeg can only start copied
// video on a keyframe: given -ss between two, it starts at the one before,
// so a session that reported the asked-for time ran up to a keyframe
// interval ahead of the picture, and each run's re-seek skipped or repeated
// content at the join.

const (
	// keyframeLookback is the furthest back a start may move to reach a
	// keyframe: inside the 15 s the apps accept between the start they asked
	// for and the one the decision gives.
	keyframeLookback = 12 * time.Second
	// keyframeLookahead is the furthest past a run's window its end may move
	// to reach the next keyframe.
	keyframeLookahead = 20 * time.Second
	// eventStartLookup bounds the lookups a session's first run waits for.
	eventStartLookup = 300 * time.Millisecond
	// eventRunLookup bounds a later run's lookup, made in the background
	// well before the viewer reaches that run.
	eventRunLookup = 3 * time.Second
)

// Keyframe is a keyframe of a file's first video stream: when it shows, in
// microseconds from the file's start time (as -ss counts), and how far its
// decode time runs ahead of that (the stream's B-frame reorder delay).
type Keyframe struct {
	Us, LeadUs int64
}

// ErrNoKeyframe means no keyframe lies within reach of the time asked for.
var ErrNoKeyframe = errors.New("no keyframe near that time")

// KeyframeFinder finds keyframes near a time, reading only around it.
type KeyframeFinder interface {
	// AtOrBefore is the last keyframe at or before us, at most
	// keyframeLookback before it.
	AtOrBefore(ctx context.Context, us int64) (Keyframe, error)
	// AtOrAfter is the first keyframe at or after us, at most
	// keyframeLookahead after it.
	AtOrAfter(ctx context.Context, us int64) (Keyframe, error)
}

// NewKeyframeFinder reads path's keyframes with bounded ffprobe reads.
// startUs is the file's start time when the stored probe knows it; else it
// is read once, on the first lookup.
func NewKeyframeFinder(p *probe.Prober, path string, startUs *int64) KeyframeFinder {
	f := &probeFinder{probe: p, path: path}
	if startUs != nil {
		f.start, f.started = *startUs, true
	}
	return f
}

type probeFinder struct {
	probe *probe.Prober
	path  string

	mu      sync.Mutex
	start   int64
	started bool
}

func (f *probeFinder) startTime(ctx context.Context) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.started {
		s, err := f.probe.StartTime(ctx, f.path)
		if err != nil {
			return 0, err
		}
		f.start, f.started = s, true
	}
	return f.start, nil
}

func (f *probeFinder) AtOrBefore(ctx context.Context, us int64) (Keyframe, error) {
	start, err := f.startTime(ctx)
	if err != nil {
		return Keyframe{}, err
	}
	at := start + us
	var found *probe.Packet
	err = f.probe.VideoPackets(ctx, f.path, at, at+1000, func(p probe.Packet) bool {
		if p.Key && p.PtsUs <= at {
			found = &p
		}
		// Decode order: once a packet decodes after the time, no keyframe
		// shown at or before it can follow.
		return p.DtsUs <= at
	})
	if err != nil {
		return Keyframe{}, err
	}
	if found == nil || at-found.PtsUs > keyframeLookback.Microseconds() {
		return Keyframe{}, ErrNoKeyframe
	}
	return Keyframe{Us: found.PtsUs - start, LeadUs: max(found.PtsUs-found.DtsUs, 0)}, nil
}

func (f *probeFinder) AtOrAfter(ctx context.Context, us int64) (Keyframe, error) {
	start, err := f.startTime(ctx)
	if err != nil {
		return Keyframe{}, err
	}
	at := start + us
	var found *probe.Packet
	err = f.probe.VideoPackets(ctx, f.path, at, at+keyframeLookahead.Microseconds(), func(p probe.Packet) bool {
		if p.Key && p.PtsUs >= at {
			found = &p
			return false
		}
		return true
	})
	if err != nil {
		return Keyframe{}, err
	}
	if found == nil {
		return Keyframe{}, ErrNoKeyframe
	}
	return Keyframe{Us: found.PtsUs - start, LeadUs: max(found.PtsUs-found.DtsUs, 0)}, nil
}

// eventStartPlan finds where an event session asked to start at startMs
// really starts, the keyframe at or before it, and the keyframe that ends
// its first window, both at once and within wait (eventStartLookup), since
// the viewer waits for them. ok is false when the start has none in reach: the
// session then runs as asked. A missing end only leaves the first run to
// end where its window does.
func eventStartPlan(ctx context.Context, f KeyframeFinder, startMs int64, window, wait time.Duration) (startUs int64, end *Keyframe, ok bool) {
	ctx, cancel := context.WithTimeout(ctx, wait)
	defer cancel()
	startUs = startMs * 1000
	var start, last Keyframe
	var startErr, endErr error
	var wg sync.WaitGroup
	if startMs > 0 {
		wg.Go(func() { start, startErr = f.AtOrBefore(ctx, startUs) })
	}
	// From the asked-for start: the window then runs at least its length
	// from the keyframe before it too.
	wg.Go(func() { last, endErr = f.AtOrAfter(ctx, startUs+window.Microseconds()) })
	wg.Wait()
	if startMs > 0 {
		if startErr != nil {
			return startUs, nil, false
		}
		startUs = start.Us
	}
	if endErr == nil {
		end = &last
	}
	return startUs, end, true
}

// ceilMs is us in whole milliseconds, rounded up: -ss at a keyframe's time
// rounded down would land on the keyframe before it.
func ceilMs(us int64) int64 {
	return (us + 999) / 1000
}

// eventRunLength is how long a run from fromUs to the keyframe end must be
// told to last (-t) so that end itself is left for the next run: ffmpeg
// stops copied video at the first packet decoded at or after the limit, so
// the limit sits one millisecond before end's decode time.
func eventRunLength(fromUs int64, end Keyframe) int64 {
	return end.Us - end.LeadUs - fromUs - 1000
}

// KeyframeLookupsMayTake lets an event start wait up to d for its keyframe
// lookups instead of eventStartLookup. Tests that run beside many ffmpeg
// processes call it: there a lookup that takes 20 ms alone can take longer
// than the bound, and the session then rightly starts as asked.
func (m *Manager) KeyframeLookupsMayTake(d time.Duration) { m.keyframeWait.Store(int64(d)) }

func (m *Manager) keyframeLookupWait() time.Duration {
	if d := time.Duration(m.keyframeWait.Load()); d > 0 {
		return d
	}
	return eventStartLookup
}
