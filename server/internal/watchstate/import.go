package watchstate

import (
	"context"
	"database/sql"
	"errors"

	"watchmymedia/server/internal/db"
)

// Imports apply another server's watched marks under the rules in
// PLAN-PLEX-SYNC.md ("Additive merge rules"): they fill items the user has
// never decided here and change nothing else. Plex runs use ImportChecked so
// their checkpoint and authorization checks commit with the marks. Previews use
// Evaluate, which applies the same merge rules without writing.

// Identity is what an item must still be when an import reaches it, so a
// mark meant for a deleted or rematched item cannot land on whatever now
// has its ID.
type Identity struct {
	LibraryID int64
	// TMDBID is the movie's, or the episode's show's.
	TMDBID int64
	// Season and Episode number an episode; zero for movies.
	Season, Episode int64
}

// Target is a local item an import would mark watched.
type Target struct {
	ItemType string
	ItemID   int64
	Identity Identity
}

// Outcome is what an import does, or would do, to one item.
type Outcome string

const (
	// Applied: marked watched (or, from Evaluate, would be).
	Applied Outcome = "applied"
	// AlreadyWatched: already watched here, by the user or an earlier
	// import. Nothing changes, not even its time.
	AlreadyWatched Outcome = "already_watched"
	// LocalKept: the user's own choice decides the item (an unfinished play,
	// a rewatch, an unwatch, or a play that has only started), so it stays.
	LocalKept Outcome = "local_kept"
	// Unavailable: the item is gone or is no longer the identity the import
	// matched.
	Unavailable Outcome = "unavailable"
)

// ErrNoUser is returned when the user an import is for no longer exists.
var ErrNoUser = errors.New("watchstate: user does not exist")

// Evaluate says what Import would do to each target now, without writing.
func (s *Service) Evaluate(ctx context.Context, userID int64, targets []Target) ([]Outcome, error) {
	if _, err := s.q.GetUser(ctx, userID); errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNoUser
	} else if err != nil {
		return nil, err
	}
	out := make([]Outcome, len(targets))
	for i, t := range targets {
		o, err := evaluate(ctx, s.q, userID, t)
		if err != nil {
			return nil, err
		}
		out[i] = o
	}
	return out, nil
}

// Import marks the targets watched for the user where nothing here has
// decided them, recording run as the owner of each mark it makes. Everything
// is checked again inside one transaction, so a local change, rematch or
// deletion that commits first wins, and one that commits after overrides the
// import as any local change does. Watch.changed goes out after the commit.
func (s *Service) Import(ctx context.Context, userID, run int64, targets []Target) ([]Outcome, error) {
	return s.ImportChecked(ctx, userID, run, targets, nil, nil)
}

// ImportChecked commits an importer checkpoint with its marks. The validator
// runs under the same write lock, so cancellation, generation, identity and
// lease checks cannot go stale between authorization and the watch writes.
func (s *Service) ImportChecked(ctx context.Context, userID, run int64, targets []Target, check func(*db.Queries) error, record func(*db.Queries, []Outcome) error) ([]Outcome, error) {
	if run <= 0 {
		return nil, errors.New("watchstate: an import needs its run")
	}
	out := make([]Outcome, len(targets))
	applied := map[string][]int64{}
	err := s.change(ctx, func(q *db.Queries, rev int64) error {
		if check != nil {
			if err := check(q); err != nil {
				return err
			}
		}
		if _, err := q.GetUser(ctx, userID); errors.Is(err, sql.ErrNoRows) {
			return ErrNoUser
		} else if err != nil {
			return err
		}
		for i, t := range targets {
			o, err := evaluate(ctx, q, userID, t)
			if err != nil {
				return err
			}
			out[i] = o
			if o != Applied {
				continue
			}
			if err := q.InsertImportedWatch(ctx, db.InsertImportedWatchParams{UserID: userID, ItemType: t.ItemType, ItemID: t.ItemID}); err != nil {
				return err
			}
			if err := q.InsertImportWatchGuard(ctx, db.InsertImportWatchGuardParams{UserID: userID, ItemType: t.ItemType, ItemID: t.ItemID, Revision: rev, ImportRun: &run}); err != nil {
				return err
			}
			applied[t.ItemType] = append(applied[t.ItemType], t.ItemID)
		}
		if record != nil {
			return record(q, out)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	for _, itemType := range []string{Movie, Episode} {
		if len(applied[itemType]) > 0 {
			s.publish(userID, itemType, applied[itemType])
		}
	}
	return out, nil
}

// evaluate applies the merge rules to one target.
func evaluate(ctx context.Context, q *db.Queries, userID int64, t Target) (Outcome, error) {
	ok, err := identityHolds(ctx, q, t)
	if err != nil || !ok {
		return Unavailable, err
	}
	guard, state, err := current(ctx, q, userID, t.ItemType, t.ItemID)
	switch {
	case err != nil:
		return "", err
	case state != nil && state.Completed == 1:
		return AlreadyWatched, nil
	case guard != nil || state != nil:
		return LocalKept, nil
	}
	return Applied, nil
}

// identityHolds checks that the target's ID still names the item the
// import matched.
func identityHolds(ctx context.Context, q *db.Queries, t Target) (bool, error) {
	want := t.Identity
	switch t.ItemType {
	case Movie:
		m, err := q.GetMovie(ctx, t.ItemID)
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		return m.LibraryID == want.LibraryID && m.TmdbID == want.TMDBID && want.Season == 0 && want.Episode == 0, nil
	case Episode:
		e, err := q.GetEpisodeIdentity(ctx, t.ItemID)
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		if err != nil {
			return false, err
		}
		return e.LibraryID == want.LibraryID && e.TmdbID == want.TMDBID && e.SeasonNumber == want.Season && e.EpisodeNumber == want.Episode, nil
	}
	return false, nil
}

// UndoTarget is a mark whose exact import-owned revision an admin previewed.
type UndoTarget struct {
	Target   Target
	Revision int64
}

// UndoImport removes only unchanged marks owned by this run and records a local
// unwatch guard, so recurring imports cannot put the mark straight back.
func (s *Service) UndoImport(ctx context.Context, userID, run int64, targets []UndoTarget, check func(*db.Queries) error) (int, int, error) {
	return s.UndoImports(ctx, run, []UndoRequest{{UserID: userID, Targets: targets}}, check)
}

// UndoRequest is one person's exact previewed import revisions.
type UndoRequest struct {
	UserID  int64
	Targets []UndoTarget
}

// UndoImports commits the complete multi-user undo atomically. A failure leaves
// every mark unchanged; notifications are sent only after the shared commit.
func (s *Service) UndoImports(ctx context.Context, run int64, requests []UndoRequest, check func(*db.Queries) error) (int, int, error) {
	undone, kept := 0, 0
	changed := map[int64]map[string][]int64{}
	err := s.change(ctx, func(q *db.Queries, rev int64) error {
		if check != nil {
			if err := check(q); err != nil {
				return err
			}
		}
		for _, request := range requests {
			userID := request.UserID
			for _, v := range request.Targets {
				holds, err := identityHolds(ctx, q, v.Target)
				if err != nil {
					return err
				}
				g, w, err := current(ctx, q, userID, v.Target.ItemType, v.Target.ItemID)
				if err != nil {
					return err
				}
				if !holds || g == nil || w == nil || g.Owner != "import" || g.ImportRun == nil || *g.ImportRun != run || g.Revision != v.Revision || w.Completed != 1 {
					kept++
					continue
				}
				if err := q.DeleteWatchState(ctx, db.DeleteWatchStateParams{UserID: userID, ItemType: v.Target.ItemType, ItemID: v.Target.ItemID}); err != nil {
					return err
				}
				if err := claim(ctx, q, rev, userID, v.Target.ItemType, []int64{v.Target.ItemID}); err != nil {
					return err
				}
				if changed[userID] == nil {
					changed[userID] = map[string][]int64{}
				}
				changed[userID][v.Target.ItemType] = append(changed[userID][v.Target.ItemType], v.Target.ItemID)
				undone++
			}
		}
		return nil
	})
	if err != nil {
		return 0, 0, err
	}
	for user, kinds := range changed {
		for kind, ids := range kinds {
			s.publish(user, kind, ids)
		}
	}
	return undone, kept, nil
}
