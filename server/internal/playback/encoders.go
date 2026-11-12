package playback

import (
	"strconv"
	"strings"
)

// videoEncoder is a video encoder a transcode can use.
type videoEncoder struct {
	name string
	// hwaccel is the hardware path it runs on, or "software".
	hwaccel string
	codec   string // h264 or hevc
	// args tune it for a capped bit rate and its buffer (bits per second)
	// and, for HEVC, a profile (main, or main10 for HDR).
	args func(rate, buf, profile string) []string
}

// videoEncoders are the encoders Detect looks for, in that order.
var videoEncoders = []videoEncoder{
	{"libx264", "software", "h264", func(rate, buf, _ string) []string {
		return []string{"-preset", "veryfast", "-crf", "22", "-maxrate", rate, "-bufsize", buf, "-profile:v", "high"}
	}},
	{"h264_videotoolbox", "videotoolbox", "h264", func(rate, buf, _ string) []string {
		return []string{"-b:v", rate, "-maxrate", rate, "-bufsize", buf, "-profile:v", "high", "-realtime", "true"}
	}},
	{"hevc_videotoolbox", "videotoolbox", "hevc", func(rate, buf, profile string) []string {
		return []string{"-b:v", rate, "-maxrate", rate, "-bufsize", buf, "-profile:v", profile, "-tag:v", "hvc1", "-realtime", "true"}
	}},
	{"h264_nvenc", "nvenc", "h264", func(rate, buf, _ string) []string {
		return []string{"-preset", "p4", "-rc", "vbr", "-b:v", rate, "-maxrate", rate, "-bufsize", buf, "-profile:v", "high"}
	}},
	{"hevc_nvenc", "nvenc", "hevc", func(rate, buf, profile string) []string {
		return []string{"-preset", "p4", "-rc", "vbr", "-b:v", rate, "-maxrate", rate, "-bufsize", buf, "-profile:v", profile, "-tag:v", "hvc1"}
	}},
	{"h264_qsv", "qsv", "h264", func(rate, buf, _ string) []string {
		return []string{"-preset", "veryfast", "-b:v", rate, "-maxrate", rate, "-bufsize", buf}
	}},
	{"hevc_qsv", "qsv", "hevc", func(rate, buf, profile string) []string {
		return []string{"-preset", "veryfast", "-b:v", rate, "-maxrate", rate, "-bufsize", buf, "-profile:v", profile, "-tag:v", "hvc1"}
	}},
	{"h264_vaapi", "vaapi", "h264", func(rate, buf, _ string) []string {
		return []string{"-b:v", rate, "-maxrate", rate, "-bufsize", buf}
	}},
	{"hevc_vaapi", "vaapi", "hevc", func(rate, buf, profile string) []string {
		return []string{"-b:v", rate, "-maxrate", rate, "-bufsize", buf, "-profile:v", profile, "-tag:v", "hvc1"}
	}},
	{"h264_amf", "amf", "h264", func(rate, buf, _ string) []string {
		return []string{"-usage", "transcoding", "-quality", "speed", "-rc", "vbr_peak", "-b:v", rate, "-maxrate", rate, "-bufsize", buf, "-profile:v", "high"}
	}},
	{"hevc_amf", "amf", "hevc", func(rate, buf, profile string) []string {
		return []string{"-usage", "transcoding", "-quality", "speed", "-rc", "vbr_peak", "-b:v", rate, "-maxrate", rate, "-bufsize", buf, "-profile:v", profile, "-tag:v", "hvc1"}
	}},
}

// findEncoder looks an encoder up by name.
func findEncoder(name string) (videoEncoder, bool) {
	for _, e := range videoEncoders {
		if e.name == name {
			return e, true
		}
	}
	return videoEncoder{}, false
}

// encoderOn is the encoder for codec on the hardware path hwaccel.
func encoderOn(hwaccel, codec string) (videoEncoder, bool) {
	for _, e := range videoEncoders {
		if e.hwaccel == hwaccel && e.codec == codec {
			return e, true
		}
	}
	return videoEncoder{}, false
}

// hwaccelOf names the hardware path an encoder belongs to. A name the
// table lacks still says its path by its suffix (h264_nvenc's _nvenc).
func hwaccelOf(encoder string) string {
	if e, ok := findEncoder(encoder); ok {
		return e.hwaccel
	}
	for _, e := range videoEncoders {
		if e.hwaccel != "software" && strings.HasSuffix(encoder, "_"+e.hwaccel) {
			return e.hwaccel
		}
	}
	return "software"
}

// H264RateArgs shares the encoder-specific rate controls with live inputs.
// The caller chooses a detected H.264 encoder and owns input/filter setup.
func H264RateArgs(name string, bitrate int64) []string {
	e, ok := findEncoder(name)
	if !ok || e.codec != "h264" {
		return nil
	}
	return e.args(strconv.FormatInt(bitrate, 10), strconv.FormatInt(bitrate*2, 10), "high")
}
