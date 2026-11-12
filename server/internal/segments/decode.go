package segments

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/ffexec"
)

// ErrNoDecoder is ffmpeg failing to start at all: no binary, or the system
// out of open files or processes. It says nothing about the file.
var ErrNoDecoder = errors.New("could not run ffmpeg")

// Decoder reads a stretch of a file's audio as mono samples at SampleRate.
type Decoder struct {
	FFmpeg string
	// Nice runs ffmpeg at low priority (under nice when it exists; below
	// normal priority on Windows), so detection does not compete with
	// playback.
	Nice bool
}

// PCM decodes lengthMs of audio stream index (an ffprobe stream index)
// from startMs.
func (d Decoder) PCM(ctx context.Context, path string, index int, startMs, lengthMs int64) ([]int16, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	bin := d.FFmpeg
	if bin == "" {
		bin = "ffmpeg"
	}
	cmd := ffexec.Command(ctx, bin, ffexec.FFmpegArgs(
		"-ss", ffexec.Seconds(startMs), "-i", path, "-t", ffexec.Seconds(lengthMs),
		"-map", "0:"+strconv.Itoa(index), "-vn", "-sn", "-ac", "1", "-ar", strconv.Itoa(SampleRate), "-f", "s16le", "-")...)
	cmd.LowPriority = d.Nice
	out, err := cmd.Output()
	if err != nil {
		msg := cmd.Tail()
		noDecoder := ctx.Err() == nil && !ffexec.RanOnFile(err, msg)
		if i := strings.IndexByte(msg, '\n'); i > 0 {
			msg = msg[:i]
		}
		// The message is the first line of stderr, not all the exit error carries.
		if exit, ok := errors.AsType[*ffexec.ExitError](err); ok {
			err = exit.Err
		}
		if noDecoder {
			return nil, fmt.Errorf("decode audio: %w: %w: %s", ErrNoDecoder, err, msg)
		}
		return nil, fmt.Errorf("decode audio: %w: %s", err, msg)
	}
	samples := make([]int16, len(out)/2)
	if err := binary.Read(bytes.NewReader(out), binary.LittleEndian, samples); err != nil {
		return nil, err
	}
	return samples, nil
}
