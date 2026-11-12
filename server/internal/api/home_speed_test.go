package api

import (
	"database/sql"
	"fmt"
	"io"
	"math/rand/v2"
	"net/http"
	"os"
	"slices"
	"sync"
	"testing"
	"time"

	"watchmymedia/server/internal/lists"
)

// seedHomeLibrary fills the test server's database the way a large
// household library looks: 2,850 movies and 1,100 shows (40,000 episodes on
// TMDB, 14,000 of them on disk), with 116 movie files and 1,300 episode
// files waiting on a match, spread over a hundred folders. A third of those
// folders belong to shows already in the library. Every other movie has a
// trailer file (PLAN-TRAILERS.md §5.2), pending as trailers stay, which
// every count and work list leaves out.
func seedHomeLibrary(tb testing.TB, conn *sql.DB, now time.Time) (tvLibrary int64) {
	tb.Helper()
	ctx := tb.Context()
	r := rand.New(rand.NewPCG(3, 4)) //nolint:gosec // a fixed seed: the same library on every run
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	exec := func(q string, args ...any) int64 {
		res, err := tx.ExecContext(ctx, q, args...)
		if err != nil {
			tb.Fatal(err)
		}
		id, _ := res.LastInsertId()
		return id
	}
	ts := func(t time.Time) string { return t.UTC().Format("2006-01-02T15:04:05.000Z") }
	added := func() string { return ts(now.Add(-time.Duration(r.IntN(2*365*24)) * time.Hour)) }
	file := func(lib int64, path, parsed, status string, movieID any, height int) int64 {
		return exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, height, added_at, match_status, movie_id)
			VALUES (?, ?, ?, ?, 'present', ?, ?, ?, ?, ?)`, lib, path, 1+r.IntN(4<<30), ts(now), parsed, height, added(), status, movieID)
	}

	for i := range 2850 {
		year := 1960 + r.IntN(66)
		rel := fmt.Sprintf("%d-%02d-%02d", year, 1+r.IntN(12), 1+r.IntN(28))
		id := exec(`INSERT INTO movies (library_id, tmdb_id, title, year, release_date, genres, poster_path) VALUES (1, ?, ?, ?, ?, '["Drama"]', '/p.jpg')`,
			100000+i, fmt.Sprintf("Movie %d", i), year, rel)
		file(1, fmt.Sprintf("/m/Movie %d (%d)/Movie %d.mkv", i, year, i), `{"kind":"movie"}`, "matched", id, 1080)
		if i%2 == 0 {
			// Not drawn from r, so the rest of the library stays as it was.
			exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, height, added_at, extra_kind, extra_of)
				VALUES (1, ?, 150000000, ?, 'present', '{}', 1080, ?, 'trailer', ?)`, fmt.Sprintf("/m/Movie %d (%d)/trailer.mp4", i, year), ts(now), ts(now), id)
		}
	}
	for i := range 116 {
		file(1, fmt.Sprintf("/m/Unknown %d (2001)/Unknown %d.mkv", i, i), fmt.Sprintf(`{"kind":"movie","title":"Unknown %d","year":2001}`, i), "unmatched", nil, 1080)
	}

	tvLibrary = exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'TV', '/t')`)
	type ep struct{ id, show, season, number int64 }
	var onDisk []ep
	for s := range 1100 {
		show := exec(`INSERT INTO shows (library_id, tmdb_id, title, status, genres, poster_path) VALUES (?, ?, ?, 'Returning Series', '["Drama"]', '/s.jpg')`,
			tvLibrary, 200000+s, fmt.Sprintf("Show %d", s))
		for season := int64(1); season <= int64(2+r.IntN(3)); season++ {
			count := 8 + r.IntN(8)
			sid := exec(`INSERT INTO seasons (show_id, number, episode_count) VALUES (?, ?, ?)`, show, season, count)
			for n := int64(1); n <= int64(count); n++ {
				air := now.AddDate(0, 0, -r.IntN(20*365)+30).Format("2006-01-02")
				eid := exec(`INSERT INTO episodes (season_id, show_id, number, title, air_date) VALUES (?, ?, ?, ?, ?)`, sid, show, n, fmt.Sprintf("Episode %d", n), air)
				if r.IntN(100) < 36 {
					onDisk = append(onDisk, ep{eid, show, season, n})
				}
			}
		}
	}
	for _, e := range onDisk {
		path := fmt.Sprintf("/t/Show %d/Season %d/Show %d - S%02dE%02d.mkv", e.show-1, e.season, e.show-1, e.season, e.number)
		f := file(tvLibrary, path, fmt.Sprintf(`{"kind":"episode","title":"Show %d","season":%d,"episodes":[%d]}`, e.show-1, e.season, e.number), "matched", nil, 1080)
		exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, e.id, f)
	}
	for i := range 1300 {
		folder := i % 100
		name := fmt.Sprintf("Unknown Show %d", folder)
		if folder < 33 {
			name = fmt.Sprintf("Show %d", folder*30) // a known show's folder: episodes to pick
		}
		path := fmt.Sprintf("/t/%s/Season 9/%s - S09E%02d.mkv", name, name, i)
		file(tvLibrary, path, fmt.Sprintf(`{"kind":"episode","title":%q,"season":9,"episodes":[%d]}`, name, i), "unmatched", nil, 720)
	}

	// The signed-in admin has watched some of it and is partway through a few.
	for i, e := range onDisk {
		switch {
		case i%7 == 0:
			exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed, updated_at) VALUES (1, 'episode', ?, 1, ?)`, e.id, added())
		case i%400 == 1:
			exec(`INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, updated_at)
				SELECT 1, 'episode', ?, media_file_id, 600000, 2400000, ? FROM episode_files WHERE episode_id = ?`, e.id, added(), e.id)
		}
	}
	if err := tx.Commit(); err != nil {
		tb.Fatal(err)
	}
	return tvLibrary
}

// TestHomeSpeed times the home screen and the library pages over a large
// seeded library against budgets at the median: the home screen is the
// first thing every client loads. `go test` (CI) fails only at five times
// the budget, since shared runners are slow and noisy; WMM_HOME_STRICT=1
// (make bench) holds each page to its budget.
func TestHomeSpeed(t *testing.T) {
	if testing.Short() {
		t.Skip("seeds a large library")
	}
	if raceEnabled {
		t.Skip("the race detector makes timings meaningless")
	}
	var conn *sql.DB
	srv := newTestServerWith(t, func(d *Deps) {
		conn = d.Conn
		// The list job runs, as on a server, so library lists' items are
		// cached until the library changes.
		d.Lists = lists.New(lists.Options{Queries: d.Queries, Conn: d.Conn, TMDB: d.TMDB, Bus: d.Bus})
		go d.Lists.Run(t.Context())
	})
	begin := time.Now()
	tv := seedHomeLibrary(t, conn, time.Now())
	seedHomeLists(t, conn, tv)
	t.Logf("seeded in %v", time.Since(begin).Round(time.Millisecond))

	var show int64
	if err := conn.QueryRowContext(t.Context(), `SELECT id FROM shows WHERE library_id = ? ORDER BY id LIMIT 1`, tv).Scan(&show); err != nil {
		t.Fatal(err)
	}

	strict := os.Getenv("WMM_HOME_STRICT") == "1"
	cases := []struct {
		name   string
		path   string
		budget time.Duration
	}{
		{"home", "/api/home", 150 * time.Millisecond},
		{"home arranged", "/api/home?arranged=true", 150 * time.Millisecond},
		{"home layout", "/api/me/rows/home", 50 * time.Millisecond},
		{"tv rows", fmt.Sprintf("/api/libraries/%d/rows", tv), 150 * time.Millisecond},
		{"tv items", fmt.Sprintf("/api/libraries/%d/items?sort=added&limit=60", tv), 150 * time.Millisecond},
		{"tv unmatched", fmt.Sprintf("/api/libraries/%d/unmatched", tv), 100 * time.Millisecond},
		{"show", fmt.Sprintf("/api/shows/%d", show), 10 * time.Millisecond},
		{"movie rows", "/api/libraries/1/rows", 100 * time.Millisecond},
		{"libraries", "/api/libraries", 50 * time.Millisecond},
	}
	for _, c := range cases {
		median := medianOf(t, func() { getOK(t, srv.client, srv.URL+c.path) })
		report(t, strict, c.name, median, c.budget)
	}

	// The web app's Home screen asks for these together (the page, and the
	// header's libraries, server and activity), so they queue on the one
	// database connection: the burst has a budget of its own.
	for _, home := range []string{"/api/home", "/api/home?arranged=true"} {
		burst := []string{home, "/api/libraries", "/api/server", "/api/activity"}
		median := medianOf(t, func() {
			var wg sync.WaitGroup
			for _, p := range burst {
				wg.Go(func() { getOK(t, srv.client, srv.URL+p) })
			}
			wg.Wait()
		})
		name := "home burst"
		if home != "/api/home" {
			name = "arranged burst"
		}
		report(t, strict, name, median, 150*time.Millisecond)
	}
	// The arranged Home drew every list row.
	var home HomeData
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/home?arranged=true", "", &home)
	lists := 0
	for _, r := range home.Rows {
		if r.ListID != 0 && len(r.Items) == rowItems {
			lists++
		}
	}
	if lists != 13 {
		t.Errorf("Home drew %d full list rows of 13", lists)
	}
}

// seedHomeLists makes Home's layout as full as a household might: ten
// household lists of 250 entries each, two thirds of them movies in the
// library and the rest shows, and three library lists (unwatched movies,
// dramas by title, continuing shows by last aired).
func seedHomeLists(tb testing.TB, conn *sql.DB, tvLibrary int64) {
	tb.Helper()
	ctx := tb.Context()
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		tb.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()
	list := func(name, kind, mediaType, params string) int64 {
		res, err := tx.ExecContext(ctx, `INSERT INTO media_lists (screen, name, source_kind, params, media_type, entry_limit) VALUES ('home', ?, ?, ?, ?, 250)`, name, kind, params, mediaType)
		if err != nil {
			tb.Fatal(err)
		}
		id, _ := res.LastInsertId()
		return id
	}
	for k := range 10 {
		id := list(fmt.Sprintf("List %d", k), "import", "all", `{"format":"lines"}`)
		for r := 1; r <= 250; r++ {
			kind, tmdbID := "movie", 100000+(k*131+r*7)%2850
			if r%3 == 0 {
				kind, tmdbID = "tv", 200000+(k*97+r)%1100
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO media_list_entries (list_id, rank, media_type, tmdb_id, title) VALUES (?, ?, ?, ?, 'x')`, id, r, kind, tmdbID); err != nil {
				tb.Fatal(err)
			}
		}
	}
	list("Unwatched", "library", "movie", `{"libraryId":1,"filter":"unwatched"}`)
	list("Dramas", "library", "movie", `{"libraryId":1,"genre":"Drama","sort":"title"}`)
	list("Continuing", "library", "tv", fmt.Sprintf(`{"libraryId":%d,"filter":"continuing","sort":"aired"}`, tvLibrary))
	if err := tx.Commit(); err != nil {
		tb.Fatal(err)
	}
}

// medianOf runs fn five times and returns the median time.
func medianOf(t *testing.T, fn func()) time.Duration {
	t.Helper()
	var times []time.Duration
	for range 5 {
		start := time.Now()
		fn()
		times = append(times, time.Since(start))
	}
	slices.Sort(times)
	return times[len(times)/2]
}

// report logs a median against its budget and fails over it: at the budget
// with strict, at five times it otherwise.
func report(t *testing.T, strict bool, name string, median, budget time.Duration) {
	t.Helper()
	limit := budget * 5
	if strict {
		limit = budget
	}
	t.Logf("%-14s median %6.1f ms  (budget %v)", name, float64(median.Microseconds())/1000, budget)
	if median > limit {
		t.Errorf("%s: median %v over %v", name, median, limit)
	}
}

func getOK(t *testing.T, c *http.Client, url string) {
	res, err := c.Get(url)
	if err != nil {
		t.Error(err)
		return
	}
	_, _ = io.Copy(io.Discard, res.Body)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Errorf("%s -> %d", url, res.StatusCode)
	}
}
