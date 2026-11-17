package related

import (
	"context"
	"database/sql"
	"fmt"
	"io"
	"log/slog"
	"slices"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

var now = time.Date(2026, 11, 17, 12, 0, 0, 0, time.UTC)

type env struct {
	t    *testing.T
	conn *sql.DB
	q    *db.Queries
}

func newEnv(t *testing.T) env {
	t.Helper()
	conn, q := dbtest.Open(t)
	e := env{t, conn, q}
	e.exec(`INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/m'), (2, 'movie', '4K', '/m4'), (3, 'tv', 'TV', '/t')`)
	return e
}

func (e env) exec(query string, args ...any) int64 {
	e.t.Helper()
	res, err := e.conn.ExecContext(e.t.Context(), query, args...)
	if err != nil {
		e.t.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}

// movie adds a movie with tmdbID to lib, released in year.
func (e env) movie(lib, tmdbID int64, title string, year int) int64 {
	e.t.Helper()
	return e.exec(`INSERT INTO movies (library_id, tmdb_id, title, year, release_date, genres) VALUES (?, ?, ?, ?, ?, '[]')`,
		lib, tmdbID, title, year, fmt.Sprintf("%d-06-01", year))
}

func (e env) show(lib, tmdbID int64, title string, year int) int64 {
	e.t.Helper()
	return e.exec(`INSERT INTO shows (library_id, tmdb_id, title, year, first_air_date, genres) VALUES (?, ?, ?, ?, ?, '[]')`,
		lib, tmdbID, title, year, fmt.Sprintf("%d-01-01", year))
}

func (e env) store(id int64, mv *tmdb.Movie) {
	e.t.Helper()
	if err := e.q.Transaction(e.t.Context(), func(q *db.Queries) error { return StoreMovie(e.t.Context(), q, id, mv, now) }); err != nil {
		e.t.Fatal(err)
	}
}

func (e env) storeShow(id int64, tv *tmdb.TVShow) {
	e.t.Helper()
	if err := e.q.Transaction(e.t.Context(), func(q *db.Queries) error { return StoreShow(e.t.Context(), q, id, tv, now) }); err != nil {
		e.t.Fatal(err)
	}
}

// details are a movie's TMDB details with keywords, genres and
// recommendations.
func details(keywords []int, genres []string, recs ...int) *tmdb.Movie {
	mv := &tmdb.Movie{OriginalLanguage: "en"}
	for _, k := range keywords {
		mv.Keywords.Keywords = append(mv.Keywords.Keywords, tmdb.Keyword{ID: k})
	}
	for _, g := range genres {
		mv.Genres = append(mv.Genres, tmdb.Genre{Name: g})
	}
	for _, r := range recs {
		mv.Recommendations.Results = append(mv.Recommendations.Results, struct {
			ID int `json:"id"`
		}{r})
	}
	return mv
}

func TestMovieTags(t *testing.T) {
	mv := details([]int{9}, []string{"Drama"}, 50, 51)
	mv.OriginalLanguage = "KO"
	for i := range 12 {
		mv.Credits.Cast = append(mv.Credits.Cast, tmdb.CastMember{ID: 100 + i})
	}
	mv.Credits.Crew = []tmdb.CrewMember{{ID: 7, Job: "Director"}, {ID: 8, Job: "Writer"}}
	got := MovieTags(mv)
	count := map[string]int{}
	for _, tg := range got {
		count[tg.Kind]++
	}
	want := map[string]int{KindRec: 2, KindKeyword: 1, KindGenre: 1, KindPerson: castKept, KindDirector: 1, KindLanguage: 1}
	for k, n := range want {
		if count[k] != n {
			t.Errorf("%s tags = %d, want %d (%v)", k, count[k], n, got)
		}
	}
	if !slices.Contains(got, Tag{Kind: KindRec, Value: "51", Rank: 1}) || !slices.Contains(got, Tag{Kind: KindLanguage, Value: "ko"}) {
		t.Errorf("tags = %v", got)
	}
	if tags := MovieTags(&tmdb.Movie{OriginalLanguage: "xx"}); len(tags) != 0 {
		t.Errorf("no language (xx) gave %v", tags)
	}
	tv := &tmdb.TVShow{CreatedBy: []tmdb.Person{{ID: 3}}}
	if tags := ShowTags(tv); !slices.Equal(tags, []Tag{{Kind: KindCreator, Value: "3"}}) {
		t.Errorf("ShowTags = %v", tags)
	}
}

func TestMovieRows(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	// A trilogy: parts 1 and 3 in the library, part 3 in two libraries.
	part2 := e.movie(1, 2, "Part Two", 2012)
	part1 := e.movie(1, 1, "Part One", 2010)
	part3 := e.movie(1, 3, "Part Three", 2014)
	part3in4K := e.movie(2, 3, "Part Three", 2014)
	part2in4K := e.movie(2, 2, "Part Two", 2012)
	// Recommended by TMDB, in this order, and one the library lacks.
	recA := e.movie(1, 20, "Rec A", 2011)
	recB := e.movie(2, 21, "Rec B", 2013)
	// Names Part Two in its own recommendations.
	fan := e.movie(1, 30, "Fan", 2001)
	// Shares a rare keyword.
	kin := e.movie(1, 40, "Kin", 2015)
	// Shares only Drama, which most titles have.
	drama := e.movie(1, 50, "Drama Only", 2012)
	// Not yet fetched: stays out.
	e.movie(1, 60, "Unfetched", 2012)

	trilogy := &tmdb.CollectionRef{ID: 900, Name: "The Trilogy Collection"}
	mv := details([]int{1, 2}, []string{"Drama"}, 999, 21, 20)
	mv.Collection = trilogy
	mv.Credits.Crew = []tmdb.CrewMember{{ID: 7, Job: "Director"}}
	e.store(part2, mv)
	e.store(part2in4K, mv)
	for _, id := range []int64{part1, part3, part3in4K} {
		m := details(nil, []string{"Drama"})
		m.Collection = trilogy
		e.store(id, m)
	}
	e.store(recA, details(nil, []string{"Drama"}))
	e.store(recB, details(nil, nil))
	e.store(fan, details(nil, nil, 2))
	kinDetails := details([]int{1}, []string{"Drama"})
	kinDetails.Credits.Crew = []tmdb.CrewMember{{ID: 7, Job: "Director"}}
	e.store(kin, kinDetails)
	e.store(drama, details(nil, []string{"Drama"}))

	res, err := Movie(ctx, e.q, part2, now)
	if err != nil {
		t.Fatal(err)
	}
	if res.CollectionName != "The Trilogy Collection" || !slices.Equal(res.Collection, []int64{part1, part3}) {
		t.Errorf("collection = %q %v, want parts one and three (library 1's copy)", res.CollectionName, res.Collection)
	}
	// TMDB ranks Rec B above Rec A, but a tenth of a point apart; Rec A's
	// Drama, which Rec B lacks, puts it first. Fan (5: it recommends Part
	// Two) beats Kin (4.8: the director, a keyword, Drama, the era); Drama
	// Only has no strong signal.
	if want := []int64{recA, recB, fan, kin}; !slices.Equal(res.Similar, want) {
		t.Errorf("similar = %v, want %v", res.Similar, want)
	}
	if res.Due {
		t.Error("just fetched, but due")
	}

	// From the 4K library, the 4K copies come first.
	res, err = Movie(ctx, e.q, part2in4K, now)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(res.Collection, []int64{part1, part3in4K}) {
		t.Errorf("4K collection = %v, want [%d %d]", res.Collection, part1, part3in4K)
	}

	// A refetch that drops the collection and the recommendations.
	refetched := details([]int{1}, nil)
	refetched.Credits.Crew = []tmdb.CrewMember{{ID: 7, Job: "Director"}}
	e.store(part2, refetched)
	res, err = Movie(ctx, e.q, part2, now)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Collection) != 0 || !slices.Equal(res.Similar, []int64{fan, kin}) {
		t.Errorf("after refetch: collection %v, similar %v", res.Collection, res.Similar)
	}

	// TMDB no longer has it: no tags, still marked fetched.
	e.store(kin, nil)
	var n int
	if err := e.conn.QueryRowContext(ctx, `SELECT COUNT(*) FROM movie_tags WHERE movie_id = ?`, kin).Scan(&n); err != nil || n != 0 {
		t.Errorf("tags after a 404 = %d, %v", n, err)
	}
	if _, err := Movie(ctx, e.q, 9999, now); err == nil {
		t.Error("a missing movie gave no error")
	}
}

func TestShowRows(t *testing.T) {
	e := newEnv(t)
	a := e.show(3, 1, "Harbour", 2015)
	b := e.show(3, 2, "Saltmarsh", 2016)
	c := e.show(3, 3, "Unrelated", 2016)
	tv := &tmdb.TVShow{CreatedBy: []tmdb.Person{{ID: 77}}}
	e.storeShow(a, tv)
	e.storeShow(b, tv)
	e.storeShow(c, &tmdb.TVShow{})
	res, err := Show(t.Context(), e.q, a, now)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(res.Similar, []int64{b}) || res.Collection != nil {
		t.Errorf("show result = %+v", res)
	}
}

func TestDue(t *testing.T) {
	at := func(d time.Duration) *string { return new(db.FormatTime(now.Add(-d))) }
	cases := []struct {
		checked  *string
		released string
		want     bool
	}{
		{nil, "", true},
		{at(29 * 24 * time.Hour), "2026-06-01", false},
		{at(31 * 24 * time.Hour), "2026-06-01", true},
		{at(31 * 24 * time.Hour), "2019-06-01", false},
		{at(91 * 24 * time.Hour), "2019-06-01", true},
		{at(91 * 24 * time.Hour), "", true},
	}
	for _, c := range cases {
		if got := Due(c.checked, c.released, now); got != c.want {
			t.Errorf("Due(%v, %q) = %v, want %v", deref(c.checked), c.released, got, c.want)
		}
	}
}

func TestRefresher(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	fake := tmdbtest.New(t)
	fresh := e.movie(1, 11, "Never fetched", 2010)
	gone := e.movie(1, 12, "Gone from TMDB", 2010)
	show := e.show(3, 13, "A show", 2010)
	old := e.movie(1, 14, "Old data", 2010)
	e.exec(`UPDATE movies SET related_checked_at = ? WHERE id = ?`, db.FormatTime(now.Add(-200*24*time.Hour)), old)
	fake.JSON("/movie/11", map[string]any{"id": 11, "belongs_to_collection": map[string]any{"id": 5, "name": "Five"},
		"keywords": map[string]any{"keywords": []any{map[string]any{"id": 1}}}, "recommendations": map[string]any{"results": []any{map[string]any{"id": 14}}}})
	fake.JSON("/tv/13", map[string]any{"id": 13, "keywords": map[string]any{"results": []any{map[string]any{"id": 2}}}})
	fake.JSON("/movie/14", map[string]any{"id": 14, "keywords": map[string]any{"keywords": []any{map[string]any{"id": 1}}}})

	r := New(e.q, fake.Client(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	r.Now = func() time.Time { return now }
	r.Batch = 2
	more, err := r.RunOnce(ctx)
	if err != nil || !more {
		t.Fatalf("first run: more %v, err %v", more, err)
	}
	more, err = r.RunOnce(ctx)
	if err != nil || more {
		t.Fatalf("second run: more %v, err %v", more, err)
	}
	for _, id := range []int64{fresh, gone} {
		info, err := e.q.GetMovieRelatedInfo(ctx, id)
		if err != nil || info.RelatedCheckedAt == nil {
			t.Errorf("movie %d not marked fetched: %+v %v", id, info, err)
		}
	}
	info, _ := e.q.GetMovieRelatedInfo(ctx, fresh)
	if deref(info.CollectionName) != "Five" {
		t.Errorf("collection = %v", info.CollectionName)
	}
	if s, _ := e.q.GetShowRelatedInfo(ctx, show); s.RelatedCheckedAt == nil {
		t.Error("show not fetched")
	}
	// The old movie isn't fetched until its page asks for it.
	if fake.Count("/movie/14") != 0 {
		t.Error("old data fetched without being asked")
	}
	r.Want("movie", old)
	r.Want("movie", old)
	if _, err := r.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if fake.Count("/movie/14") != 1 {
		t.Errorf("old movie fetched %d times, want 1", fake.Count("/movie/14"))
	}
	// Fresh now: asking again fetches nothing.
	r.Want("movie", old)
	if _, err := r.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if fake.Count("/movie/14") != 1 {
		t.Errorf("fresh movie fetched again: %d", fake.Count("/movie/14"))
	}
	res, err := Movie(ctx, e.q, fresh, now)
	if err != nil || !slices.Equal(res.Similar, []int64{old}) {
		t.Errorf("after the job: %+v, %v", res, err)
	}

	// Without a key it asks TMDB nothing.
	before := fake.Total()
	none := New(e.q, tmdb.NewWith("", fake.URL, tmdb.Options{}), slog.New(slog.NewTextHandler(io.Discard, nil)))
	none.Want("movie", fresh)
	if more, err := none.RunOnce(context.Background()); more || err != nil || fake.Total() != before {
		t.Errorf("unconfigured: more %v err %v requests %d", more, err, fake.Total()-before)
	}
}
