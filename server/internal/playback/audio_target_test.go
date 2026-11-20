package playback

import (
	"context"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

// The profiles, as the apps build them.
var (
	androidTVTrueHD = Profile{Name: "android", Containers: []string{"mkv", "mp4", "webm", "mpegts"}, VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "mp3", "opus", "flac", "vorbis", "ac3", "eac3", "dts", "truehd", "pcm"}, MaxAudioChannels: 8}
	// A soundbar on ARC: Dolby as a bitstream, two channels of PCM.
	androidTVARC = Profile{Name: "android", Containers: []string{"mkv", "mp4", "webm", "mpegts"}, VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "mp3", "opus", "flac", "vorbis", "ac3", "eac3", "pcm"}, MaxAudioChannels: 8, MaxPcmChannels: 2}
	// Audio passthrough Off on a receiver that takes eight channels of PCM.
	androidTVDecodes = Profile{Name: "android", Containers: []string{"mkv", "mp4", "webm", "mpegts"}, VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "mp3", "opus", "flac", "vorbis", "ac3", "eac3", "pcm"}, MaxAudioChannels: 8}
	appleTVReceiver = Profile{Name: "tvos", Containers: []string{"mp4", "mov"}, VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "mp3", "flac", "alac", "ac3", "eac3"}, MaxAudioChannels: 8}
	appleTVSpeakers = Profile{Name: "tvos", Containers: []string{"mp4", "mov"}, VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "mp3", "flac", "alac", "ac3", "eac3"}, MaxAudioChannels: 2}
	iPhoneSurround = Profile{Name: "ios", Containers: []string{"mp4", "mov"}, VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "mp3", "flac", "alac", "ac3", "eac3"}, MaxAudioChannels: 6}
	// A Cast TV says two PCM channels: its speakers, or ARC to a soundbar.
	castDolby = Profile{Name: "cast", Containers: []string{"mp4"}, VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "flac", "ac3", "eac3"}, MaxAudioChannels: 8, MaxPcmChannels: 2, SeparateAudio: true}
	castPlain = Profile{Name: "cast", Containers: []string{"mp4"}, VideoCodecs: []string{"h264", "hevc"},
		AudioCodecs: []string{"aac", "flac"}, MaxAudioChannels: 2}
)

func capped(p Profile, bps int64) Profile {
	p.MaxBitrate = bps
	return p
}

func TestAudioTargets(t *testing.T) {
	t.Parallel()
	const flac8 = 8 * 48_000 * 24 * 6 / 10
	for _, c := range []struct {
		name     string
		codec    string
		profile  string // ffprobe's, for DTS
		channels int
		ext      string
		p        Profile
		download bool
		mode     Mode
		want     AudioPlan
	}{
		{"Android TV, receiver takes TrueHD", "truehd", "", 8, ".mkv", androidTVTrueHD, false, ModeDirect, AudioPlan{Index: 1, Codec: "truehd", Copy: true, Channels: 8}},
		{"Android TV on ARC", "truehd", "", 8, ".mkv", androidTVARC, false, ModeAudio, AudioPlan{Index: 1, Codec: "eac3", Channels: 6, Bitrate: 640_000}},
		{"Android TV, passthrough Off", "truehd", "", 8, ".mkv", androidTVDecodes, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 8, Bitrate: flac8}},
		{"Apple TV on a receiver", "truehd", "", 8, ".mkv", appleTVReceiver, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 8, Bitrate: flac8}},
		{"Apple TV on TV speakers", "truehd", "", 8, ".mkv", appleTVSpeakers, false, ModeAudio, AudioPlan{Index: 1, Codec: "aac", Channels: 2, Bitrate: 192_000}},
		{"iPhone with surround", "truehd", "", 8, ".mkv", iPhoneSurround, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 6, Bitrate: 6 * 48_000 * 24 * 6 / 10}},
		{"Chrome", "truehd", "", 8, ".mkv", webProfile(), false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 8, Bitrate: flac8}},
		{"Cast TV with Dolby", "truehd", "", 8, ".mkv", castDolby, false, ModeAudio, AudioPlan{Index: 1, Codec: "eac3", Channels: 6, Bitrate: 640_000}},
		{"Cast TV without Dolby", "truehd", "", 8, ".mkv", castPlain, false, ModeAudio, AudioPlan{Index: 1, Codec: "aac", Channels: 2, Bitrate: 192_000}},
		{"a download never gets FLAC", "truehd", "", 8, ".mkv", appleTVReceiver, true, ModeAudio, AudioPlan{Index: 1, Codec: "eac3", Channels: 6, Bitrate: 640_000}},
		{"a capped play never gets FLAC", "truehd", "", 8, ".mkv", capped(appleTVReceiver, 8_000_000), false, ModeAudio, AudioPlan{Index: 1, Codec: "eac3", Channels: 6, Bitrate: 640_000}},
		{"below the surround floor, stereo", "truehd", "", 8, ".mkv", capped(appleTVReceiver, 2_000_000), false, ModeTranscode, AudioPlan{Index: 1, Codec: "aac", Channels: 2, Bitrate: 192_000}},
		{"DTS-HD MA is lossless", "dts", "DTS-HD MA", 8, ".mkv", appleTVReceiver, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 8, Bitrate: flac8}},
		{"DTS:X's core is lossless too", "dts", "DTS-HD MA + DTS:X", 8, ".mkv", appleTVReceiver, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 8, Bitrate: flac8}},
		{"DTS is not", "dts", "DTS", 6, ".mkv", appleTVReceiver, false, ModeAudio, AudioPlan{Index: 1, Codec: "eac3", Channels: 6, Bitrate: 640_000}},
		{"DTS on Chrome is AAC 5.1", "dts", "DTS", 6, ".mkv", webProfile(), false, ModeAudio, AudioPlan{Index: 1, Codec: "aac", Channels: 6, Bitrate: 384_000}},
		{"PCM plays directly", "pcm_s24le", "", 6, ".mkv", androidTVDecodes, false, ModeDirect, AudioPlan{Index: 1, Codec: "pcm_s24le", Copy: true, Channels: 6}},
		{"PCM in HLS is FLAC", "pcm_s24le", "", 6, ".avi", androidTVDecodes, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 6, Bitrate: 6 * 48_000 * 24 * 6 / 10}},
		{"PCM in MOV is FLAC: ExoPlayer reads no in24", "pcm_s24le", "", 6, ".mov", androidTVDecodes, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 6, Bitrate: 6 * 48_000 * 24 * 6 / 10}},
		{"PCM on Apple TV is FLAC", "pcm_s24le", "", 6, ".mkv", appleTVReceiver, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 6, Bitrate: 6 * 48_000 * 24 * 6 / 10}},
		{"stereo ALAC on Android is FLAC", "alac", "", 2, ".mkv", androidTVARC, false, ModeAudio, AudioPlan{Index: 1, Codec: "flac", Channels: 2, Bitrate: 2 * 48_000 * 24 * 6 / 10}},
		{"surround AAC to stereo", "aac", "", 6, ".mkv", appleTVSpeakers, false, ModeAudio, AudioPlan{Index: 1, Codec: "aac", Channels: 2, Bitrate: 192_000}},
		{"E-AC-3 7.1 to 5.1", "eac3", "", 8, ".mkv", iPhoneSurround, false, ModeAudio, AudioPlan{Index: 1, Codec: "eac3", Channels: 6, Bitrate: 640_000}},
	} {
		info := mkInfo("matroska", "h264", 8, "", c.codec, c.channels)
		info.Audio[0].Profile = c.profile
		d := Decide(info, c.ext, Request{Profile: c.p, Download: c.download}, nil, Capabilities{})
		if d.Mode != c.mode || d.Audio != c.want {
			t.Errorf("%s: %s %+v, want %s %+v (%v)", c.name, d.Mode, d.Audio, c.mode, c.want, d.Reasons)
		}
	}
}

func TestAudioTargetReasons(t *testing.T) {
	t.Parallel()
	info := mkInfo("matroska", "h264", 8, "", "truehd", 8)
	d := Decide(info, ".mkv", Request{Profile: appleTVReceiver}, nil, Capabilities{})
	if !slices.Contains(d.Reasons, "audio converted to FLAC 7.1, which keeps it lossless") {
		t.Errorf("reasons %q", d.Reasons)
	}
	d = Decide(info, ".mkv", Request{Profile: androidTVARC}, nil, Capabilities{})
	if !slices.Contains(d.Reasons, "audio converted to EAC3 5.1, which keeps it surround") {
		t.Errorf("reasons %q", d.Reasons)
	}
	pcm := mkInfo("matroska", "h264", 8, "", "pcm_s16le", 2)
	d = Decide(pcm, ".mp4", Request{Profile: appleTVReceiver}, nil, Capabilities{})
	if !slices.Contains(d.Reasons, "client cannot decode pcm audio") {
		t.Errorf("reasons %q", d.Reasons)
	}
	d = Decide(pcm, ".avi", Request{Profile: androidTVDecodes}, nil, Capabilities{})
	if !slices.Contains(d.Reasons, "PCM audio is copied only in direct play of Matroska") {
		t.Errorf("reasons %q", d.Reasons)
	}
}

// The stream's bitrate counts the converted audio at its own rate.
func TestAudioTargetBitrate(t *testing.T) {
	t.Parallel()
	info := mkInfo("matroska", "h264", 8, "", "truehd", 8)
	info.Video[0].BitRate = 20_000_000
	info.Audio[0].BitRate = 4_000_000
	d := Decide(info, ".mkv", Request{Profile: appleTVReceiver}, nil, Capabilities{})
	if d.Audio.Codec != "flac" || d.Bitrate != 24_000_000 || d.AudioBitrate(info) != 4_000_000 {
		t.Errorf("FLAC at the source's rate: %+v, stream %d", d.Audio, d.Bitrate)
	}
	d = Decide(info, ".mkv", Request{Profile: androidTVARC}, nil, Capabilities{})
	if d.Bitrate != 20_640_000 || d.AudioBitrate(info) != 640_000 {
		t.Errorf("E-AC-3: %+v, stream %d", d.Audio, d.Bitrate)
	}
	d = Decide(info, ".mkv", Request{Profile: androidTVTrueHD}, nil, Capabilities{})
	if !d.Audio.Copy || d.AudioBitrate(info) != 4_000_000 {
		t.Errorf("copied TrueHD: %+v", d.Audio)
	}
	if got := PeakBandwidthWithAudio(1_000_000, 640_000); got != 2_640_000 {
		t.Errorf("peak with E-AC-3 %d", got)
	}
	if got := PeakBandwidthWithAudio(1_000_000, 0); got != PeakBandwidth(1_000_000) {
		t.Errorf("peak without audio %d, want the allowance's %d", got, PeakBandwidth(1_000_000))
	}
}

func TestAudioEncodeArgs(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		plan  AudioPlan
		args  string
		codec string
	}{
		{AudioPlan{Codec: "flac", Channels: 8}, "-c:a flac -ac 8", "fLaC"},
		{AudioPlan{Codec: "eac3", Channels: 6, Bitrate: 640_000}, "-c:a eac3 -b:a 640k -ac 6", "ec-3"},
		{AudioPlan{Codec: "ac3", Channels: 6, Bitrate: 640_000}, "-c:a ac3 -b:a 640k -ac 6", "ac-3"},
		{AudioPlan{Codec: "aac", Channels: 6, Bitrate: 384_000}, "-c:a aac -b:a 384k -ac 6", "mp4a.40.2"},
		{AudioPlan{Codec: "aac", Channels: 2, Bitrate: 192_000}, "-c:a aac -b:a 192k -ac 2", "mp4a.40.2"},
		// A plan made before Bitrate, such as a test's, keeps 192k.
		{AudioPlan{Codec: "aac", Channels: 2}, "-c:a aac -b:a 192k -ac 2", "mp4a.40.2"},
	} {
		if got := strings.Join(audioEncodeArgs(c.plan), " "); got != c.args {
			t.Errorf("%+v: args %q, want %q", c.plan, got, c.args)
		}
		if got := audioCodecs(mkInfo("matroska", "h264", 8, "", "truehd", 8), c.plan); got != c.codec {
			t.Errorf("%+v: CODECS %q, want %q", c.plan, got, c.codec)
		}
	}
}

// Each target, written by the ffmpeg on PATH into the session's fMP4, has
// the codec, channels and (for FLAC) bit depth asked for.
func TestConvertedAudioSessions(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	src := filepath.Join(t.TempDir(), "pcm71.mkv")
	if b, err := exec.Command(ffmpeg, "-v", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=4",
		"-f", "lavfi", "-i", "sine=frequency=330:duration=4:sample_rate=48000",
		"-filter_complex", "[1:a]aformat=sample_fmts=s32,pan=7.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0|c6=c0|c7=c0[a]",
		"-map", "0:v", "-map", "[a]", "-c:v", "mpeg4", "-q:v", "4", "-c:a", "pcm_s24le", "-shortest", src).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg sample: %v\n%s", err, b)
	}
	m := testManager(t, ffmpeg, "software")
	ffprobe := filepath.Join(filepath.Dir(ffmpeg), "ffprobe")
	for i, c := range []struct {
		plan AudioPlan
		want string
	}{
		{AudioPlan{Index: 1, Codec: "flac", Channels: 8}, "flac,8,24"},
		{AudioPlan{Index: 1, Codec: "flac", Channels: 6}, "flac,6,24"},
		{AudioPlan{Index: 1, Codec: "eac3", Channels: 6, Bitrate: 640_000}, "eac3,6,"},
		{AudioPlan{Index: 1, Codec: "ac3", Channels: 6, Bitrate: 640_000}, "ac3,6,"},
		{AudioPlan{Index: 1, Codec: "aac", Channels: 6, Bitrate: 384_000}, "aac,6,"},
	} {
		s, err := m.Start(context.Background(), Spec{FileID: int64(100 + i), Path: src, Mode: ModeAudio, Video: VideoPlan{Codec: "mpeg4", Copy: true}, Audio: c.plan, BurnIn: -1})
		if err != nil {
			t.Fatal(err)
		}
		select {
		case <-s.done:
		case <-time.After(60 * time.Second):
			t.Fatalf("%s: ffmpeg did not finish", c.plan.Codec)
		}
		if s.Err() != nil {
			t.Fatalf("%s: %v", c.plan.Codec, s.Err())
		}
		out, err := exec.Command(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,channels,bits_per_raw_sample",
			"-of", "csv=p=0", filepath.Join(s.Dir, "init.mp4")).CombinedOutput()
		got := strings.TrimSpace(strings.ReplaceAll(string(out), "N/A", ""))
		if c.plan.Codec != "flac" {
			got = strings.Join(strings.Split(got, ",")[:2], ",") + ","
		}
		if err != nil || got != c.want {
			t.Errorf("%s: init segment %q %v, want %q", c.plan.Codec, out, err, c.want)
		}
	}
}
