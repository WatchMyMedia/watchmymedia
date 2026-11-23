package api

import (
	"net/http"
	"strconv"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/people"
	"watchmymedia/server/internal/tmdb"
)

// castOf stores a title's cast as the matcher does: (person ID, name,
// part) in billing order.
func castOf(members ...[3]any) tmdb.Credits {
	var c tmdb.Credits
	for _, m := range members {
		c.Cast = append(c.Cast, tmdb.CastMember{ID: m[0].(int), Name: m[1].(string), Character: m[2].(string), ProfilePath: "/" + m[1].(string) + ".jpg"})
	}
	return c
}

func TestGetPerson(t *testing.T) {
	t.Parallel()
	e := newRelatedEnv(t)
	ctx := t.Context()
	store := func(id int64, c tmdb.Credits) {
		t.Helper()
		if err := e.q.Transaction(ctx, func(q *db.Queries) error {
			return people.StoreMovie(ctx, q, id, &tmdb.Movie{Credits: c}, time.Now())
		}); err != nil {
			t.Fatal(err)
		}
	}
	// Ada is in three films, one of them in a second library too, and plays
	// two parts in one; a namesake is someone else.
	first := e.movie(8001, "First Light", 2001, true)
	second := e.movie(8002, "Second Tide", 2010, true)
	twice := e.movie(8003, "Twin Parts", 2005, true)
	noFile := e.movie(8004, "Never Arrived", 2012, false)
	fourK := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('movie', '4K films', '/films-4k')`)
	copy4K := e.exec(`INSERT INTO movies (library_id, tmdb_id, title, year, release_date, genres) VALUES (?, 8002, 'Second Tide', 2010, '2010-03-01', '[]')`, fourK)
	e.exec(`INSERT INTO media_files (library_id, movie_id, path, size, mtime, status, parsed, height, added_at, match_status)
		VALUES (?, ?, '/films-4k/8002.mkv', 900, '2026-01-01T00:00:00.000Z', 'present', '{"kind":"movie"}', 2160, '2026-01-01T00:00:00.000Z', 'matched')`, fourK, copy4K)
	store(first, castOf([3]any{501, "Ada Vale", "Io"}, [3]any{502, "Ben Lark", "Pilot"}))
	store(second, castOf([3]any{502, "Ben Lark", "Captain"}, [3]any{501, "Ada Vale", "Maren"}))
	store(copy4K, castOf([3]any{502, "Ben Lark", "Captain"}, [3]any{501, "Ada Vale", "Maren"}))
	store(twice, castOf([3]any{501, "Ada Vale", "Rhea"}, [3]any{503, "Ada Vale", "Someone else"}, [3]any{501, "Ada Vale", "Thea"}))
	store(noFile, castOf([3]any{501, "Ada Vale", "Ghost"}))
	tvLib := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'People TV', '/people-tv')`)
	show, _ := e.show(tvLib, 8101, "Harbour Watch", 2)
	if err := e.q.Transaction(ctx, func(q *db.Queries) error {
		return people.StoreShow(ctx, q, show, &tmdb.TVShow{Credits: castOf([3]any{501, "Ada Vale", "Maren Holt"})}, time.Now())
	}); err != nil {
		t.Fatal(err)
	}

	var p PersonDetail
	if r := doJSON(t, e.client, http.MethodGet, e.URL+"/api/people/501", "", &p); r.StatusCode != http.StatusOK {
		t.Fatalf("status %d: %s", r.StatusCode, r.Body)
	}
	if p.PersonID != 501 || p.Name != "Ada Vale" || p.ProfilePath != "/Ada Vale.jpg" {
		t.Errorf("person = %+v", p)
	}
	var got []string
	for _, m := range p.Movies {
		got = append(got, m.Title+" as "+m.Character)
	}
	want := []string{"Second Tide as Maren", "Twin Parts as Rhea / Thea", "First Light as Io"}
	if len(got) != len(want) {
		t.Fatalf("movies = %q, want %q", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("movie %d = %q, want %q", i, got[i], want[i])
		}
	}
	if p.Movies[0].ID != second {
		t.Errorf("the copy shown is %d, want the first library's %d", p.Movies[0].ID, second)
	}
	if len(p.Shows) != 1 || p.Shows[0].ID != show || p.Shows[0].Character != "Maren Holt" || p.Shows[0].Kind != "show" {
		t.Errorf("shows = %+v", p.Shows)
	}

	// The namesake is told apart by ID.
	var other PersonDetail
	if r := doJSON(t, e.client, http.MethodGet, e.URL+"/api/people/503", "", &other); r.StatusCode != http.StatusOK || len(other.Movies) != 1 || other.Movies[0].Character != "Someone else" {
		t.Errorf("namesake: %d %+v", r.StatusCode, other)
	}
	// A member sees what an admin sees.
	var asMember PersonDetail
	if r := e.asMember(http.MethodGet, "/api/people/501", "", &asMember); r.StatusCode != http.StatusOK || len(asMember.Movies) != 3 || len(asMember.Shows) != 1 {
		t.Errorf("member: %d %+v", r.StatusCode, asMember)
	}
	// Someone in nothing the library has, or only in a title with no file.
	store(noFile, castOf([3]any{504, "Cy Moor", "Only here"}))
	for _, id := range []string{"504", "999999"} {
		if r := doJSON(t, e.client, http.MethodGet, e.URL+"/api/people/"+id, "", nil); r.StatusCode != http.StatusNotFound {
			t.Errorf("person %s: status %d, want 404", id, r.StatusCode)
		}
	}
	// The movie's reply carries the person IDs.
	var mv MovieDetail
	if r := doJSON(t, e.client, http.MethodGet, e.URL+"/api/movies/"+strconv.FormatInt(first, 10), "", &mv); r.StatusCode != http.StatusOK ||
		len(mv.Credits) != 2 || mv.Credits[0].PersonID != 501 || mv.Credits[1].Character != "Pilot" {
		t.Errorf("movie credits: %d %+v", r.StatusCode, mv.Credits)
	}
}
