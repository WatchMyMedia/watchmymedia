package api

import (
	"bytes"
	"database/sql"
	"embed"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
)

// seedRowsLibrary gives the test server a small library whose rows don't
// depend on today's date: every file was added and every episode aired
// years ago. Library 1 (movies) gets five movies and the test server's own
// unmatched file; tvLib, a TV library, three shows. The admin is partway
// through a movie and an episode. TMDB IDs are 9001+ for movies and 9101+
// for shows.
func seedRowsLibrary(t testing.TB, conn *sql.DB) (tvLib int64) {
	t.Helper()
	ctx := t.Context()
	exec := func(q string, args ...any) int64 {
		t.Helper()
		res, err := conn.ExecContext(ctx, q, args...)
		if err != nil {
			t.Fatal(err)
		}
		id, _ := res.LastInsertId()
		return id
	}
	// The test server's unmatched file, added long ago.
	exec(`UPDATE media_files SET added_at = '2019-05-01T10:00:00.000Z'`)
	for i := range 5 {
		id := exec(`INSERT INTO movies (library_id, tmdb_id, imdb_id, title, year, release_date, genres, rating, poster_path)
			VALUES (1, ?, ?, ?, ?, ?, '["Drama"]', 7.5, '/m.jpg')`,
			9001+i, fmt.Sprintf("tt90%05d", i), fmt.Sprintf("Lantern %d", i), 2000+i, fmt.Sprintf("%d-03-0%d", 2000+i, i+1))
		exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, height, added_at, match_status, movie_id)
			VALUES (1, ?, 1000, '2020-01-01T00:00:00.000Z', 'present', '{"kind":"movie"}', 1080, ?, 'matched', ?)`,
			fmt.Sprintf("/m/Lantern %d.mkv", i), fmt.Sprintf("2020-01-0%dT10:00:00.000Z", i+1), id)
	}
	tvLib = exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'TV', '/t')`)
	for s := range 3 {
		show := exec(`INSERT INTO shows (library_id, tmdb_id, title, year, status, genres, poster_path) VALUES (?, ?, ?, 2010, 'Ended', '["Comedy"]', '/s.jpg')`,
			tvLib, 9101+s, fmt.Sprintf("Harbour %d", s))
		season := exec(`INSERT INTO seasons (show_id, number, episode_count) VALUES (?, 1, 2)`, show)
		for n := 1; n <= 2; n++ {
			ep := exec(`INSERT INTO episodes (season_id, show_id, number, title, air_date) VALUES (?, ?, ?, ?, ?)`,
				season, show, n, fmt.Sprintf("Episode %d", n), fmt.Sprintf("2011-0%d-1%d", s+1, n))
			f := exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, height, added_at, match_status)
				VALUES (?, ?, 500, '2020-01-01T00:00:00.000Z', 'present', '{"kind":"episode"}', 720, ?, 'matched')`,
				tvLib, fmt.Sprintf("/t/Harbour %d/S01E0%d.mkv", s, n), fmt.Sprintf("2021-0%d-0%dT10:00:00.000Z", s+1, n))
			exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, ep, f)
			if s == 0 && n == 1 {
				exec(`INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, updated_at)
					VALUES (1, 'episode', ?, ?, 60000, 1200000, '2022-02-02T10:00:00.000Z')`, ep, f)
			}
		}
	}
	exec(`INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, updated_at)
		SELECT 1, 'movie', m.id, f.id, 600000, 6000000, '2022-01-01T10:00:00.000Z' FROM movies m JOIN media_files f ON f.movie_id = m.id WHERE m.tmdb_id = 9002`)
	exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed, updated_at)
		SELECT 1, 'movie', id, 1, '2022-01-01T10:00:00.000Z' FROM movies WHERE tmdb_id = 9004`)
	return tvLib
}

// The golden files are compiled into the test binary rather than read from
// disk: Go's test cache checks files a test opens by modification time, which
// a fresh checkout resets, so reading them made this package re-run on every
// CI run. Embedded files are checked by content.
//
//go:embed testdata/unarranged_*.json
var unarrangedGolden embed.FS

// Without arranged=true, Home and the library rows are exactly what they
// were before rows could be arranged, byte for byte, so
// apps that don't know rows see no change. The golden files were written
// by the code before arranged rows existed (WMM_UPDATE_GOLDEN=1, which
// writes them to testdata/ for the next build to embed), and written again
// only for optional fields added to cards since (tmdbId; and from API level
// 34 a show card's unwatchedCount, seasonCount and, on recently added
// shows, added; Continue watching's tmdbId and an episode card's
// showTmdbId).
func TestUnarrangedRepliesUnchanged(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	tv := seedRowsLibrary(t, srv.conn)
	for _, c := range []struct{ name, path string }{
		{"home", "/api/home"},
		{"movie_rows", "/api/libraries/1/rows"},
		{"tv_rows", fmt.Sprintf("/api/libraries/%d/rows", tv)},
		{"home_arranged_false", "/api/home?arranged=false"},
	} {
		res := doJSON(t, srv.client, http.MethodGet, srv.URL+c.path, "", nil)
		if res.StatusCode != http.StatusOK {
			t.Fatalf("%s -> %d: %s", c.path, res.StatusCode, res.Body)
		}
		golden := c.name
		if golden == "home_arranged_false" {
			golden = "home"
		}
		file := "testdata/unarranged_" + golden + ".json"
		if os.Getenv("WMM_UPDATE_GOLDEN") == "1" && golden == c.name {
			if err := os.WriteFile(filepath.FromSlash(file), res.Body, 0o600); err != nil {
				t.Fatal(err)
			}
			continue
		}
		want, err := unarrangedGolden.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		// Git on Windows checks text files out with CRLF line endings;
		// the replies end in a bare LF.
		want = bytes.ReplaceAll(want, []byte("\r\n"), []byte("\n"))
		if !bytes.Equal(res.Body, want) {
			t.Errorf("%s changed:\n got %s\nwant %s", c.path, res.Body, want)
		}
	}
}

// The merge rules, as a table.
func TestMergeRows(t *testing.T) {
	t.Parallel()
	home := []string{"continue", "live", "shows", "aired", "movies", "released"}
	p := func(spec ...string) []RowPlacement {
		out := []RowPlacement{}
		for _, s := range spec {
			id, hidden := strings.CutPrefix(s, "-")
			out = append(out, RowPlacement{ID: id, Hidden: hidden})
		}
		return out
	}
	for _, c := range []struct {
		name               string
		base               []RowPlacement
		defaults, builtins []string
		own, other         []string
		want               []RowPlacement
	}{
		{name: "nothing saved: the built-in order", builtins: home, defaults: home,
			want: p("continue", "live", "shows", "aired", "movies", "released")},
		{name: "a saved order and hidden rows are kept", base: p("movies", "-continue", "live", "shows", "aired", "released"), builtins: home, defaults: home,
			want: p("movies", "-continue", "live", "shows", "aired", "released")},
		{name: "unknown IDs and repeats are dropped", base: p("movies", "list:9", "gone", "movies", "continue", "live", "shows", "aired", "released"), builtins: home, defaults: home,
			want: p("movies", "continue", "live", "shows", "aired", "released")},
		{name: "a missing built-in goes after the nearest row before it", base: p("released", "continue", "shows", "aired", "movies"), builtins: home, defaults: home,
			want: p("released", "continue", "live", "shows", "aired", "movies")},
		{name: "one with nothing before it goes first", base: p("aired", "shows"), builtins: home, defaults: home,
			want: p("continue", "live", "aired", "movies", "released", "shows")},
		{name: "the nearest one present, skipping missing ones", base: p("released", "aired"), builtins: home, defaults: home,
			want: p("continue", "live", "shows", "released", "aired", "movies")},
		{name: "placed by the household's order, not the built-in one", base: p("continue"), builtins: home,
			defaults: []string{"released", "movies", "continue", "live", "shows", "aired"},
			want:     p("released", "movies", "continue", "live", "shows", "aired")},
		{name: "lists made here go first, others last and hidden", base: p("continue", "shows"), builtins: []string{"continue", "shows"}, defaults: []string{"continue", "shows"},
			own: []string{"list:3", "list:1"}, other: []string{"list:2"},
			want: p("list:3", "list:1", "continue", "shows", "-list:2")},
		{name: "placed lists stay where they are", base: p("continue", "-list:3", "list:2", "shows"), builtins: []string{"continue", "shows"}, defaults: []string{"continue", "shows"},
			own: []string{"list:3"}, other: []string{"list:2"},
			want: p("continue", "-list:3", "list:2", "shows")},
		{name: "an empty saved order is every row in its default place", base: p(), builtins: []string{"recentlyAdded", "recentlyReleased"}, defaults: []string{"recentlyAdded", "recentlyReleased"},
			want: p("recentlyAdded", "recentlyReleased")},
	} {
		got := mergeRows(c.base, c.defaults, c.builtins, c.own, c.other)
		if !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s:\n got %v\nwant %v", c.name, got, c.want)
		}
	}
}

// Where watchlists' rows go: as built-in rows do, from their place in the
// default order (withWatchlists), in the order they were made.
func TestWatchlistPlacement(t *testing.T) {
	t.Parallel()
	p := func(spec ...string) []RowPlacement {
		out := []RowPlacement{}
		for _, s := range spec {
			id, hidden := strings.CutPrefix(s, "-")
			out = append(out, RowPlacement{ID: id, Hidden: hidden})
		}
		return out
	}
	home := []string{"continue", "followed", "live", "shows", "movies"}
	var full []string // Home with 31 rows, New from shows you follow not counting
	for i := range 29 {
		full = append(full, fmt.Sprintf("row%d", i))
	}
	full = append([]string{"continue", "followed"}, full...)
	for _, c := range []struct {
		name                 string
		base                 []RowPlacement
		builtins, watchlists []string
		library              bool
		want                 []RowPlacement
	}{
		{name: "a new person", builtins: home, watchlists: []string{"list:1"},
			want: p("continue", "followed", "list:1", "live", "shows", "movies")},
		{name: "no New from shows you follow: after Continue watching", builtins: []string{"continue", "live", "movies"}, watchlists: []string{"list:1"},
			want: p("continue", "list:1", "live", "movies")},
		{name: "neither: first", builtins: []string{"live", "movies"}, watchlists: []string{"list:1"},
			want: p("list:1", "live", "movies")},
		{name: "a library's screen: first, in the order made", library: true, builtins: []string{"recentlyAdded", "recentlyReleased"}, watchlists: []string{"list:1", "list:2"},
			want: p("list:1", "list:2", "recentlyAdded", "recentlyReleased")},
		{name: "a layout saved before it", base: p("movies", "continue", "followed", "shows", "live"), builtins: home, watchlists: []string{"list:1"},
			want: p("movies", "continue", "followed", "list:1", "shows", "live")},
		{name: "one made later goes after the earlier, wherever that was moved", base: p("list:1", "movies", "continue", "followed", "shows", "live"),
			builtins: home, watchlists: []string{"list:1", "list:2"},
			want: p("list:1", "list:2", "movies", "continue", "followed", "shows", "live")},
		{name: "a hidden one stays hidden", base: p("-list:1", "continue", "followed", "live", "shows", "movies"), builtins: home, watchlists: []string{"list:1"},
			want: p("-list:1", "continue", "followed", "live", "shows", "movies")},
		{name: "a deleted one goes", base: p("continue", "followed", "list:1", "live", "shows", "movies"), builtins: home,
			want: p("continue", "followed", "live", "shows", "movies")},
		{name: "a full screen", base: p(full...), builtins: full, watchlists: []string{"list:1"},
			want: p(slices.Insert(slices.Clone(full), 2, "list:1")...)},
	} {
		order := withWatchlists(c.builtins, c.watchlists, !c.library)
		got := mergeRows(c.base, order, order, nil, nil)
		if !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s:\n got %v\nwant %v", c.name, got, c.want)
		}
	}
	// On the full screen the new row is drawn and the last one isn't.
	order := withWatchlists(full, []string{"list:1"}, true)
	var rows []placedRow
	for _, r := range mergeRows(p(full...), order, order, nil, nil) {
		rows = append(rows, placedRow{ID: r.ID, Kind: rowKindBuiltin})
	}
	entries := layoutEntries(rows, nil)
	if last := entries[len(entries)-1]; !entries[2].Shown || last.Shown || last.Note != "Not shown: too many rows" {
		t.Errorf("a full screen: %+v ... %+v", entries[2], last)
	}
}
