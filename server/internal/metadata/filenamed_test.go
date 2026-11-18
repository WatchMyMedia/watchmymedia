package metadata

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb/tmdbtest"
	"watchmymedia/server/internal/watchstate"
)

// cunkTMDB is a fake TMDB with Cunk on Cinema as TMDB lists it: the BBC's
// three episodes, where the files are the streaming release's six. list
// lists more of season 1, as TMDB does when someone adds them. A second,
// made-up show has all six, for lining files up elsewhere.
func cunkTMDB(t *testing.T) (f *tmdbtest.Server, list func(episodes ...string)) {
	t.Helper()
	f = tmdbtest.New(t)
	show := func(id int, name string, episodes ...string) {
		eps := make([]any, 0, len(episodes))
		for i, title := range episodes {
			eps = append(eps, map[string]any{"id": id*100 + i + 1, "episode_number": i + 1, "season_number": 1, "name": title, "air_date": "2026-11-12"})
		}
		f.JSON(fmt.Sprintf("/tv/%d/season/1", id), map[string]any{"id": id * 10, "season_number": 1, "name": "Miniseries", "episodes": eps})
		f.JSON(fmt.Sprintf("/tv/%d", id), map[string]any{"id": id, "name": name, "first_air_date": "2026-11-12", "genres": []any{},
			"seasons": []any{map[string]any{"season_number": 1, "name": "Miniseries", "episode_count": len(episodes), "air_date": "2026-11-12"}}})
	}
	bbc := []string{"Episode 1", "Episode 2", "Episode 3"}
	show(900, "Cunk on Cinema", bbc...)
	show(901, "Cunk on Cinema: Six", "The Opening Shot", "The Sweet Sound of Success", "A Star Is Born", "Sex and Violence and Sharks", "Star Harder", "Hollypocalypse Now")
	f.Handle("/search/tv", func(r *http.Request) any {
		if strings.Contains(strings.ToLower(r.URL.Query().Get("query")), "cunk") {
			return map[string]any{"results": []any{map[string]any{"id": 900, "name": "Cunk on Cinema", "first_air_date": "2026-11-12"}}}
		}
		return map[string]any{"results": []any{}}
	})
	return f, func(more ...string) { show(900, "Cunk on Cinema", append(bbc, more...)...) }
}

func cunkFile(e env, season, episode int) db.MediaFile {
	return e.file(e.tv, fmt.Sprintf("/t/Cunk on Cinema/Season %02d/Cunk on Cinema - S%02dE%02d.mkv", season, season, episode),
		fmt.Sprintf(`{"kind":"episode","title":"Cunk on Cinema","season":%d,"episodes":[%d]}`, season, episode))
}

// linkedTo is the one episode a file is linked to.
func linkedTo(t *testing.T, e env, f db.MediaFile) db.Episode {
	t.Helper()
	ids, err := e.q.ListEpisodeIDsForFile(e.ctx, f.ID)
	if err != nil || len(ids) != 1 {
		t.Fatalf("%s is linked to %v (%v), want one episode", f.Path, ids, err)
	}
	ep, err := e.q.GetEpisode(e.ctx, ids[0])
	if err != nil {
		t.Fatal(err)
	}
	return ep
}

// nextDay is the matcher after its caches of TMDB's answers have expired.
func nextDay(e env, f *tmdbtest.Server) *Matcher {
	return New(e.q, f.Client(), e.bus, slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError})), Options{Workers: 2})
}

func TestFileNamedEpisodes(t *testing.T) {
	t.Parallel()
	fake, list := cunkTMDB(t)
	e := setupWith(t, fake.Client())
	var files []db.MediaFile
	for n := 1; n <= 6; n++ {
		files = append(files, cunkFile(e, 1, n))
	}
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil || sum.Matched != 3 || sum.Unmatched != 3 {
		t.Fatalf("match = %+v, %v", sum, err)
	}
	// E04 to E06 stay unmatched with TMDB's reason, and each plays as an
	// episode of the show in its place, named after its file.
	ids := map[int]int64{}
	for i, f := range files[3:] {
		n := i + 4
		row, _ := e.q.GetMediaFile(e.ctx, f.ID)
		if row.MatchStatus != StatusUnmatched || !strings.Contains(deref(row.MatchError), fmt.Sprintf("no episode [%d]", n)) {
			t.Errorf("E%02d = %s %q", n, row.MatchStatus, deref(row.MatchError))
		}
		ep := linkedTo(t, e, f)
		if want := fmt.Sprintf("Cunk on Cinema - S01E%02d", n); ep.FileNamed != 1 || ep.Number != int64(n) || ep.Title != want {
			t.Errorf("E%02d plays as %+v, want %q", n, ep, want)
		}
		ids[n] = ep.ID
	}
	show, err := e.q.GetShowByTMDB(e.ctx, db.GetShowByTMDBParams{LibraryID: e.tv.ID, TmdbID: 900})
	if err != nil {
		t.Fatal(err)
	}
	seasons, _ := e.q.ListSeasons(e.ctx, show.ID)
	if len(seasons) != 1 || seasons[0].FileNamed != 0 || seasons[0].AvailableCount != 6 || seasons[0].EpisodeCount != 3 {
		t.Fatalf("seasons = %+v", seasons)
	}
	// They are no TMDB episodes, so they don't arrive for followers.
	wantArrivals(t, e, map[string]int64{"900 S01E01": 0, "900 S01E02": 0, "900 S01E03": 0})

	// The daily retry finds the same episodes, not new ones.
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, true); err != nil {
		t.Fatal(err)
	}
	if ep := linkedTo(t, e, files[3]); ep.ID != ids[4] {
		t.Errorf("retry moved E04 to %d from %d", ep.ID, ids[4])
	}
	if eps, _ := e.q.ListSeasonEpisodes(e.ctx, seasons[0].ID); len(eps) != 6 {
		t.Errorf("season 1 has %d episodes after the retry", len(eps))
	}

	// Someone watches E04 and is partway through E05.
	user, _ := e.q.CreateUser(e.ctx, db.CreateUserParams{Name: "u", PasswordHash: "x"})
	ws := e.m.opts.Watch
	if err := ws.MarkWatched(e.ctx, user.ID, watchstate.Episode, ids[4]); err != nil {
		t.Fatal(err)
	}

	// TMDB lists E04. The next day's retry fills in the same row, so the
	// file's link and the watch state stay, and the file is matched.
	list("Sex and Violence and Sharks")
	m := nextDay(e, fake)
	if _, err := m.MatchLibrary(e.ctx, e.tv, true); err != nil {
		t.Fatal(err)
	}
	ep := linkedTo(t, e, files[3])
	if ep.ID != ids[4] || ep.FileNamed != 0 || ep.Title != "Sex and Violence and Sharks" {
		t.Errorf("E04 = %+v", ep)
	}
	if row, _ := e.q.GetMediaFile(e.ctx, files[3].ID); row.MatchStatus != StatusMatched {
		t.Errorf("E04 = %s %q", row.MatchStatus, deref(row.MatchError))
	}
	if w, err := e.q.GetWatchState(e.ctx, db.GetWatchStateParams{UserID: user.ID, ItemType: "episode", ItemID: ids[4]}); err != nil || w.Completed != 1 {
		t.Errorf("E04's watch state = %+v %v", w, err)
	}
	if ep := linkedTo(t, e, files[4]); ep.ID != ids[5] || ep.FileNamed != 1 {
		t.Errorf("E05 = %+v", ep)
	}
	// It arrives now, as TMDB's episode: quietly, as the file is not new by
	// then on a real server, but here it is.
	wantArrivals(t, e, map[string]int64{"900 S01E01": 0, "900 S01E02": 0, "900 S01E03": 0, "900 S01E04": 0})
}

func TestFileNamedSeason(t *testing.T) {
	t.Parallel()
	fake, _ := cunkTMDB(t)
	e := setupWith(t, fake.Client())
	f := cunkFile(e, 2, 1)
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	row, _ := e.q.GetMediaFile(e.ctx, f.ID)
	if row.MatchStatus != StatusUnmatched || !strings.Contains(deref(row.MatchError), "no season 2") {
		t.Errorf("S02E01 = %s %q", row.MatchStatus, deref(row.MatchError))
	}
	ep := linkedTo(t, e, f)
	season, err := e.q.GetSeason(e.ctx, ep.SeasonID)
	if err != nil || season.Number != 2 || season.FileNamed != 1 || season.Name != "Season 2" || ep.FileNamed != 1 || ep.Number != 1 {
		t.Fatalf("S02E01 plays as %+v of %+v (%v)", ep, season, err)
	}
	// Asked again, TMDB still has no season 2, and the reason still says so.
	if _, err := nextDay(e, fake).MatchLibrary(e.ctx, e.tv, true); err != nil {
		t.Fatal(err)
	}
	if row, _ := e.q.GetMediaFile(e.ctx, f.ID); !strings.Contains(deref(row.MatchError), "no season 2") {
		t.Errorf("retried S02E01 = %q", deref(row.MatchError))
	}
	if again := linkedTo(t, e, f); again.ID != ep.ID {
		t.Errorf("retry moved S02E01 to %d from %d", again.ID, ep.ID)
	}
}

// A file with no number to put it at plays as it did: from the library's
// list by its file name, with no episode.
func TestFileNamedNeedsNumbers(t *testing.T) {
	t.Parallel()
	fake, _ := cunkTMDB(t)
	e := setupWith(t, fake.Client())
	odd := []db.MediaFile{
		e.file(e.tv, "/t/Cunk on Cinema/Cunk on Cinema - 2026-12-25.mkv", `{"kind":"episode","title":"Cunk on Cinema","airDate":"2026-12-25"}`),
		e.file(e.tv, "/t/Cunk on Cinema/Specials/Cunk on Cinema - S00E03.mkv", `{"kind":"episode","title":"Cunk on Cinema","season":0,"episodes":[3]}`),
		e.file(e.tv, "/t/Cunk on Cinema/Cunk on Cinema - S01E00.mkv", `{"kind":"episode","title":"Cunk on Cinema","season":1,"episodes":[0]}`),
		e.file(e.tv, "/t/Cunk on Cinema/Cunk on Cinema - S2019E01.mkv", `{"kind":"episode","title":"Cunk on Cinema","season":2019,"episodes":[1]}`),
	}
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	for _, f := range odd {
		row, _ := e.q.GetMediaFile(e.ctx, f.ID)
		ids, _ := e.q.ListEpisodeIDsForFile(e.ctx, f.ID)
		if row.MatchStatus != StatusUnmatched || len(ids) != 0 {
			t.Errorf("%s = %s, episodes %v", f.Path, row.MatchStatus, ids)
		}
	}
}

// TMDB not answering is no reason for a file to stop playing.
func TestFileNamedSurvivesLookupFailure(t *testing.T) {
	t.Parallel()
	fake, _ := cunkTMDB(t)
	e := setupWith(t, fake.Client())
	f := cunkFile(e, 1, 4)
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	ep := linkedTo(t, e, f)
	m := nextDay(e, fake) // its client keeps the key TMDB now refuses
	fake.SetKey("revoked")
	if _, err := m.MatchLibrary(e.ctx, e.tv, true); err != nil {
		t.Fatal(err)
	}
	if row, _ := e.q.GetMediaFile(e.ctx, f.ID); row.MatchStatus != StatusFailed {
		t.Errorf("E04 = %s %q", row.MatchStatus, deref(row.MatchError))
	}
	if again := linkedTo(t, e, f); again.ID != ep.ID || again.FileNamed != 1 {
		t.Errorf("E04 after a failed lookup = %+v, want %+v", again, ep)
	}
}

// Lining a file up elsewhere hands its watch state to where it went and
// removes the episode named after it, and a season made for files with it.
func TestFileNamedHandsOnWatchState(t *testing.T) {
	t.Parallel()
	fake, _ := cunkTMDB(t)
	e := setupWith(t, fake.Client())
	e4, s2 := cunkFile(e, 1, 4), cunkFile(e, 2, 1)
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	from4, from2 := linkedTo(t, e, e4), linkedTo(t, e, s2)
	user, _ := e.q.CreateUser(e.ctx, db.CreateUserParams{Name: "u", PasswordHash: "x"})
	ws := e.m.opts.Watch
	if err := ws.MarkWatched(e.ctx, user.ID, watchstate.Episode, from4.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := ws.Report(e.ctx, watchstate.Progress{UserID: user.ID, ItemType: watchstate.Episode, ItemIDs: []int64{from2.ID}, FileID: s2.ID, PositionMs: 60_000, DurationMs: 1_800_000}); err != nil {
		t.Fatal(err)
	}

	if err := e.m.Apply(e.ctx, e.tv, e4, 901, new(1), []int{4}); err != nil {
		t.Fatal(err)
	}
	if err := e.m.Apply(e.ctx, e.tv, s2, 901, new(1), []int{6}); err != nil {
		t.Fatal(err)
	}
	to4, to6 := linkedTo(t, e, e4), linkedTo(t, e, s2)
	if to4.Title != "Sex and Violence and Sharks" || to6.Title != "Hollypocalypse Now" {
		t.Fatalf("lined up on %q and %q", to4.Title, to6.Title)
	}
	if w, err := e.q.GetWatchState(e.ctx, db.GetWatchStateParams{UserID: user.ID, ItemType: "episode", ItemID: to4.ID}); err != nil || w.Completed != 1 {
		t.Errorf("E04's watch state = %+v %v", w, err)
	}
	if w, err := e.q.GetWatchState(e.ctx, db.GetWatchStateParams{UserID: user.ID, ItemType: "episode", ItemID: to6.ID}); err != nil || w.PositionMs != 60_000 {
		t.Errorf("S02E01's watch state = %+v %v", w, err)
	}
	for _, id := range []int64{from4.ID, from2.ID} {
		if _, err := e.q.GetEpisode(e.ctx, id); err == nil {
			t.Errorf("episode %d named after a file is still there", id)
		}
	}
	if _, err := e.q.GetSeason(e.ctx, from2.SeasonID); err == nil {
		t.Error("the season made for S02E01 is still there")
	}
	if _, err := e.q.GetSeason(e.ctx, from4.SeasonID); err != nil {
		t.Errorf("TMDB's season 1 went with E04: %v", err)
	}
}

// A person matching a group to a show leaves the files TMDB lacks there
// unmatched, and playing in that show straight away.
func TestApplyGroupNamesWhatTMDBLacks(t *testing.T) {
	t.Parallel()
	fake, _ := cunkTMDB(t)
	e := setupWith(t, fake.Client())
	e1, e5 := cunkFile(e, 1, 1), cunkFile(e, 1, 5)
	res, err := e.m.ApplyGroup(e.ctx, e.tv, []db.MediaFile{e1, e5}, 900, false)
	if err != nil || res.Matched != 1 || res.NeedEpisodes != 1 {
		t.Fatalf("apply = %+v, %v", res, err)
	}
	if row, _ := e.q.GetMediaFile(e.ctx, e5.ID); row.MatchStatus != StatusUnmatched {
		t.Errorf("E05 = %s", row.MatchStatus)
	}
	if ep := linkedTo(t, e, e5); ep.FileNamed != 1 || ep.Number != 5 {
		t.Errorf("E05 plays as %+v", ep)
	}
}

// One whose file a person deleted goes after the next match run.
func TestFileNamedSweep(t *testing.T) {
	t.Parallel()
	fake, _ := cunkTMDB(t)
	e := setupWith(t, fake.Client())
	e4, s2 := cunkFile(e, 1, 4), cunkFile(e, 2, 1)
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	ep4, ep2 := linkedTo(t, e, e4), linkedTo(t, e, s2)
	if _, err := e.conn.ExecContext(e.ctx, `DELETE FROM media_files WHERE id = ?`, s2.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.q.GetEpisode(e.ctx, ep2.ID); err == nil {
		t.Error("S02E01 without its file is still there")
	}
	if _, err := e.q.GetSeason(e.ctx, ep2.SeasonID); err == nil {
		t.Error("season 2 without episodes is still there")
	}
	if _, err := e.q.GetEpisode(e.ctx, ep4.ID); err != nil {
		t.Errorf("E04, whose file is there, went too: %v", err)
	}
}
