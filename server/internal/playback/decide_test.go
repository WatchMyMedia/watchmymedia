package playback

import (
	"os"
	"path/filepath"
	"testing"

	"watchmymedia/server/internal/probe"
)

func webProfile() Profile {
	return Profile{
		Name: "web", Containers: []string{"mp4", "webm"}, VideoCodecs: []string{"h264", "hevc", "vp9", "av1"},
		AudioCodecs: []string{"aac", "opus", "mp3", "flac"}, SubtitleFormats: []string{"webvtt"},
	}
}

func mkInfo(container, vcodec string, bitDepth int, hdr string, acodec string, channels int, subs ...probe.SubtitleStream) *probe.MediaInfo {
	return &probe.MediaInfo{
		Container: container, DurationMs: 60000, BitRate: 8_000_000,
		Video:     []probe.VideoStream{{Index: 0, Codec: vcodec, Width: 1920, Height: 1080, BitDepth: bitDepth, HDR: hdr}},
		Audio:     []probe.AudioStream{{Index: 1, Codec: acodec, Channels: channels, Language: "eng", Default: true}},
		Subtitles: subs,
	}
}

func TestDecideModes(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name string
		info *probe.MediaInfo
		ext  string
		req  Request
		mode Mode
		vCpy bool
		aCpy bool
	}{
		{"mp4 h264 aac -> direct", mkInfo("mov", "h264", 8, "", "aac", 2), ".mp4", Request{Profile: webProfile()}, ModeDirect, true, true},
		{"mkv h264 aac -> remux", mkInfo("matroska", "h264", 8, "", "aac", 2), ".mkv", Request{Profile: webProfile()}, ModeRemux, true, true},
		{"mkv h264 dts -> audio transcode", mkInfo("matroska", "h264", 8, "", "dts", 6), ".mkv", Request{Profile: webProfile()}, ModeAudio, true, false},
		{"mkv hevc10 -> transcode (client lacks hevc10)", mkInfo("matroska", "hevc", 10, "hdr10", "aac", 2), ".mkv", Request{Profile: webProfile()}, ModeTranscode, false, true},
		{"mkv hevc10 with support -> remux", mkInfo("matroska", "hevc", 10, "hdr10", "aac", 2), ".mkv",
			Request{Profile: Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"hevc10"}, AudioCodecs: []string{"aac"}, HDR: true}}, ModeRemux, true, true},
		{"mp4 too tall for client -> transcode", mkInfo("mov", "h264", 8, "", "aac", 2), ".mp4",
			Request{Profile: Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxHeight: 720}}, ModeTranscode, false, true},
		{"bitrate cap -> transcode", mkInfo("mov", "h264", 8, "", "aac", 2), ".mp4",
			Request{Profile: Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxBitrate: 4_000_000}}, ModeTranscode, false, true},
		{"pgs burn-in -> transcode", mkInfo("matroska", "h264", 8, "", "aac", 2, probe.SubtitleStream{Index: 2, Codec: "hdmv_pgs_subtitle", Image: true}), ".mkv",
			Request{Profile: webProfile(), SubtitleID: "s2"}, ModeTranscode, false, true},
		{"text sub selected -> no burn-in", mkInfo("matroska", "h264", 8, "", "aac", 2, probe.SubtitleStream{Index: 2, Codec: "subrip"}), ".mkv",
			Request{Profile: webProfile(), SubtitleID: "s2"}, ModeRemux, true, true},
		{"7.1 truehd with stereo client -> audio transcode to 2ch", mkInfo("matroska", "h264", 8, "", "truehd", 8), ".mkv",
			Request{Profile: Profile{Containers: []string{"mkv"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxAudioChannels: 2}}, ModeAudio, true, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			d := Decide(c.info, c.ext, c.req, nil, Capabilities{})
			if d.Mode != c.mode {
				t.Fatalf("mode = %s, want %s (reasons %v)", d.Mode, c.mode, d.Reasons)
			}
			if d.Video.Copy != c.vCpy || d.Audio.Copy != c.aCpy {
				t.Errorf("copy video=%v audio=%v, want %v/%v", d.Video.Copy, d.Audio.Copy, c.vCpy, c.aCpy)
			}
			if (d.Mode == ModeDirect) != (d.Protocol == "file") {
				t.Errorf("protocol %q for mode %s", d.Protocol, d.Mode)
			}
			if d.Mode != ModeDirect && len(d.Reasons) == 0 {
				t.Errorf("no reasons given for %s", d.Mode)
			}
		})
	}
}

func TestDecideDetails(t *testing.T) {
	t.Parallel()
	info := mkInfo("matroska", "hevc", 10, "hdr10", "truehd", 8,
		probe.SubtitleStream{Index: 2, Codec: "ass", Language: "eng"},
		probe.SubtitleStream{Index: 3, Codec: "hdmv_pgs_subtitle", Image: true, Language: "fre", Forced: true})
	info.Audio = append(info.Audio, probe.AudioStream{Index: 4, Codec: "aac", Channels: 2, Language: "ger"})
	p := webProfile()
	p.MaxAudioChannels = 2
	d := Decide(info, ".mkv", Request{Profile: p}, []Sidecar{{Path: "/m/x.en.srt", Language: "en", Codec: "subrip"}}, Capabilities{})
	if d.Mode != ModeTranscode || !d.Video.ToneMap || d.Video.Codec != "h264" {
		t.Errorf("video plan = %+v mode %s", d.Video, d.Mode)
	}
	if d.Audio.Index != 1 || d.Audio.Copy || d.Audio.Codec != "aac" || d.Audio.Channels != 2 {
		t.Errorf("audio plan = %+v", d.Audio)
	}
	if len(d.AudioTracks) != 2 || len(d.Subtitles) != 3 {
		t.Fatalf("tracks = %d subs = %d", len(d.AudioTracks), len(d.Subtitles))
	}
	if d.Subtitles[0].Format != "webvtt" || d.Subtitles[1].Format != "image" || !d.Subtitles[1].BurnInOnly || d.Subtitles[2].ID != "x0" || !d.Subtitles[2].External {
		t.Errorf("subtitles = %+v", d.Subtitles)
	}
	// Choosing the AAC track makes audio copyable; ASS clients get ASS.
	p.SubtitleFormats = []string{"webvtt", "ass"}
	idx := 4
	d = Decide(info, ".mkv", Request{Profile: p, AudioIndex: &idx}, nil, Capabilities{})
	if !d.Audio.Copy || d.Audio.Index != 4 || d.Subtitles[0].Format != "ass" {
		t.Errorf("audio = %+v subs = %+v", d.Audio, d.Subtitles)
	}
}

func TestFindSidecars(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	for _, n := range []string{"Movie (2001).mkv", "Movie (2001).srt", "Movie (2001).en.srt", "Movie (2001).fr.forced.ass", "Movie (2001).jpg", "Other.srt"} {
		if err := os.WriteFile(filepath.Join(dir, n), []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	got := FindSidecars(filepath.Join(dir, "Movie (2001).mkv"))
	if len(got) != 3 {
		t.Fatalf("sidecars = %+v", got)
	}
	if got[0].Language != "en" || got[0].Codec != "subrip" || got[1].Language != "fr" || got[1].Codec != "ass" || got[2].Language != "" {
		t.Errorf("sidecars = %+v", got)
	}
}

func TestDecide4K(t *testing.T) {
	t.Parallel()
	info := mkInfo("matroska", "hevc", 10, "hdr10", "aac", 2)
	info.Video[0].Width, info.Video[0].Height = 3840, 2160
	uhd := Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264", "hevc", "hevc10"}, AudioCodecs: []string{"aac"}, MaxWidth: 3840, MaxHeight: 2160, HDR: true}
	if d := Decide(info, ".mkv", Request{Profile: uhd}, nil, Capabilities{}); d.Mode != ModeRemux || !d.Video.Copy {
		t.Errorf("4K client: mode %s copy %v (reasons %v)", d.Mode, d.Video.Copy, d.Reasons)
	}
	// A client limited to 1080p, or one that asked for 720p, gets a scaled transcode.
	for _, c := range []struct{ maxW, maxH, w, h int }{{1920, 1080, 1920, 1080}, {1280, 720, 1280, 720}, {0, 0, 1920, 1080}} {
		p := uhd
		p.VideoCodecs = []string{"h264"}
		p.MaxWidth, p.MaxHeight = c.maxW, c.maxH
		d := Decide(info, ".mkv", Request{Profile: p}, nil, Capabilities{})
		if d.Mode != ModeTranscode || d.Video.Width != c.w || d.Video.Height != c.h {
			t.Errorf("limit %dx%d: mode %s size %dx%d, want %dx%d", c.maxW, c.maxH, d.Mode, d.Video.Width, d.Video.Height, c.w, c.h)
		}
	}
}

func TestTranscodeSize(t *testing.T) {
	t.Parallel()
	cases := []struct{ w, h, maxW, maxH, wantW, wantH int }{
		{3840, 2160, 0, 0, 1920, 1080},
		{3840, 1600, 0, 0, 1920, 800}, // scope: the width is the limit
		{1440, 1080, 0, 0, 1440, 1080},
		{1280, 720, 0, 0, 1280, 720}, // never scaled up
		{1920, 1080, 1280, 720, 1280, 720},
		{1920, 800, 1280, 720, 1280, 532}, // 533 rounded down to even
		{720, 480, 0, 0, 720, 480},
	}
	for _, c := range cases {
		w, h := transcodeSize(c.w, c.h, Profile{MaxWidth: c.maxW, MaxHeight: c.maxH}, 0)
		if w != c.wantW || h != c.wantH {
			t.Errorf("%dx%d in %dx%d = %dx%d, want %dx%d", c.w, c.h, c.maxW, c.maxH, w, h, c.wantW, c.wantH)
		}
	}
}

// With an HEVC encoder, clients that decode HEVC get it, HDR survives only
// for HDR clients that take 10-bit, and the 4K cap follows the hardware.
func TestDecideHEVCAnd4K(t *testing.T) {
	t.Parallel()
	info := mkInfo("matroska", "hevc", 10, "hdr10", "aac", 2)
	info.Video[0].Width, info.Video[0].Height = 3840, 2160
	info.BitRate = 60_000_000
	vt := Capabilities{Encoder: "h264_videotoolbox", HEVCEncoder: "hevc_videotoolbox", HWAccel: "videotoolbox", MaxHeight: 2160}
	sw := Capabilities{Encoder: "libx264", HWAccel: "software", MaxHeight: 1080}
	cases := []struct {
		name           string
		codecs         []string
		hdr            bool
		caps           Capabilities
		codec, encoder string
		toneMap        bool
		width, height  int
	}{
		{"hdr client with hevc10 keeps HDR at 4K", []string{"h264", "hevc", "hevc10"}, true, vt, "hevc", "hevc_videotoolbox", false, 3840, 2160},
		{"sdr client with hevc gets tone-mapped hevc", []string{"h264", "hevc"}, false, vt, "hevc", "hevc_videotoolbox", true, 3840, 2160},
		{"hdr client without hevc10 gets tone-mapped hevc", []string{"h264", "hevc"}, true, vt, "hevc", "hevc_videotoolbox", true, 3840, 2160},
		{"h264-only client is always tone mapped", []string{"h264"}, true, vt, "h264", "h264_videotoolbox", true, 3840, 2160},
		{"software caps: h264 at 1080p", []string{"h264", "hevc", "hevc10"}, true, sw, "h264", "libx264", true, 1920, 1080},
		{"zero caps: 1080p default", []string{"h264", "hevc"}, false, Capabilities{}, "h264", "", true, 1920, 1080},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			p := Profile{Containers: []string{"mp4"}, VideoCodecs: c.codecs, AudioCodecs: []string{"aac"}, MaxBitrate: 20_000_000, HDR: c.hdr}
			d := Decide(info, ".mkv", Request{Profile: p}, nil, c.caps)
			if d.Mode != ModeTranscode {
				t.Fatalf("mode %s (reasons %v)", d.Mode, d.Reasons)
			}
			v := d.Video
			if v.Codec != c.codec || v.Encoder != c.encoder || v.ToneMap != c.toneMap || v.Width != c.width || v.Height != c.height || v.BitDepth != 10 {
				t.Errorf("video plan = %+v", v)
			}
		})
	}
}
