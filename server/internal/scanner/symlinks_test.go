package scanner

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestScansIgnoreMediaSymlinks(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(t.TempDir(), "private.mp4")
	if err := os.WriteFile(outside, []byte("synthetic media"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "Linked (2020).mp4")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if files, _, err := collect(context.Background(), root, root); err != nil || len(files) != 0 {
		t.Fatalf("full scan: %v, %v", files, err)
	}
	if files, _, err := collectPaths(context.Background(), root, []string{link}); err != nil || len(files) != 0 {
		t.Fatalf("watcher scan: %v, %v", files, err)
	}
}
