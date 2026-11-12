package playback

import (
	"context"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

func needFFmpeg(t *testing.T) string {
	t.Helper()
	p, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	return p
}

// sample renders a 6-second clip with a subtitle stream into an MKV.
func sample(t *testing.T, ffmpeg string) string {
	t.Helper()
	dir := t.TempDir()
	srt := filepath.Join(dir, "subs.srt")
	if err := os.WriteFile(srt, []byte("1\n00:00:01,000 --> 00:00:03,000\nHello there\n\n2\n00:00:04,000 --> 00:00:05,500\nSecond line\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(dir, "clip.mkv")
	cmd := exec.Command(ffmpeg, "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=6",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=6",
		"-i", srt,
		"-map", "0:v", "-map", "1:a", "-map", "2:s",
		"-c:v", "mpeg4", "-q:v", "4", "-c:a", "aac", "-c:s", "srt", "-shortest", out)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg sample: %v\n%s", err, b)
	}
	// A sidecar next to it as well.
	if err := os.WriteFile(filepath.Join(dir, "clip.en.srt"), []byte("1\n00:00:00,500 --> 00:00:02,000\nSidecar\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return out
}

// testLog shows warnings and errors.
var testLog = slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))

// detected runs Detect once per ffmpeg and preference for all the tests.
var (
	detectedMu sync.Mutex
	detected   = map[string]func() Capabilities{}
)

func detectedCaps(ffmpeg, pref string) Capabilities {
	detectedMu.Lock()
	get, ok := detected[ffmpeg+"|"+pref]
	if !ok {
		get = sync.OnceValue(func() Capabilities { return Detect(context.Background(), ffmpeg, pref, testLog) })
		detected[ffmpeg+"|"+pref] = get
	}
	detectedMu.Unlock()
	return get()
}

func testManager(t *testing.T, ffmpeg string, pref string) *Manager {
	t.Helper()
	return NewManager(ffmpeg, filepath.Join(t.TempDir(), "transcode"), detectedCaps(ffmpeg, pref), time.Minute, 4, testLog)
}

func TestRemuxSession(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	clip := sample(t, ffmpeg)
	m := testManager(t, ffmpeg, "software")
	s, err := m.Start(context.Background(), Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: clip, Mode: ModeRemux, Video: VideoPlan{Codec: "mpeg4", Copy: true}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1})
	if err != nil {
		t.Fatal(err)
	}
	// Wait for ffmpeg to finish the short clip.
	select {
	case <-s.done:
	case <-time.After(30 * time.Second):
		t.Fatal("ffmpeg did not finish")
	}
	if s.Err() != nil {
		t.Fatal(s.Err())
	}
	pl, err := s.ReadPlaylist()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(pl), "#EXT-X-MAP:URI=\"init.mp4\"") || !strings.Contains(string(pl), "#EXT-X-ENDLIST") || strings.Count(string(pl), "#EXTINF") < 1 {
		t.Errorf("playlist:\n%s\nffmpeg stderr:\n%s", pl, s.cmd.Tail())
		files, _ := filepath.Glob(filepath.Join(s.Dir, PlaylistName+"*"))
		for _, path := range files {
			b, err := os.ReadFile(path)
			t.Logf("%s: %q (%v)", filepath.Base(path), b, err)
		}
	}
	if _, err := os.Stat(filepath.Join(s.Dir, "init.mp4")); err != nil {
		t.Error("init.mp4 missing")
	}
	segs, _ := filepath.Glob(filepath.Join(s.Dir, "seg*.m4s"))
	if len(segs) == 0 {
		t.Error("no segments")
	}
	// Starting another session for the same play replaces this one and its dir goes away.
	s2, err := m.Start(context.Background(), Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: clip, Mode: ModeRemux, StartMs: 3000, Video: VideoPlan{Copy: true}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.Get(s.ID); ok {
		t.Error("old session still registered")
	}
	<-s2.done
	eventually(t, "old session dir not removed", func() bool {
		_, err := os.Stat(s.Dir)
		return os.IsNotExist(err)
	})
	m.StopAll()
}

// Two people watching one file each keep their session. Restarting a play
// replaces only that play's session, and only for the user who owns it.
func TestPlaysOfOneFile(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	clip := sample(t, ffmpeg)
	m := testManager(t, ffmpeg, "software")
	defer m.StopAll()
	start := func(play string, user, at int64) *Session {
		t.Helper()
		s, err := m.Start(context.Background(), Spec{FileID: 1, PlayID: play, UserID: user, Path: clip, Mode: ModeRemux, StartMs: at,
			Video: VideoPlan{Codec: "mpeg4", Copy: true}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1})
		if err != nil {
			t.Fatal(err)
		}
		return s
	}
	running := func(s *Session) bool {
		_, ok := m.Get(s.ID)
		return ok
	}
	a := start("aaaa", 1, 0)
	b := start("bbbb", 2, 0)
	if !running(a) || !running(b) {
		t.Fatal("the second viewer of the file stopped the first")
	}
	a2 := start("aaaa", 1, 3000) // a seek
	if running(a) || !running(a2) || !running(b) {
		t.Errorf("after a seek: old %v, new %v, other viewer %v", running(a), running(a2), running(b))
	}
	c := start("aaaa", 2, 0) // another user sending the same play ID
	if !running(a2) || !running(c) {
		t.Error("another user's request replaced the owner's session")
	}
	m.StopPlay("aaaa", 1)
	if running(a2) || !running(b) || !running(c) {
		t.Errorf("StopPlay: owner's %v, others %v %v", running(a2), running(b), running(c))
	}
}

func TestNegotiatedReplacementKeepsWorkingSession(t *testing.T) {
	ffmpeg := needFFmpeg(t)
	clip := sample(t, ffmpeg)
	m := testManager(t, ffmpeg, "software")
	defer m.StopAll()
	spec := Spec{FileID: 1, PlayID: "recovery", UserID: 1, Path: clip, Mode: ModeRemux,
		Video: VideoPlan{Codec: "mpeg4", Copy: true}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	old, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	broken := spec
	broken.PreservePrevious = true
	broken.Path = filepath.Join(t.TempDir(), "missing.mkv")
	if _, err := m.Start(context.Background(), broken); err == nil {
		t.Fatal("broken replacement started")
	}
	if _, ok := m.Get(old.ID); !ok {
		t.Fatal("failed replacement displaced old session")
	}
	spec.PreservePrevious = true
	next, err := m.Start(context.Background(), spec)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := m.Get(old.ID); !ok {
		t.Fatal("replacement stopped old before acknowledgement")
	}
	if _, ok := m.Get(next.ID); !ok {
		t.Fatal("replacement was not registered")
	}
	m.Stop(old.ID)
	if _, ok := m.Get(next.ID); !ok {
		t.Fatal("acknowledging one session stopped the replacement")
	}
}

func TestAudioAndFullTranscodeSessions(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	clip := sample(t, ffmpeg)
	m := testManager(t, ffmpeg, "software")
	if m.caps.Encoder != "libx264" {
		t.Skip("libx264 not available")
	}
	s, err := m.Start(context.Background(), Spec{FileID: 2, Path: clip, Mode: ModeAudio, Video: VideoPlan{Codec: "mpeg4", Copy: true}, Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1})
	if err != nil {
		t.Fatal(err)
	}
	<-s.done
	if s.Err() != nil {
		t.Fatal(s.Err())
	}
	burn := 2
	if !m.caps.LibASS {
		burn = -1
	}
	// The subtitle is stream 2 of the file but subtitle stream 0.
	s, err = m.Start(context.Background(), Spec{FileID: 3, Path: clip, Mode: ModeTranscode, StartMs: 1000,
		Video: VideoPlan{Codec: "h264", Height: 144}, Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, MaxBitrate: 500_000, BurnIn: burn, BurnInSub: 0})
	if err != nil {
		t.Fatal(err)
	}
	<-s.done
	if s.Err() != nil {
		t.Fatal(s.Err())
	}
	pl, _ := s.ReadPlaylist()
	if !strings.Contains(string(pl), "#EXT-X-ENDLIST") {
		t.Errorf("transcode playlist incomplete:\n%s", pl)
	}
	m.StopAll()
}

func TestSubtitleConversion(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	clip := sample(t, ffmpeg)
	subs := NewSubtitles(ffmpeg, filepath.Join(t.TempDir(), "subs"))
	vtt, err := subs.Embedded(context.Background(), 1, clip, 2, "webvtt")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(string(vtt), "WEBVTT") || !strings.Contains(string(vtt), "Hello there") {
		t.Errorf("vtt = %q", vtt)
	}
	// Second call is served from the cache (same content).
	again, _ := subs.Embedded(context.Background(), 1, clip, 2, "webvtt")
	if string(again) != string(vtt) {
		t.Error("cache mismatch")
	}
	side := FindSidecars(clip)
	if len(side) != 1 {
		t.Fatalf("sidecars = %+v", side)
	}
	sv, err := subs.Sidecar(context.Background(), 1, 0, side[0], "webvtt")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(sv), "Sidecar") {
		t.Errorf("sidecar vtt = %q", sv)
	}
	ass, err := subs.Embedded(context.Background(), 1, clip, 2, "ass")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(ass), "[Script Info]") {
		t.Errorf("ass = %q", ass[:min(len(ass), 80)])
	}
}

func TestDetect(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	c := detectedCaps(ffmpeg, "auto")
	if c.Encoder == "" || c.HWAccel == "" {
		t.Errorf("caps = %+v", c)
	}
	if c2 := detectedCaps(ffmpeg, "software"); c2.Encoder != "libx264" && len(c2.Encoders) > 0 {
		t.Errorf("software pref -> %s", c2.Encoder)
	}
	// The build the releases bundle converts Dolby Vision 5 and strips
	// Dolby Vision from copies; CI runs with it.
	if v, _ := exec.Command(ffmpeg, "-version").Output(); strings.Contains(string(v), "Jellyfin") && (!c.DoviToneMap || !c.doviStrip) {
		t.Errorf("jellyfin-ffmpeg: doviToneMap %v, dovi_rpu %v", c.DoviToneMap, c.doviStrip)
	}
}

func TestTranscodeArgs(t *testing.T) {
	t.Parallel()
	spec := Spec{Path: "/m/movie.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 1920, Height: 1080}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	vt := NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "h264_videotoolbox", HWAccel: "videotoolbox"}, time.Minute, 1, log)
	got := strings.Join(vt.args(spec, t.TempDir()), " ")
	if !strings.Contains(got, "-hwaccel videotoolbox -i /m/movie.mkv") || !strings.Contains(got, "scale=1920:1080") {
		t.Errorf("videotoolbox args = %s", got)
	}
	sw := NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "libx264", HWAccel: "software"}, time.Minute, 1, log)
	got = strings.Join(sw.args(spec, t.TempDir()), " ")
	if strings.Contains(got, "-hwaccel") || strings.Contains(got, "-level") {
		t.Errorf("software args = %s", got)
	}
	// Copying video needs no decoder at all.
	spec.Video.Copy = true
	if got = strings.Join(vt.args(spec, t.TempDir()), " "); strings.Contains(got, "-hwaccel") {
		t.Errorf("copy args = %s", got)
	}
}

func TestVideoToolboxGPUArgs(t *testing.T) {
	t.Parallel()
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "h264_videotoolbox", HWAccel: "videotoolbox", GPUFilters: true, ToneMap: true, LibASS: true, Overlay: true, gpuToneMap: true}, time.Minute, 1, log)
	vf := func(spec Spec) string {
		args := m.args(spec, t.TempDir())
		joined := strings.Join(args, " ")
		if !strings.Contains(joined, "-hwaccel videotoolbox -hwaccel_output_format videotoolbox_vld -init_hw_device videotoolbox=vt -filter_hw_device vt") {
			t.Errorf("device args missing: %s", joined)
		}
		if strings.Contains(joined, " scale=") || strings.Contains(joined, "zscale") {
			t.Errorf("CPU filters in GPU path: %s", joined)
		}
		for i, a := range args {
			if (a == "-vf" || a == "-filter_complex") && i+1 < len(args) {
				return args[i+1]
			}
		}
		t.Fatalf("no filter graph in %s", joined)
		return ""
	}
	base := Spec{Path: "/m/movie.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 1920, Height: 1080}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	if got, want := vf(base), "format=nv12|p010le|videotoolbox_vld,hwupload,scale_vt=w=1920:h=1080"; got != want {
		t.Errorf("plain: %s", got)
	}
	hdr := base
	hdr.Video = VideoPlan{Codec: "h264", Height: 1080, HDR: "hdr10", BitDepth: 10, ToneMap: true}
	if got, want := vf(hdr), "format=nv12|p010le|videotoolbox_vld,hwupload,scale_vt=w=-2:h='min(ih,1080)':color_matrix=bt709:color_primaries=bt709:color_transfer=bt709"; got != want {
		t.Errorf("tone map: %s", got)
	}
	// Burn-in pulls the scaled frame back at the source's depth.
	burn := hdr
	burn.BurnIn, burn.BurnInSub = 3, 1
	if got := vf(burn); !strings.HasSuffix(got, "hwdownload,format=p010le,format=yuv420p,subtitles='/m/movie.mkv':si=1") {
		t.Errorf("text burn-in: %s", got)
	}
	burn.Video.BitDepth = 8
	burn.BurnInImage = true
	if got := vf(burn); !strings.Contains(got, "hwdownload,format=nv12,format=yuv420p[v];[v][0:3]overlay[out]") {
		t.Errorf("image burn-in: %s", got)
	}
	// Later in the file the overlay reads the subtitle from a second input, opened
	// earlier and without the hardware decoder (TestImageBurnInDrawsTheCueOnScreenAtTheStart).
	later := burn
	later.StartMs, later.VOD = 36000, &VODOptions{DurationMs: 60000}
	if got := strings.Join(m.args(later, t.TempDir()), " "); !strings.Contains(got, "-filter_hw_device vt -ss 36.000 -copyts -start_at_zero -i /m/movie.mkv -ss 6.000 -i /m/movie.mkv -filter_complex") ||
		!strings.Contains(got, "[v][1:3]overlay[out]") {
		t.Errorf("image burn-in later in the file: %s", got)
	}
	// Same size and SDR: nothing to do but hand the frames to the encoder.
	same := base
	same.Video = VideoPlan{Codec: "h264"}
	if got, want := vf(same), "format=nv12|p010le|videotoolbox_vld,hwupload"; got != want {
		t.Errorf("no-op: %s", got)
	}
	// HEVC output keeping HDR: Main 10, hvc1 tag, no colour conversion.
	hevc := base
	hevc.Video = VideoPlan{Codec: "hevc", Encoder: "hevc_videotoolbox", Width: 3840, Height: 2160, HDR: "hdr10", BitDepth: 10}
	if got := vf(hevc); got != "format=nv12|p010le|videotoolbox_vld,hwupload,scale_vt=w=3840:h=2160" {
		t.Errorf("hevc hdr filters: %s", got)
	}
	if got := strings.Join(m.args(hevc, t.TempDir()), " "); !strings.Contains(got, "-c:v hevc_videotoolbox -b:v 20000000 -maxrate 20000000 -bufsize 40000000 -profile:v main10 -tag:v hvc1") {
		t.Errorf("hevc hdr encoder args: %s", got)
	}
	hevc.BurnIn, hevc.BurnInSub = 3, 1
	if got := vf(hevc); !strings.HasSuffix(got, "hwdownload,format=p010le,format=yuv420p10le,subtitles='/m/movie.mkv':si=1") {
		t.Errorf("hevc hdr burn-in: %s", got)
	}
	hevc.Video.ToneMap = true
	hevc.BurnIn = -1
	if got := strings.Join(m.args(hevc, t.TempDir()), " "); !strings.Contains(got, "color_transfer=bt709") || !strings.Contains(got, "-profile:v main -tag:v hvc1") {
		t.Errorf("hevc sdr args: %s", got)
	}
}

func TestEncoderArgsCPUPathKeepsHDR(t *testing.T) {
	t.Parallel()
	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "h264_nvenc", HEVCEncoder: "hevc_nvenc", HWAccel: "nvenc", ToneMap: true, zscale: true}, time.Minute, 1, log)
	spec := Spec{Path: "/m/movie.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "hevc", Encoder: "hevc_nvenc", Height: 2160, HDR: "hdr10", BitDepth: 10}, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	got := strings.Join(m.args(spec, t.TempDir()), " ")
	if !strings.Contains(got, "format=p010le") || strings.Contains(got, "zscale") || !strings.Contains(got, "-c:v hevc_nvenc") || !strings.Contains(got, "-profile:v main10") {
		t.Errorf("nvenc hdr args: %s", got)
	}
	spec.Video.ToneMap = true
	if got = strings.Join(m.args(spec, t.TempDir()), " "); !strings.Contains(got, "tonemap=") || !strings.Contains(got, "format=yuv420p") || !strings.Contains(got, "-profile:v main ") {
		t.Errorf("nvenc sdr args: %s", got)
	}
}

// On a Mac with ffmpeg's VideoToolbox filters, the GPU path must transcode
// an 8-bit software-decoded source and a 10-bit HDR source end to end.
func TestVideoToolboxGPUSession(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	m := testManager(t, ffmpeg, "videotoolbox")
	if !m.caps.GPUFilters {
		t.Skip("no VideoToolbox scale_vt")
	}
	if !m.caps.gpuToneMap {
		t.Error("scale_vt works but its tone mapping did not pass Detect")
	}
	clip := sample(t, ffmpeg)
	dir := t.TempDir()
	// x265 writes the HDR colour tags into the stream, which is what real
	// files carry; the VideoToolbox encoder ignores them, so a clip from it
	// only proves the 10-bit path works.
	hdr := filepath.Join(dir, "hdr.mp4")
	tagged := true
	src := []string{"-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=3"}
	colour := []string{"-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc", "-tag:v", "hvc1", "-an", hdr}
	x265 := append(append(append([]string{}, src...), "-c:v", "libx265", "-preset", "ultrafast", "-pix_fmt", "yuv420p10le",
		"-x265-params", "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"), colour...)
	if exec.Command(ffmpeg, x265...).Run() != nil {
		tagged = false
		vt := append(append(append([]string{}, src...), "-c:v", "hevc_videotoolbox", "-profile:v", "main10", "-pix_fmt", "p010le", "-b:v", "2M"), colour...)
		if b, err := exec.Command(ffmpeg, vt...).CombinedOutput(); err != nil {
			t.Skipf("cannot make a 10-bit clip: %v\n%s", err, b)
		}
	}
	specs := []Spec{
		{FileID: 10, Path: clip, Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Height: 144}, Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1},
		{FileID: 11, Path: hdr, Mode: ModeTranscode, StartMs: 500, Video: VideoPlan{Codec: "h264", Width: 320, Height: 180, HDR: "hdr10", BitDepth: 10, ToneMap: true}, Audio: AudioPlan{Index: 0, Copy: true}, BurnIn: -1},
	}
	if m.caps.HEVCEncoder != "" {
		// HDR carried through as 10-bit HEVC.
		specs = append(specs, Spec{FileID: 12, Path: hdr, Mode: ModeTranscode, Video: VideoPlan{Codec: "hevc", Encoder: m.caps.HEVCEncoder, Width: 320, Height: 180, HDR: "hdr10", BitDepth: 10}, Audio: AudioPlan{Index: 0, Copy: true}, BurnIn: -1})
	}
	for _, spec := range specs {
		s, err := m.Start(context.Background(), spec)
		if err != nil {
			t.Fatal(err)
		}
		select {
		case <-s.done:
		case <-time.After(60 * time.Second):
			t.Fatalf("file %d: ffmpeg did not finish", spec.FileID)
		}
		if s.Err() != nil {
			t.Fatalf("file %d: %v", spec.FileID, s.Err())
		}
		pl, _ := s.ReadPlaylist()
		if !strings.Contains(string(pl), "#EXT-X-ENDLIST") {
			t.Errorf("file %d: playlist incomplete:\n%s", spec.FileID, pl)
		}
		if spec.Video.Codec == "hevc" {
			out, err := exec.Command(filepath.Join(filepath.Dir(ffmpeg), "ffprobe"), "-v", "error", "-select_streams", "v:0",
				"-show_entries", "stream=codec_name,pix_fmt,color_transfer", "-of", "csv=p=0", filepath.Join(s.Dir, "init.mp4")).CombinedOutput()
			want := "hevc,yuv420p10le,"
			if tagged {
				want += "smpte2084"
			}
			if err != nil || !strings.HasPrefix(string(out), want) {
				t.Errorf("hevc init segment: %q %v", out, err)
			}
		}
	}
	m.StopAll()
}

// A build that lists NVENC without a usable GPU must fall back to libx264.
func TestDetectSkipsUnusableEncoder(t *testing.T) {
	t.Parallel()
	if runtime.GOOS == "windows" {
		t.Skip("fake ffmpeg is a shell script")
	}
	fake := filepath.Join(t.TempDir(), "ffmpeg")
	script := `#!/bin/sh
case "$*" in
*-encoders*) printf ' V..... libx264              x264\n V....D h264_nvenc           NVENC\n' ;;
*h264_nvenc*) echo "Cannot load libcuda.so.1" >&2; exit 255 ;;
esac
exit 0
`
	if err := os.WriteFile(fake, []byte(script), 0o700); err != nil { //nolint:gosec // G306: a script, which must be executable
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, pref := range []string{"auto", "nvenc"} {
		c := Detect(context.Background(), fake, pref, log)
		if c.Encoder != "libx264" || c.HWAccel != "software" || strings.Join(c.Encoders, ",") != "libx264" {
			t.Errorf("pref %s: caps = %+v", pref, c)
		}
	}
}

func TestProgressWriter(t *testing.T) {
	t.Parallel()
	s := &Session{StartMs: 60_000}
	w := &progressWriter{s: s}
	// Blocks arrive in arbitrary chunks; a line may be split across writes.
	for _, chunk := range []string{"frame=10\nfps=24.0\nout_time_us=2500", "000\nspeed=N/A\nprogress=continue\n", "out_time_us=4000000\nspeed=3.25x\n"} {
		if _, err := w.Write([]byte(chunk)); err != nil {
			t.Fatal(err)
		}
	}
	speed, pos := s.Progress()
	if speed != 3.25 || pos != 64_000 {
		t.Errorf("progress = %v, %d; want 3.25, 64000", speed, pos)
	}
}
