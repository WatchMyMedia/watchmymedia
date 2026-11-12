package scanner

import (
	"context"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
)

// Paths a scan is asked for are not always spelled as the files on disk.
// Radarr, Sonarr and the other webhooks send names normalised to NFC, while
// folders on a Mac are often NFD (decomposed accents). APFS opens either
// spelling, so reading "Pokémon" in NFC under an NFD folder stored a second
// row for every file below it, which the next full scan marked missing; on
// Linux the two are different names and the scan found nothing. A scan of
// paths therefore spells each one as the disk does before reading it.

// below returns p relative to root, when p is root or below it. Names are
// compared as NFC when the bytes differ.
func below(root, p string) (string, bool) {
	sep := string(filepath.Separator)
	if p == root {
		return "", true
	}
	if within(root, p) {
		return strings.TrimPrefix(p[len(root):], sep), true
	}
	r, q := norm.NFC.String(root), norm.NFC.String(p)
	if q == r {
		return "", true
	}
	if within(r, q) {
		return strings.TrimPrefix(q[len(r):], sep), true
	}
	return "", false
}

// spell joins rel to root with each name spelled as on disk: the entry with
// the same bytes if there is one, else the one that is the same text in
// NFC. Names that read the same in both forms are kept without a look.
// When a name is not on disk, the rest of rel is kept as given; rest is
// that part, and dir the deepest folder that was found.
func spell(root, rel string) (p, dir, rest string) {
	dir = root
	if rel == "" {
		return root, root, ""
	}
	parts := strings.Split(rel, string(filepath.Separator))
	for i, name := range parts {
		found, ok := entryNamed(dir, name)
		if !ok {
			rest = filepath.Join(parts[i:]...)
			return filepath.Join(dir, rest), dir, rest
		}
		dir = filepath.Join(dir, found)
	}
	return dir, dir, ""
}

// entryNamed finds name in the folder dir, spelled as on disk.
func entryNamed(dir, name string) (string, bool) {
	if invariant(name) {
		return name, true // no other spelling to find
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return "", false
	}
	want, found := norm.NFC.String(name), ""
	for _, e := range entries {
		if e.Name() == name {
			return name, true
		}
		if found == "" && norm.NFC.String(e.Name()) == want {
			found = e.Name()
		}
	}
	return found, found != ""
}

// invariant reports whether s reads the same in NFC and NFD, so there is
// no other spelling of it to look for.
func invariant(s string) bool { return norm.NFC.IsNormalString(s) && norm.NFD.IsNormalString(s) }

// spellPaths spells each path, which is below root, as the disk does. A
// path that is gone, so has no spelling on disk, is spelled as the database
// has the files that were there, so they are marked missing. The result is
// collapsed, since two spellings of one path become the same path.
func (s *Scanner) spellPaths(ctx context.Context, libraryID int64, root string, paths []string) []string {
	out := make([]string, 0, len(paths))
	for _, p := range paths {
		rel, _ := below(root, p)
		sp, dir, rest := spell(root, rel)
		if rest != "" && !invariant(rest) {
			sp = s.spelledAsKnown(ctx, libraryID, dir, sp)
		}
		out = append(out, sp)
	}
	return collapse(out)
}

// spelledAsKnown finds how the database spells p, a path below the folder
// dir that is no longer on disk: a file it has at p, or a file below p.
func (s *Scanner) spelledAsKnown(ctx context.Context, libraryID int64, dir, p string) string {
	sep := filepath.Separator
	rows, err := s.q.ListScanFilesUnder(ctx, db.ListScanFilesUnderParams{LibraryID: libraryID, Path: dir, Lo: dir + string(sep), Hi: dir + string(sep+1)})
	if err != nil {
		s.log.Warn("scan: look up a removed path", "path", p, "err", err)
		return p
	}
	want, depth := norm.NFC.String(p), strings.Count(p, string(sep))
	for _, r := range rows {
		cut := r.Path
		if i := nthIndex(cut, byte(sep), depth+1); i >= 0 {
			cut = cut[:i]
		}
		if norm.NFC.String(cut) == want {
			return cut
		}
	}
	return p
}

// nthIndex is the index of the nth c in s, or -1.
func nthIndex(s string, c byte, n int) int {
	for i := range len(s) {
		if s[i] == c {
			if n--; n == 0 {
				return i
			}
		}
	}
	return -1
}
