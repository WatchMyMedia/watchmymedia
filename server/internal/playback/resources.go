package playback

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
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

func (m *Manager) Resources() (ResourceSnapshot, error) {
	m.resourceMu.Lock()
	defer m.resourceMu.Unlock()
	return m.resourcesLocked()
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

func (m *Manager) checkResources() {
	r, err := m.Resources()
	if err != nil {
		m.log.Warn("cannot measure playback temporary storage", "err", err)
		return
	}
	m.resourceMu.Lock()
	stopLive := m.stopLive
	m.resourceMu.Unlock()
	var victim *Session
	var liveID string
	var largest int64
	var reason string
	m.mu.Lock()
	owned := make([]*Session, 0, len(m.sessions)+len(m.retiring))
	for s := range m.ownedSessionsLocked() {
		owned = append(owned, s)
	}
	m.mu.Unlock()
	for _, item := range r.Sessions {
		if r.MaxSessionBytes <= 0 || item.Bytes <= r.MaxSessionBytes {
			continue
		}
		if item.Kind == "live" && stopLive != nil && item.Bytes > largest {
			victim, liveID, largest, reason = nil, item.ID, item.Bytes, "session byte limit"
		}
		if item.Kind == "file" {
			for _, s := range owned {
				if filepath.Base(s.Dir) == item.ID && item.Bytes > largest {
					victim, liveID, largest, reason = s, "", item.Bytes, "session byte limit"
				}
			}
		}
	}
	if victim == nil && liveID == "" && ((r.MaxCacheBytes > 0 && r.TemporaryBytes > r.MaxCacheBytes) ||
		(r.FreeBytes != nil && r.MinFreeBytes > 0 && *r.FreeBytes < uint64(r.MinFreeBytes))) {
		for _, item := range r.Sessions {
			if item.Kind == "live" && stopLive != nil && item.Bytes > largest {
				victim, liveID, largest = nil, item.ID, item.Bytes
			}
			if item.Kind == "file" {
				for _, s := range owned {
					if filepath.Base(s.Dir) == item.ID && item.Bytes > largest {
						victim, liveID, largest = s, "", item.Bytes
					}
				}
			}
		}
		reason = "cache byte limit or free-space reserve"
	}
	if liveID != "" {
		m.log.Warn("live TV session stopped for temporary storage", "session", liveID, "reason", reason)
		stopLive(liveID)
		return
	}
	if victim == nil {
		return
	}
	err = fmt.Errorf("%w: %s", ErrStorageBudget, reason)
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
