package related

import (
	"context"
	"strconv"
	"time"

	"watchmymedia/server/internal/db"
)

// How long a title's related data is kept before a page view fetches it
// again in the background: TMDB's recommendations for a new release fill in
// over its first months.
const (
	RecentEvery = 30 * 24 * time.Hour
	OlderEvery  = 90 * 24 * time.Hour
)

// Due says whether related data fetched at checkedAt (nil: never) for a
// title released on released (a date, empty when unknown) is due to be
// fetched again at now.
func Due(checkedAt *string, released string, now time.Time) bool {
	if checkedAt == nil {
		return true
	}
	every := OlderEvery
	if released != "" && released >= now.UTC().AddDate(-1, 0, 0).Format(time.DateOnly) {
		every = RecentEvery
	}
	return now.Sub(db.ParseTime(*checkedAt)) >= every
}

// Result is a title's related titles as IDs of its kind, in order.
type Result struct {
	// CollectionName names the collection row; Collection are the
	// collection's other films in the library, one per TMDB title, in
	// release order. Movies only.
	CollectionName string
	Collection     []int64
	// Similar are More like this's titles, best first: up to twice
	// SimilarMax, since titles with no file present have no card.
	Similar []int64
	// Due says the title's related data should be fetched again.
	Due bool
}

// Movie works out a movie's related titles from what is stored; it never
// asks TMDB.
func Movie(ctx context.Context, q *db.Queries, id int64, now time.Time) (Result, error) {
	info, err := q.GetMovieRelatedInfo(ctx, id)
	if err != nil {
		return Result{}, err
	}
	res := Result{Due: Due(info.RelatedCheckedAt, deref(info.ReleaseDate), now)}
	exclude := map[int64]bool{}
	if info.CollectionID != nil {
		rows, err := q.ListCollectionMovies(ctx, db.ListCollectionMoviesParams{CollectionID: info.CollectionID, TmdbID: info.TmdbID})
		if err != nil {
			return Result{}, err
		}
		refs := make([]Ref, 0, len(rows))
		for _, r := range rows {
			exclude[r.ID] = true
			refs = append(refs, Ref{ID: r.ID, TMDBID: r.TmdbID, LibraryID: r.LibraryID})
		}
		for _, r := range OnePerTitle(refs, info.LibraryID) {
			res.Collection = append(res.Collection, r.ID)
		}
		res.CollectionName = deref(info.CollectionName)
	}
	if info.RelatedCheckedAt == nil {
		return res, nil
	}
	s, err := movieSignals(ctx, q, id, info.TmdbID)
	if err != nil {
		return Result{}, err
	}
	s.Exclude = exclude
	target := Ref{ID: id, TMDBID: info.TmdbID, LibraryID: info.LibraryID, Year: deref(info.Year)}
	res.Similar = ids(Similar(target, s, SimilarMax*2))
	return res, nil
}

// Show works out a show's related titles from what is stored; it never
// asks TMDB.
func Show(ctx context.Context, q *db.Queries, id int64, now time.Time) (Result, error) {
	info, err := q.GetShowRelatedInfo(ctx, id)
	if err != nil {
		return Result{}, err
	}
	res := Result{Due: Due(info.RelatedCheckedAt, deref(info.FirstAirDate), now)}
	if info.RelatedCheckedAt == nil {
		return res, nil
	}
	s, err := showSignals(ctx, q, id, info.TmdbID)
	if err != nil {
		return Result{}, err
	}
	target := Ref{ID: id, TMDBID: info.TmdbID, LibraryID: info.LibraryID, Year: deref(info.Year)}
	res.Similar = ids(Similar(target, s, SimilarMax*2))
	return res, nil
}

func newSignals() Signals {
	return Signals{Counts: map[TagKey]int64{}, Recommended: map[int64]int64{}, Tags: map[int64][]TagKey{}, Refs: map[int64]Ref{}}
}

func movieSignals(ctx context.Context, q *db.Queries, id, tmdbID int64) (Signals, error) {
	s := newSignals()
	var err error
	if s.Titles, err = q.CountRelatedMovies(ctx); err != nil {
		return s, err
	}
	counts, err := q.CountMovieTagTitles(ctx, id)
	if err != nil {
		return s, err
	}
	for _, c := range counts {
		s.Counts[TagKey{c.Kind, c.Value}] = c.Titles
	}
	matches, err := q.ListMovieTagMatches(ctx, id)
	if err != nil {
		return s, err
	}
	for _, m := range matches {
		s.Matches = append(s.Matches, Match{OtherID: m.OtherID, Kind: m.Kind, Value: m.Value, RankA: m.RankA, RankB: m.RankB})
	}
	recs, err := q.ListMovieRecommended(ctx, id)
	if err != nil {
		return s, err
	}
	for _, r := range recs {
		recommend(s.Recommended, r.ID, r.Rank)
	}
	if s.Recommending, err = q.ListMoviesRecommending(ctx, strconv.FormatInt(tmdbID, 10)); err != nil {
		return s, err
	}
	cands := s.Candidates()
	if len(cands) == 0 {
		return s, nil
	}
	tags, err := q.ListMovieTagsOf(ctx, cands)
	if err != nil {
		return s, err
	}
	for _, t := range tags {
		s.Tags[t.MovieID] = append(s.Tags[t.MovieID], TagKey{t.Kind, t.Value})
	}
	refs, err := q.ListMovieRelatedRefs(ctx, cands)
	if err != nil {
		return s, err
	}
	for _, r := range refs {
		s.Refs[r.ID] = Ref{ID: r.ID, TMDBID: r.TmdbID, LibraryID: r.LibraryID, Title: r.Title, Year: deref(r.Year), Rating: deref(r.Rating)}
	}
	return s, nil
}

func showSignals(ctx context.Context, q *db.Queries, id, tmdbID int64) (Signals, error) {
	s := newSignals()
	var err error
	if s.Titles, err = q.CountRelatedShows(ctx); err != nil {
		return s, err
	}
	counts, err := q.CountShowTagTitles(ctx, id)
	if err != nil {
		return s, err
	}
	for _, c := range counts {
		s.Counts[TagKey{c.Kind, c.Value}] = c.Titles
	}
	matches, err := q.ListShowTagMatches(ctx, id)
	if err != nil {
		return s, err
	}
	for _, m := range matches {
		s.Matches = append(s.Matches, Match{OtherID: m.OtherID, Kind: m.Kind, Value: m.Value, RankA: m.RankA, RankB: m.RankB})
	}
	recs, err := q.ListShowRecommended(ctx, id)
	if err != nil {
		return s, err
	}
	for _, r := range recs {
		recommend(s.Recommended, r.ID, r.Rank)
	}
	if s.Recommending, err = q.ListShowsRecommending(ctx, strconv.FormatInt(tmdbID, 10)); err != nil {
		return s, err
	}
	cands := s.Candidates()
	if len(cands) == 0 {
		return s, nil
	}
	tags, err := q.ListShowTagsOf(ctx, cands)
	if err != nil {
		return s, err
	}
	for _, t := range tags {
		s.Tags[t.ShowID] = append(s.Tags[t.ShowID], TagKey{t.Kind, t.Value})
	}
	refs, err := q.ListShowRelatedRefs(ctx, cands)
	if err != nil {
		return s, err
	}
	for _, r := range refs {
		s.Refs[r.ID] = Ref{ID: r.ID, TMDBID: r.TmdbID, LibraryID: r.LibraryID, Title: r.Title, Year: deref(r.Year), Rating: deref(r.Rating)}
	}
	return s, nil
}

// recommend keeps a title's best (lowest) TMDB rank.
func recommend(m map[int64]int64, id, rank int64) {
	if r, ok := m[id]; !ok || rank < r {
		m[id] = rank
	}
}

func ids(refs []Ref) []int64 {
	out := make([]int64, 0, len(refs))
	for _, r := range refs {
		out = append(out, r.ID)
	}
	return out
}

func deref[T any](p *T) T {
	var zero T
	if p == nil {
		return zero
	}
	return *p
}
