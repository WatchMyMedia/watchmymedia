package api

import (
	"database/sql"
	"fmt"
	"math/rand/v2"
	"net/http"
	"net/url"
	"os"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

// searchWords are the words the speed test's titles are made of, the
// commonest first: made-up titles, with the shape real ones have (a few
// words, many starting with "The", some sharing a word's first letters).
var searchWords = strings.Fields(`the of and a in to night day star house man love last city dark girl
	king war blood home life dead world time lost secret black white red road river heart fire story
	game long little big new old good bad wild blue green gold silver stone iron glass paper water
	summer winter spring autumn morning midnight shadow light moon sun sky sea island mountain forest
	garden street station bridge tower castle kingdom empire country town village harbor market school
	hospital hotel kitchen office court prison church library museum theatre circus carnival festival
	mother father sister brother daughter son wife husband friend stranger neighbor doctor teacher
	detective soldier captain pilot sailor farmer hunter singer dancer painter writer thief spy ghost
	witch wizard dragon wolf fox bear lion tiger eagle raven horse dog cat fish bird snake spider
	angel devil saint sinner hero villain queen prince princess knight baron duke lady lord master
	journey return escape revenge promise mystery murder trial wedding funeral birthday holiday
	letter song dance dream memory silence whisper echo storm thunder rain snow ice wind dust ash
	steel copper velvet silk crimson scarlet amber ivory ebony violet indigo emerald sapphire ruby
	first second third seven nine hundred thousand zero double triple final only other
	starlight starfall stargazer stairway stranded strange street strong standing statue
	northern southern eastern western hidden broken frozen burning falling rising running
	quiet loud bright pale deep high low far near early late young ancient modern`)

// searchTitle makes a title of one to four words. Some start with "The",
// and a few carry what people leave out when they type: a hyphen, an
// apostrophe, a subtitle after a colon, an accent, a number.
func searchTitle(r *rand.Rand) string {
	word := func() string {
		// The commonest words are far commoner, as they are in titles.
		w := searchWords[4+int(float64(len(searchWords)-4)*r.Float64()*r.Float64())]
		return strings.ToUpper(w[:1]) + w[1:]
	}
	n := 1 + r.IntN(4)
	parts := make([]string, 0, n+1)
	if r.IntN(10) < 3 {
		parts = append(parts, "The")
	}
	for range n {
		parts = append(parts, word())
	}
	title := strings.Join(parts, " ")
	switch r.IntN(20) {
	case 0:
		title = strings.Replace(title, " ", "-", 1)
	case 1:
		title = parts[0] + "'s " + word()
	case 2:
		title += ": The " + word()
	case 3:
		title = strings.Replace(title, "e", "é", 1)
	case 4:
		title += fmt.Sprintf(" %d", 2+r.IntN(3))
	}
	return title
}

// seedSearchTitles gives the large seeded library titles to search and rows
// as wide as a matched library's: a description on every movie, show and
// episode, and a movie's credits. A fifth of the episodes keep the plain
// "Episode 7" TMDB gives an untitled one.
func seedSearchTitles(tb testing.TB, conn *sql.DB) {
	tb.Helper()
	ctx := tb.Context()
	r := rand.New(rand.NewPCG(7, 8)) //nolint:gosec // a fixed seed: the same titles on every run
	text := func(n int) string {
		var b strings.Builder
		for b.Len() < n {
			b.WriteString(searchWords[r.IntN(len(searchWords))])
			b.WriteByte(' ')
		}
		return b.String()
	}
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	ids := func(table string) []int64 {
		rows, err := tx.QueryContext(ctx, `SELECT id FROM `+table+` ORDER BY id`)
		if err != nil {
			tb.Fatal(err)
		}
		defer func() { _ = rows.Close() }()
		var out []int64
		for rows.Next() {
			var id int64
			if err := rows.Scan(&id); err != nil {
				tb.Fatal(err)
			}
			out = append(out, id)
		}
		if err := rows.Err(); err != nil {
			tb.Fatal(err)
		}
		return out
	}
	exec := func(q string, args ...any) {
		if _, err := tx.ExecContext(ctx, q, args...); err != nil {
			tb.Fatal(err)
		}
	}
	for _, id := range ids("movies") {
		title := searchTitle(r)
		var original any
		if r.IntN(8) == 0 {
			original = searchTitle(r)
		}
		exec(`UPDATE movies SET title = ?, original_title = ?, overview = ?, credits = ? WHERE id = ?`, title, original, text(400), text(1500), id)
	}
	for _, id := range ids("shows") {
		exec(`UPDATE shows SET title = ?, overview = ?, credits = ? WHERE id = ?`, searchTitle(r), text(400), text(1500), id)
	}
	for _, id := range ids("episodes") {
		if r.IntN(5) == 0 {
			exec(`UPDATE episodes SET overview = ? WHERE id = ?`, text(250), id)
			continue
		}
		exec(`UPDATE episodes SET title = ?, overview = ? WHERE id = ?`, searchTitle(r), text(250), id)
	}
	if err := tx.Commit(); err != nil {
		tb.Fatal(err)
	}
}

// TestSearchSpeed times a search over the large seeded library, alone and
// ten at once: every keystroke in a search box is one, from the first
// letter, and an app signed in to several servers sends each of them the
// same words and waits for all. It checks the plans of the queries behind
// it without statistics, and times the question an app asks a server
// before it switches to it (GET /api/server) while ten searches run.
func TestSearchSpeed(t *testing.T) {
	if testing.Short() {
		t.Skip("seeds a large library")
	}
	if raceEnabled {
		t.Skip("the race detector makes timings meaningless")
	}
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) { conn = d.Conn })
	begin := time.Now()
	seedHomeLibrary(t, conn, time.Now())
	seedSearchTitles(t, conn)
	t.Logf("seeded in %v", time.Since(begin).Round(time.Millisecond))

	// Each title query reads the index first, then the table by row, and
	// asks "has a present file" from the title's own files. Left to
	// itself SQLite starts that from every present file in the library.
	ctx := t.Context()
	for _, c := range []struct {
		name string
		run  func(*db.Queries)
	}{
		{"movie titles", func(q *db.Queries) {
			_, _ = q.SearchMovieTitles(ctx, db.SearchMovieTitlesParams{Legacy: "%harbor%", Words: `"harbor"`})
		}},
		{"movie scan", func(q *db.Queries) {
			_, _ = q.ScanMovieTitles(ctx, db.ScanMovieTitlesParams{Legacy: "%st%", Word1: "%st%", Word2: "%", Word3: "%"})
		}},
		{"show titles", func(q *db.Queries) {
			_, _ = q.SearchShowTitles(ctx, db.SearchShowTitlesParams{Legacy: "%harbor%", Words: `"harbor"`})
		}},
		{"show scan", func(q *db.Queries) {
			_, _ = q.ScanShowTitles(ctx, db.ScanShowTitlesParams{Legacy: "%st%", Word1: "%st%", Word2: "%", Word3: "%"})
		}},
		{"episode titles", func(q *db.Queries) {
			_, _ = q.SearchEpisodeTitles(ctx, db.SearchEpisodeTitlesParams{Legacy: "%harbor%", Words: `"harbor"`})
		}},
		{"episode scan", func(q *db.Queries) {
			_, _ = q.ScanEpisodeTitles(ctx, db.ScanEpisodeTitlesParams{Legacy: "%st%", Word1: "%st%", Word2: "%", Word3: "%"})
		}},
		{"episode rows", func(q *db.Queries) { _, _ = q.ListSearchEpisodesByIDs(ctx, []int64{1, 2, 3}) }},
	} {
		p := &planner{DB: conn, tb: t}
		c.run(db.New(p))
		t.Logf("%s plan:\n%s", c.name, p.plan)
		lines := strings.Split(p.plan, "\n")
		if c.name != "episode rows" && !strings.HasPrefix(lines[0], "SCAN x VIRTUAL TABLE") {
			t.Errorf("%s does not start from the index", c.name)
		}
		for _, line := range lines[1:] {
			if strings.HasPrefix(line, "SCAN") || strings.Contains(line, "(status=?)") {
				t.Errorf("%s plan reads a whole table or every present file (%q)", c.name, line)
			}
		}
	}

	// A title of three words or more, typed in full.
	var full string
	if err := conn.QueryRowContext(ctx, `SELECT title FROM movies WHERE title LIKE '% % %' AND title NOT LIKE '%:%' ORDER BY id LIMIT 1`).Scan(&full); err != nil {
		t.Fatal(err)
	}
	strict := os.Getenv("WMM_HOME_STRICT") == "1"
	cases := []struct {
		name   string
		q      string
		budget time.Duration
	}{
		{"one letter", "e", 60 * time.Millisecond},
		{"two letters", "st", 60 * time.Millisecond},
		{"common word", "the", 60 * time.Millisecond},
		{"untitled", "episode", 60 * time.Millisecond},
		{"one word", "harbor", 15 * time.Millisecond},
		{"full title", full, 15 * time.Millisecond},
		{"no match", "zq", 40 * time.Millisecond},
	}
	path := func(q string) string { return srv.URL + "/api/search?q=" + url.QueryEscape(q) }
	for _, c := range cases {
		var res SearchResults
		doJSON(t, srv.client, "GET", path(c.q), "", &res)
		median := medianOf(t, func() { getOK(t, srv.client, path(c.q)) })
		t.Logf("%q: %d movies, %d shows, %d episodes", c.q, len(res.Movies), len(res.Shows), len(res.Episodes))
		report(t, strict, c.name, median, c.budget)
	}
	// Ten searches at once queue on the database.
	median := medianOf(t, func() {
		var wg sync.WaitGroup
		for i := range 10 {
			wg.Go(func() { getOK(t, srv.client, path(cases[i%len(cases)].q)) })
		}
		wg.Wait()
	})
	report(t, strict, "10 searches", median, 400*time.Millisecond)

	// An app asks a server which one it is before it switches to it, and
	// gives it 5 seconds. The answer is three small reads, which must not
	// wait for the searches beside them.
	stop := make(chan struct{})
	var wg sync.WaitGroup
	for i := range 10 {
		wg.Go(func() {
			for n := i; ; n++ {
				select {
				case <-stop:
					return
				default:
					getOK(t, srv.client, path(cases[n%len(cases)].q))
				}
			}
		})
	}
	var asked []time.Duration
	for range 101 {
		start := time.Now()
		getOK(t, http.DefaultClient, srv.URL+"/api/server")
		asked = append(asked, time.Since(start))
	}
	close(stop)
	wg.Wait()
	slices.Sort(asked)
	t.Logf("which server, beside 10 searches: slowest of 101 %v", asked[len(asked)-1].Round(10*time.Microsecond))
	report(t, strict, "which server", asked[len(asked)/2], 25*time.Millisecond)
}
