package api

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/watchstate"
)

// Every local route leaves a guard an import respects (PLAN-PLEX-SYNC.md
// P1): starting playback before any progress report, progress reports from
// outside a play, unwatching an item, and clearing a season or a show.
func TestWatchRoutesGuardLocalChoices(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	base := srv.URL + "/api"
	imports := watchstate.New(q, nil)
	path := filepath.Join(t.TempDir(), "Clip (2020).mp4")
	if err := os.WriteFile(path, []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatal(err)
	}
	lib := srv.fileLibrary(t, path)
	probeJSON := `{"container":"mov","durationMs":600000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2}],"subtitles":[]}`
	dur := int64(600_000)
	f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib, Path: path, Size: 16, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, DurationMs: &dur, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	movie, _ := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: lib, TmdbID: 603, Title: "Clip", Genres: "[]", Credits: "[]"})
	if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	target := watchstate.Target{ItemType: "movie", ItemID: movie.ID, Identity: watchstate.Identity{LibraryID: lib, TMDBID: 603}}
	guard := func(itemType string, id int64) db.WatchGuard {
		t.Helper()
		g, err := q.GetWatchGuard(ctx, db.GetWatchGuardParams{UserID: 1, ItemType: itemType, ItemID: id})
		if err != nil {
			t.Fatalf("no guard on %s %d: %v", itemType, id, err)
		}
		return g
	}
	importKept := func(targets ...watchstate.Target) {
		t.Helper()
		got, err := imports.Import(ctx, 1, 1, targets)
		if err != nil {
			t.Fatal(err)
		}
		for i, o := range got {
			if o != watchstate.LocalKept {
				t.Errorf("import of %s %d = %s", targets[i].ItemType, targets[i].ItemID, o)
			}
		}
	}

	// Starting playback guards the movie before any progress, with no
	// activity tracker recording the play.
	var d playback.Decision
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"original"}`
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, f.ID), body, &d).StatusCode; code != 200 || d.Mode != playback.ModeDirect {
		t.Fatalf("playback -> %d %+v", code, d)
	}
	started := guard("movie", movie.ID)
	if started.Owner != "local" {
		t.Errorf("guard after playback start = %+v", started)
	}
	if _, err := q.GetWatchState(ctx, db.GetWatchStateParams{UserID: 1, ItemType: "movie", ItemID: movie.ID}); err == nil {
		t.Error("playback start saved progress")
	}
	importKept(target)

	// A report from outside a play takes a newer revision.
	if code := doJSON(t, srv.client, http.MethodPut, fmt.Sprintf("%s/files/%d/progress", base, f.ID), `{"positionMs":60000,"durationMs":600000}`, nil).StatusCode; code != 200 {
		t.Fatalf("progress -> %d", code)
	}
	if g := guard("movie", movie.ID); g.Owner != "local" || g.Revision <= started.Revision {
		t.Errorf("guard after progress = %+v, started at %+v", g, started)
	}
	importKept(target)

	// Unwatching deletes the state, as it always has, and keeps the guard.
	if code := doJSON(t, srv.client, http.MethodDelete, fmt.Sprintf("%s/watch/movie/%d/watched", base, movie.ID), "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("unwatch -> %d", code)
	}
	if _, err := q.GetWatchState(ctx, db.GetWatchStateParams{UserID: 1, ItemType: "movie", ItemID: movie.ID}); err == nil {
		t.Error("unwatch kept the state")
	}
	guard("movie", movie.ID)
	importKept(target)

	// Clearing a season guards its episodes, watched or not; clearing a show
	// guards every one of them.
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 1396, Title: "Show", Genres: "[]", Credits: "[]"})
	var eps []watchstate.Target
	for s := int64(1); s <= 2; s++ {
		season, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: s, EpisodeCount: 2})
		for n := int64(1); n <= 2; n++ {
			e, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: n})
			eps = append(eps, watchstate.Target{ItemType: "episode", ItemID: e.ID, Identity: watchstate.Identity{LibraryID: tv.ID, TMDBID: 1396, Season: s, Episode: n}})
		}
	}
	if code := doJSON(t, srv.client, http.MethodPost, fmt.Sprintf("%s/watch/episode/%d/watched", base, eps[0].ItemID), "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("mark episode -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodDelete, fmt.Sprintf("%s/shows/%d/seasons/1/watched", base, show.ID), "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("clear season -> %d", code)
	}
	importKept(eps[0], eps[1])
	if got, err := imports.Evaluate(ctx, 1, eps[2:]); err != nil || got[0] != watchstate.Applied || got[1] != watchstate.Applied {
		t.Errorf("season 2 after clearing season 1 = %v %v", got, err)
	}
	if code := doJSON(t, srv.client, http.MethodDelete, fmt.Sprintf("%s/shows/%d/watched", base, show.ID), "", nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("clear show -> %d", code)
	}
	importKept(eps...)
}
