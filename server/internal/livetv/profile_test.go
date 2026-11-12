package livetv

import (
	"bytes"
	"context"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
)

func TestLivePlanUsesCopyOnlyForInspectedCompatibleSource(t *testing.T) {
	t.Parallel()
	source := SourceInfo{VideoCodec: "h264", AudioCodec: "aac", AudioPresent: true, AudioChannels: 2,
		Width: 1280, Height: 720, Bitrate: 3_000_000, ScanKnown: true}
	ts := &LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxHeight: 1080, MaxBitrate: 8_000_000}
	if got := chooseLivePlan(ts, source, "1080"); got.Mode != "original" || got.SegmentFormat != "mpegts" {
		t.Fatalf("compatible TS: %+v", got)
	}
	fmp4 := *ts
	fmp4.SegmentFormats = []string{"fmp4"}
	if got := chooseLivePlan(&fmp4, source, "1080"); got.Mode != "remux" || got.SegmentFormat != "fmp4" {
		t.Fatalf("compatible fMP4: %+v", got)
	}
	source.AudioCodec = "ac3"
	if got := chooseLivePlan(&fmp4, source, "1080"); got.Mode != "audio" || got.Reason != "audio_unsupported" {
		t.Fatalf("audio-only conversion: %+v", got)
	}
	source.AudioCodec = "aac"
	source.Interlaced = true
	if got := chooseLivePlan(ts, source, "1080"); got.Mode != "video" || got.Reason != "deinterlace_required" {
		t.Fatalf("interlaced fallback: %+v", got)
	}
	source.Interlaced = false
	source.HDRType = "hdr10"
	if got := chooseLivePlan(ts, source, "1080"); got.Mode != "video" || got.Reason != "hdr_unsupported" || got.hdrType != "hdr10" ||
		!strings.Contains(strings.Join(HLSArgsForPlan(t.TempDir(), got, "libx264", 1080, 8000, nil), " "), "tonemap=") {
		t.Fatalf("HDR fallback: %+v", got)
	}
	source.HDRType = ""
	if got := chooseLivePlan(ts, source, "480"); got.Mode != "video" {
		t.Fatalf("quality cap: %+v", got)
	}
	source.Bitrate = 0
	if got := chooseLivePlan(ts, source, "1080"); got.Mode != "video" || got.Reason != "source_unknown" {
		t.Fatalf("unknown source: %+v", got)
	}
	source.Bitrate = 3_000_000
	source.AudioPresent = false
	if got := chooseLivePlan(ts, source, "1080"); got.Mode != "video" || got.Reason != "source_unknown" {
		t.Fatalf("unproven audio map: %+v", got)
	}
	if got := chooseLivePlan(nil, source, "1080"); got.Mode != "video" || got.Reason != "legacy_client" {
		t.Fatalf("old client: %+v", got)
	}
	if err := validateLiveProfile(&LiveProfile{SegmentFormats: []string{"unknown"}}); err == nil {
		t.Fatal("unsupported live segment format was accepted")
	}
}

func TestLiveAutomaticAdmissionUsesCopyDemandWhenProven(t *testing.T) {
	t.Parallel()
	source := SourceInfo{VideoCodec: "h264", AudioCodec: "aac", AudioPresent: true, AudioChannels: 2,
		Width: 1280, Height: 720, Bitrate: 3_000_000, ScanKnown: true}
	profile := &LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"h264"},
		AudioCodecs: []string{"aac"}, MaxHeight: 1080, MaxBitrate: 8_000_000}
	if got := liveDemand("1080", profile, source); got != 4_375_000 {
		t.Fatalf("copy demand %d", got)
	}
	source.VideoCodec = "mpeg2video"
	if got := liveDemand("1080", profile, source); got != 10_625_000 {
		t.Fatalf("conversion demand %d", got)
	}
}

func liveTransportSample(t *testing.T, audio string) []byte {
	t.Helper()
	for _, name := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(name); err != nil {
			t.Skip(name + " is unavailable")
		}
	}
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	args := ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
		"-t", "3", "-c:v", "libx264", "-threads", "1", "-g", "30", "-c:a", audio, "-f", "mpegts", "pipe:1")
	media, err := ffexec.Command(ctx, "ffmpeg", args...).Output()
	if err != nil {
		t.Fatal(err)
	}
	return media
}

func TestInspectTransportSampleAndRemuxWithoutSecondInput(t *testing.T) {
	t.Parallel()
	media := liveTransportSample(t, "aac")
	info, err := InspectSample(t.Context(), "ffprobe", media, 2_000_000)
	if err != nil {
		t.Fatal(err)
	}
	if info.VideoCodec != "h264" || info.AudioCodec != "aac" || info.Width != 320 || info.Height != 180 || !info.ScanKnown || info.Interlaced {
		t.Fatalf("inspected source: %+v", info)
	}
	for _, plan := range []LivePlan{{Mode: "original", SegmentFormat: "mpegts"}, {Mode: "remux", SegmentFormat: "fmp4", sourceAudio: "aac"}} {
		dir := t.TempDir()
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		cmd := ffexec.Command(ctx, "ffmpeg", HLSArgsForPlan(dir, plan, "", 0, 0, nil)...)
		cmd.Stdin = bytes.NewReader(media)
		err := cmd.Run()
		cancel()
		if err != nil {
			t.Fatalf("%s output: %v (%s)", plan.Mode, err, cmd.Tail())
		}
		playlist, err := os.ReadFile(filepath.Join(dir, "live.m3u8"))
		if err != nil || !bytes.Contains(playlist, []byte("#EXTINF:")) {
			t.Fatalf("%s playlist: %s %v", plan.Mode, playlist, err)
		}
		if plan.SegmentFormat == "fmp4" {
			if !strings.Contains(string(playlist), "init.mp4") || !strings.Contains(string(playlist), ".m4s") {
				t.Fatalf("fMP4 map and segment missing: %s", playlist)
			}
			if _, err := os.Stat(filepath.Join(dir, "init.mp4")); err != nil {
				t.Fatal(err)
			}
		} else if !strings.Contains(string(playlist), ".ts") {
			t.Fatalf("TS segment missing: %s", playlist)
		}
	}
}

func TestUnsharedTunerInspectionReplaysEveryByte(t *testing.T) {
	t.Parallel()
	media := liveTransportSample(t, "aac")
	r := newInspectedTuner(io.NopCloser(bytes.NewReader(media)))
	defer r.Close()
	_, _ = r.Inspect(t.Context(), "ffprobe")
	got, err := io.ReadAll(r)
	if err != nil || !bytes.Equal(got, media) {
		t.Fatalf("inspection changed source bytes: got %d, want %d, err %v", len(got), len(media), err)
	}
}

func TestLiveAudioOnlyConversionProducesFMP4(t *testing.T) {
	t.Parallel()
	media := liveTransportSample(t, "ac3")
	source, err := InspectSample(t.Context(), "ffprobe", media, 2_000_000)
	if err != nil {
		t.Fatal(err)
	}
	profile := &LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxHeight: 1080, MaxBitrate: 8_000_000}
	plan := chooseLivePlan(profile, source, "1080")
	if plan.Mode != "audio" || plan.SegmentFormat != "fmp4" {
		t.Fatalf("unexpected plan: %+v", plan)
	}
	dir := t.TempDir()
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	cmd := ffexec.Command(ctx, "ffmpeg", HLSArgsForPlan(dir, plan, "", 0, 0, nil)...)
	cmd.Stdin = bytes.NewReader(media)
	if err := cmd.Run(); err != nil {
		t.Fatalf("audio-only HLS: %v (%s)", err, cmd.Tail())
	}
	if _, err := os.Stat(filepath.Join(dir, "init.mp4")); err != nil {
		t.Fatal(err)
	}
}

func TestLiveFMP4RemuxPreservesCompatibleAC3(t *testing.T) {
	t.Parallel()
	media := liveTransportSample(t, "ac3")
	source, err := InspectSample(t.Context(), "ffprobe", media, 2_000_000)
	if err != nil {
		t.Fatal(err)
	}
	profile := &LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"ac3"}, MaxHeight: 1080, MaxBitrate: 8_000_000}
	plan := chooseLivePlan(profile, source, "1080")
	if plan.Mode != "remux" {
		t.Fatalf("compatible AC-3 was converted: %+v", plan)
	}
	dir := t.TempDir()
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	cmd := ffexec.Command(ctx, "ffmpeg", HLSArgsForPlan(dir, plan, "", 0, 0, nil)...)
	cmd.Stdin = bytes.NewReader(media)
	if err := cmd.Run(); err != nil {
		t.Fatalf("AC-3 remux: %v (%s)", err, cmd.Tail())
	}
	if _, err := os.Stat(filepath.Join(dir, "init.mp4")); err != nil {
		t.Fatal(err)
	}
}

func TestLiveHardwareRequiresActualLivePipelineForNegotiatingClient(t *testing.T) {
	t.Parallel()
	caps := playback.Capabilities{Encoder: "h264_videotoolbox"}
	if got := selectLiveEncoder(t.Context(), "/missing/ffmpeg", caps, true); got != "libx264" {
		t.Fatalf("failed live hardware probe: %s", got)
	}
	if got := selectLiveEncoder(t.Context(), "/missing/ffmpeg", caps, false); got != "h264_videotoolbox" {
		t.Fatalf("legacy encoder changed: %s", got)
	}
	if got := selectLiveEncoder(t.Context(), "ffmpeg", playback.Capabilities{Encoder: "h264_qsv"}, true); got != "libx264" {
		t.Fatalf("unsupported live upload path: %s", got)
	}
}

func TestLivePipelineProbeUsesInterlacedSourceAndPublishesHLS(t *testing.T) {
	t.Parallel()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is unavailable")
	}
	elapsed, err := probeLivePipeline(t.Context(), "ffmpeg", "libx264", nil)
	if err != nil || elapsed <= 0 {
		t.Fatalf("live CPU pipeline: elapsed %s, err %v", elapsed, err)
	}
}

func TestConcurrentLiveProbeWaitersShareOneResult(t *testing.T) {
	t.Parallel()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is unavailable")
	}
	caps := playback.Capabilities{Encoder: "libx264", Detected: time.Now()}
	results := make([]bool, 8)
	var wg sync.WaitGroup
	for i := range results {
		wg.Go(func() { results[i] = liveEncoderWorks(t.Context(), "ffmpeg", caps) })
	}
	wg.Wait()
	for _, result := range results[1:] {
		if result != results[0] {
			t.Fatalf("concurrent live probes disagreed: %v", results)
		}
	}
}
