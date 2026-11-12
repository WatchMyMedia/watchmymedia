package dvr

import (
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"watchmymedia/server/internal/growing"
)

func TestPublishLongUnicodePathWithOpenReaderNeverOverwrites(t *testing.T) {
	t.Parallel()
	dir := filepath.Join(t.TempDir(), strings.Repeat("recording-", 12), strings.Repeat("broadcast-", 10), "Été’s media")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	partial := filepath.Join(dir, ".Épisode 01.ts.partial")
	final := filepath.Join(dir, "Épisode 01.ts")
	if len(final) <= 300 {
		t.Fatalf("fixture must exceed MAX_PATH with ASCII directory components: %d", len(final))
	}
	first := []byte("first owned broadcast")
	if err := os.WriteFile(partial, first, 0o600); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	reader, err := growing.Open(t.Context(), partial, 0, done)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = reader.Close() }()
	if err = publishFile(partial, final); err != nil {
		t.Fatalf("long-path publication under an open growing reader: %v", err)
	}
	close(done)
	if _, err = os.Stat(partial); !os.IsNotExist(err) {
		t.Fatalf("partial remained after publication: %v", err)
	}
	second := []byte("second distinct broadcast")
	if err = os.WriteFile(partial, second, 0o600); err != nil {
		t.Fatal(err)
	}
	if err = publishFile(partial, final); err == nil {
		t.Fatal("a later publication overwrote the existing final")
	}
	body, err := os.ReadFile(final)
	if err != nil || string(body) != string(first) {
		t.Fatalf("existing final changed: %q %v", body, err)
	}
	body, err = os.ReadFile(partial)
	if err != nil || string(body) != string(second) {
		t.Fatalf("rejected partial changed: %q %v", body, err)
	}
	body, err = io.ReadAll(reader)
	if err != nil || string(body) != string(first) {
		t.Fatalf("held reader lost the published recording: %q %v", body, err)
	}
}
