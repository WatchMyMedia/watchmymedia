package livetv

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
)

// A broadcast's A53 captions are video frame side data, so -sn alone does
// not exclude them. Re-encoding them triggered a VideoToolbox SEI failure
// on real antenna input. Keep a fictional caption-bearing input in CI.
func TestLiveBroadcastCaptions(t *testing.T) {
	for _, tool := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Skip(err)
		}
	}
	ctx, cancel := context.WithTimeout(t.Context(), time.Minute)
	defer cancel()
	dir := t.TempDir()
	video := filepath.Join(dir, "captions.m2v")
	cmd := ffexec.Command(ctx, "ffmpeg", ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30", "-t", "6", "-c:v", "mpeg2video", "-threads", "1", "-flags", "+ilme+ildct", "-top", "1", "-f", "mpeg2video", "pipe:1")...)
	data, err := cmd.Output()
	if err != nil {
		t.Fatal(err)
	}
	// MPEG-2 picture user data: ATSC GA94, one valid CEA-608 erase command.
	// Insert before each first slice, after the picture coding extension.
	slice := []byte{0, 0, 1, 1}
	caption := []byte{0, 0, 1, 0xb2, 'G', 'A', '9', '4', 3, 0x41, 0xff, 0xfc, 0x94, 0x2c, 0xff}
	data = bytes.ReplaceAll(data, slice, append(caption, slice...))
	if err := os.WriteFile(video, data, 0o600); err != nil {
		t.Fatal(err)
	}
	input := filepath.Join(dir, "broadcast.ts")
	if err := ffexec.Command(ctx, "ffmpeg", ffexec.FFmpegArgs("-fflags", "+genpts", "-i", video, "-c:v", "copy", "-f", "mpegts", input)...).Run(); err != nil {
		t.Fatal(err)
	}
	hasCaptions := func(t *testing.T, path string) bool {
		t.Helper()
		out, err := ffexec.Command(ctx, "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames", "-show_entries", "frame=side_data_list", "-of", "json", path).Output()
		if err != nil {
			t.Fatal(err)
		}
		return bytes.Contains(out, []byte("ATSC A53"))
	}
	if !hasCaptions(t, input) {
		t.Fatal("fixture has no decoded A53 caption side data")
	}
	for _, encoder := range []string{"libx264", "h264_videotoolbox"} {
		t.Run(encoder, func(t *testing.T) {
			if encoder == "h264_videotoolbox" {
				if runtime.GOOS != "darwin" {
					t.Skip("VideoToolbox requires macOS hardware")
				}
				if err := ffexec.Command(ctx, "ffmpeg", ffexec.FFmpegArgs("-f", "lavfi", "-i", "testsrc2=size=160x120", "-frames:v", "1", "-c:v", encoder, "-f", "null", "-")...).Run(); err != nil {
					t.Skipf("VideoToolbox unavailable: %v", err)
				}
			}
			output := t.TempDir()
			in, err := os.Open(input)
			if err != nil {
				t.Fatal(err)
			}
			defer in.Close()
			cmd := ffexec.Command(ctx, "ffmpeg", HLSArgs(output, encoder, 480, 2000)...)
			cmd.Stdin = in
			if err := cmd.Run(); err != nil {
				t.Fatal(err)
			}
			if hasCaptions(t, filepath.Join(output, "segment-000000000.ts")) {
				t.Fatal("unsupported broadcast captions reached the encoder output")
			}
		})
	}
}
