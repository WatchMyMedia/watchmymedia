package playback

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"time"

	"watchmymedia/server/internal/bgwork"
)

var ErrStorageBudget = errors.New("playback temporary storage unavailable")

// ResourceLimits cover owned temporary playback output. A zero byte limit
// disables that particular limit. Set before the manager starts serving.
type ResourceLimits struct {
	MaxCacheBytes   int64
	MaxSessionBytes int64
	MinFreeBytes    int64
	EventAhead      time.Duration
	EventKeepBehind time.Duration
	Free            func(string) (uint64, error) // test hook
}

func DefaultResourceLimits() ResourceLimits {
	return ResourceLimits{MaxCacheBytes: 4 << 30, MaxSessionBytes: 512 << 20,
		MinFreeBytes: 1 << 30, EventAhead: 32 * time.Second, EventKeepBehind: 32 * time.Second}
}

func (m *Manager) SetResourceLimits(l ResourceLimits) {
	m.resources = l
	m.eventAhead = l.EventAhead
	m.eventResume = min(l.EventAhead/4, eventResume)
}

type SessionResources struct {
	Kind  string `json:"kind"`
	ID    string `json:"id"`
	Bytes int64  `json:"bytes"`
	Files int    `json:"files"`
}

type ResourceSnapshot struct {
	At                time.Time          `json:"at"`
	TemporaryBytes    int64              `json:"temporaryBytes"`
	TemporaryFiles    int                `json:"temporaryFiles"`
	FreeBytes         *uint64            `json:"freeBytes"`
	ScanComplete      bool               `json:"scanComplete"`
	MaxCacheBytes     int64              `json:"maxCacheBytes"`
	MaxSessionBytes   int64              `json:"maxSessionBytes"`
	MinFreeBytes      int64              `json:"minFreeBytes"`
	EventAheadMs      int64              `json:"eventAheadMs"`
	EventKeepBehindMs int64              `json:"eventKeepBehindMs"`
	Sessions          []SessionResources `json:"sessions"`
}

// Resources measures the owned temporary output now. It walks every
// session's folder under resourceMu, which every playback start takes too,
// so readers that only show the figures use LastResources.
func (m *Manager) Resources() (ResourceSnapshot, error) {
	m.resourceMu.Lock()
	r, err := m.resourcesLocked()
	m.resourceMu.Unlock()
	m.lastResources.Store(&resourceReading{r: r, err: err})
	return r, err
}

type resourceReading struct {
	r   ResourceSnapshot
	err error
}

// resourcesFresh is how old a measurement LastResources serves may be. The
// watchdog measures every resourceEvery while Run runs; past this, Run is
// not running and LastResources measures itself.
const resourcesFresh = 5 * time.Second

// LastResources is the latest measurement of the owned temporary output,
// taken by the storage watchdog at most resourcesFresh ago, for the pages
// that show it. It neither walks the disk nor takes resourceMu then.
func (m *Manager) LastResources() (ResourceSnapshot, error) {
	if last := m.lastResources.Load(); last != nil && time.Since(last.r.At) < resourcesFresh {
		r := last.r
		r.Sessions = slices.Clone(r.Sessions)
		return r, last.err
	}
	return m.Resources()
}

// RemoveOutputDir deletes an owned file or Live TV session's output while
// excluding resource scans. On Windows, opening a directory that is being
// deleted can fail with a sharing violation and reject an unrelated start.
func (m *Manager) RemoveOutputDir(dir string) error {
	m.resourceMu.Lock()
	defer m.resourceMu.Unlock()
	return os.RemoveAll(dir)
}

const resourceScanFileLimit = 100_000

func (m *Manager) resourcesLocked() (ResourceSnapshot, error) {
	l := m.resources
	out := ResourceSnapshot{At: time.Now(), MaxCacheBytes: l.MaxCacheBytes,
		MaxSessionBytes: l.MaxSessionBytes, MinFreeBytes: l.MinFreeBytes,
		EventAheadMs: l.EventAhead.Milliseconds(), EventKeepBehindMs: l.EventKeepBehind.Milliseconds(),
		Sessions: []SessionResources{}}
	entries, err := os.ReadDir(m.root)
	if errors.Is(err, os.ErrNotExist) {
		entries, err = nil, nil
	}
	if err != nil {
		return out, err
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		path := filepath.Join(m.root, e.Name())
		if m.liveRoot != "" && filepath.Clean(path) == filepath.Clean(m.liveRoot) {
			continue
		}
		if err = scanSession(path, "file", e.Name(), &out); err != nil {
			return out, err
		}
	}
	if m.liveRoot != "" {
		liveEntries, readErr := os.ReadDir(m.liveRoot)
		if errors.Is(readErr, os.ErrNotExist) {
			liveEntries, readErr = nil, nil
		}
		if readErr != nil {
			return out, readErr
		}
		for _, e := range liveEntries {
			if !e.IsDir() {
				continue
			}
			if err = scanSession(filepath.Join(m.liveRoot, e.Name()), "live", e.Name(), &out); err != nil {
				return out, err
			}
		}
	}
	sort.Slice(out.Sessions, func(i, j int) bool {
		if out.Sessions[i].Kind == out.Sessions[j].Kind {
			return out.Sessions[i].ID < out.Sessions[j].ID
		}
		return out.Sessions[i].Kind < out.Sessions[j].Kind
	})
	free := l.Free
	if free == nil {
		free = bgwork.FreeBytes
	}
	// The owned directory may not yet exist before Clean. Walk to an
	// existing ancestor without creating any directory in a read-only call.
	path := m.root
	for {
		if _, statErr := os.Stat(path); statErr == nil {
			break
		}
		parent := filepath.Dir(path)
		if parent == path {
			break
		}
		path = parent
	}
	n, err := free(path)
	if err != nil {
		return out, err
	}
	out.FreeBytes = &n
	out.ScanComplete = true
	return out, nil
}

// SetLiveCache includes the owned rolling Live TV HLS output in the same
// cache budget. Call after constructing Live TV sessions and before serving.
func (m *Manager) SetLiveCache(root string, stop func(string)) {
	m.resourceMu.Lock()
	defer m.resourceMu.Unlock()
	m.liveRoot, m.stopLive = root, stop
}

func scanSession(path, kind, id string, out *ResourceSnapshot) error {
	item := SessionResources{Kind: kind, ID: id}
	err := filepath.WalkDir(path, func(_ string, d fs.DirEntry, walkErr error) error {
		if errors.Is(walkErr, os.ErrNotExist) {
			return nil
		}
		if walkErr != nil {
			return walkErr
		}
		if !d.Type().IsRegular() {
			return nil
		}
		if out.TemporaryFiles >= resourceScanFileLimit {
			return errors.New("playback cache scan file limit reached")
		}
		info, statErr := d.Info()
		if errors.Is(statErr, os.ErrNotExist) {
			return nil
		}
		if statErr != nil {
			return statErr
		}
		item.Bytes += info.Size()
		item.Files++
		out.TemporaryBytes += info.Size()
		out.TemporaryFiles++
		return nil
	})
	if err != nil {
		return err
	}
	out.Sessions = append(out.Sessions, item)
	return nil
}

func (m *Manager) admitStorage() error {
	r, err := m.Resources()
	if err != nil {
		return fmt.Errorf("%w: measure temporary volume: %w", ErrStorageBudget, err)
	}
	if r.MaxCacheBytes > 0 && r.TemporaryBytes >= r.MaxCacheBytes {
		return fmt.Errorf("%w: cache byte limit", ErrStorageBudget)
	}
	if r.FreeBytes != nil && r.MinFreeBytes > 0 && *r.FreeBytes < uint64(r.MinFreeBytes) {
		return fmt.Errorf("%w: free-space reserve", ErrStorageBudget)
	}
	return nil
}

// CheckStorage is the read-only admission check used before Live TV creates
// a logical session. ReserveLive repeats it immediately before opening a tuner.
func (m *Manager) CheckStorage() error { return m.admitStorage() }

type resourceFailure struct{ err error }

func (s *Session) ResourceError() error {
	if f := s.resourceFailure.Load(); f != nil {
		return f.err
	}
	return nil
}

// checkResources is the storage watchdog. It stops every session past the
// per-session limit and, while the cache is past its limit or the volume
// below its free-space reserve, the largest sessions, only as many as
// bring the figures back within bounds. Folders already on their way out
// (sessions stopped, by the watchdog or otherwise, whose ffmpeg has not
// exited or whose files are not yet deleted) count as freed: stopping more
// sessions for bytes about to go stopped one more every tick until none
// were left.
func (m *Manager) checkResources() {
	m.watchMu.Lock()
	defer m.watchMu.Unlock()
	r, err := m.Resources()
	if err != nil {
		m.log.Warn("cannot measure playback temporary storage", "err", err)
		return
	}
	m.resourceMu.Lock()
	stopLive := m.stopLive
	m.resourceMu.Unlock()
	owned := map[string]*Session{}
	removing := map[string]bool{}
	m.mu.Lock()
	for s := range m.ownedSessionsLocked() {
		owned[filepath.Base(s.Dir)] = s
	}
	for name := range m.removing {
		removing[name] = true
	}
	m.mu.Unlock()

	type candidate struct {
		item SessionResources
		s    *Session // nil for a Live TV output
	}
	var candidates []candidate
	var leaving int64
	live := map[string]bool{}
	for _, item := range r.Sessions {
		switch item.Kind {
		case "live":
			live[item.ID] = true
			if _, stopped := m.stoppedLive[item.ID]; stopped {
				leaving += item.Bytes
			} else if stopLive != nil {
				candidates = append(candidates, candidate{item: item})
			}
		case "file":
			// A folder of no session that is not being deleted either (one
			// whose deletion failed) still counts against the limits.
			s := owned[item.ID]
			switch {
			case s != nil && s.resourceFailure.Load() == nil:
				candidates = append(candidates, candidate{item: item, s: s})
			case s != nil || removing[item.ID]:
				leaving += item.Bytes
			}
		}
	}
	for id := range m.stoppedLive {
		if !live[id] {
			delete(m.stoppedLive, id)
		}
	}
	// Largest first, as the sessions whose stop frees the most.
	sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].item.Bytes > candidates[j].item.Bytes })

	stop := func(c candidate, reason string) {
		if c.s == nil {
			m.log.Warn("live TV session stopped for temporary storage", "session", c.item.ID, "bytes", c.item.Bytes, "reason", reason)
			if m.stoppedLive == nil {
				m.stoppedLive = map[string]struct{}{}
			}
			m.stoppedLive[c.item.ID] = struct{}{}
			stopLive(c.item.ID)
			return
		}
		m.stopForStorage(c.s, reason)
	}
	var kept []candidate
	freed := leaving
	for _, c := range candidates {
		if r.MaxSessionBytes > 0 && c.item.Bytes > r.MaxSessionBytes {
			stop(c, "session byte limit")
			freed += c.item.Bytes
			continue
		}
		kept = append(kept, c)
	}
	over := func() bool {
		if r.MaxCacheBytes > 0 && r.TemporaryBytes-freed > r.MaxCacheBytes {
			return true
		}
		// Deleted files give their room back to the volume.
		return r.FreeBytes != nil && r.MinFreeBytes > 0 && int64(*r.FreeBytes)+freed < r.MinFreeBytes //nolint:gosec // G115: free bytes fit an int64
	}
	for _, c := range kept {
		if !over() {
			break
		}
		stop(c, "cache byte limit or free-space reserve")
		freed += c.item.Bytes
	}
}

// stopForStorage stops a session the watchdog chose, failing its requests
// with ErrStorageBudget.
func (m *Manager) stopForStorage(victim *Session, reason string) {
	err := fmt.Errorf("%w: %s", ErrStorageBudget, reason)
	if victim.resourceFailure.CompareAndSwap(nil, &resourceFailure{err: err}) {
		m.log.Warn("playback session stopped for temporary storage", "session", victim.ID, "reason", reason)
		m.mu.Lock()
		m.stopLocked(victim.ID)
		// Storage enforcement overrides a shared producer lease. Its peers
		// must fail or retry rather than let an over-budget worker continue.
		victim.cancel()
		m.mu.Unlock()
	}
}
