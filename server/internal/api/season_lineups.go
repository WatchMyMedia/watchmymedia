package api

import (
	"context"
	"net/http"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/metadata"
)

// Season line-ups (PLAN-SEASON-MAPPING.md): where a season starts when TMDB
// numbers it differently from the files in a show folder. Admins only; the
// apps have no screens for it.

type SuggestSeasonsInput struct {
	ID   int64 `path:"id" doc:"Library ID."`
	Body struct {
		FileIDs []int64 `json:"fileIds" minItems:"1" maxItems:"5000"`
		TMDBID  int     `json:"tmdbId" minimum:"1" doc:"The TMDB show the files' folder is matched to."`
	}
}

type SeasonLineUpList struct {
	Seasons []metadata.SeasonLineUp `json:"seasons" nullable:"false" doc:"The seasons, as the files number them, that do not line up with the show on TMDB."`
}

type SuggestSeasonsOutput struct {
	Body SeasonLineUpList
}

type PreviewSeasonInput struct {
	ID   int64 `path:"id" doc:"Library ID."`
	Body struct {
		FileIDs []int64             `json:"fileIds" minItems:"1" maxItems:"5000"`
		Rule    metadata.SeasonRule `json:"rule"`
	}
}

type PreviewSeasonOutput struct {
	Body metadata.SeasonPreview
}

// SeasonLineUpBody is one line-up to match by, and the files it covers.
type SeasonLineUpBody struct {
	FileIDs []int64             `json:"fileIds" minItems:"1" maxItems:"5000"`
	Rule    metadata.SeasonRule `json:"rule"`
}

type MatchSeasonsInput struct {
	ID   int64 `path:"id" doc:"Library ID."`
	Body struct {
		LineUps  []SeasonLineUpBody `json:"lineUps" minItems:"1" maxItems:"100"`
		Remember bool               `json:"remember" required:"false" doc:"Use each line-up for new files of its season in the files' shared folder."`
	}
}

// RememberedSeasonLineUp is a line-up stored for a folder.
type RememberedSeasonLineUp struct {
	Folder string              `json:"folder"`
	Rule   metadata.SeasonRule `json:"rule"`
	// Show is the rule's show when the library has it.
	ShowID    *int64 `json:"showId,omitempty"`
	ShowTitle string `json:"showTitle,omitempty"`
}

type RememberedSeasonLineUpList struct {
	Items []RememberedSeasonLineUp `json:"items" nullable:"false"`
}

type ListSeasonLineUpsOutput struct {
	Body RememberedSeasonLineUpList
}

type ForgetSeasonLineUpInput struct {
	ID          int64  `path:"id" doc:"Library ID."`
	Folder      string `query:"folder" required:"true" minLength:"1" doc:"The show folder, relative to the library."`
	FromSeason  int    `query:"fromSeason" required:"true" minimum:"1"`
	FromEpisode int    `query:"fromEpisode" required:"true" minimum:"1"`
}

func registerSeasonLineUps(h huma.API, deps Deps) {
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "suggestSeasonLineUps",
		Method:      http.MethodPost,
		Path:        Prefix + "/libraries/{id}/season-lineups/suggest",
		Summary:     "Find the seasons TMDB numbers differently from the files, and a line-up for each",
		Description: "Saves nothing. A suggestion comes from one of TMDB's other episode orders, from numbers that run on into the next season, from the episode names in the files, from a later show TMDB lists the season as, or from a neighbouring season already lined up.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *SuggestSeasonsInput) (*SuggestSeasonsOutput, error) {
		lib, files, err := loadGroupFiles(ctx, deps, in.ID, in.Body.FileIDs)
		if err != nil {
			return nil, err
		}
		seasons, err := deps.Matcher.SuggestSeasons(ctx, lib, files, in.Body.TMDBID)
		if err != nil {
			return nil, huma.Error502BadGateway(err.Error())
		}
		return &SuggestSeasonsOutput{Body: SeasonLineUpList{Seasons: seasons}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "previewSeasonLineUp",
		Method:      http.MethodPost,
		Path:        Prefix + "/libraries/{id}/season-lineups/preview",
		Summary:     "Preview where a season line-up would put each file",
		Description: "Saves nothing. Lists the show's seasons and the chosen season's episodes too, to choose the start from.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *PreviewSeasonInput) (*PreviewSeasonOutput, error) {
		lib, files, err := loadGroupFiles(ctx, deps, in.ID, in.Body.FileIDs)
		if err != nil {
			return nil, err
		}
		pv, err := deps.Matcher.PreviewSeason(ctx, lib, files, in.Body.Rule)
		if err != nil {
			return nil, huma.Error502BadGateway(err.Error())
		}
		return &PreviewSeasonOutput{Body: pv}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "matchSeasons",
		Method:      http.MethodPost,
		Path:        Prefix + "/libraries/{id}/match-seasons",
		Summary:     "Match files by season line-ups chosen by the user",
		Description: "Each file is linked to the episode its line-up places it on, and locked. A file with no place stays unmatched with the reason. With remember, new files of each season in the files' shared folder follow its line-up.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *MatchSeasonsInput) (*MatchFilesOutput, error) {
		var lib db.Library
		lineUps := make([]metadata.SeasonApply, 0, len(in.Body.LineUps))
		for _, lu := range in.Body.LineUps {
			l, files, err := loadGroupFiles(ctx, deps, in.ID, lu.FileIDs)
			if err != nil {
				return nil, err
			}
			lib = l
			lineUps = append(lineUps, metadata.SeasonApply{Rule: lu.Rule, Files: files})
		}
		res, err := deps.Matcher.ApplySeasons(ctx, lib, lineUps, in.Body.Remember)
		if err != nil {
			return nil, huma.Error502BadGateway(err.Error())
		}
		publishChanged(deps, lib)
		return &MatchFilesOutput{Body: res}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "listSeasonLineUps",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/season-lineups",
		Summary:     "List the season line-ups remembered for a library's folders",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *struct {
		ID int64 `path:"id" doc:"Library ID."`
	}) (*ListSeasonLineUpsOutput, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		rows, err := deps.Queries.ListSeasonMaps(ctx, lib.ID)
		if err != nil {
			return nil, err
		}
		items := make([]RememberedSeasonLineUp, 0, len(rows))
		for _, r := range rows {
			item := RememberedSeasonLineUp{Folder: r.Folder, Rule: metadata.SeasonRule{
				FromSeason: int(r.FromSeason), FromEpisode: int(r.FromEpisode), TMDBID: int(r.TmdbID), ToSeason: int(r.ToSeason), ToEpisode: int(r.ToEpisode)}}
			if show, err := deps.Queries.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: lib.ID, TmdbID: r.TmdbID}); err == nil {
				item.ShowID, item.ShowTitle = &show.ID, show.Title
			}
			items = append(items, item)
		}
		return &ListSeasonLineUpsOutput{Body: RememberedSeasonLineUpList{Items: items}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "forgetSeasonLineUp",
		Method:        http.MethodDelete,
		Path:          Prefix + "/libraries/{id}/season-lineups",
		Summary:       "Forget a remembered season line-up",
		Description:   "Files already matched by it stay matched; new files of that season are matched by their own numbers again.",
		Tags:          []string{"files"},
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, in *ForgetSeasonLineUpInput) (*struct{}, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		n, err := deps.Queries.DeleteSeasonMap(ctx, db.DeleteSeasonMapParams{LibraryID: lib.ID, Folder: in.Folder, FromSeason: int64(in.FromSeason), FromEpisode: int64(in.FromEpisode)})
		if err != nil {
			return nil, err
		}
		if n == 0 {
			return nil, huma.Error404NotFound("no such season line-up")
		}
		return nil, nil
	})
}
