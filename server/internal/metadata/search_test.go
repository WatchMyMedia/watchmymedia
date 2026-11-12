package metadata

import (
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/tmdb/tmdbtest"
)

// newSearchTMDB is a fake TMDB for the shapes of names that used to go
// unmatched.
func newSearchTMDB(t *testing.T) *tmdbtest.Server {
	t.Helper()
	f := tmdbtest.New(t)
	show := func(id int, name, first string, pop float64, countries ...string) map[string]any {
		return map[string]any{"id": id, "name": name, "first_air_date": first, "popularity": pop, "origin_country": countries}
	}
	movie := func(id int, title, release string) map[string]any {
		return map[string]any{"id": id, "title": title, "release_date": release, "popularity": 10}
	}
	seasons := func(counts ...int) []any {
		var out []any
		for i, n := range counts {
			out = append(out, map[string]any{"season_number": i + 1, "name": fmt.Sprintf("Season %d", i+1), "episode_count": n})
		}
		return out
	}
	episodes := func(n int) []any {
		var out []any
		for i := 1; i <= n; i++ {
			out = append(out, map[string]any{"id": i, "episode_number": i, "name": fmt.Sprintf("Episode %d", i)})
		}
		return out
	}
	year := func(r *http.Request) string {
		return r.URL.Query().Get("first_air_date_year") + r.URL.Query().Get("year")
	}
	f.Handle("/search/tv", func(r *http.Request) any {
		results := []any{}
		switch q, y := r.URL.Query().Get("query"), year(r); {
		case q == "The Conners":
			results = []any{show(500, "The Conners", "2018-10-16", 50, "US")}
		case q == "Rivals" && y == "2023":
			results = []any{show(900, "Became My Archenemy's Secretary", "2023-03-01", 5, "CN")}
		case q == "Rivals" && y == "":
			results = []any{show(501, "Rivals", "2024-10-18", 40, "GB")}
		case q == "Crashing":
			results = []any{show(503, "Crashing", "2016-01-11", 30, "GB"), show(502, "Crashing", "2017-02-19", 30, "US")}
		case q == "Shared Name":
			results = []any{show(506, "Shared Name", "2010-01-01", 50), show(507, "Shared Name", "1990-01-01", 5)}
		case q == "American Sports Story" || q == "American Sports Story Aaron Hernandez":
			results = []any{show(508, "American Sports Story: Aaron Hernandez", "2024-09-17", 20, "US")}
		case q == "Stale Show":
			results = []any{show(510, "Stale Show", "2020-01-01", 5)}
		case q == "Shōgun": // composed; TMDB finds nothing for "Shōgun"
			results = []any{show(511, "Shōgun", "2024-02-27", 60, "US")}
		}
		return map[string]any{"results": results}
	})
	f.Handle("/search/movie", func(r *http.Request) any {
		results := []any{}
		switch q, y := r.URL.Query().Get("query"), year(r); q {
		case "Kevin Hart & Chris Rock Headliners Only":
			results = []any{movie(600, "Kevin Hart & Chris Rock: Headliners Only", "2023-06-14")}
		case "doctor jekyll":
			results = []any{movie(601, "Doctor Jekyll", "2023-10-27")}
		case "typist artist pirate king": // released 2023, shown at a festival in 2022
			results = []any{movie(602, "Typist Artist Pirate King", "2023-12-01")}
		case "Without Remorse":
			results = []any{movie(603, "Tom Clancy's Without Remorse", "2021-04-29")}
			if y == "" {
				results = append(results, movie(604, "Without Remorse", "")) // a stub with no date
			}
		case "The House Next Door":
			results = []any{movie(605, "The House Next Door: Meet the Blacks 2", "2021-10-29")}
			if y == "" {
				results = append(results, movie(606, "The House Next Door", "2022-05-01"))
			}
		}
		return map[string]any{"results": results}
	})
	for _, id := range []int{500, 501, 502, 503, 508, 511} {
		f.JSON(fmt.Sprintf("/tv/%d", id), map[string]any{"id": id, "name": "Show", "seasons": seasons(10, 10, 10)})
	}
	// The popular one has a single season.
	f.JSON("/tv/506", map[string]any{"id": 506, "name": "Shared Name", "seasons": seasons(10)})
	f.JSON("/tv/506/season/1", map[string]any{"season_number": 1, "name": "Season 1", "episodes": episodes(10)})
	f.JSON("/tv/507", map[string]any{"id": 507, "name": "Shared Name", "seasons": seasons(20, 10, 10)})
	f.JSON("/tv/510", map[string]any{"id": 510, "name": "Stale Show", "seasons": seasons(2)})
	f.Handle("/tv/510/season/1", func(*http.Request) any {
		n := 1 // TMDB lists the second episode only later
		if f.Count("/tv/510/season/1") > 1 {
			n = 2
		}
		return map[string]any{"season_number": 1, "name": "Season 1", "episodes": episodes(n)}
	})
	for _, id := range []int{600, 601, 602} {
		f.JSON(fmt.Sprintf("/movie/%d", id), map[string]any{"id": id, "title": "Movie", "genres": []any{}})
	}
	f.Fallback(func(r *http.Request) any {
		if strings.Contains(r.URL.Path, "/season/") {
			return map[string]any{"season_number": 1, "name": "Season", "episodes": episodes(20)}
		}
		return nil
	})
	return f
}

// addFile stores a file under a library with what the parser reads from
// its path, joined with the system's separator as the scanner joins it.
func (e env) addFile(lib db.Library, rel string) db.MediaFile {
	var g parser.Guess
	if lib.Type == "tv" {
		g = parser.ParseEpisode(rel)
	} else {
		g = parser.ParseMovie(rel)
	}
	b, _ := json.Marshal(g)
	return e.file(lib, filepath.Join(lib.Path, filepath.FromSlash(rel)), string(b))
}

// showOf returns the TMDB ID of the show a file is linked to, or 0.
func (e env) showOf(t *testing.T, f db.MediaFile) int64 {
	t.Helper()
	row, _ := e.q.GetMediaFile(e.ctx, f.ID)
	if row.MatchStatus != StatusMatched {
		return 0
	}
	ids, _ := e.q.ListEpisodeIDsForFile(e.ctx, f.ID)
	if len(ids) == 0 {
		return 0
	}
	ep, _ := e.q.GetEpisode(e.ctx, ids[0])
	show, _ := e.q.GetShow(e.ctx, ep.ShowID)
	return show.TmdbID
}

func TestMatchByOtherNames(t *testing.T) {
	fake := newSearchTMDB(t)
	e := setupWith(t, fake.Client())
	files := map[string]db.MediaFile{
		// The folder is misspelt; the files say what the show is called.
		"conners": e.addFile(e.tv, "The Connors/The.Conners.S01E02.720p.HDTV.x264-AVS.mkv"),
		// The folder's year finds only other shows; without it, the show.
		"rivals": e.addFile(e.tv, "Rivals (2023)/Rivals.2024.S01E01.1080p.WEB.H264.mkv"),
		// A country code without brackets.
		"crashing": e.addFile(e.tv, "Crashing US/Crashing.S02E02.720p.HDTV.x264-BATV.mkv"),
		// Decomposed accents, as some file systems store names.
		"shogun": e.addFile(e.tv, "Shōgun (2024)/Shogun.2024.S01E03.1080p.WEB.mkv"),
	}
	for i := 3; i <= 4; i++ {
		e.addFile(e.tv, fmt.Sprintf("The Connors/The.Conners.S01E0%d.720p.HDTV.x264-AVS.mkv", i))
	}
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	want := map[string]int64{"conners": 500, "rivals": 501, "crashing": 502, "shogun": 511}
	for name, f := range files {
		if got := e.showOf(t, f); got != want[name] {
			row, _ := e.q.GetMediaFile(e.ctx, f.ID)
			t.Errorf("%s: show %d, want %d (%s %q)", name, got, want[name], row.MatchStatus, deref(row.MatchError))
		}
	}
	// Three files of one folder search TMDB once for each name.
	if n := fake.Count("/search/tv?The Connors&"); n != 1 {
		t.Errorf("searched the folder's name %d times, want 1", n)
	}
	if n := fake.Count("/search/tv?The Conners&"); n != 1 {
		t.Errorf("searched the files' name %d times, want 1", n)
	}
	if n := fake.Count("/search/tv?Shōgun&2024"); n != 0 {
		t.Errorf("searched the decomposed name %d times", n)
	}

	mv := map[string]db.MediaFile{
		"&":      e.addFile(e.mov, "Kevin.Hart.and.Chris.Rock.Headliners.Only.2023.1080p.WEB.h264-EDITH.mkv"),
		"prefix": e.addFile(e.mov, "rusted-doctor.jekyll.2023.1080p.bluray.x264.mkv"),
		// A year one off, which TMDB's year filter returned.
		"vouched": e.addFile(e.mov, "rusted-typist.artist.pirate.king.2022.1080p.bluray.x264.mkv"),
	}
	// Found only by searching without the year: a stub with no date, and a
	// same-title movie a year later. Neither is the file's.
	stub := e.addFile(e.mov, "Without.Remorse.2021.1080p.AMZN.WEB-DL.mkv")
	later := e.addFile(e.mov, "The.House.Next.Door.2021.1080p.AMZN.WEBRip.mkv")
	if _, err := e.m.MatchLibrary(e.ctx, e.mov, false); err != nil {
		t.Fatal(err)
	}
	for name, f := range mv {
		if row, _ := e.q.GetMediaFile(e.ctx, f.ID); row.MatchStatus != StatusMatched {
			t.Errorf("movie %s: %s %q", name, row.MatchStatus, deref(row.MatchError))
		}
	}
	for _, f := range []db.MediaFile{stub, later} {
		if row, _ := e.q.GetMediaFile(e.ctx, f.ID); row.MatchStatus != StatusUnmatched {
			t.Errorf("%s matched movie %v", f.Path, row.MovieID)
		}
	}
}

func TestSameNameShowWithTheSeason(t *testing.T) {
	e := setupWith(t, newSearchTMDB(t).Client())
	// The more popular "Shared Name" has no season 3; the other has it.
	s3 := e.addFile(e.tv, "Shared Name/Season 03/Shared Name - S03E02.mkv")
	// Season 1 of the popular one lacks episode 12 but has the season: it
	// stays the match, though the other has twenty episodes in season 1.
	s1 := e.addFile(e.tv, "Shared Name 2/Season 01/Shared Name - S01E12.mkv")
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	if got := e.showOf(t, s3); got != 507 {
		t.Errorf("S03E02 matched show %d, want 507", got)
	}
	row, _ := e.q.GetMediaFile(e.ctx, s1.ID)
	if row.MatchStatus != StatusUnmatched || !strings.Contains(deref(row.MatchError), "no episode [12]") {
		t.Errorf("S01E12 = %s %q, want unmatched under the popular show", row.MatchStatus, deref(row.MatchError))
	}
}

func TestFolderShowAndSeasonRefetch(t *testing.T) {
	fake := newSearchTMDB(t)
	e := setupWith(t, fake.Client())
	// The full name finds the show, and siblings whose short name finds
	// nothing for sure follow it, in the same run whichever comes first.
	full := e.addFile(e.tv, "ASS/American.Sports.Story.Aaron.Hernandez.S01E01.1080p.WEB.mkv")
	short := e.addFile(e.tv, "ASS/American.Sports.Story.S01E02.1080p.WEB.mkv")
	shorter := e.addFile(e.tv, "ASS/American Sports Story - S01E03.mkv")
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Matched != 3 || sum.Unmatched != 0 {
		t.Errorf("summary = %+v", sum)
	}
	for _, f := range []db.MediaFile{full, short, shorter} {
		if got := e.showOf(t, f); got != 508 {
			row, _ := e.q.GetMediaFile(e.ctx, f.ID)
			t.Errorf("%s matched %d, want 508 (%q)", f.Path, got, deref(row.MatchError))
		}
	}

	// TMDB lists an episode after its season was stored: the season is
	// fetched again, once.
	e.addFile(e.tv, "Stale Show/Season 01/Stale Show - S01E01.mkv")
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	second := e.addFile(e.tv, "Stale Show/Season 01/Stale Show - S01E02.mkv")
	third := e.addFile(e.tv, "Stale Show/Season 01/Stale Show - S01E03.mkv")
	if _, err := e.m.MatchLibrary(e.ctx, e.tv, false); err != nil {
		t.Fatal(err)
	}
	if got := e.showOf(t, second); got != 510 {
		row, _ := e.q.GetMediaFile(e.ctx, second.ID)
		t.Errorf("new episode = %d (%q), want 510", got, deref(row.MatchError))
	}
	if row, _ := e.q.GetMediaFile(e.ctx, third.ID); row.MatchStatus != StatusUnmatched {
		t.Errorf("episode TMDB lacks = %s", row.MatchStatus)
	}
	if n := fake.Count("/tv/510/season/1"); n != 2 {
		t.Errorf("season fetched %d times, want 2", n)
	}
}

// Workers matching one show at the same time share each TMDB lookup: the
// search, the show, its season and an ID in the folder's name are each asked
// for once, however many files ask.
func TestConcurrentLookupsAreShared(t *testing.T) {
	f := tmdbtest.New(t)
	slow := func(v any) tmdbtest.Handler {
		return func(*http.Request) any {
			time.Sleep(50 * time.Millisecond) // long enough for every worker to ask
			return v
		}
	}
	episodes := make([]any, 0, 8)
	for i := 1; i <= 8; i++ {
		episodes = append(episodes, map[string]any{"id": 9000 + i, "episode_number": i, "name": fmt.Sprintf("Episode %d", i)})
	}
	seasons := []any{map[string]any{"season_number": 1, "name": "Season 1", "episode_count": 8}}
	f.Handle("/search/tv", slow(map[string]any{"results": []any{
		map[string]any{"id": 520, "name": "Slow Show", "first_air_date": "2021-01-01", "popularity": 10},
	}}))
	f.Handle("/tv/520", slow(map[string]any{"id": 520, "name": "Slow Show", "seasons": seasons}))
	f.Handle("/tv/520/season/1", slow(map[string]any{"season_number": 1, "name": "Season 1", "episodes": episodes}))
	f.Handle("/find/77", slow(map[string]any{"movie_results": []any{}, "tv_results": []any{
		map[string]any{"id": 521, "name": "Tagged Show", "first_air_date": "2021-01-01"},
	}}))
	f.Handle("/tv/521", slow(map[string]any{"id": 521, "name": "Tagged Show", "seasons": seasons}))
	f.Handle("/tv/521/season/1", slow(map[string]any{"season_number": 1, "name": "Season 1", "episodes": episodes}))

	e := setupWith(t, f.Client())
	e.m = New(e.q, f.Client(), e.bus, e.m.log, Options{Workers: 8})
	for i := 1; i <= 8; i++ {
		e.addFile(e.tv, fmt.Sprintf("Slow Show/Season 01/Slow Show - S01E%02d.mkv", i))
		e.addFile(e.tv, fmt.Sprintf("Tagged Show {tvdb-77}/Season 01/Tagged Show - S01E%02d.mkv", i))
	}
	sum, err := e.m.MatchLibrary(e.ctx, e.tv, false)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Matched != 16 {
		t.Errorf("summary = %+v", sum)
	}
	for _, key := range []string{"/search/tv?Slow Show&", "/tv/520", "/tv/520/season/1", "/find/77", "/tv/521", "/tv/521/season/1"} {
		if n := f.Count(key); n != 1 {
			t.Errorf("%s asked %d times, want 1", key, n)
		}
	}
}
