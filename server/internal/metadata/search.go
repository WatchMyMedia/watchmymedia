package metadata

import (
	"context"
	"errors"
	"regexp"
	"strconv"
	"strings"
	"time"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/memo"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/tmdb"
)

// searchTTL is how long a TMDB search's answer is reused: every episode in
// a show folder asks the same.
const searchTTL = 10 * time.Minute

// A query is one search to make: a name, and the year to narrow it to.
type query struct {
	text string
	year *int
}

var (
	// A country code ending a name without brackets: "Crashing US".
	bareCountryRe = regexp.MustCompile(`^(.+?)\s+(US|UK|AU|NZ|CA|IE)$`)
	// A release group stuck to the front of a lower-case movie name:
	// "rusted-doctor.jekyll.2023".
	groupPrefixRe = regexp.MustCompile(`^[a-z0-9]{2,12}-([a-z0-9].*)$`)
	// TMDB's search finds "Dylan & Zoey" for "Dylan & Zoey" but not for
	// "Dylan and Zoey".
	andRe = regexp.MustCompile(`(?i)\s+and\s+`)
)

// names returns the readings of a file's name that TMDB's results are
// scored against, the searches to make in rounds (matching stops after the
// first round that finds a match), and the country the name gives.
//
// The first round searches the folder's name and, when it differs, the
// file's own; later rounds try them without the year, then spellings TMDB
// may know better: "&" for "and", a country code without brackets, a movie
// name without a release group in front.
func names(kind string, g parser.Guess) (readings []reading, rounds [][]query, country string) {
	title, fileTitle := g.Title, g.FileTitle
	if kind == library.TypeTV {
		var fileCountry string
		title, country = splitCountry(title)
		fileTitle, fileCountry = splitCountry(fileTitle)
		if country == "" {
			country = fileCountry
		}
	}
	// A year in the file's name stands in for the folder's when it has
	// none: "Queenie/queenie.2024.s01e03" is the 2024 Queenie.
	year := g.Year
	if year == nil {
		year = g.FileYear
	}
	var primary, variants []query
	add := func(qs *[]query, text string, year *int, read bool) {
		if strings.TrimSpace(text) == "" {
			return
		}
		*qs = append(*qs, query{text, year})
		if read {
			readings = append(readings, reading{text, year})
		}
	}
	add(&primary, title, year, true)
	if g.Year != nil && g.FileYear != nil && fileTitle == "" {
		readings = append(readings, reading{title, g.FileYear}) // "Rivals (2023)/Rivals.2024.S01E01"
	}
	fileYear := g.FileYear
	if fileYear == nil {
		fileYear = g.Year
	}
	if fileTitle != title {
		add(&primary, fileTitle, fileYear, true)
	}

	for _, q := range primary {
		if andRe.MatchString(q.text) {
			add(&variants, andRe.ReplaceAllString(q.text, " & "), q.year, false)
		}
	}
	if m := bareCountryRe.FindStringSubmatch(title); kind == library.TypeTV && country == "" && m != nil {
		country = m[2]
		add(&variants, m[1], year, true)
	}
	if m := groupPrefixRe.FindStringSubmatch(title); kind == library.TypeMovie && m != nil && title == strings.ToLower(title) {
		add(&variants, m[1], year, true)
	}

	rounds = append(rounds, primary)
	for _, q := range primary {
		if q.year != nil {
			rounds = append(rounds, []query{{q.text, nil}})
		}
	}
	for _, q := range variants {
		rounds = append(rounds, []query{q})
		if q.year != nil {
			rounds = append(rounds, []query{{q.text, nil}})
		}
	}
	return readings, rounds, country
}

// find searches TMDB for a file's movie or show under each of its names
// and returns the best confident match, with every candidate seen, ranked.
// For shows, fits (when set) checks a confident candidate against the file:
// top says whether it is the best one. When none fits, the best confident
// one is the match, as before there was a check.
func (m *Matcher) find(ctx context.Context, kind string, g parser.Guess, fits func(c Candidate, top bool) (bool, error)) (*Candidate, []Candidate, error) {
	readings, rounds, country := names(kind, g)
	var in []scoreInput
	seen := map[int]bool{}
	// Movies TMDB returned for a search narrowed to a year. Its year filter
	// counts every release date, so it vouches for a year one off from the
	// name's; a movie found only without the year must have it exactly
	// (the 2022 "The House Next Door" is not the 2021 one).
	vouched := map[int]bool{}
	confident := func(c Candidate) bool {
		if kind == library.TypeMovie && c.yearKnown && c.yearSim < 1 && !vouched[c.TMDBID] {
			return false
		}
		return accept(c)
	}
	var ranked []Candidate
	for _, round := range rounds {
		added := false
		for _, q := range round {
			results, err := m.search(ctx, kind, q.text, q.year)
			if err != nil {
				return nil, ranked, err
			}
			for _, r := range results {
				if q.year != nil {
					vouched[r.TMDBID] = true
				}
				if !seen[r.TMDBID] {
					seen[r.TMDBID] = true
					in = append(in, r)
					added = true
				}
			}
		}
		if !added {
			continue
		}
		ranked = rank(readings, country, in)
		top := true
		for _, c := range ranked {
			if !confident(c) {
				continue
			}
			ok := true
			if fits != nil {
				var err error
				if ok, err = fits(c, top); err != nil {
					return nil, ranked, err
				}
			}
			if ok {
				return &c, ranked, nil
			}
			top = false
		}
	}
	for _, c := range ranked {
		if confident(c) {
			return &c, ranked, nil
		}
	}
	return nil, ranked, nil
}

// search returns TMDB's results for a movie or show name, reusing recent
// answers.
func (m *Matcher) search(ctx context.Context, kind, text string, year *int) ([]scoreInput, error) {
	// Names on disk may be stored decomposed ("e" and a combining accent),
	// for which TMDB finds nothing.
	text = norm.NFC.String(text)
	y := 0
	if year != nil {
		y = *year
	}
	key := kind + "\x00" + text + "\x00" + strconv.Itoa(y)
	return memo.Load(ctx, m.searches, &m.flight, key, func(ctx context.Context) ([]scoreInput, error) {
		var in []scoreInput
		switch kind {
		case library.TypeTV:
			results, err := m.tmdb.SearchTV(ctx, text, y)
			if err != nil {
				return nil, err
			}
			for _, r := range results {
				c := Candidate{TMDBID: r.ID, Title: r.Name, Year: tmdb.Year(r.FirstAirDate), Overview: r.Overview, PosterPath: r.PosterPath}
				if len(r.OriginCountry) > 0 {
					c.Country = r.OriginCountry[0]
				}
				in = append(in, scoreInput{Candidate: c, OriginalTitle: r.OriginalName, Popularity: r.Popularity, Countries: r.OriginCountry})
			}
		default:
			results, err := m.tmdb.SearchMovies(ctx, text, y)
			if err != nil {
				return nil, err
			}
			for _, r := range results {
				in = append(in, scoreInput{
					TMDBID: r.ID, Title: r.Title, Year: tmdb.Year(r.ReleaseDate), Overview: r.Overview, PosterPath: r.PosterPath,
					OriginalTitle: r.OriginalTitle,
					Popularity:    r.Popularity,
				})
			}
		}
		return in, nil
	})
}

// fitsShow reports whether a TMDB show has the season a file is numbered
// in and, with every, each of its episodes. A show without the season is
// likely another of the same name. A missing episode alone is not held
// against the best match, as TMDB is often behind on new episodes, but
// another show must have them all to be chosen over it.
func (m *Matcher) fitsShow(ctx context.Context, tmdbID int, g parser.Guess, every bool) (bool, error) {
	if g.AirDate != "" || g.Season == nil {
		return true, nil // dated files are found by air date, within the show's run
	}
	show, err := m.getTV(ctx, tmdbID)
	if errors.Is(err, tmdb.ErrNotFound) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	year := strconv.Itoa(*g.Season)
	for _, s := range show.Seasons {
		yearSeason := *g.Season >= 1900 && s.SeasonNumber > 0 && strings.HasPrefix(s.AirDate, year)
		if s.SeasonNumber != *g.Season && !yearSeason {
			continue
		}
		if every {
			for _, e := range g.Episodes {
				if e < 1 || e > s.EpisodeCount {
					return false, nil
				}
			}
		}
		return true, nil
	}
	return false, nil
}
