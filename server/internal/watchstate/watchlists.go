package watchstate

import (
	"context"
	"log/slog"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/lists"
)

// leaveWatchlists takes what a change finished off the person's
// watchlists that remove watched titles: a movie the change finished, or
// a show whose card now says watched (every episode with a present file
// finished, in any one library that has it). The watch's own time
// decides, so an offline play from before a title was saved leaves it,
// and an import counts as watched when it ran.
//
// Every change that can finish an item calls it once, after its
// transaction commits, so a failure here never loses a watch mark: it is
// logged. A Move, which carries watch state to an item that replaces
// another, finishes nothing and doesn't call it.
func (s *Service) leaveWatchlists(ctx context.Context, userID int64, itemType string, ids []int64) {
	if len(ids) == 0 {
		return
	}
	left, err := s.finish(ctx, userID, itemType, ids)
	if err != nil {
		if ctx.Err() == nil {
			slog.WarnContext(ctx, "could not take finished titles off watchlists", "user_id", userID, "item_type", itemType, "err", err)
		}
		return
	}
	if s.bus == nil {
		return
	}
	for _, l := range left {
		s.bus.Publish(events.Event{Type: lists.ChangedEvent, Data: lists.ListsChanged{ListID: l.ID},
			To: &events.Audience{UserID: userID, Private: true}})
	}
}

func (s *Service) finish(ctx context.Context, userID int64, itemType string, ids []int64) ([]db.MediaList, error) {
	switch itemType {
	case Movie:
		titles, err := s.q.ListMovieTMDBIDs(ctx, ids)
		if err != nil {
			return nil, err
		}
		return lists.Leave(ctx, s.q, userID, "movie", titles, time.Now(), func([]int64) (map[int64]string, error) {
			rows, err := s.q.ListFinishedMovies(ctx, db.ListFinishedMoviesParams{UserID: userID, Ids: ids})
			out := make(map[int64]string, len(rows))
			for _, r := range rows {
				// A movie in two libraries, both finished: the later.
				if r.UpdatedAt > out[r.TmdbID] {
					out[r.TmdbID] = r.UpdatedAt
				}
			}
			return out, err
		})
	case Episode:
		shows, err := s.q.ListEpisodeShowTMDBIDs(ctx, ids)
		if err != nil {
			return nil, err
		}
		return lists.Leave(ctx, s.q, userID, "tv", shows, time.Now(), func(tmdbIDs []int64) (map[int64]string, error) {
			rows, err := s.q.ListFinishedShows(ctx, db.ListFinishedShowsParams{UserID: userID, TmdbIds: tmdbIDs})
			out := make(map[int64]string, len(rows))
			for _, r := range rows {
				if r.FinishedAt > out[r.TmdbID] {
					out[r.TmdbID] = r.FinishedAt
				}
			}
			return out, err
		})
	}
	return nil, nil
}
