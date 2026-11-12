package integrations

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations/arr"
)

func TestToLocal(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("server paths in this table are POSIX")
	}
	pm := PathMap{
		{From: "/tv", To: "/Volumes/Media/TV"},
		{From: "/tv/kids/", To: "/Volumes/Kids"},
		{From: `D:\Media\Movies`, To: "/srv/movies"},
	}
	cases := []struct{ in, want string }{
		{"/tv/Harbor Lights/Season 01/a.mkv", "/Volumes/Media/TV/Harbor Lights/Season 01/a.mkv"},
		{"/tv", "/Volumes/Media/TV"},
		{"/tv/", "/Volumes/Media/TV"},
		// The longest From wins.
		{"/tv/kids/Paper Boats", "/Volumes/Kids/Paper Boats"},
		// A prefix only counts at a folder boundary.
		{"/tvshows/Other", "/tvshows/Other"},
		// An app on Windows sends backslashes.
		{`D:\Media\Movies\Quiet Field (2019)\q.mkv`, "/srv/movies/Quiet Field (2019)/q.mkv"},
		// Decomposed accents compare equal to composed ones.
		{"/tv/Cafe\u0301 Nights", "/Volumes/Media/TV/Caf\u00e9 Nights"},
		// No mapping: the path as it is.
		{"/Volumes/Media/Movies/x.mkv", "/Volumes/Media/Movies/x.mkv"},
	}
	for _, c := range cases {
		if got := pm.ToLocal(c.in); got != c.want {
			t.Errorf("ToLocal(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestScanPathsKeepsToLibraries(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX paths")
	}
	libs := []db.Library{
		{ID: 1, Type: "movie", Path: "/Volumes/Media/Movies"},
		{ID: 2, Type: "tv", Path: "/Volumes/Media/TV"},
	}
	pm := PathMap{{From: "/tv", To: "/Volumes/Media/TV"}, {From: "/movies", To: "/Volumes/Media/Movies"}}
	byLib, outside := scanPaths(libs, arr.Sonarr, pm, []string{
		"/tv/Harbor Lights/Season 01/e1.mkv",
		"/downloads/complete/e1.mkv",       // outside every library
		"/movies/Quiet Field (2019)/q.mkv", // a movie library: not Sonarr's
		"/tv/../etc/passwd",                // cleaned to /etc/passwd: outside
	})
	if outside != 3 {
		t.Errorf("outside = %d, want 3", outside)
	}
	if got := byLib[2]; len(got) != 1 || got[0] != "/Volumes/Media/TV/Harbor Lights/Season 01/e1.mkv" {
		t.Errorf("tv paths = %v", got)
	}
	if len(byLib[1]) != 0 {
		t.Errorf("movie library got Sonarr's paths: %v", byLib[1])
	}
}

func TestSuggest(t *testing.T) {
	lib := filepath.Join(t.TempDir(), "TV")
	if err := os.MkdirAll(filepath.Join(lib, "Harbor Lights"), 0o755); err != nil {
		t.Fatal(err)
	}
	got := suggest([]string{"/tv", "/downloads"}, []string{lib}, []string{"/tv/Harbor Lights", "/tv/Not Here"}, nil)
	if len(got) != 1 || got[0].From != "/tv" || got[0].To != lib {
		t.Errorf("suggest = %+v", got)
	}
	// A root folder already inside a library needs nothing.
	if got := suggest([]string{"/tv"}, []string{lib}, []string{"/tv/Harbor Lights"}, PathMap{{From: "/tv", To: lib}}); len(got) != 0 {
		t.Errorf("suggested a mapping for a mapped folder: %+v", got)
	}
}
