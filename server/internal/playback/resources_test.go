package playback

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"
)

func TestPlaybackResourcesCountOnlyOwnedOutputAndRejectLowDisk(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	m := NewManager("unused", dir, Capabilities{}, time.Minute, 2, testLog)
	if err := os.MkdirAll(filepath.Join(m.root, "session"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(m.root, "session", "seg00000.m4s"), make([]byte, 1024), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "unowned.m4s"), make([]byte, 4096), 0o600); err != nil {
		t.Fatal(err)
	}
	m.SetResourceLimits(ResourceLimits{MaxCacheBytes: 2048, MaxSessionBytes: 2048, MinFreeBytes: 100,
		EventAhead: 12 * time.Second, EventKeepBehind: 4 * time.Second, Free: func(string) (uint64, error) { return 99, nil }})
	r, err := m.Resources()
	if err != nil || r.TemporaryBytes != 1024 || r.TemporaryFiles != 1 || !r.ScanComplete {
		t.Fatalf("resources=%+v, %v", r, err)
	}
	if err := m.admitStorage(); !errors.Is(err, ErrStorageBudget) {
		t.Fatalf("admission=%v", err)
	}
}

func TestPlaybackStoragePressureStopsOnlyOwnedSession(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	t.Parallel()
	src := keyedSampleAt(t, ffmpeg, "24", 40, "0")
	m := testManager(t, ffmpeg, "software")
	m.eventAhead = 12 * time.Second
	m.resources.MinFreeBytes = 0
	m.resources.MaxSessionBytes = 1024
	spec := vodTranscodeSpec(src)
	spec.VOD = nil
	s, err := m.Start(t.Context(), spec)
	if err != nil {
		t.Fatal(err)
	}
	m.checkResources()
	if !errors.Is(s.ResourceError(), ErrStorageBudget) {
		t.Fatalf("resource error = %v", s.ResourceError())
	}
	if len(m.Sessions()) != 0 {
		t.Fatal("storage victim remained admitted")
	}
	select {
	case <-s.done:
	case <-time.After(10 * time.Second):
		t.Fatal("storage victim did not join")
	}
	eventually(t, "storage victim files remained", func() bool { _, err := os.Stat(s.Dir); return os.IsNotExist(err) })
}

func TestPlaybackResourcesIncludeLiveTVWithoutDoubleCounting(t *testing.T) {
	t.Parallel()
	for _, nested := range []bool{false, true} {
		t.Run(map[bool]string{false: "sibling", true: "nested"}[nested], func(t *testing.T) {
			dir := t.TempDir()
			m := NewManager("unused", dir, Capabilities{}, time.Minute, 2, testLog)
			liveRoot := filepath.Join(dir, "live")
			if nested {
				liveRoot = filepath.Join(m.root, "live")
			}
			for path, size := range map[string]int{
				filepath.Join(m.root, "file-id", "seg.m4s"):      100,
				filepath.Join(liveRoot, "live-id", "segment.ts"): 200,
			} {
				if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(path, make([]byte, size), 0o600); err != nil {
					t.Fatal(err)
				}
			}
			stopped := ""
			m.SetLiveCache(liveRoot, func(id string) { stopped = id })
			limits := DefaultResourceLimits()
			limits.MinFreeBytes = 0
			limits.MaxCacheBytes = 250
			m.SetResourceLimits(limits)
			r, err := m.Resources()
			if err != nil || r.TemporaryBytes != 300 || r.TemporaryFiles != 2 || len(r.Sessions) != 2 {
				t.Fatalf("resources=%+v, %v", r, err)
			}
			if r.Sessions[0].Kind != "file" || r.Sessions[1].Kind != "live" {
				t.Fatalf("session kinds=%+v", r.Sessions)
			}
			m.checkResources()
			if stopped != "live-id" {
				t.Fatalf("storage victim=%q", stopped)
			}
		})
	}
}

// dirBytes is the size of the files under dir.
func dirBytes(t *testing.T, dir string) int64 {
	t.Helper()
	var n int64
	err := filepath.WalkDir(dir, func(_ string, d fs.DirEntry, err error) error {
		if err != nil || !d.Type().IsRegular() {
			return err
		}
		info, err := d.Info()
		if err == nil {
			n += info.Size()
		}
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	return n
}

// A VOD session keeps its pieces by time, about four minutes ahead of the
// player and two behind, which at a high bit rate is more than the
// per-session storage limit: from 18 Mbit/s or so at the default 512 MiB.
// The watchdog then stopped an ordinary play, and the player got a 404 for
// the session. Here the default window is kept and the limit lowered to two
// thirds of the clip, which the run made whole once two segments were asked
// for. The session must hold its pieces within the limit, never be stopped
// and serve every segment.
func TestVODStaysWithinTheSessionByteLimit(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	t.Parallel()
	src := keyedSampleAt(t, ffmpeg, "24", 60, "expr:gte(t,n_forced*2)")
	k := NewKeyframes(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "", testLog)
	st, err := os.Stat(src)
	if err != nil {
		t.Fatal(err)
	}
	keys, err := k.Get(t.Context(), src, st.Size(), "x")
	if err != nil {
		t.Fatal(err)
	}
	m := testManager(t, ffmpeg, "software")
	limits := DefaultResourceLimits()
	limits.MinFreeBytes = 0 // the test machine's own disk is not the subject
	limits.MaxSessionBytes = st.Size() * 2 / 3
	m.SetResourceLimits(limits)
	spec := Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: src, Mode: ModeRemux, BurnIn: -1,
		Video: VideoPlan{Codec: "mpeg4", Copy: true, Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Copy: true},
		VOD: &VODOptions{DurationMs: 60_000, Keyframes: keys, FrameRate: 24}}
	s, err := m.Start(t.Context(), spec)
	if err != nil {
		t.Fatal(err)
	}
	defer m.Stop(s.ID)
	if s.vod.Segments() != 10 {
		t.Fatalf("segments = %d", s.vod.Segments())
	}
	fetch := func(k int) {
		t.Helper()
		if b, _, err := s.VODFile(t.Context(), fmt.Sprintf("v%05d.m4s", k)); err != nil || len(b) == 0 {
			t.Fatalf("segment %d: %v (resource error %v)", k, err, s.ResourceError())
		}
	}
	settle := func(when string) {
		t.Helper()
		// Wait for ffmpeg to stop: paused, or at the end of the clip.
		eventually(t, "the run did not stop "+when, func() bool { return !s.vod.running() })
		m.checkResources()
		if err := s.ResourceError(); err != nil {
			t.Fatalf("%s: stopped for storage: %v (%d bytes on disk, limit %d)", when, err, dirBytes(t, s.Dir), limits.MaxSessionBytes)
		}
		if _, ok := m.Get(s.ID); !ok {
			t.Fatalf("%s: session no longer registered", when)
		}
		if n := dirBytes(t, s.Dir); n > limits.MaxSessionBytes {
			t.Fatalf("%s: %d bytes on disk, over the limit of %d", when, n, limits.MaxSessionBytes)
		}
	}
	fetch(0)
	fetch(1)
	settle("after two segments")
	for k := 2; k < s.vod.Segments(); k++ {
		fetch(k)
		m.checkResources()
		if err := s.ResourceError(); err != nil {
			t.Fatalf("segment %d: stopped for storage: %v", k, err)
		}
	}
	settle("at the end")
}

// Over the cache limit, the watchdog stops the largest sessions until the
// rest fit, and counts a stopped session's folder as freed while its files
// wait to be deleted. It used to stop the largest session each tick and,
// while that folder was still there, the next largest a tick later, and so
// on until none were left.
func TestStoragePressureStopsOnlyWhatIsNeeded(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name     string
		maxCache int64
		stopped  []string
	}{
		{"one is enough", 250, []string{"big"}},
		{"two are needed", 120, []string{"big", "mid"}},
		{"all are needed", 10, []string{"big", "mid", "small"}},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			m := NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 4, testLog)
			limits := DefaultResourceLimits()
			limits.MinFreeBytes = 0
			limits.MaxCacheBytes = c.maxCache
			m.SetResourceLimits(limits)
			sizes := map[string]int{"big": 140, "mid": 100, "small": 60}
			sessions := map[string]*Session{}
			for id, size := range sizes {
				// ffmpeg that is slow to exit: done never closes, so the
				// folder stays until the test ends.
				done := make(chan struct{})
				t.Cleanup(func() { close(done) })
				s := &Session{ID: id, Dir: filepath.Join(m.root, id), done: done, cancel: func() {}}
				if err := os.MkdirAll(s.Dir, 0o755); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(filepath.Join(s.Dir, "r1-00000.m4s"), make([]byte, size), 0o600); err != nil {
					t.Fatal(err)
				}
				m.mu.Lock()
				m.sessions[id] = s
				m.mu.Unlock()
				sessions[id] = s
			}
			for range 4 {
				m.checkResources()
			}
			var stopped []string
			for _, id := range []string{"big", "mid", "small"} {
				if errors.Is(sessions[id].ResourceError(), ErrStorageBudget) {
					stopped = append(stopped, id)
				}
			}
			if !slices.Equal(stopped, c.stopped) {
				t.Fatalf("stopped %v, want %v", stopped, c.stopped)
			}
		})
	}
}

// The settings and activity pages show the watchdog's last measurement:
// reading it neither walks the session folders nor waits for resourceMu,
// which every playback start takes.
func TestLastResourcesNeedsNoScan(t *testing.T) {
	t.Parallel()
	m := NewManager("unused", t.TempDir(), Capabilities{}, time.Minute, 2, testLog)
	limits := DefaultResourceLimits()
	limits.Free = func(string) (uint64, error) { return 1 << 40, nil }
	m.SetResourceLimits(limits)
	write := func(name string) {
		t.Helper()
		if err := os.MkdirAll(filepath.Join(m.root, "session"), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(m.root, "session", name), make([]byte, 1000), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write("r1-00000.m4s")
	m.checkResources()
	write("r1-00001.m4s") // after the measurement

	m.resourceMu.Lock()
	got := make(chan ResourceSnapshot, 1)
	go func() {
		r, err := m.LastResources()
		if err != nil {
			t.Error(err)
		}
		got <- r
	}()
	var r ResourceSnapshot
	select {
	case r = <-got:
	case <-time.After(5 * time.Second):
		m.resourceMu.Unlock()
		t.Fatal("LastResources waited for resourceMu")
	}
	m.resourceMu.Unlock()
	if r.TemporaryBytes != 1000 || r.TemporaryFiles != 1 || !r.ScanComplete || len(r.Sessions) != 1 {
		t.Fatalf("snapshot = %+v, want the watchdog's measurement", r)
	}

	// With no measurement fresh enough (Run is not running), it measures.
	old := *m.lastResources.Load()
	old.r.At = time.Now().Add(-time.Minute)
	m.lastResources.Store(&old)
	if r, err := m.LastResources(); err != nil || r.TemporaryBytes != 2000 {
		t.Fatalf("stale snapshot served: %+v %v", r, err)
	}
}
