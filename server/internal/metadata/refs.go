package metadata

import (
	"context"
	"errors"
	"regexp"
	"strconv"
	"strings"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/memo"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/tmdb"
)

// ref is a show or movie named by ID rather than title, typed or pasted
// into a search box.
type ref struct {
	source string // "tmdb", "tvdb" or "imdb"
	id     string
	kind   string // "tv" or "movie", when a TMDB link says
}

var (
	tmdbURLRe  = regexp.MustCompile(`(?i)themoviedb\.org/(tv|movie)/(\d+)`)
	tmdbIDRe   = regexp.MustCompile(`(?i)^tmdb[\s:#-]*(\d+)$`)
	tvdbURLRe  = regexp.MustCompile(`(?i)thetvdb\.com/\S*?(?:[?&]id=|/series/|/movies/)(\d+)\b`)
	tvdbSlugRe = regexp.MustCompile(`(?i)thetvdb\.com/(?:series|movies)/[a-z]`)
	tvdbIDRe   = regexp.MustCompile(`(?i)^tvdb[\s:#-]*(\d+)$`)
	imdbIDRe   = regexp.MustCompile(`(?i)\b(tt\d{6,10})\b`)
)

// parseRef recognises TMDB, TheTVDB and IMDb IDs and links: "tmdb:2224",
// "https://www.themoviedb.org/tv/2224-the-daily-show", "tvdb:71256",
// "tt0115147" or an IMDb title link. A bare number is a title ("1917").
func parseRef(q string) (ref, bool, error) {
	q = strings.TrimSpace(q)
	if m := tmdbURLRe.FindStringSubmatch(q); m != nil {
		return ref{source: "tmdb", id: m[2], kind: strings.ToLower(m[1])}, true, nil
	}
	if m := tmdbIDRe.FindStringSubmatch(q); m != nil {
		return ref{source: "tmdb", id: m[1]}, true, nil
	}
	if m := tvdbURLRe.FindStringSubmatch(q); m != nil {
		return ref{source: "tvdb", id: m[1]}, true, nil
	}
	if tvdbSlugRe.MatchString(q) {
		return ref{}, true, noMatch("TheTVDB links with a name in them cannot be looked up. Enter the numeric ID from that page instead, like tvdb:71256")
	}
	if m := tvdbIDRe.FindStringSubmatch(q); m != nil {
		return ref{source: "tvdb", id: m[1]}, true, nil
	}
	if m := imdbIDRe.FindStringSubmatch(q); m != nil {
		return ref{source: "imdb", id: strings.ToLower(m[1])}, true, nil
	}
	return ref{}, false, nil
}

// searchByRef returns the one TMDB item an ID names, or none.
func (m *Matcher) searchByRef(ctx context.Context, libraryType string, r ref) ([]Candidate, error) {
	want := "movie"
	if libraryType == library.TypeTV {
		want = "tv"
	}
	if r.kind != "" && r.kind != want {
		if want == "tv" {
			return nil, noMatch("that link is a TMDB movie; this is a TV library")
		}
		return nil, noMatch("that link is a TMDB TV show; this is a movie library")
	}
	notFound := func(err error) ([]Candidate, error) {
		if errors.Is(err, tmdb.ErrNotFound) {
			return []Candidate{}, nil
		}
		return nil, err
	}
	if r.source == "tmdb" {
		id, _ := strconv.Atoi(r.id)
		if want == "tv" {
			tv, err := m.tmdb.GetTV(ctx, id)
			if err != nil {
				return notFound(err)
			}
			return []Candidate{{TMDBID: tv.ID, Title: tv.Name, Year: tmdb.Year(tv.FirstAirDate), Overview: tv.Overview, PosterPath: tv.PosterPath, Score: 1}}, nil
		}
		mv, err := m.tmdb.GetMovie(ctx, id)
		if err != nil {
			return notFound(err)
		}
		return []Candidate{{TMDBID: mv.ID, Title: mv.Title, Year: tmdb.Year(mv.ReleaseDate), Overview: mv.Overview, PosterPath: mv.PosterPath, Score: 1}}, nil
	}
	found, err := m.tmdb.Find(ctx, r.id, r.source+"_id")
	if err != nil {
		return notFound(err)
	}
	out := []Candidate{}
	if want == "tv" {
		for _, t := range found.TVResults {
			c := Candidate{TMDBID: t.ID, Title: t.Name, Year: tmdb.Year(t.FirstAirDate), Overview: t.Overview, PosterPath: t.PosterPath, Score: 1}
			if len(t.OriginCountry) > 0 {
				c.Country = t.OriginCountry[0]
			}
			out = append(out, c)
		}
		if len(out) == 0 && len(found.MovieResults) > 0 {
			return nil, noMatch("that ID is a movie on TMDB; this is a TV library")
		}
		return out, nil
	}
	for _, mv := range found.MovieResults {
		out = append(out, Candidate{TMDBID: mv.ID, Title: mv.Title, Year: tmdb.Year(mv.ReleaseDate), Overview: mv.Overview, PosterPath: mv.PosterPath, Score: 1})
	}
	if len(out) == 0 && len(found.TVResults) > 0 {
		return nil, noMatch("that ID is a TV show on TMDB; this is a movie library")
	}
	return out, nil
}

// nameID resolves an ID written in the folder or file name ("{tmdb-2224}",
// "[imdbid-tt0115147]", "{tvdb-71256}") to the TMDB ID it names, so the
// file needs no title search. An ID TMDB does not know, or one that names
// a movie in a TV library or the reverse, is skipped and the title search
// runs as usual.
func (m *Matcher) nameID(ctx context.Context, lib db.Library, g parser.Guess) (*int, error) {
	var refs []ref
	if g.TMDBID > 0 {
		refs = append(refs, ref{source: "tmdb", id: strconv.Itoa(g.TMDBID)})
	}
	if g.IMDBID != "" {
		refs = append(refs, ref{source: "imdb", id: g.IMDBID})
	}
	if g.TVDBID > 0 {
		refs = append(refs, ref{source: "tvdb", id: strconv.Itoa(g.TVDBID)})
	}
	for _, r := range refs {
		id, err := memo.Load(ctx, m.ids, &m.flight, lib.Type+":"+r.source+":"+r.id, func(ctx context.Context) (int, error) {
			found, err := m.searchByRef(ctx, lib.Type, r)
			if err != nil && !IsNoMatch(err) {
				return 0, err
			}
			if len(found) == 0 {
				m.log.Warn("ID in a name is not on TMDB for this library type", "id", r.source+"-"+r.id, "library", lib.Name)
				return 0, nil
			}
			return found[0].TMDBID, nil
		})
		if err != nil {
			return nil, err
		}
		if id > 0 {
			return &id, nil
		}
	}
	return nil, nil
}

// IsNoMatch reports whether err is a "no match" explanation for a person
// (a bad ID, a missing episode) rather than a failure to reach TMDB.
func IsNoMatch(err error) bool {
	var nm *noMatchError
	return errors.As(err, &nm)
}
