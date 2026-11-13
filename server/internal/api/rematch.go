package api

import (
	"context"
	"net/http"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/scanner"
)

type LibraryCandidatesInput struct {
	ID    int64  `path:"id" doc:"Library ID."`
	Query string `query:"q" minLength:"1" doc:"Title to search for."`
	Year  int    `query:"year" required:"false" minimum:"1800" doc:"Year to narrow the search; 0 for any."`
}

type RematchPreviewInput struct {
	ID     int64 `path:"id"`
	TMDBID int   `query:"tmdbId" minimum:"1" doc:"The TMDB show or movie to move to."`
}

type RematchPreviewOutput struct {
	Body metadata.RematchPlan
}

type RematchInput struct {
	ID   int64 `path:"id"`
	Body struct {
		TMDBID   int  `json:"tmdbId" minimum:"1" doc:"The TMDB show or movie to move to."`
		Remember bool `json:"remember" required:"false" doc:"Use it for new files in the files' shared folder."`
	}
}

type RematchOutput struct {
	Body metadata.RematchResult
}

func registerRematch(h huma.API, deps Deps) {
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "searchLibraryCandidates",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/candidates",
		Summary:     "Search TMDB for shows or movies, by the library's type",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *LibraryCandidatesInput) (*SearchCandidatesOutput, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if err := tmdbReady(deps); err != nil {
			return nil, err
		}
		var year *int
		if in.Year > 0 {
			year = &in.Year
		}
		return searchCandidates(ctx, deps, lib.Type, in.Query, year)
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "previewShowRematch",
		Method:      http.MethodGet,
		Path:        Prefix + "/shows/{id}/rematch",
		Summary:     "Preview moving a show's files to another TMDB show",
		Description: "Saves nothing. Counts the files that keep their season and episode in the other show, and the watched episodes that carry over.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *RematchPreviewInput) (*RematchPreviewOutput, error) {
		show, err := deps.Queries.GetShow(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "show not found")
		}
		if err := tmdbReady(deps); err != nil {
			return nil, err
		}
		plan, err := deps.Matcher.PreviewShowRematch(ctx, show, in.TMDBID)
		if err != nil {
			return nil, huma.Error502BadGateway(err.Error())
		}
		return &RematchPreviewOutput{Body: plan}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "rematchShow",
		Method:      http.MethodPost,
		Path:        Prefix + "/shows/{id}/rematch",
		Summary:     "Move a show's files to another TMDB show",
		Description: "Each file keeps its season and episode numbers and is locked; files whose episode the other show lacks are left unmatched. Watch state moves to the episode with the same numbers. The old show is removed.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *RematchInput) (*RematchOutput, error) {
		show, err := deps.Queries.GetShow(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "show not found")
		}
		if show.TmdbID == int64(in.Body.TMDBID) {
			return nil, huma.Error422UnprocessableEntity("the show is already matched to that TMDB show")
		}
		if err := tmdbReady(deps); err != nil {
			return nil, err
		}
		lib, err := deps.Queries.GetLibrary(ctx, show.LibraryID)
		if err != nil {
			return nil, err
		}
		res, err := deps.Matcher.RematchShow(ctx, lib, show, in.Body.TMDBID, in.Body.Remember)
		if err != nil {
			return nil, huma.Error502BadGateway(err.Error())
		}
		publishChanged(deps, lib)
		return &RematchOutput{Body: res}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "previewMovieRematch",
		Method:      http.MethodGet,
		Path:        Prefix + "/movies/{id}/rematch",
		Summary:     "Preview moving a movie's files to another TMDB movie",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *RematchPreviewInput) (*RematchPreviewOutput, error) {
		movie, err := deps.Queries.GetMovie(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "movie not found")
		}
		if err := tmdbReady(deps); err != nil {
			return nil, err
		}
		plan, err := deps.Matcher.PreviewMovieRematch(ctx, movie)
		if err != nil {
			return nil, err
		}
		return &RematchPreviewOutput{Body: plan}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "rematchMovie",
		Method:      http.MethodPost,
		Path:        Prefix + "/movies/{id}/rematch",
		Summary:     "Move a movie's files to another TMDB movie",
		Description: "The files are locked to the other movie and watch state moves with them. The old movie is removed.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *RematchInput) (*RematchOutput, error) {
		movie, err := deps.Queries.GetMovie(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "movie not found")
		}
		if movie.TmdbID == int64(in.Body.TMDBID) {
			return nil, huma.Error422UnprocessableEntity("the movie is already matched to that TMDB movie")
		}
		if err := tmdbReady(deps); err != nil {
			return nil, err
		}
		lib, err := deps.Queries.GetLibrary(ctx, movie.LibraryID)
		if err != nil {
			return nil, err
		}
		res, err := deps.Matcher.RematchMovie(ctx, lib, movie, in.Body.TMDBID, in.Body.Remember)
		if err != nil {
			return nil, huma.Error502BadGateway(err.Error())
		}
		publishChanged(deps, lib)
		return &RematchOutput{Body: res}, nil
	})
}

func tmdbReady(deps Deps) error {
	if deps.Matcher == nil || !deps.Matcher.Configured() {
		return huma.Error503ServiceUnavailable("TMDB API key not configured")
	}
	return nil
}

// searchCandidates searches TMDB for a library's kind of item: 422 when
// nothing comes close, 502 when TMDB fails.
func searchCandidates(ctx context.Context, deps Deps, libraryType, query string, year *int) (*SearchCandidatesOutput, error) {
	items, err := deps.Matcher.Search(ctx, libraryType, query, year)
	if metadata.IsNoMatch(err) {
		return nil, huma.Error422UnprocessableEntity(err.Error())
	}
	if err != nil {
		return nil, huma.Error502BadGateway(err.Error())
	}
	if items == nil {
		items = []metadata.Candidate{}
	}
	return &SearchCandidatesOutput{Body: CandidateList{Items: items}}, nil
}

// publishChanged tells clients a library's items changed.
func publishChanged(deps Deps, lib db.Library) {
	if deps.Bus != nil {
		deps.Bus.Publish(events.Event{Type: "library.changed", Data: scanner.LibraryChanged{LibraryID: lib.ID}})
	}
}
