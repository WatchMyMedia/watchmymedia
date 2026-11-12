package api

import (
	"fmt"
	"net/http"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

// clientFixture is a matched movie and a TV library with one show: two
// seasons, S01E01 and S01E02 on disk (E02 in 4K Dolby Vision), S01E03 not.
type clientFixture struct {
	srv     *testEnv
	movieID int64
	tvID    int64
	showID  int64
	ep1     db.Episode
	ep2     db.Episode
	file1   db.MediaFile
	file2   db.MediaFile
}

func newClientFixture(t *testing.T) clientFixture {
	t.Helper()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	r, pg := "R", "TV-14"
	movie, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: 1, TmdbID: 949, Title: "Heat", Overview: "o", Genres: "[]", Credits: "[]", ContentRating: &r})
	if err != nil {
		t.Fatal(err)
	}
	heat, _ := q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: heat.ID})

	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	status := "Returning Series"
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 1, Title: "Harbor Lights", Status: &status, Genres: "[]", Credits: "[]", ContentRating: &pg})
	ended := "Ended"
	other, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 2, Title: "Glass Country", Status: &ended, Genres: "[]", Credits: "[]"})
	s1, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 3})
	s2, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 2, Name: "Season 2", EpisodeCount: 1})
	d1, d2, d3 := "2026-01-01", "2026-01-08", "2026-01-15"
	ep1, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: show.ID, Number: 1, Title: "Pilot", AirDate: &d1})
	ep2, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: show.ID, Number: 2, Title: "Tide", AirDate: &d2})
	_, _ = q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: show.ID, Number: 3, Title: "Undertow", AirDate: &d3})
	_, _ = q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s2.ID, ShowID: show.ID, Number: 1, Title: "Return"})
	os1, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: other.ID, Number: 1, Name: "Season 1", EpisodeCount: 1})
	oep, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: os1.ID, ShowID: other.ID, Number: 1, Title: "Glass"})

	file := func(path string, height int64, hdr string, dv *int64, ep int64) db.MediaFile {
		probe := fmt.Sprintf(`{"container":"matroska","durationMs":2880000,"video":[{"index":0,"codec":"hevc","width":%d,"height":%d,"hdr":%q}],`+
			`"audio":[{"index":1,"codec":"eac3","channels":6,"default":true}],"subtitles":[]}`, height*16/9, height, hdr)
		mkv, dur := "matroska", int64(2880000)
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: path, Size: 100, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
			Parsed: "{}", Probe: &probe, Container: &mkv, Height: &height, DurationMs: &dur, Hdr: hdr, DvProfile: dv, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep, MediaFileID: f.ID})
		_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", ID: f.ID})
		return f
	}
	eight := int64(8)
	f1 := file("/t/Harbor Lights/S01E01.mkv", 1080, "", nil, ep1.ID)
	f2 := file("/t/Harbor Lights/S01E02.mkv", 2160, "dolbyvision", &eight, ep2.ID)
	file("/t/Glass Country/S01E01.mkv", 720, "", nil, oep.ID)
	return clientFixture{srv: srv, movieID: movie.ID, tvID: tv.ID, showID: show.ID, ep1: ep1, ep2: ep2, file1: f1, file2: f2}
}

func TestContentRatingsAndHero(t *testing.T) {
	t.Parallel()
	f := newClientFixture(t)
	var movie MovieDetail
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/movies/%d", f.srv.URL, f.movieID), "", &movie)
	var show ShowDetail
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/shows/%d", f.srv.URL, f.showID), "", &show)
	if movie.ContentRating != "R" || show.ContentRating != "TV-14" {
		t.Errorf("ratings: movie %q, show %q", movie.ContentRating, show.ContentRating)
	}
	var items ItemList
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/items", f.srv.URL, f.tvID), "", &items)
	if items.Items[0].Title != "Glass Country" || items.Items[0].ContentRating != "" || items.Items[1].ContentRating != "TV-14" {
		t.Errorf("item ratings = %+v", items.Items)
	}
	var search SearchResults
	doJSON(t, f.srv.client, http.MethodGet, f.srv.URL+"/api/search?q=heat", "", &search)
	if len(search.Movies) != 1 || search.Movies[0].ContentRating != "R" {
		t.Errorf("search = %+v", search.Movies)
	}

	// The hero is a new episode on disk: S01E02, aired yesterday, whose file
	// is 4K Dolby Vision, with its show's rating.
	s1, _ := f.srv.q.GetSeasonByNumber(t.Context(), db.GetSeasonByNumberParams{ShowID: f.showID, Number: 1})
	yesterday := time.Now().UTC().AddDate(0, 0, -1).Format(time.DateOnly)
	_, _ = f.srv.q.UpsertEpisode(t.Context(), db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: f.showID, Number: 2, Title: "Tide", AirDate: &yesterday})
	var home HomeData
	doJSON(t, f.srv.client, http.MethodGet, f.srv.URL+"/api/home", "", &home)
	h := home.Hero
	if h == nil || h.Kind != "episode" || *h.EpisodeID != f.ep2.ID || h.ContentRating != "TV-14" || h.Quality != "4K" || !h.HDR {
		t.Fatalf("episode hero = %+v", h)
	}
	// A movie hero takes its item's.
	m := pickHero(HomeData{RecentlyAddedMovies: []BrowseItem{{Kind: "movie", ID: 3, Title: "Heat", ContentRating: "R", Quality: "1080p"}}}, nil)
	if m == nil || m.ContentRating != "R" || m.Quality != "1080p" || m.HDR {
		t.Errorf("movie hero = %+v", m)
	}
}

func TestLibraryTotals(t *testing.T) {
	t.Parallel()
	f := newClientFixture(t)
	var items ItemList
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/items?filter=continuing", f.srv.URL, f.tvID), "", &items)
	want := ItemTotals{Items: 2, Episodes: 3, Continuing: 1, Unmatched: 0, TotalSize: 300}
	if items.Total != 1 || items.Totals != want {
		t.Errorf("filtered total %d, totals %+v, want %+v", items.Total, items.Totals, want)
	}
	doJSON(t, f.srv.client, http.MethodGet, f.srv.URL+"/api/libraries/1/items", "", &items)
	if items.Totals.Items != 1 || items.Totals.Episodes != 0 || items.Totals.TotalSize != 10 {
		t.Errorf("movie library totals = %+v", items.Totals)
	}
}

func TestEpisodeWatchedFlagsAndShowWatched(t *testing.T) {
	t.Parallel()
	f := newClientFixture(t)
	aired := func() map[int64]bool {
		var rows LibraryRows
		doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/rows", f.srv.URL, f.tvID), "", &rows)
		out := map[int64]bool{}
		for _, c := range rows.RecentlyAired {
			out[c.EpisodeID] = c.Watched
		}
		return out
	}
	if w := aired(); w[f.ep1.ID] || w[f.ep2.ID] {
		t.Fatalf("nothing watched yet: %v", w)
	}
	if code := doJSON(t, f.srv.client, http.MethodPost, fmt.Sprintf("%s/api/watch/episode/%d/watched", f.srv.URL, f.ep1.ID), "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("mark watched -> %d", code)
	}
	if w := aired(); !w[f.ep1.ID] || w[f.ep2.ID] {
		t.Errorf("after marking E01: %v", w)
	}
	var search SearchResults
	doJSON(t, f.srv.client, http.MethodGet, f.srv.URL+"/api/search?q=pilot", "", &search)
	if len(search.Episodes) != 1 || !search.Episodes[0].Watched {
		t.Errorf("search episodes = %+v", search.Episodes)
	}

	// The whole show in one call, and one event.
	ch, cancel := f.srv.bus.Subscribe()
	defer cancel()
	if code := doJSON(t, f.srv.client, http.MethodPost, fmt.Sprintf("%s/api/shows/%d/watched", f.srv.URL, f.showID), "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("mark show watched -> %d", code)
	}
	select {
	case ev := <-ch:
		if wc, ok := ev.Data.(WatchChanged); ev.Type != "watch.changed" || !ok || len(wc.ItemIDs) != 4 {
			t.Errorf("event = %s %+v, want watch.changed for the show's 4 episodes", ev.Type, ev.Data)
		}
	case <-time.After(time.Second):
		t.Error("no watch.changed event")
	}
	var show ShowDetail
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/shows/%d", f.srv.URL, f.showID), "", &show)
	if show.Seasons[0].WatchedCount != 2 || show.NextEpisode != nil {
		t.Errorf("after marking the show: seasons %+v, next %+v", show.Seasons, show.NextEpisode)
	}
	if w := aired(); !w[f.ep2.ID] {
		t.Errorf("E02 not watched after marking the show: %v", w)
	}
	if code := doJSON(t, f.srv.client, http.MethodDelete, fmt.Sprintf("%s/api/shows/%d/watched", f.srv.URL, f.showID), "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("mark show unwatched -> %d", code)
	}
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/shows/%d", f.srv.URL, f.showID), "", &show)
	if show.Seasons[0].WatchedCount != 0 || show.NextEpisode == nil || show.NextEpisode.EpisodeID != f.ep1.ID {
		t.Errorf("after unmarking the show: seasons %+v, next %+v", show.Seasons, show.NextEpisode)
	}
	if code := doJSON(t, f.srv.client, http.MethodPost, f.srv.URL+"/api/shows/999/watched", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("unknown show -> %d", code)
	}
}

func TestFileHDRAndEpisode(t *testing.T) {
	t.Parallel()
	f := newClientFixture(t)
	var season SeasonDetail
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/shows/%d/seasons/1", f.srv.URL, f.showID), "", &season)
	v1, v2 := season.Episodes[0].Files[0], season.Episodes[1].Files[0]
	if v1.HDR != "" || v1.DVProfile != 0 || v2.HDR != "dolbyvision" || v2.DVProfile != 8 {
		t.Errorf("episode files: %q/%d and %q/%d", v1.HDR, v1.DVProfile, v2.HDR, v2.DVProfile)
	}
	var file MediaFileDetail
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/files/%d", f.srv.URL, f.file2.ID), "", &file)
	if file.EpisodeID == nil || *file.EpisodeID != f.ep2.ID || *file.ShowID != f.showID || *file.SeasonNumber != 1 || *file.EpisodeNumber != 2 {
		t.Errorf("file's episode = %v %v %v %v", file.EpisodeID, file.ShowID, file.SeasonNumber, file.EpisodeNumber)
	}
	var movieFile MediaFileDetail
	heat, _ := f.srv.q.GetMediaFileByPath(t.Context(), "/m/Heat (1995).mkv")
	doJSON(t, f.srv.client, http.MethodGet, fmt.Sprintf("%s/api/files/%d", f.srv.URL, heat.ID), "", &movieFile)
	if movieFile.EpisodeID != nil || movieFile.ShowID != nil || movieFile.MovieID == nil {
		t.Errorf("movie file = %+v", movieFile)
	}
}

func TestPlaybackPlans(t *testing.T) {
	t.Parallel()
	f := newClientFixture(t)
	// A phone that plays HEVC in MKV and AAC only: the 1080p file needs its
	// EAC3 audio converted; the 4K one is too big as well.
	body := fmt.Sprintf(`{"profile":{"containers":["mkv","mp4"],"videoCodecs":["h264","hevc"],"audioCodecs":["aac"],"maxHeight":1080},"fileIds":[%d,%d,%d,999,%d]}`,
		f.file1.ID, f.file2.ID, f.file1.ID, f.file1.ID)
	var plans PlanList
	if code := doJSON(t, f.srv.client, http.MethodPost, f.srv.URL+"/api/playback/plans", body, &plans).StatusCode; code != http.StatusOK {
		t.Fatalf("plans -> %d", code)
	}
	if len(plans.Items) != 2 {
		t.Fatalf("plans = %+v, want one per known file", plans.Items)
	}
	p1, p2 := plans.Items[0], plans.Items[1]
	if p1.FileID != f.file1.ID || p1.Mode != playback.ModeAudio || !p1.Video.Copy || p1.Audio.Copy || p1.Audio.Codec != "aac" || p1.Protocol != "hls" {
		t.Errorf("1080p plan = %+v", p1)
	}
	if p2.FileID != f.file2.ID || p2.Mode != playback.ModeTranscode || p2.Video.Copy || p2.Video.Height != 1080 || len(p2.Reasons) == 0 {
		t.Errorf("4K plan = %+v", p2)
	}
	// One that takes EAC3 plays the 1080p file as it is.
	body = fmt.Sprintf(`{"profile":{"containers":["mkv"],"videoCodecs":["hevc"],"audioCodecs":["aac","eac3"]},"fileIds":[%d]}`, f.file1.ID)
	doJSON(t, f.srv.client, http.MethodPost, f.srv.URL+"/api/playback/plans", body, &plans)
	if len(plans.Items) != 1 || plans.Items[0].Mode != playback.ModeDirect || plans.Items[0].Protocol != "file" {
		t.Errorf("direct plan = %+v", plans.Items)
	}
	// An empty list is refused.
	if code := doJSON(t, f.srv.client, http.MethodPost, f.srv.URL+"/api/playback/plans", `{"profile":{},"fileIds":[]}`, nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("no files -> %d", code)
	}
}

func TestNextEpisodeRuntimeAndFinale(t *testing.T) {
	t.Parallel()
	f := newClientFixture(t)
	q := f.srv.q
	ctx := t.Context()
	// E03 gets a file without a probed length, so its runtime is TMDB's.
	s1, _ := q.GetSeasonByNumber(ctx, db.GetSeasonByNumberParams{ShowID: f.showID, Number: 1})
	runtime := int64(52)
	ep3, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: s1.ID, ShowID: f.showID, Number: 3, Title: "Undertow", RuntimeMin: &runtime})
	probe := `{"container":"matroska","durationMs":0,"video":[{"index":0,"codec":"hevc","width":1920,"height":1080}],"audio":[],"subtitles":[]}`
	f3, _ := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: f.tvID, Path: "/t/Harbor Lights/S01E03.mkv", Size: 1, Mtime: db.FormatTime(time.Now()),
		AddedAt: db.FormatTime(time.Now()), Parsed: "{}", Probe: &probe, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep3.ID, MediaFileID: f3.ID})

	next := func(fileID int64) *playback.UpNext {
		var d playback.Decision
		// An HDR screen, so E02's Dolby Vision plays as it is: these tests
		// have no transcoder.
		body := `{"profile":{"containers":["mkv"],"videoCodecs":["hevc"],"audioCodecs":["eac3"],"hdr":true}}`
		if code := doJSON(t, f.srv.client, http.MethodPost, fmt.Sprintf("%s/api/files/%d/playback", f.srv.URL, fileID), body, &d).StatusCode; code != http.StatusOK {
			t.Fatalf("playback -> %d", code)
		}
		return d.NextEpisode
	}
	if n := next(f.file1.ID); n == nil || n.EpisodeNumber != 2 || n.RuntimeMin != 48 || n.SeasonFinale {
		t.Errorf("after E01 = %+v, want E02 at its file's 48 min", n)
	}
	if n := next(f.file2.ID); n == nil || n.EpisodeNumber != 3 || n.RuntimeMin != 52 || !n.SeasonFinale {
		t.Errorf("after E02 = %+v, want the finale E03 at TMDB's 52 min", n)
	}
}
