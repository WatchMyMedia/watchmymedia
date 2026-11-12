package playback

import (
	"errors"
	"os"
	"path/filepath"
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
