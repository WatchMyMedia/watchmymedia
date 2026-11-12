package api

import (
	"context"
	"net/http"
	"strings"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
)

type SearchInput struct {
	Q     string `query:"q" minLength:"1" maxLength:"100"`
	Limit int64  `query:"limit" default:"20" minimum:"1" maximum:"50"`
}

type SearchEpisode struct {
	ID             int64  `json:"id"`
	ShowID         int64  `json:"showId"`
	ShowTitle      string `json:"showTitle"`
	ShowPosterPath string `json:"showPosterPath,omitempty"`
	SeasonNumber   int64  `json:"seasonNumber"`
	Number         int64  `json:"number"`
	Title          string `json:"title"`
	StillPath      string `json:"stillPath,omitempty"`
	AirDate        string `json:"airDate,omitempty"`
	FileID         int64  `json:"fileId"`
	Watched        bool   `json:"watched" required:"false" doc:"The signed-in user has watched it."`
}

type SearchResults struct {
	Movies   []Movie         `json:"movies" nullable:"false"`
	Shows    []Show          `json:"shows" nullable:"false"`
	Episodes []SearchEpisode `json:"episodes" nullable:"false"`
}

type SearchOutput struct{ Body SearchResults }

func registerSearch(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "search",
		Method:      http.MethodGet,
		Path:        Prefix + "/search",
		Summary:     "Search titles across libraries",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *SearchInput) (*SearchOutput, error) {
		pattern := "%" + strings.ReplaceAll(strings.ReplaceAll(strings.TrimSpace(in.Q), "%", ""), "_", "") + "%"
		out := &SearchOutput{Body: SearchResults{Movies: []Movie{}, Shows: []Show{}, Episodes: []SearchEpisode{}}}
		movies, err := deps.Queries.SearchMovies(ctx, db.SearchMoviesParams{Title: pattern, OriginalTitle: &pattern, Limit: in.Limit})
		if err != nil {
			return nil, err
		}
		for _, m := range movies {
			out.Body.Movies = append(out.Body.Movies, movieFromDB(m))
		}
		shows, err := deps.Queries.SearchShows(ctx, db.SearchShowsParams{Title: pattern, OriginalTitle: &pattern, Limit: in.Limit})
		if err != nil {
			return nil, err
		}
		for _, s := range shows {
			out.Body.Shows = append(out.Body.Shows, showFromDB(s))
		}
		episodes, err := deps.Queries.SearchEpisodes(ctx, db.SearchEpisodesParams{Title: pattern, Limit: in.Limit})
		if err != nil {
			return nil, err
		}
		ids := make([]int64, len(episodes))
		for i, e := range episodes {
			ids[i] = e.ID
		}
		watched, err := watchedEpisodes(ctx, deps, ids)
		if err != nil {
			return nil, err
		}
		for _, e := range episodes {
			out.Body.Episodes = append(out.Body.Episodes, SearchEpisode{
				ID: e.ID, ShowID: e.ShowID, ShowTitle: e.ShowTitle, ShowPosterPath: deref(e.ShowPosterPath), SeasonNumber: e.SeasonNumber,
				Number: e.Number, Title: e.Title, StillPath: deref(e.StillPath), AirDate: deref(e.AirDate), FileID: toInt64(e.FileID),
				Watched: watched[e.ID],
			})
		}
		return out, nil
	})
}
