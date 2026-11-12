package livetv

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/playback"
)

// Live's CPU deinterlacer and selected encoder use a different FFmpeg filter
// path from VOD. A measured VOD encoder alone cannot establish this path.
// Probe one synthetic interlaced MPEG-2/AC-3 input through the exact live
// HLS command, without opening a tuner or using a viewer's broadcast bytes.
const liveProbeTimeout = 10 * time.Second

type liveProbeResult struct {
	done chan struct{}
	ok   bool
}

var liveProbes sync.Map // encoder/build/device/detection -> *liveProbeResult

func liveEncoderWorks(ctx context.Context, ffmpeg string, caps playback.Capabilities) bool {
	device := -1
	if caps.Device != nil {
		device = *caps.Device
	}
	key := fmt.Sprintf("%s|%s|%d|%d", ffmpeg, caps.Encoder, device, caps.Detected.UnixNano())
	entry := &liveProbeResult{done: make(chan struct{})}
	value, loaded := liveProbes.LoadOrStore(key, entry)
	probe := value.(*liveProbeResult)
	if !loaded {
		elapsed, err := probeLivePipeline(ctx, ffmpeg, caps.Encoder, caps.Device)
		probe.ok = err == nil && elapsed > 0 && elapsed <= 2400*time.Millisecond
		close(probe.done)
		if ctx.Err() != nil {
			liveProbes.Delete(key) // A canceled first viewer must not poison later starts.
		}
	}
	select {
	case <-probe.done:
		return probe.ok
	case <-ctx.Done():
		return false
	}
}

func probeLivePipeline(ctx context.Context, ffmpeg, encoder string, device *int) (time.Duration, error) {
	ctx, cancel := context.WithTimeout(ctx, liveProbeTimeout)
	defer cancel()
	dir, err := os.MkdirTemp("", "watchmymedia-live-probe-")
	if err != nil {
		return 0, err
	}
	defer func() { _ = os.RemoveAll(dir) }()
	input := filepath.Join(dir, "broadcast.ts")
	generate := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs(
		"-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30000/1001",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
		"-t", "3", "-c:v", "mpeg2video", "-threads", "1", "-flags", "+ilme+ildct", "-top", "1",
		"-b:v", "2000k", "-c:a", "ac3", "-b:a", "192k", "-f", "mpegts", input)...)
	if err := generate.Run(); err != nil {
		return 0, fmt.Errorf("make live sample: %w", err)
	}
	source, err := os.Open(input)
	if err != nil {
		return 0, err
	}
	defer func() { _ = source.Close() }()
	out := filepath.Join(dir, "hls")
	if err := os.Mkdir(out, 0o700); err != nil {
		return 0, err
	}
	cmd := ffexec.Command(ctx, ffmpeg, HLSArgsOnDevice(out, encoder, 360, 1800, device)...)
	cmd.Stdin = source
	started := time.Now()
	if err := cmd.Run(); err != nil {
		return 0, fmt.Errorf("live pipeline: %w", err)
	}
	elapsed := time.Since(started)
	playlist, err := os.ReadFile(filepath.Join(out, "live.m3u8"))
	if err != nil || !bytes.Contains(playlist, []byte("#EXTINF:")) || !bytes.Contains(playlist, []byte(".ts")) {
		return 0, errors.New("live pipeline published no playlist")
	}
	return elapsed, nil
}
