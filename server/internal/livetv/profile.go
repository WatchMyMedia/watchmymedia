package livetv

import (
	"errors"
	"slices"

	"watchmymedia/server/internal/playback"
)

var ErrProfile = errors.New("invalid Live TV device profile")

// LiveProfile describes only the live HLS path. File containers do not imply
// support for that container as an HLS segment format.
type LiveProfile struct {
	SegmentFormats   []string `json:"segmentFormats" nullable:"false" required:"false" doc:"HLS segment formats the player accepts: mpegts or fmp4."`
	VideoCodecs      []string `json:"videoCodecs" nullable:"false" required:"false"`
	AudioCodecs      []string `json:"audioCodecs" nullable:"false" required:"false"`
	HDRTypes         []string `json:"hdrTypes" nullable:"false" required:"false" doc:"HDR formats the current display can show, such as hdr10 or hlg."`
	MaxHeight        int      `json:"maxHeight,omitempty" required:"false"`
	MaxBitrate       int64    `json:"maxBitrate,omitempty" required:"false" doc:"Sustained total bits per second; zero means the client has not measured it."`
	MaxAudioChannels int      `json:"maxAudioChannels,omitempty" required:"false"`
	Interlaced       bool     `json:"interlaced" required:"false" doc:"The live player can present interlaced source without server deinterlacing."`
}

// SourceInfo is an inspection of bytes from the shared capture, never an
// assertion based on a tuner's channel name or guide metadata.
type SourceInfo struct {
	VideoCodec    string
	AudioCodec    string
	Width         int
	Height        int
	Bitrate       int64
	AudioChannels int
	AudioPresent  bool
	Interlaced    bool
	ScanKnown     bool
	HDRType       string
	Generation    int64
	SquarePixels  bool
}

type LivePlan struct {
	Mode          string // original, remux, audio or video
	Reason        string // why the mode: in video mode, why the picture is converted
	SegmentFormat string // mpegts or fmp4
	sourceAudio   string
	hdrType       string
	// copyAudio is video mode's choice to write the broadcast's audio as it
	// is, as original and remux always do and audio mode never does.
	copyAudio bool
	// audioChannels is what a conversion may keep (liveAudioChannels); video
	// mode keeps more than two only at liveSurroundVideoKbps or above.
	audioChannels int
}

// Live audio conversions are AAC: stereo at 192 kbps, as before, or up to 5.1
// at 64 kbps a channel (PLAN-AUDIO.md §3.6). A converted picture below
// liveSurroundVideoKbps is a play kept small for a slow link, which keeps
// stereo (PLAN-AUDIO.md decision 8).
const (
	liveStereoAudioKbps     = 192
	liveAudioKbpsPerChannel = 64
	liveSurroundVideoKbps   = 4000
)

func validateLiveProfile(p *LiveProfile) error {
	if p == nil {
		return nil
	}
	if len(p.SegmentFormats) == 0 || len(p.SegmentFormats) > 4 || len(p.VideoCodecs) > 16 || len(p.AudioCodecs) > 16 || len(p.HDRTypes) > 8 ||
		p.MaxHeight < 0 || p.MaxHeight > 4320 || p.MaxBitrate < 0 || p.MaxBitrate > 1_000_000_000_000 || p.MaxAudioChannels < 0 || p.MaxAudioChannels > 32 {
		return ErrProfile
	}
	for _, f := range p.SegmentFormats {
		if f != "mpegts" && f != "fmp4" {
			return ErrProfile
		}
	}
	for _, list := range [][]string{p.VideoCodecs, p.AudioCodecs, p.HDRTypes} {
		for _, value := range list {
			if len(value) == 0 || len(value) > 32 {
				return ErrProfile
			}
		}
	}
	return nil
}

// chooseLivePlan stays conservative when any required source fact is absent.
// The quality cap is a total-bitrate bound even when the decoder could copy.
func chooseLivePlan(profile *LiveProfile, source SourceInfo, quality string) LivePlan {
	fallback := LivePlan{Mode: "video", Reason: "compatibility", SegmentFormat: "mpegts", hdrType: source.HDRType}
	if profile == nil {
		fallback.Reason = "legacy_client"
		return fallback
	}
	if !slices.Contains(profile.SegmentFormats, "mpegts") && slices.Contains(profile.SegmentFormats, "fmp4") {
		fallback.SegmentFormat = "fmp4"
	}
	if source.VideoCodec == "" || source.Width <= 0 || source.Height <= 0 || source.Bitrate <= 0 || !source.ScanKnown ||
		!source.AudioPresent || source.AudioCodec == "" || source.AudioChannels <= 0 {
		fallback.Reason = "source_unknown"
		return fallback
	}
	if source.HDRType == "unknown" {
		fallback.Reason = "source_unknown"
		return fallback
	}
	// The source is known from here on. A picture converted for any of the
	// reasons below keeps the broadcast's audio when the device takes it, so
	// an ATSC 1.0 channel's MPEG-2 doesn't cost an app its AC-3 5.1.
	fallback.sourceAudio = source.AudioCodec
	fallback.copyAudio = liveAudioFits(profile, source, fallback.SegmentFormat)
	if !fallback.copyAudio {
		fallback.audioChannels = liveAudioChannels(profile, source)
	}
	if source.HDRType != "" && !slices.Contains(profile.HDRTypes, source.HDRType) {
		fallback.Reason = "hdr_unsupported"
		return fallback
	}
	if source.Interlaced && !profile.Interlaced {
		fallback.Reason = "deinterlace_required"
		return fallback
	}
	if profile.MaxHeight > 0 && source.Height > profile.MaxHeight || source.Height > playback.StepByID(quality).MaxHeight {
		fallback.Reason = "resolution_limit"
		return fallback
	}
	limit := playback.StepByID(quality).MaxBitrate
	if profile.MaxBitrate > 0 && profile.MaxBitrate < limit {
		limit = profile.MaxBitrate
	}
	// Broadcast and HLS framing vary. Keep 25% headroom before promising a
	// copy at the selected cap; lower caps force conversion.
	if source.Bitrate+source.Bitrate/4 > limit {
		fallback.Reason = "bitrate_limit"
		return fallback
	}
	if !slices.Contains(profile.VideoCodecs, source.VideoCodec) {
		fallback.Reason = "video_unsupported"
		return fallback
	}
	format := ""
	if slices.Contains(profile.SegmentFormats, "mpegts") {
		format = "mpegts"
	} else if slices.Contains(profile.SegmentFormats, "fmp4") && (source.VideoCodec == "h264" || source.VideoCodec == "hevc") {
		format = "fmp4"
	}
	if format == "" {
		fallback.Reason = "segment_format_unsupported"
		return fallback
	}
	if !liveAudioFits(profile, source, format) {
		return LivePlan{Mode: "audio", Reason: "audio_unsupported", SegmentFormat: format, audioChannels: liveAudioChannels(profile, source)}
	}
	if format == "fmp4" {
		return LivePlan{Mode: "remux", Reason: "compatible_source", SegmentFormat: format, sourceAudio: source.AudioCodec}
	}
	return LivePlan{Mode: "original", Reason: "compatible_source", SegmentFormat: format}
}

// liveAudioFits says whether the broadcast's audio can be written as it is:
// the device lists its codec and takes its channels, and fMP4 segments can
// carry it.
func liveAudioFits(profile *LiveProfile, source SourceInfo, format string) bool {
	return source.AudioPresent && source.AudioCodec != "" && source.AudioChannels > 0 &&
		slices.Contains(profile.AudioCodecs, source.AudioCodec) &&
		(profile.MaxAudioChannels <= 0 || source.AudioChannels <= profile.MaxAudioChannels) &&
		(format != "fmp4" || fmp4Audio(source.AudioCodec))
}

// liveAudioChannels is how many channels a conversion keeps: up to 5.1 for a
// device that says it takes six channels or more, else stereo. A profile that
// sends no limit (the web's and the Cast receiver's) stays stereo until AAC
// 5.1 in MPEG-TS is checked on them (PLAN-AUDIO.md §3.6).
func liveAudioChannels(profile *LiveProfile, source SourceInfo) int {
	if profile == nil || profile.MaxAudioChannels < 6 || source.AudioChannels <= 2 {
		return 2
	}
	return min(source.AudioChannels, 6)
}

func fmp4Audio(codec string) bool {
	return codec == "aac" || codec == "ac3" || codec == "eac3" || codec == "mp3"
}
