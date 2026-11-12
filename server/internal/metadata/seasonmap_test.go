package metadata

import (
	"fmt"
	"net/http"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// seasonTMDB is a fake TMDB with made-up shows whose seasons differ from
// how files number them.
func seasonTMDB(t *testing.T) *tmdbtest.Server {
	t.Helper()
	f := tmdbtest.New(t)
	show := func(id int, name, first string, seasons ...[]string) {
		var list []any
		for i, names := range seasons {
			n := i + 1
			list = append(list, map[string]any{"season_number": n, "name": fmt.Sprintf("Season %d", n), "episode_count": len(names), "air_date": first})
			var eps []any
			for j, name := range names {
				eps = append(eps, map[string]any{"id": id*1000 + n*100 + j, "episode_number": j + 1, "season_number": n, "name": name, "air_date": first})
			}
			f.JSON(fmt.Sprintf("/tv/%d/season/%d", id, n), map[string]any{"id": id*10 + n, "season_number": n, "name": fmt.Sprintf("Season %d", n), "episodes": eps})
		}
		f.JSON(fmt.Sprintf("/tv/%d", id), map[string]any{"id": id, "name": name, "first_air_date": first, "genres": []any{}, "seasons": list})
	}
	// Harbor Lights: three seasons; its fourth is a show of its own.
	show(500, "Harbor Lights", "2022-01-01", []string{"Pilot", "Second", "Third"}, []string{"Low Water", "High Water"}, []string{"Slack Water", "Dead Reckoning", "Spring Tide"})
	show(501, "Harbor Lights: Low Tide", "2026-01-01", []string{"Ebb", "Neap", "Flood"})
	show(499, "Harbor Lights: Origins", "2010-01-01", []string{"Before", "Earlier", "First"})
	show(502, "Harbor Lights: Short", "2027-01-01", []string{"Only One"})
	// The Night Ferry: two seasons that TMDB also lists in three parts.
	show(600, "The Night Ferry", "2021-01-01", []string{"One", "Two"}, []string{"Three", "Four", "Five", "Six"})
	part := func(order int, eps ...[2]int) map[string]any {
		var list []any
		for i, e := range eps {
			list = append(list, map[string]any{"season_number": e[0], "episode_number": e[1], "order": i})
		}
		return map[string]any{"name": fmt.Sprintf("Part %d", order), "order": order, "episodes": list}
	}
	f.JSON("/tv/600/episode_groups", map[string]any{"results": []any{map[string]any{"id": "parts600", "name": "Parts", "group_count": 3, "episode_count": 6}}})
	f.JSON("/tv/episode_group/parts600", map[string]any{"id": "parts600", "name": "Parts", "groups": []any{
		part(1, [2]int{1, 1}, [2]int{1, 2}), part(2, [2]int{2, 1}, [2]int{2, 2}), part(3, [2]int{2, 3}, [2]int{2, 4}),
	}})
	// Camp Kettle: nothing on TMDB says where its season 22 is.
	show(700, "Camp Kettle", "2001-01-01", []string{"A", "B"}, []string{"C", "D"}, []string{"E", "F"})
	f.Handle("/search/tv", func(r *http.Request) any {
		q := strings.ToLower(r.URL.Query().Get("query"))
		if strings.Contains(q, "harbor lights") {
			return map[string]any{"results": []any{
				map[string]any{"id": 500, "name": "Harbor Lights", "first_air_date": "2022-01-01"},
				map[string]any{"id": 499, "name": "Harbor Lights: Origins", "first_air_date": "2010-01-01"},
				map[string]any{"id": 502, "name": "Harbor Lights: Short", "first_air_date": "2027-01-01"},
				map[string]any{"id": 501, "name": "Harbor Lights: Low Tide", "first_air_date": "2026-01-01"},
			}}
		}
		return map[string]any{"results": []any{}}
	})
	return f
}

func TestSeasonRulePlace(t *testing.T) {
	counts := map[int]int{1: 3, 2: 2, 3: 3}
	for _, c := range []struct {
		rule                  SeasonRule
		episode, season, want int
		ok                    bool
	}{
		{SeasonRule{FromSeason: 4, FromEpisode: 1, ToSeason: 1, ToEpisode: 1}, 2, 1, 2, true},
		{SeasonRule{FromSeason: 2, FromEpisode: 3, ToSeason: 2, ToEpisode: 3}, 3, 3, 1, true},  // runs on
		{SeasonRule{FromSeason: 9, FromEpisode: 1, ToSeason: 1, ToEpisode: 3}, 4, 3, 1, true},  // across two seasons
		{SeasonRule{FromSeason: 9, FromEpisode: 5, ToSeason: 2, ToEpisode: 1}, 6, 2, 2, true},  // from an episode other than 1
		{SeasonRule{FromSeason: 9, FromEpisode: 5, ToSeason: 2, ToEpisode: 1}, 4, 0, 0, false}, // before the rule starts
		{SeasonRule{FromSeason: 9, FromEpisode: 1, ToSeason: 3, ToEpisode: 3}, 2, 0, 0, false}, // past the last season
		{SeasonRule{FromSeason: 9, FromEpisode: 1, ToSeason: 7, ToEpisode: 1}, 1, 0, 0, false}, // no such season
	} {
		s, n, ok := c.rule.place(counts, c.episode)
		if s != c.season || n != c.want || ok != c.ok {
			t.Errorf("%+v episode %d = S%dE%d %v, want S%dE%d %v", c.rule, c.episode, s, n, ok, c.season, c.want, c.ok)
		}
	}
	// A two-episode file whose halves would land in two seasons has no place.
	r := SeasonRule{FromSeason: 9, FromEpisode: 1, ToSeason: 1, ToEpisode: 3}
	if _, _, ok := r.placeAll(counts, []int{1, 2}); ok {
		t.Error("a file across two seasons was placed")
	}
	if s, n, ok := r.placeAll(counts, []int{2, 3}); !ok || s != 2 || len(n) != 2 || n[0] != 1 || n[1] != 2 {
		t.Errorf("two-episode file = S%d %v %v", s, n, ok)
	}
}

func episodeFile(e env, folder string, season, episode int, title string) db.MediaFile {
	parsed := fmt.Sprintf(`{"kind":"episode","title":%q,"season":%d,"episodes":[%d],"episodeTitle":%q}`, folder, season, episode, title)
	return e.file(e.tv, fmt.Sprintf("/t/%s/S%02dE%02d.mkv", folder, season, episode), parsed)
}

func TestSuggestSeasons(t *testing.T) {
	e := setupWith(t, seasonTMDB(t).Client())
	one := func(t *testing.T, tmdbID int, files ...db.MediaFile) SeasonLineUp {
		t.Helper()
		got, err := e.m.SuggestSeasons(e.ctx, e.tv, files, tmdbID)
		if err != nil {
			t.Fatal(err)
		}
		if len(got) != 1 {
			t.Fatalf("seasons = %+v", got)
		}
		return got[0]
	}
	want := func(t *testing.T, lu SeasonLineUp, source string, rule SeasonRule) {
		t.Helper()
		if lu.Suggestion == nil {
			t.Fatalf("no suggestion for season %d", lu.Season)
		}
		if s := lu.Suggestion; s.Source != source || s.Rule != rule || s.Found != len(lu.FileIDs) || s.Detail == "" {
			t.Errorf("suggestion = %+v", *s)
		}
	}

	t.Run("a season that lines up is left out", func(t *testing.T) {
		got, err := e.m.SuggestSeasons(e.ctx, e.tv, []db.MediaFile{episodeFile(e, "Lined", 1, 2, "")}, 500)
		if err != nil || len(got) != 0 {
			t.Errorf("got %+v, %v", got, err)
		}
	})
	t.Run("TMDB's parts order", func(t *testing.T) {
		lu := one(t, 600, episodeFile(e, "Ferry", 3, 1, ""), episodeFile(e, "Ferry", 3, 2, ""))
		want(t, lu, SourceOrder, SeasonRule{FromSeason: 3, FromEpisode: 1, TMDBID: 600, ToSeason: 2, ToEpisode: 3})
		if lu.Suggestion.LastSeason != 2 || lu.Suggestion.LastEpisode != 4 {
			t.Errorf("last = %+v", lu.Suggestion)
		}
	})
	t.Run("a part one episode short suggests nothing", func(t *testing.T) {
		lu := one(t, 600, episodeFile(e, "Ferry2", 3, 1, ""), episodeFile(e, "Ferry2", 3, 3, ""))
		if lu.Suggestion != nil {
			t.Errorf("suggestion = %+v", *lu.Suggestion)
		}
	})
	t.Run("numbers run on into the next season", func(t *testing.T) {
		lu := one(t, 500, episodeFile(e, "RunOn", 2, 1, ""), episodeFile(e, "RunOn", 2, 3, ""), episodeFile(e, "RunOn", 2, 4, ""))
		if lu.FromEpisode != 3 || len(lu.FileIDs) != 2 || lu.OnTMDB != 2 {
			t.Fatalf("line-up = %+v", lu)
		}
		want(t, lu, SourceRunOn, SeasonRule{FromSeason: 2, FromEpisode: 3, TMDBID: 500, ToSeason: 3, ToEpisode: 1})
	})
	t.Run("episode names find the season", func(t *testing.T) {
		lu := one(t, 500, episodeFile(e, "Names", 7, 1, "Slack Water"), episodeFile(e, "Names", 7, 2, "Dead Reckoning 1080p"), episodeFile(e, "Names", 7, 3, ""))
		want(t, lu, SourceNames, SeasonRule{FromSeason: 7, FromEpisode: 1, TMDBID: 500, ToSeason: 3, ToEpisode: 1})
	})
	t.Run("names out of order suggest nothing from names", func(t *testing.T) {
		lu := one(t, 700, episodeFile(e, "Kettle", 9, 1, "D"), episodeFile(e, "Kettle", 9, 2, "C"))
		if lu.Suggestion != nil {
			t.Errorf("suggestion = %+v", *lu.Suggestion)
		}
	})
	t.Run("a later show of its own, confirmed by names", func(t *testing.T) {
		lu := one(t, 500, episodeFile(e, "Own", 4, 1, "Ebb"), episodeFile(e, "Own", 4, 2, "Neap"), episodeFile(e, "Own", 4, 3, "Flood"))
		want(t, lu, SourceOwnShow, SeasonRule{FromSeason: 4, FromEpisode: 1, TMDBID: 501, ToSeason: 1, ToEpisode: 1})
		if !strings.Contains(lu.Suggestion.Detail, "All 3 episode names") || lu.Suggestion.Show.Title != "Harbor Lights: Low Tide" {
			t.Errorf("suggestion = %+v", *lu.Suggestion)
		}
	})
	t.Run("a later show without names is a guess; earlier and shorter shows are not", func(t *testing.T) {
		lu := one(t, 500, episodeFile(e, "Guess", 4, 1, ""), episodeFile(e, "Guess", 4, 2, ""))
		want(t, lu, SourceOwnShow, SeasonRule{FromSeason: 4, FromEpisode: 1, TMDBID: 501, ToSeason: 1, ToEpisode: 1})
		if !strings.Contains(lu.Suggestion.Detail, "Check the names") {
			t.Errorf("detail = %q", lu.Suggestion.Detail)
		}
	})
	t.Run("names that are another show's suggest nothing", func(t *testing.T) {
		lu := one(t, 500, episodeFile(e, "Wrong", 4, 1, "Something"), episodeFile(e, "Wrong", 4, 2, "Else"))
		if lu.Suggestion != nil {
			t.Errorf("suggestion = %+v", *lu.Suggestion)
		}
	})
	t.Run("follows a neighbour lined up by hand", func(t *testing.T) {
		s22 := episodeFile(e, "Camp Kettle", 22, 1, "")
		s23 := []db.MediaFile{episodeFile(e, "Camp Kettle", 23, 1, ""), episodeFile(e, "Camp Kettle", 23, 2, "")}
		if lu := one(t, 700, s23...); lu.Suggestion != nil {
			t.Fatalf("suggestion before any line-up = %+v", *lu.Suggestion)
		}
		rule := SeasonRule{FromSeason: 22, FromEpisode: 1, TMDBID: 700, ToSeason: 1, ToEpisode: 1}
		if _, err := e.m.ApplySeasons(e.ctx, e.tv, []SeasonApply{{Rule: rule, Files: []db.MediaFile{s22}}}, true); err != nil {
			t.Fatal(err)
		}
		want(t, one(t, 700, s23...), SourceNeighbour, SeasonRule{FromSeason: 23, FromEpisode: 1, TMDBID: 700, ToSeason: 2, ToEpisode: 1})
	})
}

func TestApplySeasonsAndRemember(t *testing.T) {
	e := setupWith(t, seasonTMDB(t).Client())
	a := episodeFile(e, "Harbor Lights", 4, 1, "")
	b := episodeFile(e, "Harbor Lights", 4, 2, "")
	past := episodeFile(e, "Harbor Lights", 4, 9, "") // the show of its own has three episodes
	other := episodeFile(e, "Harbor Lights", 5, 1, "")
	rule := SeasonRule{FromSeason: 4, FromEpisode: 1, TMDBID: 501, ToSeason: 1, ToEpisode: 1}

	pv, err := e.m.PreviewSeason(e.ctx, e.tv, []db.MediaFile{a, b, past, other}, rule)
	if err != nil {
		t.Fatal(err)
	}
	if pv.Found != 2 || pv.Show.Title != "Harbor Lights: Low Tide" || len(pv.Seasons) != 1 || len(pv.Episodes) != 3 ||
		len(pv.Files[1].To) != 1 || pv.Files[1].To[0].Title != "Neap" || pv.Files[2].Reason == "" || pv.Files[3].Reason == "" {
		t.Fatalf("preview = %+v", pv)
	}
	if row, _ := e.q.GetMediaFile(e.ctx, a.ID); row.MatchStatus != StatusPending {
		t.Errorf("preview changed a file: %s", row.MatchStatus)
	}

	// Without remember nothing is stored.
	res, err := e.m.ApplySeasons(e.ctx, e.tv, []SeasonApply{{Rule: rule, Files: []db.MediaFile{a}}}, false)
	if err != nil || res.Matched != 1 || res.Folder != "" {
		t.Fatalf("result = %+v, %v", res, err)
	}
	if rows, _ := e.q.ListSeasonMaps(e.ctx, e.tv.ID); len(rows) != 0 {
		t.Fatalf("stored without remember: %+v", rows)
	}

	res, err = e.m.ApplySeasons(e.ctx, e.tv, []SeasonApply{{Rule: rule, Files: []db.MediaFile{a, b, past}}}, true)
	if err != nil {
		t.Fatal(err)
	}
	if res.Matched != 2 || res.NeedEpisodes != 1 || res.Folder != "Harbor Lights" || res.ShowID == nil {
		t.Fatalf("result = %+v", res)
	}
	row, _ := e.q.GetMediaFile(e.ctx, b.ID)
	if row.MatchStatus != StatusMatched || row.MatchLocked != 1 {
		t.Errorf("E02 = %s locked=%d", row.MatchStatus, row.MatchLocked)
	}
	eps, _ := e.q.ListEpisodeIDsForFile(e.ctx, b.ID)
	if len(eps) != 1 {
		t.Fatalf("E02 episodes = %v", eps)
	}
	ep, _ := e.q.GetEpisode(e.ctx, eps[0])
	show, _ := e.q.GetShow(e.ctx, ep.ShowID)
	if ep.Number != 2 || ep.Title != "Neap" || show.TmdbID != 501 {
		t.Errorf("E02 landed on %q (%d) of show %d", ep.Title, ep.Number, show.TmdbID)
	}
	row, _ = e.q.GetMediaFile(e.ctx, past.ID)
	if row.MatchStatus != StatusUnmatched || !strings.Contains(deref(row.MatchError), "past the end") {
		t.Errorf("E09 = %s %q", row.MatchStatus, deref(row.MatchError))
	}

	// A file added later follows the line-up without a search; another
	// season of the folder does not.
	c := episodeFile(e, "Harbor Lights", 4, 3, "")
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	row, _ = e.q.GetMediaFile(e.ctx, c.ID)
	eps, _ = e.q.ListEpisodeIDsForFile(e.ctx, c.ID)
	if row.MatchStatus != StatusMatched || len(eps) != 1 {
		t.Fatalf("E03 = %s %v %q", row.MatchStatus, eps, deref(row.MatchError))
	}
	if ep, _ := e.q.GetEpisode(e.ctx, eps[0]); ep.Title != "Flood" {
		t.Errorf("E03 landed on %q", ep.Title)
	}
	if eps, _ := e.q.ListEpisodeIDsForFile(e.ctx, other.ID); len(eps) != 0 {
		t.Errorf("season 5 followed season 4's line-up: %v", eps)
	}

	// Forgetting the line-up leaves matched files matched.
	if n, err := e.q.DeleteSeasonMap(e.ctx, db.DeleteSeasonMapParams{LibraryID: e.tv.ID, Folder: "Harbor Lights", FromSeason: 4, FromEpisode: 1}); err != nil || n != 1 {
		t.Fatalf("delete = %d, %v", n, err)
	}
	if row, _ := e.q.GetMediaFile(e.ctx, b.ID); row.MatchStatus != StatusMatched {
		t.Errorf("E02 after forgetting = %s", row.MatchStatus)
	}
}
