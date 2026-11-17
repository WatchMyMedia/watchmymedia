package api

import (
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

// Episodes named after files (internal/metadata/filenamed.go): a show TMDB
// lists with three episodes, whose files are six. The three TMDB lacks play
// from the show's page as episodes named after their files, count in their
// season, read as out rather than coming, are not listed a second time by
// file name, and stay in the Unmatched view. Requests count only TMDB's.
func TestFileNamedEpisodesBrowse(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	now := time.Now().UTC()
	tv, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	if err != nil {
		t.Fatal(err)
	}
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 900, Title: "Cunk on Cinema", Genres: "[]", Credits: "[]"})
	season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Miniseries", EpisodeCount: 3})
	aired := now.AddDate(0, 0, -5).Format(time.DateOnly)
	h := int64(1080)
	file := func(path, parsed, status string, reason *string) db.MediaFile {
		t.Helper()
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: path, Size: 100, Mtime: db.FormatTime(now),
			AddedAt: db.FormatTime(now.Add(-time.Hour)), Height: &h, Parsed: parsed, LastSeenAt: db.FormatTime(now), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: status, MatchError: reason, ID: f.ID}); err != nil {
			t.Fatal(err)
		}
		return f
	}
	for n := int64(1); n <= 6; n++ {
		path := fmt.Sprintf("/t/Cunk on Cinema/Cunk on Cinema - S01E%02d.mkv", n)
		parsed := fmt.Sprintf(`{"kind":"episode","title":"Cunk on Cinema","season":1,"episodes":[%d]}`, n)
		var ep db.Episode
		var f db.MediaFile
		if n <= 3 {
			ep, _ = q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: n, Title: fmt.Sprintf("Episode %d", n), AirDate: &aired})
			f = file(path, parsed, "matched", nil)
		} else {
			if err := q.InsertFileNamedEpisode(ctx, db.InsertFileNamedEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: n, Title: fmt.Sprintf("Cunk on Cinema - S01E%02d", n)}); err != nil {
				t.Fatal(err)
			}
			ep, _ = q.GetEpisodeByNumber(ctx, db.GetEpisodeByNumberParams{SeasonID: season.ID, Number: n})
			f = file(path, parsed, "unmatched", new(fmt.Sprintf(`season 1 of "Cunk on Cinema" has no episode [%d] on TMDB`, n)))
		}
		if err := q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: f.ID}); err != nil {
			t.Fatal(err)
		}
	}
	// A file with nothing to place it by still lists by its own name.
	file("/t/Mystery/a1b2c3.mkv", `{"kind":"unknown","title":"a1b2c3"}`, "unmatched", new("could not read a season and episode number from the file name"))
	get := func(path string, out any) {
		t.Helper()
		doJSON(t, srv.client, http.MethodGet, srv.URL+path, "", out)
	}

	var detail ShowDetail
	get(fmt.Sprintf("/api/shows/%d", show.ID), &detail)
	if len(detail.Seasons) != 1 || detail.Seasons[0].EpisodeCount != 6 || detail.Seasons[0].AvailableCount != 6 || detail.TotalEpisodes != 6 || detail.EpisodeCount != 6 {
		t.Errorf("show = %d of %d episodes, seasons %+v", detail.EpisodeCount, detail.TotalEpisodes, detail.Seasons)
	}

	var se SeasonDetail
	get(fmt.Sprintf("/api/shows/%d/seasons/1", show.ID), &se)
	if len(se.Episodes) != 6 || se.EpisodeCount != 6 || se.AvailableCount != 6 {
		t.Fatalf("season = %+v with %d episodes", se.Season, len(se.Episodes))
	}
	if e := se.Episodes[3]; e.Number != 4 || e.Title != "Cunk on Cinema - S01E04" || !e.Aired || !e.HasFile || e.AirDate != "" {
		t.Errorf("E04 = %+v", e)
	}

	var rows LibraryRows
	get(fmt.Sprintf("/api/libraries/%d/rows", tv.ID), &rows)
	var showCard, mystery bool
	for _, it := range rows.RecentlyAdded {
		switch {
		case it.Kind == "show" && it.ID == show.ID:
			showCard = true
		case it.Kind == "unmatched" && strings.Contains(it.FileName, "Cunk"):
			t.Errorf("listed again by file name: %+v", it)
		case it.Kind == "unmatched" && strings.Contains(it.FileName, "a1b2c3"):
			mystery = true
		}
	}
	if !showCard || !mystery {
		t.Errorf("recently added: show %v, unplaced file %v in %+v", showCard, mystery, rows.RecentlyAdded)
	}

	var groups UnmatchedGroupList
	get(fmt.Sprintf("/api/libraries/%d/unmatched", tv.ID), &groups)
	listed := 0
	for _, g := range groups.Groups {
		for _, f := range g.Files {
			if strings.Contains(f.RelativePath, "Cunk") {
				listed++
			}
		}
	}
	if listed != 3 {
		t.Errorf("Unmatched view lists %d of the 3 files: %+v", listed, groups.Groups)
	}

	today := now.Format(time.DateOnly)
	avail, err := q.ListSeasonAvailabilityByTMDB(ctx, db.ListSeasonAvailabilityByTMDBParams{Today: &today, TmdbID: 900})
	if err != nil || len(avail) != 1 || avail[0].AvailableCount != 3 {
		t.Errorf("availability for requests = %+v, %v", avail, err)
	}
}
