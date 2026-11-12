package playback

import (
	"fmt"
	"strings"

	"watchmymedia/server/internal/probe"
)

// HLSCodecs is the CODECS attribute for a VOD session's variant: RFC 6381
// strings for the video and audio the decision sends. Without one a player
// guesses, and Shaka (the Cast receiver's HLS player) guesses H.264 and AAC,
// so every HEVC cast failed as soon as it buffered. It is "" when a stream
// has no string here: no attribute is better than a wrong one.
func HLSCodecs(info *probe.MediaInfo, d Decision) string {
	if len(info.Video) == 0 {
		return ""
	}
	v := videoCodecs(info.Video[0], d.Video)
	if v == "" {
		return ""
	}
	if d.Audio.Codec == "" {
		return v
	}
	a := audioCodecs(info, d.Audio)
	if a == "" {
		return ""
	}
	return v + "," + a
}

func videoCodecs(src probe.VideoStream, v VideoPlan) string {
	w, h, fps := v.Width, v.Height, src.FrameRate
	if w <= 0 || h <= 0 {
		w, h = src.Width, src.Height
	}
	if fps <= 0 {
		fps = 30
	}
	if !v.Copy {
		// What encoderArgs asks for: H.264 High, or HEVC Main (Main 10
		// when the transcode keeps HDR).
		switch v.Codec {
		case "h264":
			return avcCodecs("High", w, h, fps)
		case "hevc":
			return hevcCodecs(v.HDR != "" && !v.ToneMap, w, h, fps)
		}
		return ""
	}
	switch src.Codec {
	case "h264":
		return avcCodecs(src.Profile, w, h, fps)
	case "hevc":
		switch {
		case v.KeepDV && src.DVProfile == 5:
			// Under dvh1, with the record's level (Apple's HLS authoring
			// spec); no attribute when an old probe lacks the level.
			if src.DVLevel <= 0 {
				return ""
			}
			return fmt.Sprintf("dvh1.05.%02d", src.DVLevel)
		case v.KeepDV, v.StripDV:
			// Profile 8 kept, or a base layer sent alone: HEVC under hvc1,
			// with Dolby Vision in SUPPLEMENTAL-CODECS when kept.
		case src.HDR == "dolbyvision" && src.DVProfile != 8:
			// Copied as it always was for older apps' profiles: Dolby
			// Vision 8 carries an HEVC Main 10 base layer; the other
			// profiles are not plain HEVC.
			return ""
		}
		switch src.Profile {
		case "Main":
			return hevcCodecs(false, w, h, fps)
		case "Main 10":
			return hevcCodecs(true, w, h, fps)
		case "":
			return hevcCodecs(src.BitDepth > 8, w, h, fps)
		}
	case "av1":
		if src.HDR == "dolbyvision" && !v.StripDV {
			return ""
		}
		return av1Codecs(src.Profile, src.BitDepth, w, h, fps)
	}
	return ""
}

// HLSSupplementalCodecs is the master playlist's SUPPLEMENTAL-CODECS for a
// copy that keeps Dolby Vision 8: the Dolby Vision format beside the HEVC
// base layer that CODECS names, branded for that base (db1p HDR10, db4h
// HLG, db2g SDR), as Apple's HLS authoring spec has it. It is "" for
// everything else, and when an old probe lacks the record's level.
func HLSSupplementalCodecs(info *probe.MediaInfo, d Decision) string {
	if len(info.Video) == 0 || !d.Video.Copy || !d.Video.KeepDV {
		return ""
	}
	src := info.Video[0]
	if src.Codec != "hevc" || src.DVProfile != 8 || src.DVLevel <= 0 {
		return ""
	}
	base, _ := baseLayer(src)
	brand := map[string]string{"hdr10": "db1p", "hlg": "db4h", "": "db2g"}[base]
	return fmt.Sprintf("dvh1.08.%02d/%s", src.DVLevel, brand)
}

// HLSVideoRange is the master playlist's VIDEO-RANGE for what is sent:
// SDR, PQ or HLG. Dolby Vision 8 kept takes its base layer's (HLG for
// 8.4); profile 5 is PQ.
func HLSVideoRange(info *probe.MediaInfo, d Decision) string {
	v := d.Video
	if v.HDR == "" || v.ToneMap {
		return "SDR"
	}
	kind := v.HDR
	if kind == "dolbyvision" && v.Copy && v.KeepDV && len(info.Video) > 0 && info.Video[0].DVProfile == 8 {
		kind, _ = baseLayer(info.Video[0])
	}
	switch kind {
	case "":
		return "SDR"
	case "hlg":
		return "HLG"
	}
	return "PQ"
}

// avcProfiles are profile_idc and the constraint flags byte, as hex, by
// ffprobe's profile name.
var avcProfiles = map[string]string{
	"Constrained Baseline":  "42E0",
	"Baseline":              "4200",
	"Main":                  "4D40",
	"High":                  "6400",
	"High 10":               "6E00",
	"High 4:2:2":            "7A00",
	"High 4:4:4 Predictive": "F400",
}

// avcLevels are H.264's levels (Table A-1): level_idc, the largest frame in
// macroblocks and macroblocks per second.
var avcLevels = []struct{ idc, frame, rate int }{
	{30, 1620, 40500}, {31, 3600, 108000}, {32, 5120, 216000}, {40, 8192, 245760}, {42, 8704, 522240},
	{50, 22080, 589824}, {51, 36864, 983040}, {52, 36864, 2073600},
	{60, 139264, 4177920}, {61, 139264, 8355840}, {62, 139264, 16711680},
}

func avcCodecs(profile string, w, h int, fps float64) string {
	p, ok := avcProfiles[profile]
	if !ok {
		return ""
	}
	mbs := ((w + 15) / 16) * ((h + 15) / 16)
	idc := avcLevels[len(avcLevels)-1].idc
	for _, l := range avcLevels {
		if mbs <= l.frame && float64(mbs)*fps <= float64(l.rate) {
			idc = l.idc
			break
		}
	}
	return fmt.Sprintf("avc1.%s%02X", p, idc)
}

// hevcLevels are HEVC's levels (Table A.8): level_idc, the largest picture
// in luma samples and luma samples per second.
var hevcLevels = []struct {
	idc    int
	ps, sr float64
}{
	{90, 552960, 16588800}, {93, 983040, 33177600}, {120, 2228224, 66846720}, {123, 2228224, 133693440},
	{150, 8912896, 267386880}, {153, 8912896, 534773760}, {156, 8912896, 1069547520},
	{180, 35651584, 1069547520}, {183, 35651584, 2139095040}, {186, 35651584, 4278190080},
}

// hevcCodecs is Main or Main 10, Main tier, progressive and frame-only
// (the constraint byte B0), tagged hvc1 as the session writes it.
func hevcCodecs(tenBit bool, w, h int, fps float64) string {
	ps := float64(w) * float64(h)
	idc := hevcLevels[len(hevcLevels)-1].idc
	for _, l := range hevcLevels {
		if ps <= l.ps && ps*fps <= l.sr {
			idc = l.idc
			break
		}
	}
	if tenBit {
		return fmt.Sprintf("hvc1.2.4.L%d.B0", idc)
	}
	return fmt.Sprintf("hvc1.1.6.L%d.B0", idc)
}

// av1Levels are AV1's levels (Annex A.3): seq_level_idx, the largest
// picture in samples and samples per second.
var av1Levels = []struct {
	idx    int
	ps, sr float64
}{
	{0, 147456, 4423680}, {1, 278784, 8363520}, {4, 665856, 19975680}, {5, 1065024, 31950720},
	{8, 2359296, 70778880}, {9, 2359296, 141557760}, {12, 8912896, 267386880}, {13, 8912896, 534773760},
	{14, 8912896, 1069547520}, {16, 35651584, 1069547520}, {17, 35651584, 2139095040}, {18, 35651584, 4278190080},
}

var av1Profiles = map[string]int{"Main": 0, "": 0, "High": 1, "Professional": 2}

func av1Codecs(profile string, bitDepth, w, h int, fps float64) string {
	n, ok := av1Profiles[profile]
	if !ok {
		return ""
	}
	if bitDepth <= 0 {
		bitDepth = 8
	}
	ps := float64(w) * float64(h)
	idx := av1Levels[len(av1Levels)-1].idx
	for _, l := range av1Levels {
		if ps <= l.ps && ps*fps <= l.sr {
			idx = l.idx
			break
		}
	}
	return fmt.Sprintf("av01.%d.%02dM.%02d", n, idx, bitDepth)
}

func audioCodecs(info *probe.MediaInfo, a AudioPlan) string {
	if !a.Copy {
		// The transcoder writes AAC-LC (see args).
		if a.Codec == "aac" {
			return "mp4a.40.2"
		}
		return ""
	}
	profile := ""
	for _, s := range info.Audio {
		if s.Index == a.Index {
			profile = s.Profile
		}
	}
	switch a.Codec {
	case "aac":
		switch {
		case strings.EqualFold(profile, "HE-AACv2"):
			return "mp4a.40.29"
		case strings.EqualFold(profile, "HE-AAC"):
			return "mp4a.40.5"
		}
		return "mp4a.40.2"
	case "ac3":
		return "ac-3"
	case "eac3":
		return "ec-3"
	case "mp3":
		return "mp4a.40.34"
	case "opus":
		return "Opus"
	case "flac":
		return "fLaC"
	case "alac":
		return "alac"
	}
	return ""
}
