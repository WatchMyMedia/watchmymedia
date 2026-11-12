// Package watchstate makes every change to people's watch state: progress
// from players, watched and unwatched marks on items, seasons and shows,
// playback starts, rematches and, later, imports from another server
// (PLAN-PLEX-SYNC.md). Each change runs in one transaction that takes the
// next revision and leaves a guard per user and item saying whether the
// user's own choice or an import decided it. An import may only fill an item
// with no guard, so a local mark, unfinished play, rewatch or unwatch always
// survives it. An unwatch still deletes the watch_state row, as the API has
// always shown it, but keeps its guard.
//
// The service keeps no state of its own: instances over one database share
// its guards, and SQLite's write lock orders their changes.
package watchstate

import (
	"context"
	"database/sql"
	"errors"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
)

// Item types, as watch_state stores them.
const (
	Movie   = "movie"
	Episode = "episode"
)

// Service changes watch state. Use New.
type Service struct {
	q   *db.Queries
	bus *events.Bus
}

// New returns a service over q. Changes are announced on bus after they
// commit; a nil bus announces nothing.
func New(q *db.Queries, bus *events.Bus) *Service {
	return &Service{q: q, bus: bus}
}

// WatchChanged tells a user's screens that their progress or watched marks
// changed, so lists such as Continue watching can refetch.
type WatchChanged struct {
	ItemType string  `json:"itemType" enum:"movie,episode"`
	ItemIDs  []int64 `json:"itemIds" nullable:"false" doc:"The movies or episodes that changed."`
}

// publish sends watch.changed to the user alone. Call it after the change
// commits.
func (s *Service) publish(userID int64, itemType string, ids []int64) {
	if s.bus == nil {
		return
	}
	s.bus.Publish(events.Event{Type: "watch.changed", Data: WatchChanged{ItemType: itemType, ItemIDs: ids}, To: &events.Audience{UserID: userID, Private: true}})
}

// change runs fn in a transaction whose first statement takes the next
// revision. Transactions begin IMMEDIATE (db.Open), so it holds the
// database's write lock from the start: what fn reads cannot change under
// it before it commits.
func (s *Service) change(ctx context.Context, fn func(q *db.Queries, rev int64) error) error {
	return s.q.Transaction(ctx, func(q *db.Queries) error {
		rev, err := q.NextWatchRevision(ctx)
		if err != nil {
			return err
		}
		return fn(q, rev)
	})
}

// claim makes the user's own choice decide each item.
func claim(ctx context.Context, q *db.Queries, rev, userID int64, itemType string, ids []int64) error {
	for _, id := range ids {
		if err := q.ClaimWatchGuard(ctx, db.ClaimWatchGuardParams{UserID: userID, ItemType: itemType, ItemID: id, Revision: rev}); err != nil {
			return err
		}
	}
	return nil
}

// Progress is a player's report of its position in a file.
type Progress struct {
	UserID int64
	// ItemType and ItemIDs are the file's movie, or every episode it holds.
	ItemType   string
	ItemIDs    []int64
	FileID     int64
	PositionMs int64
	DurationMs int64
	// Completed marks the items watched, at position 0.
	Completed bool
	// Playing is a heartbeat from a running play: it announces a change only
	// when an item is started or finished, not at every report.
	Playing bool
}

// Report saves a position on every item of the file and returns the state
// saved on the last one. Lists change when an item is started, finished or
// left (paused, stopped, or a report from outside a play), so only those
// reports announce a change.
func (s *Service) Report(ctx context.Context, p Progress) (db.WatchState, error) {
	if len(p.ItemIDs) == 0 {
		return db.WatchState{}, errors.New("watchstate: a report needs an item")
	}
	completed := db.BoolInt(p.Completed)
	var last db.WatchState
	notify := !p.Playing
	err := s.change(ctx, func(q *db.Queries, rev int64) error {
		prev, err := q.GetWatchState(ctx, db.GetWatchStateParams{UserID: p.UserID, ItemType: p.ItemType, ItemID: p.ItemIDs[0]})
		switch {
		case errors.Is(err, sql.ErrNoRows):
			notify = true
		case err != nil:
			return err
		case prev.Completed != completed:
			notify = true
		}
		fileID := p.FileID
		for _, id := range p.ItemIDs {
			last, err = q.UpsertWatchState(ctx, db.UpsertWatchStateParams{
				UserID: p.UserID, ItemType: p.ItemType, ItemID: id, MediaFileID: &fileID, PositionMs: p.PositionMs, DurationMs: p.DurationMs, Completed: completed,
			})
			if err != nil {
				return err
			}
		}
		return claim(ctx, q, rev, p.UserID, p.ItemType, p.ItemIDs)
	})
	if err != nil {
		return db.WatchState{}, err
	}
	if notify {
		s.publish(p.UserID, p.ItemType, p.ItemIDs)
	}
	return last, nil
}

// Claim records that the user started playing the items, before any
// progress is saved: an import arriving before the first report, or with
// none at all, leaves them alone.
func (s *Service) Claim(ctx context.Context, userID int64, itemType string, ids []int64) error {
	if len(ids) == 0 {
		return nil
	}
	return s.change(ctx, func(q *db.Queries, rev int64) error {
		return claim(ctx, q, rev, userID, itemType, ids)
	})
}

// MarkWatched marks an item watched and clears its position.
func (s *Service) MarkWatched(ctx context.Context, userID int64, itemType string, id int64) error {
	err := s.change(ctx, func(q *db.Queries, rev int64) error {
		if _, err := q.UpsertWatchState(ctx, db.UpsertWatchStateParams{UserID: userID, ItemType: itemType, ItemID: id, Completed: 1}); err != nil {
			return err
		}
		return claim(ctx, q, rev, userID, itemType, []int64{id})
	})
	if err == nil {
		s.publish(userID, itemType, []int64{id})
	}
	return err
}

// MarkUnwatched clears an item's progress and watched mark. Its guard stays,
// so no import marks it watched again.
func (s *Service) MarkUnwatched(ctx context.Context, userID int64, itemType string, id int64) error {
	err := s.change(ctx, func(q *db.Queries, rev int64) error {
		if err := q.DeleteWatchState(ctx, db.DeleteWatchStateParams{UserID: userID, ItemType: itemType, ItemID: id}); err != nil {
			return err
		}
		return claim(ctx, q, rev, userID, itemType, []int64{id})
	})
	if err == nil {
		s.publish(userID, itemType, []int64{id})
	}
	return err
}

// MarkSeason marks every episode of a season watched, or clears their
// marks and progress. Either way every episode of the season is the user's
// choice from now on, including those that had no state.
func (s *Service) MarkSeason(ctx context.Context, userID, seasonID int64, watched bool) error {
	ids := []int64{}
	err := s.change(ctx, func(q *db.Queries, rev int64) error {
		var err error
		if watched {
			err = q.MarkSeasonWatched(ctx, db.MarkSeasonWatchedParams{UserID: userID, SeasonID: seasonID})
		} else {
			err = q.UnmarkSeasonWatched(ctx, db.UnmarkSeasonWatchedParams{UserID: userID, SeasonID: seasonID})
		}
		if err != nil {
			return err
		}
		if err := q.ClaimSeasonWatchGuards(ctx, db.ClaimSeasonWatchGuardsParams{UserID: userID, Revision: rev, SeasonID: seasonID}); err != nil {
			return err
		}
		eps, err := q.ListSeasonEpisodes(ctx, seasonID)
		if err != nil {
			return err
		}
		for _, e := range eps {
			ids = append(ids, e.ID)
		}
		return nil
	})
	if err == nil {
		s.publish(userID, Episode, ids)
	}
	return err
}

// MarkShow marks every episode of a show watched, specials included, or
// clears their marks and progress, as MarkSeason does for each season. One
// watch.changed lists them all.
func (s *Service) MarkShow(ctx context.Context, userID, showID int64, watched bool) error {
	var ids []int64
	err := s.change(ctx, func(q *db.Queries, rev int64) error {
		var err error
		if watched {
			err = q.MarkShowWatched(ctx, db.MarkShowWatchedParams{UserID: userID, ShowID: showID})
		} else {
			err = q.UnmarkShowWatched(ctx, db.UnmarkShowWatchedParams{UserID: userID, ShowID: showID})
		}
		if err != nil {
			return err
		}
		if err := q.ClaimShowWatchGuards(ctx, db.ClaimShowWatchGuardsParams{UserID: userID, Revision: rev, ShowID: showID}); err != nil {
			return err
		}
		ids, err = q.ListShowEpisodeIDs(ctx, showID)
		return err
	})
	if err == nil {
		s.publish(userID, Episode, ids)
	}
	return err
}

// Move is one item's watch state moving to the item that replaces it in a
// rematch. To 0 means nothing replaces it and its state is cleared.
type Move struct {
	From, To int64
}

// Move carries every user's state and guard on each From item to its To
// item, then clears From. Where To already holds the user's own choice
// (state or a local guard), To keeps it, as rematches always kept the
// destination's state. Otherwise the user's state on From replaces To's,
// and a guard-only unwatch on From clears To. What arrives is the user's
// own from then on: an import's ownership does not survive a rematch, since
// it was for the old item's identity.
func (s *Service) Move(ctx context.Context, itemType string, moves []Move) error {
	if len(moves) == 0 {
		return nil
	}
	return s.change(ctx, func(q *db.Queries, rev int64) error {
		for _, m := range moves {
			if m.From == m.To {
				continue
			}
			if m.To != 0 {
				if err := q.MovePlexExclusions(ctx, db.MovePlexExclusionsParams{ToID: m.To, ItemType: itemType, FromID: m.From}); err != nil {
					return err
				}
				users, err := q.ListItemWatchUsers(ctx, db.ListItemWatchUsersParams{ItemType: itemType, ItemID: m.From})
				if err != nil {
					return err
				}
				for _, u := range users {
					if err := move(ctx, q, rev, u, itemType, m); err != nil {
						return err
					}
				}
			}
			if err := q.DeleteWatchStatesForItem(ctx, db.DeleteWatchStatesForItemParams{ItemType: itemType, ItemID: m.From}); err != nil {
				return err
			}
			if err := q.DeleteWatchGuardsForItem(ctx, db.DeleteWatchGuardsForItemParams{ItemType: itemType, ItemID: m.From}); err != nil {
				return err
			}
		}
		return nil
	})
}

func move(ctx context.Context, q *db.Queries, rev, userID int64, itemType string, m Move) error {
	src, srcState, err := current(ctx, q, userID, itemType, m.From)
	if err != nil {
		return err
	}
	dst, dstState, err := current(ctx, q, userID, itemType, m.To)
	if err != nil {
		return err
	}
	if local(dst, dstState) {
		return nil
	}
	switch {
	case srcState != nil:
		if err := q.ReplaceWatchState(ctx, db.ReplaceWatchStateParams{
			UserID: userID, ItemType: itemType, ItemID: m.To, MediaFileID: srcState.MediaFileID,
			PositionMs: srcState.PositionMs, DurationMs: srcState.DurationMs, Completed: srcState.Completed, UpdatedAt: srcState.UpdatedAt,
		}); err != nil {
			return err
		}
	case src != nil && src.Owner == ownerLocal:
		if err := q.DeleteWatchState(ctx, db.DeleteWatchStateParams{UserID: userID, ItemType: itemType, ItemID: m.To}); err != nil {
			return err
		}
	default:
		return nil
	}
	return claim(ctx, q, rev, userID, itemType, []int64{m.To})
}

// ownerLocal is a guard's owner when the user's own choice decides the item.
const ownerLocal = "local"

// current reads a user's guard and state on an item; either may be nil.
func current(ctx context.Context, q *db.Queries, userID int64, itemType string, id int64) (*db.WatchGuard, *db.WatchState, error) {
	g, err := q.GetWatchGuard(ctx, db.GetWatchGuardParams{UserID: userID, ItemType: itemType, ItemID: id})
	guard := &g
	if errors.Is(err, sql.ErrNoRows) {
		guard, err = nil, nil
	}
	if err != nil {
		return nil, nil, err
	}
	w, err := q.GetWatchState(ctx, db.GetWatchStateParams{UserID: userID, ItemType: itemType, ItemID: id})
	state := &w
	if errors.Is(err, sql.ErrNoRows) {
		state, err = nil, nil
	}
	if err != nil {
		return nil, nil, err
	}
	return guard, state, nil
}

// local says whether the user's own choice decides an item: a local guard,
// or state without a guard, which only an older server could have written.
func local(g *db.WatchGuard, w *db.WatchState) bool {
	return (g != nil && g.Owner == ownerLocal) || (g == nil && w != nil)
}
