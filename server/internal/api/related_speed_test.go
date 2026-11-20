package api

import (
	"database/sql"
	"fmt"
	"math/rand/v2"
	"net/http"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/related"
)

// seedRelatedTags gives every movie and show of the large seeded library
// tags as TMDB's would be: 20 recommendations, a
// third of them in the library; 15 keywords from 4,000, the common ones
// far commoner; three of 19 genres, Drama on half the titles; ten billed
// cast from 20,000 people and a director or creator from 2,000, a few of
// them in many titles; and the original language, English on four in five.
func seedRelatedTags(tb testing.TB, conn *sql.DB) {
	tb.Helper()
	ctx := tb.Context()
	r := rand.New(rand.NewPCG(5, 6)) //nolint:gosec // a fixed seed: the same tags on every run
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	genres := []string{"Drama", "Comedy", "Thriller", "Action", "Romance", "Crime", "Horror", "Adventure", "Science Fiction", "Family",
		"Fantasy", "Mystery", "Animation", "History", "Documentary", "Music", "War", "Western", "TV Movie"}
	langs := []string{"ko", "fr", "ja", "es", "de", "it", "hi"}
	// skewed picks lower numbers far more often, as popular keywords and
	// actors are.
	skewed := func(n int) int { return int(float64(n) * r.Float64() * r.Float64() * r.Float64()) }
	now := db.FormatTime(time.Now())
	titles := func(table string) (ids, tmdbIDs []int64) {
		rows, err := tx.QueryContext(ctx, fmt.Sprintf(`SELECT id, tmdb_id FROM %s`, table))
		if err != nil {
			tb.Fatal(err)
		}
		defer func() { _ = rows.Close() }()
		for rows.Next() {
			var id, tmdbID int64
			if err := rows.Scan(&id, &tmdbID); err != nil {
				tb.Fatal(err)
			}
			ids, tmdbIDs = append(ids, id), append(tmdbIDs, tmdbID)
		}
		if err := rows.Err(); err != nil {
			tb.Fatal(err)
		}
		return ids, tmdbIDs
	}
	for _, kind := range []struct{ table, id, maker, titles string }{
		{"movie_tags", "movie_id", related.KindDirector, "movies"},
		{"show_tags", "show_id", related.KindCreator, "shows"},
	} {
		ids, tmdbIDs := titles(kind.titles)
		seedTags(tb, tx, kind.table, kind.id, func(add func(id int64, k string, v any, rank int)) {
			for _, id := range ids {
				for i := range 20 {
					rec := int64(9_000_000 + r.IntN(1_000_000))
					if i%3 == 0 {
						rec = tmdbIDs[r.IntN(len(tmdbIDs))]
					}
					add(id, related.KindRec, rec, i)
				}
				for range 15 {
					add(id, related.KindKeyword, skewed(4000), 0)
				}
				if r.IntN(2) == 0 {
					add(id, related.KindGenre, "Drama", 0)
				}
				for range 2 {
					add(id, related.KindGenre, genres[1+skewed(len(genres)-1)], 0)
				}
				for i := range 10 {
					add(id, related.KindPerson, skewed(20000), i)
				}
				add(id, kind.maker, skewed(2000), 0)
				lang := "en"
				if r.IntN(5) == 0 {
					lang = langs[r.IntN(len(langs))]
				}
				add(id, related.KindLanguage, lang, 0)
			}
		})
		if _, err := tx.ExecContext(ctx, fmt.Sprintf(`UPDATE %s SET related_checked_at = ?`, kind.titles), now); err != nil {
			tb.Fatal(err)
		}
	}
	// Ten films of one collection.
	if _, err := tx.ExecContext(ctx, `UPDATE movies SET collection_id = 1, collection_name = 'The Long Collection' WHERE id % 285 = 0`); err != nil {
		tb.Fatal(err)
	}
	if err := tx.Commit(); err != nil {
		tb.Fatal(err)
	}
}

// seedTags inserts the tags fill adds into table, keyed by idColumn.
func seedTags(tb testing.TB, tx *sql.Tx, table, idColumn string, fill func(add func(id int64, k string, v any, rank int))) {
	tb.Helper()
	ctx := tb.Context()
	ins, err := tx.PrepareContext(ctx, fmt.Sprintf(`INSERT OR IGNORE INTO %s (%s, kind, value, rank) VALUES (?, ?, ?, ?)`, table, idColumn))
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = ins.Close() }()
	fill(func(id int64, k string, v any, rank int) {
		if _, err := ins.ExecContext(ctx, id, k, fmt.Sprint(v), rank); err != nil {
			tb.Fatal(err)
		}
	})
}

// TestRelatedSpeed times a movie's and a show's related rows over the large
// seeded library with tags, alone and ten at once, and
// checks the queries' plans without statistics: no whole-table reads.
func TestRelatedSpeed(t *testing.T) {
	if testing.Short() {
		t.Skip("seeds a large library")
	}
	if raceEnabled {
		t.Skip("the race detector makes timings meaningless")
	}
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) { conn = d.Conn })
	begin := time.Now()
	tv := seedHomeLibrary(t, conn, time.Now())
	seedRelatedTags(t, conn)
	var tags int
	if err := conn.QueryRowContext(t.Context(), `SELECT (SELECT COUNT(*) FROM movie_tags) + (SELECT COUNT(*) FROM show_tags)`).Scan(&tags); err != nil {
		t.Fatal(err)
	}
	t.Logf("seeded %d tags in %v", tags, time.Since(begin).Round(time.Millisecond))

	var movie, show int64
	if err := conn.QueryRowContext(t.Context(), `SELECT id FROM movies WHERE collection_id = 1 ORDER BY id LIMIT 1`).Scan(&movie); err != nil {
		t.Fatal(err)
	}
	if err := conn.QueryRowContext(t.Context(), `SELECT id FROM shows WHERE library_id = ? ORDER BY id LIMIT 1`, tv).Scan(&show); err != nil {
		t.Fatal(err)
	}

	ctx := t.Context()
	for _, c := range []struct {
		name string
		run  func(*db.Queries)
	}{
		{"tag matches", func(q *db.Queries) { _, _ = q.ListMovieTagMatches(ctx, movie) }},
		{"tag counts", func(q *db.Queries) { _, _ = q.CountMovieTagTitles(ctx, movie) }},
		{"recommended", func(q *db.Queries) { _, _ = q.ListMovieRecommended(ctx, movie) }},
		{"recommending", func(q *db.Queries) { _, _ = q.ListMoviesRecommending(ctx, "100000") }},
		{"tags of", func(q *db.Queries) { _, _ = q.ListMovieTagsOf(ctx, []int64{1, 2, 3}) }},
		{"collection", func(q *db.Queries) {
			_, _ = q.ListCollectionMovies(ctx, db.ListCollectionMoviesParams{CollectionID: new(int64(1)), TmdbID: 1})
		}},
		{"show tag matches", func(q *db.Queries) { _, _ = q.ListShowTagMatches(ctx, show) }},
		{"show recommended", func(q *db.Queries) { _, _ = q.ListShowRecommended(ctx, show) }},
	} {
		p := &planner{DB: conn, tb: t}
		c.run(db.New(p))
		t.Logf("%s plan:\n%s", c.name, p.plan)
		for _, bad := range []string{"SCAN movies", "SCAN shows", "SCAN m\n", "SCAN s\n", "SCAN t\n", "SCAN a\n", "SCAN b\n", "SCAN c\n", "SCAN movie_tags", "SCAN show_tags"} {
			if strings.Contains(p.plan+"\n", bad) {
				t.Errorf("%s plan reads a whole table (%q)", c.name, strings.TrimSpace(bad))
			}
		}
	}

	var rows RelatedRows
	doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/movies/%d/related", srv.URL, movie), "", &rows)
	t.Logf("movie rows: %v", rowIDs(rows.Rows))
	if ids := rowIDs(rows.Rows); len(ids) != 2 || len(rows.Rows[1].Items) != related.SimilarMax {
		t.Errorf("movie rows = %v, want the collection and a full More like this", ids)
	}

	strict := os.Getenv("WMM_HOME_STRICT") == "1"
	moviePath := fmt.Sprintf("/api/movies/%d/related", movie)
	showPath := fmt.Sprintf("/api/shows/%d/related", show)
	for _, c := range []struct {
		name   string
		path   string
		budget time.Duration
	}{
		{"movie related", moviePath, 25 * time.Millisecond},
		{"show related", showPath, 25 * time.Millisecond},
	} {
		median := medianOf(t, func() { getOK(t, srv.client, srv.URL+c.path) })
		report(t, strict, c.name, median, c.budget)
	}
	// Ten people opening pages at once queue on the database.
	median := medianOf(t, func() {
		var wg sync.WaitGroup
		for i := range 10 {
			path := moviePath
			if i%2 == 1 {
				path = showPath
			}
			wg.Go(func() { getOK(t, srv.client, srv.URL+path) })
		}
		wg.Wait()
	})
	report(t, strict, "10 related", median, 200*time.Millisecond)
}
