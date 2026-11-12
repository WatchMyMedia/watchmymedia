package lists

import (
	"context"
	"slices"
)

// Library makes lists from a saved browse filter on one library: its sort,
// filter, genre and decade, as listLibraryItems takes them. Such a list has
// no entries of its own; the api package reads it from the library each
// time, through the browse code, so it follows the library as it changes.
type Library struct{}

// Library sorts and filters, as listLibraryItems' enums.
var (
	LibrarySorts   = []string{"title", "added", "year", "rating", "released", "runtime", "aired"}
	LibraryFilters = []string{"", "continuing", "ended", "4k", "hdr", "unwatched"}
)

func (Library) Kind() string       { return KindLibrary }
func (Library) Name(Params) string { return "your library" }

func (Library) Validate(r Request) error {
	p := r.Params
	if p.LibraryID <= 0 {
		return invalid("Choose a library")
	}
	if p.Sort != "" && !slices.Contains(LibrarySorts, p.Sort) {
		return invalid("Sort by title, added, year, rating, released, runtime or aired")
	}
	if !slices.Contains(LibraryFilters, p.Filter) {
		return invalid("Filter by continuing, ended, 4k, hdr or unwatched")
	}
	if p.Decade != 0 && (p.Decade < 1870 || p.Decade > 2100 || p.Decade%10 != 0) {
		return invalid("A decade is a year ending in 0, such as 1990")
	}
	return nil
}

// Fetch says a library list is not fetched: it is read from the library.
func (Library) Fetch(context.Context, Request) (Result, error) { return Result{}, ErrNotFetched }

// LibrarySort is the sort a library list uses: newest added unless it says.
func LibrarySort(p Params) string {
	if p.Sort == "" {
		return "added"
	}
	return p.Sort
}
