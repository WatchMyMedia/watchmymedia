package api

import (
	"context"
	"fmt"
	"net/http"
	"slices"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations"
)

// Deleting and replacing files (PLAN-ARR.md §8, §9): the Radarr or Sonarr
// that manages an item deletes it, after the server has said what will go.
// Admins delete anything; when Who can delete is "everyone", anyone signed in
// may delete an episode or a movie too (PLAN-EPISODE-PAGE.md §4).

// DeleteSettings is who may delete episodes and movies.
type DeleteSettings struct {
	Access string `json:"access" enum:"admins,everyone" doc:"admins: only admins delete; everyone: anyone signed in may delete episodes and movies. Shows, whole seasons and replacing a file stay with admins."`
}

type DeleteSettingsOutput struct{ Body DeleteSettings }

type UpdateDeleteSettingsInput struct{ Body DeleteSettings }

// integrationsDeleter lets admins delete anything, and anyone signed in
// delete an episode or a movie while the server lets everyone.
func integrationsDeleter(ctx context.Context, deps Deps, typ string) (*integrations.Service, error) {
	if deps.Integrations == nil && deps.Recordings != nil {
		access := "admins"
		if deps.Settings != nil {
			access, _ = deps.Settings.Get(ctx, integrations.KeyDeleteAccess, "admins")
		}
		if (typ == "episode" || typ == "movie") && access == "everyone" {
			_, err := currentUser(ctx)
			return nil, err
		}
		_, err := requireAdmin(ctx)
		return nil, err
	}

	if (typ == "episode" || typ == "movie") && deps.Integrations != nil && deps.Integrations.DeleteAccess(ctx) == "everyone" {
		if _, err := currentUser(ctx); err != nil {
			return nil, err
		}
		return deps.Integrations, nil
	}
	return integrationsAdmin(ctx, deps)
}

type PreviewDeleteInput struct {
	Type   string `query:"type" enum:"movie,show,season,episode"`
	ID     int64  `query:"id" minimum:"1" doc:"The movie, show or episode; for a season, its show."`
	Season int64  `query:"season" minimum:"0" doc:"A season's number (0 for specials)."`
}

type DeletePreviewOutput struct{ Body integrations.DeletePreview }

type DeleteItemInput struct {
	ID   int64 `path:"id"`
	Body integrations.DeleteOptions
}

type DeleteSeasonInput struct {
	ID     int64 `path:"id" doc:"Show ID."`
	Number int64 `path:"number" minimum:"0"`
	Body   integrations.DeleteOptions
}

type DeleteOutput struct{ Body integrations.DeleteResult }

type ReplaceFileInput struct {
	ID int64 `path:"id" doc:"Media file ID."`
}

type ReplaceFileOutput struct{ Body integrations.ReplaceResult }

func registerDelete(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "previewDelete",
		Method:      http.MethodGet,
		Path:        Prefix + "/delete/preview",
		Summary:     "Say what deleting a movie, show, season or episode would do",
		Description: "Asks each Radarr or Sonarr that holds the item now: the files and their size, the folder, the recycling bin, files the app doesn't list, and the choices with their defaults; and names the Seerr that will be told. An item no app manages has no delete. Admins; for an episode or a movie, anyone signed in while getDeleteSettings says everyone.",
		Tags:        []string{"delete"},
	}, func(ctx context.Context, in *PreviewDeleteInput) (*DeletePreviewOutput, error) {
		s, err := integrationsDeleter(ctx, deps, in.Type)
		if err != nil {
			return nil, err
		}
		t, err := deleteTarget(ctx, deps.Queries, in.Type, in.ID, in.Season)
		if err != nil {
			return nil, err
		}
		rec, err := splitRecordings(ctx, deps, t.Files)
		if err != nil {
			return nil, err
		}
		p := integrations.DeletePreview{Apps: []integrations.DeleteApp{}, Kept: len(rec.rest)}
		if s != nil && rec.asksApps() {
			p = s.PreviewDelete(ctx, rec.forApps(t))
		}
		if len(rec.paths) > 0 {
			p.Recording = true
			p.Managed = true
			p.Files += len(rec.paths)
			p.Size += rec.bytes
		}
		if p.Managed {
			p.Trailers = len(t.Trailers)
		}

		if t.Type == "movie" {
			files, _ := deps.Queries.ListMovieFiles(ctx, &in.ID)
			for i := range p.Apps {
				p.Apps[i].Quality = folderQuality(files, p.Apps[i].LocalFolder)
			}
		}
		return &DeletePreviewOutput{Body: p}, nil
	})

	del := func(ctx context.Context, typ string, id, season int64, o integrations.DeleteOptions) (*DeleteOutput, error) {
		s, err := integrationsDeleter(ctx, deps, typ)
		if err != nil {
			return nil, err
		}
		u, _ := currentUser(ctx)
		t, err := deleteTarget(ctx, deps.Queries, typ, id, season)
		if err != nil {
			return nil, err
		}
		rec, err := splitRecordings(ctx, deps, t.Files)
		if err != nil {
			return nil, err
		}
		res := integrations.DeleteResult{Apps: []integrations.DeleteDone{}}
		count, bytes := 0, int64(0)
		if len(rec.paths) > 0 {
			count, bytes, err = deps.Recordings.DeletePaths(ctx, rec.paths, o.RecordAgain)
			if err != nil {
				return nil, recordingError(err)
			}
		}
		if s != nil && rec.asksApps() {
			res, err = s.DeleteMedia(ctx, person(ctx, u), rec.forApps(t), o)
			switch {
			case err == nil:
			case count > 0 && integrations.IsNotManaged(err):
				// The recordings went. The item's other files, which no
				// app holds, stay, as the preview said (kept).
				res = integrations.DeleteResult{Apps: []integrations.DeleteDone{}}
			case count > 0 && len(res.Apps) > 0:
				// The recordings went and every app failed: the reply says
				// which, as when one of several apps fails.
			default:
				return nil, integrationError(err)
			}
		}
		if count > 0 {
			res.Apps = append(res.Apps, integrations.DeleteDone{Connection: "WatchMyMedia recordings", Files: count, Size: bytes})
		}
		return &DeleteOutput{Body: res}, nil
	}
	const whoDeletes = "Admins, or anyone signed in while getDeleteSettings says everyone."
	const deleteDoc = "Radarr or Sonarr deletes the files (to its recycling bin when one is set), after the item is unmonitored. The item's open requests close, Seerr (when connected) clears its record so the item can be asked for again, and the server scans the folder at once. When several apps hold the item and one fails, the others go ahead and the reply says which failed; Seerr failing never fails the delete."

	huma.Register(h, huma.Operation{
		OperationID: "deleteMovie",
		Method:      http.MethodPost,
		Path:        Prefix + "/movies/{id}/delete",
		Summary:     "Delete a movie in the Radarrs that hold it",
		Description: deleteDoc + " The movie's trailer files go with it when none of its files stays: with its folder, or deleted by the server when the movie stays in Radarr. " + whoDeletes,
		Tags:        []string{"delete"},
	}, func(ctx context.Context, in *DeleteItemInput) (*DeleteOutput, error) {
		return del(ctx, "movie", in.ID, 0, in.Body)
	})

	huma.Register(h, huma.Operation{
		OperationID: "deleteShow",
		Method:      http.MethodPost,
		Path:        Prefix + "/shows/{id}/delete",
		Summary:     "Delete a show in the Sonarrs that hold it",
		Description: deleteDoc,
		Tags:        []string{"delete"},
	}, func(ctx context.Context, in *DeleteItemInput) (*DeleteOutput, error) {
		return del(ctx, "show", in.ID, 0, in.Body)
	})

	huma.Register(h, huma.Operation{
		OperationID: "deleteSeason",
		Method:      http.MethodPost,
		Path:        Prefix + "/shows/{id}/seasons/{number}/delete",
		Summary:     "Delete a season's files in Sonarr",
		Description: "Unmonitors the season (or, with keepMonitored, only the episodes whose files go), then has Sonarr delete the files. " + deleteDoc,
		Tags:        []string{"delete"},
	}, func(ctx context.Context, in *DeleteSeasonInput) (*DeleteOutput, error) {
		return del(ctx, "season", in.ID, in.Number, in.Body)
	})

	huma.Register(h, huma.Operation{
		OperationID: "deleteEpisode",
		Method:      http.MethodPost,
		Path:        Prefix + "/episodes/{id}/delete",
		Summary:     "Delete an episode's file in Sonarr",
		Description: "Unmonitors the episode, then has Sonarr delete its file. " + deleteDoc + " " + whoDeletes,
		Tags:        []string{"delete"},
	}, func(ctx context.Context, in *DeleteItemInput) (*DeleteOutput, error) {
		return del(ctx, "episode", in.ID, 0, in.Body)
	})

	huma.Register(h, huma.Operation{
		OperationID: "replaceFile",
		Method:      http.MethodPost,
		Path:        Prefix + "/files/{id}/replace",
		Summary:     "Replace a file: block its release, delete it and search again",
		Description: "The Radarr or Sonarr that manages the file marks the grab that brought it in failed, so it blocklists that release, deletes the file and searches for another. A file imported by hand has no grab to block; it is replaced all the same and the reply says so. The item is gone until the new copy arrives.",
		Tags:        []string{"delete"},
	}, func(ctx context.Context, in *ReplaceFileInput) (*ReplaceFileOutput, error) {
		s, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		u, _ := currentUser(ctx)
		t, err := replaceTarget(ctx, deps.Queries, in.ID)
		if err != nil {
			return nil, err
		}
		res, err := s.ReplaceFile(ctx, person(ctx, u), t)
		if err != nil {
			return nil, integrationError(err)
		}
		return &ReplaceFileOutput{Body: res}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getDeleteSettings",
		Method:      http.MethodGet,
		Path:        Prefix + "/settings/deleting",
		Summary:     "Who may delete episodes and movies (admin)",
		Tags:        []string{"delete"},
	}, func(ctx context.Context, _ *struct{}) (*DeleteSettingsOutput, error) {
		s, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		return &DeleteSettingsOutput{Body: DeleteSettings{Access: s.DeleteAccess(ctx)}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "updateDeleteSettings",
		Method:      http.MethodPut,
		Path:        Prefix + "/settings/deleting",
		Summary:     "Change who may delete episodes and movies (admin)",
		Tags:        []string{"delete"},
	}, func(ctx context.Context, in *UpdateDeleteSettingsInput) (*DeleteSettingsOutput, error) {
		s, err := integrationsAdmin(ctx, deps)
		if err != nil {
			return nil, err
		}
		if err := s.SetDeleteAccess(ctx, in.Body.Access); err != nil {
			return nil, integrationError(err)
		}
		return &DeleteSettingsOutput{Body: DeleteSettings{Access: s.DeleteAccess(ctx)}}, nil
	})
}

// deleteTarget finds a library movie, show, season or episode, with its
// present files, for a delete.
func deleteTarget(ctx context.Context, q *db.Queries, typ string, id, season int64) (integrations.DeleteTarget, error) {
	t := integrations.DeleteTarget{Type: typ}
	library := func(id int64) error {
		lib, err := q.GetLibrary(ctx, id)
		if err != nil {
			return err
		}
		t.LibraryID, t.LibraryPath = lib.ID, lib.Path
		return nil
	}
	if typ == "movie" {
		m, err := q.GetMovie(ctx, id)
		if err != nil {
			return t, notFound(err, "movie not found")
		}
		if err := library(m.LibraryID); err != nil {
			return t, err
		}
		t.TMDBID, t.Title = int(m.TmdbID), m.Title
		if m.Year != nil {
			t.Title += fmt.Sprintf(" (%d)", *m.Year)
		}
		files, err := q.ListMovieFiles(ctx, &m.ID)
		if err != nil {
			return t, err
		}
		for _, f := range files {
			if f.Status == "present" {
				t.Files = append(t.Files, f.Path)
			}
		}
		trailers, err := q.ListMovieTrailerFiles(ctx, &m.ID)
		if err != nil {
			return t, err
		}
		for _, f := range trailers {
			t.Trailers = append(t.Trailers, f.Path)
		}
		return t, nil
	}

	showID := id
	var ep db.Episode
	if typ == "episode" {
		e, err := q.GetEpisode(ctx, id)
		if err != nil {
			return t, notFound(err, "episode not found")
		}
		se, err := q.GetSeason(ctx, e.SeasonID)
		if err != nil {
			return t, err
		}
		ep, showID, season = e, e.ShowID, se.Number
	}
	show, err := q.GetShow(ctx, showID)
	if err != nil {
		return t, notFound(err, "show not found")
	}
	if err := library(show.LibraryID); err != nil {
		return t, err
	}
	t.TMDBID, t.Title = int(show.TmdbID), show.Title
	seasons, err := q.ListSeasons(ctx, show.ID)
	if err != nil {
		return t, err
	}
	for _, se := range seasons {
		if typ != "show" && se.Number != season {
			continue
		}
		files, err := q.ListSeasonEpisodeFiles(ctx, se.ID)
		if err != nil {
			return t, err
		}
		for _, f := range files {
			if f.Status == "present" && (typ != "episode" || f.EpisodeID == ep.ID) && !slices.Contains(t.Files, f.Path) {
				t.Files = append(t.Files, f.Path)
			}
		}
		if typ == "season" {
			eps, err := q.ListSeasonEpisodes(ctx, se.ID)
			if err != nil {
				return t, err
			}
			t.Airing = seasonAiring(show, se.Number == seasons[len(seasons)-1].Number, eps, time.Now().UTC().Format("2006-01-02"))
		}
	}
	switch typ {
	case "season":
		if !slices.ContainsFunc(seasons, func(se db.ListSeasonsRow) bool { return se.Number == season }) {
			return t, huma.Error404NotFound("season not found")
		}
		t.Season = int(season)
		t.Title += fmt.Sprintf(" · Season %d", season)
	case "episode":
		t.Season, t.Episode = int(season), int(ep.Number)
		t.Title += " · " + seCode(season, ep.Number)
	}
	return t, nil
}

// recordingFiles parts a delete's files into those finished recordings own,
// which the server deletes itself (PLAN-LIVE-TV-DVR.md §4.5), and the rest,
// which stay with Radarr or Sonarr.
type recordingFiles struct {
	paths []string
	bytes int64
	rest  []string
}

func splitRecordings(ctx context.Context, deps Deps, files []string) (recordingFiles, error) {
	out := recordingFiles{rest: files}
	if deps.Recordings == nil || len(files) == 0 {
		return out, nil
	}
	out.rest = nil
	for _, f := range files {
		owned, err := deps.Recordings.OwnedPaths(ctx, []string{f})
		if err != nil {
			return out, err
		}
		if len(owned) == 0 {
			out.rest = append(out.rest, f)
			continue
		}
		out.paths = append(out.paths, f)
		out.bytes += owned[0].Bytes
	}
	return out, nil
}

// asksApps reports whether Radarr or Sonarr has a part in the delete: not
// when every file is a recording, which no app holds even where an app
// manages the show.
func (r recordingFiles) asksApps() bool { return len(r.paths) == 0 || len(r.rest) > 0 }

// forApps is t with only the files the apps may hold.
func (r recordingFiles) forApps(t integrations.DeleteTarget) integrations.DeleteTarget {
	t.Files = r.rest
	return t
}

// seasonAiring reports whether a season still has episodes to come: one airs
// after today, or, in a show's last season, one has no date yet.
func seasonAiring(show db.Show, last bool, eps []db.Episode, today string) bool {
	if ended(deref(show.Status)) {
		return false
	}
	for _, e := range eps {
		if e.AirDate != nil && *e.AirDate > today || (last && (e.AirDate == nil || *e.AirDate == "")) {
			return true
		}
	}
	return false
}

// folderQuality is the best of a movie's present files in folder: "4K Dolby
// Vision", "1080p".
func folderQuality(files []db.MediaFile, folder string) string {
	var best db.MediaFile
	for _, f := range files {
		if f.Status == "present" && folder != "" && integrations.InFolder(folder, f.Path) && deref(f.Height) >= deref(best.Height) {
			best = f
		}
	}
	label := qualityLabel(deref(best.Height))
	switch best.Hdr {
	case "dolbyvision":
		label += " Dolby Vision"
	case "hdr10":
		label += " HDR10"
	case "hlg":
		label += " HLG"
	}
	return label
}

// replaceTarget finds the movie or episode a present file belongs to.
func replaceTarget(ctx context.Context, q *db.Queries, fileID int64) (integrations.ReplaceTarget, error) {
	r, err := q.GetFileItem(ctx, fileID)
	if err != nil {
		return integrations.ReplaceTarget{}, notFound(err, "file not found")
	}
	if r.Status != "present" {
		return integrations.ReplaceTarget{}, huma.Error404NotFound("file not found")
	}
	lib, err := q.GetLibrary(ctx, r.LibraryID)
	if err != nil {
		return integrations.ReplaceTarget{}, err
	}
	t := integrations.ReplaceTarget{LibraryID: lib.ID, LibraryPath: lib.Path, Path: r.Path}
	switch {
	case r.MovieID != nil:
		t.Type, t.TMDBID, t.Title = "movie", int(deref(r.MovieTmdbID)), deref(r.MovieTitle)
	case r.ShowID != nil:
		t.Type, t.TMDBID, t.Title = "episode", int(deref(r.ShowTmdbID)), deref(r.ShowTitle)
	default:
		return t, huma.Error422UnprocessableEntity("this file isn't matched to a movie or an episode")
	}
	return t, nil
}
