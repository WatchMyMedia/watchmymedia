package trailers

import (
	"context"
	"errors"
	"log/slog"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb"
)

// How often a movie's trailers are asked for again. TMDB lists trailers for
// upcoming films around their release, and its editors add more later; its
// terms allow keeping them for six months.
const (
	// RecentMonths is how long after its release a movie counts as recent.
	RecentMonths = 6
	// RecentEvery is how often a recent or upcoming movie is checked.
	RecentEvery = 7 * 24 * time.Hour
	// OlderEvery is how often every other movie is checked.
	OlderEvery = 30 * 24 * time.Hour
)

// Refresher asks TMDB again for the trailers of movies whose last check is
// due, in the background, a few hundred movies at a time. Matching a movie
// stores its trailers too, so the job mostly fills in movies matched before
// trailers existed, and keeps recent films' lists current. It also holds
// whether YouTube trailers are on (the trailers.youtube setting); while they
// are off it asks TMDB nothing.
type Refresher struct {
	q       *db.Queries
	tmdb    *tmdb.Client
	log     *slog.Logger
	youtube atomic.Bool
	wake    chan struct{}
	// Delay is how long after start the first run waits, and the wait
	// before the next run when a run had more movies than Batch.
	Delay time.Duration
	// Every is how often it runs otherwise.
	Every time.Duration
	// Batch is the most movies a run asks for: at TMDB's pace of 20
	// requests a second, 250 take about 13 seconds.
	Batch int
}

// New returns a refresher with YouTube trailers on or off, as the setting
// says. It runs from Run.
func New(q *db.Queries, client *tmdb.Client, log *slog.Logger, youtube bool) *Refresher {
	r := &Refresher{q: q, tmdb: client, log: log, wake: make(chan struct{}, 1), Delay: 5 * time.Minute, Every: 6 * time.Hour, Batch: 250}
	r.youtube.Store(youtube)
	return r
}

// YouTube is whether YouTube trailers are on: replies list them and the web
// app may frame YouTube's player. A nil Refresher says no.
func (r *Refresher) YouTube() bool { return r != nil && r.youtube.Load() }

// SetYouTube turns YouTube trailers on or off. Turning them on runs the job
// soon, for the movies that fell due while they were off.
func (r *Refresher) SetYouTube(on bool) {
	if r == nil {
		return
	}
	if was := r.youtube.Swap(on); on && !was {
		select {
		case r.wake <- struct{}{}:
		default:
		}
	}
}

// Run refreshes trailers Delay after it starts and every Every after
// that, sooner while movies are left over from a full run, until ctx ends.
func (r *Refresher) Run(ctx context.Context) {
	timer := time.NewTimer(r.Delay)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-r.wake:
			timer.Reset(r.Delay)
			continue
		case <-timer.C:
		}
		more, err := r.RunOnce(ctx)
		if err != nil && ctx.Err() == nil {
			r.log.Warn("could not refresh movie trailers", "err", err)
		}
		if more {
			timer.Reset(r.Delay)
		} else {
			timer.Reset(r.Every)
		}
	}
}

// RunOnce asks TMDB for the trailers of up to Batch movies that are due,
// and says whether more were due than it asked for. A movie TMDB no longer
// has keeps no trailers until its next check. Any other failure (no key, a
// refused key, TMDB down) ends the run, and the movies left wait for the
// next one.
func (r *Refresher) RunOnce(ctx context.Context) (more bool, err error) {
	if !r.YouTube() || !r.tmdb.Configured() {
		return false, nil
	}
	now := time.Now()
	due, err := r.q.ListMoviesForTrailers(ctx, db.ListMoviesForTrailersParams{
		OlderBefore:  db.FormatTime(now.Add(-OlderEvery)),
		RecentSince:  now.UTC().AddDate(0, -RecentMonths, 0).Format(time.DateOnly),
		RecentBefore: db.FormatTime(now.Add(-RecentEvery)),
		MaxRows:      int64(r.Batch),
	})
	if err != nil {
		return false, err
	}
	found := 0
	for i, m := range due {
		videos, err := r.tmdb.GetMovieVideos(ctx, int(m.TmdbID))
		var list []Trailer
		switch {
		case err == nil:
			list = Choose(videos.Results)
		case errors.Is(err, tmdb.ErrNotFound):
		default:
			if i > 0 {
				r.log.Info("movie trailers refreshed", "movies", i, "with_trailers", found)
			}
			return false, err
		}
		if len(list) > 0 {
			found++
		}
		if err := r.q.SetMovieTrailers(ctx, db.SetMovieTrailersParams{Trailers: Encode(list), TrailersCheckedAt: new(db.FormatTime(time.Now())), ID: m.ID}); err != nil {
			return false, err
		}
	}
	if len(due) > 0 {
		r.log.Info("movie trailers refreshed", "movies", len(due), "with_trailers", found)
	}
	return len(due) == r.Batch, nil
}
