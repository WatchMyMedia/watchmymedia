package api

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"maps"
	"math/rand/v2"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"unicode"

	"watchmymedia/server/internal/db"
)

// searchLib adds made-up titles to a test server's library, each with a
// present file unless a test takes it away.
type searchLib struct {
	tb   testing.TB
	conn *sql.DB
	tv   int64
	n    int
}

func newSearchLib(tb testing.TB, conn *sql.DB) *searchLib {
	tb.Helper()
	l := &searchLib{tb: tb, conn: conn}
	l.tv = l.exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'TV', '/t')`)
	return l
}

func (l *searchLib) exec(q string, args ...any) int64 {
	l.tb.Helper()
	res, err := l.conn.ExecContext(l.tb.Context(), q, args...)
	if err != nil {
		l.tb.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}

func (l *searchLib) file(library int64, movie any) int64 {
	l.n++
	return l.exec(`INSERT INTO media_files (library_id, path, size, mtime, parsed, match_status, movie_id) VALUES (?, ?, 1, '2026-01-01T00:00:00.000Z', '{}', 'matched', ?)`,
		library, fmt.Sprintf("/files/%d.mkv", l.n), movie)
}

// movie adds a movie with a present file. original is its original title,
// when it has one.
func (l *searchLib) movie(title string, year int, original ...string) int64 {
	l.n++
	var o any
	if len(original) > 0 {
		o = original[0]
	}
	id := l.exec(`INSERT INTO movies (library_id, tmdb_id, title, original_title, year) VALUES (1, ?, ?, ?, ?)`, 1000+l.n, title, o, year)
	l.file(1, id)
	return id
}

// show adds a show with no episodes, which search does not list until one
// has a file.
func (l *searchLib) show(title string, year int) int64 {
	l.n++
	return l.exec(`INSERT INTO shows (library_id, tmdb_id, title, year) VALUES (?, ?, ?, ?)`, l.tv, 1000+l.n, title, year)
}

// episode adds an episode with a present file to a show.
func (l *searchLib) episode(show int64, season, number int, title string) int64 {
	l.exec(`INSERT INTO seasons (show_id, number) VALUES (?, ?) ON CONFLICT DO NOTHING`, show, season)
	id := l.exec(`INSERT INTO episodes (season_id, show_id, number, title) VALUES ((SELECT id FROM seasons WHERE show_id = ? AND number = ?), ?, ?, ?)`, show, season, show, number, title)
	l.exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, id, l.file(l.tv, nil))
	return id
}

// search asks the API, as an app does.
func search(t *testing.T, srv *testEnv, typed string, limit int) SearchResults {
	t.Helper()
	var res SearchResults
	path := "/api/search?q=" + url.QueryEscape(typed)
	if limit > 0 {
		path += fmt.Sprintf("&limit=%d", limit)
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+path, "", &res)
	return res
}

func movieTitles(r SearchResults) []string {
	out := make([]string, len(r.Movies))
	for i, m := range r.Movies {
		out[i] = m.Title
	}
	return out
}

func showTitles(r SearchResults) []string {
	out := make([]string, len(r.Shows))
	for i, s := range r.Shows {
		out[i] = s.Title
	}
	return out
}

func episodeTitles(r SearchResults) []string {
	out := make([]string, len(r.Episodes))
	for i, e := range r.Episodes {
		out[i] = fmt.Sprintf("%s S%dE%d %s", e.ShowTitle, e.SeasonNumber, e.Number, e.Title)
	}
	return out
}

// TestSearchBestMatchesFirst checks the order of each group: the whole
// title, then titles that start with the words, then titles where they
// start a word, then anywhere; and by title within each. Twenty-five titles
// that come first by the alphabet used to fill the list before the title
// someone typed in full.
func TestSearchBestMatchesFirst(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	lib := newSearchLib(t, srv.conn)
	for i := range 25 {
		lib.movie(fmt.Sprintf("Aardvark Starlet %02d", i), 2000)
	}
	for _, title := range []string{"Rockstar", "Lone Star", "Stardust", "Star Harbor", "A Star Is Lit", "The Star", "Star", "Morning Star Rising", "Harbor Lights"} {
		lib.movie(title, 2001)
	}
	want := []string{
		"Star", "The Star", // the whole title, with or without its article
		"A Star Is Lit", "Star Harbor", "Stardust", // starts with it
		"Aardvark Starlet 00", "Aardvark Starlet 01", // a word starts with it
	}
	if got := movieTitles(search(t, srv, "star", 7)); !slices.Equal(got, want) {
		t.Errorf("first seven for star = %q, want %q", got, want)
	}
	got := movieTitles(search(t, srv, "star", 50))
	if len(got) != 33 || !slices.Equal(got[30:], []string{"Lone Star", "Morning Star Rising", "Rockstar"}) {
		t.Errorf("all for star (%d) end %q", len(got), got[max(0, len(got)-3):])
	}
	// The default limit is 20, as it was.
	if got := search(t, srv, "star", 0); len(got.Movies) != 20 {
		t.Errorf("default limit gave %d movies", len(got.Movies))
	}

	atlas, harbor := lib.show("Atlas Road", 2010), lib.show("Harbor Road", 2012)
	lib.show("Road Without Episodes", 2014)
	lib.episode(harbor, 1, 1, "Pilot")
	lib.episode(harbor, 1, 2, "The Co-Pilot")
	lib.episode(harbor, 2, 1, "Autopilot")
	lib.episode(atlas, 2, 3, "Pilot Error")
	lib.episode(atlas, 1, 1, "Pilot")
	lib.episode(atlas, 1, 2, "The Pilot")
	res := search(t, srv, "pilot", 0)
	// Episodes that match equally well stay in the order they had: by
	// show, season and number.
	wantEpisodes := []string{
		"Atlas Road S1E1 Pilot", "Atlas Road S1E2 The Pilot", "Harbor Road S1E1 Pilot",
		"Atlas Road S2E3 Pilot Error",
		"Harbor Road S1E2 The Co-Pilot",
		"Harbor Road S2E1 Autopilot",
	}
	if got := episodeTitles(res); !slices.Equal(got, wantEpisodes) {
		t.Errorf("episodes for pilot = %q, want %q", got, wantEpisodes)
	}
	for _, e := range res.Episodes {
		if e.FileID == 0 || e.ShowID == 0 {
			t.Errorf("episode without its file or show: %+v", e)
		}
	}
	// A show is listed once it has an episode with a file.
	if got := showTitles(search(t, srv, "road", 0)); !slices.Equal(got, []string{"Atlas Road", "Harbor Road"}) {
		t.Errorf("shows for road = %q", got)
	}
}

// TestSearchForgivesTyping checks each kind of typing a search forgives,
// through the index (a word of three letters or more) and without it.
func TestSearchForgivesTyping(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	lib := newSearchLib(t, srv.conn)
	lib.movie("Café Soleil", 2003)
	lib.movie("Lantern-Man: No Way Back", 2019)
	lib.movie("BOLT·E", 2008)
	lib.movie("The Lord of the Tides", 2001)
	lib.movie("Dusk", 2021)
	lib.movie("Dusk", 1984)
	lib.movie("Dusk 2021", 2019)
	lib.movie("Harbor\u2019s Dozen", 2001)
	lib.movie("M*I*S*T", 1970)
	lib.movie("Dr. Io", 1962)
	lib.movie("E.V.", 1982)
	lib.movie("Ox", 2009)
	lib.movie("Oxbow", 2015)
	lib.movie("Тихая гавань", 2010)
	lib.movie("The Cloud Castle", 1986, "Kumo no Shiro")
	lib.movie("Rockstar", 2011)
	show := lib.show("Été Indien", 2015)
	lib.episode(show, 1, 1, "Señor Lantern-Man")

	for _, c := range []struct {
		typed string
		want  []string // movie titles with their years, in order
	}{
		// Case beyond A to Z, and accents, typed or left out.
		{"cafe soleil", []string{"Café Soleil 2003"}},
		{"CAFÉ SOLEIL", []string{"Café Soleil 2003"}},
		{"cafe\u0301", []string{"Café Soleil 2003"}},
		{"ТИХАЯ", []string{"Тихая гавань 2010"}},
		// Punctuation, typed, left out or as a gap.
		{"lanternman", []string{"Lantern-Man: No Way Back 2019"}},
		{"lantern man", []string{"Lantern-Man: No Way Back 2019"}},
		{"Lantern-Man", []string{"Lantern-Man: No Way Back 2019"}},
		{"lantern man no way", []string{"Lantern-Man: No Way Back 2019"}},
		{"bolt e", []string{"BOLT·E 2008"}},
		{"bolte", []string{"BOLT·E 2008"}},
		{"bolt-e", []string{"BOLT·E 2008"}},
		{"harbors dozen", []string{"Harbor\u2019s Dozen 2001"}},
		{"harbor's dozen", []string{"Harbor\u2019s Dozen 2001"}},
		{"mist", []string{"M*I*S*T 1970"}},
		// Words in any order, and words left out.
		{"lord tides", []string{"The Lord of the Tides 2001"}},
		{"tides lord", []string{"The Lord of the Tides 2001"}},
		{"cloud castle", []string{"The Cloud Castle 1986"}},
		{"shiro kumo", []string{"The Cloud Castle 1986"}},
		// A year after the words: that year's title, and the title that
		// really has the year in it. Two of one name are in the order they
		// were added.
		{"dusk 2021", []string{"Dusk 2021", "Dusk 2021 2019"}},
		{"dusk (1984)", []string{"Dusk 1984"}},
		{"dusk", []string{"Dusk 2021", "Dusk 1984", "Dusk 2021 2019"}},
		{"dusk 1999", nil},
		// Words too short for the index.
		{"ox", []string{"Ox 2009", "Oxbow 2015"}},
		{"ox 2009", []string{"Ox 2009"}},
		{"dr io", []string{"Dr. Io 1962"}},
		{"ev", []string{"E.V. 1982"}},
		{"e v", []string{"E.V. 1982"}},
		// Anywhere in a word, as before.
		{"ockst", []string{"Rockstar 2011"}},
		{"st", []string{"M*I*S*T 1970", "Rockstar 2011", "The Cloud Castle 1986"}},
	} {
		res := search(t, srv, c.typed, 0)
		var got []string
		for _, m := range res.Movies {
			got = append(got, fmt.Sprintf("%s %d", m.Title, m.Year))
		}
		if !slices.Equal(got, c.want) {
			t.Errorf("%q found %q, want %q", c.typed, got, c.want)
		}
	}
	if got := showTitles(search(t, srv, "ete indien", 0)); !slices.Equal(got, []string{"Été Indien"}) {
		t.Errorf("ete indien found shows %q", got)
	}
	for _, typed := range []string{"senor lanternman", "SEÑOR lantern man", "lantern senor"} {
		if got := episodeTitles(search(t, srv, typed, 0)); !slices.Equal(got, []string{"Été Indien S1E1 Señor Lantern-Man"}) {
			t.Errorf("%q found episodes %q", typed, got)
		}
	}
}

// The three queries search ran before it had an index or an order, to
// check that everything they found is still found.
const (
	oldSearchMovies = `SELECT m.id FROM movies m
		JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
		WHERE m.title LIKE ?1 OR m.original_title LIKE ?1
		GROUP BY m.id`
	oldSearchShows = `SELECT s.id FROM shows s
		JOIN episodes e ON e.show_id = s.id
		JOIN episode_files ef ON ef.episode_id = e.id
		JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
		WHERE s.title LIKE ?1 OR s.original_title LIKE ?1
		GROUP BY s.id`
	oldSearchEpisodes = `SELECT e.id FROM episodes e
		JOIN seasons se ON se.id = e.season_id
		JOIN shows s ON s.id = e.show_id
		JOIN episode_files ef ON ef.episode_id = e.id
		JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
		WHERE e.title LIKE ?1
		GROUP BY e.id`
)

// TestSearchKeepsWhatItFound runs the old queries and the new search over
// the same titles for a few thousand things someone might type, most of
// them pieces of the titles themselves, and checks that every title the
// old ones found is still found.
func TestSearchKeepsWhatItFound(t *testing.T) {
	t.Parallel()
	var deps Deps
	srv := newTestServerWith(t, func(d *Deps) { deps = *d })
	lib := newSearchLib(t, srv.conn)
	r := rand.New(rand.NewPCG(11, 12)) //nolint:gosec // a fixed seed: the same titles on every run
	titles := []string{
		"100% Wolf_Moon", "Wait!?", "!?", "a_b", "Cab Ride", "50/50 Split", "Mr. & Mrs. Vane", "…And Then", "#Hashtag",
		"Cafe\u0301 Noir", "Café Noir", "ÉTÉ", "été", "Тихая гавань", "ТИХАЯ ночь", "夜の港", "한국 영화", "Ærø", "Straße",
		"Lantern-Man", "LanternMan", "Lantern Man", "BOLT·E", "Bolt-E", "E.V.", "M*I*S*T", "Ox", "Io", "X", "2021", "Dusk 2021", "  Spaced  Out  ",
	}
	for len(titles) < 500 {
		titles = append(titles, searchTitle(r))
	}
	var shows []int64
	for i, title := range titles {
		var id int64
		if i%9 == 0 {
			id = lib.movie(title, 1960+i%60, searchTitle(r))
		} else {
			id = lib.movie(title, 1960+i%60)
		}
		switch i % 11 {
		case 3: // no file at all
			lib.exec(`DELETE FROM media_files WHERE movie_id = ?`, id)
		case 7: // its file is missing
			lib.exec(`UPDATE media_files SET status = 'missing' WHERE movie_id = ?`, id)
		}
		if i%4 == 0 {
			shows = append(shows, lib.show(title, 1990+i%30))
		}
	}
	for i, show := range shows {
		if i%6 == 5 {
			continue // a show with no files
		}
		for n := range 4 {
			title := titles[(i*7+n*13)%len(titles)]
			id := lib.episode(show, 1+n/2, 1+n%2, title)
			if (i+n)%10 == 0 {
				lib.exec(`UPDATE media_files SET status = 'missing' WHERE id IN (SELECT media_file_id FROM episode_files WHERE episode_id = ?)`, id)
			}
		}
	}

	// Things to type: every title, and pieces of titles cut anywhere, in
	// either case, so with their punctuation, spaces and accents; then
	// every pair of letters.
	typed := map[string]bool{}
	for _, title := range titles {
		typed[title] = true
		runes := []rune(title)
		for range 6 {
			from := r.IntN(len(runes))
			piece := string(runes[from : from+1+r.IntN(min(8, len(runes)-from))])
			switch r.IntN(3) {
			case 0:
				piece = strings.ToUpper(piece)
			case 1:
				piece = strings.ToLower(piece)
			}
			typed[piece] = true
		}
	}
	// Pieces a random cut seldom makes: an accent typed apart from its
	// letter in a word of two letters, the wildcards, marks alone, single
	// characters of scripts without spaces, and a year.
	for _, q := range []string{
		"fe\u0301", "e\u0301", "É", "é", "ét", "%", "_", "a_b", "0%", "100%", "!?", "·", "-", ".", " x ", "ß", "ø", "Æ",
		"夜", "夜の", "の港", "한국", "한", "영화", "ТИ", "ти", "2021", "dusk 2021", "Dusk 20", "t·e", "T-E", "e.v", "*S*",
	} {
		typed[q] = true
	}
	for a := 'a'; a <= 'z'; a++ {
		typed[string(a)] = true
		for b := 'a'; b <= 'z'; b++ {
			typed[string(a)+string(b)] = true
		}
	}

	ctx := t.Context()
	ids := func(query, pattern string) map[int64]bool {
		rows, err := srv.conn.QueryContext(ctx, query, pattern)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = rows.Close() }()
		out := map[int64]bool{}
		for rows.Next() {
			var id int64
			if err := rows.Scan(&id); err != nil {
				t.Fatal(err)
			}
			out[id] = true
		}
		if err := rows.Err(); err != nil {
			t.Fatal(err)
		}
		return out
	}
	// Under -short, which the race detector's run passes, one search in
	// eight: each is five searches of the library.
	every, enough := 1, 2000
	if testing.Short() {
		every, enough = 8, 250
	}
	asked := slices.Sorted(maps.Keys(typed))
	const all = 1 << 30
	searches, before, after, stopped := 0, 0, 0, 0
	for i, q := range asked {
		// What the handler is given: at least one character, at most 100.
		if strings.TrimSpace(q) == "" || i%every != 0 {
			continue
		}
		searches++
		// The pattern exactly as the old handler built it.
		pattern := "%" + strings.ReplaceAll(strings.ReplaceAll(strings.TrimSpace(q), "%", ""), "_", "") + "%"
		res, err := searchLibrary(ctx, deps, q, all)
		if err != nil {
			t.Fatalf("%q: %v", q, err)
		}
		found := map[string]map[int64]bool{"movies": {}, "shows": {}, "episodes": {}}
		for _, m := range res.Movies {
			found["movies"][m.ID] = true
		}
		for _, s := range res.Shows {
			found["shows"][s.ID] = true
		}
		for _, e := range res.Episodes {
			found["episodes"][e.ID] = true
		}
		for group, query := range map[string]string{"movies": oldSearchMovies, "shows": oldSearchShows, "episodes": oldSearchEpisodes} {
			old := ids(query, pattern)
			before += len(old)
			after += len(found[group])
			for id := range old {
				if !found[group][id] {
					t.Errorf("%q no longer finds %s %d", q, group, id)
				}
			}
		}
		// A list of twenty is the first twenty of everything found: a
		// short search that stops at the titles whose words start with
		// its letters leaves out nothing that belongs before them.
		first, err := searchLibrary(ctx, deps, q, 20)
		if err != nil {
			t.Fatalf("%q: %v", q, err)
		}
		if got, want := searchIDs(first), searchIDs(firstOf(res, 20)); !slices.Equal(got, want) {
			t.Errorf("%q: a list of twenty is %v, the first twenty of all is %v", q, got, want)
		}
		s := parseSearch(q)
		if hits, err := searchMovieHits(ctx, deps.Queries, &s, 20); err == nil && len(hits) < len(res.Movies) {
			stopped++
		}
		if t.Failed() {
			t.FailNow()
		}
	}
	t.Logf("%d searches: %d titles found before, %d now; %d stopped at the titles whose words start with the letters", searches, before, after, stopped)
	if searches < enough || before < 5*enough || after <= before || stopped < enough/100 {
		t.Errorf("the comparison is too small to mean anything, or nothing new is found")
	}
}

// firstOf is the first n of each group.
func firstOf(r *SearchResults, n int) *SearchResults {
	return &SearchResults{Movies: r.Movies[:min(n, len(r.Movies))], Shows: r.Shows[:min(n, len(r.Shows))], Episodes: r.Episodes[:min(n, len(r.Episodes))]}
}

// searchIDs is every id of a search's results, in order, group by group.
func searchIDs(r *SearchResults) []int64 {
	var ids []int64
	for _, m := range r.Movies {
		ids = append(ids, m.ID)
	}
	ids = append(ids, 0)
	for _, s := range r.Shows {
		ids = append(ids, s.ID)
	}
	ids = append(ids, 0)
	for _, e := range r.Episodes {
		ids = append(ids, e.ID)
	}
	return ids
}

// checkSearchIndex fails unless each search index holds exactly its
// table's titles, and passes SQLite's own check of an FTS index.
func checkSearchIndex(t *testing.T, conn *sql.DB) {
	t.Helper()
	ctx := t.Context()
	for _, c := range []struct{ table, original string }{{"movies", "original_title"}, {"shows", "original_title"}, {"episodes", "NULL"}} {
		var rows, indexed, same int
		indexOriginal := "x.original_title"
		if c.original == "NULL" {
			indexOriginal = "NULL"
		}
		err := conn.QueryRowContext(ctx, fmt.Sprintf(`SELECT (SELECT COUNT(*) FROM %[1]s), (SELECT COUNT(*) FROM %[1]s_search),
			(SELECT COUNT(*) FROM %[1]s t JOIN %[1]s_search x ON x.rowid = t.id WHERE x.title IS t.title AND %[3]s IS t.%[2]s)`,
			c.table, c.original, indexOriginal)).Scan(&rows, &indexed, &same)
		if c.original == "NULL" {
			err = conn.QueryRowContext(ctx, fmt.Sprintf(`SELECT (SELECT COUNT(*) FROM %[1]s), (SELECT COUNT(*) FROM %[1]s_search),
				(SELECT COUNT(*) FROM %[1]s t JOIN %[1]s_search x ON x.rowid = t.id WHERE x.title IS t.title)`, c.table)).Scan(&rows, &indexed, &same)
		}
		if err != nil {
			t.Fatal(err)
		}
		if rows != indexed || rows != same {
			t.Errorf("%s: %d rows, %d in the index, %d of them with the row's titles", c.table, rows, indexed, same)
		}
		if _, err := conn.ExecContext(ctx, fmt.Sprintf(`INSERT INTO %[1]s_search (%[1]s_search) VALUES ('integrity-check')`, c.table)); err != nil {
			t.Errorf("%s index: %v", c.table, err)
		}
	}
}

// TestSearchIndexFollowsTheLibrary makes each change a library goes
// through, with the queries the scanner and matcher use, and checks after
// each that search finds what is there and nothing that is not.
func TestSearchIndexFollowsTheLibrary(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	lib := newSearchLib(t, srv.conn)
	ctx, q := t.Context(), srv.q
	finds := func(typed string, movies, shows, episodes []string) {
		t.Helper()
		res := search(t, srv, typed, 0)
		if got := movieTitles(res); !slices.Equal(got, movies) {
			t.Errorf("%q found movies %q, want %q", typed, got, movies)
		}
		if got := showTitles(res); !slices.Equal(got, shows) {
			t.Errorf("%q found shows %q, want %q", typed, got, shows)
		}
		if got := episodeTitles(res); !slices.Equal(got, episodes) {
			t.Errorf("%q found episodes %q, want %q", typed, got, episodes)
		}
		checkSearchIndex(t, srv.conn)
	}

	// A scan finds a file and the matcher stores its movie.
	movie, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 501, Title: "Lantern-Man", OriginalTitle: new("Der Laternenmann"), Year: new(int64(2019)), Genres: "[]", Credits: "[]", Trailers: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	finds("lanternman", nil, nil, nil) // no file yet
	file := lib.file(1, movie.ID)
	finds("lanternman", []string{"Lantern-Man"}, nil, nil)
	finds("laternen", []string{"Lantern-Man"}, nil, nil)

	// A refresh stores the same titles, then TMDB renames it.
	if _, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 501, Title: "Lantern-Man", OriginalTitle: new("Der Laternenmann"), Year: new(int64(2019)), Genres: "[]", Credits: "[]", Trailers: "[]"}); err != nil {
		t.Fatal(err)
	}
	finds("lanternman", []string{"Lantern-Man"}, nil, nil)
	if _, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 501, Title: "The Glass Harbor", Year: new(int64(2019)), Genres: "[]", Credits: "[]", Trailers: "[]"}); err != nil {
		t.Fatal(err)
	}
	finds("lantern", nil, nil, nil)
	finds("laternen", nil, nil, nil)
	finds("glass harbor", []string{"The Glass Harbor"}, nil, nil)

	// A title edited in place, as a correction would.
	lib.exec(`UPDATE movies SET title = 'The Glass Harbour' WHERE id = ?`, movie.ID)
	finds("harbor", nil, nil, nil)
	finds("harbour", []string{"The Glass Harbour"}, nil, nil)

	// The file is matched again, to another movie; the first has no file.
	other, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 502, Title: "Glass Onward", Genres: "[]", Credits: "[]", Trailers: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	lib.exec(`UPDATE media_files SET movie_id = ? WHERE id = ?`, other.ID, file)
	finds("glass", []string{"Glass Onward"}, nil, nil)

	// The file goes missing, and comes back.
	lib.exec(`UPDATE media_files SET status = 'missing' WHERE id = ?`, file)
	finds("glass", nil, nil, nil)
	lib.exec(`UPDATE media_files SET status = 'present' WHERE id = ?`, file)
	finds("glass", []string{"Glass Onward"}, nil, nil)

	// A movie replaced whole, by a write that is no update.
	lib.exec(`INSERT OR REPLACE INTO movies (id, library_id, tmdb_id, title) VALUES (?, 1, 502, 'Paper Onward')`, other.ID)
	lib.exec(`UPDATE media_files SET movie_id = ? WHERE id = ?`, other.ID, file)
	finds("glass", nil, nil, nil)
	finds("paper onward", []string{"Paper Onward"}, nil, nil)

	// A write that gives way to the row already there changes nothing, and
	// the same write of a new row is indexed.
	lib.exec(`INSERT OR IGNORE INTO movies (id, library_id, tmdb_id, title) VALUES (?, 1, 502, 'Glass Again')`, other.ID)
	lib.exec(`INSERT OR IGNORE INTO movies (id, library_id, tmdb_id, title) VALUES (?, 1, 503, 'Tin Onward')`, other.ID+100)
	lib.exec(`UPDATE media_files SET movie_id = ? WHERE id = ?`, other.ID+100, lib.file(1, nil))
	finds("glass", nil, nil, nil)
	finds("onward", []string{"Paper Onward", "Tin Onward"}, nil, nil)
	if err := q.DeleteMovie(ctx, other.ID+100); err != nil {
		t.Fatal(err)
	}

	// Deleted.
	if err := q.DeleteMovie(ctx, other.ID); err != nil {
		t.Fatal(err)
	}
	if err := q.DeleteMovie(ctx, movie.ID); err != nil {
		t.Fatal(err)
	}
	finds("onward", nil, nil, nil)
	finds("glass", nil, nil, nil)

	// A show, its seasons and episodes, as the matcher stores them.
	show, err := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: lib.tv, TmdbID: 601, Title: "Harbor Road", Genres: "[]", Credits: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	season, err := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1})
	if err != nil {
		t.Fatal(err)
	}
	first, err := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 1, Title: "The Co-Pilot"})
	if err != nil {
		t.Fatal(err)
	}
	finds("harbor road", nil, nil, nil) // TMDB lists it; no file yet
	finds("copilot", nil, nil, nil)
	epFile := lib.file(lib.tv, nil)
	lib.exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, first.ID, epFile)
	finds("harbor road", nil, []string{"Harbor Road"}, nil)
	finds("copilot", nil, nil, []string{"Harbor Road S1E1 The Co-Pilot"})

	// An episode TMDB does not list is named after its file, and takes
	// TMDB's title when TMDB lists it.
	if err := q.InsertFileNamedEpisode(ctx, db.InsertFileNamedEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 2, Title: "Harbor.Road.S01E02.Night.Ferry"}); err != nil {
		t.Fatal(err)
	}
	var second int64
	if err := srv.conn.QueryRowContext(ctx, `SELECT id FROM episodes WHERE season_id = ? AND number = 2`, season.ID).Scan(&second); err != nil {
		t.Fatal(err)
	}
	lib.exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, second, lib.file(lib.tv, nil))
	finds("night ferry", nil, nil, []string{"Harbor Road S1E2 Harbor.Road.S01E02.Night.Ferry"})
	if _, err := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 2, Title: "The Last Crossing"}); err != nil {
		t.Fatal(err)
	}
	finds("night ferry", nil, nil, nil)
	finds("last crossing", nil, nil, []string{"Harbor Road S1E2 The Last Crossing"})

	// A refresh renames the first episode and the show.
	if _, err := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: 1, Title: "Departures"}); err != nil {
		t.Fatal(err)
	}
	if _, err := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: lib.tv, TmdbID: 601, Title: "Harbour Road", Genres: "[]", Credits: "[]"}); err != nil {
		t.Fatal(err)
	}
	finds("copilot", nil, nil, nil)
	finds("departures", nil, nil, []string{"Harbour Road S1E1 Departures"})
	finds("harbor road", nil, nil, nil)
	finds("harbour", nil, []string{"Harbour Road"}, nil)

	// The first episode's file goes missing: the episode goes, the show
	// stays for the other.
	lib.exec(`UPDATE media_files SET status = 'missing' WHERE id = ?`, epFile)
	finds("departures", nil, nil, nil)
	finds("harbour", nil, []string{"Harbour Road"}, nil)

	// The library is removed, and its shows, seasons and episodes with it.
	if err := q.DeleteLibrary(ctx, lib.tv); err != nil {
		t.Fatal(err)
	}
	finds("harbour", nil, nil, nil)
	finds("crossing", nil, nil, nil)
	var left int
	if err := srv.conn.QueryRowContext(ctx, `SELECT (SELECT COUNT(*) FROM movies_search) + (SELECT COUNT(*) FROM shows_search) + (SELECT COUNT(*) FROM episodes_search)`).Scan(&left); err != nil {
		t.Fatal(err)
	}
	if left != 0 {
		t.Errorf("%d titles left in the index of an empty library", left)
	}
}

// searchBreaks are what the index takes a word to start after, besides the
// start of the text: a space, a line break, ASCII's punctuation, and the
// curled quotes, middle dot, dashes, ellipsis, inverted marks and
// guillemets. Migration 00045 writes them out for each table.
const searchBreaks = " \n!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" +
	"\u2019\u2018\u201c\u201d\u00b7\u2013\u2014\u2026\u00a1\u00bf\u00ab\u00bb"

// indexWords is what the index's words column holds for a title's text:
// the text, then the text without searchJoiners if it has any, with
// searchMark before every word.
func indexWords(text string) string {
	joined := strings.Map(func(r rune) rune {
		if strings.ContainsRune(searchJoiners, r) {
			return -1
		}
		return r
	}, text)
	if joined != text {
		text += "\n" + joined
	}
	var b strings.Builder
	b.WriteString(searchMark)
	for _, r := range text {
		b.WriteRune(r)
		if strings.ContainsRune(searchBreaks, r) {
			b.WriteString(searchMark)
		}
	}
	return b.String()
}

// TestSearchIndexWords checks the words the index keeps for a title, in
// all three tables, against the marks this package matches by: the
// migration spells them out for each table, and a mark missing from one
// would leave titles that search cannot find by their first letters.
func TestSearchIndexWords(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	lib := newSearchLib(t, srv.conn)
	ctx := t.Context()
	titles := []string{"Plain Title", "\u00c9t\u00e9 Indien", "Tab\tAnd Symbols \u2605 \u30fb here"}
	// A word after each mark, and each mark at either end.
	for _, r := range searchBreaks + "~" {
		titles = append(titles, "Lantern"+string(r)+"Man", string(r)+"Edge"+string(r))
	}
	titles = append(titles, "All"+searchBreaks+"Together")
	show := lib.show("Harbor Road", 2012)
	for i, title := range titles {
		lib.movie(title, 2000, "Original "+title)
		lib.movie(title, 2000)
		lib.exec(`INSERT INTO shows (library_id, tmdb_id, title, original_title) VALUES (?, ?, ?, ?)`, lib.tv, 5000+i, "Show "+title, title)
		lib.episode(show, 1, 1+i, title)
	}
	for _, c := range []struct{ table, text string }{
		{"movies", `t.title || char(10) || COALESCE(t.original_title, '')`},
		{"shows", `t.title || char(10) || COALESCE(t.original_title, '')`},
		{"episodes", `t.title`},
	} {
		n := 0
		func() {
			rows, err := srv.conn.QueryContext(ctx, fmt.Sprintf(`SELECT %s, x.words FROM %s t JOIN %s_search x ON x.rowid = t.id`, c.text, c.table, c.table))
			if err != nil {
				t.Fatal(err)
			}
			defer func() { _ = rows.Close() }()
			for rows.Next() {
				var text, words string
				if err := rows.Scan(&text, &words); err != nil {
					t.Fatal(err)
				}
				n++
				if want := indexWords(text); words != want {
					t.Errorf("%s %q: the index keeps %q, want %q", c.table, text, words, want)
				}
			}
			if err := rows.Err(); err != nil {
				t.Fatal(err)
			}
		}()
		if n < len(titles) {
			t.Errorf("%s: %d rows read", c.table, n)
		}
	}
	checkSearchIndex(t, srv.conn)

	// And through search: a word after any of the marks is found by its
	// first letter, and a title with a joiner by its letters run together.
	lib2 := newTestServer(t)
	l := newSearchLib(t, lib2.conn)
	for i, r := range searchBreaks {
		l.movie(fmt.Sprintf("Quiet%sZebra %02d", string(r), i), 2000)
	}
	if got := search(t, lib2, "z", 50); len(got.Movies) != len([]rune(searchBreaks)) {
		t.Errorf("z found %d of %d titles with a word starting with it", len(got.Movies), len([]rune(searchBreaks)))
	}
	if got := search(t, lib2, "quietzebra", 50); len(got.Movies) != len([]rune(searchJoiners)) {
		t.Errorf("quietzebra found %d titles, want one for each of %d joiners", len(got.Movies), len([]rune(searchJoiners)))
	}
}

// TestSearchIndexKeepsLetters checks what searchLetters relies on: the
// index counts every letter and digit as a character, in every script, so
// a word with three of them can be looked up. Asked for a shorter word the
// index finds nothing, and what search found before would be lost. Each
// title here is one character three times, and the index must find each
// when asked for it.
func TestSearchIndexKeepsLetters(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	tx, err := srv.conn.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	insert, err := tx.PrepareContext(ctx, `INSERT INTO movies (id, library_id, tmdb_id, title) VALUES (?, 1, ?, ?)`)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = insert.Close() }()
	find, err := tx.PrepareContext(ctx, `SELECT COUNT(*) FROM movies_search(?) WHERE rowid = ?`)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = find.Close() }()
	var runes []rune
	for r := rune(0x30); r <= unicode.MaxRune; r++ {
		if !unicode.IsLetter(r) && !unicode.IsNumber(r) {
			continue
		}
		// Every letter and digit of the scripts with few, and one in
		// thirty of those with tens of thousands; one in four of all those
		// under -short, which the race detector's run passes.
		if r >= 0x3400 && r%31 != 0 || testing.Short() && r%4 != 0 {
			continue
		}
		runes = append(runes, r)
		if _, err := insert.ExecContext(ctx, int64(r), int64(r), strings.Repeat(string(r), 3)); err != nil {
			t.Fatal(err)
		}
	}
	for _, r := range runes {
		var n int
		if err := find.QueryRowContext(ctx, `"`+strings.Repeat(string(r), 3)+`"`, int64(r)).Scan(&n); err != nil {
			t.Fatalf("%q (U+%04X): %v", r, r, err)
		}
		if n != 1 {
			t.Errorf("the index drops %q (U+%04X), which searchLetters counts", r, r)
		}
	}
	if len(runes) < 2500 {
		t.Errorf("only %d characters checked", len(runes))
	}
}

// droppedDB is the database with a search held at its first query until
// the request's context ends.
type droppedDB struct {
	*sql.DB
	started  chan struct{}
	once     sync.Once
	ended    atomic.Bool
	laterRan atomic.Int32
}

func (d *droppedDB) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	if !strings.Contains(query, "_search") {
		return d.DB.QueryContext(ctx, query, args...)
	}
	first := false
	d.once.Do(func() { first = true })
	if !first {
		d.laterRan.Add(1)
		return d.DB.QueryContext(ctx, query, args...)
	}
	close(d.started)
	select {
	case <-ctx.Done():
		d.ended.Store(true)
	case <-time.After(10 * time.Second):
	}
	// The query itself, on the context that has ended.
	return d.DB.QueryContext(ctx, query, args...)
}

// TestSearchStopsWhenDropped checks that a search an app gave up on, as it
// does at the next keystroke, stops: closing the connection ends the
// context its queries run on, the one running returns, and the rest never
// start.
func TestSearchStopsWhenDropped(t *testing.T) {
	t.Parallel()
	held := &droppedDB{started: make(chan struct{})}
	srv := newTestServerWith(t, func(d *Deps) {
		held.DB = d.Conn
		d.Queries = db.New(held)
	})
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/api/search?q=lantern", nil)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() {
		res, err := srv.client.Do(req)
		if err == nil {
			_ = res.Body.Close()
		}
		done <- err
	}()
	select {
	case <-held.started:
	case <-time.After(10 * time.Second):
		t.Fatal("the search never reached the database")
	}
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("the request ended with %v", err)
	}
	// The handler returns once its query does; the server closing waits
	// for it.
	srv.Close()
	if !held.ended.Load() {
		t.Error("the search's context was still running 10 s after the connection closed")
	}
	if n := held.laterRan.Load(); n != 0 {
		t.Errorf("%d more queries ran for a search nobody was waiting for", n)
	}

	// And a query on a context that ends while it runs returns then, not
	// when it has read everything: this one counts to a hundred million.
	short, stop := context.WithTimeout(t.Context(), 50*time.Millisecond)
	defer stop()
	conn := held.DB
	begin := time.Now()
	var n int64
	err = conn.QueryRowContext(short, `WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 100000000) SELECT COUNT(*) FROM c`).Scan(&n)
	if err == nil || time.Since(begin) > 5*time.Second {
		t.Errorf("a query outlived its context: %v after %v", err, time.Since(begin).Round(time.Millisecond))
	}
}
