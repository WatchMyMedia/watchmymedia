package api

import (
	"context"
	"slices"

	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// An experimental family never forces a transcode of a cheaper supported
// original/remux and never turns a fixed selection into Automatic. All members
// reserve capacity before publication; refusal returns to ordinary preparation.
func startAdaptiveVOD(ctx context.Context, deps Deps, req playback.Request, info *probe.MediaInfo, spec playback.Spec, budget int64) (*playback.Session, *playback.AdaptiveStream) {
	if !deps.AdaptiveHLS || !req.Adaptive || spec.PreservePrevious || req.Quality != playback.QualityAuto ||
		spec.Mode != playback.ModeTranscode || spec.Video.Deinterlace || spec.Video.Copy || spec.Video.HDR != "" || spec.Video.ToneMap ||
		spec.BurnIn >= 0 || spec.BurnInFile != "" || spec.VOD == nil || len(spec.VOD.Subtitles) != 0 ||
		len(info.Video) != 1 || info.Video[0].HDR != "" || info.Video[0].DVProfile != 0 ||
		spec.VOD.FrameRate <= 0 || spec.VOD.FrameRate > 30 || spec.Audio.Codec == "" ||
		!slices.Contains(req.Profile.VideoCodecs, "h264") || !slices.Contains(req.Profile.AudioCodecs, "aac") {
		return nil, nil
	}
	h := min(spec.Video.Height, 720)
	if spec.Video.Width <= 0 || h < 64 {
		return nil, nil
	}
	h = min(h, 1280*spec.Video.Height/spec.Video.Width) / 2 * 2
	if h < 64 {
		return nil, nil
	}
	width := func(height int) int { return max(2, height*spec.Video.Width/spec.Video.Height/2*2) }
	if spec.MaxBitrate > 0 {
		budget = min(budget, spec.MaxBitrate)
	}
	rate := min(int64(1_872_000), playback.VideoRateWithin(budget))
	if rate < 300_000 {
		return nil, nil
	}
	high := spec
	high.Video = playback.VideoPlan{Codec: "h264", Encoder: "libx264", Width: width(h), Height: h, BitDepth: 8}
	high.Audio = playback.AudioPlan{Index: spec.Audio.Index, Codec: "aac", Channels: 2}
	if spec.Audio.Channels == 1 || req.Profile.MaxAudioChannels == 1 {
		high.Audio.Channels = 1
	}
	high.MaxBitrate = rate
	opts := *spec.VOD
	opts.IFrames, opts.Subtitles = nil, nil
	opts.VideoRange, opts.Codecs = "SDR", "avc1.64001f,mp4a.40.2"
	opts.SeparateAudio, opts.AudioChannels = true, high.Audio.Channels
	opts.Bandwidth = playback.PeakBandwidth(rate)
	high.VOD = &opts
	low := high
	low.Video.Height = h / 4 * 2
	low.Video.Width = width(low.Video.Height)
	low.MaxBitrate = rate / 3
	lowOpts := opts
	lowOpts.Bandwidth = playback.PeakBandwidth(low.MaxBitrate)
	low.VOD = &lowOpts
	previous := deps.Playback.Sessions()
	s, err := deps.Playback.StartVODLadder(ctx, low, high)
	if err != nil {
		return nil, nil
	}
	// A family is never a preserved upgrade (refused above): the play's
	// earlier output goes once the family is admitted.
	for _, old := range previous {
		if old.PlayID == spec.PlayID && old.UserID == spec.UserID {
			deps.Playback.Stop(old.ID)
		}
	}
	ids := s.AdaptiveMemberIDs()
	return s, &playback.AdaptiveStream{Renditions: []playback.AdaptiveRendition{
		{SessionID: ids[0], Width: low.Video.Width, Height: low.Video.Height, Bitrate: lowOpts.Bandwidth}, {SessionID: ids[1], Width: high.Video.Width, Height: high.Video.Height, Bitrate: opts.Bandwidth},
	}}
}
