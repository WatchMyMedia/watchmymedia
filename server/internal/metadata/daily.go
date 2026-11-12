package metadata

import (
	"context"
	"errors"
	"math"
	"regexp"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/parser"
)

// linkGuess links a file to episodes of a TMDB show by what its name says:
// the air date for files named by date (daily shows), the season that aired
// in that year for year seasons (S2024E05, TheTVDB's numbering for daily
// shows), else the season and episode numbers.
func (m *Matcher) linkGuess(ctx context.Context, lib db.Library, tmdbID int, g parser.Guess) ([]int64, error) {
	if g.AirDate != "" {
		return m.linkByDate(ctx, lib, tmdbID, g)
	}
	if g.Season == nil || len(g.Episodes) == 0 {
		return nil, noMatch("could not read a season and episode number from the file name")
	}
	season := *g.Season
	if season >= 1900 {
		s, err := m.seasonForYear(ctx, lib, tmdbID, season)
		if err != nil {
			return nil, err
		}
		season = s
	}
	return m.linkEpisodes(ctx, lib, tmdbID, season, g.Episodes)
}

// ensureShow returns the local row for a TMDB show, storing it first if needed.
func (m *Matcher) ensureShow(ctx context.Context, lib db.Library, tmdbID int) (db.Show, error) {
	defer m.shows.lock(showKey{lib.ID, tmdbID})()
	show, err := m.q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: lib.ID, TmdbID: int64(tmdbID)})
	if err == nil {
		return show, nil
	}
	return m.storeShow(ctx, lib, tmdbID)
}

// seasonForYear finds the TMDB season that started airing in a year.
func (m *Matcher) seasonForYear(ctx context.Context, lib db.Library, tmdbID, year int) (int, error) {
	show, err := m.ensureShow(ctx, lib, tmdbID)
	if err != nil {
		return 0, err
	}
	prefix := strconv.Itoa(year)
	seasons, err := m.q.ListSeasons(ctx, show.ID)
	if err != nil {
		return 0, err
	}
	for _, s := range seasons {
		if s.Number > 0 && s.AirDate != nil && strings.HasPrefix(*s.AirDate, prefix) {
			return int(s.Number), nil
		}
	}
	// A season that started after the show was stored: ask TMDB again.
	tv, err := m.getTV(ctx, tmdbID)
	if err != nil {
		return 0, err
	}
	for _, s := range tv.Seasons {
		if s.SeasonNumber > 0 && strings.HasPrefix(s.AirDate, prefix) {
			return s.SeasonNumber, nil
		}
	}
	return 0, noMatch("%q has no season that aired in %d on TMDB", show.Title, year)
}

// PickOne ends the match error of a dated file that fits more than one
// episode, so the unmatched screen can say a person needs to choose.
const PickOne = "pick the right one"

// futureSlack is how far past today a file's date may be. A recording is
// never of an episode that has not aired, but time zones and early
// releases put some a little ahead; Sonarr allows two weeks too.
const futureSlack = 14 * 24 * time.Hour

// linkByDate finds the episode that aired on the file's date, or on either
// reading of a date that could be day first or month first. When several
// aired that day, the episode title in the name picks one, as Kodi does,
// then "Part 2" picks the second, as Sonarr does; otherwise a person picks.
func (m *Matcher) linkByDate(ctx context.Context, lib db.Library, tmdbID int, g parser.Guess) ([]int64, error) {
	show, err := m.ensureShow(ctx, lib, tmdbID)
	if err != nil {
		return nil, err
	}
	latest := time.Now().Add(futureSlack).Format("2006-01-02")
	var dates []string
	for _, d := range []string{g.AirDate, g.AirDateAlt} {
		if d != "" && d <= latest {
			dates = append(dates, d)
		}
	}
	if len(dates) == 0 {
		return nil, noMatch("%s is in the future, so no episode of %q has aired on it", g.AirDate, show.Title)
	}
	// Look in what is stored first, and ask TMDB again only when no
	// reading of the date finds anything.
	var eps []db.Episode
	var aired []string
	for _, refresh := range []bool{false, true} {
		for _, d := range dates {
			on, err := m.episodesOn(ctx, show, tmdbID, d, refresh)
			if err != nil {
				return nil, err
			}
			if len(on) > 0 {
				eps = append(eps, on...)
				aired = append(aired, d)
			}
		}
		if len(eps) > 0 {
			break
		}
	}
	if len(eps) > 1 {
		if eps, err = m.withoutSpecials(ctx, show, eps); err != nil {
			return nil, err
		}
	}
	if len(eps) > 1 {
		if ep, ok := pickByTitle(g.EpisodeTitle, eps); ok {
			return []int64{ep.ID}, nil
		}
		if n := partNumber(g.EpisodeTitle); len(aired) == 1 && n >= 1 && n <= len(eps) {
			return []int64{eps[n-1].ID}, nil
		}
	}
	switch {
	case len(eps) == 1:
		return []int64{eps[0].ID}, nil
	case len(eps) == 0 && len(dates) == 2:
		return nil, noMatch("no episode of %q aired on %s or %s on TMDB", show.Title, dates[0], dates[1])
	case len(eps) == 0:
		return nil, noMatch("no episode of %q aired on %s on TMDB", show.Title, dates[0])
	case len(aired) == 2:
		return nil, noMatch("the date in the name could be %s or %s, and %q aired on both; "+PickOne, aired[0], aired[1], show.Title)
	}
	return nil, noMatch("%d episodes of %q aired on %s; "+PickOne, len(eps), show.Title, aired[0])
}

// episodesOn lists the show's episodes that aired on a date (YYYY-MM-DD).
// With refresh, when none are stored, it first stores again the season
// that was running then, for episodes that aired after the show was stored.
func (m *Matcher) episodesOn(ctx context.Context, show db.Show, tmdbID int, date string, refresh bool) ([]db.Episode, error) {
	eps, err := m.q.ListShowEpisodesByAirDate(ctx, db.ListShowEpisodesByAirDateParams{ShowID: show.ID, AirDate: &date})
	if err != nil || len(eps) > 0 || !refresh {
		return eps, err
	}
	tv, err := m.getTV(ctx, tmdbID)
	if err != nil {
		return nil, err
	}
	season := -1
	for _, s := range tv.Seasons {
		if s.SeasonNumber > 0 && s.AirDate != "" && s.AirDate <= date && s.SeasonNumber > season {
			season = s.SeasonNumber
		}
	}
	// A season's worth of dated files TMDB doesn't list would otherwise
	// fetch the season again for each file, on every run.
	if season < 0 || !m.refetchSeason(show.TmdbID, season) {
		return nil, nil
	}
	unlock := m.shows.lock(showKey{show.LibraryID, tmdbID})
	_, err = m.storeSeason(ctx, show, season)
	unlock()
	var nm *noMatchError
	if err != nil && !errors.As(err, &nm) {
		return nil, err
	}
	return m.q.ListShowEpisodesByAirDate(ctx, db.ListShowEpisodesByAirDateParams{ShowID: show.ID, AirDate: &date})
}

// withoutSpecials drops season 0 episodes when there are others, as a
// special that aired the same day is rarely what a dated file is.
func (m *Matcher) withoutSpecials(ctx context.Context, show db.Show, eps []db.Episode) ([]db.Episode, error) {
	seasons, err := m.q.ListSeasons(ctx, show.ID)
	if err != nil {
		return nil, err
	}
	special := map[int64]bool{}
	for _, s := range seasons {
		special[s.ID] = s.Number == 0
	}
	var out []db.Episode
	for _, e := range eps {
		if !special[e.SeasonID] {
			out = append(out, e)
		}
	}
	if len(out) == 0 {
		return eps, nil
	}
	return out, nil
}

// pickByTitle picks the episode the name's episode title clearly fits:
// all its words are in the episode's title ("Morgana Robinson" in
// "October 27, 2026 - Morgana Robinson"), or the whole title is close.
func pickByTitle(title string, eps []db.Episode) (db.Episode, bool) {
	words := strings.Fields(normalize(title))
	if len(words) == 0 {
		return db.Episode{}, false
	}
	best, second, at := 0.0, 0.0, -1
	for i, e := range eps {
		s := math.Max(titleSimilarity(title, e.Title), containment(words, strings.Fields(normalize(e.Title))))
		if s > best {
			best, second, at = s, best, i
		} else if s > second {
			second = s
		}
	}
	if at < 0 || best < 0.8 || best == second {
		return db.Episode{}, false
	}
	return eps[at], true
}

// containment is the share of words that are also in other.
func containment(words, other []string) float64 {
	set := map[string]bool{}
	for _, w := range other {
		set[w] = true
	}
	n := 0
	for _, w := range words {
		if set[w] {
			n++
		}
	}
	return float64(n) / float64(len(words))
}

var partRe = regexp.MustCompile(`(?i)\b(?:part|pt)[ ._-]*(\d{1,2})\b`)

// partNumber reads "Part 2" from an episode title, or 0.
func partNumber(title string) int {
	if m := partRe.FindStringSubmatch(title); m != nil {
		n, _ := strconv.Atoi(m[1])
		return n
	}
	return 0
}
