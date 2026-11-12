//go:build !windows

package plexsync

import (
	"os"
	"path/filepath"
	"testing"
)

// The folder is 0700 even if it was made wider, and each grant is 0600.
func TestCredStorePermissions(t *testing.T) {
	dataDir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dataDir, CredentialsDir), 0o755); err != nil {
		t.Fatal(err)
	}
	s, err := openCredStore(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	ref, err := s.put(testGrant(t))
	if err != nil {
		t.Fatal(err)
	}
	if info, err := os.Stat(s.dir); err != nil || info.Mode().Perm() != 0o700 {
		t.Errorf("folder mode = %v, %v; want 0700", info.Mode().Perm(), err)
	}
	if info, err := os.Stat(s.path(ref)); err != nil || info.Mode().Perm() != 0o600 {
		t.Errorf("grant mode = %v, %v; want 0600", info.Mode().Perm(), err)
	}
}
