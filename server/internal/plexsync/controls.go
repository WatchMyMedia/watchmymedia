package plexsync

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/watchstate"
)

type PlexExclusion struct {
	UserID   int64  `json:"userId" minimum:"1"`
	ItemType string `json:"itemType" enum:"movie,episode"`
	ItemID   int64  `json:"itemId" minimum:"1"`
	Title    string `json:"title,omitempty" required:"false"`
	Show     string `json:"show,omitempty" required:"false"`
	Season   int64  `json:"season,omitempty" required:"false"`
	Episode  int64  `json:"episode,omitempty" required:"false"`
	UserName string `json:"userName,omitempty" required:"false"`
}
type PlexExclusions struct {
	Generation int64           `json:"generation"`
	Items      []PlexExclusion `json:"items" nullable:"false" maxItems:"10000"`
}

func (s *Service) Exclusions(ctx context.Context, id int64) (PlexExclusions, error) {
	c, err := s.connection(ctx, id)
	if err != nil {
		return PlexExclusions{}, err
	}
	rows, err := s.q.ListPlexExclusionDisplay(ctx, id)
	out := PlexExclusions{Generation: c.Generation, Items: []PlexExclusion{}}
	for _, r := range rows {
		out.Items = append(out.Items, PlexExclusion{UserID: r.UserID, ItemType: r.ItemType, ItemID: r.ItemID, Title: r.Title, Show: r.ShowTitle, Season: r.Season, Episode: r.Episode, UserName: r.UserName})
	}
	return out, err
}
func (s *Service) SaveExclusions(ctx context.Context, id int64, in PlexExclusions) (PlexExclusions, error) {
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		c, err := q.GetPlexConnection(ctx, id)
		if err != nil {
			return err
		}
		if c.Generation != in.Generation {
			return fail(Conflict, "stale_generation")
		}
		users, err := q.ListPlexUserLinks(ctx, id)
		if err != nil {
			return err
		}
		linked := map[int64]bool{}
		for _, u := range users {
			linked[u.UserID] = true
		}
		libs, err := q.ListPlexLibraryLinks(ctx, id)
		if err != nil {
			return err
		}
		libraries := map[int64]bool{}
		for _, l := range libs {
			libraries[l.LibraryID] = true
		}
		for _, v := range in.Items {
			if !linked[v.UserID] {
				return fail(Invalid, "unknown_user")
			}
			target, err := targetFor(ctx, q, v.ItemType, v.ItemID)
			if err != nil || !libraries[target.Identity.LibraryID] {
				return fail(Invalid, "item_not_in_mapping")
			}
		}
		if err := q.DeletePlexExclusions(ctx, id); err != nil {
			return err
		}
		for _, v := range in.Items {
			if err := q.InsertPlexExclusion(ctx, db.InsertPlexExclusionParams{ConnectionID: id, UserID: v.UserID, ItemType: v.ItemType, ItemID: v.ItemID}); err != nil {
				return err
			}
		}
		_, err = q.AdvancePlexGeneration(ctx, db.AdvancePlexGenerationParams{ID: id, Generation: in.Generation})
		return err
	})
	if err != nil {
		return PlexExclusions{}, err
	}
	s.dropPreview(id)
	s.publishAdmins(ctx, id)
	return s.Exclusions(ctx, id)
}
func targetFor(ctx context.Context, q *db.Queries, kind string, id int64) (watchstate.Target, error) {
	t := watchstate.Target{ItemType: kind, ItemID: id}
	switch kind {
	case watchstate.Movie:
		m, err := q.GetMovie(ctx, id)
		if err != nil {
			return t, err
		}
		t.Identity = watchstate.Identity{LibraryID: m.LibraryID, TMDBID: m.TmdbID}
	case watchstate.Episode:
		e, err := q.GetEpisodeIdentity(ctx, id)
		if err != nil {
			return t, err
		}
		t.Identity = watchstate.Identity{LibraryID: e.LibraryID, TMDBID: e.TmdbID, Season: e.SeasonNumber, Episode: e.EpisodeNumber}
	default:
		return t, fail(Invalid, "invalid_item_type")
	}
	return t, nil
}

type PlexUndoPreview struct {
	ID        string    `json:"id"`
	RunID     int64     `json:"runId"`
	ExpiresAt time.Time `json:"expiresAt"`
	WillUndo  int       `json:"willUndo"`
	LocalKept int       `json:"localKept"`
}
type PlexUndoResult struct {
	Undone    int `json:"undone"`
	LocalKept int `json:"localKept"`
}
type undoPreview struct {
	view       PlexUndoPreview
	who        Requester
	generation int64
	targets    map[int64][]watchstate.UndoTarget
}

func (s *Service) PreviewUndo(ctx context.Context, connection, run int64, who Requester) (PlexUndoPreview, error) {
	r, err := s.run(ctx, connection, run)
	if err != nil {
		return PlexUndoPreview{}, err
	}
	if r.Status == "running" || r.Status == "queued" {
		return PlexUndoPreview{}, fail(Conflict, "run_active")
	}
	c, err := s.connection(ctx, connection)
	if err != nil {
		return PlexUndoPreview{}, err
	}
	rows, err := s.q.ListRunWatchGuards(ctx, &run)
	if err != nil {
		return PlexUndoPreview{}, err
	}
	key, err := randomHex(16)
	if err != nil {
		return PlexUndoPreview{}, err
	}
	p := undoPreview{view: PlexUndoPreview{ID: key, RunID: run, ExpiresAt: s.now().Add(previewTTL)}, who: who, generation: c.Generation, targets: map[int64][]watchstate.UndoTarget{}}
	for _, g := range rows {
		t, err := targetFor(ctx, s.q, g.ItemType, g.ItemID)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return PlexUndoPreview{}, err
		}
		p.targets[g.UserID] = append(p.targets[g.UserID], watchstate.UndoTarget{Target: t, Revision: g.Revision})
		p.view.WillUndo++
	}
	p.view.LocalKept = max(0, int(r.Marked)-p.view.WillUndo)
	s.mu.Lock()
	defer s.mu.Unlock()
	for id, v := range s.undoPreviews {
		if !v.view.ExpiresAt.After(s.now()) {
			delete(s.undoPreviews, id)
		}
	}
	if len(s.undoPreviews) >= 8 {
		return PlexUndoPreview{}, fail(Conflict, "undo_preview_limit")
	}
	s.undoPreviews[key] = p
	return p.view, nil
}
func (s *Service) Undo(ctx context.Context, connection, run int64, who Requester, previewID string) (PlexUndoResult, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, ok := s.undoPreviews[previewID]
	if !ok || p.view.RunID != run || p.who != who || !p.view.ExpiresAt.After(s.now()) {
		return PlexUndoResult{}, fail(Conflict, "undo_preview_stale")
	}
	if _, err := s.run(ctx, connection, run); err != nil {
		return PlexUndoResult{}, err
	}
	requests := make([]watchstate.UndoRequest, 0, len(p.targets))
	for user, targets := range p.targets {
		requests = append(requests, watchstate.UndoRequest{UserID: user, Targets: targets})
	}
	undone, kept, err := s.watch.UndoImports(ctx, run, requests, func(q *db.Queries) error {
		c, err := q.GetPlexConnection(ctx, connection)
		if err != nil {
			return err
		}
		if c.Generation != p.generation || c.AuthorizedBy == nil {
			return fail(Conflict, "undo_preview_stale")
		}
		actor, err := q.GetUser(ctx, who.UserID)
		if err != nil || actor.IsAdmin != 1 {
			return fail(Conflict, "needs_authorization")
		}
		n, err := q.SetPlexSync(ctx, db.SetPlexSyncParams{SyncEnabled: 0, NextSyncAt: stamp(s.now().Add(pollInterval)), ID: connection, Generation: p.generation})
		if err != nil {
			return err
		}
		if n == 0 {
			return fail(Conflict, "undo_preview_stale")
		}
		rows, err := q.ListActivePlexRuns(ctx, &connection)
		if err != nil {
			return err
		}
		for _, v := range rows {
			if _, err := q.CancelPlexRun(ctx, db.CancelPlexRunParams{ID: v.ID, FinishedAt: new(stamp(s.now()))}); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return PlexUndoResult{}, err
	}
	delete(s.undoPreviews, previewID)
	s.publishAdmins(ctx, connection)
	return PlexUndoResult{Undone: undone, LocalKept: kept + p.view.LocalKept}, nil
}
func (s *Service) Rebaseline(ctx context.Context, id, generation int64) (PlexConnection, error) {
	c, err := s.connection(ctx, id)
	if err != nil {
		return PlexConnection{}, err
	}
	if c.Generation != generation {
		return PlexConnection{}, fail(Conflict, "stale_generation")
	}
	epoch, err := randomHex(16)
	if err != nil {
		return PlexConnection{}, err
	}
	if _, err := s.SetSync(ctx, id, PlexSyncUpdate{Generation: generation, Enabled: false}); err != nil {
		return PlexConnection{}, err
	}
	n, err := s.q.RebaselinePlexConnection(ctx, db.RebaselinePlexConnectionParams{SourceEpoch: epoch, ID: id, Generation: generation})
	if err != nil {
		return PlexConnection{}, err
	}
	if n == 0 {
		return PlexConnection{}, fail(Conflict, "stale_generation")
	}
	s.dropPreview(id)
	c, err = s.connection(ctx, id)
	if err != nil {
		return PlexConnection{}, err
	}
	return s.view(ctx, c)
}
