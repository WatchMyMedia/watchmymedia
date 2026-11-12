package segments

import (
	"context"
	"math"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/bgwork"
	"watchmymedia/server/internal/ffexec"
)

func TestAdDetectorReadsRealBlackAndSilenceMetadata(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	// This verifies evidence, not throughput. The detector intentionally
	// yields CPU and I/O to concurrent foreground builds on shared runners.
	ctx, cancel := context.WithTimeout(t.Context(), 2*time.Minute)
	defer cancel()
	path := filepath.Join(t.TempDir(), "fictional-ads.ts")
	windows := "between(t,10,10.2)+between(t,25,25.2)+between(t,40,40.2)"
	cmd := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-y", "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=20:duration=45", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=45", "-vf", "drawbox=c=black:t=fill:enable='"+windows+"'", "-af", "volume=0:enable='"+windows+"'", "-c:v", "mpeg2video", "-threads", "1", "-c:a", "ac3", "-f", "mpegts", path)...)
	if err = cmd.Run(); err != nil {
		t.Fatal(err)
	}
	start := time.Now()
	// The pass gets the test's whole two-minute bound: it runs below normal
	// priority and a loaded runner can take most of that.
	found, err := DetectAdBreaks(ctx, ffmpeg, path, 2*time.Minute, bgwork.Gate{})
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("45 seconds of synthetic broadcast analysed in %s", time.Since(start))
	if len(found) != 1 || math.Abs(float64(found[0].StartMs-10100)) > 300 || math.Abs(float64(found[0].EndMs-40100)) > 300 {
		t.Fatalf("wanted one conservative 10–40 s break, got %+v", found)
	}
}

// The video's and the audio's metadata printers share stdout. Buffered,
// a 10-minute broadcast with a black, silent moment every 0.6 s tore about
// ten lines; each must arrive whole, and every moment be read.
func TestAdPassReadsEveryBoundaryOfABusyBroadcast(t *testing.T) {
	t.Parallel()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	ctx, cancel := context.WithTimeout(t.Context(), 4*time.Minute)
	defer cancel()
	path := filepath.Join(t.TempDir(), "fictional-busy.ts")
	quiet := "lt(mod(t,0.6),0.12)"
	cmd := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs("-y", "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25:duration=600", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=600", "-vf", "drawbox=c=black:t=fill:enable='"+quiet+"'", "-af", "volume=0:enable='"+quiet+"'", "-c:v", "mpeg2video", "-threads", "1", "-c:a", "ac3", "-f", "mpegts", path)...)
	if err = cmd.Run(); err != nil {
		t.Fatal(err)
	}
	// At normal priority: whole lines don't depend on how the pass yields,
	// and ten minutes of decoding in the background tier of a shared CI Mac
	// took over two minutes there (1.4 s on its own), competing with the
	// playback package's low-priority download test in the same tier.
	e, err := adPass(ctx, ffmpeg, path, 2*time.Minute, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(e.black) != 1000 || len(e.silence) != 1000 {
		t.Fatalf("read %d black and %d silent moments, want 1000 of each", len(e.black), len(e.silence))
	}
}
