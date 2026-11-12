package db

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

// Matching a list to the library starts from the list's entries and looks
// each title up by TMDB ID, then its present files, rather than reading
// every movie or show and every file. The server never runs ANALYZE, so
// these are the plans real libraries get.
func TestMediaListMatchingPlans(t *testing.T) {
	t.Parallel()
	conn, q := openTest(t)
	ctx := t.Context()
	movies, err := q.UpsertLibrary(ctx, UpsertLibraryParams{Type: "movie", Name: "Movies", Path: "/m"})
	if err != nil {
		t.Fatal(err)
	}
	now := FormatTime(time.Now())
	exec := func(query string, args ...any) int64 {
		t.Helper()
		res, err := conn.ExecContext(ctx, query, args...)
		if err != nil {
			t.Fatal(err)
		}
		id, _ := res.LastInsertId()
		return id
	}
	for i := range 300 {
		m := exec(`INSERT INTO movies (library_id, tmdb_id, title) VALUES (?, ?, ?)`, movies.ID, 1000+i, fmt.Sprintf("Film %d", i))
		exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, added_at, last_seen_at, movie_id) VALUES (?, ?, 1, ?, 'present', '{}', ?, ?, ?)`,
			movies.ID, fmt.Sprintf("/m/%d.mkv", i), now, now, now, m)
	}
	list := exec(`INSERT INTO media_lists (screen, name, source_kind, media_type) VALUES ('home', 'Best', 'tmdb', 'all')`)
	for i := range 100 {
		exec(`INSERT INTO media_list_entries (list_id, rank, media_type, tmdb_id, title) VALUES (?, ?, 'movie', ?, 'x')`, list, i+1, 1000+2*i)
	}
	const entries = "SEARCH e USING INDEX sqlite_autoindex_media_list_entries_1 (list_id=?)"
	one := func(query string) string { return strings.Replace(query, "/*SLICE:ids*/?", "?", 1) }
	for name, c := range map[string]struct {
		query string
		want  []string
	}{
		"ListMediaListMovieRanks": {one(listMediaListMovieRanks), []string{entries, "movies_tmdb_idx (tmdb_id=?)", "media_files_movie_idx (movie_id=?)"}},
		"ListMediaListShowRanks":  {one(listMediaListShowRanks), []string{entries, "shows_tmdb_idx (tmdb_id=?)", "SEARCH ep USING", "SEARCH ef USING", "SEARCH f USING INTEGER PRIMARY KEY"}},
		"ListMovieCardsByIDs":     {one(listMovieCardsByIDs), []string{"SEARCH m USING INTEGER PRIMARY KEY (rowid=?)", "media_files_movie_idx (movie_id=?)"}},
		"ListShowCardsByIDs":      {one(listShowCardsByIDs), []string{"SEARCH s USING INTEGER PRIMARY KEY (rowid=?)", "SEARCH e USING", "SEARCH ef USING", "SEARCH f USING INTEGER PRIMARY KEY"}},
	} {
		plan := explainWithArgs(t, conn, c.query, list)
		joined := strings.Join(plan, "\n  ")
		for _, w := range c.want {
			if !strings.Contains(joined, w) {
				t.Errorf("%s lacks %q:\n  %s", name, w, joined)
			}
		}
		for _, bad := range []string{"SCAN m", "SCAN s", "SCAN f", "SCAN ep", "SCAN ef"} {
			if strings.Contains(joined+"\n", bad+"\n") || strings.Contains(joined, bad+" ") {
				t.Errorf("%s reads a whole table (%s):\n  %s", name, bad, joined)
			}
		}
		t.Logf("%s:\n  %s", name, joined)
	}
}
