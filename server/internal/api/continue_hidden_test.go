package api

import (
	"fmt"
	"net/http"
	"slices"
	"testing"

	"watchmymedia/server/internal/db"
)

// Taking a title off Continue watching keeps its place and leaves it off
// the list, Home's fixed row and its arranged one, until Undo or a play
// brings it back. The cards carry the title's rating and summary.
func TestHideFromContinueWatching(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	fi := newFileItems(t, srv)
	ctx := t.Context()
	year := int64(1995)
	if _, err := srv.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: fi.movie.LibraryID, TmdbID: 949, Title: "Heat", Year: &year, Genres: "[]",
		ContentRating: new("R"), Overview: "A crew and a detective."}); err != nil {
		t.Fatal(err)
	}
	if err := srv.conn.QueryRowContext(ctx, `UPDATE shows SET content_rating = 'TV-14' WHERE id = ? RETURNING id`, fi.showID).Scan(new(int64)); err != nil {
		t.Fatal(err)
	}
	if err := srv.conn.QueryRowContext(ctx, `UPDATE episodes SET overview = 'The lights go out.' WHERE id = ? RETURNING id`, fi.ep1ID).Scan(new(int64)); err != nil {
		t.Fatal(err)
	}
	for _, f := range []db.MediaFile{fi.movie, fi.episode} {
		body := `{"positionMs":600000,"durationMs":2400000}`
		if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/api/files/%d/progress", srv.URL, f.ID), body, nil).StatusCode; code != http.StatusOK {
			t.Fatalf("progress on %d -> %d", f.ID, code)
		}
	}
	continuing := func() map[string]ContinueItem {
		t.Helper()
		var list ContinueList
		if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/watch/continue", "", &list).StatusCode; code != http.StatusOK {
			t.Fatalf("continue -> %d", code)
		}
		out := map[string]ContinueItem{}
		for _, it := range list.Items {
			// The episode file holds E01 and E02; E01 is the card checked.
			if it.Kind == "movie" || *it.EpisodeID == fi.ep1ID {
				out[it.Kind] = it
			}
		}
		return out
	}
	homeKinds := func(arranged bool) []string {
		t.Helper()
		var home HomeData
		url := srv.URL + "/api/home"
		if arranged {
			url += "?arranged=true"
		}
		if code := doJSON(t, srv.client, http.MethodGet, url, "", &home).StatusCode; code != http.StatusOK {
			t.Fatalf("home -> %d", code)
		}
		items := home.ContinueWatching
		if arranged {
			items = nil
			for _, r := range home.Rows {
				if r.ID == "continue" {
					items = r.Continue
				}
			}
		}
		var kinds []string
		for _, it := range items {
			kinds = append(kinds, it.Kind)
		}
		return kinds
	}

	got := continuing()
	if m := got["movie"]; m.ContentRating != "R" || m.Overview != "A crew and a detective." {
		t.Errorf("movie card %+v", m)
	}
	if e := got["episode"]; e.ContentRating != "TV-14" || e.Overview != "The lights go out." {
		t.Errorf("episode card %+v", e)
	}

	hidden := fmt.Sprintf("%s/api/watch/movie/%d/hidden", srv.URL, fi.movieID)
	ch, cancel := srv.bus.Subscribe()
	defer cancel()
	if code := doJSON(t, srv.client, http.MethodPut, hidden, "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("hide -> %d", code)
	}
	if e := <-ch; e.Type != "watch.changed" {
		t.Errorf("event %q", e.Type)
	}
	if _, ok := continuing()["movie"]; ok {
		t.Error("hidden movie still in Continue watching")
	}
	for _, arranged := range []bool{false, true} {
		if kinds := homeKinds(arranged); len(kinds) != 2 || slices.Contains(kinds, "movie") {
			t.Errorf("home (arranged %v) continue = %v", arranged, kinds)
		}
	}
	if w, err := srv.q.GetWatchState(ctx, db.GetWatchStateParams{UserID: 1, ItemType: "movie", ItemID: fi.movieID}); err != nil || w.PositionMs != 600000 {
		t.Errorf("the place went: %+v %v", w, err)
	}

	// Undo.
	if code := doJSON(t, srv.client, http.MethodDelete, hidden, "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("show -> %d", code)
	}
	if _, ok := continuing()["movie"]; !ok {
		t.Error("Undo didn't bring the movie back")
	}

	// A play brings it back too.
	if code := doJSON(t, srv.client, http.MethodPut, hidden, "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("hide -> %d", code)
	}
	body := `{"positionMs":610000,"durationMs":2400000}`
	if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/api/files/%d/progress", srv.URL, fi.movie.ID), body, nil).StatusCode; code != http.StatusOK {
		t.Fatalf("progress -> %d", code)
	}
	if _, ok := continuing()["movie"]; !ok {
		t.Error("a play didn't bring the movie back")
	}

	// Nothing to hide: no state, no error.
	if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/api/watch/episode/%d/hidden", srv.URL, fi.ep2ID+1000), "", nil).StatusCode; code != http.StatusNoContent {
		t.Errorf("hide of nothing -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/api/watch/show/%d/hidden", srv.URL, fi.showID), "", nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("hide of a show -> %d", code)
	}
}
