package playback

import (
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// transcode.dir may be a shared folder: startup clears the server's own
// folder and old session folders, and leaves everything else alone.
func TestCleanKeepsOtherFiles(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	write := func(rel string) {
		t.Helper()
		p := filepath.Join(dir, rel)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write("notes.txt")
	write("other-app/cache.bin")
	write(SessionsDir + "/0123456789abcdef/r1-00003.m4s")
	// A session folder from before SessionsDir, and folders that only look like one.
	write("00112233445566aa/index.m3u8")
	write("00112233445566aa/seg00001.m4s")
	write("00112233445566aa/init.mp4")
	write("00112233445566bb/r2-init.mp4")
	write("00112233445566bb/r2.m3u8.tmp")
	write("00112233445566cc/index.m3u8")
	write("00112233445566cc/photo.jpg")
	write("not-a-session/index.m3u8")

	m := NewManager("ffmpeg", dir, Capabilities{}, time.Minute, 1, slog.New(slog.DiscardHandler))
	if err := m.Clean(); err != nil {
		t.Fatal(err)
	}
	for rel, want := range map[string]bool{
		"notes.txt":                       true,
		"other-app/cache.bin":             true,
		"00112233445566cc/photo.jpg":      true,
		"not-a-session/index.m3u8":        true,
		SessionsDir:                       true,
		SessionsDir + "/0123456789abcdef": false,
		"00112233445566aa":                false,
		"00112233445566bb":                false,
	} {
		_, err := os.Stat(filepath.Join(dir, rel))
		if got := err == nil; got != want {
			t.Errorf("%s exists = %v, want %v", rel, got, want)
		}
	}
}
