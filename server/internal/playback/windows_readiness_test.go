package playback

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
	"watchmymedia/server/internal/probe"
)

// Exercise the real Windows FFmpeg argv/filter path too, with no user library:
// HEVC video and E-AC-3 audio, Unicode/spaces/apostrophes, and a sidecar burn-in.
func TestTranscodeMediaPathsAndSidecars(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	encoders := probeRunner(ffmpeg, false)(t.Context(), []string{"-encoders"}, 15*time.Second).output
	if !strings.Contains(encoders, " libx265 ") || !strings.Contains(encoders, " eac3 ") {
		t.Skip("HEVC/E-AC-3 fixture encoders unavailable")
	}
	filters := probeRunner(ffmpeg, false)(t.Context(), []string{"-filters"}, 15*time.Second).output
	if !strings.Contains(filters, " subtitles ") {
		t.Skip("libass unavailable")
	}
	dir := filepath.Join(t.TempDir(), "Café [demo] & spaces")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	input := filepath.Join(dir, "Orbit's Demo (2026).mkv")
	args := ffexec.FFmpegArgs("-y", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=2", "-f", "lavfi", "-i", "sine=frequency=440:duration=2", "-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "pools=1:frame-threads=1:log-level=error", "-c:a", "eac3", "-shortest", input)
	if err := ffexec.Command(t.Context(), ffmpeg, args...).Run(); err != nil {
		t.Fatal(err)
	}
	sub := filepath.Join(dir, "Orbit's Demo (2026).en.srt")
	if err := os.WriteFile(sub, []byte("1\n00:00:00,000 --> 00:00:01,500\nExample subtitle\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	caps := Capabilities{Encoder: "libx264", HWAccel: "software", Encoders: []string{"libx264"}, MaxHeight: 1080, LibASS: true}
	m := NewManager(ffmpeg, t.TempDir(), caps, time.Minute, 1, testLog)
	t.Cleanup(m.StopAll)
	s, err := m.Start(t.Context(), Spec{FileID: 1, Path: input, Mode: ModeTranscode, Video: VideoPlan{Codec: "h264", Width: 320, Height: 180}, Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2}, BurnIn: -1, BurnInFile: sub})
	if err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(s.Dir)
	if err != nil {
		t.Fatal(err)
	}
	segment := ""
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".ts") || strings.HasSuffix(e.Name(), ".m4s") {
			segment = filepath.Join(s.Dir, e.Name())
			break
		}
	}
	if segment == "" {
		t.Fatal("no playable segment was produced")
	}
	playlist, err := s.ReadPlaylist()
	if err != nil {
		t.Fatal(err)
	}
	// Probe the same immutable playlist bytes an HTTP reader receives.
	probePath := filepath.Join(s.Dir, "probe.m3u8")
	if err := os.WriteFile(probePath, playlist, 0o600); err != nil {
		t.Fatal(err)
	}
	info, err := (&probe.Prober{Path: "ffprobe"}).Probe(t.Context(), probePath)
	if err != nil {
		t.Fatal(err)
	}
	if len(info.Video) != 1 || info.Video[0].Codec != "h264" || len(info.Audio) != 1 || info.Audio[0].Codec != "aac" {
		t.Fatalf("unplayable conversion: %+v", info)
	}
}
