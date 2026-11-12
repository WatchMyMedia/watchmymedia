package watchstate

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"watchmymedia/server/internal/db"
)

// Offline changes (PLAN-DOWNLOADS.md §6): a phone playing a download with
// no connection keeps its progress and marks, each with when it happened,
// and sends them when it is back online. The newest local change wins by
// when it happened, so a later play on the TV is not overwritten by an
// older offline one; an import's mark never beats an offline change, which
// is local. An applied change keeps its own time in the state and the guard,
// so a change made offline yesterday sorts as yesterday's, and the next
// offline change is compared with it.

// Newer: the item was played or marked here after the offline change, so
// that later change stays and the offline one is dropped.
const Newer Outcome = "newer"

// ReportAt saves a position a device made at the given time, while
// offline, on each of the file's items that nothing here changed later: an
// item with no guard, an imported mark, or a local change from before at.
// Completed marks the item watched at position 0, as Report does. It
// returns an outcome per item: Applied, Newer, or Unavailable for an item
// that no longer exists. Watch.changed goes out for the applied items.
func (s *Service) ReportAt(ctx context.Context, p Progress, at time.Time) ([]Outcome, error) {
	if len(p.ItemIDs) == 0 {
		return nil, errors.New("watchstate: a report needs an item")
	}
	fileID := p.FileID
	return s.offline(ctx, p.UserID, p.ItemType, p.ItemIDs, at, func(q *db.Queries, id int64, stamp string) error {
		return q.UpsertWatchStateAt(ctx, db.UpsertWatchStateAtParams{
			UserID: p.UserID, ItemType: p.ItemType, ItemID: id, MediaFileID: &fileID, PositionMs: p.PositionMs, DurationMs: p.DurationMs,
			Completed: db.BoolInt(p.Completed), UpdatedAt: stamp,
		})
	})
}

// MarkAt marks items watched or unwatched as a device did at the given
// time, offline, under ReportAt's rule. Watched clears the position, as
// MarkWatched does; unwatched deletes the state and keeps a guard, as
// MarkUnwatched does.
func (s *Service) MarkAt(ctx context.Context, userID int64, itemType string, ids []int64, watched bool, at time.Time) ([]Outcome, error) {
	return s.offline(ctx, userID, itemType, ids, at, func(q *db.Queries, id int64, stamp string) error {
		if !watched {
			return q.DeleteWatchState(ctx, db.DeleteWatchStateParams{UserID: userID, ItemType: itemType, ItemID: id})
		}
		return q.UpsertWatchStateAt(ctx, db.UpsertWatchStateAtParams{UserID: userID, ItemType: itemType, ItemID: id, Completed: 1, UpdatedAt: stamp})
	})
}

// offline applies write to each item the offline change at may decide,
// in one transaction that takes the next revision, and claims those items
// for the user with at as the guard's time.
func (s *Service) offline(ctx context.Context, userID int64, itemType string, ids []int64, at time.Time, write func(q *db.Queries, id int64, stamp string) error) ([]Outcome, error) {
	// Stored times have milliseconds; compare at as it will be stored.
	at = at.UTC().Truncate(time.Millisecond)
	stamp := db.FormatTime(at)
	out := make([]Outcome, len(ids))
	var applied []int64
	err := s.change(ctx, func(q *db.Queries, rev int64) error {
		applied = applied[:0]
		for i, id := range ids {
			o, err := offlineOutcome(ctx, q, userID, itemType, id, at)
			if err != nil {
				return err
			}
			out[i] = o
			if o != Applied {
				continue
			}
			if err := write(q, id, stamp); err != nil {
				return err
			}
			if err := q.ClaimWatchGuardAt(ctx, db.ClaimWatchGuardAtParams{UserID: userID, ItemType: itemType, ItemID: id, Revision: rev, UpdatedAt: stamp}); err != nil {
				return err
			}
			applied = append(applied, id)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if len(applied) > 0 {
		s.publish(userID, itemType, applied)
	}
	return out, nil
}

// offlineOutcome says whether an offline change made at may decide an
// item: yes unless the item is gone or a local change here is as new.
func offlineOutcome(ctx context.Context, q *db.Queries, userID int64, itemType string, id int64, at time.Time) (Outcome, error) {
	ok, err := itemExists(ctx, q, itemType, id)
	if err != nil || !ok {
		return Unavailable, err
	}
	guard, state, err := current(ctx, q, userID, itemType, id)
	if err != nil {
		return "", err
	}
	if !local(guard, state) {
		return Applied, nil
	}
	// A local choice: the guard says when it was made, or, for state an
	// older server wrote without one, the state does.
	when := ""
	if guard != nil {
		when = guard.UpdatedAt
	} else {
		when = state.UpdatedAt
	}
	if db.ParseTime(when).Before(at) {
		return Applied, nil
	}
	return Newer, nil
}

func itemExists(ctx context.Context, q *db.Queries, itemType string, id int64) (bool, error) {
	var err error
	switch itemType {
	case Movie:
		_, err = q.GetMovie(ctx, id)
	case Episode:
		_, err = q.GetEpisode(ctx, id)
	default:
		return false, nil
	}
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}
