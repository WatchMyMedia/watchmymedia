package api

import (
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"testing"
)

// The files list finds files by words in their path, as typed or as a
// decomposed name on disk spells them; shows only the unreadable ones when
// asked; counts those whatever the filter; and names what each row matched.
func TestListFilesSearchesAndNamesMatches(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	exec := func(query string, args ...any) int64 {
		t.Helper()
		res, err := srv.conn.ExecContext(t.Context(), query, args...)
		if err != nil {
			t.Fatal(err)
		}
		id, _ := res.LastInsertId()
		return id
	}
	lib := exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'Shows', '/fictional/shows')`)
	file := func(path, probeError string, movie any) int64 {
		var perr any
		if probeError != "" {
			perr = probeError
		}
		return exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, probe_error, movie_id, match_status)
			VALUES (?, ?, 1, '2026-01-01T00:00:00Z', 'present', '{}', ?, ?, 'matched')`, lib, "/fictional/shows/"+path, perr, movie)
	}
	show := exec(`INSERT INTO shows (library_id, tmdb_id, title) VALUES (?, 77, 'Harbor Lights')`, lib)
	season := exec(`INSERT INTO seasons (show_id, number) VALUES (?, 3)`, show)
	e7 := exec(`INSERT INTO episodes (season_id, show_id, number, title) VALUES (?, ?, 7, 'The Long Dark')`, season, show)
	e8 := exec(`INSERT INTO episodes (season_id, show_id, number, title) VALUES (?, ?, 8, 'Low Water')`, season, show)
	double := file("Harbor Lights/S03E07E08.mkv", "", nil)
	exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?), (?, ?)`, e8, double, e7, double)
	movie := exec(`INSERT INTO movies (library_id, tmdb_id, title, year) VALUES (?, 99, 'Night Ferry', 1949)`, lib)
	file("Extras/Night Ferry (1949).mkv", "", movie)
	file("Café Society/broken.mkv", "Invalid data found when processing input", nil)

	list := func(query string) MediaFileList {
		t.Helper()
		var out MediaFileList
		if code := doJSON(t, srv.client, http.MethodGet, srv.URL+fmt.Sprintf("/api/libraries/%d/files?%s", lib, query), "", &out).StatusCode; code != http.StatusOK {
			t.Fatalf("%s -> %d", query, code)
		}
		return out
	}
	paths := func(l MediaFileList) []string {
		var out []string
		for _, f := range l.Items {
			out = append(out, f.RelativePath)
		}
		return out
	}
	all := list("")
	if all.Total != 3 || all.Unreadable != 1 {
		t.Fatalf("all: total %d, unreadable %d", all.Total, all.Unreadable)
	}
	byPath := map[string]MediaFile{}
	for _, f := range all.Items {
		byPath[f.RelativePath] = f
	}
	if f := byPath["Harbor Lights/S03E07E08.mkv"]; f.Title != "Harbor Lights" || f.Episode == nil || f.Episode.SeasonNumber != 3 || f.Episode.EpisodeNumber != 7 || f.Episode.Title != "The Long Dark" {
		t.Errorf("an episode file names %q %+v", f.Title, f.Episode)
	}
	if f := byPath["Extras/Night Ferry (1949).mkv"]; f.Title != "Night Ferry" || f.Year != 1949 || f.Episode != nil {
		t.Errorf("a movie file names %q %d %+v", f.Title, f.Year, f.Episode)
	}
	if got := paths(list("q=" + url.QueryEscape("harbor s03"))); !slices.Equal(got, []string{"Harbor Lights/S03E07E08.mkv"}) {
		t.Errorf("words in order find %v", got)
	}
	if got := paths(list("q=" + url.QueryEscape("café"))); len(got) != 1 {
		t.Errorf("a composed word finds a decomposed name: %v", got)
	}
	if got := list("probe=failed"); got.Total != 1 || len(got.Items) != 1 || got.Items[0].ProbeError == "" || got.Unreadable != 1 {
		t.Errorf("unreadable files: %+v", got)
	}
	if got := list("probe=failed&q=harbor"); got.Total != 0 || len(got.Items) != 0 {
		t.Errorf("unreadable files matching harbor: %+v", got)
	}
}
