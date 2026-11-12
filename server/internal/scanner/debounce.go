package scanner

import (
	"context"
	"maps"
	"slices"
	"time"
)

// maxDebounce is the longest a library's changes wait for events to stop
// before they are scanned anyway.
const maxDebounce = time.Minute

// debouncer gathers the paths of a library's filesystem events into one
// scan, sent once events have stopped for wait or at most maxWait after the
// first of them: a steady trickle, such as a download writing for an hour,
// would otherwise hold the scan off for as long as it lasted. It belongs to
// the watcher's goroutine; only its timers run elsewhere.
type debouncer struct {
	wait, maxWait time.Duration
	// due receives a burst whose time is up. The watcher passes it to take.
	due     chan *burst
	pending map[int64]*burst
}

// burst is the paths of one library waiting to be scanned.
type burst struct {
	library int64
	paths   map[string]bool
	first   time.Time
	timer   *time.Timer
}

func newDebouncer(wait, maxWait time.Duration) *debouncer {
	return &debouncer{wait: wait, maxWait: max(wait, maxWait), due: make(chan *burst), pending: map[int64]*burst{}}
}

// add notes a changed path of a library and sets when to scan it.
func (d *debouncer) add(ctx context.Context, library int64, path string) {
	now := time.Now()
	b := d.pending[library]
	if b == nil {
		b = &burst{library: library, paths: map[string]bool{}, first: now}
		b.timer = time.AfterFunc(d.wait, func() {
			select {
			case d.due <- b:
			case <-ctx.Done():
			}
		})
		d.pending[library] = b
	} else {
		b.timer.Reset(min(d.wait, max(d.maxWait-now.Sub(b.first), 0)))
	}
	b.paths[path] = true
}

// take returns the library and paths of a burst that came due, and forgets
// them. A burst taken already (its timer fired twice) gives nothing.
func (d *debouncer) take(b *burst) (int64, []string, bool) {
	if d.pending[b.library] != b {
		return 0, nil, false
	}
	delete(d.pending, b.library)
	b.timer.Stop()
	return b.library, slices.Sorted(maps.Keys(b.paths)), true
}
