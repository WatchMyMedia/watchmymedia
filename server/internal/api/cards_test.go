package api

import (
	"database/sql"
	"fmt"
	"net/http"
	"slices"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

func TestSortItemsOrder(t *testing.T) {
	t.Parallel()
	items := func() []BrowseItem {
		return []BrowseItem{{ID: 1, Title: "Bravo", RuntimeMin: 90}, {ID: 2, Title: "Alpha", RuntimeMin: 150}, {ID: 3, Title: "Charlie", RuntimeMin: 30}}
	}
	for _, c := range []struct {
		by, order string
		want      []int64
	}{
		{"runtime", "", []int64{2, 1, 3}},
		{"runtime", "desc", []int64{2, 1, 3}},
		{"runtime", "asc", []int64{3, 1, 2}},
		{"title", "", []int64{2, 1, 3}},
		{"title", "desc", []int64{3, 1, 2}},
	} {
		got := items()
		sortItemsOrder(got, c.by, c.order)
		if ids := itemIDs(got); !slices.Equal(ids, c.want) {
			t.Errorf("sort %s %s = %v, want %v", c.by, c.order, ids, c.want)
		}
	}
}

// cardsEnv is a test server with a TV library and two movie libraries
// written by hand.
type cardsEnv struct {
	*testEnv
	t    *testing.T
	conn *sql.DB
}

func newCardsEnv(t *testing.T) cardsEnv {
	t.Helper()
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) { conn = d.Conn })
	return cardsEnv{srv, t, conn}
}

func (e cardsEnv) exec(query string, args ...any) int64 {
	e.t.Helper()
	res, err := e.conn.ExecContext(e.t.Context(), query, args...)
	if err != nil {
		e.t.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}

// episode adds an episode with a present file added at added.
func (e cardsEnv) episode(lib, show, season int64, number int, added time.Time) int64 {
	e.t.Helper()
	ep := e.exec(`INSERT INTO episodes (season_id, show_id, number, title, air_date, runtime_min) VALUES (?, ?, ?, ?, '2020-01-01', 44)`, season, show, number, fmt.Sprintf("Part %d", number))
	f := e.exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, height, added_at, match_status)
		VALUES (?, ?, 100, '2020-01-01T00:00:00.000Z', 'present', '{"kind":"episode"}', 1080, ?, 'matched')`,
		lib, fmt.Sprintf("/cards-tv/%d/%d/%d.mkv", show, season, number), db.FormatTime(added))
	e.exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, ep, f)
	return ep
}

func (e cardsEnv) items(lib int64, query string) ItemList {
	e.t.Helper()
	var out ItemList
	if r := doJSON(e.t, e.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/items?limit=100%s", e.URL, lib, query), "", &out); r.StatusCode != http.StatusOK {
		e.t.Fatalf("items%s: %d %s", query, r.StatusCode, r.Body)
	}
	return out
}

func titles(items []BrowseItem) []string {
	out := make([]string, len(items))
	for i, it := range items {
		out[i] = it.Title
	}
	return out
}

func TestShowCardsAndLibraryTotals(t *testing.T) {
	t.Parallel()
	e := newCardsEnv(t)
	now, old := time.Now(), time.Date(2020, 3, 1, 0, 0, 0, 0, time.UTC)
	lib := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'Cards TV', '/cards-tv')`)
	show := func(tmdbID int64, title, rating string) int64 {
		return e.exec(`INSERT INTO shows (library_id, tmdb_id, title, year, status, genres, content_rating) VALUES (?, ?, ?, 2020, 'Returning Series', '[]', NULLIF(?, ''))`, lib, tmdbID, title, rating)
	}
	season := func(show int64, n int) int64 {
		return e.exec(`INSERT INTO seasons (show_id, number, episode_count) VALUES (?, ?, 2)`, show, n)
	}
	// Started: specials and two seasons, one episode watched.
	started := show(701, "Started", "TV-14")
	specials, s1, s2 := season(started, 0), season(started, 1), season(started, 2)
	e.episode(lib, started, specials, 1, old)
	watched := e.episode(lib, started, s1, 1, old)
	e.episode(lib, started, s1, 2, old)
	e.episode(lib, started, s2, 1, old)
	e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed) VALUES (1, 'episode', ?, 1)`, watched)
	// Fresh: two episodes added now, none watched, and followed.
	fresh := show(702, "Fresh", "TV-MA")
	fs := season(fresh, 1)
	e.episode(lib, fresh, fs, 1, now)
	e.episode(lib, fresh, fs, 2, now)
	e.exec(`INSERT INTO show_follows (user_id, tmdb_id, title) VALUES (1, 702, 'Fresh')`)
	// Done: watched through, no rating.
	done := show(703, "Done", "")
	ds := season(done, 1)
	for n := 1; n <= 2; n++ {
		ep := e.episode(lib, done, ds, n, old)
		e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed) VALUES (1, 'episode', ?, 1)`, ep)
	}

	all := e.items(lib, "")
	byTitle := map[string]BrowseItem{}
	for _, it := range all.Items {
		byTitle[it.Title] = it
	}
	for _, c := range []struct {
		title                 string
		unwatched, seasons    int64
		newCount              int64
		isNew, watchedThrough bool
	}{
		{"Started", 3, 2, 0, false, false},
		{"Fresh", 2, 1, 2, true, false},
		{"Done", 0, 1, 0, false, true},
	} {
		it := byTitle[c.title]
		if it.UnwatchedCount == nil || *it.UnwatchedCount != c.unwatched || it.SeasonCount != c.seasons || it.NewEpisodeCount != c.newCount ||
			it.IsNew != c.isNew || it.Watched != c.watchedThrough {
			t.Errorf("%s: unwatched %v seasons %d new %d isNew %v watched %v", c.title, it.UnwatchedCount, it.SeasonCount, it.NewEpisodeCount, it.IsNew, it.Watched)
		}
	}
	if tot := all.Totals; tot.Unwatched != 2 || tot.InProgress != 1 || tot.NewEpisodes != 1 || tot.Following != 1 || tot.AddedThisMonth != 1 {
		t.Errorf("totals = %+v", tot)
	}
	if got := all.Facets.ContentRatings; !slices.Equal(got, []string{"TV-14", "TV-MA", "unrated"}) {
		t.Errorf("content ratings = %q", got)
	}

	for _, c := range []struct {
		query string
		want  []string
	}{
		{"&filter=inProgress", []string{"Started"}},
		{"&filter=following", []string{"Fresh"}},
		{"&filters=unwatched,following", []string{"Fresh"}},
		{"&filter=unwatched&filters=continuing", []string{"Fresh", "Started"}},
		{"&contentRating=TV-MA,unrated", []string{"Done", "Fresh"}},
		{"&contentRating=TV-14&filter=following", nil},
		{"&sort=title&order=desc", []string{"Started", "Fresh", "Done"}},
	} {
		got := e.items(lib, c.query)
		if !slices.Equal(titles(got.Items), c.want) {
			t.Errorf("%s = %q, want %q", c.query, titles(got.Items), c.want)
		}
		// The totals stay the library's whatever the filter.
		if got.Totals.Items != 3 || got.Totals.Unwatched != 2 {
			t.Errorf("%s totals = %+v", c.query, got.Totals)
		}
	}
	// Each rating's count is after every other filter, before the rating.
	rated := e.items(lib, "&filter=unwatched&contentRating=TV-14")
	if c := rated.ContentRatingCounts; c["TV-14"] != 1 || c["TV-MA"] != 1 || c["unrated"] != 0 {
		t.Errorf("rating counts = %v", c)
	}
}

func TestLibraryCollections(t *testing.T) {
	t.Parallel()
	e := newCardsEnv(t)
	lib := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('movie', 'Cards films', '/cards-films')`)
	other := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('movie', 'Cards 4K', '/cards-4k')`)
	movie := func(lib, tmdbID int64, title, released string, collection int64, file bool) int64 {
		id := e.exec(`INSERT INTO movies (library_id, tmdb_id, title, year, release_date, genres, poster_path, collection_id, collection_name)
			VALUES (?, ?, ?, ?, ?, '[]', ?, NULLIF(?, 0), CASE WHEN ? > 0 THEN 'The Fathoms Collection' END)`,
			lib, tmdbID, title, released[:4], released, "/"+title+".jpg", collection, collection)
		if file {
			e.exec(`INSERT INTO media_files (library_id, movie_id, path, size, mtime, status, parsed, height, added_at, match_status)
				VALUES (?, ?, ?, 100, '2020-01-01T00:00:00.000Z', 'present', '{"kind":"movie"}', 1080, '2020-01-01T00:00:00.000Z', 'matched')`,
				lib, id, fmt.Sprintf("/cards/%d/%d.mkv", lib, tmdbID))
		}
		return id
	}
	two := movie(lib, 802, "Fathoms Two", "2015-05-01", 90, true)
	movie(lib, 801, "Fathoms One", "2010-05-01", 90, true)
	movie(lib, 803, "Fathoms Three", "2020-05-01", 90, false)  // its file went missing
	movie(other, 803, "Fathoms Three", "2020-05-01", 90, true) // the 4K library has it
	movie(lib, 811, "Lone Saga", "2012-01-01", 91, true)       // the only one of its collection
	movie(lib, 820, "No Collection", "2001-01-01", 0, true)

	var list LibraryCollections
	if r := doJSON(t, e.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/collections", e.URL, lib), "", &list); r.StatusCode != http.StatusOK {
		t.Fatalf("collections: %d %s", r.StatusCode, r.Body)
	}
	if len(list.Items) != 1 || list.Items[0].ID != 90 || list.Items[0].Count != 2 || list.Items[0].Total != 0 || list.Items[0].PosterPath != "/Fathoms One.jpg" ||
		list.Items[0].FirstYear != 2010 || list.Items[0].LastYear != 2015 {
		t.Fatalf("collections before TMDB's is kept = %+v", list.Items)
	}
	// Once TMDB's collection is kept: its own name and poster, its size, and
	// what the library lacks.
	e.exec(`INSERT INTO tmdb_collections (id, name, poster_path, parts, fetched_at) VALUES (90, 'Fathoms Collection', '/fathoms.jpg', ?, ?)`,
		`[{"id":804,"title":"Fathoms Four","release_date":""},{"id":803,"title":"Fathoms Three","release_date":"2020-05-01"},`+
			`{"id":802,"title":"Fathoms Two","release_date":"2015-05-01"},{"id":801,"title":"Fathoms One","release_date":"2010-05-01"}]`, db.FormatTime(time.Now()))
	doJSON(t, e.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/collections", e.URL, lib), "", &list)
	if c := list.Items[0]; c.Name != "Fathoms Collection" || c.Total != 4 || c.PosterPath != "/fathoms.jpg" {
		t.Errorf("collection = %+v", c)
	}
	var detail LibraryCollectionDetail
	if r := doJSON(t, e.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/collections/90", e.URL, lib), "", &detail); r.StatusCode != http.StatusOK {
		t.Fatalf("collection: %d %s", r.StatusCode, r.Body)
	}
	if got := titles(detail.Movies); !slices.Equal(got, []string{"Fathoms One", "Fathoms Two"}) {
		t.Errorf("movies = %q", got)
	}
	var missing []string
	for _, m := range detail.Missing {
		missing = append(missing, fmt.Sprintf("%s %s", m.Title, m.State))
	}
	if !slices.Equal(missing, []string{"Fathoms Three available", "Fathoms Four requestable"}) {
		t.Errorf("missing = %q", missing)
	}
	for _, path := range []string{"/collections/91", "/collections/999"} {
		if r := doJSON(t, e.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d%s", e.URL, lib, path), "", nil); r.StatusCode != http.StatusNotFound {
			t.Errorf("%s: %d, want 404", path, r.StatusCode)
		}
	}
	// The grid of one, and a movie's place in it.
	if got := titles(e.items(lib, "&collection=90&sort=released&order=asc").Items); !slices.Equal(got, []string{"Fathoms One", "Fathoms Two"}) {
		t.Errorf("collection grid = %q", got)
	}
	var mv MovieDetail
	doJSON(t, e.client, http.MethodGet, fmt.Sprintf("%s/api/movies/%d", e.URL, two), "", &mv)
	if c := mv.Collection; c == nil || c.ID != 90 || c.Part != 2 || c.Total != 4 || c.Have != 2 || c.Name != "Fathoms Collection" {
		t.Errorf("movie's collection = %+v", c)
	}
}

func TestHomeAcrossLibrariesAndSearchMarks(t *testing.T) {
	t.Parallel()
	e := newCardsEnv(t)
	a := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('movie', 'Older films', '/older-films')`)
	b := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('movie', 'Newer films', '/newer-films')`)
	add := func(lib, tmdbID int64, title string, added time.Time) int64 {
		id := e.exec(`INSERT INTO movies (library_id, tmdb_id, title, year, release_date, genres, runtime_min) VALUES (?, ?, ?, 2001, '2001-01-01', '[]', 101)`, lib, tmdbID, title)
		e.exec(`INSERT INTO media_files (library_id, movie_id, path, size, mtime, status, parsed, height, added_at, match_status)
			VALUES (?, ?, ?, 100, '2020-01-01T00:00:00.000Z', 'present', '{"kind":"movie"}', 1080, ?, 'matched')`,
			lib, id, fmt.Sprintf("/films/%d/%d.mkv", lib, tmdbID), db.FormatTime(added))
		return id
	}
	day := func(d int) time.Time { return time.Date(2024, 1, d, 0, 0, 0, 0, time.UTC) }
	add(a, 901, "Quartz Early", day(1))
	add(a, 902, "Quartz Late", day(9))
	partway := add(b, 903, "Quartz Middle", day(5))
	e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, position_ms, duration_ms) VALUES (1, 'movie', ?, 30000, 120000)`, partway)
	var home HomeData
	doJSON(t, e.client, http.MethodGet, e.URL+"/api/home", "", &home)
	var got []string
	for _, it := range home.RecentlyAddedMovies {
		if it.LibraryID == a || it.LibraryID == b {
			got = append(got, it.Title)
		}
	}
	if !slices.Equal(got, []string{"Quartz Late", "Quartz Middle", "Quartz Early"}) {
		t.Errorf("recently added across libraries = %q", got)
	}
	var res SearchResults
	doJSON(t, e.client, http.MethodGet, e.URL+"/api/search?q=quartz", "", &res)
	marks := map[string]string{}
	for _, m := range res.Movies {
		marks[m.Title] = fmt.Sprintf("%v %.2f", m.Watched != nil && *m.Watched, m.Progress)
	}
	if marks["Quartz Middle"] != "false 0.25" || marks["Quartz Early"] != "false 0.00" || len(res.Movies) != 3 {
		t.Errorf("search marks = %v", marks)
	}
}

// An app signed in to several servers finds a title's copies by TMDB id,
// and Continue watching, episode cards and search's episodes carry the id
// to match them.
func TestTitlesByTMDBAndTheirIDs(t *testing.T) {
	t.Parallel()
	e := newCardsEnv(t)
	films := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('movie', 'Lookup films', '/lookup-films')`)
	fourK := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('movie', 'Lookup 4K', '/lookup-4k')`)
	movie := func(lib int64, file bool) int64 {
		id := e.exec(`INSERT INTO movies (library_id, tmdb_id, title, year, genres) VALUES (?, 4401, 'Tidewater', 2012, '[]')`, lib)
		if file {
			e.exec(`INSERT INTO media_files (library_id, movie_id, path, size, mtime, status, parsed, height, added_at, match_status)
				VALUES (?, ?, ?, 100, '2020-01-01T00:00:00.000Z', 'present', '{"kind":"movie"}', 1080, '2020-01-01T00:00:00.000Z', 'matched')`,
				lib, id, fmt.Sprintf("/lookup/%d.mkv", lib))
		}
		return id
	}
	first := movie(films, true)
	second := movie(fourK, true)
	e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, updated_at)
		VALUES (1, 'movie', ?, (SELECT id FROM media_files WHERE movie_id = ?), 30000, 120000, '2026-01-01T00:00:00.000Z')`, second, second)
	var copies MovieCopies
	if r := doJSON(t, e.client, http.MethodGet, e.URL+"/api/movies?tmdbId=4401", "", &copies); r.StatusCode != http.StatusOK {
		t.Fatalf("movies by TMDB: %d %s", r.StatusCode, r.Body)
	}
	if len(copies.Items) != 2 || copies.Items[0].ID != first || copies.Items[1].ID != second || copies.Items[1].Progress != 0.25 {
		t.Errorf("copies = %+v", copies.Items)
	}
	// None is an empty list, not an error.
	if r := doJSON(t, e.client, http.MethodGet, e.URL+"/api/movies?tmdbId=999999", "", &copies); r.StatusCode != http.StatusOK || len(copies.Items) != 0 {
		t.Errorf("no copies: %d %+v", r.StatusCode, copies.Items)
	}

	tv := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'Lookup TV', '/cards-tv')`)
	show := e.exec(`INSERT INTO shows (library_id, tmdb_id, title, year, genres) VALUES (?, 5501, 'Breakwater', 2015, '[]')`, tv)
	season := e.exec(`INSERT INTO seasons (show_id, number, episode_count) VALUES (?, 1, 1)`, show)
	ep := e.episode(tv, show, season, 1, time.Now())
	e.exec(`UPDATE episodes SET air_date = ? WHERE id = ?`, time.Now().Format(time.DateOnly), ep)
	e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, updated_at)
		VALUES (1, 'episode', ?, (SELECT media_file_id FROM episode_files WHERE episode_id = ?), 1000, 100000, '2026-02-01T00:00:00.000Z')`, ep, ep)
	var shows ShowCopies
	if r := doJSON(t, e.client, http.MethodGet, e.URL+"/api/shows?tmdbId=5501", "", &shows); r.StatusCode != http.StatusOK ||
		len(shows.Items) != 1 || shows.Items[0].ID != show || shows.Items[0].UnwatchedCount == nil || *shows.Items[0].UnwatchedCount != 1 {
		t.Errorf("shows by TMDB: %d %+v", r.StatusCode, shows.Items)
	}
	if r := doJSON(t, e.client, http.MethodGet, e.URL+"/api/movies", "", nil); r.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("no tmdbId: %d, want 422", r.StatusCode)
	}

	var cont ContinueList
	doJSON(t, e.client, http.MethodGet, e.URL+"/api/watch/continue", "", &cont)
	ids := map[string]int64{}
	for _, it := range cont.Items {
		ids[it.Kind] = it.TMDBID
	}
	if ids["movie"] != 4401 || ids["episode"] != 5501 {
		t.Errorf("continue watching's TMDB ids = %v", ids)
	}
	var res SearchResults
	doJSON(t, e.client, http.MethodGet, e.URL+"/api/search?q=part%201", "", &res)
	if len(res.Episodes) != 1 || res.Episodes[0].ShowTMDBID != 5501 {
		t.Errorf("search's episodes = %+v", res.Episodes)
	}
	var rows LibraryRows
	doJSON(t, e.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/rows", e.URL, tv), "", &rows)
	if len(rows.RecentlyAired) != 1 || rows.RecentlyAired[0].ShowTMDBID != 5501 {
		t.Errorf("recently aired = %+v", rows.RecentlyAired)
	}
}
