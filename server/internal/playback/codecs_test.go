package playback

import (
	"testing"

	"watchmymedia/server/internal/probe"
)

func TestHLSCodecs(t *testing.T) {
	t.Parallel()
	hevc1080 := probe.VideoStream{Codec: "hevc", Profile: "Main", Width: 1920, Height: 1080, FrameRate: 23.976, BitDepth: 8}
	hdr4k := probe.VideoStream{Codec: "hevc", Profile: "Main 10", Width: 3840, Height: 2160, FrameRate: 24, BitDepth: 10, HDR: "hdr10"}
	eac3 := probe.AudioStream{Index: 1, Codec: "eac3", Channels: 6}
	aacLC := probe.AudioStream{Index: 1, Codec: "aac", Profile: "LC", Channels: 2}
	copyOf := func(v probe.VideoStream) VideoPlan {
		return VideoPlan{Codec: v.Codec, Copy: true, Width: v.Width, Height: v.Height, HDR: v.HDR, BitDepth: v.BitDepth}
	}
	copyAudio := func(a probe.AudioStream) AudioPlan {
		return AudioPlan{Index: a.Index, Codec: a.Codec, Copy: true, Channels: a.Channels}
	}
	toAAC := AudioPlan{Index: 1, Codec: "aac", Channels: 2}

	cases := []struct {
		name  string
		video probe.VideoStream
		audio []probe.AudioStream
		v     VideoPlan
		a     AudioPlan
		want  string
	}{
		// The cast that failed: HEVC copied, E-AC3 converted.
		{"hevc copy, audio to aac", hevc1080, []probe.AudioStream{eac3}, copyOf(hevc1080), toAAC, "hvc1.1.6.L120.B0,mp4a.40.2"},
		{"hevc main 10 copy", hdr4k, []probe.AudioStream{eac3}, copyOf(hdr4k), copyAudio(eac3), "hvc1.2.4.L150.B0,ec-3"},
		{"dolby vision 8 copy", probe.VideoStream{Codec: "hevc", Profile: "Main 10", Width: 3840, Height: 2160, FrameRate: 24, BitDepth: 10, HDR: "dolbyvision", DVProfile: 8},
			nil, VideoPlan{Codec: "hevc", Copy: true, Width: 3840, Height: 2160, HDR: "dolbyvision"}, AudioPlan{}, "hvc1.2.4.L150.B0"},
		{"dolby vision 5 copy", probe.VideoStream{Codec: "hevc", Profile: "Main 10", Width: 3840, Height: 2160, FrameRate: 24, HDR: "dolbyvision", DVProfile: 5},
			nil, VideoPlan{Codec: "hevc", Copy: true, Width: 3840, Height: 2160, HDR: "dolbyvision"}, AudioPlan{}, ""},
		{"hevc rext copy", probe.VideoStream{Codec: "hevc", Profile: "Rext", Width: 1920, Height: 1080}, nil,
			VideoPlan{Codec: "hevc", Copy: true, Width: 1920, Height: 1080}, AudioPlan{}, ""},
		{"h264 high copy", probe.VideoStream{Codec: "h264", Profile: "High", Width: 1920, Height: 1080, FrameRate: 24}, []probe.AudioStream{aacLC},
			VideoPlan{Codec: "h264", Copy: true, Width: 1920, Height: 1080}, copyAudio(aacLC), "avc1.640028,mp4a.40.2"},
		{"h264 baseline 720p, mp3", probe.VideoStream{Codec: "h264", Profile: "Constrained Baseline", Width: 1280, Height: 720, FrameRate: 30},
			[]probe.AudioStream{{Index: 1, Codec: "mp3"}}, VideoPlan{Codec: "h264", Copy: true, Width: 1280, Height: 720}, AudioPlan{Index: 1, Codec: "mp3", Copy: true}, "avc1.42E01F,mp4a.40.34"},
		{"h264 1080p60 copy", probe.VideoStream{Codec: "h264", Profile: "Main", Width: 1920, Height: 1080, FrameRate: 59.94}, nil,
			VideoPlan{Codec: "h264", Copy: true, Width: 1920, Height: 1080}, AudioPlan{}, "avc1.4D402A"},
		{"he-aac copy", probe.VideoStream{Codec: "h264", Profile: "High", Width: 1280, Height: 720, FrameRate: 25},
			[]probe.AudioStream{{Index: 2, Codec: "aac", Profile: "HE-AAC"}}, VideoPlan{Codec: "h264", Copy: true, Width: 1280, Height: 720},
			AudioPlan{Index: 2, Codec: "aac", Copy: true}, "avc1.64001F,mp4a.40.5"},
		// Transcodes: what encoderArgs produces, at the output size.
		{"hevc to h264 1080p", hdr4k, []probe.AudioStream{eac3}, VideoPlan{Codec: "h264", Width: 1920, Height: 1080, HDR: "hdr10", ToneMap: true}, toAAC, "avc1.640028,mp4a.40.2"},
		{"hevc transcode keeping hdr", hdr4k, nil, VideoPlan{Codec: "hevc", Width: 3840, Height: 2160, HDR: "hdr10"}, AudioPlan{}, "hvc1.2.4.L150.B0"},
		{"hevc transcode sdr", hevc1080, nil, VideoPlan{Codec: "hevc", Width: 1280, Height: 720}, AudioPlan{}, "hvc1.1.6.L93.B0"},
		{"av1 10-bit copy", probe.VideoStream{Codec: "av1", Profile: "Main", Width: 1920, Height: 1080, FrameRate: 24, BitDepth: 10},
			[]probe.AudioStream{{Index: 1, Codec: "opus"}}, VideoPlan{Codec: "av1", Copy: true, Width: 1920, Height: 1080}, AudioPlan{Index: 1, Codec: "opus", Copy: true}, "av01.0.08M.10,Opus"},
		// No string for the audio: no attribute at all.
		{"dts copy", hevc1080, []probe.AudioStream{{Index: 1, Codec: "dts"}}, copyOf(hevc1080), AudioPlan{Index: 1, Codec: "dts", Copy: true}, ""},
		{"vp9 copy", probe.VideoStream{Codec: "vp9", Width: 1920, Height: 1080}, nil, VideoPlan{Codec: "vp9", Copy: true, Width: 1920, Height: 1080}, AudioPlan{}, ""},
	}
	for _, c := range cases {
		info := &probe.MediaInfo{Video: []probe.VideoStream{c.video}, Audio: c.audio}
		if got := HLSCodecs(info, Decision{Video: c.v, Audio: c.a}); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
	if got := HLSCodecs(&probe.MediaInfo{}, Decision{}); got != "" {
		t.Errorf("no video: got %q", got)
	}
}

// The master playlist's strings for each Dolby Vision copy: kept (profile 5
// under dvh1; 8 as its base layer with SUPPLEMENTAL-CODECS), stripped to its
// base layer, and as older apps' profiles get it.
func TestDolbyVisionPlaylistStrings(t *testing.T) {
	t.Parallel()
	dv := func(profile, compat, level int) probe.VideoStream {
		return probe.VideoStream{Codec: "hevc", Profile: "Main 10", Width: 3840, Height: 2160, FrameRate: 24, BitDepth: 10, HDR: "dolbyvision",
			DVProfile: profile, DVCompat: compat, DVLevel: level, Detailed: true}
	}
	old8 := dv(8, 0, 0)
	old8.Detailed = false
	kept := func(p int) VideoPlan {
		return VideoPlan{Codec: "hevc", Copy: true, HDR: "dolbyvision", DVProfile: p, KeepDV: true}
	}
	stripped := func(hdr string) VideoPlan { return VideoPlan{Codec: "hevc", Copy: true, HDR: hdr, StripDV: true} }
	asBefore := VideoPlan{Codec: "hevc", Copy: true, HDR: "dolbyvision"}
	for _, c := range []struct {
		name                      string
		src                       probe.VideoStream
		v                         VideoPlan
		codecs, supplemental, rng string
	}{
		{"5 kept", dv(5, 0, 6), kept(5), "dvh1.05.06", "", "PQ"},
		{"5 kept at level 9", dv(5, 0, 9), kept(5), "dvh1.05.09", "", "PQ"},
		{"8.1 kept", dv(8, 1, 6), kept(8), "hvc1.2.4.L150.B0", "dvh1.08.06/db1p", "PQ"},
		{"8.4 kept", dv(8, 4, 7), kept(8), "hvc1.2.4.L150.B0", "dvh1.08.07/db4h", "HLG"},
		{"8.2 kept", dv(8, 2, 6), kept(8), "hvc1.2.4.L150.B0", "dvh1.08.06/db2g", "SDR"},
		{"8.6 kept", dv(8, 6, 6), kept(8), "hvc1.2.4.L150.B0", "dvh1.08.06/db1p", "PQ"},
		// An old probe knows no level: CODECS for the base, no Dolby Vision string.
		{"8 kept from an old probe", old8, kept(8), "hvc1.2.4.L150.B0", "", "PQ"},
		{"8.1 stripped", dv(8, 1, 6), stripped("hdr10"), "hvc1.2.4.L150.B0", "", "PQ"},
		{"8.4 stripped", dv(8, 4, 6), stripped("hlg"), "hvc1.2.4.L150.B0", "", "HLG"},
		{"8.2 stripped", dv(8, 2, 6), stripped(""), "hvc1.2.4.L150.B0", "", "SDR"},
		{"7 stripped", dv(7, 6, 6), stripped("hdr10"), "hvc1.2.4.L150.B0", "", "PQ"},
		{"8 as before", dv(8, 1, 6), asBefore, "hvc1.2.4.L150.B0", "", "PQ"},
		{"5 as before", dv(5, 0, 6), asBefore, "", "", "PQ"},
		{"5 tone mapped", dv(5, 0, 6), VideoPlan{Codec: "h264", Width: 1920, Height: 1080, HDR: "dolbyvision", ToneMap: true}, "avc1.640028", "", "SDR"},
	} {
		info := &probe.MediaInfo{Video: []probe.VideoStream{c.src}}
		d := Decision{Video: c.v}
		if d.Video.Width == 0 {
			d.Video.Width, d.Video.Height = c.src.Width, c.src.Height
		}
		if got := HLSCodecs(info, d); got != c.codecs {
			t.Errorf("%s: CODECS %q, want %q", c.name, got, c.codecs)
		}
		if got := HLSSupplementalCodecs(info, d); got != c.supplemental {
			t.Errorf("%s: SUPPLEMENTAL-CODECS %q, want %q", c.name, got, c.supplemental)
		}
		if got := HLSVideoRange(info, d); got != c.rng {
			t.Errorf("%s: VIDEO-RANGE %q, want %q", c.name, got, c.rng)
		}
	}
	// The other ranges, as before.
	for hdr, want := range map[string]string{"": "SDR", "hdr10": "PQ", "hlg": "HLG"} {
		v := probe.VideoStream{Codec: "hevc", HDR: hdr}
		if got := HLSVideoRange(&probe.MediaInfo{Video: []probe.VideoStream{v}}, Decision{Video: VideoPlan{Copy: true, HDR: hdr}}); got != want {
			t.Errorf("%q: VIDEO-RANGE %q, want %q", hdr, got, want)
		}
	}
	if got := HLSVideoRange(&probe.MediaInfo{}, Decision{Video: VideoPlan{HDR: "hdr10", ToneMap: true}}); got != "SDR" {
		t.Errorf("tone mapped: %q", got)
	}
}
