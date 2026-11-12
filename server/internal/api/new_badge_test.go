package api

import (
	"fmt"
	"net/http"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

// The New badge means "a file arrived in the last 7 days and this person
// has not watched it" (PLAN-HOME.md section 4): watching clears it on the
// Recently added card, the Recently aired card, the library grid, the
// season's episodes and the movie, while an older unwatched episode keeps
// the show out of New once its recent episodes are watched.
func TestNewBadgeFollowsWatching(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	now := time.Now().UTC()

	tv, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	if err != nil {
		t.Fatal(err)
	}
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 900, Title: "Harbor Lights", Genres: "[]", Credits: "[]"})
	season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 3})
	h := int64(1080)
	episodes := map[int]db.Episode{}
	for _, e := range []struct {
		number int
		added  time.Duration
	}{{1, 10 * 24 * time.Hour}, {2, 60 * time.Hour}, {3, 24 * time.Hour}} {
		aired := now.Add(-e.added).AddDate(0, 0, -1).Format(time.DateOnly)
		row, err := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: int64(e.number), Title: fmt.Sprintf("Episode %d", e.number), AirDate: &aired})
		if err != nil {
			t.Fatal(err)
		}
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: fmt.Sprintf("/t/Harbor Lights/S01E%02d.mkv", e.number), Size: 100,
			Mtime: db.FormatTime(now), AddedAt: db.FormatTime(now.Add(-e.added)), Height: &h, Parsed: "{}", LastSeenAt: db.FormatTime(now), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: row.ID, MediaFileID: f.ID})
		_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", ID: f.ID})
		episodes[e.number] = row
	}
	moviePath := "/m/Signal to Noise (2026).mkv"
	movie, _ := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 901, Title: "Signal to Noise", Genres: "[]", Credits: "[]"})
	mf, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, moviePath), Path: moviePath, Size: 100, Mtime: db.FormatTime(now),
		AddedAt: db.FormatTime(now.Add(-24 * time.Hour)), Height: &h, Parsed: "{}", LastSeenAt: db.FormatTime(now), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: mf.ID})

	get := func(path string, out any) {
		t.Helper()
		doJSON(t, srv.client, http.MethodGet, srv.URL+path, "", out)
	}
	showCard := func(home HomeData) BrowseItem {
		t.Helper()
		for _, it := range home.RecentlyAddedShows {
			if it.ID == show.ID {
				return it
			}
		}
		t.Fatalf("no card for the show in %+v", home.RecentlyAddedShows)
		return BrowseItem{}
	}
	movieCard := func(home HomeData) BrowseItem {
		t.Helper()
		for _, it := range home.RecentlyAddedMovies {
			if it.Kind == "movie" && it.ID == movie.ID {
				return it
			}
		}
		t.Fatalf("no card for the movie in %+v", home.RecentlyAddedMovies)
		return BrowseItem{}
	}
	airedNew := func(home HomeData) map[int64]bool {
		out := map[int64]bool{}
		for _, c := range home.RecentlyAired {
			out[c.EpisodeNumber] = c.IsNew
		}
		return out
	}
	itemNew := func(libraryID, id int64) (isNew, watched bool) {
		t.Helper()
		var items ItemList
		get(fmt.Sprintf("/api/libraries/%d/items", libraryID), &items)
		for _, it := range items.Items {
			if it.ID == id && it.Kind != "unmatched" {
				return it.IsNew, it.Watched
			}
		}
		t.Fatalf("item %d not in library %d: %+v", id, libraryID, items.Items)
		return false, false
	}
	seasonNew := func() map[int64]bool {
		var se SeasonDetail
		get(fmt.Sprintf("/api/shows/%d/seasons/1", show.ID), &se)
		out := map[int64]bool{}
		for _, e := range se.Episodes {
			out[e.Number] = e.IsNew
		}
		return out
	}

	// Before anything is watched.
	var home HomeData
	get("/api/home", &home)
	if card := showCard(home); !card.IsNew || card.Subtitle != "S01E03" {
		t.Errorf("recently added show = %+v, want New and S01E03", card)
	}
	if aired := airedNew(home); !aired[3] || !aired[2] || aired[1] {
		t.Errorf("recently aired new = %v, want E03 and E02 New, E01 not", aired)
	}
	if card := movieCard(home); !card.IsNew {
		t.Errorf("recently added movie = %+v, want New", card)
	}
	if isNew, _ := itemNew(tv.ID, show.ID); !isNew {
		t.Error("the show's grid tile is not New before watching")
	}
	if isNew, _ := itemNew(1, movie.ID); !isNew {
		t.Error("the movie's grid tile is not New before watching")
	}
	if eps := seasonNew(); !eps[3] || !eps[2] || eps[1] {
		t.Errorf("season new = %v, want E03 and E02 New, E01 (10 days) not", eps)
	}

	// Watch the newest episode and the film: both lose New at once, while the
	// show keeps it for E02, still unwatched and within the week.
	for _, p := range []string{fmt.Sprintf("/api/watch/episode/%d/watched", episodes[3].ID), fmt.Sprintf("/api/watch/movie/%d/watched", movie.ID)} {
		if code := doJSON(t, srv.client, http.MethodPost, srv.URL+p, "", nil).StatusCode; code/100 != 2 {
			t.Fatalf("POST %s -> %d", p, code)
		}
	}
	get("/api/home", &home)
	if card := showCard(home); card.IsNew {
		t.Errorf("the card for a watched episode is still New: %+v", card)
	}
	if aired := airedNew(home); aired[3] || !aired[2] {
		t.Errorf("recently aired new after watching E03 = %v", aired)
	}
	if card := movieCard(home); card.IsNew || !card.Watched {
		t.Errorf("recently added movie after watching = %+v", card)
	}
	if isNew, watched := itemNew(1, movie.ID); isNew || !watched {
		t.Errorf("movie tile after watching: new=%v watched=%v", isNew, watched)
	}
	if isNew, _ := itemNew(tv.ID, show.ID); !isNew {
		t.Error("the show lost New while E02, added this week, is unwatched")
	}
	if eps := seasonNew(); eps[3] || !eps[2] {
		t.Errorf("season new after watching E03 = %v", eps)
	}

	// Watch E02 too: the show is not New, though E01 from ten days ago is
	// still unwatched. That is the badge watched shows used to keep.
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+fmt.Sprintf("/api/watch/episode/%d/watched", episodes[2].ID), "", nil).StatusCode; code/100 != 2 {
		t.Fatalf("mark E02 -> %d", code)
	}
	if isNew, watched := itemNew(tv.ID, show.ID); isNew || watched {
		t.Errorf("show tile after watching this week's episodes: new=%v watched=%v, want neither", isNew, watched)
	}
	get("/api/home", &home)
	if aired := airedNew(home); aired[2] || aired[3] {
		t.Errorf("recently aired new after watching both = %v", aired)
	}
}
