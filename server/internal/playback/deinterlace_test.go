package playback

import (
	"slices"
	"strings"
	"testing"

	"watchmymedia/server/internal/probe"
)

// Interlacing never forces a transcode: a file the client plays as it is
// keeps direct play and remux (and so Original and Original downloads).
// When the video is transcoded for another reason, interlaced frames go
// through the filter Live TV uses.
func TestInterlacedFilesDeinterlaceOnlyWhenTranscoded(t *testing.T) {
	t.Parallel()
	direct := Profile{VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, Containers: []string{"mp4"}}
	remux := Profile{VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, Containers: []string{"mkv"}}
	smaller := direct
	smaller.MaxHeight = 720
	audio := Profile{VideoCodecs: []string{"h264"}, AudioCodecs: []string{"ac3"}, Containers: []string{"mp4"}}
	caps := Capabilities{Encoder: "libx264", Encoders: []string{"libx264"}}
	for _, order := range []string{"tt", "bb", "tb", "bt", "progressive", "unknown", ""} {
		interlaced := order == "tt" || order == "bb" || order == "tb" || order == "bt"
		v := probe.VideoStream{Codec: "h264", Width: 1920, Height: 1080, FieldOrder: order}
		info := &probe.MediaInfo{Video: []probe.VideoStream{v}, Audio: []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2}}}
		for _, c := range []struct {
			name        string
			profile     Profile
			mode        Mode
			deinterlace bool
		}{
			{"direct", direct, ModeDirect, false},
			{"remux", remux, ModeRemux, false},
			{"audio", audio, ModeAudio, false},
			{"transcode", smaller, ModeTranscode, interlaced},
		} {
			d := Decide(info, ".mp4", Request{Profile: c.profile}, nil, caps)
			if d.Mode != c.mode || d.Video.Deinterlace != c.deinterlace {
				t.Fatalf("%s %s: mode %s deinterlace %v, want %s %v (%v)", order, c.name, d.Mode, d.Video.Deinterlace, c.mode, c.deinterlace, d.Reasons)
			}
			if slices.ContainsFunc(d.Reasons, func(r string) bool { return strings.Contains(r, "interlac") }) {
				t.Fatalf("%s %s: interlacing given as a reason: %v", order, c.name, d.Reasons)
			}
			m := &Manager{}
			args := strings.Join(m.args(Spec{Mode: d.Mode, Video: d.Video, Audio: d.Audio, BurnIn: -1, caps: &caps}, t.TempDir()), " ")
			if strings.Contains(args, BroadcastDeinterlace) != c.deinterlace {
				t.Fatalf("%s %s: filter %v in %s", order, c.name, c.deinterlace, args)
			}
		}
	}
}

// Deinterlacing keeps the transcode on the CPU path, where the filter is:
// the GPU chains have no bwdif.
func TestDeinterlacingTranscodeSkipsTheGPUFilters(t *testing.T) {
	t.Parallel()
	v := probe.VideoStream{Codec: "h264", Width: 1920, Height: 1080, FieldOrder: "tt"}
	info := &probe.MediaInfo{Video: []probe.VideoStream{v}, Audio: []probe.AudioStream{{Index: 1, Codec: "aac", Channels: 2}}}
	caps := Capabilities{HWAccel: "videotoolbox", Encoder: "h264_videotoolbox", Encoders: []string{"h264_videotoolbox", "libx264"}, GPUFilters: true}
	d := Decide(info, ".mp4", Request{Profile: Profile{VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, Containers: []string{"mp4"}, MaxHeight: 720}}, nil, caps)
	if d.Mode != ModeTranscode || !d.Video.Deinterlace {
		t.Fatalf("%+v", d)
	}
	m := &Manager{}
	args := strings.Join(m.args(Spec{Mode: d.Mode, Video: d.Video, Audio: d.Audio, BurnIn: -1, caps: &caps}, t.TempDir()), " ")
	if !strings.Contains(args, BroadcastDeinterlace) || strings.Contains(args, "scale_vt") {
		t.Fatalf("deinterlacing transcode: %s", args)
	}
}
