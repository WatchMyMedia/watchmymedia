package dvr

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func TestSealUsesPostCloseFingerprintAndAllowsPublication(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), ".recording.partial")
	writer, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = writer.WriteString("owned broadcast"); err != nil {
		_ = writer.Close()
		t.Fatal(err)
	}
	seal, err := sealOutput(writer, path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = seal.Close() }()
	if _, err = writer.WriteString("already closed"); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("writer remained open during fingerprinting: %v", err)
	}
	actual, err := os.Stat(path)
	if err != nil || !os.SameFile(actual, seal.info) || actual.Size() != seal.info.Size() || !actual.ModTime().Equal(seal.info.ModTime()) {
		t.Fatalf("seal differs from post-close file: actual=%v proof=%v err=%v", actual, seal.info, err)
	}
	final := filepath.Join(filepath.Dir(path), "recording.ts")
	if err = publishFile(path, final); err != nil {
		t.Fatalf("the seal reader prevented publication: %v", err)
	}
	actual, err = os.Stat(final)
	if err != nil || !actual.ModTime().Equal(seal.info.ModTime()) {
		t.Fatalf("publication changed the sealed proof: %v", err)
	}
	body, err := io.ReadAll(seal.file)
	if err != nil || string(body) != "owned broadcast" {
		t.Fatalf("held identity changed: %q %v", body, err)
	}
}

func TestSealRejectsAPathNamingAnotherFile(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	original := filepath.Join(dir, "owned.partial")
	other := filepath.Join(dir, "foreign.partial")
	writer, err := os.OpenFile(original, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = writer.WriteString("owned"); err != nil {
		_ = writer.Close()
		t.Fatal(err)
	}
	if err = os.WriteFile(other, []byte("other"), 0o600); err != nil {
		_ = writer.Close()
		t.Fatal(err)
	}
	if seal, err := sealOutput(writer, other); !errors.Is(err, ErrInvalid) {
		if seal != nil {
			_ = seal.Close()
		}
		t.Fatalf("adopted a different file through its pathname: %v", err)
	}
	body, err := os.ReadFile(other)
	if err != nil || string(body) != "other" {
		t.Fatalf("foreign file changed: %q %v", body, err)
	}
}
