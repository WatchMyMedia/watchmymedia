package api

import (
	"fmt"
	"net/http"
	"slices"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

func TestSortItems(t *testing.T) {
	t.Parallel()
	day := func(d int) time.Time { return time.Date(2026, 1, d, 0, 0, 0, 0, time.UTC) }
	items := func() []BrowseItem {
		return []BrowseItem{
			{ID: 1, Title: "The Wire", AddedAt: day(3), Year: 2002, Rating: 9.3, ReleaseDate: "2002-06-02", RuntimeMin: 60, LastAirDate: "2008-03-09"},
			{ID: 2, Title: "Alien", AddedAt: day(1), Year: 1979, Rating: 8.5, ReleaseDate: "1979-05-25", RuntimeMin: 117, LastAirDate: ""},
			{ID: 3, Title: "a Bug's Life", AddedAt: day(2), Year: 1998, Rating: 7.2, ReleaseDate: "1998-11-25", RuntimeMin: 95, LastAirDate: "2010-01-01"},
			{ID: 4, Title: "Zodiac", AddedAt: day(2), Year: 2007, Rating: 7.7, ReleaseDate: "", RuntimeMin: 157, LastAirDate: "2009-01-01"},
		}
	}
	cases := []struct {
		by   string
		want []int64
	}{
		{"title", []int64{2, 3, 1, 4}}, // "a Bug's Life" under B, "The Wire" under W
		{"", []int64{2, 3, 1, 4}},
		{"added", []int64{1, 3, 4, 2}}, // ties keep their order
		{"year", []int64{4, 1, 3, 2}},
		{"rating", []int64{1, 2, 4, 3}},
		{"released", []int64{1, 3, 2, 4}},
		{"runtime", []int64{4, 2, 3, 1}},
		{"aired", []int64{3, 4, 1, 2}},
	}
	for _, c := range cases {
		got := items()
		sortItems(got, c.by)
		ids := make([]int64, len(got))
		for i, it := range got {
			ids[i] = it.ID
		}
		if !slices.Equal(ids, c.want) {
			t.Errorf("sort %q = %v, want %v", c.by, ids, c.want)
		}
	}
}

func TestSeasonAiring(t *testing.T) {
	t.Parallel()
	status := func(s string) db.Show { return db.Show{Status: &s} }
	ep := func(date string) db.Episode {
		if date == "-" {
			return db.Episode{}
		}
		return db.Episode{AirDate: &date}
	}
	today := "2026-11-01"
	cases := []struct {
		name string
		show db.Show
		last bool
		eps  []db.Episode
		want bool
	}{
		{"an episode to come", status("Returning Series"), false, []db.Episode{ep("2026-10-01"), ep("2026-12-01")}, true},
		{"all aired", status("Returning Series"), false, []db.Episode{ep("2026-10-01"), ep("2026-11-01")}, false},
		{"undated, last season", status("Returning Series"), true, []db.Episode{ep("2026-10-01"), ep("-")}, true},
		{"undated, earlier season", status("Returning Series"), false, []db.Episode{ep("-")}, false},
		{"ended", status("Ended"), true, []db.Episode{ep("2027-01-01")}, false},
		{"canceled", status("Canceled"), true, []db.Episode{ep("-")}, false},
		{"no status", db.Show{}, false, []db.Episode{ep("2027-01-01")}, true},
	}
	for _, c := range cases {
		if got := seasonAiring(c.show, c.last, c.eps, today); got != c.want {
			t.Errorf("%s: %v, want %v", c.name, got, c.want)
		}
	}
}

// fileItems is a movie file, an episode file holding two episodes, an
// unmatched file and a missing one, for the lookups of what a file is.
type fileItems struct {
	movie, episode, unmatched, missing db.MediaFile
	movieID, showID, ep1ID, ep2ID      int64
}

func newFileItems(t *testing.T, srv *testEnv) fileItems {
	t.Helper()
	ctx := t.Context()
	q := srv.q
	var fi fileItems
	var err error
	fi.movie, _ = q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	year := int64(1995)
	movie, _ := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: fi.movie.LibraryID, TmdbID: 949, Title: "Heat", Year: &year, Genres: "[]"})
	fi.movieID = movie.ID
	_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: fi.movie.ID})
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 1396, Title: "Harbor Lights", Genres: "[]"})
	fi.showID = show.ID
	s1, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 2})
	still := "/still.jpg"
	ep2, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: show.ID, Number: 2, Title: "Tide"})
	ep1, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: show.ID, Number: 1, Title: "Pilot", StillPath: &still})
	fi.ep1ID, fi.ep2ID = ep1.ID, ep2.ID
	now := db.FormatTime(time.Now())
	file := func(path string) db.MediaFile {
		f, ferr := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: path, Size: 1, Mtime: now, AddedAt: now, LastSeenAt: now, Parsed: "{}"})
		if ferr != nil {
			t.Fatal(ferr)
		}
		return f
	}
	fi.episode = file("/t/Harbor Lights/S01E01E02.mkv")
	// Linked second episode first: the file is still described by E01.
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep2.ID, MediaFileID: fi.episode.ID})
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep1.ID, MediaFileID: fi.episode.ID})
	fi.unmatched = file("/t/Extras/Behind the Scenes.mkv")
	fi.missing = file("/t/Harbor Lights/old.mkv")
	if _, err = q.MarkMediaFilesMissing(ctx, []int64{fi.missing.ID}); err != nil {
		t.Fatal(err)
	}
	return fi
}

func TestHistoryItem(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	fi := newFileItems(t, srv)
	deps := Deps{Queries: srv.q, Log: discardLog()}
	m := historyItem(t.Context(), deps, fi.movie)
	if m.Type != "movie" || m.ItemID != fi.movieID || m.MovieID == nil || m.Title != "Heat" || m.Year == nil || *m.Year != 1995 {
		t.Errorf("movie file = %+v", m)
	}
	e := historyItem(t.Context(), deps, fi.episode)
	if e.Type != "episode" || e.ItemID != fi.ep1ID || e.ShowTitle != "Harbor Lights" || e.Title != "Pilot" || *e.SeasonNumber != 1 || *e.EpisodeNumber != 1 ||
		*e.ShowID != fi.showID || e.ArtPath != "/still.jpg" {
		t.Errorf("episode file = %+v", e)
	}
	if u := historyItem(t.Context(), deps, fi.unmatched); u.Type != "file" || u.Title != "Behind the Scenes" || u.ItemID != fi.unmatched.ID {
		t.Errorf("unmatched file = %+v", u)
	}
}

func TestReplaceTarget(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	fi := newFileItems(t, srv)
	ctx := t.Context()
	if r, err := replaceTarget(ctx, srv.q, fi.movie.ID); err != nil || r.Type != "movie" || r.TMDBID != 949 || r.Title != "Heat" || r.LibraryPath != "/m" {
		t.Errorf("movie -> %+v %v", r, err)
	}
	if r, err := replaceTarget(ctx, srv.q, fi.episode.ID); err != nil || r.Type != "episode" || r.TMDBID != 1396 || r.Title != "Harbor Lights" || r.Path != fi.episode.Path {
		t.Errorf("episode -> %+v %v", r, err)
	}
	for _, c := range []struct {
		name string
		id   int64
		want int
	}{{"unmatched", fi.unmatched.ID, http.StatusUnprocessableEntity}, {"missing", fi.missing.ID, http.StatusNotFound}, {"no such file", 999, http.StatusNotFound}} {
		_, err := replaceTarget(ctx, srv.q, c.id)
		if status := statusOf(err); status != c.want {
			t.Errorf("%s -> %v, want %d", c.name, err, c.want)
		}
	}
	// Without Radarr or Sonarr, replacing is refused before any lookup.
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/api/files/%d/replace", srv.URL, fi.movie.ID), "", nil).StatusCode; code != http.StatusServiceUnavailable {
		t.Errorf("replace without integrations -> %d", code)
	}
}

// Marking a season, and Continue watching's episode cards.
func TestSeasonMarksAndContinueEpisodes(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	fi := newFileItems(t, srv)
	ctx := t.Context()
	season := fmt.Sprintf("%s/api/shows/%d/seasons/1/watched", srv.URL, fi.showID)
	ch, cancel := srv.bus.Subscribe()
	defer cancel()
	if code := doJSON(t, srv.client, http.MethodPost, season, "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("mark season -> %d", code)
	}
	if e := <-ch; e.Type != "watch.changed" {
		t.Errorf("event %q", e.Type)
	}
	for _, id := range []int64{fi.ep1ID, fi.ep2ID} {
		if w, err := srv.q.GetWatchState(ctx, db.GetWatchStateParams{UserID: 1, ItemType: "episode", ItemID: id}); err != nil || w.Completed != 1 {
			t.Errorf("episode %d after marking: %+v %v", id, w, err)
		}
	}
	if code := doJSON(t, srv.client, http.MethodDelete, season, "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("unmark season -> %d", code)
	}
	if _, err := srv.q.GetWatchState(ctx, db.GetWatchStateParams{UserID: 1, ItemType: "episode", ItemID: fi.ep1ID}); err == nil {
		t.Error("episode still has state after unmarking")
	}
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/api/shows/%d/seasons/7/watched", srv.URL, fi.showID), "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("missing season -> %d", code)
	}

	// Halfway through the episode file: a Continue watching card for E01.
	body := `{"positionMs":600000,"durationMs":2400000}`
	if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/api/files/%d/progress", srv.URL, fi.episode.ID), body, nil).StatusCode; code != 200 {
		t.Fatalf("progress -> %d", code)
	}
	var list ContinueList
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/watch/continue", "", &list)
	var card *ContinueItem
	for i := range list.Items {
		if list.Items[i].Kind == "episode" && *list.Items[i].EpisodeID == fi.ep1ID {
			card = &list.Items[i]
		}
	}
	if card == nil || card.Title != "Harbor Lights" || card.Subtitle != "S01E01 Pilot" || *card.ShowID != fi.showID || card.SeasonNumber != 1 ||
		card.StillPath != "/still.jpg" || card.FileID != fi.episode.ID || card.PositionMs != 600000 {
		t.Fatalf("continue = %+v", list.Items)
	}
	// A card whose file has gone is left out.
	if _, err := srv.q.MarkMediaFilesMissing(ctx, []int64{fi.episode.ID}); err != nil {
		t.Fatal(err)
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/watch/continue", "", &list)
	if len(list.Items) != 0 {
		t.Errorf("continue after the file went missing = %+v", list.Items)
	}
}
