// Package playback picks how to deliver a file to a client and runs the
// ffmpeg sessions that deliver it. The cheapest mode that works wins:
// direct play, then remux, then audio-only transcode, then full transcode.
package playback

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/probe"
)

type Mode string

// Transcodes stop at 1080p unless the hardware encoder proved it can do 4K
// faster than real time (Capabilities.MaxHeight); clients that can show 4K
// usually get the original anyway.
const defaultMaxTranscodeHeight = 1080

const (
	ModeDirect    Mode = "direct"    // original file over HTTP range
	ModeRemux     Mode = "remux"     // -c copy into fMP4/HLS
	ModeAudio     Mode = "audio"     // copy video, transcode audio
	ModeTranscode Mode = "transcode" // re-encode video (and audio if needed)
)

// Profile is what a client can play. The web client builds it from
// MediaSource.isTypeSupported and mediaCapabilities. Native apps build theirs
// at launch (PLAN-CLIENTS.md §4.4 and §5.4) and ask for VOD playlists:
//
//   - Apple TV and iOS (AVPlayer): containers mp4; videoCodecs h264, hevc,
//     hevc10 when the device can show HDR, av1 where it decodes it; audio aac,
//     mp3, flac, alac, ac3, eac3 (never dts or truehd, which AVPlayer cannot
//     decode); subtitleFormats webvtt only (ASS is converted, image subtitles
//     are burned in); maxAudioChannels 8 on an Apple TV set to Dolby, else 2;
//     4K and hdr where the device and display allow.
//   - Android TV and phones (ExoPlayer): containers mkv, mp4, webm, mpegts;
//     video and audio codecs from MediaCodecList, with ac3, eac3, dts and
//     truehd only where they decode or pass through over HDMI; subtitleFormats
//     webvtt and ass; size and hdr from the display.
//
// From apiLevel 8 (PLAN-HDR-PROFILE.md) apps also send hdrTypes and the
// dovi keys, and offer hevc10 with any Main 10 decoder, whatever the screen.
// HDR is copied only to a screen that shows it, for every profile; a
// profile with hdrTypes also has Dolby Vision classified by its base layer
// (classify).
type Profile struct {
	Name string `json:"name,omitempty" example:"web"`
	// Containers the client can play directly: mp4, webm, mkv, mpegts.
	Containers []string `json:"containers" nullable:"false" required:"false"`
	// VideoCodecs: h264, hevc, hevc10 (10-bit HEVC), av1, vp9, mpeg4,
	// mpeg2video, and dovi5, dovi7, dovi8 (Dolby Vision profiles the device
	// decodes and its display shows; from apiLevel 8).
	VideoCodecs []string `json:"videoCodecs" nullable:"false" required:"false"`
	// AudioCodecs: aac, opus, mp3, ac3, eac3, flac, vorbis, dts, truehd, pcm.
	AudioCodecs      []string `json:"audioCodecs" nullable:"false" required:"false"`
	MaxWidth         int      `json:"maxWidth,omitempty"`
	MaxHeight        int      `json:"maxHeight,omitempty"`
	MaxBitrate       int64    `json:"maxBitrate,omitempty" doc:"Bits per second; 0 means unlimited."`
	MaxAudioChannels int      `json:"maxAudioChannels,omitempty" doc:"0 means any."`
	HDR              bool     `json:"hdr" required:"false" doc:"True when the client can display HDR. Without hdrTypes, HDR video is copied only when this is true; otherwise transcodes tone-map to SDR."`
	// HDRTypes is a list of plain strings, not an enum: generated clients
	// refuse enum values they do not know.
	HDRTypes []string `json:"hdrTypes,omitempty" nullable:"false" required:"false" doc:"The HDR the screen shows: hdr10, hlg, dovi. Sending it, even empty, means the profile follows PLAN-HDR-PROFILE: HDR is copied only when its kind is listed, and Dolby Vision needs its dovi key. From apiLevel 8; older servers reject it."`
	// SubtitleFormats the client renders itself: webvtt, ass.
	SubtitleFormats []string `json:"subtitleFormats" nullable:"false" required:"false"`
	SeparateAudio   bool     `json:"separateAudio,omitempty" required:"false" doc:"VOD playlists carry the audio as its own rendition (EXT-X-MEDIA TYPE=AUDIO, audio.m3u8) instead of muxed with the video. Cast receivers ask for it: they take AC-3 and E-AC-3 only in an audio SourceBuffer of their own. From apiLevel 4."`
	// SelectsAudioTrack: ExoPlayer and AVPlayer can switch a direct-play
	// file's audio themselves; a browser can't, so for it a track other than
	// the file's default needs a remux that carries only that track.
	SelectsAudioTrack bool `json:"selectsAudioTrack,omitempty" required:"false" doc:"The player chooses among a direct-play file's audio tracks itself and starts on audio.index, so a track other than the file's default stays direct play instead of a remux. From apiLevel 21; older servers reject it."`
}

// Request is a playback request for one file.
type Request struct {
	Adaptive bool    `json:"adaptive,omitempty" required:"false" doc:"API level 27: accept an admitted adaptive HLS family for Automatic. Experimental server opt-in required; unsupported requests retain single rendition playback."`
	Profile  Profile `json:"profile"`
	// AudioIndex selects an audio stream by its ffprobe index; omit for the default track.
	AudioIndex *int `json:"audioIndex,omitempty"`
	// SubtitleID selects a subtitle track and takes precedence over the
	// person's defaults. Omit it with SubtitleMode auto to use preferences.
	SubtitleID   string `json:"subtitleId,omitempty"`
	SubtitleMode string `json:"subtitleMode,omitempty" enum:"auto,off" required:"false" doc:"From API level 18: auto uses the person's subtitle preferences; off explicitly disables them. A subtitleId takes precedence. Omitted, older players keep their existing behavior."`
	// BurnIn forces the selected subtitle to be burned into the video.
	BurnIn  bool   `json:"burnIn,omitempty"`
	StartMs int64  `json:"startMs,omitempty" doc:"Position to start from; only used by HLS sessions."`
	PlayID  string `json:"playId,omitempty" pattern:"^[0-9a-f]{16}$" doc:"The playId an earlier request for this file returned. The new request continues that play and replaces its session (seeking, track and quality changes); omit it to start a new play."`
	// Client names the device for activity; omitted, it is worked out from the user agent.
	Client           *Client `json:"client,omitempty"`
	Quality          string  `json:"quality,omitempty" maxLength:"32" doc:"The quality the viewer picked: original, 2160, 1080, 720, 480 or 360, shown in activity, with its caps already in the profile; or auto, with the device's own profile, for the server to pick the step and lower it when the connection cannot keep up (API level 3)."`
	AutoRecovery     bool    `json:"autoRecovery,omitempty" required:"false" doc:"API level 24: opt in to negotiated Automatic recovery. Send only with quality auto and report fresh transfer age and switch generations. Older clients stay step-down only."`
	SwitchGeneration uint64  `json:"switchGeneration,omitempty" required:"false" doc:"Generation of a quality directive this request accepts. Omit for a new play or an unrelated seek/track change."`
	// Playlist picks the HLS playlist kind. event (the default) grows as
	// ffmpeg works and starts at startMs. vod lists the whole file at once,
	// so native players show the real timeline and seek anywhere; its
	// master playlist also offers the text subtitles as renditions.
	Playlist string `json:"playlist,omitempty" enum:"event,vod" doc:"HLS playlist kind: event (default) or vod (the whole file, for native players)."`
}

// Client describes the app or browser playing.
type Client struct {
	DeviceID string `json:"deviceId" minLength:"1" maxLength:"64" doc:"A stable random ID the client keeps for itself."`
	Name     string `json:"name,omitempty" maxLength:"64" doc:"Such as Chrome on macOS or Living room Apple TV."`
	Product  string `json:"product,omitempty" maxLength:"64" example:"WatchMyMedia Web"`
	Platform string `json:"platform,omitempty" maxLength:"32" example:"macOS"`
	Version  string `json:"version,omitempty" maxLength:"32"`
	Model    string `json:"model,omitempty" maxLength:"64" example:"Pixel 9" doc:"The device's model; kept with a sign-in for the signed-in devices list."`
}

type AudioTrack struct {
	Index    int    `json:"index"`
	Codec    string `json:"codec"`
	Channels int    `json:"channels"`
	Language string `json:"language,omitempty"`
	Title    string `json:"title,omitempty"`
	Default  bool   `json:"default"`
}

type SubtitleTrack struct {
	// ID is "s<index>" for an embedded stream, "x<n>" for a sidecar file or
	// "d<id>" for one downloaded from a provider.
	ID       string `json:"id"`
	Language string `json:"language,omitempty"`
	Title    string `json:"title,omitempty"`
	Codec    string `json:"codec" example:"subrip"`
	// Format is how the client receives it: webvtt, ass, or image (burn-in only).
	Format     string `json:"format" enum:"webvtt,ass,image"`
	URL        string `json:"url,omitempty"`
	Forced     bool   `json:"forced"`
	Default    bool   `json:"default"`
	BurnInOnly bool   `json:"burnInOnly"`
	External   bool   `json:"external" doc:"Not inside the video: a sidecar file next to it, or a download."`
	// Source is where the track comes from: embedded, sidecar or opensubtitles.
	Source          string `json:"source" enum:"embedded,sidecar,opensubtitles" required:"false"`
	Release         string `json:"release,omitempty" doc:"A download's release name."`
	HearingImpaired bool   `json:"hearingImpaired" required:"false"`
}

type VideoPlan struct {
	Codec     string `json:"codec,omitempty"`
	Copy      bool   `json:"copy"`
	Width     int    `json:"width,omitempty"`
	Height    int    `json:"height,omitempty"`
	HDR       string `json:"hdr,omitempty" doc:"The HDR the screen gets when the video is copied: hdr10 for a Dolby Vision 8.1 base layer, hlg for 8.4, dolbyvision when Dolby Vision is kept. A transcode is made from it, and with toneMap the screen gets SDR."`
	DVProfile int    `json:"dvProfile,omitempty" required:"false" doc:"The Dolby Vision profile sent (5, 7, 8); set only when the video is copied with its Dolby Vision. From apiLevel 8."`
	// BitDepth is the source's, 8 or 10; the transcoder needs it to pull
	// frames back off the GPU for subtitle burn-in.
	BitDepth int    `json:"bitDepth,omitempty"`
	ToneMap  bool   `json:"toneMap" doc:"HDR source converted to SDR."`
	Encoder  string `json:"encoder,omitempty" doc:"ffmpeg encoder used when not copying."`

	// How an HLS copy treats Dolby Vision, for the session (the API does
	// not show it): KeepDV writes its configuration record (under dvh1 for
	// profile 5, whose base layer nothing else shows), StripDV removes the
	// record and the RPU, leaving the base layer. With neither, HEVC is
	// copied under hvc1 as it always was.
	KeepDV      bool `json:"-"`
	StripDV     bool `json:"-"`
	Deinterlace bool `json:"-"`
}

type AudioPlan struct {
	Index    int    `json:"index"`
	Codec    string `json:"codec,omitempty"`
	Copy     bool   `json:"copy"`
	Channels int    `json:"channels,omitempty"`
}

// Decision is the chosen mode and everything the player needs.
type Decision struct {
	Adaptive *AdaptiveStream `json:"adaptive,omitempty" required:"false" doc:"API level 27: the player owns rendition selection within this admitted family. Server quality directives only change families or return to Original."`
	// caps binds the decision to its adapter and filters, including before Start.
	caps    *Capabilities
	Mode    Mode     `json:"mode" enum:"direct,remux,audio,transcode"`
	Reasons []string `json:"reasons" nullable:"false" doc:"Why a cheaper mode was not possible."`
	// Protocol is "file" (play URL directly) or "hls".
	Protocol string `json:"protocol" enum:"file,hls"`
	// Playlist is the HLS playlist kind made: vod when asked for and
	// possible, else event (a file's keyframes may still be being read).
	Playlist    string          `json:"playlist,omitempty" enum:",event,vod" doc:"HLS only: vod (start at 0, file time = player time) or event (file time = startMs + player time)."`
	URL         string          `json:"url"`
	MimeType    string          `json:"mimeType,omitempty"`
	PlayID      string          `json:"playId" doc:"Identifies this viewing; send it back with later requests for the same file."`
	SessionID   string          `json:"sessionId,omitempty"`
	DurationMs  int64           `json:"durationMs"`
	Growing     *Growing        `json:"growing,omitempty" required:"false" doc:"A recording still being written. Use event playback and extend the recorded duration from the clock, up to endsAt (API level 19)."`
	StartMs     int64           `json:"startMs" doc:"Where the HLS session starts; add to the player's time to get the file position."`
	ResumeMs    int64           `json:"resumeMs" doc:"The user's saved position for this item, if partway through."`
	Bitrate     int64           `json:"bitrate" required:"false" doc:"About how many bits per second the stream needs: the file's average when its video is copied, else the video encoder's cap plus the audio; 0 when unknown."`
	Auto        *AutoChoice     `json:"auto,omitempty" doc:"Set when the request asked for quality auto: the step the server picked."`
	Video       VideoPlan       `json:"video"`
	Audio       AudioPlan       `json:"audio"`
	AudioTracks []AudioTrack    `json:"audioTracks" nullable:"false"`
	Subtitles   []SubtitleTrack `json:"subtitles" nullable:"false"`
	SubtitleID  string          `json:"subtitleId,omitempty" required:"false" doc:"The initial subtitle track selected from the request or the person's preferences (API level 18); absent means Off."`
	BurnIn      *SubtitleTrack  `json:"burnIn,omitempty"`
	// Segments are the skippable parts of the file: intro, recap, credits, preview, commercial.
	Segments    []Segment `json:"segments" nullable:"false"`
	CreditsMs   int64     `json:"creditsMs" doc:"Where the final end credits start; 0 when unknown."`
	WatchedAtMs int64     `json:"watchedAtMs" doc:"Position past which the item counts as watched: the final credits, else 92%."`
	UpNextMs    int64     `json:"upNextMs" doc:"When to offer the next episode: the credits, else shortly before the end; 0 means do not offer it."`
	// NextEpisode is the episode after this one, for the player's Next Episode card.
	NextEpisode *UpNext `json:"nextEpisode,omitempty"`
	// Trickplay is the file's seek thumbnails, once they are made.
	Trickplay *Trickplay `json:"trickplay,omitempty" required:"false" doc:"The file's seek thumbnails; absent until the server has made them."`
}

type Growing struct {
	StartedAt time.Time `json:"startedAt" required:"false"`
	EndsAt    time.Time `json:"endsAt" required:"false"`
}

// Trickplay describes a file's seek thumbnails (PLAN-TRICKPLAY.md §2): JPEG
// sheets of Columns × Rows tiles, each Width × Height, one tile every
// IntervalMs. Tile i covers [i × intervalMs, (i+1) × intervalMs) and shows
// the last keyframe at or before its start; it is in sheet i / (columns ×
// rows), at cell i % (columns × rows), counted along the rows.
type Trickplay struct {
	IntervalMs int64  `json:"intervalMs" example:"10000"`
	Width      int    `json:"width" example:"320" doc:"A tile's width in pixels."`
	Height     int    `json:"height" example:"180" doc:"A tile's height in pixels."`
	Columns    int    `json:"columns" example:"10"`
	Rows       int    `json:"rows" example:"10"`
	Count      int    `json:"count" example:"724" doc:"Tiles in all; the last sheet is partly filled."`
	Sheets     int    `json:"sheets" example:"8"`
	URL        string `json:"url" example:"/api/files/42/trickplay/9c1e07a2/{sheet}.jpg" doc:"A sheet's URL: replace {sheet} with its number, from 0. It names the version of the thumbnails, so a sheet can be cached for good."`
}

// AutoChoice is the step an Automatic play was decided at.
type AutoChoice struct {
	Step       string `json:"step" enum:"original,2160,1080,720,480,360"`
	Reason     string `json:"reason" example:"measured 3.1 Mbps"`
	Generation uint64 `json:"generation,omitempty" required:"false" doc:"Accepted negotiated quality switch generation; acknowledge after the new stream plays its first frame."`
}

// UpNext is the episode that follows the one playing.
type UpNext struct {
	FileID        int64  `json:"fileId"`
	EpisodeID     int64  `json:"episodeId"`
	ShowID        int64  `json:"showId"`
	ShowTitle     string `json:"showTitle"`
	SeasonNumber  int64  `json:"seasonNumber"`
	EpisodeNumber int64  `json:"episodeNumber"`
	Title         string `json:"title"`
	StillPath     string `json:"stillPath,omitempty"`
	RuntimeMin    int64  `json:"runtimeMin,omitempty" doc:"Its file's length, else TMDB's runtime."`
	SeasonFinale  bool   `json:"seasonFinale" required:"false" doc:"The last episode of its season (not for specials)."`
}

// Sidecar is a subtitle file found next to the video.
type Sidecar struct {
	Path     string
	Language string
	Codec    string // subrip, ass, webvtt
	Forced   bool
}

var sidecarExts = map[string]string{".srt": "subrip", ".ass": "ass", ".ssa": "ass", ".vtt": "webvtt"}

// FindSidecars lists subtitle files that share the video's base name:
// "Movie.srt", "Movie.en.srt", "Movie.en.forced.srt".
func FindSidecars(videoPath string) []Sidecar {
	dir := filepath.Dir(videoPath)
	stem := strings.TrimSuffix(filepath.Base(videoPath), filepath.Ext(videoPath))
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []Sidecar
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || e.Type()&os.ModeSymlink != 0 || !strings.HasPrefix(name, stem+".") {
			continue
		}
		ext := strings.ToLower(filepath.Ext(name))
		codec, ok := sidecarExts[ext]
		if !ok {
			continue
		}
		rest := strings.TrimPrefix(strings.TrimSuffix(name, filepath.Ext(name)), stem+".")
		lang, forced := sidecarSubtitleLabels(rest)
		out = append(out, Sidecar{Path: filepath.Join(dir, name), Language: lang, Codec: codec, Forced: forced})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}

// HasTextSubtitle validates an untrusted stream index against the saved probe.
func HasTextSubtitle(info *probe.MediaInfo, index int) bool {
	for _, s := range info.Subtitles {
		if s.Index == index && !s.Image && isTextSubtitle(s.Codec) {
			return true
		}
	}
	return false
}

func containerOf(ext string) string {
	switch strings.ToLower(ext) {
	case ".mkv":
		return "mkv"
	case ".mp4", ".m4v", ".mov":
		return "mp4"
	case ".webm":
		return "webm"
	case ".ts", ".m2ts", ".mts":
		return "mpegts"
	case ".avi":
		return "avi"
	}
	return strings.TrimPrefix(strings.ToLower(ext), ".")
}

// MimeType for direct play by extension.
func MimeType(ext string) string {
	switch containerOf(ext) {
	case "mp4":
		return "video/mp4"
	case "webm":
		return "video/webm"
	case "mkv":
		return "video/x-matroska"
	case "mpegts":
		return "video/mp2t"
	case "avi":
		return "video/x-msvideo"
	}
	return "application/octet-stream"
}

// videoKey maps a probed stream to the profile's codec vocabulary.
func videoKey(v probe.VideoStream) string {
	if v.Codec == "hevc" && v.BitDepth > 8 {
		return "hevc10"
	}
	return v.Codec
}

// sentVideo is how a video stream goes to a profile when it is copied
// (PLAN-HDR-PROFILE.md §3).
type sentVideo struct {
	Key     string // the profile key it needs: h264, hevc10, dovi5…
	HDR     string // what the screen gets: "", hdr10, hlg or dolbyvision
	KeepDV  bool   // copied with its Dolby Vision configuration and RPU
	StripDV bool   // copied as its base layer, Dolby Vision removed
	Remux   bool   // may be copied, but not sent as the file is
	// DirectOnly is Dolby Vision 7 kept, which only direct play carries:
	// fMP4 cannot hold its enhancement layer in the one track.
	DirectOnly bool
	// Why says why a Remux cannot be direct play.
	Why string
}

// keptProfiles are the Dolby Vision profiles a dovi key keeps. AV1
// (profile 10) and AVC (9) keys wait (PLAN-HDR-PROFILE.md §9).
var keptProfiles = []int{5, 7, 8}

func doviKey(profile int) string { return "dovi" + strconv.Itoa(profile) }

// classify says how v would go to p if copied. A profile without hdrTypes
// keeps the classification older apps were built for: a 10-bit Dolby
// Vision HEVC stream is hevc10 and sent as it is (decision 8). With
// hdrTypes, Dolby Vision is kept only for its dovi key, and otherwise sent
// as its base layer, or not copied when it has none worth showing.
func classify(v probe.VideoStream, p Profile) sentVideo {
	if v.HDR != "dolbyvision" || p.HDRTypes == nil {
		return sentVideo{Key: videoKey(v), HDR: v.HDR}
	}
	key := doviKey(v.DVProfile)
	if v.Codec == "hevc" && slices.Contains(keptProfiles, v.DVProfile) && has(p.VideoCodecs, key) {
		return sentVideo{Key: key, HDR: "dolbyvision", KeepDV: true, DirectOnly: v.DVProfile == 7}
	}
	return baseClass(v)
}

// baseClass is v sent as its Dolby Vision base layer. With none worth
// showing, it needs its dovi key, which the caller found missing.
func baseClass(v probe.VideoStream) sentVideo {
	hdr, ok := baseLayer(v)
	if !ok {
		return sentVideo{Key: doviKey(v.DVProfile), HDR: "dolbyvision"}
	}
	s := sentVideo{Key: videoKey(v), HDR: hdr, StripDV: true}
	why := fmt.Sprintf("Dolby Vision %d is sent as its %s base layer, which needs a remux", v.DVProfile, hdrName(hdr))
	switch {
	case v.DVProfile == 7:
		// ExoPlayer has no fallback for profile 7 in the file; a remux
		// without its Dolby Vision plays as HDR10.
		s.Remux, s.Why = true, why
	case v.CodecTag == "dvh1" || v.CodecTag == "dvhe":
		// An MP4 that names Dolby Vision in its sample entry needs a
		// Dolby Vision decoder to play at all.
		s.Remux, s.Why = true, why
	}
	return s
}

// baseLayer is the HDR a Dolby Vision stream's base layer shows without
// its RPU ("" for SDR), and whether a plain decoder shows it right; other
// streams' HDR is their own. Profile 5 has no such base layer. A probe
// that did not read the record counts profile 8 as 8.1, by far the most
// common, and other profiles as having none (decision 10); profile 7 is
// always an HDR10 base under an enhancement layer.
func baseLayer(v probe.VideoStream) (string, bool) {
	if v.HDR != "dolbyvision" {
		return v.HDR, true
	}
	compat := v.DVCompat
	switch {
	case v.DVProfile == 7:
		return "hdr10", true
	case v.DVProfile == 8 && (!v.Detailed || compat == 0):
		compat = 1 // 8 always has a compatible base; 0 is no valid 8
	case !v.Detailed:
		compat = 0
	}
	switch compat {
	case 1, 6: // HDR10, Blu-ray HDR10
		return "hdr10", true
	case 4:
		return "hlg", true
	case 2:
		return "", true
	}
	return "dolbyvision", false
}

// shows reports whether p's screen shows HDR of kind ("" is SDR). With
// hdrTypes the kind must be listed; Dolby Vision is copied only with its
// key, which already says the display shows it. Without, hdr says.
func (p Profile) shows(kind string) bool {
	switch {
	case kind == "":
		return true
	case p.HDRTypes == nil:
		return p.HDR
	case kind == "dolbyvision":
		return true
	}
	return has(p.HDRTypes, kind)
}

// hdrName words an HDR kind for reasons.
func hdrName(kind string) string {
	switch kind {
	case "":
		return "SDR"
	case "hdr10":
		return "HDR10"
	case "hlg":
		return "HLG"
	case "dolbyvision":
		return "Dolby Vision"
	}
	return kind
}

// copyable reports whether p takes sent as it is copied: its codec key and
// the HDR rule (PLAN-HDR-PROFILE.md decision 1). Size and bit rate are
// checked apart.
func copyable(sent sentVideo, p Profile) (bool, []string) {
	var why []string
	if !has(p.VideoCodecs, sent.Key) {
		if n, ok := strings.CutPrefix(sent.Key, "dovi"); ok {
			why = append(why, "client cannot play Dolby Vision "+n)
		} else {
			why = append(why, fmt.Sprintf("client cannot decode %s video", sent.Key))
		}
	}
	if !p.shows(sent.HDR) {
		why = append(why, "the screen cannot show "+hdrName(sent.HDR))
	}
	return len(why) == 0, why
}

func audioKey(a probe.AudioStream) string {
	if strings.HasPrefix(a.Codec, "pcm_") {
		return "pcm"
	}
	return a.Codec
}

func has(list []string, v string) bool {
	for _, x := range list {
		if strings.EqualFold(x, v) {
			return true
		}
	}
	return false
}

func isTextSubtitle(codec string) bool { return probe.SubtitleKind(codec) == probe.SubtitleText }

// Decide picks the mode for a file. caps says which encoders a transcode can
// use and how large it may be. The caller fills in URLs and the session.
func Decide(info *probe.MediaInfo, ext string, req Request, sidecars []Sidecar, caps Capabilities) Decision {
	p := req.Profile
	d := Decision{caps: &caps, Reasons: []string{}, AudioTracks: []AudioTrack{}, Subtitles: []SubtitleTrack{}, Segments: []Segment{}, DurationMs: info.DurationMs, StartMs: req.StartMs}

	// --- video ---
	var videoOK bool
	var v probe.VideoStream
	var sent sentVideo
	if len(info.Video) == 0 {
		videoOK = false
		d.Reasons = append(d.Reasons, "no video stream")
	} else {
		v = info.Video[0]
		d.Video = VideoPlan{Codec: v.Codec, Copy: true, Width: v.Width, Height: v.Height, HDR: v.HDR, BitDepth: v.BitDepth}
		sent = classify(v, p)
		ok, why := copyable(sent, p)
		videoOK = ok
		d.Reasons = append(d.Reasons, why...)
		if (p.MaxWidth > 0 && v.Width > p.MaxWidth) || (p.MaxHeight > 0 && v.Height > p.MaxHeight) {
			videoOK = false
			d.Reasons = append(d.Reasons, fmt.Sprintf("%dx%d exceeds the client's %dx%d", v.Width, v.Height, p.MaxWidth, p.MaxHeight))
		}
		if p.MaxBitrate > 0 && info.BitRate > p.MaxBitrate {
			videoOK = false
			d.Reasons = append(d.Reasons, fmt.Sprintf("bitrate %d kb/s exceeds the client's %d kb/s", info.BitRate/1000, p.MaxBitrate/1000))
		}
	}

	// --- audio ---
	for _, a := range info.Audio {
		d.AudioTracks = append(d.AudioTracks, AudioTrack{Index: a.Index, Codec: a.Codec, Channels: a.Channels, Language: a.Language, Title: a.Title, Default: a.Default})
	}
	var audio *probe.AudioStream
	for i := range info.Audio {
		a := &info.Audio[i]
		if req.AudioIndex != nil && a.Index == *req.AudioIndex {
			audio = a
			break
		}
		if req.AudioIndex == nil && (audio == nil || (a.Default && !audio.Default)) {
			audio = a
		}
	}
	audioOK := true
	if audio != nil {
		d.Audio = AudioPlan{Index: audio.Index, Codec: audio.Codec, Copy: true, Channels: audio.Channels}
		if key := audioKey(*audio); !has(p.AudioCodecs, key) {
			audioOK = false
			d.Reasons = append(d.Reasons, fmt.Sprintf("client cannot decode %s audio", key))
		}
		if p.MaxAudioChannels > 0 && audio.Channels > p.MaxAudioChannels {
			audioOK = false
			d.Reasons = append(d.Reasons, fmt.Sprintf("%d audio channels exceed the client's %d", audio.Channels, p.MaxAudioChannels))
		}
	}

	// --- subtitles ---
	wantASS := has(p.SubtitleFormats, "ass")
	for _, s := range info.Subtitles {
		t := SubtitleTrack{ID: fmt.Sprintf("s%d", s.Index), Language: s.Language, Title: s.Title, Codec: s.Codec, Forced: s.Forced, Default: s.Default, Source: "embedded"}
		switch {
		case s.Image || !isTextSubtitle(s.Codec):
			t.Format, t.BurnInOnly = "image", true
		case (s.Codec == "ass" || s.Codec == "ssa") && wantASS:
			t.Format = "ass"
		default:
			t.Format = "webvtt"
		}
		d.Subtitles = append(d.Subtitles, t)
	}
	for i, sc := range sidecars {
		t := SubtitleTrack{ID: fmt.Sprintf("x%d", i), Language: sc.Language, Codec: sc.Codec, Forced: sc.Forced, External: true, Format: "webvtt", Source: "sidecar"}
		if sc.Codec == "ass" && wantASS {
			t.Format = "ass"
		}
		d.Subtitles = append(d.Subtitles, t)
	}
	burn := false
	if req.SubtitleID != "" {
		for i := range d.Subtitles {
			t := &d.Subtitles[i]
			if t.ID == req.SubtitleID && (t.BurnInOnly || req.BurnIn) {
				burn = true
				d.BurnIn = t
				d.Reasons = append(d.Reasons, "subtitle burn-in requested")
			}
		}
	}

	// --- mode ---
	containerOK := has(p.Containers, containerOf(ext))
	if req.AudioIndex != nil && audio != nil && len(info.Audio) > 1 && containerOK && !p.SelectsAudioTrack {
		if DefaultAudioIndex(info.Audio) != audio.Index {
			containerOK = false
			d.Reasons = append(d.Reasons, "selected audio track requires a remux")
		}
	}
	if !containerOK && videoOK && audioOK {
		d.Reasons = append(d.Reasons, fmt.Sprintf("client cannot play the %s container", containerOf(ext)))
	}
	if sent.DirectOnly && videoOK && !burn && (!containerOK || !audioOK) {
		// Dolby Vision 7 is kept only in direct play: an HLS stream gets
		// its HDR10 base layer, if the client takes that.
		sent = baseClass(v)
		ok, why := copyable(sent, p)
		videoOK = ok
		d.Reasons = append(d.Reasons, sent.Why)
		d.Reasons = append(d.Reasons, why...)
	}
	if sent.Remux && containerOK && videoOK && audioOK && !burn {
		containerOK = false
		d.Reasons = append(d.Reasons, sent.Why)
	}
	switch {
	case !videoOK || burn:
		d.Mode, d.Protocol = ModeTranscode, "hls"
		d.Video.Copy = false
		if !audioOK {
			d.Audio.Copy = false
		}
	case !audioOK:
		d.Mode, d.Protocol = ModeAudio, "hls"
		d.Audio.Copy = false
	case containerOK:
		d.Mode, d.Protocol = ModeDirect, "file"
		d.MimeType = MimeType(ext)
		d.StartMs = 0
	default:
		d.Mode, d.Protocol = ModeRemux, "hls"
	}
	if d.Video.Copy && len(info.Video) > 0 {
		d.Video.HDR, d.Video.KeepDV = sent.HDR, sent.KeepDV
		d.Video.StripDV = sent.StripDV && d.Protocol == "hls"
		if sent.HDR == "dolbyvision" {
			d.Video.DVProfile = v.DVProfile
		}
	}
	if !d.Video.Copy {
		// A transcode decodes Dolby Vision's base layer, for every profile:
		// HDR10 for 7 and 8.1, HLG for 8.4. Profile 5 has none a decoder
		// shows right, so it is always mapped to SDR, through its RPU
		// where ffmpeg can (Capabilities.DoviToneMap).
		kind := ""
		if len(info.Video) > 0 {
			kind, _ = baseLayer(v)
		}
		d.Video.HDR = kind
		// HEVC where the client decodes it: better quality per bit, and the
		// only way to keep HDR, which needs 10-bit output an HDR client can
		// show. H.264 output is always 8-bit SDR.
		d.Video.Codec, d.Video.Encoder = "h264", caps.Encoder
		keepHDR := false
		if caps.HEVCEncoder != "" && has(p.VideoCodecs, "hevc") {
			d.Video.Codec, d.Video.Encoder = "hevc", caps.HEVCEncoder
			keepHDR = kind != "" && kind != "dolbyvision" && p.shows(kind) && has(p.VideoCodecs, "hevc10")
		}
		d.Video.ToneMap = kind != "" && !keepHDR
		if kind == "dolbyvision" && !caps.DoviToneMap {
			d.Reasons = append(d.Reasons, fmt.Sprintf("the server cannot convert Dolby Vision %d's colours, so they will be off", v.DVProfile))
		}
		d.Video.Width, d.Video.Height = transcodeSize(d.Video.Width, d.Video.Height, p, caps.MaxHeight)
		// Interlaced video is deinterlaced when it is transcoded anyway,
		// with the filter Live TV uses. It never forces a transcode by
		// itself: direct play, remux and Original stay, and the player
		// deinterlaces what it is sent as it would any interlaced file.
		d.Video.Deinterlace = len(info.Video) > 0 && Interlaced(v)
	}
	if !d.Audio.Copy {
		d.Audio.Codec = "aac"
		if p.MaxAudioChannels > 0 && d.Audio.Channels > p.MaxAudioChannels {
			d.Audio.Channels = p.MaxAudioChannels
		} else if d.Audio.Channels > 6 {
			d.Audio.Channels = 6
		}
	}
	d.Bitrate = streamBitrate(info, d, audio, p.MaxBitrate)
	return d
}

// streamBitrate is what a decision's stream needs, roughly: the file's
// average when the video is copied, else the encoder's cap, plus the audio
// as sent.
func streamBitrate(info *probe.MediaInfo, d Decision, audio *probe.AudioStream, maxBitrate int64) int64 {
	audioBps := int64(0)
	switch {
	case audio == nil:
	case !d.Audio.Copy:
		audioBps = transcodeAudioBitrate
	default:
		audioBps = audio.BitRate
	}
	if !d.Video.Copy {
		return transcodeBitrate(maxBitrate, max(d.Video.Height, d.Video.Width*9/16)) + audioBps
	}
	if d.Audio.Copy && info.BitRate > 0 {
		return info.BitRate
	}
	if len(info.Video) > 0 && info.Video[0].BitRate > 0 {
		return info.Video[0].BitRate + audioBps
	}
	return info.BitRate
}

// transcodeSize fits a w x h picture inside the client's limits and the
// transcode cap (capHeight, or 1080 when 0), keeping the aspect ratio. Both
// sides come out even, as 4:2:0 chroma needs.
func transcodeSize(w, h int, p Profile, capHeight int) (int, int) {
	if w <= 0 || h <= 0 {
		return w, h
	}
	if capHeight <= 0 {
		capHeight = defaultMaxTranscodeHeight
	}
	maxW, maxH := capHeight*16/9, capHeight
	if p.MaxWidth > 0 && p.MaxWidth < maxW {
		maxW = p.MaxWidth
	}
	if p.MaxHeight > 0 && p.MaxHeight < maxH {
		maxH = p.MaxHeight
	}
	if w > maxW {
		w, h = maxW, h*maxW/w
	}
	if h > maxH {
		w, h = w*maxH/h, maxH
	}
	return max(w&^1, 2), max(h&^1, 2)
}
