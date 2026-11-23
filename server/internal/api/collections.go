package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strings"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/tmdb"
)

// LibraryCollection is a TMDB collection a library has two films of or
// more.
type LibraryCollection struct {
	ID           int64  `json:"id" doc:"TMDB collection ID; listLibraryItems takes it as collection."`
	Name         string `json:"name"`
	Count        int64  `json:"count" doc:"Its movies in this library with a present file."`
	Total        int64  `json:"total,omitempty" doc:"The films TMDB lists in it; absent until the server has fetched the collection."`
	PosterPath   string `json:"posterPath,omitempty" doc:"The collection's own picture, else its first film's."`
	BackdropPath string `json:"backdropPath,omitempty"`
	FirstYear    int64  `json:"firstYear,omitempty" doc:"The year of its earliest film in the library."`
	LastYear     int64  `json:"lastYear,omitempty"`
}

type LibraryCollections struct {
	Items []LibraryCollection `json:"items" nullable:"false" doc:"By name."`
}

// LibraryCollectionDetail is a collection's films: the library's, and the
// ones it lacks, to request.
type LibraryCollectionDetail struct {
	LibraryCollection
	Movies  []BrowseItem  `json:"movies" nullable:"false" doc:"The library's films of it with a present file, in release order, marked for the signed-in person as cards are."`
	Missing []Requestable `json:"missing" nullable:"false" doc:"The films TMDB lists in it that this library lacks, in release order, each with where it stands as request search says it (available when another library has it). Request opens createRequest as from search. Empty until the server has fetched the collection."`
}

type CollectionInput struct {
	ID           int64 `path:"id" doc:"Library ID."`
	CollectionID int64 `path:"collectionId" doc:"TMDB collection ID."`
}

// MovieCollection is where a movie falls in its TMDB collection.
type MovieCollection struct {
	ID    int64  `json:"id" doc:"TMDB collection ID."`
	Name  string `json:"name"`
	Part  int64  `json:"part,omitempty" doc:"Its place among the collection's films by release, from 1; absent until the server has fetched the collection."`
	Total int64  `json:"total,omitempty" doc:"The films TMDB lists in it; absent until fetched."`
	Have  int64  `json:"have" doc:"The collection's films in this movie's library with a present file, it included."`
}

func registerCollections(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "listLibraryCollections",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/collections",
		Summary:     "A movie library's TMDB collections",
		Description: "The collections the library has two films of or more. Empty for a TV library. From API level 34.",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *LibraryIDInput) (*struct{ Body LibraryCollections }, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		out := LibraryCollections{Items: []LibraryCollection{}}
		if lib.Type != library.TypeMovie {
			return &struct{ Body LibraryCollections }{Body: out}, nil
		}
		rows, err := deps.Queries.ListLibraryCollections(ctx, lib.ID)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			out.Items = append(out.Items, collectionFromRow(r))
		}
		sort.SliceStable(out.Items, func(i, j int) bool { return titleKey(out.Items[i].Name) < titleKey(out.Items[j].Name) })
		return &struct{ Body LibraryCollections }{Body: out}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getLibraryCollection",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/collections/{collectionId}",
		Summary:     "A collection's films: the library's, and the ones it lacks to request",
		Description: "404 when the library has fewer than two of its films. The reply never waits on TMDB. From API level 34.",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *CollectionInput) (*struct{ Body LibraryCollectionDetail }, error) {
		out, err := libraryCollection(ctx, deps, in.ID, in.CollectionID)
		if err != nil {
			return nil, err
		}
		return &struct{ Body LibraryCollectionDetail }{Body: out}, nil
	})
}

func collectionFromRow(r db.ListLibraryCollectionsRow) LibraryCollection {
	c := LibraryCollection{ID: deref(r.CollectionID), Name: r.CollectionName, Count: r.Movies, FirstYear: toInt64(r.FirstYear), LastYear: toInt64(r.LastYear),
		PosterPath: deref(r.FirstPosterPath), BackdropPath: deref(r.FirstBackdropPath)}
	if r.TmdbName != nil && *r.TmdbName != "" {
		c.Name = *r.TmdbName
	}
	if p := deref(r.PosterPath); p != "" {
		c.PosterPath = p
	}
	if p := deref(r.BackdropPath); p != "" {
		c.BackdropPath = p
	}
	if r.Parts != nil {
		c.Total = int64(len(collectionParts(*r.Parts)))
	}
	return c
}

// collectionParts reads what a kept collection lists; anything unreadable
// is nothing.
func collectionParts(raw string) []tmdb.CollectionPart {
	var parts []tmdb.CollectionPart
	if err := json.Unmarshal([]byte(raw), &parts); err != nil {
		return nil
	}
	// TMDB lists them in no set order: by release, with the unreleased
	// (no date) last.
	sort.SliceStable(parts, func(i, j int) bool {
		a, b := parts[i].ReleaseDate, parts[j].ReleaseDate
		if (a == "") != (b == "") {
			return b == ""
		}
		return a < b
	})
	return parts
}

func libraryCollection(ctx context.Context, deps Deps, libraryID, collectionID int64) (LibraryCollectionDetail, error) {
	lib, err := getLibrary(ctx, deps, libraryID)
	if err != nil {
		return LibraryCollectionDetail{}, err
	}
	notFound := huma.Error404NotFound("the library has fewer than two films of this collection")
	if lib.Type != library.TypeMovie {
		return LibraryCollectionDetail{}, notFound
	}
	rows, err := deps.Queries.ListLibraryCollections(ctx, lib.ID)
	if err != nil {
		return LibraryCollectionDetail{}, err
	}
	var out LibraryCollectionDetail
	found := false
	for _, r := range rows {
		if deref(r.CollectionID) == collectionID {
			out.LibraryCollection, found = collectionFromRow(r), true
			break
		}
	}
	if !found {
		return LibraryCollectionDetail{}, notFound
	}
	out.Movies, out.Missing = []BrowseItem{}, []Requestable{}
	ids, err := deps.Queries.ListLibraryCollectionMovieIDs(ctx, db.ListLibraryCollectionMovieIDsParams{LibraryID: lib.ID, CollectionID: &collectionID})
	if err != nil {
		return out, err
	}
	have := map[int64]bool{}
	if len(ids) > 0 {
		cards, err := deps.Queries.ListMovieCardsByIDs(ctx, ids)
		if err != nil {
			return out, err
		}
		for _, r := range cards {
			it := movieItem(db.ListMovieItemsRow(r))
			out.Movies = append(out.Movies, it)
			have[it.TMDBID] = true
		}
	}
	sort.SliceStable(out.Movies, func(i, j int) bool {
		a, b := out.Movies[i].ReleaseDate, out.Movies[j].ReleaseDate
		if a != b {
			return a < b
		}
		return out.Movies[i].ID < out.Movies[j].ID
	})
	marks, err := loadWatchMarks(ctx, deps)
	if err != nil {
		return out, err
	}
	marks.apply(out.Movies)

	kept, err := deps.Queries.GetTMDBCollection(ctx, collectionID)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return out, nil
	case err != nil:
		return out, err
	}
	var missing []Requestable
	for _, p := range collectionParts(kept.Parts) {
		if p.ID <= 0 || have[int64(p.ID)] {
			continue
		}
		missing = append(missing, Requestable{MediaType: "movie", TMDBID: int64(p.ID), Title: p.Title, Year: int64(tmdb.Year(p.ReleaseDate)),
			Overview: p.Overview, PosterPath: p.PosterPath})
	}
	out.Missing = collectionRequestStates(ctx, deps, missing)
	return out, markWatchlisted(ctx, deps, out.Missing)
}

// collectionRequestStates says where each of a collection's missing films
// stands, as list rows say it: available when another library has it,
// requested or downloading when a request is open, else requestable. Open
// requests are read once for all of them; with Seerr, whose state comes one
// title at a time, they are requestable here and the request dialog's
// lookup says more. Without a request service, or when the person may not
// request movies, only the library's own state.
func collectionRequestStates(ctx context.Context, deps Deps, items []Requestable) []Requestable {
	requests := false
	open := map[int64]string{}
	if s := deps.Integrations; s != nil {
		if user, ok := auth.UserFrom(ctx); ok {
			if s.Seerr() {
				requests = s.SeerrAccess(ctx, person(ctx, user)).Movies
			} else {
				requests, _ = s.Requests()
				rows, err := deps.Queries.ListOpenRequests(ctx)
				if err != nil {
					deps.Log.WarnContext(ctx, "could not read open requests for a collection", "err", err)
				}
				for _, r := range rows {
					if r.MediaType != "movie" {
						continue
					}
					if r.Status == "approved" {
						open[r.TmdbID] = "downloading"
					} else if open[r.TmdbID] == "" {
						open[r.TmdbID] = "requested"
					}
				}
			}
		}
	}
	out := make([]Requestable, 0, len(items))
	for _, r := range items {
		r.State = "requestable"
		libraryState(ctx, deps, &r)
		if r.State == "requestable" && requests {
			if st, ok := open[r.TMDBID]; ok {
				r.State = st
			}
		}
		out = append(out, r)
	}
	return out
}

// movieCollection is where a movie falls in its collection, from what the
// library has and what is kept of TMDB's collection.
func movieCollection(ctx context.Context, deps Deps, m db.Movie) (*MovieCollection, error) {
	if m.CollectionID == nil {
		return nil, nil
	}
	out := &MovieCollection{ID: *m.CollectionID, Name: strings.TrimSpace(deref(m.CollectionName))}
	ids, err := deps.Queries.ListLibraryCollectionMovieIDs(ctx, db.ListLibraryCollectionMovieIDsParams{LibraryID: m.LibraryID, CollectionID: m.CollectionID})
	if err != nil {
		return nil, err
	}
	if len(ids) > 0 {
		cards, err := deps.Queries.ListMovieCardsByIDs(ctx, ids)
		if err != nil {
			return nil, err
		}
		out.Have = int64(len(cards))
	}
	kept, err := deps.Queries.GetTMDBCollection(ctx, *m.CollectionID)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return out, nil
	case err != nil:
		return nil, err
	}
	if kept.Name != "" {
		out.Name = kept.Name
	}
	parts := collectionParts(kept.Parts)
	out.Total = int64(len(parts))
	for i, p := range parts {
		if int64(p.ID) == m.TmdbID {
			out.Part = int64(i + 1)
			break
		}
	}
	return out, nil
}
