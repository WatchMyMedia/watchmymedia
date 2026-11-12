// Package growing follows an append-only broadcast file without treating a
// temporary end of file as the end of the recording.
package growing

import (
	"context"
	"errors"
	"io"
	"os"
	"time"

	"watchmymedia/server/internal/playback"
)

type reader struct {
	ctx    context.Context
	cancel context.CancelFunc
	file   *os.File
	done   <-chan struct{}
}

// Open holds a share-delete handle, so finalising a recording can rename it
// while a viewer continues reading. done closes only after the writer closes;
// any remaining bytes are drained before EOF. The offset is in bytes.
func Open(ctx context.Context, path string, offset int64, done <-chan struct{}) (io.ReadCloser, error) {
	f, err := playback.OpenShared(path)
	if err != nil {
		return nil, err
	}
	if _, err := f.Seek(max(offset, 0), io.SeekStart); err != nil {
		_ = f.Close()
		return nil, err
	}
	ctx, cancel := context.WithCancel(ctx)
	return &reader{ctx: ctx, cancel: cancel, file: f, done: done}, nil
}

func (r *reader) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	for {
		if err := r.ctx.Err(); err != nil {
			return 0, err
		}
		n, err := r.file.Read(p)
		if n > 0 || !errors.Is(err, io.EOF) {
			return n, err
		}
		select {
		case <-r.done:
			// The writer may have appended between our read and its close.
			return r.file.Read(p)
		default:
		}
		t := time.NewTimer(100 * time.Millisecond)
		select {
		case <-r.ctx.Done():
			t.Stop()
			return 0, r.ctx.Err()
		case <-r.done:
			t.Stop()
		case <-t.C:
		}
	}
}

func (r *reader) Close() error { r.cancel(); return r.file.Close() }

// Offset estimates a seek from the measured average bitrate and aligns to a
// transport packet. The decoder finds the next complete keyframe itself.
func Offset(positionMs, bitrate int64) int64 {
	if positionMs <= 0 || bitrate <= 0 {
		return 0
	}
	return (positionMs / 8 * bitrate / 1000) / 188 * 188
}

const SeekLookbackMs int64 = 8000

// SeekOffset uses actual written bytes rather than the initial probe bitrate.
// Eight seconds of tail leave a keyframe and a complete HLS segment to decode.
// In a signal gap, it cannot seek beyond the writer and wait for missing bytes.
func SeekOffset(positionMs, elapsedMs, size int64) int64 {
	if elapsedMs <= 0 || size <= 0 {
		return 0
	}
	positionMs = min(max(positionMs, 0), max(elapsedMs-SeekLookbackMs, 0))
	return int64(float64(size)*float64(positionMs)/float64(elapsedMs)) / 188 * 188
}
