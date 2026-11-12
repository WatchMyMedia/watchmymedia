package playback

import (
	"context"
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/hwdevice"
)

// Detecting what ffmpeg can do takes a few seconds (test encodes on every
// hardware path, a timed 4K encode), longer on a machine whose drivers
// make the tests time out. The server listens meanwhile: DetectInBackground
// runs it, and what needs the result waits for it (Capabilities). The last
// result is kept in a file and used at once while ffmpeg, the preference
// and the build are the same, and detection then runs again to catch a
// GPU that has come or gone.

// capsWait is how long Capabilities waits for detection before it answers
// with the software defaults.
const capsWait = 30 * time.Second

// defaultCaps is what a transcode uses before detection says otherwise:
// libx264 at 1080p, as when detection finds no hardware.
func defaultCaps(ffmpeg string) Capabilities {
	return Capabilities{FFmpeg: ffmpeg, Encoders: []string{}, Encoder: "libx264", HWAccel: "software", MaxHeight: 1080}
}

// Capabilities is what ffmpeg can do, waiting (up to capsWait) for
// detection to finish.
func (m *Manager) Capabilities() Capabilities {
	return m.CapabilitiesContext(context.Background())
}

// CapabilitiesContext also stops waiting when this playback attempt ends.
// Canceling one viewer must not cancel server-wide encoder detection.
func (m *Manager) CapabilitiesContext(ctx context.Context) Capabilities {
	m.capsMu.Lock()
	ready := m.capsReady
	m.capsMu.Unlock()
	t := time.NewTimer(capsWait)
	defer t.Stop()
	select {
	case <-ready:
	case <-t.C:
		m.log.Warn("encoder detection is taking long; using software defaults meanwhile")
	case <-m.ctx.Done():
	case <-ctx.Done():
	}
	return m.currentCaps()
}

// currentCaps is what is known now, without waiting: for building a
// session's command line, whose decision has already waited.
func (m *Manager) currentCaps() Capabilities {
	m.capsMu.Lock()
	defer m.capsMu.Unlock()
	c := m.caps
	c.Encoders = slices.Clone(c.Encoders)
	if c.Encoders == nil {
		c.Encoders = []string{}
	}
	c.Diagnostics = append([]HardwareDiagnostic(nil), c.Diagnostics...)
	c.Device = hwdevice.Index(c.Device)
	return c
}

// SetAdapterScanner configures bounded inventory discovery before serving.
// Nil keeps the existing behavior on platforms without native enumeration.
func (m *Manager) SetAdapterScanner(scan hwdevice.Scanner) {
	m.capsMu.Lock()
	defer m.capsMu.Unlock()
	m.adapterScan = scan
}

func (m *Manager) HardwareAdapters() *hwdevice.Inventory {
	m.capsMu.Lock()
	defer m.capsMu.Unlock()
	if m.adapters == nil {
		return nil
	}
	i := m.adapters.Clone()
	return &i
}

func (m *Manager) setAdapters(gen int, i hwdevice.Inventory) bool {
	m.capsMu.Lock()
	defer m.capsMu.Unlock()
	if gen != m.capsGen {
		return false
	}
	copy := i.Clone()
	m.adapters = &copy
	return true
}

// newDetection starts a detection that supersedes any before it, and
// returns its number for setCaps.
func (m *Manager) newDetection() int {
	m.capsMu.Lock()
	defer m.capsMu.Unlock()
	return m.newDetectionLocked()
}

func (m *Manager) newDetectionLocked() int {
	m.capsGen++
	if m.capsCancel != nil {
		m.capsCancel()
	}
	select {
	case <-m.capsReady:
	default:
		close(m.capsReady)
	}
	return m.capsGen
}

// setCaps stores detection gen's result, unless a newer detection has
// started, and wakes those waiting.
func (m *Manager) setCaps(gen int, c Capabilities) bool {
	m.capsMu.Lock()
	defer m.capsMu.Unlock()
	if gen != m.capsGen {
		return false
	}
	m.caps = c
	select {
	case <-m.capsReady:
	default:
		close(m.capsReady)
	}
	return true
}

// DetectInBackground detects encoders for the hwaccel preference without
// blocking, using the result kept in cacheFile (when it is not "") until
// the detection finishes. Call it before the server takes requests.
func (m *Manager) DetectInBackground(pref, cacheFile string) {
	m.DetectOnDeviceInBackground(pref, nil, cacheFile)
}

// DetectOnDeviceInBackground probes a selected adapter. Every generation owns
// its context; changing preference cancels obsolete probes and cannot publish
// their results. A browser disconnect does not abandon a committed preference.
func (m *Manager) DetectOnDeviceInBackground(pref string, device *int, cacheFile string) {
	m.detectInBackground(pref, device, cacheFile, true)
}

func (m *Manager) detectInBackground(pref string, device *int, cacheFile string, useCache bool) {
	m.capsMu.Lock()
	if m.ctx.Err() != nil {
		m.capsMu.Unlock()
		return
	}
	gen := m.newDetectionLocked()
	m.caps = defaultCaps(m.ffmpeg)
	m.capsReady = make(chan struct{})
	if cacheFile != "" {
		m.capsFile = cacheFile
	}
	file := m.capsFile
	ctx, cancel := context.WithCancel(m.ctx)
	m.capsCancel = cancel
	device = hwdevice.Index(device)
	scan := m.adapterScan
	if scan != nil {
		i := hwdevice.Inventory{Supported: true, Adapters: []hwdevice.Adapter{}, Diagnostics: []hwdevice.EnumerationDiagnostic{}}
		if m.adapters != nil {
			i = m.adapters.Clone()
		}
		i.Checking = true
		m.adapters = &i
	}
	m.detectWG.Add(1)
	m.capsMu.Unlock()
	go func() {
		defer m.detectWG.Done()
		defer cancel()
		key := capsKeyForDevice(ctx, m.ffmpeg, pref, device)
		if useCache {
			if c, ok := loadCaps(file, key); ok && m.setCaps(gen, c) {
				m.logCaps("transcoder (from last detection; detecting again)", c)
			}
		}
		if scan != nil {
			i := scan(ctx)
			if ctx.Err() != nil {
				m.setAdapters(gen, hwdevice.Inventory{Supported: true, Adapters: []hwdevice.Adapter{}, Diagnostics: []hwdevice.EnumerationDiagnostic{}})
				return
			}
			i.Checking = false
			m.setAdapters(gen, i)
		}
		c := DetectOnDevice(ctx, m.ffmpeg, pref, device, m.log)
		if ctx.Err() != nil {
			m.setCaps(gen, m.currentCaps())
			return
		}
		if m.setCaps(gen, c) {
			m.logCaps("transcoder", c)
			storeCaps(file, key, c)
		}
	}()
}

// SetHardwarePreferenceInBackground is used after the preference is committed.
func (m *Manager) SetHardwarePreferenceInBackground(pref string, device *int) {
	m.detectInBackground(pref, device, "", false)
}

// SetPreference preserves the existing default-adapter entry point.
func (m *Manager) SetPreference(ctx context.Context, pref string) Capabilities {
	return m.SetHardwarePreference(ctx, pref, nil)
}

func (m *Manager) SetHardwarePreference(ctx context.Context, pref string, device *int) Capabilities {
	m.SetHardwarePreferenceInBackground(pref, device)
	return m.CapabilitiesContext(ctx)
}

func (m *Manager) logCaps(msg string, c Capabilities) {
	device := -1
	if c.Device != nil {
		device = *c.Device
	}
	m.log.Info(msg, "encoder", c.Encoder, "hevc", c.HEVCEncoder, "hwaccel", c.HWAccel, "gpu_filters", c.GPUFilters,
		"device", device, "max_height", c.MaxHeight, "speed_4k", c.Speed4K, "tonemap", c.ToneMap, "dovi_tonemap", c.DoviToneMap, "libass", c.LibASS)
}

// capsKey says which ffmpeg and preference a kept detection is for.
type capsKey struct {
	Revision int    `json:"revision"`
	Device   int    `json:"device"`
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	ModTime  int64  `json:"modTime"`
	Version  string `json:"version"`
	Pref     string `json:"pref"`
}

// Revision 2 invalidates detections made by the old encoder-only 4K test;
// revision 3 those that timed ffmpeg's start-up with the 4K frames;
// revision 4 those made before VAAPI had a GPU pipeline to prove.
const hardwareProbeRevision = 4

func capsKeyForDevice(ctx context.Context, ffmpeg, pref string, device *int) capsKey {
	path, err := exec.LookPath(ffmpeg)
	if err != nil {
		return capsKey{}
	}
	if abs, err := filepath.Abs(path); err == nil {
		path = abs
	}
	st, err := os.Stat(path)
	if err != nil {
		return capsKey{}
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	out, err := ffexec.Command(ctx, path, ffexec.FFmpegArgs("-version")...).Output()
	if err != nil {
		return capsKey{}
	}
	version, _, _ := strings.Cut(string(out), "\n")
	index := -1
	if device != nil {
		index = *device
	}
	return capsKey{Revision: hardwareProbeRevision, Device: index, Path: path, Size: st.Size(), ModTime: st.ModTime().UnixNano(), Version: strings.TrimSpace(version), Pref: pref}
}

// capsFile is a kept detection. Capabilities' unexported fields are kept
// beside it.
type capsFile struct {
	Key         capsKey              `json:"key"`
	Caps        Capabilities         `json:"caps"`
	ZScale      bool                 `json:"zscale"`
	GPUToneMap  bool                 `json:"gpuToneMap"`
	DoviStrip   bool                 `json:"doviStrip"`
	Diagnostics []HardwareDiagnostic `json:"diagnostics,omitempty"`
}

func loadCaps(file string, key capsKey) (Capabilities, bool) {
	if file == "" || key == (capsKey{}) {
		return Capabilities{}, false
	}
	b, err := os.ReadFile(file)
	if err != nil {
		return Capabilities{}, false
	}
	var f capsFile
	if json.Unmarshal(b, &f) != nil || f.Key != key || f.Caps.Encoder == "" {
		return Capabilities{}, false
	}
	c := f.Caps
	c.zscale, c.gpuToneMap, c.doviStrip = f.ZScale, f.GPUToneMap, f.DoviStrip
	c.Diagnostics = f.Diagnostics
	if c.Encoders == nil {
		c.Encoders = []string{}
	}
	return c, true
}

func storeCaps(file string, key capsKey, c Capabilities) {
	if file == "" || key == (capsKey{}) {
		return
	}
	b, err := json.Marshal(capsFile{Key: key, Caps: c, ZScale: c.zscale, GPUToneMap: c.gpuToneMap, DoviStrip: c.doviStrip, Diagnostics: c.Diagnostics})
	if err != nil {
		return
	}
	tmp, err := os.CreateTemp(filepath.Dir(file), filepath.Base(file)+".*.tmp")
	if err != nil {
		return
	}
	_, werr := tmp.Write(b)
	if cerr := tmp.Close(); werr != nil || cerr != nil {
		_ = os.Remove(tmp.Name())
		return
	}
	if os.Rename(tmp.Name(), file) != nil {
		_ = os.Remove(tmp.Name())
	}
}
