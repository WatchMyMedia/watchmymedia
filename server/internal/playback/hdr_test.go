package playback

import (
	"context"
	"fmt"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/probe/probetest"
)

// Sources for the HDR and Dolby Vision decisions (PLAN-HDR-PROFILE.md §8).
// dvNew streams were probed with the record's base layer and level;
// dvOld ones before (decision 10).
func hdrSource(codec, hdr string) probe.VideoStream {
	return probe.VideoStream{Codec: codec, Profile: "Main 10", Width: 1920, Height: 1080, FrameRate: 24, BitDepth: 10, HDR: hdr, Detailed: true}
}

func dvNew(profile, compat int) probe.VideoStream {
	v := hdrSource("hevc", "dolbyvision")
	v.DVProfile, v.DVCompat, v.DVLevel = profile, compat, 6
	return v
}

func dvOld(profile int) probe.VideoStream {
	v := hdrSource("hevc", "dolbyvision")
	v.DVProfile, v.Detailed = profile, false
	return v
}

// hdrProfile is a device that decodes everything; types nil is an older
// app's profile, which says only hdr.
func hdrProfile(hdr bool, types []string, keys ...string) Profile {
	return Profile{Containers: []string{"mkv", "mp4"}, VideoCodecs: append([]string{"h264", "hevc", "hevc10", "av1", "vp9"}, keys...),
		AudioCodecs: []string{"aac"}, HDR: hdr, HDRTypes: types}
}

func TestDecideHDRAndDolbyVision(t *testing.T) {
	t.Parallel()
	var (
		oldHDR   = hdrProfile(true, nil)
		oldSDR   = hdrProfile(false, nil)
		sdr      = hdrProfile(false, []string{})
		hdr10    = hdrProfile(true, []string{"hdr10"})
		hdr10HLG = hdrProfile(true, []string{"hdr10", "hlg"})
		dovi8    = hdrProfile(true, []string{"hdr10", "hlg", "dovi"}, "dovi8")
		dovi58   = hdrProfile(true, []string{"hdr10", "hlg", "dovi"}, "dovi5", "dovi8")
		dovi7    = hdrProfile(true, []string{"hdr10", "hlg", "dovi"}, "dovi7", "dovi8")
	)
	// mp4Only is an Apple device: anything in Matroska is remuxed.
	mp4Only := func(p Profile) Profile { p.Containers = []string{"mp4"}; return p }
	tagged := func(v probe.VideoStream, tag string) probe.VideoStream { v.CodecTag = tag; return v }
	vt := Capabilities{Encoder: "h264_videotoolbox", HEVCEncoder: "hevc_videotoolbox", HWAccel: "videotoolbox", MaxHeight: 2160}

	type want struct {
		mode        Mode
		hdr         string // VideoPlan.HDR
		keep, strip bool
		dvProfile   int
		toneMap     bool
		reason      string // a reason that must be given, if any
	}
	cases := []struct {
		name string
		v    probe.VideoStream
		ext  string
		p    Profile
		caps Capabilities
		want want
	}{
		// Older apps' profiles: Dolby Vision as before, and HDR only to an HDR screen.
		{"hdr10 to an older hdr profile", hdrSource("hevc", "hdr10"), ".mkv", oldHDR, vt, want{mode: ModeDirect, hdr: "hdr10"}},
		{"hdr10 to an older sdr profile", hdrSource("hevc", "hdr10"), ".mkv", oldSDR, vt, want{mode: ModeTranscode, hdr: "hdr10", toneMap: true, reason: "the screen cannot show HDR10"}},
		{"hlg to an older sdr profile", hdrSource("hevc", "hlg"), ".mkv", oldSDR, vt, want{mode: ModeTranscode, hdr: "hlg", toneMap: true, reason: "the screen cannot show HLG"}},
		{"av1 hdr to an older sdr profile", hdrSource("av1", "hdr10"), ".mkv", oldSDR, vt, want{mode: ModeTranscode, hdr: "hdr10", toneMap: true, reason: "the screen cannot show HDR10"}},
		{"vp9 profile 2 hdr to an older sdr profile", hdrSource("vp9", "hdr10"), ".webm", hdrProfile(false, nil), vt, want{mode: ModeTranscode, hdr: "hdr10", toneMap: true, reason: "the screen cannot show HDR10"}},
		{"10-bit sdr hevc to an older sdr profile", hdrSource("hevc", ""), ".mkv", oldSDR, vt, want{mode: ModeDirect}},
		{"dv 8.1 to an older hdr profile", dvNew(8, 1), ".mkv", oldHDR, vt, want{mode: ModeDirect, hdr: "dolbyvision", dvProfile: 8}},
		{"dv 5 mp4 to an older hdr profile", tagged(dvNew(5, 0), "dvh1"), ".mp4", oldHDR, vt, want{mode: ModeDirect, hdr: "dolbyvision", dvProfile: 5}},
		{"dv 5 mkv to an older apple profile", dvNew(5, 0), ".mkv", mp4Only(oldHDR), vt, want{mode: ModeRemux, hdr: "dolbyvision", dvProfile: 5}},
		{"dv 8.1 to an older sdr profile", dvNew(8, 1), ".mkv", oldSDR, vt, want{mode: ModeTranscode, hdr: "hdr10", toneMap: true, reason: "the screen cannot show Dolby Vision"}},

		// An SDR screen with a Main 10 decoder.
		{"10-bit sdr hevc to an sdr screen", hdrSource("hevc", ""), ".mkv", sdr, vt, want{mode: ModeDirect}},
		{"hdr10 to an sdr screen", hdrSource("hevc", "hdr10"), ".mkv", sdr, vt, want{mode: ModeTranscode, hdr: "hdr10", toneMap: true, reason: "the screen cannot show HDR10"}},
		{"dv 8.1 to an sdr screen", dvNew(8, 1), ".mkv", sdr, vt, want{mode: ModeTranscode, hdr: "hdr10", toneMap: true, reason: "the screen cannot show HDR10"}},
		{"dv 8.2 to an sdr screen", dvNew(8, 2), ".mkv", sdr, vt, want{mode: ModeDirect}},
		{"dv 8.2 mp4 hvc1 to an sdr screen", tagged(dvNew(8, 2), "hvc1"), ".mp4", sdr, vt, want{mode: ModeDirect}},
		{"dv 8.2 mp4 dvh1 to an sdr screen", tagged(dvNew(8, 2), "dvh1"), ".mp4", sdr, vt, want{mode: ModeRemux, strip: true, reason: "Dolby Vision 8 is sent as its SDR base layer, which needs a remux"}},
		{"dv 5 to an sdr screen", dvNew(5, 0), ".mkv", sdr, vt, want{mode: ModeTranscode, hdr: "dolbyvision", toneMap: true,
			reason: "client cannot play Dolby Vision 5"}},
		{"dv 5 colours off without tonemapx", dvNew(5, 0), ".mkv", sdr, vt, want{mode: ModeTranscode, hdr: "dolbyvision", toneMap: true,
			reason: "the server cannot convert Dolby Vision 5's colours, so they will be off"}},

		// An HDR10 screen, and one that also shows HLG.
		{"hdr10 to an hdr10 screen", hdrSource("hevc", "hdr10"), ".mkv", hdr10, vt, want{mode: ModeDirect, hdr: "hdr10"}},
		{"hlg to an hdr10 screen", hdrSource("hevc", "hlg"), ".mkv", hdr10, vt, want{mode: ModeTranscode, hdr: "hlg", toneMap: true, reason: "the screen cannot show HLG"}},
		{"av1 hdr to an hdr10 screen", hdrSource("av1", "hdr10"), ".mkv", hdr10, vt, want{mode: ModeDirect, hdr: "hdr10"}},
		{"dv 8.1 mkv to an hdr10 screen", dvNew(8, 1), ".mkv", hdr10, vt, want{mode: ModeDirect, hdr: "hdr10"}},
		{"dv 8.1 mp4 hvc1 to an hdr10 screen", tagged(dvNew(8, 1), "hvc1"), ".mp4", hdr10, vt, want{mode: ModeDirect, hdr: "hdr10"}},
		{"dv 8.1 mp4 dvh1 to an hdr10 screen", tagged(dvNew(8, 1), "dvh1"), ".mp4", hdr10, vt, want{mode: ModeRemux, hdr: "hdr10", strip: true,
			reason: "Dolby Vision 8 is sent as its HDR10 base layer, which needs a remux"}},
		{"dv 8.1 mkv to an hdr10 apple device", dvNew(8, 1), ".mkv", mp4Only(hdr10), vt, want{mode: ModeRemux, hdr: "hdr10", strip: true}},
		{"dv 8.4 to an hdr10 screen", dvNew(8, 4), ".mkv", hdr10, vt, want{mode: ModeTranscode, hdr: "hlg", toneMap: true, reason: "the screen cannot show HLG"}},
		{"dv 7 to an hdr10 screen", dvNew(7, 6), ".mkv", hdr10, vt, want{mode: ModeRemux, hdr: "hdr10", strip: true,
			reason: "Dolby Vision 7 is sent as its HDR10 base layer, which needs a remux"}},
		{"dv 7 to an sdr screen", dvNew(7, 6), ".mkv", sdr, vt, want{mode: ModeTranscode, hdr: "hdr10", toneMap: true, reason: "the screen cannot show HDR10"}},
		{"dv 8 old probe to an hdr10 screen", dvOld(8), ".mkv", hdr10, vt, want{mode: ModeDirect, hdr: "hdr10"}},
		{"dv 5 old probe to an hdr10 screen", dvOld(5), ".mkv", hdr10, vt, want{mode: ModeTranscode, hdr: "dolbyvision", toneMap: true, reason: "client cannot play Dolby Vision 5"}},
		{"hlg to an hdr10 and hlg screen", hdrSource("hevc", "hlg"), ".mkv", hdr10HLG, vt, want{mode: ModeDirect, hdr: "hlg"}},
		{"dv 8.4 to an hdr10 and hlg screen", dvNew(8, 4), ".mkv", hdr10HLG, vt, want{mode: ModeDirect, hdr: "hlg"}},
		{"dv 8.4 mp4 dvh1 to an hdr10 and hlg screen", tagged(dvNew(8, 4), "dvh1"), ".mp4", hdr10HLG, vt, want{mode: ModeRemux, hdr: "hlg", strip: true}},

		// Dolby Vision displays with the decoders' profiles.
		{"dv 8.1 with dovi8", dvNew(8, 1), ".mkv", dovi8, vt, want{mode: ModeDirect, hdr: "dolbyvision", keep: true, dvProfile: 8}},
		{"dv 8.1 mp4 dvh1 with dovi8", tagged(dvNew(8, 1), "dvh1"), ".mp4", dovi8, vt, want{mode: ModeDirect, hdr: "dolbyvision", keep: true, dvProfile: 8}},
		{"dv 8.4 mkv with dovi8 on apple", dvNew(8, 4), ".mkv", mp4Only(dovi8), vt, want{mode: ModeRemux, hdr: "dolbyvision", keep: true, dvProfile: 8}},
		{"dv 8 old probe with dovi8", dvOld(8), ".mkv", dovi8, vt, want{mode: ModeDirect, hdr: "dolbyvision", keep: true, dvProfile: 8}},
		{"dv 5 with dovi8 only", dvNew(5, 0), ".mkv", dovi8, vt, want{mode: ModeTranscode, hdr: "dolbyvision", toneMap: true, reason: "client cannot play Dolby Vision 5"}},
		{"dv 7 with dovi8 only", dvNew(7, 6), ".mkv", dovi8, vt, want{mode: ModeRemux, hdr: "hdr10", strip: true}},
		{"dv 5 with dovi5", dvNew(5, 0), ".mkv", dovi58, vt, want{mode: ModeDirect, hdr: "dolbyvision", keep: true, dvProfile: 5}},
		{"dv 5 mkv with dovi5 on apple", dvNew(5, 0), ".mkv", mp4Only(dovi58), vt, want{mode: ModeRemux, hdr: "dolbyvision", keep: true, dvProfile: 5}},
		{"dv 5 old probe with dovi5", dvOld(5), ".mkv", dovi58, vt, want{mode: ModeDirect, hdr: "dolbyvision", keep: true, dvProfile: 5}},
		{"dv 7 with dovi7", dvNew(7, 6), ".mkv", dovi7, vt, want{mode: ModeDirect, hdr: "dolbyvision", keep: true, dvProfile: 7}},
		{"dv 7 with dovi7 but no mkv", dvNew(7, 6), ".mkv", mp4Only(dovi7), vt, want{mode: ModeRemux, hdr: "hdr10", strip: true,
			reason: "Dolby Vision 7 is sent as its HDR10 base layer, which needs a remux"}},
		{"av1 dv 10.1 is its hdr10 base", func() probe.VideoStream { v := dvNew(10, 1); v.Codec = "av1"; return v }(), ".mkv", dovi58, vt, want{mode: ModeDirect, hdr: "hdr10"}},
		{"av1 dv 10.0 has no key yet", func() probe.VideoStream { v := dvNew(10, 0); v.Codec = "av1"; return v }(), ".mkv", dovi58, vt,
			want{mode: ModeTranscode, hdr: "dolbyvision", toneMap: true, reason: "client cannot play Dolby Vision 10"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			info := &probe.MediaInfo{DurationMs: 60000, BitRate: 8_000_000, Video: []probe.VideoStream{c.v},
				Audio: []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2, Default: true}}}
			caps := c.caps
			caps.DoviToneMap = !strings.Contains(c.name, "without tonemapx")
			d := Decide(info, c.ext, Request{Profile: c.p}, nil, caps)
			v, w := d.Video, c.want
			if d.Mode != w.mode || v.Copy != (w.mode != ModeTranscode) || v.HDR != w.hdr || v.KeepDV != w.keep || v.StripDV != w.strip ||
				v.DVProfile != w.dvProfile || v.ToneMap != w.toneMap {
				t.Errorf("mode %s video %+v (reasons %q), want %+v", d.Mode, v, d.Reasons, w)
			}
			if w.reason != "" && !slices.Contains(d.Reasons, w.reason) {
				t.Errorf("reasons %q lack %q", d.Reasons, w.reason)
			}
			colours := slices.ContainsFunc(d.Reasons, func(r string) bool { return strings.Contains(r, "colours") })
			if colours != (!caps.DoviToneMap && w.toneMap && w.hdr == "dolbyvision") {
				t.Errorf("colour warning %v, reasons %q", colours, d.Reasons)
			}
			if d.Mode != ModeDirect && len(d.Reasons) == 0 {
				t.Errorf("no reasons for %s", d.Mode)
			}
		})
	}
}

// Dolby Vision 7 is kept only in direct play: when the audio has to be
// converted, the video goes as its HDR10 base layer instead.
func TestDolbyVision7KeptOnlyInDirectPlay(t *testing.T) {
	t.Parallel()
	info := &probe.MediaInfo{DurationMs: 60000, Video: []probe.VideoStream{dvNew(7, 6)}, Audio: []probe.AudioStream{{Index: 1, Codec: "truehd", Channels: 8, Default: true}}}
	p := hdrProfile(true, []string{"hdr10", "dovi"}, "dovi7")
	d := Decide(info, ".mkv", Request{Profile: p}, nil, Capabilities{})
	if d.Mode != ModeAudio || !d.Video.Copy || d.Video.KeepDV || !d.Video.StripDV || d.Video.HDR != "hdr10" || d.Video.DVProfile != 0 ||
		!slices.Contains(d.Reasons, "Dolby Vision 7 is sent as its HDR10 base layer, which needs a remux") {
		t.Errorf("mode %s video %+v reasons %q", d.Mode, d.Video, d.Reasons)
	}
	// Without hdr10 there is no base layer to send either.
	p.HDRTypes = []string{"dovi"}
	if d := Decide(info, ".mkv", Request{Profile: p}, nil, Capabilities{}); d.Mode != ModeTranscode || !d.Video.ToneMap ||
		!slices.Contains(d.Reasons, "the screen cannot show HDR10") {
		t.Errorf("no hdr10: mode %s video %+v reasons %q", d.Mode, d.Video, d.Reasons)
	}
}

// A transcode keeps HDR only for a screen that shows its kind, and never
// for profile 5, which goes to SDR (PLAN-HDR-PROFILE.md §9 has HDR10).
func TestTranscodeKeepsOnlyHDRTheScreenShows(t *testing.T) {
	t.Parallel()
	vt := Capabilities{Encoder: "h264_videotoolbox", HEVCEncoder: "hevc_videotoolbox", HWAccel: "videotoolbox", MaxHeight: 2160, DoviToneMap: true}
	for _, c := range []struct {
		name    string
		v       probe.VideoStream
		p       Profile
		hdr     string
		toneMap bool
	}{
		{"hdr10 to an hdr10 screen", hdrSource("hevc", "hdr10"), hdrProfile(true, []string{"hdr10"}), "hdr10", false},
		{"hlg to an hdr10 screen", hdrSource("hevc", "hlg"), hdrProfile(true, []string{"hdr10"}), "hlg", true},
		{"hlg to an hlg screen", hdrSource("hevc", "hlg"), hdrProfile(true, []string{"hlg"}), "hlg", false},
		{"hdr10 to an older hdr profile", hdrSource("hevc", "hdr10"), hdrProfile(true, nil), "hdr10", false},
		{"dv 8.1 to an older hdr profile", dvNew(8, 1), hdrProfile(true, nil), "hdr10", false},
		{"dv 8.4 to an hlg screen", dvNew(8, 4), hdrProfile(true, []string{"hlg"}), "hlg", false},
		{"dv 7 to an hdr10 screen", dvNew(7, 6), hdrProfile(true, []string{"hdr10"}), "hdr10", false},
		{"dv 5 to an older hdr profile", dvNew(5, 0), hdrProfile(true, nil), "dolbyvision", true},
		{"dv 5 to a dolby vision screen", dvNew(5, 0), hdrProfile(true, []string{"hdr10", "dovi"}, "dovi5"), "dolbyvision", true},
	} {
		info := &probe.MediaInfo{DurationMs: 60000, Video: []probe.VideoStream{c.v}, Audio: []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2}}}
		c.p.MaxHeight = 720 // forces a transcode
		d := Decide(info, ".mkv", Request{Profile: c.p}, nil, vt)
		if d.Mode != ModeTranscode || d.Video.Codec != "hevc" || d.Video.HDR != c.hdr || d.Video.ToneMap != c.toneMap || d.Video.DVProfile != 0 || d.Video.KeepDV || d.Video.StripDV {
			t.Errorf("%s: mode %s video %+v", c.name, d.Mode, d.Video)
		}
	}
}

func TestBaseLayer(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		v    probe.VideoStream
		kind string
		ok   bool
	}{
		{hdrSource("hevc", "hdr10"), "hdr10", true},
		{hdrSource("hevc", ""), "", true},
		{dvNew(5, 0), "dolbyvision", false},
		{dvNew(7, 6), "hdr10", true},
		{dvNew(8, 1), "hdr10", true},
		{dvNew(8, 2), "", true},
		{dvNew(8, 4), "hlg", true},
		{dvNew(8, 6), "hdr10", true},
		{dvNew(8, 0), "hdr10", true}, // no valid profile 8 lacks a base layer
		{dvOld(8), "hdr10", true},
		{dvOld(7), "hdr10", true},
		{dvOld(5), "dolbyvision", false},
		{dvOld(0), "dolbyvision", false}, // profile never read
		{dvNew(10, 4), "hlg", true},
	} {
		if kind, ok := baseLayer(c.v); kind != c.kind || ok != c.ok {
			t.Errorf("profile %d compat %d detailed %v: %q %v, want %q %v", c.v.DVProfile, c.v.DVCompat, c.v.Detailed, kind, ok, c.kind, c.ok)
		}
	}
}

// argsOf is a session's ffmpeg command line, joined, and its -vf.
func argsOf(t *testing.T, m *Manager, spec Spec) (all, vf string) {
	t.Helper()
	a := m.args(spec, t.TempDir())
	for i, x := range a {
		if x == "-vf" {
			vf = a[i+1]
		}
	}
	return strings.Join(a, " "), vf
}

func TestDolbyVisionCopyArgs(t *testing.T) {
	t.Parallel()
	m := NewManager("ffmpeg", t.TempDir(), Capabilities{Encoder: "libx264", HWAccel: "software", doviStrip: true}, time.Minute, 1, testLog)
	spec := func(v VideoPlan) Spec {
		v.Codec, v.Copy = "hevc", true
		return Spec{Path: "/m/movie.mkv", Mode: ModeRemux, Video: v, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	}
	for _, c := range []struct {
		name       string
		v          VideoPlan
		has, lacks []string
	}{
		{"as before", VideoPlan{HDR: "dolbyvision", DVProfile: 8}, []string{"-c:v copy -tag:v hvc1 -map 0:1"}, []string{"-strict", "dovi_rpu", "dvh1"}},
		{"8 kept", VideoPlan{HDR: "dolbyvision", DVProfile: 8, KeepDV: true}, []string{"-c:v copy -tag:v hvc1 -strict unofficial -map 0:1"}, []string{"dovi_rpu", "dvh1"}},
		{"5 kept", VideoPlan{HDR: "dolbyvision", DVProfile: 5, KeepDV: true}, []string{"-c:v copy -tag:v dvh1 -strict unofficial -map 0:1"}, []string{"dovi_rpu", "hvc1"}},
		{"base layer", VideoPlan{HDR: "hdr10", StripDV: true}, []string{"-c:v copy -tag:v hvc1 -bsf:v dovi_rpu=strip=1 -map 0:1"}, []string{"-strict", "dvh1"}},
	} {
		all, _ := argsOf(t, m, spec(c.v))
		for _, s := range c.has {
			if !strings.Contains(all, s) {
				t.Errorf("%s: no %q in %s", c.name, s, all)
			}
		}
		for _, s := range c.lacks {
			if strings.Contains(all, s) {
				t.Errorf("%s: %q in %s", c.name, s, all)
			}
		}
	}
	// An ffmpeg without dovi_rpu still drops the record, which it writes
	// only with -strict unofficial.
	m.caps.doviStrip = false
	if all, _ := argsOf(t, m, spec(VideoPlan{HDR: "hdr10", StripDV: true})); strings.Contains(all, "dovi_rpu") || strings.Contains(all, "-strict") || !strings.Contains(all, "-tag:v hvc1") {
		t.Errorf("no dovi_rpu: %s", all)
	}
}

// Profile 5 is tone mapped on the CPU, through tonemapx where ffmpeg has
// it; elsewhere it is read as HDR10 so the transcode runs at all. Other
// HDR keeps its GPU path.
func TestDolbyVisionToneMapArgs(t *testing.T) {
	t.Parallel()
	dv := Spec{Path: "/m/movie.mkv", Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 1920, Height: 1080, HDR: "dolbyvision", BitDepth: 10, ToneMap: true},
		Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1}
	hdr10 := dv
	hdr10.Video.HDR = "hdr10"
	const tonemapx = "tonemapx=tonemap=bt2390:desat=0:peak=100:t=bt709:m=bt709:p=bt709:format=yuv420p,scale=1920:1080,format=yuv420p"
	const zscale = "scale=1920:1080,setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc,zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709,tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv,format=yuv420p"
	jellyfin := Capabilities{Encoder: "libx264", HWAccel: "software", ToneMap: true, zscale: true, DoviToneMap: true}
	debian := Capabilities{Encoder: "libx264", HWAccel: "software", ToneMap: true, zscale: true}
	vt := Capabilities{Encoder: "h264_videotoolbox", HWAccel: "videotoolbox", GPUFilters: true, ToneMap: true, zscale: true, gpuToneMap: true, DoviToneMap: true}
	nvenc := Capabilities{Encoder: "h264_nvenc", HWAccel: "nvenc", GPUFilters: true, ToneMap: true, zscale: true, gpuToneMap: true, DoviToneMap: true}
	for _, c := range []struct {
		name       string
		caps       Capabilities
		spec       Spec
		vf, device string
	}{
		{"jellyfin-ffmpeg", jellyfin, dv, tonemapx, ""},
		{"no tonemapx", debian, dv, zscale, ""},
		{"videotoolbox", vt, dv, tonemapx, "-hwaccel videotoolbox -i"},
		{"videotoolbox hdr10", vt, hdr10, "format=nv12|p010le|videotoolbox_vld,hwupload,scale_vt=w=1920:h=1080:color_matrix=bt709:color_primaries=bt709:color_transfer=bt709", "-hwaccel_output_format videotoolbox_vld"},
		{"nvenc", nvenc, dv, tonemapx, ""},
		{"nvenc hdr10", nvenc, hdr10, "", "-hwaccel cuda"},
	} {
		m := NewManager("ffmpeg", t.TempDir(), c.caps, time.Minute, 1, testLog)
		all, vf := argsOf(t, m, c.spec)
		if c.vf != "" && vf != c.vf {
			t.Errorf("%s:\n got %s\nwant %s", c.name, vf, c.vf)
		}
		if c.device != "" && !strings.Contains(all, c.device) {
			t.Errorf("%s: no %q in %s", c.name, c.device, all)
		}
		if c.spec.Video.HDR == "dolbyvision" && strings.Contains(all, "-hwaccel_output_format") {
			t.Errorf("%s: profile 5 on the GPU: %s", c.name, all)
		}
	}
}

// With real ffmpeg: a kept copy carries the record under the right sample
// entry, a stripped one carries none, and profile 5 transcodes.
func TestDolbyVisionSessions(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	ffprobe := filepath.Join(filepath.Dir(ffmpeg), "ffprobe")
	m := testManager(t, ffmpeg, "software")
	t.Cleanup(m.StopAll)
	dv8 := probetest.DolbyVisionClip(t, ffmpeg, probetest.DolbyVision{Profile: 8, Level: 6, Compat: 1, MKV: true})
	dv5 := probetest.DolbyVisionClip(t, ffmpeg, probetest.DolbyVision{Profile: 5, Level: 6, Compat: 0, Entry: "dvh1"})
	run := func(id int64, path string, mode Mode, v VideoPlan) string {
		t.Helper()
		s, err := m.Start(context.Background(), Spec{FileID: id, PlayID: fmt.Sprintf("p%d", id), UserID: 1, Path: path, Mode: mode, Video: v, Audio: AudioPlan{Index: 1, Copy: true}, BurnIn: -1})
		if err != nil {
			t.Fatalf("file %d: %v", id, err)
		}
		select {
		case <-s.done:
		case <-time.After(60 * time.Second):
			t.Fatalf("file %d: ffmpeg did not finish", id)
		}
		if s.Err() != nil {
			t.Fatalf("file %d: %v", id, s.Err())
		}
		out, err := exec.Command(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,codec_tag_string:stream_side_data=dv_profile,dv_bl_signal_compatibility_id",
			"-of", "compact", filepath.Join(s.Dir, "init.mp4")).CombinedOutput()
		if err != nil {
			t.Fatalf("file %d: ffprobe: %v\n%s", id, err, out)
		}
		return strings.TrimSpace(string(out))
	}
	if got := run(1, dv8, ModeRemux, VideoPlan{Codec: "hevc", Copy: true, HDR: "dolbyvision", DVProfile: 8, KeepDV: true}); !strings.Contains(got, "codec_tag_string=hvc1") ||
		!strings.Contains(got, "dv_profile=8") || !strings.Contains(got, "dv_bl_signal_compatibility_id=1") {
		t.Errorf("8.1 kept: %s", got)
	}
	if got := run(2, dv5, ModeRemux, VideoPlan{Codec: "hevc", Copy: true, HDR: "dolbyvision", DVProfile: 5, KeepDV: true}); !strings.Contains(got, "codec_tag_string=dvh1") ||
		!strings.Contains(got, "dv_profile=5") {
		t.Errorf("5 kept: %s", got)
	}
	if got := run(3, dv8, ModeRemux, VideoPlan{Codec: "hevc", Copy: true, HDR: "hdr10", StripDV: true}); !strings.Contains(got, "codec_tag_string=hvc1") || strings.Contains(got, "dv_profile") {
		t.Errorf("8.1 stripped: %s", got)
	}
	if got := run(4, dv5, ModeTranscode, VideoPlan{Codec: "h264", Width: 320, Height: 180, HDR: "dolbyvision", BitDepth: 10, ToneMap: true}); !strings.Contains(got, "codec_name=h264") || strings.Contains(got, "dv_profile") {
		t.Errorf("5 transcoded (dovi tone map %v): %s", m.caps.DoviToneMap, got)
	}
}
