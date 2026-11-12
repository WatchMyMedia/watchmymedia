package playback

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/ffexec"
)

// Force the exact Windows failure without relying on two renames overlapping:
// even shared-delete access prevents FFmpeg's MoveFileEx from replacing an
// open destination. Its successful exit must not strand the final segments.
func TestFinishedPlaylistRecoversFFmpegRenameFailure(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	clip := sample(t, ffmpeg)
	dir := t.TempDir()
	path := filepath.Join(dir, PlaylistName)
	const old = "#EXTM3U\n# previous snapshot\n"
	if err := os.WriteFile(path, []byte(old), 0o600); err != nil {
		t.Fatal(err)
	}
	held, err := OpenShared(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = held.Close() })
	ctx, cancel := context.WithTimeout(t.Context(), 30*time.Second)
	defer cancel()
	cmd := ffexec.Command(ctx, ffmpeg, ffexec.FFmpegArgs(
		"-y", "-i", clip, "-map", "0:v:0", "-c:v", "copy", "-an", "-sn",
		"-f", "hls", "-hls_time", "1", "-hls_playlist_type", "event",
		"-hls_segment_type", "fmp4", "-hls_fmp4_init_filename", "init.mp4",
		"-hls_segment_filename", filepath.Join(dir, "seg%05d.m4s"),
		"-hls_flags", "temp_file", path,
	)...)
	cmd.Dir = dir
	if err := cmd.Run(); err != nil {
		t.Fatalf("FFmpeg no longer exits successfully after a blocked rename: %v", err)
	}
	got, err := os.ReadFile(path)
	if err != nil || string(got) != old {
		t.Fatalf("did not force the Windows destination conflict: %q, %v", got, err)
	}
	final, err := os.ReadFile(path + ".tmp")
	if err != nil || !bytes.Contains(final, []byte("#EXT-X-ENDLIST")) || bytes.Count(final, []byte("#EXTINF:")) < 2 {
		t.Fatalf("missing complete unpublished playlist: %q, %v; stderr: %s", final, err, cmd.Tail())
	}
	if err := held.Close(); err != nil {
		t.Fatal(err)
	}
	if err := finishPlaylist(path); err != nil {
		t.Fatal(err)
	}
	got, err = ReadShared(path)
	if err != nil || !bytes.Equal(got, final) {
		t.Fatalf("did not recover every final segment and marker: %q, %v", got, err)
	}
}

func TestSharedPathPreservesWindowsNamespaces(t *testing.T) {
	t.Parallel()
	long := strings.Repeat(`folder\`, 45)
	for _, tc := range []struct{ path, want string }{
		{`C:\Media\clip.mkv`, `C:\Media\clip.mkv`},
		{`C:\Media\` + long + `clip.mkv`, `\\?\C:\Media\` + long + `clip.mkv`},
		{`\\fileserver\media\` + long + `clip.mkv`, `\\?\UNC\fileserver\media\` + long + `clip.mkv`},
		{`\\?\C:\Media\clip.mkv`, `\\?\C:\Media\clip.mkv`},
		{`\\.\ExampleDevice`, `\\.\ExampleDevice`},
	} {
		got, err := sharedPath(tc.path)
		if err != nil || got != tc.want {
			t.Fatalf("sharedPath(%q)=%q, %v; want %q", tc.path, got, err, tc.want)
		}
	}
}
