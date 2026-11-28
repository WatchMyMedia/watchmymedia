package livetv

import (
	"bytes"
	"context"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
)

// The viewer's sound settings convert the broadcast's sound, even where it
// would have been copied, and run the chain ahead of the resampler.
func TestLiveAudioWithEffects(t *testing.T) {
	t.Parallel()
	both := []string{"mpegts", "fmp4"}
	boost := playback.AudioEffects{Dialogue: playback.EffectMedium}
	source := atscSource
	source.AudioLayout = "5.1(side)"
	progressive := source
	progressive.VideoCodec, progressive.Interlaced, progressive.Width, progressive.Height, progressive.Bitrate = "h264", false, 854, 480, 1_200_000
	for _, tc := range []struct {
		name    string
		profile *LiveProfile
		source  SourceInfo
		quality string
		mode    string
		audio   string
	}{
		{"copied as it is becomes audio mode", appLiveProfile(both, 6), progressive, "480", "audio",
			"-c:a aac -ac 6 -b:a 384k -af pan=5.1(side)|c0=0.79*c0|c1=0.79*c1|c2=1.26*c2|c3=0.79*c3|c4=0.79*c4|c5=0.79*c5,alimiter=limit=-1dB:level=false,aresample=async=1:first_pts=0"},
		{"video mode no longer copies the sound", appLiveProfile(both, 8), source, "1080", "video",
			"-c:a aac -ac 6 -b:a 384k -af pan=5.1(side)|c0=0.79*c0|c1=0.79*c1|c2=1.26*c2|c3=0.79*c3|c4=0.79*c4|c5=0.79*c5,alimiter=limit=-1dB:level=false,aresample=async=1:first_pts=0"},
		{"web gets stereo with more of the centre", webLiveProfile(), source, "1080", "video",
			"-c:a aac -ac 2 -b:a 192k -af pan=stereo|c0=0.25*c0+0.55*c2+0.2*c4|c1=0.25*c1+0.55*c2+0.2*c5,alimiter=limit=-1dB:level=false,aresample=async=1:first_pts=0"},
		{"an app with no profile", nil, SourceInfo{}, "1080", "video",
			"-c:a aac -ac 2 -b:a 192k -af aformat=channel_layouts=stereo,dialoguenhance=original=1:enhance=2:voice=8,pan=stereo|c0=0.4*c0+0.6*c2|c1=0.4*c1+0.6*c2,alimiter=limit=-1dB:level=false,aresample=async=1:first_pts=0"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			plan := chooseLivePlan(tc.profile, tc.source, tc.quality).withEffects(boost, playback.AudioFilters{}, tc.profile, tc.source)
			if plan.Mode != tc.mode {
				t.Fatalf("plan %+v, want %s", plan, tc.mode)
			}
			step := playback.StepByID(tc.quality)
			args := HLSArgsForPlan(t.TempDir(), plan, "libx264", step.MaxHeight, int(step.MaxBitrate/1000), nil)
			if got := liveAudioOf(t, args); got != tc.audio {
				t.Fatalf("audio\n got %q\nwant %q", got, tc.audio)
			}
		})
	}
	// Both off: as before.
	plan := chooseLivePlan(appLiveProfile(both, 6), progressive, "480").withEffects(playback.AudioEffects{}, playback.AudioFilters{}, appLiveProfile(both, 6), progressive)
	if plan.Mode != "original" {
		t.Errorf("both off: %+v", plan)
	}
	// An ffmpeg without a limiter applies neither, and copies as before.
	plan = chooseLivePlan(appLiveProfile(both, 6), progressive, "480").withEffects(boost, playback.AudioFilters{NoLimiter: true}, appLiveProfile(both, 6), progressive)
	if plan.Mode != "original" {
		t.Errorf("no limiter: %+v", plan)
	}
	// The adaptive family's common audio carries them too.
	family := &liveFamily{rungs: [2]liveRung{{"low", 426, 240, 400_000}, {"high", 854, 480, 1_200_000}}}
	family.sound = LivePlan{Mode: "video"}.withEffects(boost, playback.AudioFilters{}, appLiveProfile(both, 2), source)
	if got := strings.Join(family.args(t.TempDir()), " "); !strings.Contains(got, "-af pan=stereo|c0=0.25*c0+0.55*c2+0.2*c4|c1=0.25*c1+0.55*c2+0.2*c5,alimiter=limit=-1dB:level=false,aresample=async=1:first_pts=0") {
		t.Errorf("family args %q", got)
	}
}

// Viewers with different sound settings never share an output; viewers
// with the same ones still do.
func TestSharedOutputKeySplitsByEffects(t *testing.T) {
	t.Parallel()
	profile := &LiveProfile{SegmentFormats: []string{"fmp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}
	viewer := func(e playback.AudioEffects) *session {
		return &session{outputKind: "single", view: SessionView{Quality: "720"}, profile: profile, effects: e}
	}
	plain := liveOutputKey("capture", 1, viewer(playback.AudioEffects{}))
	medium := liveOutputKey("capture", 1, viewer(playback.AudioEffects{Dialogue: playback.EffectMedium}))
	high := liveOutputKey("capture", 1, viewer(playback.AudioEffects{Dialogue: playback.EffectHigh}))
	loud := liveOutputKey("capture", 1, viewer(playback.AudioEffects{Loudness: playback.EffectMedium}))
	if plain == medium || medium == high || medium == loud || plain == loud {
		t.Fatal("viewers with different sound settings share an output")
	}
	if again := liveOutputKey("capture", 1, viewer(playback.AudioEffects{Dialogue: playback.EffectMedium})); again != medium {
		t.Fatal("viewers with the same sound settings no longer share an output")
	}
}

// The chain runs in ffmpeg on a broadcast's AC-3, read with its layout by
// the inspection, into each segment format.
func TestLiveEffectsRunInFFmpeg(t *testing.T) {
	t.Parallel()
	liveTransportSample(t, "ac3") // skips without ffmpeg and ffprobe
	ffmpeg, _ := exec.LookPath("ffmpeg")
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	// A broadcast's AC-3 5.1, as ATSC 1.0 channels send it.
	media, err := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
		"-t", "3", "-c:v", "libx264", "-threads", "1", "-g", "30", "-ac", "6", "-c:a", "ac3", "-f", "mpegts", "pipe:1")...).Output()
	if err != nil {
		t.Fatal(err)
	}
	source, err := InspectSample(t.Context(), "ffprobe", media, 2_000_000)
	if err != nil {
		t.Fatal(err)
	}
	if source.AudioChannels != 6 || !strings.HasPrefix(source.AudioLayout, "5.1") {
		t.Fatalf("inspection read %d channels, layout %q", source.AudioChannels, source.AudioLayout)
	}
	filters := playback.Detect(t.Context(), ffmpeg, "software", slog.New(slog.DiscardHandler)).AudioFilters()
	if filters != (playback.AudioFilters{}) {
		t.Skipf("ffmpeg lacks the sound filters: %+v", filters)
	}
	for _, format := range []string{"mpegts", "fmp4"} {
		profile := &LiveProfile{SegmentFormats: []string{format}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac", "ac3"}, MaxHeight: 1080, MaxBitrate: 8_000_000}
		profile.MaxAudioChannels = 6
		plan := chooseLivePlan(profile, source, "1080").withEffects(playback.AudioEffects{Dialogue: playback.EffectHigh, Loudness: playback.EffectHigh}, filters, profile, source)
		if plan.Mode != "audio" || plan.audioChannels != 6 {
			t.Fatalf("%s: %+v", format, plan)
		}
		dir := t.TempDir()
		ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
		cmd := ffexec.Command(ctx, ffmpeg, HLSArgsForPlan(dir, plan, "", 0, 0, nil)...)
		cmd.Stdin = bytes.NewReader(media)
		err := cmd.Run()
		cancel()
		if err != nil {
			t.Fatalf("%s: %v (%s)", format, err, cmd.Tail())
		}
		if _, err := os.Stat(filepath.Join(dir, "live.m3u8")); err != nil {
			t.Fatalf("%s: %v", format, err)
		}
	}
}
