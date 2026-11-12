package integrations

import (
	"os"
	"path"
	"path/filepath"
	"regexp"
	"strings"

	"golang.org/x/text/unicode/norm"
)

// Mapping turns the start of an app's paths into the server's: Radarr in a
// container sees "/movies" where the server sees "/Volumes/Media/Movies".
type Mapping struct {
	From string `json:"from" maxLength:"1024" doc:"The start of the path as the app sees it."`
	To   string `json:"to" maxLength:"1024" doc:"The same folder as this server sees it."`
}

type PathMap []Mapping

var windowsPath = regexp.MustCompile(`^(?:[A-Za-z]:[\\/]|\\\\)`)

// slashed puts a path in one form for comparing: forward slashes (an app on
// Windows sends backslashes), composed accents (folder names on real
// servers are often decomposed), no trailing slash.
func slashed(p string) string {
	p = strings.TrimSpace(p)
	if windowsPath.MatchString(p) {
		p = strings.ReplaceAll(p, `\`, "/")
	}
	p = norm.NFC.String(p)
	if len(p) > 1 {
		p = strings.TrimRight(p, "/")
	}
	return path.Clean(p)
}

// under reports whether p is dir or below it, both slashed.
func under(dir, p string) bool {
	return p == dir || dir == "/" || strings.HasPrefix(p, dir+"/")
}

// ToLocal maps an app's path to the server's. The longest matching From
// wins; with none, the path is taken as it is, for an app that sees the same
// folders as the server.
func (pm PathMap) ToLocal(p string) string {
	s := slashed(p)
	best := -1
	for i, m := range pm {
		from := slashed(m.From)
		if m.From != "" && under(from, s) && (best < 0 || len(from) > len(slashed(pm[best].From))) { //nolint:gosec // G602: best is an index of pm once set
			best = i
		}
	}
	if best < 0 {
		return filepath.FromSlash(s)
	}
	rest := strings.TrimPrefix(s, slashed(pm[best].From))
	return filepath.Join(norm.NFC.String(pm[best].To), filepath.FromSlash(rest))
}

// localKey is a server path in the form the index compares.
func localKey(p string) string { return slashed(filepath.ToSlash(p)) }

// inside reports whether the server path p is in the folder dir.
func inside(dir, p string) bool { return under(localKey(dir), localKey(p)) }

// suggest proposes mappings for an app's root folders that the paths as
// given do not put inside any library: a root folder whose last name is a
// library's last name, or names a folder in a library, and which holds a
// folder of the app's items that the server can see there.
func suggest(roots []string, libraries []string, items []string, have PathMap) []Mapping {
	var out []Mapping
	for _, root := range roots {
		local := have.ToLocal(root)
		mapped := false
		for _, lib := range libraries {
			mapped = mapped || inside(lib, local)
		}
		if mapped {
			continue
		}
		r := slashed(root)
		for _, lib := range libraries {
			to := ""
			switch {
			case strings.EqualFold(path.Base(r), filepath.Base(lib)):
				to = lib
			default:
				if fi, err := os.Stat(filepath.Join(lib, path.Base(r))); err == nil && fi.IsDir() {
					to = filepath.Join(lib, path.Base(r))
				}
			}
			if to == "" || !holdsItem(r, to, items) {
				continue
			}
			out = append(out, Mapping{From: root, To: to})
			break
		}
	}
	return out
}

// holdsItem reports whether one of the items under root (app paths) is a
// folder the server sees under to.
func holdsItem(root, to string, items []string) bool {
	checked := 0
	for _, it := range items {
		s := slashed(it)
		if !under(root, s) || s == root {
			continue
		}
		rel := strings.TrimPrefix(s, root+"/")
		if fi, err := os.Stat(filepath.Join(to, filepath.FromSlash(rel))); err == nil && fi.IsDir() {
			return true
		}
		if checked++; checked >= 20 {
			break
		}
	}
	return false
}

// InFolder reports whether the server path p is in the folder dir, comparing
// as the index does.
func InFolder(dir, p string) bool { return inside(dir, p) }
