// Package lists makes the lists that rows on Home and the library screens
// come from (PLAN-ROWS.md 7.2): a Source per kind (TMDB, MDBList, a saved
// library filter, an import), the entries each one fetches, and the
// Service whose refresh job (Run) fetches them again in the background,
// like trailers.Refresher. Matching entries to the library is done when a
// screen is read, in the api package.
package lists

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"time"

	"watchmymedia/server/internal/tmdb"
)

// Source kinds, as MediaListSource.kind says them.
const (
	KindTMDB    = "tmdb"
	KindMDBList = "mdblist"
	KindLibrary = "library"
	KindImport  = "import"
)

// Kinds are every source, in the order the builder offers them.
var Kinds = []string{KindTMDB, KindMDBList, KindLibrary, KindImport}

// Limits (PLAN-ROWS.md Decision 13).
const (
	MaxEntries     = 250
	MaxImportBytes = 1 << 20
)

// TMDB charts, as MediaListSource.chart names them.
const (
	ChartTrendingDay  = "trending-day"
	ChartTrendingWeek = "trending-week"
	ChartPopular      = "popular"
	ChartTopRated     = "top-rated"
	ChartNowPlaying   = "now-playing"
	ChartUpcoming     = "upcoming"
	ChartOnTheAir     = "on-the-air"
	ChartAiringToday  = "airing-today"
	ChartDiscover     = "discover"
	ChartList         = "list"
	ChartCollection   = "collection"
)

// Charts are the values MediaListSource.chart takes, for getMediaListSources.
var Charts = []string{ChartTrendingDay, ChartTrendingWeek, ChartPopular, ChartTopRated, ChartNowPlaying, ChartUpcoming,
	ChartOnTheAir, ChartAiringToday, ChartDiscover, ChartList, ChartCollection}

// Params are a source's options, kept with the list as JSON. Each kind
// reads the fields it names (see MediaListSource in the api package).
type Params struct {
	// TMDB.
	Chart     string  `json:"chart,omitempty"`
	Ref       string  `json:"ref,omitempty"` // TMDB list or collection, or MDBList list
	Genres    []int64 `json:"genres,omitempty"`
	YearFrom  int     `json:"yearFrom,omitempty"`
	YearTo    int     `json:"yearTo,omitempty"`
	MinRating float64 `json:"minRating,omitempty"`
	MinVotes  int     `json:"minVotes,omitempty"`
	Language  string  `json:"language,omitempty"`
	SortBy    string  `json:"sortBy,omitempty"`

	// The library.
	LibraryID int64  `json:"libraryId,omitempty"`
	Sort      string `json:"sort,omitempty"`
	Filter    string `json:"filter,omitempty"`
	Genre     string `json:"genre,omitempty"`
	Decade    int    `json:"decade,omitempty"`

	// An import: what its text was read as. The text itself is read once,
	// when the list is made, and never kept.
	Format string `json:"format,omitempty"`
	Text   string `json:"-"`
}

// Encode is p as media_lists.params keeps it.
func (p Params) Encode() string {
	b, _ := json.Marshal(p)
	return string(b)
}

// DecodeParams reads media_lists.params; anything it can't read is no options.
func DecodeParams(s string) Params {
	var p Params
	_ = json.Unmarshal([]byte(s), &p)
	return p
}

// Entry is one title on a list.
type Entry struct {
	Rank      int
	MediaType string // movie or tv
	// TMDBID is 0 for an imported title TMDB could not find.
	TMDBID     int64
	IMDbID     string
	Title      string
	Year       int64
	PosterPath string
}

// Found is whether the entry has a TMDB ID, which matches it to the library.
func (e Entry) Found() bool { return e.TMDBID > 0 }

// Request is what a source is asked for: its options, the list's media type
// (movie, tv or all) and the most entries to keep.
type Request struct {
	Params    Params
	MediaType string
	Limit     int
}

// Result is what a source fetched.
type Result struct {
	Entries []Entry
	// URL is the list on the source's own site, when it has one.
	URL string
	// Format is what an import's text was read as: imdb, letterboxd, lines.
	Format string
}

// Source is somewhere lists come from.
type Source interface {
	// Kind is one of Kinds.
	Kind() string
	// Name is what a row says it is from ("From TMDB").
	Name(p Params) string
	// Validate checks a request without fetching it. Its errors are
	// *InvalidError, said to the person making the list.
	Validate(r Request) error
	// Fetch gets a request's entries, ranked from 1, at most r.Limit.
	Fetch(ctx context.Context, r Request) (Result, error)
}

// InvalidError is a list's options that can't work, worded for the person.
type InvalidError struct{ Reason string }

func (e *InvalidError) Error() string { return e.Reason }

func invalid(format string, args ...any) error {
	return &InvalidError{Reason: fmt.Sprintf(format, args...)}
}

// RefusedError is an outside service turning a list down: the key it
// refused, or a list it doesn't have. Trying again won't help.
type RefusedError struct{ Reason string }

func (e *RefusedError) Error() string { return e.Reason }

// Errors a fetch can end with.
var (
	// ErrFetching is a fetch asked for while the list's last one runs.
	ErrFetching = errors.New("lists: this list is being fetched")
	// ErrNotFetched is a fetch of a list that is never fetched: a library
	// list, read from the library as it is now.
	ErrNotFetched = errors.New("lists: library lists are read from the library, not fetched")
	// ErrDisabled is a fetch from an outside service while lists.enabled
	// is off.
	ErrDisabled = errors.New("lists from outside services are turned off on this server (lists.enabled)")
)

// Describe words a fetch's failure for the list's page ("MDBList refused
// the key"), without the request's address or key.
func Describe(err error) string {
	var inv *InvalidError
	var ref *RefusedError
	switch {
	case err == nil:
		return ""
	case errors.As(err, &inv):
		return inv.Reason
	case errors.As(err, &ref):
		return ref.Reason
	case errors.Is(err, ErrDisabled):
		return ErrDisabled.Error()
	case errors.Is(err, tmdb.ErrNoAPIKey):
		return "This server has no TMDB key"
	case errors.Is(err, tmdb.ErrUnauthorized):
		return "TMDB refused the server's key"
	case errors.Is(err, tmdb.ErrNotFound):
		return "TMDB has no such list"
	case errors.Is(err, context.DeadlineExceeded):
		return "The service took too long to answer"
	}
	return "Couldn't fetch the list; it is tried again later"
}

// Every is how often a list is fetched again (PLAN-ROWS.md 7.2): trending
// lists, charts, TMDB lists and MDBList lists daily, discover and
// collections weekly; an import's TMDB details every three months, inside
// TMDB's six months. Library lists are never fetched (0).
func Every(kind string, p Params) time.Duration {
	const day = 24 * time.Hour
	switch kind {
	case KindTMDB:
		if p.Chart == ChartDiscover || p.Chart == ChartCollection {
			return 7 * day
		}
		return day
	case KindMDBList:
		return day
	case KindImport:
		return 90 * day
	}
	return 0
}

// Backoff is how long after its nth failure in a row a list is tried
// again: an hour, then six, then a day.
func Backoff(failures int) time.Duration {
	switch {
	case failures <= 1:
		return time.Hour
	case failures == 2:
		return 6 * time.Hour
	}
	return 24 * time.Hour
}

// Fetched reports whether lists of kind are fetched from somewhere: TMDB,
// MDBList, or (for an import's details) TMDB again.
func Fetched(kind string) bool {
	return kind == KindTMDB || kind == KindMDBList || kind == KindImport
}

// Outside reports whether lists of kind come from an outside service,
// which lists.enabled turns off.
func Outside(kind string) bool { return kind == KindTMDB || kind == KindMDBList }

// mediaTypes are the entry media types a list of mediaType keeps.
func mediaTypes(mediaType string) []string {
	switch mediaType {
	case "movie":
		return []string{"movie"}
	case "tv":
		return []string{"tv"}
	}
	return []string{"movie", "tv"}
}

// keeps reports whether a list of mediaType keeps an entry of entryType.
func keeps(mediaType, entryType string) bool {
	return slices.Contains(mediaTypes(mediaType), entryType)
}

// finish ranks entries from 1, leaving out those of another media type and
// a TMDB ID seen already, up to limit.
func finish(entries []Entry, mediaType string, limit int) []Entry {
	if limit <= 0 || limit > MaxEntries {
		limit = MaxEntries
	}
	seen := map[string]bool{}
	out := make([]Entry, 0, min(len(entries), limit))
	for _, e := range entries {
		if len(out) == limit {
			break
		}
		if !keeps(mediaType, e.MediaType) {
			continue
		}
		if e.Found() {
			key := fmt.Sprintf("%s/%d", e.MediaType, e.TMDBID)
			if seen[key] {
				continue
			}
			seen[key] = true
		}
		e.Rank = len(out) + 1
		out = append(out, e)
	}
	return out
}
