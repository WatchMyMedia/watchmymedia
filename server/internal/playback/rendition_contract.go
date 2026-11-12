package playback

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
)

// renditionContract names encoded bytes, never a viewer or a playback URL.
// The version prevents a future encoder/timeline change from finding older
// cached bytes. A source change must produce a different identity before any
// new segment joins an existing rendition.
func renditionContract(spec Spec) (string, error) {
	if spec.VOD == nil || spec.Path == "" || spec.Growing != nil {
		return "", errors.New("rendition cache requires a finite file")
	}
	st, err := os.Stat(spec.Path)
	if err != nil {
		return "", err
	}
	if !st.Mode().IsRegular() {
		return "", errors.New("rendition source is not a regular file")
	}
	var burnInSize, burnInModifiedNs int64
	if spec.BurnInFile != "" {
		burnIn, err := os.Stat(spec.BurnInFile)
		if err != nil {
			return "", err
		}
		if !burnIn.Mode().IsRegular() {
			return "", errors.New("burn-in source is not a regular file")
		}
		burnInSize, burnInModifiedNs = burnIn.Size(), burnIn.ModTime().UnixNano()
	}
	// Function-valued subtitle and I-frame suppliers are not transferable
	// between viewers. Their advertised text/thumbnail metadata is included,
	// but A0 never shares the supplier itself or any per-viewer playlist.
	var iframe *struct {
		Bandwidth int64
		Codecs    string
		Width     int
		Height    int
	}
	if f := spec.VOD.IFrames; f != nil {
		iframe = &struct {
			Bandwidth int64
			Codecs    string
			Width     int
			Height    int
		}{f.Bandwidth, f.Codecs, f.Width, f.Height}
	}
	var caps *struct {
		FFmpeg, Encoder, HEVCEncoder, HWAccel string
		Device                                *int
		GPUFilters, ToneMap, DoviToneMap      bool
		ZScale, GPUToneMap, DoviStrip         bool
		Detected                              int64
	}
	if c := spec.caps; c != nil {
		caps = &struct {
			FFmpeg, Encoder, HEVCEncoder, HWAccel string
			Device                                *int
			GPUFilters, ToneMap, DoviToneMap      bool
			ZScale, GPUToneMap, DoviStrip         bool
			Detected                              int64
		}{c.FFmpeg, c.Encoder, c.HEVCEncoder, c.HWAccel, c.Device, c.GPUFilters,
			c.ToneMap, c.DoviToneMap, c.zscale, c.gpuToneMap, c.doviStrip, c.Detected.UnixNano()}
	}
	contract := struct {
		Version            int
		FileID             int64
		Path               string
		Size               int64
		ModifiedNs         int64
		Mode               Mode
		Video              VideoPlan
		KeepDV             bool
		StripDV            bool
		Deinterlace        bool
		Audio              AudioPlan
		MaxBitrate         int64
		BurnIn             int
		BurnInImage        bool
		BurnInSub          int
		BurnInFile         string
		BurnInSize         int64
		BurnInModifiedNs   int64
		DurationMs         int64
		Keyframes          []int64
		FrameRate          float64
		ConstantFrameRate  bool
		Bandwidth          int64
		VideoRange         string
		Codecs             string
		SupplementalCodecs string
		SeparateAudio      bool
		AudioChannels      int
		AudioLanguage      string
		Subtitles          []VODSubtitle
		IFrames            any
		Capabilities       any
	}{
		Version: 2, FileID: spec.FileID, Path: spec.Path, Size: st.Size(), ModifiedNs: st.ModTime().UnixNano(),
		Mode: spec.Mode, Video: spec.Video, KeepDV: spec.Video.KeepDV, StripDV: spec.Video.StripDV,
		Deinterlace: spec.Video.Deinterlace, Audio: spec.Audio, MaxBitrate: spec.MaxBitrate,
		BurnIn: spec.BurnIn, BurnInImage: spec.BurnInImage, BurnInSub: spec.BurnInSub, BurnInFile: spec.BurnInFile,
		BurnInSize: burnInSize, BurnInModifiedNs: burnInModifiedNs,
		DurationMs: spec.VOD.DurationMs, Keyframes: spec.VOD.Keyframes, FrameRate: spec.VOD.FrameRate,
		ConstantFrameRate: spec.ReserveSlot && !spec.Video.Copy && spec.VOD.FrameRate > 0,
		Bandwidth:         spec.VOD.Bandwidth, VideoRange: spec.VOD.VideoRange, Codecs: spec.VOD.Codecs,
		SupplementalCodecs: spec.VOD.SupplementalCodecs, SeparateAudio: spec.VOD.SeparateAudio,
		AudioChannels: spec.VOD.AudioChannels, AudioLanguage: spec.VOD.AudioLanguage,
		Subtitles: spec.VOD.Subtitles, IFrames: iframe, Capabilities: caps,
	}
	b, err := json.Marshal(contract)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:]), nil
}
