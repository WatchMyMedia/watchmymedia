package playback

import (
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"testing"
	"testing/synctest"
	"time"
)

func TestCapabilitiesWaitCanBeCanceledByViewer(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		m := NewManager("unused", t.TempDir(), Capabilities{Encoder: "libx264"}, time.Minute, 1, testLog)
		m.capsReady = make(chan struct{}) // Detection is still running.
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan Capabilities, 1)
		go func() { done <- m.CapabilitiesContext(ctx) }()
		synctest.Wait()
		cancel()
		synctest.Wait()
		select {
		case c := <-done:
			if c.Encoder != "libx264" || m.ctx.Err() != nil {
				t.Fatal("viewer cancellation affected shared encoder detection")
			}
		default:
			t.Fatal("viewer cancellation did not stop waiting for detection")
		}
		close(m.capsReady)
	})
}

// slowDetectFFmpeg is a stand-in ffmpeg whose encoder list takes delay
// seconds and lists libx264 and h264_nvenc, which does not work; runs counts
// the lists.
func slowDetectFFmpeg(t *testing.T, delay string) (bin, runs string) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("needs a shell")
	}
	dir := t.TempDir()
	bin, runs = filepath.Join(dir, "ffmpeg"), filepath.Join(dir, "runs")
	script := `#!/bin/sh
case "$*" in
*-version*) echo "ffmpeg version test" ;;
*-encoders*) echo run >> "` + runs + `"; sleep ` + delay + `; printf ' V..... libx264              x264\n V....D h264_nvenc           NVENC\n' ;;
*h264_nvenc*) exit 1 ;;
esac
exit 0
`
	if err := os.WriteFile(bin, []byte(script), 0o700); err != nil { //nolint:gosec // G306: a script, which must be executable
		t.Fatal(err)
	}
	return bin, runs
}

// Detection runs in the background: Capabilities waits for it, the result
// is kept, and the next start uses what was kept at once while it detects
// again.
func TestDetectInBackground(t *testing.T) {
	t.Parallel()
	bin, runs := slowDetectFFmpeg(t, "0.5")
	cache := filepath.Join(t.TempDir(), "transcoder.json")
	log := slog.New(slog.DiscardHandler)

	m := NewManager(bin, t.TempDir(), Capabilities{}, time.Minute, 1, log)
	start := time.Now()
	m.DetectInBackground("auto", cache)
	if d := time.Since(start); d > 200*time.Millisecond {
		t.Errorf("DetectInBackground blocked for %v", d)
	}
	if c := m.currentCaps(); c.Encoder != "libx264" || c.MaxHeight != 1080 {
		t.Errorf("before detection: %+v", c)
	}
	c := m.Capabilities()
	if d := time.Since(start); d < 400*time.Millisecond {
		t.Errorf("Capabilities answered after %v, before detection finished", d)
	}
	if !slices.Equal(c.Encoders, []string{"libx264"}) || c.Detected.IsZero() {
		t.Fatalf("detected %+v", c)
	}
	eventually(t, "the detection was not kept", func() bool { _, err := os.Stat(cache); return err == nil })

	// The next start answers from what was kept, and detects again.
	m2 := NewManager(bin, t.TempDir(), Capabilities{}, time.Minute, 1, log)
	start = time.Now()
	m2.DetectInBackground("auto", cache)
	c2 := m2.Capabilities()
	if d := time.Since(start); d > 400*time.Millisecond || !c2.Detected.Equal(c.Detected) {
		t.Errorf("kept detection: %v, detected %v (first %v)", d, c2.Detected, c.Detected)
	}
	eventually(t, "it did not detect again", func() bool { return countRuns(t, runs) == 2 })

	// Another preference is not what was kept.
	m3 := NewManager(bin, t.TempDir(), Capabilities{}, time.Minute, 1, log)
	start = time.Now()
	m3.DetectInBackground("software", cache)
	m3.Capabilities()
	if d := time.Since(start); d < 400*time.Millisecond {
		t.Errorf("used a detection kept for another preference (%v)", d)
	}
	// A newer detection's result is not overwritten by an older one.
	m4 := NewManager(bin, t.TempDir(), Capabilities{}, time.Minute, 1, log)
	older, newer := m4.newDetection(), m4.newDetection()
	m4.setCaps(newer, Capabilities{Encoder: "h264_videotoolbox"})
	if m4.setCaps(older, Capabilities{Encoder: "libx264"}) || m4.Capabilities().Encoder != "h264_videotoolbox" {
		t.Errorf("an older detection replaced a newer one: %+v", m4.Capabilities())
	}
}

// Shutting down lets requests waiting on detection go.
func TestCapabilitiesDuringShutdown(t *testing.T) {
	t.Parallel()
	bin, _ := slowDetectFFmpeg(t, "30")
	m := NewManager(bin, t.TempDir(), Capabilities{}, time.Minute, 1, slog.New(slog.DiscardHandler))
	m.DetectInBackground("auto", "")
	ctx, cancel := context.WithCancel(context.Background())
	go m.Run(ctx)
	cancel()
	done := make(chan Capabilities)
	go func() { done <- m.Capabilities() }()
	select {
	case c := <-done:
		if c.Encoder != "libx264" {
			t.Errorf("caps = %+v", c)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("Capabilities did not return")
	}
}

// A kept detection keeps what only the session reads: zscale, the GPU's
// tone mapping and dovi_rpu.
func TestKeptDetectionKeepsUnexportedCaps(t *testing.T) {
	t.Parallel()
	file := filepath.Join(t.TempDir(), "transcoder.json")
	key := capsKey{Path: "/usr/bin/ffmpeg", Size: 1, Version: "ffmpeg version 8.1.3-Jellyfin", Pref: "auto"}
	c := Capabilities{Encoder: "libx264", HWAccel: "software", ToneMap: true, DoviToneMap: true, zscale: true, gpuToneMap: true, doviStrip: true}
	storeCaps(file, key, c)
	got, ok := loadCaps(file, key)
	if !ok || !got.DoviToneMap || !got.zscale || !got.gpuToneMap || !got.doviStrip {
		t.Errorf("kept %+v, %v", got, ok)
	}
}

func TestDetectionCacheSeparatesAdaptersAndProbeRevisions(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	one, two := 1, 2
	a := capsKeyForDevice(t.Context(), ffmpeg, "nvenc", &one)
	b := capsKeyForDevice(t.Context(), ffmpeg, "nvenc", &two)
	if a == b || a.Revision != hardwareProbeRevision {
		t.Fatal("cache did not distinguish adapters/revision")
	}
	c := Capabilities{Encoder: "h264_nvenc", HWAccel: "nvenc", Device: &one, MaxHeight: 2160, Diagnostics: []HardwareDiagnostic{{Encoder: "h264_nvenc", Stage: "4k-pipeline", OK: true, FPS: 96}}}
	file := filepath.Join(t.TempDir(), "caps.json")
	storeCaps(file, a, c)
	if got, ok := loadCaps(file, a); !ok || got.Device == nil || *got.Device != 1 || len(got.Diagnostics) != 1 {
		t.Fatalf("cache lost device/diagnostics: %+v %v", got, ok)
	}
	if _, ok := loadCaps(file, b); ok {
		t.Fatal("cache from another adapter was reused")
	}
	// Earlier revisions timed the encoder alone, then ffmpeg's start-up with the frames.
	for rev := range hardwareProbeRevision {
		old := a
		old.Revision = rev
		storeCaps(file, old, c)
		if _, ok := loadCaps(file, a); ok {
			t.Fatalf("revision %d 4K result was reused", rev)
		}
	}
}
