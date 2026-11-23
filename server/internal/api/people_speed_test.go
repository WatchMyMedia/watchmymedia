package api

import (
	"context"
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
)

// seedCast gives every movie and show of the large seeded library a billed
// cast of 12 from 20,000 people, a few of them in many titles, as a real
// library's leads are.
func seedCast(tb testing.TB, conn *sql.DB) {
	tb.Helper()
	ctx := tb.Context()
	r := rand.New(rand.NewPCG(7, 8)) //nolint:gosec // a fixed seed: the same cast on every run
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	skewed := func(n int) int { return int(float64(n) * r.Float64() * r.Float64() * r.Float64()) }
	for _, kind := range []struct{ table, cast, id string }{{"movies", "movie_cast", "movie_id"}, {"shows", "show_cast", "show_id"}} {
		ids := queryIDs(tb, tx, `SELECT id FROM `+kind.table)
		func() {
			ins, err := tx.PrepareContext(ctx, fmt.Sprintf(`INSERT INTO %s (%s, billing, person_id, name, character, profile_path) VALUES (?, ?, ?, ?, ?, ?)`, kind.cast, kind.id))
			if err != nil {
				tb.Fatal(err)
			}
			defer func() { _ = ins.Close() }()
			for _, id := range ids {
				for billing := range 12 {
					person := 1 + skewed(20000)
					if _, err := ins.ExecContext(ctx, id, billing, person, fmt.Sprintf("Person %d", person), "Part", "/p.jpg"); err != nil {
						tb.Fatal(err)
					}
				}
			}
		}()
	}
	if err := tx.Commit(); err != nil {
		tb.Fatal(err)
	}
}

// queryIDs reads a column of IDs.
func queryIDs(tb testing.TB, q interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}, query string, args ...any) []int64 {
	tb.Helper()
	rows, err := q.QueryContext(tb.Context(), query, args...)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			tb.Fatal(err)
		}
		ids = append(ids, id)
	}
	if err := rows.Err(); err != nil {
		tb.Fatal(err)
	}
	return ids
}

// TestPersonSpeed times a person's page over the large seeded library with
// a billed cast on every title, for the person in the most titles and for
// one in a few, alone and ten at once, and checks the queries' plans
// without statistics: no whole-table reads.
func TestPersonSpeed(t *testing.T) {
	if testing.Short() {
		t.Skip("seeds a large library")
	}
	if raceEnabled {
		t.Skip("the race detector makes timings meaningless")
	}
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) { conn = d.Conn })
	ctx := t.Context()
	begin := time.Now()
	seedHomeLibrary(t, conn, time.Now())
	seedCast(t, conn)
	var busiest, typical, titles int64
	if err := conn.QueryRowContext(ctx, `SELECT person_id, COUNT(*) AS n FROM movie_cast GROUP BY person_id ORDER BY n DESC LIMIT 1`).Scan(&busiest, &titles); err != nil {
		t.Fatal(err)
	}
	if err := conn.QueryRowContext(ctx, `SELECT person_id FROM movie_cast GROUP BY person_id HAVING COUNT(*) = 3 LIMIT 1`).Scan(&typical); err != nil {
		t.Fatal(err)
	}
	t.Logf("seeded in %v; the busiest person is in %d movies", time.Since(begin).Round(time.Millisecond), titles)

	movieIDs := queryIDs(t, conn, `SELECT DISTINCT movie_id FROM movie_cast WHERE person_id = ?`, busiest)
	showIDs := queryIDs(t, conn, `SELECT DISTINCT show_id FROM show_cast WHERE person_id = ?`, busiest)
	for _, c := range []struct {
		name string
		run  func(*db.Queries)
	}{
		{"movie parts", func(q *db.Queries) { _, _ = q.ListPersonMovieCast(ctx, busiest) }},
		{"show parts", func(q *db.Queries) { _, _ = q.ListPersonShowCast(ctx, busiest) }},
		{"movie cards", func(q *db.Queries) { _, _ = q.ListMovieCardsByIDs(ctx, movieIDs) }},
		{"show cards", func(q *db.Queries) { _, _ = q.ListShowCardsByIDs(ctx, showIDs) }},
	} {
		p := &planner{DB: conn, tb: t}
		c.run(db.New(p))
		t.Logf("%s plan:\n%s", c.name, p.plan)
		for _, bad := range []string{"SCAN movies", "SCAN shows", "SCAN m\n", "SCAN s\n", "SCAN c\n", "SCAN f\n", "SCAN e\n", "SCAN movie_cast", "SCAN show_cast", "SCAN media_files"} {
			if strings.Contains(p.plan+"\n", bad) {
				t.Errorf("%s plan reads a whole table (%q)", c.name, strings.TrimSpace(bad))
			}
		}
	}

	strict := os.Getenv("WMM_HOME_STRICT") == "1"
	busyPath, typicalPath := fmt.Sprintf("/api/people/%d", busiest), fmt.Sprintf("/api/people/%d", typical)
	for _, c := range []struct {
		name   string
		path   string
		budget time.Duration
	}{
		{"busiest person", busyPath, 25 * time.Millisecond},
		{"typical person", typicalPath, 25 * time.Millisecond},
	} {
		median := medianOf(t, func() { getOK(t, srv.client, srv.URL+c.path) })
		report(t, strict, c.name, median, c.budget)
	}
	median := medianOf(t, func() {
		var wg sync.WaitGroup
		for i := range 10 {
			path := busyPath
			if i%2 == 1 {
				path = typicalPath
			}
			wg.Go(func() { getOK(t, srv.client, srv.URL+path) })
		}
		wg.Wait()
	})
	report(t, strict, "10 people", median, 200*time.Millisecond)
	var p PersonDetail
	doJSON(t, srv.client, http.MethodGet, srv.URL+busyPath, "", &p)
	if len(p.Movies) == 0 {
		t.Errorf("the busiest person has no movies: %+v", p)
	}
}
