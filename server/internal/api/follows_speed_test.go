package api

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

// seedFollows gives seedHomeLibrary's library what the migration would
// have: a quiet arrival for every episode on disk. Then the admin follows
// up to follows shows (every third), and a week of new episodes arrives,
// fresh of them, each show's missing episodes together, for followed shows
// and others. It returns the newest arrival's id before the week.
func seedFollows(tb testing.TB, conn *sql.DB, tv int64, follows, fresh int, now time.Time) int64 {
	tb.Helper()
	ctx := tb.Context()
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	exec := func(q string, args ...any) {
		if _, err := tx.ExecContext(ctx, q, args...); err != nil {
			tb.Fatal(err)
		}
	}
	exec(`INSERT INTO episode_arrivals (tmdb_id, season, number, episode_id, arrived_at, quiet)
		SELECT s.tmdb_id, se.number, e.number, e.id, MIN(f.added_at) AS arrived, 1 FROM episodes e
		JOIN seasons se ON se.id = e.season_id JOIN shows s ON s.id = e.show_id
		JOIN episode_files ef ON ef.episode_id = e.id JOIN media_files f ON f.id = ef.media_file_id
		GROUP BY s.tmdb_id, se.number, e.number ORDER BY arrived, s.tmdb_id, se.number, e.number`)
	var before int64
	if err := tx.QueryRowContext(ctx, `SELECT MAX(id) FROM episode_arrivals`).Scan(&before); err != nil {
		tb.Fatal(err)
	}
	// The admin follows every third show, from the first.
	exec(`INSERT INTO show_follows (user_id, tmdb_id, title, since_arrival, created_at)
		SELECT 1, tmdb_id, title, ?, ? FROM shows WHERE library_id = ? AND id % 3 = 0 ORDER BY id LIMIT ?`,
		before, db.FormatTime(now.Add(-30*24*time.Hour)), tv, follows)
	// New episodes over the week, oldest first, each with a file.
	// Read them all first: the transaction's one connection can't write
	// while the rows are open.
	eps := func() []int64 {
		rows, err := tx.QueryContext(ctx, `SELECT e.id FROM episodes e WHERE e.show_id IN (SELECT id FROM shows WHERE library_id = ?)
			AND NOT EXISTS (SELECT 1 FROM episode_files ef WHERE ef.episode_id = e.id) ORDER BY e.show_id % 97, e.id LIMIT ?`, tv, fresh)
		if err != nil {
			tb.Fatal(err)
		}
		defer func() { _ = rows.Close() }()
		var eps []int64
		for rows.Next() {
			var id int64
			if err := rows.Scan(&id); err != nil {
				tb.Fatal(err)
			}
			eps = append(eps, id)
		}
		if err := rows.Err(); err != nil {
			tb.Fatal(err)
		}
		return eps
	}()
	for i, id := range eps {
		at := db.FormatTime(now.Add(-7*24*time.Hour + time.Duration(i+1)*7*24*time.Hour/time.Duration(len(eps)+1)).Add(-3 * time.Minute))
		exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, height, added_at, match_status)
			VALUES (?, ?, 1, ?, 'present', '{"kind":"episode"}', 1080, ?, 'matched')`, tv, fmt.Sprintf("/t/new/%d.mkv", id), at, at)
		exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, last_insert_rowid())`, id)
		exec(`INSERT INTO episode_arrivals (tmdb_id, season, number, episode_id, arrived_at, quiet)
			SELECT s.tmdb_id, se.number, e.number, e.id, ?, 0 FROM episodes e JOIN seasons se ON se.id = e.season_id JOIN shows s ON s.id = e.show_id
			WHERE e.id = ?`, at, id)
	}
	if err := tx.Commit(); err != nil {
		tb.Fatal(err)
	}
	return before
}

// planner stands in for the database under the generated queries: it runs
// EXPLAIN QUERY PLAN on a query instead, with the query's own parameters,
// and keeps the plan as SQLite makes it without statistics (the server
// never runs ANALYZE). The query itself then fails, which the caller
// ignores.
type planner struct {
	*sql.DB
	tb   testing.TB
	plan string
}

func (p *planner) QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error) {
	rows, err := p.DB.QueryContext(ctx, "EXPLAIN QUERY PLAN "+query, args...)
	if err != nil {
		p.tb.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var lines []string
	for rows.Next() {
		var id, parent, unused int
		var detail string
		if err := rows.Scan(&id, &parent, &unused, &detail); err != nil {
			p.tb.Fatal(err)
		}
		lines = append(lines, detail)
	}
	if err := rows.Err(); err != nil {
		p.tb.Fatal(err)
	}
	p.plan = strings.Join(lines, "\n")
	return nil, errors.New("planned")
}

// TestNewEpisodesSpeed times the feed devices check, the follows list and
// Home's followed row over the large seeded library, alone and ten at once
// (PLAN-NEW-EPISODES.md section 5.4), and checks the feed's and the row's
// plans without statistics: the person's follows, then each one's
// arrivals from (tmdb_id, id), and an episode row by show TMDB id, season
// and number, never a scan of all arrivals, shows or episodes.
func TestNewEpisodesSpeed(t *testing.T) {
	if testing.Short() {
		t.Skip("seeds a large library")
	}
	if raceEnabled {
		t.Skip("the race detector makes timings meaningless")
	}
	var conn *sql.DB
	var queries *db.Queries
	srv := newTestServerWith(t, func(d *Deps) { conn, queries = d.Conn, d.Queries })
	now := time.Now()
	tv := seedHomeLibrary(t, conn, now)
	before := seedFollows(t, conn, tv, maxFollows, 600, now)
	var follows, fresh int
	if err := conn.QueryRowContext(t.Context(), `SELECT (SELECT COUNT(*) FROM show_follows), (SELECT COUNT(*) FROM episode_arrivals WHERE quiet = 0)`).Scan(&follows, &fresh); err != nil {
		t.Fatal(err)
	}
	t.Logf("%d follows, %d new episodes this week, cursor %d", follows, fresh, before)

	week := db.FormatTime(now.Add(-newWindow))
	for _, c := range []struct {
		name string
		run  func(*db.Queries)
	}{
		{"feed", func(q *db.Queries) {
			_, _ = q.ListFollowedArrivals(t.Context(), db.ListFollowedArrivalsParams{UserID: 1, After: before, Upto: before + 1000, Since: week, MaxRows: feedMaxArrivals + 1})
		}},
		{"row", func(q *db.Queries) {
			_, _ = q.ListNewestFollowedArrivals(t.Context(), db.ListNewestFollowedArrivalsParams{UserID: 1, Since: week, MaxRows: 3 * followedRowLength})
		}},
		{"list", func(q *db.Queries) { _, _ = q.ListShowFollows(t.Context(), 1) }},
	} {
		p := &planner{DB: conn, tb: t}
		c.run(db.New(p))
		t.Logf("%s plan:\n%s", c.name, p.plan)
		for _, bad := range []string{"SCAN a\n", "SCAN episode_arrivals", "SCAN s\n", "SCAN shows", "SCAN e\n", "SCAN episodes", "SCAN mf\n", "SCAN media_files"} {
			if strings.Contains(p.plan+"\n", bad) {
				t.Errorf("%s plan reads a whole table (%q)", c.name, strings.TrimSpace(bad))
			}
		}
	}

	// The feed's reply is what devices notify: check it is the week's.
	feed, err := newEpisodes(t.Context(), queries, 1, fmt.Sprint(before), now)
	if err != nil {
		t.Fatal(err)
	}
	listed := 0
	for _, g := range feed.Shows {
		listed += g.Count
	}
	t.Logf("feed: %d shows, %d episodes, cursor %s", len(feed.Shows), listed, feed.Cursor)
	if len(feed.Shows) == 0 {
		t.Fatal("the feed is empty")
	}

	strict := os.Getenv("WMM_HOME_STRICT") == "1"
	cases := []struct {
		name   string
		path   string
		budget time.Duration
	}{
		{"feed", fmt.Sprintf("/api/me/new-episodes?after=%d", before), 10 * time.Millisecond},
		{"feed, no news", "/api/me/new-episodes?after=" + feed.Cursor, 10 * time.Millisecond},
		{"feed, first", "/api/me/new-episodes", 10 * time.Millisecond},
		{"follows", "/api/me/follows", 20 * time.Millisecond},
		{"home arranged", "/api/home?arranged=true", 150 * time.Millisecond},
	}
	for _, c := range cases {
		median := medianOf(t, func() { getOK(t, srv.client, srv.URL+c.path) })
		report(t, strict, c.name, median, c.budget)
	}
	row := medianOf(t, func() {
		if _, err := followedEpisodes(context.WithoutCancel(t.Context()), queries, 1, time.Now()); err != nil {
			t.Error(err)
		}
	})
	report(t, strict, "followed row", row, 10*time.Millisecond)

	// Ten devices checking at once queue on the database: SQLite runs
	// their queries one after another.
	for _, c := range []struct {
		name   string
		run    func()
		budget time.Duration
	}{
		{"10 feeds", func() { getOK(t, srv.client, srv.URL+cases[0].path) }, 60 * time.Millisecond},
		{"10 rows", func() {
			if _, err := followedEpisodes(context.WithoutCancel(t.Context()), queries, 1, time.Now()); err != nil {
				t.Error(err)
			}
		}, 60 * time.Millisecond},
	} {
		median := medianOf(t, func() {
			var wg sync.WaitGroup
			for range 10 {
				wg.Go(c.run)
			}
			wg.Wait()
		})
		report(t, strict, c.name, median, c.budget)
	}
	var home HomeData
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home?arranged=true", "", &home)
	for _, r := range home.Rows {
		if r.ID == rowFollowed && len(r.Episodes) == followedRowLength {
			return
		}
	}
	t.Errorf("Home has no full followed row: %v", rowIDs(home.Rows))
}
