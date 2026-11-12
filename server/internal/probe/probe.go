// Package probe wraps ffprobe and reduces its output to the stream facts the
// server needs for playback decisions and display.
package probe

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/ffexec"
)

// Prober runs ffprobe. Zero value uses "ffprobe" from PATH with a 60s timeout.
type Prober struct {
	Path    string
	Timeout time.Duration
}

// Prober is the interface the scanner depends on; *Prober satisfies it.
type Interface interface {
	Probe(ctx context.Context, file string) (*MediaInfo, error)
}

type MediaInfo struct {
	// Container is the first name ffprobe lists for the format, e.g. "matroska", "mov".
	Container  string `json:"container"`
	FormatName string `json:"formatName"`
	DurationMs int64  `json:"durationMs"`
	BitRate    int64  `json:"bitRate,omitempty"`
	Size       int64  `json:"size,omitempty"`

	Video     []VideoStream    `json:"video" nullable:"false"`
	Audio     []AudioStream    `json:"audio" nullable:"false"`
	Subtitles []SubtitleStream `json:"subtitles" nullable:"false"`

	// StartUs is the container's start time (format.start_time), which
	// ffmpeg's -ss counts from, in microseconds, and Chapters are the
	// file's chapter marks. Playback reads them instead of running ffprobe
	// again. The stored probe keeps them (Encode, Parse); the API does not
	// show them. Extended says they were read: probes stored before they
	// were added lack them.
	StartUs  int64     `json:"-"`
	Chapters []Chapter `json:"-"`
	Extended bool      `json:"-"`
}

// stored is a probe as the database keeps it: MediaInfo, which the API
// shows too, and what only the server reads.
type stored struct {
	*MediaInfo
	Extra *extra `json:"extra,omitempty"`
}

type extra struct {
	StartUs  int64     `json:"startUs"`
	Chapters []Chapter `json:"chapters"`
	// Video holds each video stream's server-only facts, by stream index.
	// Probes stored before they were kept have none, which the scanner
	// looks for (as $.extra.video[0]) to probe Dolby Vision files again.
	Video []videoExtra `json:"video,omitempty"`
	// Audio holds the audio streams that are commentary or audio
	// description, by stream index; the others are left out.
	Audio []audioExtra `json:"audio,omitempty"`
}

type audioExtra struct {
	Index       int  `json:"index"`
	Commentary  bool `json:"commentary,omitempty"`
	Descriptive bool `json:"descriptive,omitempty"`
}

type videoExtra struct {
	Index      int    `json:"index"`
	CodecTag   string `json:"codecTag,omitempty"`
	DVCompat   int    `json:"dvCompat,omitempty"`
	DVLevel    int    `json:"dvLevel,omitempty"`
	FieldOrder string `json:"fieldOrder,omitempty"`
}

// Encode is the probe as the database keeps it.
func Encode(info *MediaInfo) ([]byte, error) {
	s := stored{MediaInfo: info}
	if info.Extended {
		s.Extra = &extra{StartUs: info.StartUs, Chapters: info.Chapters}
		if s.Extra.Chapters == nil {
			s.Extra.Chapters = []Chapter{}
		}
		for _, v := range info.Video {
			if v.Detailed {
				s.Extra.Video = append(s.Extra.Video, videoExtra{Index: v.Index, CodecTag: v.CodecTag, DVCompat: v.DVCompat, DVLevel: v.DVLevel, FieldOrder: v.FieldOrder})
			}
		}
		for _, a := range info.Audio {
			if a.Commentary || a.Descriptive {
				s.Extra.Audio = append(s.Extra.Audio, audioExtra{Index: a.Index, Commentary: a.Commentary, Descriptive: a.Descriptive})
			}
		}
	}
	return json.Marshal(s)
}

// Parse reads a probe the database keeps (Encode).
func Parse(raw string) (*MediaInfo, error) {
	info := &MediaInfo{}
	s := stored{MediaInfo: info}
	if err := json.Unmarshal([]byte(raw), &s); err != nil {
		return nil, err
	}
	if s.Extra != nil {
		info.StartUs, info.Chapters, info.Extended = s.Extra.StartUs, s.Extra.Chapters, true
		for _, x := range s.Extra.Video {
			for i := range info.Video {
				if v := &info.Video[i]; v.Index == x.Index {
					v.CodecTag, v.DVCompat, v.DVLevel, v.Detailed = x.CodecTag, x.DVCompat, x.DVLevel, true
					v.FieldOrder = x.FieldOrder
				}
			}
		}
		for _, x := range s.Extra.Audio {
			for i := range info.Audio {
				if a := &info.Audio[i]; a.Index == x.Index {
					a.Commentary, a.Descriptive = x.Commentary, x.Descriptive
				}
			}
		}
	}
	return info, nil
}

type VideoStream struct {
	Index       int     `json:"index"`
	Codec       string  `json:"codec" example:"h264"`
	Profile     string  `json:"profile,omitempty"`
	Width       int     `json:"width"`
	Height      int     `json:"height"`
	FrameRate   float64 `json:"frameRate,omitempty"`
	PixelFormat string  `json:"pixelFormat,omitempty"`
	BitDepth    int     `json:"bitDepth,omitempty"`
	// HDR is "", "hdr10", "hlg" or "dolbyvision".
	HDR string `json:"hdr,omitempty" enum:",hdr10,hlg,dolbyvision"`
	// DVProfile is the Dolby Vision profile (5, 7, 8…) from the stream's
	// DOVI configuration record; 0 for other streams.
	DVProfile int    `json:"dvProfile,omitempty"`
	BitRate   int64  `json:"bitRate,omitempty"`
	Language  string `json:"language,omitempty"`
	Title     string `json:"title,omitempty"`
	Default   bool   `json:"default"`

	// What only playback reads (PLAN-HDR-PROFILE.md §4): the record's base
	// layer compatibility ID (dv_bl_signal_compatibility_id: 0 none, 1
	// HDR10, 2 SDR, 4 HLG, 6 Blu-ray HDR10) and level, and the sample
	// entry or codec tag (hvc1, hev1, dvh1, dvhe; "" in Matroska). The
	// stored probe keeps them (Encode, Parse); the API does not show them.
	// Detailed says they were read: probes stored before they were kept
	// lack them.
	DVCompat int    `json:"-"`
	DVLevel  int    `json:"-"`
	CodecTag string `json:"-"`
	Detailed bool   `json:"-"`
	// FieldOrder is ffprobe's field order. It is kept server-side so file
	// transcodes deinterlace broadcasts without changing the public probe.
	FieldOrder string `json:"-"`
}

type AudioStream struct {
	Index         int    `json:"index"`
	Codec         string `json:"codec" example:"aac"`
	Profile       string `json:"profile,omitempty"`
	Channels      int    `json:"channels"`
	ChannelLayout string `json:"channelLayout,omitempty"`
	SampleRate    int    `json:"sampleRate,omitempty"`
	BitRate       int64  `json:"bitRate,omitempty"`
	Language      string `json:"language,omitempty"`
	Title         string `json:"title,omitempty"`
	Default       bool   `json:"default"`
	// Commentary and Descriptive are ffprobe's comment and visual_impaired
	// dispositions: tracks a language preference never chooses
	// (PLAN-LANGUAGES.md). The stored probe keeps them (Encode, Parse); the
	// API does not show them. Probes stored before they were kept have
	// neither, and track titles stand in.
	Commentary  bool `json:"-"`
	Descriptive bool `json:"-"`
}

type SubtitleStream struct {
	Index    int    `json:"index"`
	Codec    string `json:"codec" example:"subrip"`
	Language string `json:"language,omitempty"`
	Title    string `json:"title,omitempty"`
	Default  bool   `json:"default"`
	Forced   bool   `json:"forced"`
	// Image is true for bitmap formats (PGS, VOBSUB) that cannot become WebVTT.
	Image bool `json:"image"`
}

// ffprobe's JSON uses strings for most numbers.
type rawOutput struct {
	Format struct {
		FormatName string `json:"format_name"`
		Duration   string `json:"duration"`
		StartTime  string `json:"start_time"`
		BitRate    string `json:"bit_rate"`
		Size       string `json:"size"`
	} `json:"format"`
	Streams  []rawStream  `json:"streams"`
	Chapters []rawChapter `json:"chapters"`
}

type rawChapter struct {
	StartTime string            `json:"start_time"`
	EndTime   string            `json:"end_time"`
	Tags      map[string]string `json:"tags"`
}

func chapters(raw []rawChapter) []Chapter {
	out := make([]Chapter, 0, len(raw))
	for _, c := range raw {
		out = append(out, Chapter{
			StartMs: int64(math.Round(atof(c.StartTime) * 1000)),
			EndMs:   int64(math.Round(atof(c.EndTime) * 1000)),
			Title:   strings.TrimSpace(c.Tags["title"]),
		})
	}
	return out
}

type rawStream struct {
	Index            int               `json:"index"`
	CodecType        string            `json:"codec_type"`
	CodecName        string            `json:"codec_name"`
	Profile          string            `json:"profile"`
	Width            int               `json:"width"`
	Height           int               `json:"height"`
	AvgFrameRate     string            `json:"avg_frame_rate"`
	RFrameRate       string            `json:"r_frame_rate"`
	CodecTagString   string            `json:"codec_tag_string"`
	FieldOrder       string            `json:"field_order"`
	PixFmt           string            `json:"pix_fmt"`
	BitsPerRawSample string            `json:"bits_per_raw_sample"`
	ColorTransfer    string            `json:"color_transfer"`
	Channels         int               `json:"channels"`
	ChannelLayout    string            `json:"channel_layout"`
	SampleRate       string            `json:"sample_rate"`
	BitRate          string            `json:"bit_rate"`
	Tags             map[string]string `json:"tags"`
	Disposition      map[string]int    `json:"disposition"`
	SideDataList     []map[string]any  `json:"side_data_list"`
}

// Probe reads the file's format, streams and chapters in one ffprobe run.
func (p *Prober) Probe(ctx context.Context, file string) (*MediaInfo, error) {
	out, err := p.run(ctx, "-show_format", "-show_streams", "-show_chapters", "-i", file)
	if err != nil {
		return nil, err
	}
	var raw rawOutput
	if err := json.Unmarshal(out, &raw); err != nil {
		return nil, fmt.Errorf("parse ffprobe output: %w", err)
	}
	return reduce(raw), nil
}

// Chapter is one chapter mark in a file.
type Chapter struct {
	StartMs int64  `json:"startMs"`
	EndMs   int64  `json:"endMs"`
	Title   string `json:"title,omitempty"`
}

// Chapters lists the file's chapter marks, in order; most files have none.
// Probe reads them too: this is for files probed before it did.
func (p *Prober) Chapters(ctx context.Context, file string) ([]Chapter, error) {
	out, err := p.run(ctx, "-show_chapters", "-i", file)
	if err != nil {
		return nil, err
	}
	var raw rawOutput
	if err := json.Unmarshal(out, &raw); err != nil {
		return nil, fmt.Errorf("parse ffprobe output: %w", err)
	}
	return chapters(raw.Chapters), nil
}

// StartTime is the file's start time (format.start_time), which ffmpeg's
// -ss counts from, in microseconds.
func (p *Prober) StartTime(ctx context.Context, file string) (int64, error) {
	out, err := p.run(ctx, "-show_entries", "format=start_time", "-i", file)
	if err != nil {
		return 0, err
	}
	var raw struct {
		Format struct {
			StartTime string `json:"start_time"`
		} `json:"format"`
	}
	if err := json.Unmarshal(out, &raw); err != nil {
		return 0, fmt.Errorf("parse ffprobe output: %w", err)
	}
	return int64(math.Round(atof(raw.Format.StartTime) * 1e6)), nil
}

// Keyframes reads every packet of the file's first video stream and
// returns the keyframes' times in microseconds from the file's start time,
// in file order. It reads the whole file, which on a large file on a slow
// disk takes minutes, so ctx alone bounds it: the Prober's timeout does not
// apply.
func (p *Prober) Keyframes(ctx context.Context, file string) ([]int64, error) {
	cmd := ffexec.Command(ctx, p.bin(), "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time,flags:format=start_time", "-of", "csv", file)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("ffprobe: %w", err)
	}
	var keys []int64
	var start int64
	sc := bufio.NewScanner(stdout)
	for sc.Scan() {
		parts := strings.Split(sc.Text(), ",")
		switch {
		case parts[0] == "packet" && len(parts) >= 3 && strings.HasPrefix(parts[2], "K"):
			if f, err := strconv.ParseFloat(parts[1], 64); err == nil {
				keys = append(keys, int64(f*1e6))
			}
		case parts[0] == "format" && len(parts) >= 2:
			if f, err := strconv.ParseFloat(parts[1], 64); err == nil {
				start = int64(f * 1e6)
			}
		}
	}
	if err := cmd.Wait(); err != nil {
		return nil, fmt.Errorf("ffprobe: %w", err)
	}
	for i := range keys {
		keys[i] -= start
	}
	return keys, nil
}

func (p *Prober) bin() string {
	if p.Path == "" {
		return "ffprobe"
	}
	return p.Path
}

// run calls ffprobe with JSON output and returns its stdout.
func (p *Prober) run(ctx context.Context, args ...string) ([]byte, error) {
	timeout := p.Timeout
	if timeout == 0 {
		timeout = 60 * time.Second
	}
	ctx, cancel := context.WithTimeoutCause(ctx, timeout, errTimeout)
	defer cancel()

	cmd := ffexec.Command(ctx, p.bin(), append([]string{"-v", "error", "-print_format", "json"}, args...)...)
	out, err := cmd.Output()
	if err != nil {
		var exitErr *ffexec.ExitError
		msg := cmd.Tail()
		switch {
		case errors.Is(context.Cause(ctx), errTimeout):
			return nil, fmt.Errorf("ffprobe timed out after %s: %w", timeout, context.DeadlineExceeded)
		case ctx.Err() != nil:
			// The caller gave up (a scan stopping, a request going).
			return nil, fmt.Errorf("ffprobe: %w", context.Cause(ctx))
		case errors.As(err, &exitErr) && msg != "":
			return nil, &probeError{msg: firstLine(msg), err: err}
		default:
			return nil, fmt.Errorf("ffprobe: %w", err)
		}
	}
	return out, nil
}

// errTimeout is the Prober's own timeout, told apart from the caller's.
var errTimeout = errors.New("ffprobe timeout")

// probeError is ffprobe refusing a file: what it said first, with its
// exit underneath.
type probeError struct {
	msg string
	err error
}

func (e *probeError) Error() string { return "ffprobe: " + e.msg }
func (e *probeError) Unwrap() error { return e.err }

func reduce(raw rawOutput) *MediaInfo {
	info := &MediaInfo{
		FormatName: raw.Format.FormatName,
		Container:  strings.SplitN(raw.Format.FormatName, ",", 2)[0],
		DurationMs: int64(math.Round(atof(raw.Format.Duration) * 1000)),
		BitRate:    atoi64(raw.Format.BitRate),
		Size:       atoi64(raw.Format.Size),
		Video:      []VideoStream{},
		Audio:      []AudioStream{},
		Subtitles:  []SubtitleStream{},
		StartUs:    int64(math.Round(atof(raw.Format.StartTime) * 1e6)),
		Chapters:   chapters(raw.Chapters),
		Extended:   true,
	}
	for _, s := range raw.Streams {
		switch s.CodecType {
		case "video":
			if s.Disposition["attached_pic"] == 1 {
				continue // cover art, not a video track
			}
			v := VideoStream{
				Index:       s.Index,
				Codec:       s.CodecName,
				Profile:     s.Profile,
				Width:       s.Width,
				Height:      s.Height,
				FrameRate:   frameRate(s.AvgFrameRate, s.RFrameRate),
				PixelFormat: s.PixFmt,
				BitDepth:    int(atoi64(s.BitsPerRawSample)),
				BitRate:     atoi64(s.BitRate),
				Language:    s.Tags["language"],
				Title:       s.Tags["title"],
				Default:     s.Disposition["default"] == 1,
				FieldOrder:  s.FieldOrder,
			}
			if v.BitDepth == 0 && strings.Contains(s.PixFmt, "10") {
				v.BitDepth = 10
			}
			var dv doviRecord
			v.HDR, dv = hdrKind(s.ColorTransfer, s.SideDataList)
			v.DVProfile, v.DVCompat, v.DVLevel = dv.profile, dv.compat, dv.level
			v.CodecTag, v.Detailed = codecTag(s.CodecTagString), true
			info.Video = append(info.Video, v)
		case "audio":
			info.Audio = append(info.Audio, AudioStream{
				Index:         s.Index,
				Codec:         s.CodecName,
				Profile:       s.Profile,
				Channels:      s.Channels,
				ChannelLayout: s.ChannelLayout,
				SampleRate:    int(atoi64(s.SampleRate)),
				BitRate:       atoi64(s.BitRate),
				Language:      s.Tags["language"],
				Title:         s.Tags["title"],
				Default:       s.Disposition["default"] == 1,
				Commentary:    s.Disposition["comment"] == 1,
				Descriptive:   s.Disposition["visual_impaired"] == 1,
			})
		case "subtitle":
			info.Subtitles = append(info.Subtitles, SubtitleStream{
				Index:    s.Index,
				Codec:    s.CodecName,
				Language: s.Tags["language"],
				Title:    s.Tags["title"],
				Default:  s.Disposition["default"] == 1,
				Forced:   s.Disposition["forced"] == 1,
				Image:    SubtitleKind(s.CodecName) == SubtitleImage,
			})
		}
	}
	return info
}

// doviRecord is what the server keeps of a DOVI configuration record.
type doviRecord struct{ profile, compat, level int }

// hdrKind reads the stream's HDR kind and, for Dolby Vision, its record.
func hdrKind(transfer string, sideData []map[string]any) (string, doviRecord) {
	for _, sd := range sideData {
		if t, _ := sd["side_data_type"].(string); strings.Contains(strings.ToLower(t), "dovi") ||
			strings.Contains(strings.ToLower(t), "dolby vision") {
			// ffprobe prints the record's fields as JSON numbers.
			num := func(k string) int { f, _ := sd[k].(float64); return int(f) }
			return "dolbyvision", doviRecord{profile: num("dv_profile"), compat: num("dv_bl_signal_compatibility_id"), level: num("dv_level")}
		}
	}
	switch transfer {
	case "smpte2084":
		return "hdr10", doviRecord{}
	case "arib-std-b67":
		return "hlg", doviRecord{}
	}
	return "", doviRecord{}
}

// codecTag is ffprobe's codec_tag_string when it names an MP4 sample
// entry (hvc1, dvh1…); Matroska and MPEG-TS give "[0][0][0][0]" and the
// like, which say nothing.
func codecTag(s string) string {
	if len(s) != 4 {
		return ""
	}
	for _, c := range s {
		if (c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') {
			return ""
		}
	}
	return strings.ToLower(s)
}

// Subtitle kinds, as SubtitleKind names them.
const (
	// SubtitleText can become WebVTT and be burned in with libass.
	SubtitleText = "text"
	// SubtitleImage is a bitmap format (PGS, VOBSUB), only burned in.
	SubtitleImage = "image"
)

// SubtitleKind says whether a subtitle codec is text or images; it is ""
// for codecs that are neither (teletext, closed captions).
func SubtitleKind(codec string) string {
	switch codec {
	case "subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text":
		return SubtitleText
	case "hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub":
		return SubtitleImage
	}
	return ""
}

// frameRate parses "24000/1001" style ratios, preferring the average rate.
func frameRate(avg, r string) float64 {
	for _, s := range []string{avg, r} {
		num, den, ok := strings.Cut(s, "/")
		if !ok {
			continue
		}
		n, d := atof(num), atof(den)
		if n > 0 && d > 0 {
			return math.Round(n/d*1000) / 1000
		}
	}
	return 0
}

func atof(s string) float64 { f, _ := strconv.ParseFloat(s, 64); return f }

func atoi64(s string) int64 { n, _ := strconv.ParseInt(s, 10, 64); return n }

func firstLine(s string) string {
	if before, _, ok := strings.Cut(s, "\n"); ok {
		return before
	}
	return s
}
