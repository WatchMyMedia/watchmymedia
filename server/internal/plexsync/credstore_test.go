package plexsync

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/plex"
)

func testGrant(t *testing.T) grant {
	t.Helper()
	key, err := plex.GenerateDeviceKey()
	if err != nil {
		t.Fatal(err)
	}
	return grant{ClientIdentifier: "watchmymedia-synthetic", Key: key}
}

func TestCredStoreRoundTripAndRemove(t *testing.T) {
	dataDir := t.TempDir()
	s, err := openCredStore(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	g := testGrant(t)
	ref, err := s.put(g)
	if err != nil {
		t.Fatal(err)
	}
	if !credRef.MatchString(ref) {
		t.Fatalf("reference %q is not opaque hex", ref)
	}
	got, err := s.get(ref)
	if err != nil || got.ClientIdentifier != g.ClientIdentifier || got.Key.PublicJWK() != g.Key.PublicJWK() {
		t.Fatalf("grant did not round-trip: %v", err)
	}
	if formatted := fmt.Sprintf("%v %+v %#v", got, got, got); strings.Contains(formatted, "watchmymedia-synthetic") {
		t.Fatalf("formatting a grant printed it: %s", formatted)
	}
	for _, bad := range []string{"", "../config", strings.Repeat("A", 32), ref + "x"} {
		if _, err := s.get(bad); err == nil {
			t.Errorf("reference %q was read", bad)
		}
	}
	if err := s.remove(ref); err != nil {
		t.Fatal(err)
	}
	if _, err := s.get(ref); err == nil {
		t.Error("a removed grant was still read")
	}
	if err := s.remove(ref); err != nil {
		t.Errorf("removing twice: %v", err)
	}
}

func TestCredStoreRejectsDamagedFiles(t *testing.T) {
	s, err := openCredStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ref, err := s.put(testGrant(t))
	if err != nil {
		t.Fatal(err)
	}
	for _, body := range []string{"", "{", `{"v":2,"clientIdentifier":"x","deviceKeySeed":"AA=="}`, `{"v":1,"clientIdentifier":"x","deviceKeySeed":"AA=="}`, `{"v":1,"clientIdentifier":"","deviceKeySeed":""}`} {
		if err := os.WriteFile(s.path(ref), []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := s.get(ref); err == nil {
			t.Errorf("damaged grant %q was read", body)
		}
	}
}

// A grant whose reference never reached the database, or whose reference a
// restore cleared, is removed once it is older than the grace period;
// referenced grants and other files stay.
func TestCredStoreReconcileRemovesOrphans(t *testing.T) {
	s, err := openCredStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	live, err := s.put(testGrant(t))
	if err != nil {
		t.Fatal(err)
	}
	orphan, err := s.put(testGrant(t))
	if err != nil {
		t.Fatal(err)
	}
	tmp := filepath.Join(s.dir, ".0123456789abcdef0123456789abcdef.tmp")
	other := filepath.Join(s.dir, "README")
	for _, p := range []string{tmp, other} {
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	refs := map[string]bool{live: true}
	if n, err := s.reconcile(refs, time.Hour, time.Now()); err != nil || n != 0 {
		t.Fatalf("young files removed: %d %v", n, err)
	}
	if n, err := s.reconcile(refs, time.Hour, time.Now().Add(2*time.Hour)); err != nil || n != 2 {
		t.Fatalf("reconcile removed %d, %v; want the orphan and the temporary file", n, err)
	}
	if _, err := s.get(live); err != nil {
		t.Error("the referenced grant was removed")
	}
	if _, err := s.get(orphan); err == nil {
		t.Error("the orphan grant survived")
	}
	if _, err := os.Stat(other); err != nil {
		t.Error("a file that is not a grant was removed")
	}
}

// The file holds the seed, and nothing about the grant is written outside
// the store's folder.
func TestCredStoreWritesOnlyItsFolder(t *testing.T) {
	dataDir := t.TempDir()
	s, err := openCredStore(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	g := testGrant(t)
	if _, err := s.put(g); err != nil {
		t.Fatal(err)
	}
	err = filepath.WalkDir(dataDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		if filepath.Dir(path) != s.dir {
			t.Errorf("grant store wrote %s", path)
		}
		data, _ := os.ReadFile(path)
		if bytes.Contains(data, g.Key.Seed()) {
			t.Error("the seed was written raw rather than encoded")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}
