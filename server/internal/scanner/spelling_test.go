package scanner

import (
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
)

// A webhook names a folder in NFC while the disk has it in NFD, as on a
// Mac: the scan reads the files that are there, under the names the full
// scan stored, rather than adding them again under the webhook's spelling.
func TestScanPathsSpelledInOtherForm(t *testing.T) {
	s, q, fp, root, lib := setup(t)
	folder, file := "Pokémon (1998)", "Pokémon (1998) - Mewtwo.mkv"
	onDisk := filepath.Join(root, norm.NFD.String(folder), norm.NFD.String(file))
	other := filepath.Join(root, norm.NFD.String(folder), "Other (2001).mkv")
	writeOld(t, onDisk, "a")
	writeOld(t, other, "b")
	if sum := scanned(t, s, q, lib); sum.Added != 2 {
		t.Fatalf("first scan %+v", sum)
	}
	probes := fp.calls()

	webhook := filepath.Join(root, norm.NFC.String(folder))
	for _, p := range []string{webhook, filepath.Join(webhook, norm.NFC.String(file))} {
		if sum := scanned(t, s, q, lib, p); sum.Added != 0 || sum.Unchanged == 0 || sum.Missing != 0 {
			t.Errorf("scan of %q: %+v", p, sum)
		}
	}
	rows, err := q.ListMediaFiles(t.Context(), db.ListMediaFilesParams{LibraryID: lib.ID, Limit: 10})
	if err != nil || len(rows) != 2 || fp.calls() != probes {
		t.Fatalf("%d rows after scans of the other spelling (%v), %d probes", len(rows), err, fp.calls()-probes)
	}
	if sum := scanned(t, s, q, lib); sum.Missing != 0 || sum.Unchanged != 2 {
		t.Errorf("full scan after them: %+v", sum)
	}

	// The webhook says a file went: it is marked missing though no longer
	// on disk to spell its name, and so is a whole folder.
	if err := os.Remove(onDisk); err != nil {
		t.Fatal(err)
	}
	if sum := scanned(t, s, q, lib, filepath.Join(webhook, norm.NFC.String(file))); sum.Missing != 1 {
		t.Errorf("scan of a removed file: %+v", sum)
	}
	if err := os.RemoveAll(filepath.Join(root, norm.NFD.String(folder))); err != nil {
		t.Fatal(err)
	}
	if sum := scanned(t, s, q, lib, webhook); sum.Missing != 1 {
		t.Errorf("scan of a removed folder: %+v", sum)
	}
	if st := row(t, q, other).Status; st != "missing" {
		t.Errorf("file in the removed folder is %s", st)
	}
}

func TestSpell(t *testing.T) {
	root := t.TempDir()
	nfd, nfc := norm.NFD.String("Amélie (2001)"), norm.NFC.String("Amélie (2001)")
	if err := os.MkdirAll(filepath.Join(root, nfd, "Extras"), 0o750); err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct{ rel, p, rest string }{
		{"", root, ""},
		{nfc, filepath.Join(root, nfd), ""},
		{filepath.Join(nfc, "Extras"), filepath.Join(root, nfd, "Extras"), ""},
		{filepath.Join(nfc, "gone.mkv"), filepath.Join(root, nfd, "gone.mkv"), ""}, // no other spelling to find
		{filepath.Join("Gone", nfc), filepath.Join(root, "Gone", nfc), nfc},
	} {
		if p, _, rest := spell(root, c.rel); p != c.p || rest != c.rest {
			t.Errorf("spell(%q) = %q, rest %q; want %q, %q", c.rel, p, rest, c.p, c.rest)
		}
	}
	for _, c := range []struct {
		root, p, rel string
		ok           bool
	}{
		{"/m", "/m", "", true},
		{"/m", "/m/a/b", filepath.FromSlash("a/b"), true},
		{"/m/" + nfd, "/m/" + nfc + "/x", "x", true},
		{"/m", "/movies/x", "", false},
	} {
		root, p := filepath.FromSlash(c.root), filepath.FromSlash(c.p)
		if rel, ok := below(root, p); rel != c.rel || ok != c.ok {
			t.Errorf("below(%q, %q) = %q, %v", root, p, rel, ok)
		}
	}
}
