package api

import (
	"fmt"
	"net/http"
	"slices"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/related"
	"watchmymedia/server/internal/tmdb"
)

// relatedEnv is followEnv with a movie library whose movies have tags.
type relatedEnv struct {
	followEnv
	lib int64
}

func newRelatedEnv(t *testing.T) relatedEnv {
	t.Helper()
	e := newFollowEnv(t)
	lib := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('movie', 'Related films', '/related-films')`)
	return relatedEnv{e, lib}
}

// movie adds a movie, with a present file unless file is false.
func (e relatedEnv) movie(tmdbID int64, title string, year int, file bool) int64 {
	e.t.Helper()
	id := e.exec(`INSERT INTO movies (library_id, tmdb_id, title, year, release_date, genres, poster_path) VALUES (?, ?, ?, ?, ?, '[]', '/p.jpg')`,
		e.lib, tmdbID, title, year, fmt.Sprintf("%d-03-01", year))
	if file {
		e.exec(`INSERT INTO media_files (library_id, movie_id, path, size, mtime, status, parsed, height, added_at, match_status)
			VALUES (?, ?, ?, 500, '2026-01-01T00:00:00.000Z', 'present', '{"kind":"movie"}', 1080, '2026-01-01T00:00:00.000Z', 'matched')`,
			e.lib, id, fmt.Sprintf("/related-films/%d.mkv", tmdbID))
	}
	return id
}

// tags stores a movie's related data: its collection, its recommendations
// (TMDB IDs) and a director.
func (e relatedEnv) tags(id int64, collection *tmdb.CollectionRef, director int, recs ...int) {
	e.t.Helper()
	mv := &tmdb.Movie{Collection: collection}
	if director > 0 {
		mv.Credits.Crew = []tmdb.CrewMember{{ID: director, Job: "Director"}}
	}
	for _, r := range recs {
		mv.Recommendations.Results = append(mv.Recommendations.Results, struct {
			ID int `json:"id"`
		}{r})
	}
	if err := e.q.Transaction(e.t.Context(), func(q *db.Queries) error {
		return related.StoreMovie(e.t.Context(), q, id, mv, time.Now())
	}); err != nil {
		e.t.Fatal(err)
	}
}

func itemIDs(items []BrowseItem) []int64 {
	out := make([]int64, len(items))
	for i, it := range items {
		out[i] = it.ID
	}
	return out
}

func TestRelatedRows(t *testing.T) {
	e := newRelatedEnv(t)
	saga := &tmdb.CollectionRef{ID: 77, Name: "The Saga Collection"}
	one := e.movie(9001, "Saga One", 2001, true)
	two := e.movie(9002, "Saga Two", 2004, true)
	three := e.movie(9003, "Saga Three", 2007, true)
	recs := []int64{e.movie(9101, "Rec One", 2003, true), e.movie(9102, "Rec Two", 2005, true), e.movie(9103, "Rec Three", 2009, true)}
	noFile := e.movie(9104, "No file", 2004, false)
	e.tags(one, saga, 0)
	e.tags(three, saga, 0)
	e.tags(two, saga, 5, 9104, 9101, 9102, 9103)
	for _, id := range append(slices.Clone(recs), noFile) {
		e.tags(id, nil, 0)
	}
	// The admin has watched Rec Two: it keeps its place, marked.
	e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed) SELECT id, 'movie', ?, 1 FROM users WHERE is_admin = 1`, recs[1])

	var got RelatedRows
	if r := e.admin(http.MethodGet, fmt.Sprintf("/api/movies/%d/related", two), "", &got); r.StatusCode != http.StatusOK {
		t.Fatalf("status %d: %s", r.StatusCode, r.Body)
	}
	if ids := rowIDs(got.Rows); !slices.Equal(ids, []string{"collection", "similar"}) {
		t.Fatalf("rows = %v", ids)
	}
	c, s := got.Rows[0], got.Rows[1]
	if c.Title != "The Saga Collection" || c.Style != "poster" || !slices.Equal(itemIDs(c.Items), []int64{one, three}) {
		t.Errorf("collection row = %q %s %v", c.Title, c.Style, itemIDs(c.Items))
	}
	if s.Title != "More like this" || !slices.Equal(itemIDs(s.Items), recs) {
		t.Errorf("similar row = %q %v, want %v (no file: left out)", s.Title, itemIDs(s.Items), recs)
	}
	if !s.Items[1].Watched || s.Items[0].Watched {
		t.Errorf("watched marks = %v %v", s.Items[0].Watched, s.Items[1].Watched)
	}
	if c.SeeAll != nil || s.SeeAll != nil {
		t.Error("related rows have no See all")
	}

	// Saga One: the collection only, since nothing else qualifies.
	if r := e.admin(http.MethodGet, fmt.Sprintf("/api/movies/%d/related", one), "", &got); r.StatusCode != http.StatusOK {
		t.Fatalf("status %d", r.StatusCode)
	}
	if ids := rowIDs(got.Rows); !slices.Equal(ids, []string{"collection"}) || !slices.Equal(itemIDs(got.Rows[0].Items), []int64{two, three}) {
		t.Errorf("Saga One's rows = %v", ids)
	}

	// Fewer than three for More like this: no row. Rec One is named by
	// Saga Two's recommendations, and nothing else.
	if r := e.asMember(http.MethodGet, fmt.Sprintf("/api/movies/%d/related", recs[0]), "", &got); r.StatusCode != http.StatusOK {
		t.Fatalf("status %d", r.StatusCode)
	}
	if len(got.Rows) != 0 {
		t.Errorf("Rec One's rows = %v, want none", rowIDs(got.Rows))
	}

	// A movie never fetched has no rows; a missing one is a 404.
	fresh := e.movie(9200, "Fresh", 2020, true)
	if r := e.admin(http.MethodGet, fmt.Sprintf("/api/movies/%d/related", fresh), "", &got); r.StatusCode != http.StatusOK || len(got.Rows) != 0 {
		t.Errorf("unfetched movie: %d %v", r.StatusCode, rowIDs(got.Rows))
	}
	if r := e.admin(http.MethodGet, "/api/movies/987654/related", "", nil); r.StatusCode != http.StatusNotFound {
		t.Errorf("missing movie: %d", r.StatusCode)
	}
	if r := e.admin(http.MethodGet, "/api/shows/987654/related", "", nil); r.StatusCode != http.StatusNotFound {
		t.Errorf("missing show: %d", r.StatusCode)
	}
}

func TestShowRelatedRows(t *testing.T) {
	e := newRelatedEnv(t)
	tvLib := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'Related TV', '/related-tv')`)
	var shows []int64
	for i := range 4 {
		id, _ := e.show(tvLib, int64(8000+i), fmt.Sprintf("Show %d", i), 2)
		shows = append(shows, id)
	}
	// Show 0's recommendations name the other three.
	tv := &tmdb.TVShow{}
	for _, r := range []int{8001, 8002, 8003} {
		tv.Recommendations.Results = append(tv.Recommendations.Results, struct {
			ID int `json:"id"`
		}{r})
	}
	store := func(id int64, tv *tmdb.TVShow) {
		if err := e.q.Transaction(t.Context(), func(q *db.Queries) error { return related.StoreShow(t.Context(), q, id, tv, time.Now()) }); err != nil {
			t.Fatal(err)
		}
	}
	store(shows[0], tv)
	for _, id := range shows[1:] {
		store(id, &tmdb.TVShow{})
	}
	var got RelatedRows
	if r := e.admin(http.MethodGet, fmt.Sprintf("/api/shows/%d/related", shows[0]), "", &got); r.StatusCode != http.StatusOK {
		t.Fatalf("status %d: %s", r.StatusCode, r.Body)
	}
	if ids := rowIDs(got.Rows); !slices.Equal(ids, []string{"similar"}) || !slices.Equal(itemIDs(got.Rows[0].Items), shows[1:]) {
		t.Errorf("show rows = %v", ids)
	}
	if got.Rows[0].Items[0].Kind != "show" {
		t.Errorf("kind = %q", got.Rows[0].Items[0].Kind)
	}
}
