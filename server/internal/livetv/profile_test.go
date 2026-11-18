package livetv

import (
	"bytes"
	"context"
	"encoding/json"
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

// appLiveProfile is what the Apple and Android apps send for Live TV
// (RealLiveTVRepository.swift, RealLiveTV.kt): both segment formats unless a
// case narrows them, and the output's channels.
func appLiveProfile(formats []string, channels int) *LiveProfile {
	return &LiveProfile{SegmentFormats: formats, VideoCodecs: []string{"h264", "hevc"}, AudioCodecs: []string{"aac", "ac3", "eac3"}, MaxAudioChannels: channels}
}

// webLiveProfile is web/src/lib/liveTV.ts's compatibleLiveProfile, which the
// web app and the Cast receiver send.
func webLiveProfile() *LiveProfile {
	return &LiveProfile{SegmentFormats: []string{"mpegts"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxHeight: 1080}
}

// atscSource is an ATSC 1.0 channel: interlaced MPEG-2 with AC-3 5.1.
var atscSource = SourceInfo{VideoCodec: "mpeg2video", AudioCodec: "ac3", AudioPresent: true, AudioChannels: 6,
	Width: 1920, Height: 1080, Bitrate: 12_000_000, ScanKnown: true, Interlaced: true}

// liveAudioOf is the audio part of a live ffmpeg command, which comes just
// before the HLS output in every mode.
func liveAudioOf(t *testing.T, args []string) string {
	t.Helper()
	joined := strings.Join(args, " ")
	start := strings.Index(joined, " -c:a ")
	end := strings.Index(joined, " -f hls ")
	if start < 0 || end < start {
		t.Fatalf("no audio arguments: %s", joined)
	}
	return joined[start+1 : end]
}

func TestLiveAudioKeepsWhatTheDeviceTakes(t *testing.T) {
	t.Parallel()
	const (
		copied   = "-c:a copy"
		stereo   = "-c:a aac -ac 2 -b:a 192k -af aresample=async=1:first_pts=0"
		surround = "-c:a aac -ac 6 -b:a 384k -af aresample=async=1:first_pts=0"
	)
	both, fmp4 := []string{"mpegts", "fmp4"}, []string{"fmp4"}
	aacOnly := func(channels int) *LiveProfile {
		p := appLiveProfile(both, channels)
		p.AudioCodecs = []string{"aac"}
		return p
	}
	with := func(change func(*SourceInfo)) SourceInfo {
		s := atscSource
		change(&s)
		return s
	}
	// An H.264 channel whose picture the apps play as it is.
	progressive := func(s *SourceInfo) {
		s.VideoCodec, s.Interlaced, s.Width, s.Height, s.Bitrate = "h264", false, 854, 480, 1_200_000
	}
	for _, tc := range []struct {
		name    string
		profile *LiveProfile
		source  SourceInfo
		quality string
		mode    string
		format  string
		audio   string
	}{
		// Video mode: the picture is converted, the audio kept where it can be.
		{"app on a receiver, MPEG-TS", appLiveProfile(both, 8), atscSource, "1080", "video", "mpegts", copied},
		{"app on 5.1, fMP4", appLiveProfile(fmp4, 6), atscSource, "1080", "video", "fmp4", copied},
		{"app at 480p still copies", appLiveProfile(both, 6), atscSource, "480", "video", "mpegts", copied},
		{"E-AC-3 broadcast", appLiveProfile(both, 8), with(func(s *SourceInfo) { s.AudioCodec = "eac3" }), "1080", "video", "mpegts", copied},
		{"AAC into fMP4", appLiveProfile(fmp4, 6), with(func(s *SourceInfo) { s.AudioCodec, s.AudioChannels = "aac", 2 }), "1080", "video", "fmp4", copied + " -bsf:a aac_adtstoasc"},
		{"app on TV speakers", appLiveProfile(both, 2), atscSource, "1080", "video", "mpegts", stereo},
		{"app sends no channel limit", appLiveProfile(both, 0), atscSource, "1080", "video", "mpegts", copied},
		{"web and Cast receiver", webLiveProfile(), atscSource, "1080", "video", "mpegts", stereo},
		{"AAC-only app at 1080p", aacOnly(6), atscSource, "1080", "video", "mpegts", surround},
		{"AAC-only app at 720p", aacOnly(8), atscSource, "720", "video", "mpegts", surround},
		{"AAC-only app at 480p", aacOnly(6), atscSource, "480", "video", "mpegts", stereo},
		{"7.1 to a 5.1 output", appLiveProfile(both, 6), with(func(s *SourceInfo) { s.AudioCodec, s.AudioChannels = "eac3", 8 }), "1080", "video", "mpegts", surround},
		{"stereo broadcast", aacOnly(6), with(func(s *SourceInfo) { s.AudioChannels = 2 }), "1080", "video", "mpegts", stereo},
		{"fMP4 can't carry MP2", &LiveProfile{SegmentFormats: fmp4, AudioCodecs: []string{"mp2"}, MaxAudioChannels: 6}, with(func(s *SourceInfo) { s.AudioCodec, s.AudioChannels = "mp2", 2 }), "1080", "video", "fmp4", stereo},
		{"source unknown", appLiveProfile(both, 8), with(func(s *SourceInfo) { s.Bitrate = 0 }), "1080", "video", "mpegts", stereo},
		{"no profile", nil, atscSource, "1080", "video", "mpegts", stereo},
		// Audio mode: the picture is copied, so the rate doesn't hold surround back.
		{"AAC-only app, audio only", aacOnly(6), with(progressive), "480", "audio", "mpegts", surround},
		{"web, audio only", webLiveProfile(), with(progressive), "480", "audio", "mpegts", stereo},
		{"app, copied as it is", appLiveProfile(both, 6), with(progressive), "480", "original", "mpegts", copied},
		{"app, remuxed", appLiveProfile(fmp4, 6), with(progressive), "480", "remux", "fmp4", copied},
	} {
		t.Run(tc.name, func(t *testing.T) {
			plan := chooseLivePlan(tc.profile, tc.source, tc.quality)
			if plan.Mode != tc.mode || plan.SegmentFormat != tc.format {
				t.Fatalf("plan %+v, want %s in %s", plan, tc.mode, tc.format)
			}
			step := playback.StepByID(tc.quality)
			args := HLSArgsForPlan(t.TempDir(), plan, "libx264", step.MaxHeight, int(step.MaxBitrate/1000), nil)
			if got := liveAudioOf(t, args); got != tc.audio {
				t.Fatalf("audio %q, want %q", got, tc.audio)
			}
		})
	}
	// A picture converted for a reason of its own says so, whatever the audio.
	if got := chooseLivePlan(appLiveProfile(both, 8), atscSource, "1080"); got.Reason != "deinterlace_required" {
		t.Fatalf("video mode's reason: %+v", got)
	}
}

func TestLiveLadderStaysStereo(t *testing.T) {
	t.Parallel()
	source := SourceInfo{VideoCodec: "mpeg2video", AudioCodec: "ac3", AudioPresent: true, AudioChannels: 6,
		Width: 1280, Height: 720, Bitrate: 8_000_000, ScanKnown: true, SquarePixels: true}
	family, err := newLiveFamily(source, appLiveProfile([]string{"fmp4"}, 8), "1080")
	if err != nil {
		t.Fatal(err)
	}
	if got := liveAudioOf(t, family.args(t.TempDir())); got != "-c:a aac -ac 2 -ar 48000 -b:a 192k -af aresample=async=1:first_pts=0" {
		t.Fatalf("ladder audio changed: %s", got)
	}
	// The legacy and probe paths have no plan, and stay stereo too.
	if got := liveAudioOf(t, HLSArgs(t.TempDir(), "libx264", 1080, 8000)); got != "-c:a aac -ac 2 -b:a 192k -af aresample=async=1:first_pts=0" {
		t.Fatalf("legacy audio changed: %s", got)
	}
}

// atscTransportSample is a short ATSC 1.0 broadcast: interlaced MPEG-2 with
// AC-3 5.1.
func atscTransportSample(t *testing.T) []byte {
	t.Helper()
	for _, name := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(name); err != nil {
			t.Skip(name + " is unavailable")
		}
	}
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	args := ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
		"-t", "3", "-c:v", "mpeg2video", "-threads", "1", "-flags", "+ilme+ildct", "-top", "1", "-c:a", "ac3", "-ac", "6", "-b:a", "384k", "-f", "mpegts", "pipe:1")
	media, err := ffexec.Command(ctx, "ffmpeg", args...).Output()
	if err != nil {
		t.Fatal(err)
	}
	return media
}

func TestLiveVideoModeWritesTheChosenAudio(t *testing.T) {
	t.Parallel()
	media := atscTransportSample(t)
	source, err := InspectSample(t.Context(), "ffprobe", media, 0)
	if err != nil {
		t.Fatal(err)
	}
	if source.AudioCodec != "ac3" || source.AudioChannels != 6 || !source.Interlaced {
		t.Fatalf("inspected source: %+v", source)
	}
	aacOnly := appLiveProfile([]string{"mpegts", "fmp4"}, 6)
	aacOnly.AudioCodecs = []string{"aac"}
	for _, tc := range []struct {
		name     string
		profile  *LiveProfile
		codec    string
		channels int
	}{
		{"app, MPEG-TS", appLiveProfile([]string{"mpegts", "fmp4"}, 8), "ac3", 6},
		{"app, fMP4", appLiveProfile([]string{"fmp4"}, 6), "ac3", 6},
		{"AAC-only app", aacOnly, "aac", 6},
		{"web", webLiveProfile(), "aac", 2},
	} {
		t.Run(tc.name, func(t *testing.T) {
			plan := chooseLivePlan(tc.profile, source, "1080")
			if plan.Mode != "video" {
				t.Fatalf("plan %+v", plan)
			}
			ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
			defer cancel()
			dir := t.TempDir()
			cmd := ffexec.Command(ctx, "ffmpeg", HLSArgsForPlan(dir, plan, "libx264", 1080, 8000, nil)...)
			cmd.Stdin = bytes.NewReader(media)
			if err := cmd.Run(); err != nil {
				t.Fatalf("live output: %v (%s)", err, cmd.Tail())
			}
			output := filepath.Join(dir, "segment-000000000.ts")
			if plan.SegmentFormat == "fmp4" {
				// The init segment carries the sample entry; probe it with
				// the first fragment, as a player reads them.
				var joined []byte
				for _, name := range []string{"init.mp4", "segment-000000000.m4s"} {
					b, err := os.ReadFile(filepath.Join(dir, name))
					if err != nil {
						t.Fatal(err)
					}
					joined = append(joined, b...)
				}
				output = filepath.Join(dir, "joined.mp4")
				if err := os.WriteFile(output, joined, 0o600); err != nil {
					t.Fatal(err)
				}
			}
			out, err := ffexec.Command(ctx, "ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,channels", "-of", "json", output).Output()
			if err != nil {
				t.Fatal(err)
			}
			var probe struct {
				Streams []struct {
					CodecName string `json:"codec_name"`
					Channels  int    `json:"channels"`
				}
			}
			if err := json.Unmarshal(out, &probe); err != nil {
				t.Fatal(err)
			}
			if len(probe.Streams) != 1 || probe.Streams[0].CodecName != tc.codec || probe.Streams[0].Channels != tc.channels {
				t.Fatalf("audio written: %s, want %s with %d channels", out, tc.codec, tc.channels)
			}
		})
	}
}
