package playback

import (
	"testing"

	"watchmymedia/server/internal/probe"
)

// Every reason Decide writes has a kind, so Stats can count why plays were
// converted: a new reason that reads as "other" fails here until
// ReasonKind knows its shape.
func TestEveryReasonHasAKind(t *testing.T) {
	t.Parallel()
	mp4 := Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264", "hevc", "hevc10"}, AudioCodecs: []string{"aac", "ac3", "eac3", "flac"}, HDR: true, HDRTypes: []string{"hdr10"}}
	stereo := mp4
	stereo.MaxAudioChannels = 2
	small := mp4
	small.MaxWidth, small.MaxHeight, small.MaxBitrate = 1280, 720, 2_000_000
	slow := mp4
	slow.MaxBitrate = 2_000_000
	sdr := mp4
	sdr.HDRTypes = []string{}
	dovi7 := Profile{Containers: []string{"mkv", "mp4"}, VideoCodecs: []string{"h264", "hevc", "hevc10", "dovi7", "dovi8"}, AudioCodecs: []string{"aac", "eac3"}, HDR: true, HDRTypes: []string{"hdr10", "dovi"}}
	withAudio := func(info *probe.MediaInfo, a ...probe.AudioStream) *probe.MediaInfo { info.Audio = a; return info }
	withVideo := func(info *probe.MediaInfo, v probe.VideoStream) *probe.MediaInfo {
		info.Video = []probe.VideoStream{v}
		return info
	}
	noVideo := func(info *probe.MediaInfo) *probe.MediaInfo { info.Video = nil; return info }
	image := probe.SubtitleStream{Index: 2, Codec: "hdmv_pgs_subtitle", Image: true}
	second := 2
	cases := []struct {
		name string
		info *probe.MediaInfo
		ext  string
		req  Request
		caps Capabilities
		want string
	}{
		{"no video", noVideo(mkInfo("mov", "h264", 8, "", "aac", 2)), ".mp4", Request{Profile: mp4}, Capabilities{}, ReasonVideoFormat},
		{"a video codec", mkInfo("matroska", "vp9", 8, "", "aac", 2), ".mkv", Request{Profile: mp4}, Capabilities{}, ReasonVideoFormat},
		{"HDR the screen can't show", mkInfo("mov", "hevc", 10, "hdr10", "aac", 2), ".mp4", Request{Profile: sdr}, Capabilities{}, ReasonVideoFormat},
		{"Dolby Vision the device can't play", withVideo(mkInfo("mov", "hevc", 10, "", "aac", 2), probe.VideoStream{Codec: "hevc", Width: 3840, Height: 2160, BitDepth: 10, HDR: "dolbyvision", DVProfile: 5}),
			".mp4", Request{Profile: dovi7}, Capabilities{Encoder: "libx264"}, ReasonVideoFormat},
		{"Dolby Vision 7 as its base layer", withVideo(mkInfo("matroska", "hevc", 10, "", "truehd", 8), probe.VideoStream{Codec: "hevc", Width: 3840, Height: 2160, BitDepth: 10, HDR: "dolbyvision", DVProfile: 7}),
			".mkv", Request{Profile: dovi7}, Capabilities{Encoder: "libx264"}, ReasonVideoFormat},
		{"too large", withVideo(mkInfo("mov", "h264", 8, "", "aac", 2), probe.VideoStream{Codec: "h264", Width: 1920, Height: 1080, BitDepth: 8}), ".mp4", Request{Profile: small}, Capabilities{}, ReasonResolution},
		{"too many bits", mkInfo("mov", "h264", 8, "", "aac", 2), ".mp4", Request{Profile: slow}, Capabilities{}, ReasonBitrate},
		{"a sound format", mkInfo("mov", "h264", 8, "", "dts", 6), ".mp4", Request{Profile: mp4}, Capabilities{}, ReasonAudioFormat},
		{"surround to stereo", mkInfo("mov", "h264", 8, "", "eac3", 6), ".mp4", Request{Profile: stereo}, Capabilities{}, ReasonAudioChannels},
		{"lossless kept as FLAC", mkInfo("matroska", "h264", 8, "", "truehd", 8), ".mkv", Request{Profile: mp4}, Capabilities{}, ReasonAudioFormat},
		{"PCM outside Matroska", mkInfo("mov", "h264", 8, "", "pcm_s24le", 2), ".mov", Request{Profile: Profile{Containers: []string{"mov"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"pcm", "aac"}}}, Capabilities{}, ReasonAudioFormat},
		{"subtitles burned in", mkInfo("matroska", "h264", 8, "", "aac", 2, image), ".mkv", Request{Profile: mp4, SubtitleID: "s2"}, Capabilities{}, ReasonSubtitles},
		{"another audio track", withAudio(mkInfo("mov", "h264", 8, "", "aac", 2), probe.AudioStream{Index: 1, Codec: "aac", Channels: 2, Default: true}, probe.AudioStream{Index: 2, Codec: "aac", Channels: 2}),
			".mp4", Request{Profile: mp4, AudioIndex: &second}, Capabilities{}, ReasonAudioTrack},
		{"a file type", mkInfo("matroska", "h264", 8, "", "aac", 2), ".mkv", Request{Profile: mp4}, Capabilities{}, ReasonContainer},
	}
	seen := map[string]bool{}
	for _, c := range cases {
		d := Decide(c.info, c.ext, c.req, nil, c.caps)
		found := false
		for _, r := range d.Reasons {
			k := ReasonKind(r)
			if k == ReasonOther {
				t.Errorf("%s: %q reads as other", c.name, r)
			}
			seen[k] = true
			found = found || k == c.want
		}
		if !found {
			t.Errorf("%s: no %s among %q", c.name, c.want, d.Reasons)
		}
	}
	for _, k := range ReasonKinds {
		if k != ReasonSmaller && k != ReasonOther && !seen[k] {
			t.Errorf("no case writes a reason of kind %s", k)
		}
	}
}

// Reasons written outside Decide, and notes that say how rather than why.
func TestReasonKindShapes(t *testing.T) {
	t.Parallel()
	for reason, want := range map[string]string{
		"audio track 2 (fra) from the person's audio languages":                   ReasonAudioTrack,
		"h264_videotoolbox failed, so the transcode runs in software":             "",
		"the server cannot convert Dolby Vision 5's colours, so they will be off": "",
		"audio converted to EAC3 5.1, which keeps it surround":                    "",
		"3840x2160 exceeds the client's 1920x1080":                                ReasonResolution,
		"bitrate 24000 kb/s exceeds the client's 8000 kb/s":                       ReasonBitrate,
		"8 audio channels exceed the client's 2":                                  ReasonAudioChannels,
		"client cannot decode dts audio":                                          ReasonAudioFormat,
		"client cannot decode vp9 video":                                          ReasonVideoFormat,
		"something new":                                                           ReasonOther,
	} {
		if got := ReasonKind(reason); got != want {
			t.Errorf("ReasonKind(%q) = %q, want %q", reason, got, want)
		}
	}
}
