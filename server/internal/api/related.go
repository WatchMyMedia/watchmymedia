package api

import (
	"context"
	"net/http"
	"slices"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/related"
)

// RelatedRows are the rows at the end of a movie's or a show's page:
// the movie's collection, then More like this.
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
			Description: "Titles from this server's libraries of the same kind, for the end of the page. Ask once the page's details have arrived; the reply never waits on TMDB. Items are marked for the signed-in person as Home's are. From API level 30.",
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

// similarTitle names More like this.
const similarTitle = "More like this"

// relatedRows works out a title's related rows for the signed-in person,
// from what is stored: the collection's other films (movies), then More
// like this when at least 3 of its titles have a card. A title whose
// related data is old is fetched again in the background.
func relatedRows(ctx context.Context, deps Deps, kind string, id int64) ([]ScreenRow, error) {
	var res related.Result
	var err error
	if kind == "movie" {
		res, err = related.Movie(ctx, deps.Queries, id, time.Now())
	} else {
		res, err = related.Show(ctx, deps.Queries, id, time.Now())
	}
	if err != nil {
		return nil, notFound(err, kind+" not found")
	}
	if res.Due {
		deps.Related.Want(kind, id)
	}
	ids := append(slices.Clone(res.Collection), res.Similar...)
	rows := []ScreenRow{}
	if len(ids) == 0 {
		return rows, nil
	}
	var cards map[int64]BrowseItem
	if kind == "movie" {
		cards, err = movieCardsByID(ctx, deps, ids)
	} else {
		cards, err = showCardsByID(ctx, deps, ids)
	}
	if err != nil {
		return nil, err
	}
	marks, err := loadWatchMarks(ctx, deps)
	if err != nil {
		return nil, err
	}
	pick := func(ids []int64, limit int) []BrowseItem {
		items := make([]BrowseItem, 0, min(len(ids), limit))
		for _, id := range ids {
			if c, ok := cards[id]; ok && len(items) < limit {
				items = append(items, c)
			}
		}
		marks.apply(items)
		return items
	}
	if items := pick(res.Collection, rowItems); len(items) > 0 {
		rows = append(rows, ScreenRow{ID: "collection", Style: "poster", Title: res.CollectionName, Items: items})
	}
	if items := pick(res.Similar, related.SimilarMax); len(items) >= related.SimilarMin {
		rows = append(rows, ScreenRow{ID: "similar", Style: "poster", Title: similarTitle, Items: items})
	}
	return rows, nil
}
