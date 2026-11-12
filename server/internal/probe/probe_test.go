package probe

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/probe/probetest"
)

// sampleFile renders a one-second test clip with ffmpeg, or skips the test
// when ffmpeg is not installed.
func sampleFile(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	out := filepath.Join(t.TempDir(), "sample.mp4")
	cmd := exec.Command("ffmpeg", "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=64x64:rate=10:duration=1",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=1",
		"-c:v", "mpeg4", "-c:a", "aac", "-shortest", out)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	return out
}

func TestProbeSample(t *testing.T) {
	t.Parallel()
	file := sampleFile(t)
	info, err := (&Prober{}).Probe(context.Background(), file)
	if err != nil {
		t.Fatal(err)
	}
	if info.Container != "mov" {
		t.Errorf("container = %q, want mov (mp4 family)", info.Container)
	}
	if info.DurationMs < 900 || info.DurationMs > 1200 {
		t.Errorf("duration = %d ms", info.DurationMs)
	}
	if len(info.Video) != 1 || info.Video[0].Codec != "mpeg4" || info.Video[0].Width != 64 || info.Video[0].Height != 64 {
		t.Errorf("video = %+v", info.Video)
	}
	if len(info.Audio) != 1 || info.Audio[0].Codec != "aac" || info.Audio[0].Channels == 0 {
		t.Errorf("audio = %+v", info.Audio)
	}
	if info.Video[0].FrameRate != 10 {
		t.Errorf("frame rate = %v", info.Video[0].FrameRate)
	}
}

func TestProbeMissingFile(t *testing.T) {
	t.Parallel()
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe not installed")
	}
	_, err := (&Prober{}).Probe(context.Background(), filepath.Join(t.TempDir(), "nope.mkv"))
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestReduceHDRAndSubtitles(t *testing.T) {
	t.Parallel()
	var raw rawOutput
	raw.Format.FormatName = "matroska,webm"
	raw.Format.Duration = "7261.5"
	raw.Streams = []rawStream{
		{Index: 0, CodecType: "video", CodecName: "hevc", Width: 3840, Height: 2160, AvgFrameRate: "24000/1001", PixFmt: "yuv420p10le", ColorTransfer: "smpte2084"},
		{Index: 1, CodecType: "video", CodecName: "mjpeg", Disposition: map[string]int{"attached_pic": 1}},
		{Index: 2, CodecType: "audio", CodecName: "truehd", Channels: 8, Tags: map[string]string{"language": "eng"}, Disposition: map[string]int{"default": 1}},
		{Index: 3, CodecType: "subtitle", CodecName: "hdmv_pgs_subtitle", Tags: map[string]string{"language": "eng"}, Disposition: map[string]int{"forced": 1}},
	}
	info := reduce(raw)
	if info.Container != "matroska" || info.DurationMs != 7261500 {
		t.Errorf("container/duration = %q/%d", info.Container, info.DurationMs)
	}
	if len(info.Video) != 1 {
		t.Fatalf("video streams = %d, want 1 (cover art excluded)", len(info.Video))
	}
	v := info.Video[0]
	if v.HDR != "hdr10" || v.BitDepth != 10 || v.FrameRate != 23.976 {
		t.Errorf("video = %+v", v)
	}
	if a := info.Audio[0]; a.Codec != "truehd" || a.Channels != 8 || !a.Default || a.Language != "eng" {
		t.Errorf("audio = %+v", a)
	}
	s := info.Subtitles[0]
	if !s.Image || !s.Forced || s.Language != "eng" {
		t.Errorf("subtitle = %+v", s)
	}
}

func TestReduceDolbyVision(t *testing.T) {
	t.Parallel()
	// As ffprobe prints a profile 8.1 stream: the numbers come as JSON numbers.
	var raw rawOutput
	raw.Format.FormatName = "matroska,webm"
	raw.Streams = []rawStream{{Index: 0, CodecType: "video", CodecName: "hevc", Width: 3840, Height: 2160, ColorTransfer: "smpte2084",
		SideDataList: []map[string]any{{"side_data_type": "DOVI configuration record", "dv_version_major": 1.0, "dv_profile": 8.0, "dv_level": 6.0,
			"dv_bl_signal_compatibility_id": 1.0}}}}
	v := reduce(raw).Video[0]
	if v.HDR != "dolbyvision" || v.DVProfile != 8 {
		t.Errorf("video = %+v, want Dolby Vision profile 8", v)
	}
	raw.Streams[0].SideDataList = nil
	if v := reduce(raw).Video[0]; v.HDR != "hdr10" || v.DVProfile != 0 {
		t.Errorf("HDR10 video = %+v", v)
	}
}

func TestChapters(t *testing.T) {
	t.Parallel()
	src := sampleFile(t)
	dir := t.TempDir()
	meta := filepath.Join(dir, "chapters.txt")
	text := ";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=600\ntitle=Episode\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=600\nEND=1000\ntitle=End Credits\n"
	if err := os.WriteFile(meta, []byte(text), 0o600); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(dir, "chaptered.mkv")
	if b, err := exec.Command("ffmpeg", "-v", "error", "-y", "-i", src, "-i", meta, "-map_metadata", "1", "-map_chapters", "1", "-c", "copy", out).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	got, err := (&Prober{}).Chapters(context.Background(), out)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 2 || got[0].Title != "Episode" || got[1].Title != "End Credits" || got[1].StartMs != 600 || got[1].EndMs != 1000 {
		t.Errorf("chapters = %+v", got)
	}
	none, err := (&Prober{}).Chapters(context.Background(), src)
	if err != nil || len(none) != 0 {
		t.Errorf("chapters of a plain file = %+v, %v", none, err)
	}
}

// The scan's one ffprobe run reads the chapters and the start time too,
// and the stored probe keeps them while the API's JSON does not show them.
func TestProbeStoresChaptersAndStart(t *testing.T) {
	t.Parallel()
	src := sampleFile(t)
	dir := t.TempDir()
	meta := filepath.Join(dir, "chapters.txt")
	text := ";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=600\ntitle=Episode\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=600\nEND=1000\ntitle=End Credits\n"
	if err := os.WriteFile(meta, []byte(text), 0o600); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(dir, "chaptered.mkv")
	if b, err := exec.Command("ffmpeg", "-v", "error", "-y", "-i", src, "-i", meta, "-map_metadata", "1", "-map_chapters", "1", "-c", "copy", out).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	info, err := (&Prober{}).Probe(context.Background(), out)
	if err != nil {
		t.Fatal(err)
	}
	if !info.Extended || len(info.Chapters) != 2 || info.Chapters[1].Title != "End Credits" || info.Chapters[1].StartMs != 600 {
		t.Fatalf("probe = %+v", info)
	}
	start, err := (&Prober{}).StartTime(context.Background(), out)
	if err != nil || start != info.StartUs {
		t.Errorf("start %d (%v), probe says %d", start, err, info.StartUs)
	}
	raw, err := Encode(info)
	if err != nil {
		t.Fatal(err)
	}
	back, err := Parse(string(raw))
	if err != nil || !back.Extended || len(back.Chapters) != 2 || back.StartUs != info.StartUs || back.DurationMs != info.DurationMs || len(back.Video) != 1 {
		t.Errorf("parsed back = %+v, %v", back, err)
	}
	api, _ := json.Marshal(info)
	if strings.Contains(string(api), "hapters") || strings.Contains(string(api), "startUs") || strings.Contains(string(api), "xtended") {
		t.Errorf("the API's probe shows playback's fields: %s", api)
	}
	// A probe stored before these were kept says so.
	old, err := Parse(string(api))
	if err != nil || old.Extended || old.DurationMs != info.DurationMs {
		t.Errorf("old probe = %+v, %v", old, err)
	}
	// No chapters is still read.
	plain, _ := (&Prober{}).Probe(context.Background(), src)
	raw, _ = Encode(plain)
	if back, _ := Parse(string(raw)); !back.Extended || back.Chapters == nil {
		t.Errorf("plain file parsed back = %+v", back)
	}
}

// Commentary and audio-description tracks keep their dispositions in the
// stored probe, so a language preference can pass them over; the API's
// JSON doesn't show them.
func TestProbeStoresSecondaryAudio(t *testing.T) {
	t.Parallel()
	src := sampleFile(t)
	out := filepath.Join(t.TempDir(), "tracks.mkv")
	if b, err := exec.Command("ffmpeg", "-v", "error", "-y", "-i", src,
		"-map", "0:v", "-map", "0:a", "-map", "0:a", "-map", "0:a", "-c", "copy",
		"-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=eng", "-metadata:s:a:2", "language=eng",
		"-disposition:a:0", "default", "-disposition:a:1", "comment", "-disposition:a:2", "visual_impaired", out).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	info, err := (&Prober{}).Probe(context.Background(), out)
	if err != nil {
		t.Fatal(err)
	}
	if len(info.Audio) != 3 || info.Audio[0].Commentary || info.Audio[0].Descriptive || !info.Audio[1].Commentary || !info.Audio[2].Descriptive {
		t.Fatalf("audio = %+v", info.Audio)
	}
	raw, err := Encode(info)
	if err != nil {
		t.Fatal(err)
	}
	back, err := Parse(string(raw))
	if err != nil || len(back.Audio) != 3 || back.Audio[0].Commentary || !back.Audio[1].Commentary || !back.Audio[2].Descriptive {
		t.Errorf("parsed back = %+v, %v", back.Audio, err)
	}
	api, _ := json.Marshal(info)
	if strings.Contains(string(api), "ommentary") || strings.Contains(string(api), "escriptive") {
		t.Errorf("the API's probe shows playback's fields: %s", api)
	}
}

// fakeProbe is a stand-in ffprobe running script.
func fakeProbe(t *testing.T, script string) string {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("needs a shell")
	}
	bin := filepath.Join(t.TempDir(), "ffprobe")
	if err := os.WriteFile(bin, []byte("#!/bin/sh\n"+script+"\n"), 0o700); err != nil { //nolint:gosec // G306: a script, which must be executable
		t.Fatal(err)
	}
	return bin
}

// The Prober's own timeout, the caller giving up and ffprobe refusing the
// file are told apart, and each keeps its cause.
func TestProbeErrors(t *testing.T) {
	t.Parallel()
	slow := &Prober{Path: fakeProbe(t, "exec sleep 30"), Timeout: 100 * time.Millisecond}
	_, err := slow.Probe(context.Background(), "/m/a.mkv")
	if !errors.Is(err, context.DeadlineExceeded) || !strings.Contains(err.Error(), "timed out after 100ms") {
		t.Errorf("timeout: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(50 * time.Millisecond); cancel() }()
	slow.Timeout = time.Minute
	if _, err := slow.Probe(ctx, "/m/a.mkv"); !errors.Is(err, context.Canceled) || strings.Contains(err.Error(), "timed out") {
		t.Errorf("cancelled: %v", err)
	}
	bad := &Prober{Path: fakeProbe(t, "echo '/m/a.mkv: Invalid data found when processing input' >&2; echo second >&2; exit 1")}
	_, err = bad.Probe(context.Background(), "/m/a.mkv")
	var exit *ffexec.ExitError
	if err == nil || err.Error() != "ffprobe: /m/a.mkv: Invalid data found when processing input" || !errors.As(err, &exit) {
		t.Errorf("refused: %v", err)
	}
}

// The packet scan finds every keyframe, in file time.
func TestKeyframes(t *testing.T) {
	t.Parallel()
	src := sampleFile(t)
	out := filepath.Join(t.TempDir(), "keyed.mkv")
	if b, err := exec.Command("ffmpeg", "-v", "error", "-y", "-i", src, "-c:v", "mpeg4", "-g", "1000", "-force_key_frames", "0,0.5", "-an", out).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg: %v\n%s", err, b)
	}
	keys, err := (&Prober{}).Keyframes(context.Background(), out)
	if err != nil || len(keys) != 2 || keys[0] != 0 || keys[1] < 400_000 || keys[1] > 600_000 {
		t.Errorf("keyframes = %v, %v", keys, err)
	}
	if _, err := (&Prober{Path: "/nonexistent/ffprobe"}).Keyframes(context.Background(), out); err == nil {
		t.Error("no error without ffprobe")
	}
	bad := &Prober{Path: fakeProbe(t, "echo 'bad file' >&2; exit 1")}
	if _, err := bad.Keyframes(context.Background(), out); err == nil || !strings.Contains(err.Error(), "bad file") {
		t.Errorf("refused: %v", err)
	}
}

// The DOVI record's compatibility ID and level and the codec tag come from
// the scan's one ffprobe run.
func TestReduceDolbyVisionRecord(t *testing.T) {
	t.Parallel()
	var raw rawOutput
	raw.Format.FormatName = "mov,mp4,m4a,3gp,3g2,mj2"
	raw.Streams = []rawStream{
		{Index: 0, CodecType: "video", CodecName: "hevc", CodecTagString: "dvh1", Width: 3840, Height: 2160,
			SideDataList: []map[string]any{{"side_data_type": "DOVI configuration record", "dv_profile": 5.0, "dv_level": 6.0, "dv_bl_signal_compatibility_id": 0.0}}},
		{Index: 1, CodecType: "video", CodecName: "hevc", CodecTagString: "[0][0][0][0]", ColorTransfer: "arib-std-b67",
			SideDataList: []map[string]any{{"side_data_type": "DOVI configuration record", "dv_profile": 8.0, "dv_level": 9.0, "dv_bl_signal_compatibility_id": 4.0}}},
		{Index: 2, CodecType: "video", CodecName: "hevc", CodecTagString: "HVC1", ColorTransfer: "smpte2084"},
	}
	info := reduce(raw)
	want := []VideoStream{
		{Index: 0, Codec: "hevc", Width: 3840, Height: 2160, HDR: "dolbyvision", DVProfile: 5, DVCompat: 0, DVLevel: 6, CodecTag: "dvh1", Detailed: true},
		{Index: 1, Codec: "hevc", HDR: "dolbyvision", DVProfile: 8, DVCompat: 4, DVLevel: 9, CodecTag: "", Detailed: true},
		{Index: 2, Codec: "hevc", HDR: "hdr10", CodecTag: "hvc1", Detailed: true},
	}
	for i, w := range want {
		if info.Video[i] != w {
			t.Errorf("video %d = %+v, want %+v", i, info.Video[i], w)
		}
	}
	// The stored probe keeps them; the API's JSON does not show them.
	stored, err := Encode(info)
	if err != nil {
		t.Fatal(err)
	}
	back, err := Parse(string(stored))
	if err != nil {
		t.Fatal(err)
	}
	for i, w := range want {
		if back.Video[i] != w {
			t.Errorf("parsed video %d = %+v, want %+v", i, back.Video[i], w)
		}
	}
	api, _ := json.Marshal(info)
	if strings.Contains(string(api), "dvCompat") || strings.Contains(string(api), "codecTag") || strings.Contains(string(api), "etailed") {
		t.Errorf("the API's probe shows playback's fields: %s", api)
	}
	// Probes stored before the record was kept parse as not detailed, so
	// playback falls back on the profile alone and the scanner probes the
	// file again.
	for _, old := range []string{string(api), `{"container":"matroska","video":[{"index":0,"codec":"hevc","hdr":"dolbyvision","dvProfile":8,"default":true}],"extra":{"startUs":0,"chapters":[]}}`} {
		p, err := Parse(old)
		if err != nil || p.Video[0].Detailed || p.Video[0].DVCompat != 0 || p.Video[0].CodecTag != "" {
			t.Errorf("old probe %s = %+v, %v", old, p.Video[0], err)
		}
	}
}

// ffprobe reads a real file's record and sample entry, in MP4 and MKV.
func TestProbeDolbyVisionClip(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	for _, c := range []struct {
		dv  probetest.DolbyVision
		tag string
	}{
		{probetest.DolbyVision{Profile: 8, Level: 6, Compat: 4}, "hvc1"},
		{probetest.DolbyVision{Profile: 5, Level: 9, Compat: 0, Entry: "dvh1"}, "dvh1"},
		{probetest.DolbyVision{Profile: 8, Level: 6, Compat: 1, MKV: true}, ""},
	} {
		file := probetest.DolbyVisionClip(t, ffmpeg, c.dv)
		info, err := (&Prober{}).Probe(context.Background(), file)
		if err != nil {
			t.Fatal(err)
		}
		v := info.Video[0]
		if v.HDR != "dolbyvision" || v.DVProfile != c.dv.Profile || v.DVLevel != c.dv.Level || v.DVCompat != c.dv.Compat || v.CodecTag != c.tag || !v.Detailed || v.BitDepth != 10 {
			t.Errorf("%+v: video = %+v", c.dv, v)
		}
	}
}
