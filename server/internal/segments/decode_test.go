//go:build !windows

package segments

import (
	"context"
	"errors"
	"path/filepath"
	"testing"

	"watchmymedia/server/internal/ffexec/ffexectest"
)

// fakeFFmpeg writes a script that fails as ffmpeg would.
func fakeFFmpeg(t *testing.T, stderr string, code string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "ffmpeg")
	script := "#!/bin/sh\necho '" + stderr + "' >&2\nexit " + code + "\n"
	if err := ffexectest.WriteScript(path, script); err != nil {
		t.Fatal(err)
	}
	return path
}

// ffmpeg that could not run, or ran out of open files, says nothing about
// the file; ffmpeg rejecting the file does.
func TestDecoderTellsNoDecoderFromBadFile(t *testing.T) {
	t.Parallel()
	missing := filepath.Join(t.TempDir(), "ffmpeg")
	for _, c := range []struct {
		name      string
		dec       Decoder
		noDecoder bool
	}{
		{"missing", Decoder{FFmpeg: missing}, true},
		{"missing under nice", Decoder{FFmpeg: missing, Nice: true}, true},
		{"system out of open files", Decoder{FFmpeg: fakeFFmpeg(t, "/m/e.mkv: Too many open files in system", "1"), Nice: true}, true},
		{"cannot execute", Decoder{FFmpeg: fakeFFmpeg(t, "", "126")}, true},
		{"bad file", Decoder{FFmpeg: fakeFFmpeg(t, "/m/e.mkv: Invalid data found when processing input", "1"), Nice: true}, false},
	} {
		_, err := c.dec.PCM(context.Background(), "/m/e.mkv", 1, 0, 1000)
		if err == nil {
			t.Fatalf("%s: no error", c.name)
		}
		if got := errors.Is(err, ErrNoDecoder); got != c.noDecoder {
			t.Errorf("%s: %v, no decoder = %v, want %v", c.name, err, got, c.noDecoder)
		}
	}
}
