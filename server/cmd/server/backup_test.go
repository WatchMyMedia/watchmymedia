package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// A backup holds the config file the server loaded, wherever it is. The
// Linux packages keep it in /etc/watchmymedia and name it with
// WATCHMYMEDIA_CONFIG; a backup used to save only a
// config.yaml in the data folder, so theirs would have had none.
func TestBackupSavesTheLoadedConfigFile(t *testing.T) {
	dataDir, etc := t.TempDir(), t.TempDir()
	openMigrated(t, filepath.Join(dataDir, "watchmymedia.db"))
	loaded := filepath.Join(etc, "server.yaml")
	if err := os.WriteFile(loaded, []byte("port: 27599\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	// One left in the data folder, as by an earlier restore, is not the one
	// the server reads.
	if err := os.WriteFile(filepath.Join(dataDir, "config.yaml"), []byte("port: 1\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dataDir)
	t.Setenv("WATCHMYMEDIA_CONFIG", loaded)

	named := filepath.Join(t.TempDir(), "named.tar.gz")
	if err := backupCommand([]string{named}); err != nil {
		t.Fatal(err)
	}
	if err := backupCommand(nil); err != nil {
		t.Fatal(err)
	}
	saved, err := filepath.Glob(filepath.Join(dataDir, "backups", "*.tar.gz"))
	if err != nil || len(saved) != 1 {
		t.Fatalf("backups in the data folder = %v %v", saved, err)
	}
	for _, archive := range []string{named, saved[0]} {
		members := archiveMembers(t, archive)
		if got := string(members["config.yaml"]); got != "port: 27599\n" {
			t.Errorf("%s: config.yaml = %q, want the loaded file's; members %v", filepath.Base(archive), got, names(members))
		}
		if _, ok := members["server.yaml"]; ok {
			t.Errorf("%s: the config file kept its own name; a restore looks for config.yaml", filepath.Base(archive))
		}
	}
}

// With no file anywhere, a backup has none, as before.
func TestBackupWithoutAConfigFile(t *testing.T) {
	dataDir := t.TempDir()
	openMigrated(t, filepath.Join(dataDir, "watchmymedia.db"))
	t.Setenv("WATCHMYMEDIA_DATA_DIR", dataDir)
	t.Setenv("WATCHMYMEDIA_CONFIG", filepath.Join(t.TempDir(), "missing.yaml"))
	out := filepath.Join(t.TempDir(), "b.tar.gz")
	if err := backupCommand([]string{out}); err != nil {
		t.Fatal(err)
	}
	members := archiveMembers(t, out)
	if _, ok := members["config.yaml"]; ok || len(members["watchmymedia.db"]) == 0 {
		t.Errorf("members = %v", names(members))
	}
}

func archiveMembers(t *testing.T, archive string) map[string][]byte {
	t.Helper()
	raw, err := os.ReadFile(archive)
	if err != nil {
		t.Fatal(err)
	}
	gz, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	members := map[string][]byte{}
	tr := tar.NewReader(gz)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return members
		}
		if err != nil {
			t.Fatal(err)
		}
		if members[h.Name], err = io.ReadAll(tr); err != nil {
			t.Fatal(err)
		}
	}
}

func names(members map[string][]byte) string {
	var out []string
	for name := range members {
		out = append(out, name)
	}
	return strings.Join(out, ", ")
}
