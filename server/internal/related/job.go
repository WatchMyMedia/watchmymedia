package related

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb"
)

// maxWanted bounds the titles waiting for a fetch because a page asked for
// them; past it, page views ask nothing more until the job catches up.
const maxWanted = 256

// Refresher fetches titles' related data from TMDB in the background
// (PLAN-RELATED.md §3.9): first the titles whose pages were opened with old
// data (Want), then, a batch at a time, the titles matched before related
// titles existed. Matching fetches it for new titles. Pages never wait for
// it: they use what is stored meanwhile.
type Refresher struct {
	q    *db.Queries
	tmdb *tmdb.Client
	log  *slog.Logger
	wake chan struct{}

	mu     sync.Mutex
	wanted map[want]struct{}
	order  []want

	// Delay is how long after start the first run waits, and the pause
	// between batches while titles are left to fill in.
	Delay time.Duration
	// Soon is how long after a page asks for a title the job runs.
	Soon time.Duration
	// Every is how often it runs otherwise.
	Every time.Duration
	// Batch is the most titles a run fetches: at TMDB's pace of 20 requests
	// a second, 200 take about 10 seconds.
	Batch int
	// Now is the clock; tests set it.
	Now func() time.Time
}

type want struct {
	kind string
	id   int64
}

// New returns a refresher. It runs from Run.
func New(q *db.Queries, client *tmdb.Client, log *slog.Logger) *Refresher {
	return &Refresher{q: q, tmdb: client, log: log, wake: make(chan struct{}, 1), wanted: map[want]struct{}{},
		Delay: 2 * time.Minute, Soon: 10 * time.Second, Every: time.Hour, Batch: 200, Now: time.Now}
}

// Want asks for a title's related data to be fetched again soon, because
// its page was opened with old data. kind is movie or show. A nil Refresher
// does nothing.
func (r *Refresher) Want(kind string, id int64) {
	if r == nil {
		return
	}
	w := want{kind, id}
	r.mu.Lock()
	_, have := r.wanted[w]
	if !have && len(r.wanted) < maxWanted {
		r.wanted[w] = struct{}{}
		r.order = append(r.order, w)
	}
	r.mu.Unlock()
	select {
	case r.wake <- struct{}{}:
	default:
	}
}

// take removes and returns up to n wanted titles, oldest first.
func (r *Refresher) take(n int) []want {
	r.mu.Lock()
	defer r.mu.Unlock()
	n = min(n, len(r.order))
	out := append([]want(nil), r.order[:n]...)
	r.order = r.order[n:]
	for _, w := range out {
		delete(r.wanted, w)
	}
	return out
}

// Run fetches Delay after it starts, Soon after a page asks for a title,
// every Delay while titles are left to fill in and every Every otherwise,
// until ctx ends.
func (r *Refresher) Run(ctx context.Context) {
	timer := time.NewTimer(r.Delay)
	defer timer.Stop()
	next := time.Now().Add(r.Delay)
	for {
		select {
		case <-ctx.Done():
			return
		case <-r.wake:
			if time.Until(next) > r.Soon {
				timer.Reset(r.Soon)
				next = time.Now().Add(r.Soon)
			}
			continue
		case <-timer.C:
		}
		more, err := r.RunOnce(ctx)
		if err != nil && ctx.Err() == nil {
			r.log.Warn("could not fetch related titles' data", "err", err)
		}
		wait := r.Every
		if more {
			wait = r.Delay
		}
		timer.Reset(wait)
		next = time.Now().Add(wait)
	}
}

// RunOnce fetches up to Batch titles: those pages asked for, then those
// never fetched, movies first. It says whether titles are left over. A
// title TMDB no longer has keeps no tags until its next fetch. Any other
// failure (no key, a refused key, TMDB down) ends the run.
func (r *Refresher) RunOnce(ctx context.Context) (more bool, err error) {
	if !r.tmdb.Configured() {
		return false, nil
	}
	budget := r.Batch
	for _, w := range r.take(budget) {
		budget--
		if err := r.refreshWanted(ctx, w); err != nil {
			return false, err
		}
	}
	fetched := 0
	defer func() {
		if fetched > 0 {
			r.log.Info("related titles' data filled in", "titles", fetched)
		}
	}()
	if budget > 0 {
		movies, err := r.q.ListMoviesForRelated(ctx, int64(budget))
		if err != nil {
			return false, err
		}
		for _, m := range movies {
			if err := r.refreshMovie(ctx, m.ID, m.TmdbID); err != nil {
				return false, err
			}
			fetched++
		}
		budget -= len(movies)
	}
	if budget > 0 {
		shows, err := r.q.ListShowsForRelated(ctx, int64(budget))
		if err != nil {
			return false, err
		}
		for _, s := range shows {
			if err := r.refreshShow(ctx, s.ID, s.TmdbID); err != nil {
				return false, err
			}
			fetched++
		}
		budget -= len(shows)
	}
	r.mu.Lock()
	left := len(r.order)
	r.mu.Unlock()
	return budget == 0 || left > 0, nil
}

// refreshWanted fetches a title a page asked for, unless it was fetched
// since or is gone.
func (r *Refresher) refreshWanted(ctx context.Context, w want) error {
	now := r.Now()
	switch w.kind {
	case "movie":
		info, err := r.q.GetMovieRelatedInfo(ctx, w.id)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil || !Due(info.RelatedCheckedAt, deref(info.ReleaseDate), now) {
			return err
		}
		return r.refreshMovie(ctx, info.ID, info.TmdbID)
	case "show":
		info, err := r.q.GetShowRelatedInfo(ctx, w.id)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err != nil || !Due(info.RelatedCheckedAt, deref(info.FirstAirDate), now) {
			return err
		}
		return r.refreshShow(ctx, info.ID, info.TmdbID)
	}
	return nil
}

func (r *Refresher) refreshMovie(ctx context.Context, id, tmdbID int64) error {
	mv, err := r.tmdb.GetMovie(ctx, int(tmdbID))
	if err != nil && !errors.Is(err, tmdb.ErrNotFound) {
		return err
	}
	return r.q.Transaction(ctx, func(q *db.Queries) error { return StoreMovie(ctx, q, id, mv, r.Now()) })
}

func (r *Refresher) refreshShow(ctx context.Context, id, tmdbID int64) error {
	tv, err := r.tmdb.GetTV(ctx, int(tmdbID))
	if err != nil && !errors.Is(err, tmdb.ErrNotFound) {
		return err
	}
	return r.q.Transaction(ctx, func(q *db.Queries) error { return StoreShow(ctx, q, id, tv, r.Now()) })
}
