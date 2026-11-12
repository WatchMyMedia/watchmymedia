//go:build darwin || linux

package dvr

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	unixsys "golang.org/x/sys/unix"
)

// File systems that refuse the no-replace rename still publish, and still
// never replace a file that took the final name.
func TestLinkPublishNeverReplaces(t *testing.T) {
	dir := t.TempDir()
	partial, final := filepath.Join(dir, ".a.ts.partial"), filepath.Join(dir, "a.ts")
	if err := os.WriteFile(partial, []byte("recorded"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(final, []byte("someone else's"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := linkPublish(partial, final); !errors.Is(err, os.ErrExist) {
		t.Fatalf("publish over a taken name = %v, want ErrExist", err)
	}
	if b, _ := os.ReadFile(final); string(b) != "someone else's" {
		t.Fatalf("the other file became %q", b)
	}
	if err := os.Remove(final); err != nil {
		t.Fatal(err)
	}
	if err := linkPublish(partial, final); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(final); string(b) != "recorded" {
		t.Fatalf("published %q", b)
	}
	if _, err := os.Lstat(partial); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("hidden name left behind: %v", err)
	}
	if !refusesNoReplace(&os.LinkError{Op: "rename", Err: unixsys.EINVAL}) || refusesNoReplace(&os.LinkError{Op: "rename", Err: unixsys.EEXIST}) {
		t.Fatal("only a refused flag falls back, never a taken name")
	}
}
