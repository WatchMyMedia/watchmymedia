package metadata

import (
	"context"
	"errors"
	"fmt"
	"sync/atomic"

	"golang.org/x/sync/errgroup"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/tmdb"
)

// RatingCountry is whose certifications are kept (TV-14, R).
const RatingCountry = "US"

// ratingLookups is how many certifications are looked up at once; the
// client's limiter still paces them.
const ratingLookups = 4

// fillContentRatings looks up the certification of the library's movies or
// shows that were matched before ratings were kept, one TMDB call each, and
// returns how many it stored. A title TMDB has no rating for stores "", so
// it is not asked again. The first lookup that fails ends the pass: when
// TMDB can't be reached, every other title would only wait out its own
// timeout. What is left is tried on the next match run.
func (m *Matcher) fillContentRatings(ctx context.Context, lib db.Library) int {
	type title struct{ id, tmdbID int64 }
	var (
		titles []title
		lookup func(ctx context.Context, tmdbID int) (string, error)
		set    func(id int64, rating string) error
	)
	switch lib.Type {
	case library.TypeMovie:
		rows, err := m.q.ListMoviesWithoutContentRating(ctx, lib.ID)
		if err != nil {
			return 0
		}
		for _, r := range rows {
			titles = append(titles, title{r.ID, r.TmdbID})
		}
		lookup = func(ctx context.Context, tmdbID int) (string, error) {
			rd, err := m.tmdb.GetMovieReleaseDates(ctx, tmdbID)
			if err != nil {
				return "", err
			}
			return rd.Certification(RatingCountry), nil
		}
		set = func(id int64, rating string) error {
			return m.q.SetMovieContentRating(ctx, db.SetMovieContentRatingParams{ContentRating: &rating, ID: id})
		}
	case library.TypeTV:
		rows, err := m.q.ListShowsWithoutContentRating(ctx, lib.ID)
		if err != nil {
			return 0
		}
		for _, r := range rows {
			titles = append(titles, title{r.ID, r.TmdbID})
		}
		lookup = func(ctx context.Context, tmdbID int) (string, error) {
			cr, err := m.tmdb.GetTVContentRatings(ctx, tmdbID)
			if err != nil {
				return "", err
			}
			return cr.Certification(RatingCountry), nil
		}
		set = func(id int64, rating string) error {
			return m.q.SetShowContentRating(ctx, db.SetShowContentRatingParams{ContentRating: &rating, ID: id})
		}
	default:
		return 0
	}
	var filled atomic.Int64
	g, gctx := errgroup.WithContext(ctx)
	g.SetLimit(ratingLookups)
	for _, t := range titles {
		if gctx.Err() != nil {
			break
		}
		g.Go(func() error {
			rating, err := lookup(gctx, int(t.tmdbID))
			if errors.Is(err, tmdb.ErrNotFound) {
				rating, err = "", nil
			}
			if err != nil {
				return fmt.Errorf("tmdb %d: %w", t.tmdbID, err)
			}
			// A rating found is kept even when another lookup has failed.
			if err := set(t.id, rating); err != nil {
				return err
			}
			filled.Add(1)
			return nil
		})
	}
	log := m.log.With("library", lib.ID)
	if err := g.Wait(); err != nil && ctx.Err() == nil {
		log.Warn("content ratings stopped", "err", err)
	}
	if n := filled.Load(); n > 0 {
		log.Info("content ratings filled", "titles", n)
	}
	return int(filled.Load())
}
