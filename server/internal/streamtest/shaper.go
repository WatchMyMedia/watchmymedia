// Package streamtest provides test-only streaming tools. Nothing here is wired
// into the production server: the fictional fixture owns the HTTP middleware.
package streamtest

import (
	"context"
	"errors"
	"maps"
	"math"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	ChunkBytes = 16 << 10
	BurstBytes = 2 * ChunkBytes
	maxViewers = 32
)

// Rate uses bits per second; zero is unlimited, and Blocked models an outage.
type Rate struct {
	Bps     int64 `json:"bps"`
	Blocked bool  `json:"blocked,omitempty"`
}

type Profile struct {
	Aggregate Rate            `json:"aggregate"`
	Viewers   map[string]Rate `json:"viewers,omitempty"`
}

type Traffic struct {
	Bytes    int64 `json:"bytes"`
	Requests int64 `json:"requests"`
	Active   int   `json:"active"`
}

type Snapshot struct {
	AtNs     int64              `json:"atNs"`
	Revision int                `json:"revision"`
	Profile  Profile            `json:"profile"`
	Burst    int                `json:"burstBytes"`
	Total    Traffic            `json:"total"`
	ByViewer map[string]Traffic `json:"byViewer"`
}

type bucket struct {
	last   time.Time
	tokens float64
}

// Shaper charges the aggregate and viewer buckets atomically. They are shared
// across requests/connections; opening more ranges cannot multiply the cap.
// Profile changes wake already-blocked writes and discard accumulated credit.
type Shaper struct {
	mu       sync.Mutex
	started  time.Time
	profile  Profile
	revision int
	changed  chan struct{}
	all      bucket
	viewers  map[string]*bucket
	traffic  map[string]Traffic
	total    Traffic
}

func NewShaper() *Shaper {
	return &Shaper{started: time.Now(), changed: make(chan struct{}), viewers: map[string]*bucket{}, traffic: map[string]Traffic{}}
}

var viewerPattern = regexp.MustCompile(`^viewer-([1-9]|[12][0-9]|3[0-2])$`)

func validRate(r Rate) bool { return r.Bps >= 0 && r.Bps <= 1_000_000_000_000 }

func (s *Shaper) Update(p Profile) error {
	if !validRate(p.Aggregate) || len(p.Viewers) > maxViewers {
		return errors.New("invalid fixture bandwidth profile")
	}
	copy := Profile{Aggregate: p.Aggregate, Viewers: map[string]Rate{}}
	for id, rate := range p.Viewers {
		if !viewerPattern.MatchString(id) || !validRate(rate) {
			return errors.New("invalid fixture viewer or bandwidth")
		}
		copy.Viewers[id] = rate
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.profile = copy
	s.revision++
	s.all = bucket{}
	clear(s.viewers)
	close(s.changed)
	s.changed = make(chan struct{})
	return nil
}

func (s *Shaper) Snapshot() Snapshot {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := Profile{Aggregate: s.profile.Aggregate, Viewers: maps.Clone(s.profile.Viewers)}
	out := Snapshot{AtNs: time.Since(s.started).Nanoseconds(), Revision: s.revision, Profile: p, Burst: BurstBytes, Total: s.total, ByViewer: maps.Clone(s.traffic)}
	return out
}

func (b *bucket) refill(now time.Time, rate Rate) {
	if b.last.IsZero() {
		b.last = now
	}
	if rate.Bps == 0 && !rate.Blocked {
		b.tokens = BurstBytes
	} else {
		b.tokens = math.Min(BurstBytes, b.tokens+now.Sub(b.last).Seconds()*float64(rate.Bps)/8)
	}
	b.last = now
}

func delay(b *bucket, r Rate, n int) time.Duration {
	if r.Blocked {
		return time.Hour // a profile change or cancellation wakes this early
	}
	if b.tokens >= float64(n) {
		return 0
	}
	return max(time.Microsecond, time.Duration(math.Ceil((float64(n)-b.tokens)*8/float64(r.Bps)*float64(time.Second))))
}

func (s *Shaper) wait(ctx context.Context, id string, n int) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		s.mu.Lock()
		b := s.viewers[id]
		if b == nil {
			b = &bucket{}
			s.viewers[id] = b
		}
		now, rate := time.Now(), s.profile.Viewers[id]
		s.all.refill(now, s.profile.Aggregate)
		b.refill(now, rate)
		d := max(delay(&s.all, s.profile.Aggregate, n), delay(b, rate, n))
		if d == 0 {
			s.all.tokens -= float64(n)
			b.tokens -= float64(n)
			s.mu.Unlock()
			return nil
		}
		changed := s.changed
		s.mu.Unlock()
		timer := time.NewTimer(d)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-changed:
			timer.Stop()
		case <-timer.C:
		}
	}
}

func (s *Shaper) count(id string, f func(*Traffic)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := s.traffic[id]
	f(&v)
	s.traffic[id] = v
	f(&s.total)
}

// MediaPath limits shaping to GET media, never login, heartbeat or controls.
func MediaPath(path string) bool {
	_, name, sessionFile := strings.Cut(strings.TrimPrefix(path, "/api/playback/"), "/")
	return (strings.HasPrefix(path, "/api/files/") && strings.HasSuffix(path, "/stream")) ||
		(strings.HasPrefix(path, "/api/playback/") && sessionFile && name != "") ||
		(strings.HasPrefix(path, "/api/live-tv/sessions/") && strings.Contains(path, "/hls/")) ||
		(strings.HasPrefix(path, "/api/offline/") && (strings.HasSuffix(path, "/stream") ||
			strings.Contains(path, "/hls/") || strings.Contains(path, "/subtitles/")))
}

// Middleware uses a bounded test identity, independent of authorization. The
// header is fixture-only and never grants permission to the underlying routes.
func (s *Shaper) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || !MediaPath(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}
		id := r.Header.Get("X-WMM-Fixture-Viewer")
		if !viewerPattern.MatchString(id) {
			id = "unattributed"
		}
		s.count(id, func(v *Traffic) { v.Requests++; v.Active++ })
		defer s.count(id, func(v *Traffic) { v.Active-- })
		next.ServeHTTP(&pacedWriter{ResponseWriter: w, shaper: s, ctx: r.Context(), id: id}, r)
	})
}

type pacedWriter struct {
	http.ResponseWriter
	shaper *Shaper
	ctx    context.Context
	id     string
}

func (w *pacedWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *pacedWriter) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		n := min(len(p), ChunkBytes)
		if err := w.shaper.wait(w.ctx, w.id, n); err != nil {
			return total, err
		}
		written, err := w.ResponseWriter.Write(p[:n])
		w.shaper.count(w.id, func(v *Traffic) { v.Bytes += int64(written) })
		total += written
		p = p[written:]
		if err != nil {
			return total, err
		}
		if written != n {
			return total, errors.New("short fixture media write")
		}
		// Flush each charged chunk rather than collecting a second, unpaced
		// HTTP buffer that would obscure mid-transfer rate changes.
		_ = http.NewResponseController(w.ResponseWriter).Flush()
	}
	return total, nil
}
