package api

import (
	"context"
	"net/http"

	"github.com/danielgtaylor/huma/v2"
)

// TMDBLookupInput names a title by its TMDB id.
type TMDBLookupInput struct {
	TMDBID int64 `query:"tmdbId" required:"true" minimum:"1" doc:"The movie's or show's TMDB id, as cards and search carry it (tmdbId, showTmdbId)."`
}

// MovieCopies are the library's copies of one movie.
type MovieCopies struct {
	Items []Movie `json:"items" nullable:"false" doc:"Every library's copy with a present file, the first library's first, marked for the signed-in person as search's are (watched, progress). Empty when the library has none."`
}

// ShowCopies are the library's copies of one show.
type ShowCopies struct {
	Items []Show `json:"items" nullable:"false" doc:"Every library's copy with a present episode file, the first library's first, marked for the signed-in person as search's are (watched, unwatchedCount, seasonCount). Empty when the library has none."`
}

func registerTitlesByTMDB(h huma.API, deps Deps) {
	const description = "For an app signed in to several servers, which shows a title on more than one as one card and offers each server's copy when it plays. " +
		"Answers with an empty list, never an error, when the library doesn't have it. From API level 34."
	huma.Register(h, huma.Operation{
		OperationID: "findMoviesByTMDB",
		Method:      http.MethodGet,
		Path:        Prefix + "/movies",
		Summary:     "The library's copies of a movie, by its TMDB id",
		Description: description,
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *TMDBLookupInput) (*struct{ Body MovieCopies }, error) {
		if _, err := currentUser(ctx); err != nil {
			return nil, err
		}
		rows, err := deps.Queries.ListMoviesByTMDB(ctx, in.TMDBID)
		if err != nil {
			return nil, err
		}
		res := &SearchResults{Movies: make([]Movie, 0, len(rows)), Shows: []Show{}, Episodes: []SearchEpisode{}}
		for _, m := range rows {
			res.Movies = append(res.Movies, movieFromDB(m))
		}
		if err := markSearch(ctx, deps, res); err != nil {
			return nil, err
		}
		return &struct{ Body MovieCopies }{Body: MovieCopies{Items: res.Movies}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "findShowsByTMDB",
		Method:      http.MethodGet,
		Path:        Prefix + "/shows",
		Summary:     "The library's copies of a show, by its TMDB id",
		Description: description,
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *TMDBLookupInput) (*struct{ Body ShowCopies }, error) {
		if _, err := currentUser(ctx); err != nil {
			return nil, err
		}
		rows, err := deps.Queries.ListShowsByTMDB(ctx, in.TMDBID)
		if err != nil {
			return nil, err
		}
		res := &SearchResults{Movies: []Movie{}, Shows: make([]Show, 0, len(rows)), Episodes: []SearchEpisode{}}
		for _, s := range rows {
			res.Shows = append(res.Shows, showFromDB(s))
		}
		if err := markSearch(ctx, deps, res); err != nil {
			return nil, err
		}
		return &struct{ Body ShowCopies }{Body: ShowCopies{Items: res.Shows}}, nil
	})
}
