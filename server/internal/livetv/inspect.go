package livetv

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/ffexec"
)

var ErrSourceUnknown = errors.New("live source properties are unknown")

const (
	InspectBytes  = 2 << 20
	InspectWait   = 3 * time.Second
	InspectMaxAge = 30 * time.Second
	inspectOutput = 64 << 10
)

type inspectWriter struct{ bytes.Buffer }

func (w *inspectWriter) Write(p []byte) (int, error) {
	if w.Len()+len(p) > inspectOutput {
		return 0, ErrSourceUnknown
	}
	return w.Buffer.Write(p)
}

// InspectSample runs ffprobe on bytes already copied from one subscriber of
// the shared capture. It never accepts a tuner URL, opens a second tuner or
// persists a broadcast. The caller bounds both sample size and collection.
func InspectSample(ctx context.Context, ffprobe string, sample []byte, observedBps int64) (SourceInfo, error) {
	if len(sample) < 188*64 || len(sample) > InspectBytes {
		return SourceInfo{}, ErrSourceUnknown
	}
	if ffprobe == "" {
		ffprobe = "ffprobe"
	}
	ctx, cancel := context.WithTimeout(ctx, InspectWait)
	defer cancel()
	cmd := ffexec.Command(ctx, ffprobe,
		"-v", "error", "-print_format", "json", "-probesize", strconv.Itoa(InspectBytes),
		"-analyzeduration", "2000000", "-f", "mpegts", "-show_entries",
		"stream=codec_type,codec_name,width,height,field_order,sample_aspect_ratio,bit_rate,channels,color_transfer,pix_fmt:format=bit_rate",
		"-show_streams", "-show_format", "-i", "pipe:0")
	cmd.Stdin = bytes.NewReader(sample)
	out := &inspectWriter{}
	cmd.Stdout = out
	if err := cmd.Run(); err != nil {
		return SourceInfo{}, ErrSourceUnknown
	}
	var result struct {
		Streams []struct {
			CodecType     string `json:"codec_type"`
			CodecName     string `json:"codec_name"`
			Width         int    `json:"width"`
			Height        int    `json:"height"`
			FieldOrder    string `json:"field_order"`
			BitRate       string `json:"bit_rate"`
			Channels      int    `json:"channels"`
			ColorTransfer string `json:"color_transfer"`
			PixelFormat   string `json:"pix_fmt"`
			SampleAspect  string `json:"sample_aspect_ratio"`
		} `json:"streams"`
		Format struct {
			BitRate string `json:"bit_rate"`
		} `json:"format"`
	}
	if err := json.Unmarshal(out.Bytes(), &result); err != nil {
		return SourceInfo{}, ErrSourceUnknown
	}
	info := SourceInfo{Bitrate: bitrate(result.Format.BitRate)}
	var summed int64
	for _, stream := range result.Streams {
		summed += bitrate(stream.BitRate)
		switch stream.CodecType {
		case "video":
			if info.VideoCodec != "" {
				continue
			}
			info.VideoCodec, info.Width, info.Height = stream.CodecName, stream.Width, stream.Height
			info.SquarePixels = stream.SampleAspect == "1:1"
			switch stream.FieldOrder {
			case "progressive":
				info.ScanKnown = true
			case "tt", "bb", "tb", "bt":
				info.ScanKnown, info.Interlaced = true, true
			}
			switch stream.ColorTransfer {
			case "smpte2084":
				info.HDRType = "hdr10"
			case "arib-std-b67":
				info.HDRType = "hlg"
			default:
				if strings.Contains(stream.PixelFormat, "10") || strings.Contains(stream.PixelFormat, "12") {
					info.HDRType = "unknown"
				}
			}
		case "audio":
			if info.AudioPresent {
				continue
			}
			info.AudioPresent, info.AudioCodec, info.AudioChannels = true, stream.CodecName, stream.Channels
		}
	}
	info.Bitrate = max(info.Bitrate, summed, observedBps)
	if info.VideoCodec == "" || info.Width <= 0 || info.Height <= 0 || !info.ScanKnown || info.Bitrate <= 0 {
		return SourceInfo{}, ErrSourceUnknown
	}
	return info, nil
}

func bitrate(raw string) int64 {
	n, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || n < 0 || n > 1_000_000_000_000 {
		return 0
	}
	return n
}

var _ io.Writer = (*inspectWriter)(nil)
