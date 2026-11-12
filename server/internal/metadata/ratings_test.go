package metadata

import (
	"fmt"
	"net/http"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

func TestContentRatings(t *testing.T) {
	e := setup(t)
	e.file(e.mov, "/m/The Matrix (1999).mkv", `{"kind":"movie","title":"The Matrix","year":1999}`)
	e.file(e.mov, "/m/Heat.mkv", `{"kind":"movie","title":"Heat"}`)
	e.file(e.tv, "/t/Breaking Bad/Season 01/Breaking Bad - S01E01.mkv", `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[1]}`)
	if _, err := e.m.MatchLibrary(e.ctx, e.mov, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	rating := func(p *string) string {
		if p == nil {
			return "<null>"
		}
		return *p
	}
	movies, _ := e.q.ListMovies(e.ctx, db.ListMoviesParams{LibraryID: e.mov.ID, Limit: 10})
	if len(movies) != 2 || movies[0].Movie.Title != "Heat" || rating(movies[0].Movie.ContentRating) != "" || rating(movies[1].Movie.ContentRating) != "R" {
		t.Fatalf("ratings after matching: Heat %q, Matrix %q", rating(movies[0].Movie.ContentRating), rating(movies[1].Movie.ContentRating))
	}
	shows, _ := e.q.ListShows(e.ctx, db.ListShowsParams{LibraryID: e.tv.ID, Limit: 10})
	if len(shows) != 1 || rating(shows[0].Show.ContentRating) != "TV-MA" {
		t.Fatalf("show rating = %q", rating(shows[0].Show.ContentRating))
	}

	// Titles matched before ratings were kept have none; the next match
	// run looks them up, even with no files to match.
	if err := e.q.SetMovieContentRating(e.ctx, db.SetMovieContentRatingParams{ID: movies[1].Movie.ID}); err != nil {
		t.Fatal(err)
	}
	if err := e.q.SetShowContentRating(e.ctx, db.SetShowContentRatingParams{ID: shows[0].Show.ID}); err != nil {
		t.Fatal(err)
	}
	ch, cancel := e.bus.Subscribe()
	defer cancel()
	if _, err := e.m.MatchLibrary(e.ctx, e.mov, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	m, _ := e.q.GetMovie(e.ctx, movies[1].Movie.ID)
	s, _ := e.q.GetShow(e.ctx, shows[0].Show.ID)
	if rating(m.ContentRating) != "R" || rating(s.ContentRating) != "TV-MA" {
		t.Errorf("filled ratings: movie %q, show %q", rating(m.ContentRating), rating(s.ContentRating))
	}
	changed := 0
	for len(ch) > 0 {
		if ev := <-ch; ev.Type == "library.changed" {
			changed++
		}
	}
	if changed != 2 {
		t.Errorf("library.changed events = %d, want one per library", changed)
	}
	// Nothing left to look up: Heat's empty rating is not asked again.
	if n := e.m.fillContentRatings(e.ctx, e.mov); n != 0 {
		t.Errorf("third run filled %d", n)
	}
}

// With TMDB down, a pass stops at the first title that fails rather than
// waiting out every title's timeout and retries in turn.
func TestContentRatingsStopWhenTMDBIsDown(t *testing.T) {
	f := tmdbtest.New(t)
	f.Fallback(func(*http.Request) any { return tmdbtest.Reply{Status: http.StatusServiceUnavailable} })
	e := setupWith(t, f.Client())
	for i := range 20 {
		if _, err := e.q.UpsertMovie(e.ctx, db.UpsertMovieParams{LibraryID: e.mov.ID, TmdbID: int64(700100 + i),
			Title: fmt.Sprintf("Film %d", i), Genres: "[]", Credits: "[]"}); err != nil {
			t.Fatal(err)
		}
	}
	if n := e.m.fillContentRatings(e.ctx, e.mov); n != 0 {
		t.Errorf("filled %d", n)
	}
	// At most the lookups already under way finish their tries (a first
	// try and two retries each).
	if n := f.Total(); n > ratingLookups*3 {
		t.Errorf("asked TMDB %d times for 20 titles", n)
	}
}
