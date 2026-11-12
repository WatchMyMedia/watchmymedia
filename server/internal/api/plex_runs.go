package api

import (
	"context"
	"net/http"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/plexsync"
)

type PlexRunInput struct {
	ID    int64 `path:"id"`
	RunID int64 `path:"runId" minimum:"1"`
}
type StartPlexRunInput struct {
	ID   int64 `path:"id"`
	Body plexsync.PlexRunStart
}
type PlexRunOutput struct{ Body plexsync.PlexRun }
type PlexRunList = plexsync.PlexRunPage
type PlexRunListInput struct {
	ID     int64 `path:"id"`
	Before int64 `query:"before" minimum:"0"`
	Limit  int   `query:"limit" minimum:"1" maximum:"100" default:"30"`
}
type PlexRunListOutput struct{ Body PlexRunList }
type PlexSyncInput struct {
	ID   int64 `path:"id"`
	Body plexsync.PlexSyncUpdate
}
type PlexExclusionsOutput struct{ Body plexsync.PlexExclusions }
type PlexExclusionsInput struct {
	ID   int64 `path:"id"`
	Body plexsync.PlexExclusions
}
type PlexUndoPreviewOutput struct{ Body plexsync.PlexUndoPreview }
type PlexUndoInput struct {
	ID    int64 `path:"id"`
	RunID int64 `path:"runId"`
	Body  struct {
		PreviewID string `json:"previewId" maxLength:"64"`
	}
}
type PlexUndoOutput struct{ Body plexsync.PlexUndoResult }
type PlexRebaselineInput struct {
	ID   int64 `path:"id"`
	Body struct {
		Generation int64 `json:"generation"`
	}
}
type PlexHistoryInput struct {
	UserID int64 `query:"userId" doc:"Omit for your own history; another account requires an admin on an allowed admin network."`
	Limit  int   `query:"limit" minimum:"1" maximum:"200" default:"50"`
	Before int64 `query:"before" minimum:"0"`
}
type PlexHistoryOutput struct{ Body plexsync.PlexHistoryList }
type PlexHistoryDeleteInput struct {
	ID     int64 `path:"historyId"`
	UserID int64 `query:"userId"`
}
type PlexHistoryClearInput struct {
	UserID int64 `query:"userId"`
}

func registerPlexRuns(h huma.API, deps Deps) {
	const base = Prefix + "/integrations/plex/connections/{id}"
	op := func(id, method, path, summary string) huma.Operation {
		return adminOp(h, huma.Operation{OperationID: id, Method: method, Path: base + path, Summary: summary, Tags: []string{"plex"}})
	}
	start := op("startPlexRun", http.MethodPost, "/runs", "Import the exact approved preview, optionally enabling recurring sync")
	start.DefaultStatus = http.StatusAccepted
	start.Description = "Consumes a fresh completed preview made in this admin session. Rechecks mappings, current Plex state and local intent. Returns promptly with a durable run; connecting alone never imports. An incomplete preview cannot be applied."
	huma.Register(h, start, func(ctx context.Context, in *StartPlexRunInput) (*PlexRunOutput, error) {
		s, w, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.StartRun(ctx, in.ID, w, in.Body)
		return &PlexRunOutput{Body: v}, plexError(e)
	})
	huma.Register(h, op("listPlexRuns", http.MethodGet, "/runs", "List recent persisted import runs"), func(ctx context.Context, in *PlexRunListInput) (*PlexRunListOutput, error) {
		s, _, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.RunPage(ctx, in.ID, in.Limit, in.Before)
		return &PlexRunListOutput{Body: v}, plexError(e)
	})
	huma.Register(h, op("getPlexRun", http.MethodGet, "/runs/{runId}", "Read committed progress and history coverage"), func(ctx context.Context, in *PlexRunInput) (*PlexRunOutput, error) {
		s, _, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.GetRun(ctx, in.ID, in.RunID)
		return &PlexRunOutput{Body: v}, plexError(e)
	})
	huma.Register(h, op("cancelPlexRun", http.MethodPost, "/runs/{runId}/cancel", "Cancel new import work while keeping committed changes"), func(ctx context.Context, in *PlexRunInput) (*PlexRunOutput, error) {
		s, _, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.CancelRun(ctx, in.ID, in.RunID)
		return &PlexRunOutput{Body: v}, plexError(e)
	})
	huma.Register(h, op("retryPlexRun", http.MethodPost, "/runs/{runId}/retry", "Retry the same import and its committed checkpoints"), func(ctx context.Context, in *PlexRunInput) (*PlexRunOutput, error) {
		s, _, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.RetryRun(ctx, in.ID, in.RunID)
		return &PlexRunOutput{Body: v}, plexError(e)
	})
	huma.Register(h, op("updatePlexSync", http.MethodPut, "/sync", "Enable 15-minute sync or pause and cancel pending work"), func(ctx context.Context, in *PlexSyncInput) (*PlexConnectionOutput, error) {
		s, w, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.SetSyncFor(ctx, in.ID, w, in.Body)
		return &PlexConnectionOutput{Status: http.StatusOK, Body: v}, plexError(e)
	})
	huma.Register(h, op("listPlexExclusions", http.MethodGet, "/exclusions", "List watched marks excluded from imports"), func(ctx context.Context, in *PlexConnectionInput) (*PlexExclusionsOutput, error) {
		s, _, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.Exclusions(ctx, in.ID)
		return &PlexExclusionsOutput{Body: v}, plexError(e)
	})
	huma.Register(h, op("savePlexExclusions", http.MethodPut, "/exclusions", "Replace import exclusions and invalidate earlier previews"), func(ctx context.Context, in *PlexExclusionsInput) (*PlexExclusionsOutput, error) {
		s, _, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.SaveExclusions(ctx, in.ID, in.Body)
		return &PlexExclusionsOutput{Body: v}, plexError(e)
	})
	huma.Register(h, op("previewPlexUndo", http.MethodPost, "/runs/{runId}/undo-previews", "Preview undoing only unchanged watch marks from this run"), func(ctx context.Context, in *PlexRunInput) (*PlexUndoPreviewOutput, error) {
		s, w, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.PreviewUndo(ctx, in.ID, in.RunID, w)
		return &PlexUndoPreviewOutput{Body: v}, plexError(e)
	})
	huma.Register(h, op("undoPlexRun", http.MethodPost, "/runs/{runId}/undo", "Pause sync and undo unchanged previewed watch marks"), func(ctx context.Context, in *PlexUndoInput) (*PlexUndoOutput, error) {
		s, w, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.Undo(ctx, in.ID, in.RunID, w, in.Body.PreviewID)
		return &PlexUndoOutput{Body: v}, plexError(e)
	})
	rebaseline := op("rebaselinePlex", http.MethodPost, "/rebaseline", "Rebaseline source identity after a known Plex restore")
	rebaseline.Description = "Explicit recovery after source-key identity drift. Pauses and cancels work, advances the source epoch, preserves already-imported marks/history and requires a new preview. Historical events from a new epoch may overlap earlier records."
	huma.Register(h, rebaseline, func(ctx context.Context, in *PlexRebaselineInput) (*PlexConnectionOutput, error) {
		s, _, e := plexAdmin(ctx, deps)
		if e != nil {
			return nil, e
		}
		v, e := s.Rebaseline(ctx, in.ID, in.Body.Generation)
		return &PlexConnectionOutput{Status: http.StatusOK, Body: v}, plexError(e)
	})
	huma.Register(h, huma.Operation{OperationID: "listPlexHistory", Method: http.MethodGet, Path: Prefix + "/plex-history", Summary: "Read imported Plex history separately from WatchMyMedia playback", Tags: []string{"plex"}}, func(ctx context.Context, in *PlexHistoryInput) (*PlexHistoryOutput, error) {
		user, e := plexHistoryUser(ctx, deps, in.UserID)
		if e != nil {
			return nil, e
		}
		v, e := deps.Plex.History(ctx, user, in.Limit, in.Before)
		return &PlexHistoryOutput{Body: v}, plexError(e)
	})
	huma.Register(h, huma.Operation{OperationID: "deletePlexHistory", Method: http.MethodDelete, Path: Prefix + "/plex-history/{historyId}", Summary: "Delete and suppress an imported event", DefaultStatus: http.StatusNoContent, Tags: []string{"plex"}}, func(ctx context.Context, in *PlexHistoryDeleteInput) (*struct{}, error) {
		user, e := plexHistoryUser(ctx, deps, in.UserID)
		if e != nil {
			return nil, e
		}
		return nil, plexError(deps.Plex.DeleteHistory(ctx, user, in.ID))
	})
	huma.Register(h, huma.Operation{OperationID: "clearPlexHistory", Method: http.MethodDelete, Path: Prefix + "/plex-history", Summary: "Clear imported history and prevent earlier events returning", DefaultStatus: http.StatusNoContent, Tags: []string{"plex"}}, func(ctx context.Context, in *PlexHistoryClearInput) (*struct{}, error) {
		user, e := plexHistoryUser(ctx, deps, in.UserID)
		if e != nil {
			return nil, e
		}
		return nil, plexError(deps.Plex.ClearHistory(ctx, user))
	})
}
func plexHistoryUser(ctx context.Context, deps Deps, requested int64) (int64, error) {
	u, err := currentUser(ctx)
	if err != nil {
		return 0, err
	}
	if deps.Plex == nil {
		return 0, huma.Error404NotFound("Plex connections are off")
	}
	if requested <= 0 || requested == u.ID {
		return u.ID, nil
	}
	if _, err := requireAdmin(ctx); err != nil {
		return 0, err
	}
	return requested, nil
}
