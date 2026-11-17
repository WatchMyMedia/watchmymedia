package api

import (
	"context"
	"net/http"

	"github.com/danielgtaylor/huma/v2"
)

// RelatedRows are the rows at the end of a movie's or a show's page
// (PLAN-RELATED.md §6): the movie's collection, then More like this.
type RelatedRows struct {
	Rows []ScreenRow `json:"rows" nullable:"false" doc:"In order: collection (movies only, when the library has another film of the movie's TMDB collection; titled with the collection's name, in release order) and similar (More like this, best first, when at least 3 titles qualify). Both style poster, with items. Empty when neither has anything."`
}

// RelatedInput names the movie or show.
type RelatedInput struct {
	ID int64 `path:"id"`
}

// RelatedOutput is the reply of getMovieRelated and getShowRelated.
type RelatedOutput struct {
	Body RelatedRows
}

func registerRelated(h huma.API, deps Deps) {
	op := func(id, path, summary string) huma.Operation {
		return huma.Operation{OperationID: id, Method: http.MethodGet, Path: Prefix + path, Summary: summary,
			Description: "Titles from this server's libraries of the same kind, for the end of the page (PLAN-RELATED.md). Ask once the page's details have arrived; the reply never waits on TMDB. Items are marked for the signed-in person as Home's are. From API level 30.",
			Tags:        []string{"browse"}}
	}
	huma.Register(h, op("getMovieRelated", "/movies/{id}/related", "The rows at the end of a movie's page: its collection and More like this"),
		func(ctx context.Context, in *RelatedInput) (*RelatedOutput, error) {
			rows, err := relatedRows(ctx, deps, "movie", in.ID)
			if err != nil {
				return nil, err
			}
			return &RelatedOutput{Body: RelatedRows{Rows: rows}}, nil
		})
	huma.Register(h, op("getShowRelated", "/shows/{id}/related", "The row at the end of a show's page: More like this"),
		func(ctx context.Context, in *RelatedInput) (*RelatedOutput, error) {
			rows, err := relatedRows(ctx, deps, "show", in.ID)
			if err != nil {
				return nil, err
			}
			return &RelatedOutput{Body: RelatedRows{Rows: rows}}, nil
		})
}

// relatedRows works out a title's related rows. Filled in below.
func relatedRows(ctx context.Context, deps Deps, kind string, id int64) ([]ScreenRow, error) {
	_ = ctx
	_ = deps
	_ = kind
	_ = id
	return []ScreenRow{}, nil
}
