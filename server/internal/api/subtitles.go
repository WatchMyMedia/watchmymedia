package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/opensubtitles"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/subtitles"
)

type SearchSubtitlesInput struct {
	ID        int64  `path:"id"`
	Languages string `query:"languages" doc:"Comma-separated ISO 639-1 codes (pt-br style for regions); the person's preferred languages when configured, else the server's subtitle languages, when empty."`
}

type SearchSubtitlesOutput struct{ Body subtitles.SubtitleSearch }

type DownloadSubtitleInput struct {
	ID   int64 `path:"id"`
	Body struct {
		ResultID string `json:"resultId" minLength:"1" maxLength:"32" doc:"A search result's id."`
	}
}

type SubtitleTrackOutput struct{ Body playback.SubtitleTrack }

type DeleteSubtitleInput struct {
	ID    int64  `path:"id"`
	Track string `path:"track" doc:"A downloaded track's id, d<n>."`
}

func registerSubtitles(h huma.API, deps Deps) {
	registerAutomaticSubtitle(h, deps)
	huma.Register(h, huma.Operation{
		OperationID: "searchSubtitles",
		Method:      http.MethodGet,
		Path:        Prefix + "/files/{id}/subtitles/search",
		Summary:     "Find subtitles online for a file",
		Description: "Searches OpenSubtitles by the file's TMDB IDs (or its name when unmatched) and its hash. Free, and cached for a day per file and languages, so players call it in the background once playback starts. Provider failures come back in `error` with no results.",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, in *SearchSubtitlesInput) (*SearchSubtitlesOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if _, err := deps.Queries.GetMediaFile(ctx, in.ID); err != nil {
			return nil, notFound(err, "file not found")
		}
		if deps.OnlineSubtitles == nil {
			return &SearchSubtitlesOutput{Body: subtitles.SubtitleSearch{Provider: subtitles.Provider, Attribution: opensubtitles.Attribution, Languages: []string{}, Results: []subtitles.OnlineSubtitle{}, Quota: subtitles.SubtitleQuota{Remaining: -1}}}, nil
		}
		langs := subtitles.ParseLanguages(in.Languages)
		if in.Languages == "" {
			p, err := loadPreferences(ctx, deps, user.ID)
			if err != nil {
				return nil, err
			}
			for _, lang := range p.Subtitles.Languages {
				langs = append(langs, providerSubtitleLanguage(lang))
			}
			langs = subtitles.ParseLanguages(strings.Join(langs, ","))
		}
		out := deps.OnlineSubtitles.Search(ctx, in.ID, langs)
		out.CanDownload = out.Available && (effectiveAdmin(ctx, user) || deps.OnlineSubtitles.Downloads(ctx) == subtitles.DownloadsEveryone)
		return &SearchSubtitlesOutput{Body: out}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "downloadSubtitle",
		Method:      http.MethodPost,
		Path:        Prefix + "/files/{id}/subtitles/download",
		Summary:     "Download a subtitle found online and keep it for the file",
		Description: "Spends one of the server's OpenSubtitles downloads, unless this result was downloaded for the file before. Returns the new track; select it with a new playback decision (subtitleId) or load its URL directly. 429 when the day's downloads are used up.",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, in *DownloadSubtitleInput) (*SubtitleTrackOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if deps.OnlineSubtitles == nil || !deps.OnlineSubtitles.Available() {
			return nil, huma.Error503ServiceUnavailable("online subtitles are not available on this server")
		}
		if !effectiveAdmin(ctx, user) && deps.OnlineSubtitles.Downloads(ctx) != subtitles.DownloadsEveryone {
			return nil, huma.Error403Forbidden("only admins can download subtitles on this server")
		}
		if _, err := deps.Queries.GetMediaFile(ctx, in.ID); err != nil {
			return nil, notFound(err, "file not found")
		}
		row, err := deps.OnlineSubtitles.Download(ctx, in.ID, strings.TrimSpace(in.Body.ResultID), user.ID)
		var quota *opensubtitles.QuotaError
		switch {
		case errors.As(err, &quota):
			msg := "No subtitle downloads left today"
			if !quota.ResetAt.IsZero() {
				msg += "; they resume at " + quota.ResetAt.UTC().Format("15:04 UTC")
			}
			return nil, huma.NewError(http.StatusTooManyRequests, msg+".")
		case errors.Is(err, subtitles.ErrNotFound):
			return nil, huma.Error404NotFound(err.Error())
		case errors.Is(err, opensubtitles.ErrSignIn):
			return nil, huma.Error502BadGateway("OpenSubtitles refused the server's account; an admin can fix it in Settings › Subtitles.")
		case err != nil:
			deps.Log.WarnContext(ctx, "subtitle download", "file", in.ID, "result", in.Body.ResultID, "err", err)
			return nil, huma.Error502BadGateway("could not download the subtitle: " + err.Error())
		}
		t := downloadedTrack(row)
		t.URL = Prefix + "/files/" + strconv.FormatInt(in.ID, 10) + "/subtitles/" + t.ID + ".vtt"
		return &SubtitleTrackOutput{Body: t}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "deleteSubtitle",
		Method:        http.MethodDelete,
		Path:          Prefix + "/files/{id}/subtitles/{track}",
		Summary:       "Remove a downloaded subtitle (admins, or whoever downloaded it)",
		Tags:          []string{"playback"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *DeleteSubtitleInput) (*struct{}, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		n, ok := strings.CutPrefix(in.Track, "d")
		id, convErr := strconv.ParseInt(n, 10, 64)
		if !ok || convErr != nil || deps.OnlineSubtitles == nil {
			return nil, huma.Error404NotFound("no such downloaded subtitle")
		}
		row, err := deps.OnlineSubtitles.Get(ctx, in.ID, id)
		if err != nil {
			return nil, notFound(err, "no such downloaded subtitle")
		}
		if !effectiveAdmin(ctx, user) && (row.DownloadedBy == nil || *row.DownloadedBy != user.ID) {
			return nil, huma.Error403Forbidden("only admins or whoever downloaded it can remove it")
		}
		return nil, deps.OnlineSubtitles.Delete(ctx, in.ID, id)
	})
}
