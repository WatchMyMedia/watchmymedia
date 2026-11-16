package metadata

import (
	"encoding/json"
	"fmt"
	"maps"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/parser"
)

// arrivalsOf is every episode arrival as "show tmdb S01E02" -> quiet, and
// the arrival's id, so a test can tell an arrival kept from one made again.
func arrivalsOf(t *testing.T, e env) (map[string]int64, map[string]int64) {
	t.Helper()
	rows, err := e.conn.QueryContext(e.ctx, `SELECT id, quiet, tmdb_id, season, number FROM episode_arrivals ORDER BY id`)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	quiet, ids := map[string]int64{}, map[string]int64{}
	for rows.Next() {
		var id, q, tmdbID, season, number int64
		if err := rows.Scan(&id, &q, &tmdbID, &season, &number); err != nil {
			t.Fatal(err)
		}
		k := fmt.Sprintf("%d S%02dE%02d", tmdbID, season, number)
		quiet[k], ids[k] = q, id
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return quiet, ids
}

func wantArrivals(t *testing.T, e env, want map[string]int64) map[string]int64 {
	t.Helper()
	got, ids := arrivalsOf(t, e)
	if !maps.Equal(got, want) {
		t.Fatalf("arrivals (episode: quiet) = %v, want %v", got, want)
	}
	return ids
}

func matchTV(t *testing.T, e env) {
	t.Helper()
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
}

const pilot = `{"kind":"episode","title":"Breaking Bad","year":2008,"season":1,"episodes":[1]}`

// age makes a file count as added long ago: found by a scan then, or in a
// library's first scan by its date on disk.
func age(t *testing.T, e env, f db.MediaFile, by time.Duration) {
	t.Helper()
	if _, err := e.conn.ExecContext(e.ctx, `UPDATE media_files SET added_at = ? WHERE id = ?`, db.FormatTime(time.Now().Add(-by)), f.ID); err != nil {
		t.Fatal(err)
	}
}

// PLAN-NEW-EPISODES.md section 5.2's cases: a TMDB episode arrives once,
// in any library, the first time a file is linked to it, and the arrival
// is announced only when that file is new by the New badge's measure.
func TestEpisodeArrivals(t *testing.T) {
	t.Parallel()

	t.Run("a first file is an arrival", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
	})

	t.Run("an old first file is quiet", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		// A library added with files from years ago, or a file a week and
		// a day old matched only now.
		old := e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		age(t, e, old, 3*365*24*time.Hour)
		late := e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E02.mkv", `{"kind":"episode","title":"Breaking Bad","year":2008,"season":1,"episodes":[2]}`)
		age(t, e, late, NewWindow+24*time.Hour)
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 1, "1396 S01E02": 1})
	})

	t.Run("a better copy before the old file is deleted is silent", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		matchTV(t, e)
		before := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01 - 2160p.mkv", pilot)
		matchTV(t, e)
		if after := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0}); !maps.Equal(after, before) {
			t.Errorf("the arrival was made again: %v, was %v", after, before)
		}
	})

	t.Run("a better copy after the old file is deleted is silent", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		old := e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		matchTV(t, e)
		before := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		// Sonarr deletes the old file first; the next scan forgets it.
		if _, err := e.conn.ExecContext(e.ctx, `DELETE FROM media_files WHERE id = ?`, old.ID); err != nil {
			t.Fatal(err)
		}
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01 - Bluray-2160p.mkv", pilot)
		matchTV(t, e)
		if after := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0}); !maps.Equal(after, before) {
			t.Errorf("the arrival was made again: %v, was %v", after, before)
		}
	})

	t.Run("a second version is silent", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		// Both in one scan: one arrival, not two.
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01 - Theatrical.mkv", pilot)
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01 - Extended.mkv", pilot)
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
	})

	t.Run("a renamed file is silent", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		old := e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		matchTV(t, e)
		before := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		// The scanner sees the old path go missing and a new path appear.
		if _, err := e.conn.ExecContext(e.ctx, `UPDATE media_files SET status = 'missing' WHERE id = ?`, old.ID); err != nil {
			t.Fatal(err)
		}
		e.file(e.tv, "/t/Breaking Bad/S1/Breaking.Bad.S01E01.Pilot.mkv", pilot)
		matchTV(t, e)
		if after := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0}); !maps.Equal(after, before) {
			t.Errorf("the arrival was made again: %v, was %v", after, before)
		}
	})

	t.Run("a rematch to the same show is silent", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		f := e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		matchTV(t, e)
		before := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		if err := e.m.Apply(e.ctx, e.tv, f, 1396, new(1), []int{1}); err != nil {
			t.Fatal(err)
		}
		if after := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0}); !maps.Equal(after, before) {
			t.Errorf("the arrival was made again: %v, was %v", after, before)
		}
	})

	t.Run("a new file linked to the wrong episode and fixed is an arrival", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		matchTV(t, e)
		// This week's E02, named as E01: it lands on an episode that has
		// arrived, so nothing is announced yet.
		wrong := e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking.Bad.S01E01.REPACK.mkv", pilot)
		matchTV(t, e)
		before := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		if err := e.m.Apply(e.ctx, e.tv, wrong, 1396, new(1), []int{2}); err != nil {
			t.Fatal(err)
		}
		after := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0, "1396 S01E02": 0})
		if after["1396 S01E01"] != before["1396 S01E01"] {
			t.Errorf("E01's arrival was made again: %v, was %v", after, before)
		}
	})

	t.Run("a rematch to another show is quiet", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		f := e.file(e.tv, "/t/BB/Season 01/BB - S01E01.mkv", `{"kind":"episode","title":"Breaking Bad","season":1,"episodes":[1]}`)
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		// Months later the show is found to be another of the same name.
		age(t, e, f, 90*24*time.Hour)
		old, err := e.q.GetShowByTMDB(e.ctx, db.GetShowByTMDBParams{LibraryID: e.tv.ID, TmdbID: 1396})
		if err != nil {
			t.Fatal(err)
		}
		if _, err := e.m.RematchShow(e.ctx, e.tv, old, 1397, false); err != nil {
			t.Fatal(err)
		}
		// The old show's row went; its TMDB episode had still arrived.
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 0, "1397 S01E01": 1})
	})

	t.Run("season mapping of files that sat unmatched is quiet", func(t *testing.T) {
		t.Parallel()
		e := setupWith(t, seasonTMDB(t).Client())
		// Harbor Lights' fourth season is a show of its own on TMDB, so
		// these sat unmatched for years ("past the end").
		a := episodeFile(e, "Harbor Lights", 4, 1, "")
		b := episodeFile(e, "Harbor Lights", 4, 2, "")
		age(t, e, a, 2*365*24*time.Hour)
		age(t, e, b, 2*365*24*time.Hour)
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{})
		rule := SeasonRule{FromSeason: 4, FromEpisode: 1, TMDBID: 501, ToSeason: 1, ToEpisode: 1}
		if res, err := e.m.ApplySeasons(e.ctx, e.tv, []SeasonApply{{Rule: rule, Files: []db.MediaFile{a, b}}}, false); err != nil || res.Matched != 2 {
			t.Fatalf("apply = %+v, %v", res, err)
		}
		wantArrivals(t, e, map[string]int64{"501 S01E01": 1, "501 S01E02": 1})
		// The line-up kept: this week's next file follows it, announced.
		episodeFile(e, "Harbor Lights", 4, 3, "")
		if _, err := e.m.ApplySeasons(e.ctx, e.tv, []SeasonApply{{Rule: rule, Files: []db.MediaFile{a, b}}}, true); err != nil {
			t.Fatal(err)
		}
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"501 S01E01": 1, "501 S01E02": 1, "501 S01E03": 0})
	})

	t.Run("a moved file landing on another episode is quiet", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		f := e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		age(t, e, f, 200*24*time.Hour)
		// Renamed to the episode it really is: the scanner sees the old
		// path go missing and keeps the file's added date for the new one
		// (keepMovedDates).
		if _, err := e.conn.ExecContext(e.ctx, `UPDATE media_files SET status = 'missing' WHERE id = ?`, f.ID); err != nil {
			t.Fatal(err)
		}
		moved := e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E02.mkv", `{"kind":"episode","title":"Breaking Bad","year":2008,"season":1,"episodes":[2]}`)
		age(t, e, moved, 200*24*time.Hour)
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 0, "1396 S01E02": 1})
	})

	t.Run("a file for two episodes is two arrivals", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01E02.mkv", `{"kind":"episode","title":"Breaking Bad","year":2008,"season":1,"episodes":[1,2]}`)
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 0, "1396 S01E02": 0})
	})

	t.Run("a file matched by hand is an arrival", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		f := e.file(e.tv, "/t/Breaking Bad/weird name.mkv", `{"kind":"unknown","title":"weird name"}`)
		matchTV(t, e)
		if row, _ := e.q.GetMediaFile(e.ctx, f.ID); row.MatchStatus == StatusMatched {
			t.Fatal("the file matched by itself")
		}
		wantArrivals(t, e, map[string]int64{})
		if err := e.m.Apply(e.ctx, e.tv, f, 1396, new(1), []int{2}); err != nil {
			t.Fatal(err)
		}
		wantArrivals(t, e, map[string]int64{"1396 S01E02": 0})
		// One that sat unmatched for months is quiet.
		old := e.file(e.tv, "/t/Breaking Bad/another weird name.mkv", `{"kind":"unknown","title":"another weird name"}`)
		age(t, e, old, 120*24*time.Hour)
		if err := e.m.Apply(e.ctx, e.tv, old, 1396, new(2), []int{1}); err != nil {
			t.Fatal(err)
		}
		wantArrivals(t, e, map[string]int64{"1396 S01E02": 0, "1396 S02E01": 1})
	})

	t.Run("a copy in a second library is silent", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		e.file(e.tv, "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv", pilot)
		matchTV(t, e)
		before := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		// Sonarr's 4K instance adds its copy to a library of its own days
		// later.
		uhd, err := e.q.UpsertLibrary(e.ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV 4K", Path: "/t4k"})
		if err != nil {
			t.Fatal(err)
		}
		e.file(uhd, "/t4k/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01 - 2160p.mkv", pilot)
		if _, err := e.m.MatchLibrary(e.ctx, uhd, false); err != nil {
			t.Fatal(err)
		}
		if after := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0}); !maps.Equal(after, before) {
			t.Errorf("the arrival was made again: %v, was %v", after, before)
		}
		// A new episode reaching the 4K library first is an arrival there.
		e.file(uhd, "/t4k/Breaking Bad (2008)/Season 01/Breaking Bad - S01E02 - 2160p.mkv", `{"kind":"episode","title":"Breaking Bad","year":2008,"season":1,"episodes":[2]}`)
		if _, err := e.m.MatchLibrary(e.ctx, uhd, false); err != nil {
			t.Fatal(err)
		}
		wantArrivals(t, e, map[string]int64{"1396 S01E01": 0, "1396 S01E02": 0})
	})

	t.Run("a library removed and added again is silent", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		path := "/t/Breaking Bad (2008)/Season 01/Breaking Bad - S01E01.mkv"
		e.file(e.tv, path, pilot)
		matchTV(t, e)
		before := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0})
		// Moving storage: the library goes, with its files, shows and
		// episodes, and comes back under a new id.
		if _, err := e.conn.ExecContext(e.ctx, `DELETE FROM libraries WHERE id = ?`, e.tv.ID); err != nil {
			t.Fatal(err)
		}
		var gone *int64
		if err := e.conn.QueryRowContext(e.ctx, `SELECT episode_id FROM episode_arrivals`).Scan(&gone); err != nil || gone != nil {
			t.Fatalf("the arrival's episode after its library went: %v, %v", gone, err)
		}
		lib, err := e.q.UpsertLibrary(e.ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/tv-new"})
		if err != nil {
			t.Fatal(err)
		}
		e.tv = lib
		f := e.file(lib, path, pilot)
		matchTV(t, e)
		if after := wantArrivals(t, e, map[string]int64{"1396 S01E01": 0}); !maps.Equal(after, before) {
			t.Errorf("the arrival was made again: %v, was %v", after, before)
		}
		// The arrival points at the episode it is in the library as now.
		eps, _ := e.q.ListEpisodeIDsForFile(e.ctx, f.ID)
		var now *int64
		if err := e.conn.QueryRowContext(e.ctx, `SELECT episode_id FROM episode_arrivals`).Scan(&now); err != nil || len(eps) != 1 || now == nil || *now != eps[0] {
			t.Errorf("the arrival's episode = %v, want %v (%v)", now, eps, err)
		}
	})

	t.Run("a recording is an arrival once matched", func(t *testing.T) {
		t.Parallel()
		e := setup(t)
		// Named as the DVR names a recording of an episode
		// (internal/livetv/dvr recordingPath).
		rel := "Breaking Bad/Season 02/Breaking Bad - S02E01 - Seven Thirty-Seven.ts"
		parsed, err := json.Marshal(parser.ParseEpisode(rel))
		if err != nil {
			t.Fatal(err)
		}
		e.file(e.tv, "/t/"+rel, string(parsed))
		matchTV(t, e)
		wantArrivals(t, e, map[string]int64{"1396 S02E01": 0})
	})
}
