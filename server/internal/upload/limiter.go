// Package upload accounts for and optionally paces remote HTTP media delivery.
// It has no knowledge of playback sessions, so older clients and repeated
// range requests use the same aggregate ceiling as negotiated playback.
package upload

import (
	"context"
	"errors"
	"io"
	"math"
	"net/http"
	"sync"
	"time"
)

const ChunkBytes = 16 << 10
const BurstBytes = 2 * ChunkBytes

type Snapshot struct {
	ConfiguredBps   int64  `json:"configuredBps"`
	ReservedBps     int64  `json:"reservedBps"`
	DeliveredBps    int64  `json:"deliveredBps"`
	Enforcing       bool   `json:"enforcing"`
	DeliveredBytes  int64  `json:"deliveredBytes"`
	PacedBytes      int64  `json:"pacedBytes"`
	WaitNanos       int64  `json:"waitNanos"`
	ActiveViews     int    `json:"activeViews"`
	ActiveDownloads int    `json:"activeDownloads"`
	Generation      uint64 `json:"generation"`
	PolicyRevision  uint64 `json:"policyRevision" required:"false" doc:"Changes only when the configured upload policy changes; use to avoid overwriting another admin's edit. Absent on older servers."`
}

type bucket struct {
	last   time.Time
	tokens float64
}

func (b *bucket) refill(now time.Time, bps int64) {
	if b.last.IsZero() {
		b.tokens = BurstBytes
	} else {
		b.tokens = math.Min(BurstBytes, b.tokens+now.Sub(b.last).Seconds()*float64(bps)/8)
	}
	b.last = now
}

type flow struct {
	bucket   bucket
	requests int
	download bool
}

type reservation struct {
	bps    int64
	serial uint64
}

// Limiter keeps all response paths on one budget. A download gets at most
// one tenth of the budget while viewing is active; unused aggregate credit
// remains available to every class. The mutex is never held during network IO.
type Limiter struct {
	mu             sync.Mutex
	bps            int64
	enforce        bool
	generation     uint64
	policyRevision uint64
	changed        chan struct{}
	all            bucket
	flows          map[string]*flow
	reservations   map[string]reservation
	delivered      int64
	started        time.Time
	sec            [32]int64
	bytes          [32]int64
	paced          int64
	waitNanos      int64
}

func New(bps int64, enforce bool) *Limiter {
	l := &Limiter{changed: make(chan struct{}), flows: make(map[string]*flow), reservations: make(map[string]reservation), started: time.Now()}
	l.Update(bps, enforce)
	return l
}

// Update drops previously accumulated credit and wakes blocked transfers.
// Configuration validation belongs to the caller.
func (l *Limiter) Update(bps int64, enforce bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.bps, l.enforce = max(0, bps), enforce
	l.generation++
	l.policyRevision++
	l.all = bucket{}
	for _, f := range l.flows {
		f.bucket = bucket{}
	}
	close(l.changed)
	l.changed = make(chan struct{})
}

func (l *Limiter) Snapshot() Snapshot {
	l.mu.Lock()
	defer l.mu.Unlock()
	s := Snapshot{ConfiguredBps: l.bps, Enforcing: l.enforce, DeliveredBytes: l.delivered, PacedBytes: l.paced, WaitNanos: l.waitNanos, Generation: l.generation, PolicyRevision: l.policyRevision}
	now := time.Now().Unix()
	window := min(max(now-l.started.Unix(), 1), int64(30))
	for i, sec := range l.sec {
		if age := now - sec; age >= 0 && age < window {
			s.DeliveredBps += l.bytes[i] * 8 / window
		}
	}
	for _, x := range l.reservations {
		s.ReservedBps += x.bps
	}
	for _, f := range l.flows {
		if f.download {
			s.ActiveDownloads++
		} else {
			s.ActiveViews++
		}
	}
	return s
}

var ErrLimit = errors.New("remote upload limit cannot carry another stream")

// Available returns the remaining capacity for a logical play, including
// its own existing reservation on a seek or quality switch.
func (l *Limiter) Available(id string) int64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	if !l.enforce || l.bps == 0 {
		return 0
	}
	used := int64(0)
	for key, x := range l.reservations {
		if key != id {
			used += x.bps
		}
	}
	return max(0, l.bps-used)
}

// Reserve atomically replaces a play's demand. Undo restores the previous
// demand if session preparation fails. Observe-only mode records but admits.
func (l *Limiter) Reserve(id string, bps int64) (func(), error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if id == "" || bps <= 0 {
		return func() {}, nil
	}
	old, had := l.reservations[id]
	used := int64(0)
	for key, v := range l.reservations {
		if key != id {
			used += v.bps
		}
	}
	if l.enforce && l.bps > 0 && (bps > l.bps || used > l.bps-bps) {
		return nil, ErrLimit
	}
	l.generation++
	serial := l.generation
	l.reservations[id] = reservation{bps: bps, serial: serial}
	return func() {
		l.mu.Lock()
		defer l.mu.Unlock()
		if l.reservations[id].serial != serial {
			return
		}
		if had {
			l.reservations[id] = old
		} else {
			delete(l.reservations, id)
		}
		l.generation++
	}, nil
}

func (l *Limiter) Release(id string) {
	l.mu.Lock()
	if _, ok := l.reservations[id]; ok {
		delete(l.reservations, id)
		l.generation++
	}
	l.mu.Unlock()
}

func (l *Limiter) begin(key string, download bool) func() {
	l.mu.Lock()
	f := l.flows[key]
	if f == nil {
		f = &flow{download: download}
		l.flows[key] = f
	}
	f.requests++
	l.generation++
	close(l.changed)
	l.changed = make(chan struct{})
	l.mu.Unlock()
	return func() {
		l.mu.Lock()
		f.requests--
		if f.requests == 0 {
			delete(l.flows, key)
		}
		l.generation++
		close(l.changed)
		l.changed = make(chan struct{})
		l.mu.Unlock()
	}
}

func (l *Limiter) flowRate(f *flow) int64 {
	views, downloads := 0, 0
	for _, x := range l.flows {
		if x.download {
			downloads++
		} else {
			views++
		}
	}
	if f.download {
		if views > 0 {
			return max(1, l.bps/10/int64(downloads))
		}
		return max(1, l.bps/int64(downloads))
	}
	return max(1, (l.bps-l.bps/10*boolInt(downloads > 0))/int64(views))
}

func boolInt(v bool) int64 {
	if v {
		return 1
	}
	return 0
}

// take charges before a bounded write. A partial/failed write can consume
// at most ChunkBytes of unused credit; it never permits over-delivery.
func (l *Limiter) take(ctx context.Context, key string, n int) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		l.mu.Lock()
		f := l.flows[key]
		if f == nil || !l.enforce || l.bps == 0 {
			l.mu.Unlock()
			return nil
		}
		now := time.Now()
		l.all.refill(now, l.bps)
		f.bucket.refill(now, l.flowRate(f))
		allShort := float64(n) - l.all.tokens
		flowShort := float64(n) - f.bucket.tokens
		if allShort <= 0 && flowShort <= 0 {
			l.all.tokens -= float64(n)
			f.bucket.tokens -= float64(n)
			l.paced += int64(n)
			l.mu.Unlock()
			return nil
		}
		// Recompute both rates after a join, leave or configuration change.
		d := max(
			time.Duration(math.Ceil(max(0, allShort)*8/float64(l.bps)*float64(time.Second))),
			time.Duration(math.Ceil(max(0, flowShort)*8/float64(l.flowRate(f))*float64(time.Second))),
		)
		changed := l.changed
		l.mu.Unlock()
		start := time.Now()
		timer := time.NewTimer(max(time.Microsecond, d))
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-changed:
			timer.Stop()
		case <-timer.C:
		}
		l.mu.Lock()
		l.waitNanos += time.Since(start).Nanoseconds()
		l.mu.Unlock()
	}
}

type Writer struct {
	http.ResponseWriter
	limiter *Limiter
	ctx     context.Context
	key     string
}

func (w *Writer) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *Writer) Flush()                      { _ = http.NewResponseController(w.ResponseWriter).Flush() }
func (w *Writer) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		piece := p[:min(len(p), ChunkBytes)]
		if err := w.limiter.take(w.ctx, w.key, len(piece)); err != nil {
			return total, err
		}
		n, err := w.ResponseWriter.Write(piece)
		w.limiter.record(int64(n))
		total += n
		p = p[n:]
		if err != nil {
			return total, err
		}
		if n != len(piece) {
			return total, io.ErrShortWrite
		}
	}
	return total, nil
}

func (l *Limiter) record(n int64) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.delivered += n
	sec := time.Now().Unix()
	i := int(sec % int64(len(l.sec)))
	if l.sec[i] != sec {
		l.sec[i], l.bytes[i] = sec, 0
	}
	l.bytes[i] += n
}

// ReadFrom intentionally hides the connection's sendfile fast path: that
// path would skip pacing. io.Copy calls Write in bounded chunks instead.
func (w *Writer) ReadFrom(src io.Reader) (int64, error) {
	return io.Copy(struct{ io.Writer }{w}, src)
}

// Wrap tracks a remote response. A stable key combines authenticated identity
// and logical media path; concurrent range requests for it share one bucket.
func (l *Limiter) Wrap(w http.ResponseWriter, ctx context.Context, key string, download bool) (http.ResponseWriter, func()) {
	done := l.begin(key, download)
	return &Writer{ResponseWriter: w, limiter: l, ctx: ctx, key: key}, done
}
