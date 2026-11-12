package playback

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

// ErrRenditionBusy means producers, readers or pinned bytes occupy the cache's
// budget. The caller can retain its current rendition and retry later.
var ErrRenditionBusy = errors.New("rendition cache busy")

var errEmptyRendition = errors.New("rendition producer returned no media")

const renditionMaxEntryBytes = 32 << 20

// renditionCache holds only complete, validated, immutable media. A producer
// belongs to the cache, not to whichever viewer happened to request it first.
// Canceling the first request therefore cannot interrupt another waiter.
// Keys must include the complete source revision and output contract; the
// cache deliberately knows nothing about viewer identity or authorization.
type renditionCache struct {
	mu           sync.Mutex
	entries      map[string]*renditionEntry
	bytes        int64
	maxBytes     int64
	maxFlights   int
	maxFollowers int
	flights      int
	followers    int
	pins         int
	ttl          time.Duration
	closed       bool
}

type renditionEntry struct {
	ready      chan struct{}
	cancel     context.CancelFunc
	data       []byte
	err        error
	waiters    int
	pins       int
	last       time.Time
	completed  bool
	cached     bool
	production time.Duration
	producedAt time.Time
}

func newRenditionCache(maxBytes int64, maxFlights, maxFollowers int, ttl time.Duration) *renditionCache {
	return &renditionCache{entries: make(map[string]*renditionEntry), maxBytes: maxBytes,
		maxFlights: maxFlights, maxFollowers: maxFollowers, ttl: ttl}
}

// get returns one pin on complete media. The release function must be called
// after the caller finishes using the shared bytes. produce and validate run once for
// concurrent callers; a failure is never cached. produce must honor its
// context and must not use a viewer's session context for ownership.
func (c *renditionCache) get(ctx, parent context.Context, key string,
	produce func(context.Context) ([]byte, error), validate func([]byte) error) ([]byte, func(), error) {
	if err := ctx.Err(); err != nil {
		return nil, nil, err
	}
	if key == "" || produce == nil || validate == nil {
		return nil, nil, errors.New("rendition cache needs a key, producer and validator")
	}
	now := time.Now()
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, nil, ErrStopped
	}
	// The same bound covers waiting requests and readers of ready media.
	// A hot cache hit must not bypass admission and accumulate pinned output.
	if c.maxFollowers <= 0 || c.followers+c.pins >= c.maxFollowers {
		c.mu.Unlock()
		return nil, nil, ErrRenditionBusy
	}
	if e := c.entries[key]; e != nil && e.completed {
		if c.ttl <= 0 || now.Sub(e.last) < c.ttl || e.pins > 0 || e.waiters > 0 {
			e.pins++
			c.pins++
			e.last = now
			b := e.data
			c.mu.Unlock()
			return b, c.release(e), nil
		}
		c.removeLocked(key, e)
	}
	e := c.entries[key]
	if e == nil {
		if c.maxFlights <= 0 || c.flights >= c.maxFlights {
			c.mu.Unlock()
			return nil, nil, ErrRenditionBusy
		}
		work, cancel := context.WithCancel(parent)
		e = &renditionEntry{ready: make(chan struct{}), cancel: cancel, last: now}
		c.entries[key] = e
		c.flights++
		go c.run(work, key, e, produce, validate)
	}
	e.waiters++
	c.followers++
	c.mu.Unlock()

	select {
	case <-ctx.Done():
		c.mu.Lock()
		e.waiters--
		c.followers--
		if e.waiters == 0 && !e.completed {
			e.cancel()
			c.removeLocked(key, e)
		}
		c.mu.Unlock()
		return nil, nil, ctx.Err()
	case <-e.ready:
		c.mu.Lock()
		e.waiters--
		c.followers--
		if err := ctx.Err(); err != nil {
			c.mu.Unlock()
			return nil, nil, err
		}
		if e.err != nil {
			err := e.err
			c.mu.Unlock()
			return nil, nil, err
		}
		e.pins++
		c.pins++
		e.last = time.Now()
		b := e.data
		c.mu.Unlock()
		return b, c.release(e), nil
	}
}

func (c *renditionCache) run(ctx context.Context, key string, e *renditionEntry,
	produce func(context.Context) ([]byte, error), validate func([]byte) error) {
	started := time.Now()
	b, err := produce(ctx)
	if err == nil {
		if len(b) == 0 {
			err = errEmptyRendition
		} else if c.maxBytes <= 0 || int64(len(b)) > min(c.maxBytes, int64(renditionMaxEntryBytes)) {
			err = fmt.Errorf("%w: rendition exceeds cache entry limit", ErrStorageBudget)
		} else {
			err = validate(b)
		}
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.flights--
	if err == nil && ctx.Err() != nil {
		err = ctx.Err()
	}
	if err == nil && (c.closed || c.entries[key] != e) {
		err = ErrStopped
	}
	if err == nil {
		c.evictLocked(int64(len(b)), e)
		if c.bytes+int64(len(b)) <= c.maxBytes {
			e.data = b
			e.production, e.producedAt = time.Since(started), time.Now()
			c.bytes += int64(len(b))
			e.cached = true
		} else {
			// Do not hand out unaccounted media when other readers pin the
			// whole budget. A later request can retry once they release it.
			err = ErrRenditionBusy
		}
	}
	e.err = err
	e.completed = true
	e.cancel()
	if err != nil {
		c.removeLocked(key, e)
	}
	close(e.ready)
}

// preparation reports the original producer's work, never the speed of a
// warm cache read. Call while holding a pin on the returned entry.
func (c *renditionCache) preparation(key string) (time.Duration, time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if e := c.entries[key]; e != nil && e.completed && e.cached {
		return e.production, e.producedAt
	}
	return 0, time.Time{}
}

func (c *renditionCache) release(e *renditionEntry) func() {
	var once sync.Once
	return func() {
		once.Do(func() {
			c.mu.Lock()
			e.pins--
			c.pins--
			c.mu.Unlock()
		})
	}
}

func (c *renditionCache) removeLocked(key string, e *renditionEntry) {
	if c.entries[key] != e {
		return
	}
	delete(c.entries, key)
	if e.cached {
		c.bytes -= int64(len(e.data))
		e.cached = false
	}
}

func (c *renditionCache) evictLocked(incoming int64, exclude *renditionEntry) {
	for c.bytes+incoming > c.maxBytes {
		var key string
		var oldest *renditionEntry
		for k, e := range c.entries {
			if e == exclude || !e.cached || e.pins > 0 || e.waiters > 0 {
				continue
			}
			if oldest == nil || e.last.Before(oldest.last) {
				key, oldest = k, e
			}
		}
		if oldest == nil {
			return
		}
		c.removeLocked(key, oldest)
	}
}

func (c *renditionCache) sweep(now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for key, e := range c.entries {
		if e.completed && e.pins == 0 && e.waiters == 0 && c.ttl > 0 && now.Sub(e.last) >= c.ttl {
			c.removeLocked(key, e)
		}
	}
}

func (c *renditionCache) close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.closed = true
	for key, e := range c.entries {
		if !e.completed {
			e.cancel()
		}
		c.removeLocked(key, e)
	}
}

func (c *renditionCache) stats() (bytes int64, entries, flights, followers int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.bytes, len(c.entries), c.flights, c.followers
}

// RenditionStats reports retained complete bytes separately from in-flight
// producers, waiting requests and pinned readers. Followers plus Pins must
// remain within MaxFollowers. It contains no source names or viewer IDs.
type RenditionStats struct {
	Bytes        int64 `json:"bytes"`
	MaxBytes     int64 `json:"maxBytes"`
	Entries      int   `json:"entries"`
	Flights      int   `json:"flights"`
	MaxFlights   int   `json:"maxFlights"`
	Followers    int   `json:"followers"`
	MaxFollowers int   `json:"maxFollowers"`
	Pins         int   `json:"pins"`
}

func (m *Manager) RenditionStats() RenditionStats {
	c := m.renditions
	c.mu.Lock()
	defer c.mu.Unlock()
	return RenditionStats{Bytes: c.bytes, MaxBytes: c.maxBytes, Entries: len(c.entries),
		Flights: c.flights, MaxFlights: c.maxFlights, Followers: c.followers,
		MaxFollowers: c.maxFollowers, Pins: c.pins}
}
