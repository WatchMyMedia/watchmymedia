package api

import (
	"cmp"
	"context"
	"net/http"
	"slices"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
)

type SearchInput struct {
	Q     string `query:"q" minLength:"1" maxLength:"100"`
	Limit int64  `query:"limit" default:"20" minimum:"1" maximum:"50"`
}

type SearchEpisode struct {
	ID             int64  `json:"id"`
	ShowID         int64  `json:"showId"`
	ShowTitle      string `json:"showTitle"`
	ShowPosterPath string `json:"showPosterPath,omitempty"`
	SeasonNumber   int64  `json:"seasonNumber"`
	Number         int64  `json:"number"`
	Title          string `json:"title"`
	StillPath      string `json:"stillPath,omitempty"`
	AirDate        string `json:"airDate,omitempty"`
	FileID         int64  `json:"fileId"`
	Watched        bool   `json:"watched" required:"false" doc:"The signed-in user has watched it."`
	// RuntimeMin and Progress are from API level 34.
	RuntimeMin int64   `json:"runtimeMin,omitempty" required:"false" doc:"Its length in minutes, as TMDB lists it."`
	Progress   float64 `json:"progress,omitempty" required:"false" doc:"The fraction the signed-in person has watched (0-1) when partway through."`
}

type SearchResults struct {
	Movies   []Movie         `json:"movies" nullable:"false"`
	Shows    []Show          `json:"shows" nullable:"false"`
	Episodes []SearchEpisode `json:"episodes" nullable:"false"`
}

type SearchOutput struct{ Body SearchResults }

func registerSearch(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "search",
		Method:      http.MethodGet,
		Path:        Prefix + "/search",
		Summary:     "Search titles across libraries",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *SearchInput) (*SearchOutput, error) {
		res, err := searchLibrary(ctx, deps, in.Q, in.Limit)
		if err != nil {
			return nil, err
		}
		return &SearchOutput{Body: *res}, nil
	})
}

// searchLibrary finds up to limit movies, shows and episodes for what
// someone typed, each group with its best matches first.
//
// Each group is read in two steps: the titles that might match and have a
// present file, with only what ranking needs, and then the rows of the ones
// shown. Before, each group read every present file in the library and the
// title it belongs to, on every keystroke.
func searchLibrary(ctx context.Context, deps Deps, typed string, limit int64) (*SearchResults, error) {
	s := parseSearch(typed)
	out := &SearchResults{Movies: []Movie{}, Shows: []Show{}, Episodes: []SearchEpisode{}}
	q := deps.Queries

	hits, err := searchMovieHits(ctx, q, &s, limit)
	if err != nil {
		return nil, err
	}
	ids := bestHits(hits, limit)
	movies, err := q.ListMoviesByIDs(ctx, ids)
	if err != nil {
		return nil, err
	}
	for _, m := range inOrder(ids, movies, func(m db.Movie) int64 { return m.ID }) {
		out.Movies = append(out.Movies, movieFromDB(m))
	}

	if hits, err = searchShowHits(ctx, q, &s, limit); err != nil {
		return nil, err
	}
	ids = bestHits(hits, limit)
	shows, err := q.ListShowsByIDs(ctx, ids)
	if err != nil {
		return nil, err
	}
	for _, sh := range inOrder(ids, shows, func(sh db.Show) int64 { return sh.ID }) {
		out.Shows = append(out.Shows, showFromDB(sh))
	}

	if hits, err = searchEpisodeHits(ctx, q, &s, limit); err != nil {
		return nil, err
	}
	ids = bestHits(hits, limit)
	episodes, err := q.ListSearchEpisodesByIDs(ctx, ids)
	if err != nil {
		return nil, err
	}
	watched, err := watchedEpisodes(ctx, deps, ids)
	if err != nil {
		return nil, err
	}
	for _, e := range inOrder(ids, episodes, func(e db.ListSearchEpisodesByIDsRow) int64 { return e.ID }) {
		if e.FileID == 0 {
			continue // its file went missing since it was found
		}
		out.Episodes = append(out.Episodes, SearchEpisode{
			ID: e.ID, ShowID: e.ShowID, ShowTitle: e.ShowTitle, ShowPosterPath: deref(e.ShowPosterPath), SeasonNumber: e.SeasonNumber,
			Number: e.Number, Title: e.Title, StillPath: deref(e.StillPath), AirDate: deref(e.AirDate), FileID: e.FileID,
			Watched: watched[e.ID], RuntimeMin: deref(e.RuntimeMin),
		})
	}
	if err := markSearch(ctx, deps, out); err != nil {
		return nil, err
	}
	return out, nil
}

// markSearch gives search's results the signed-in person's state, as cards
// have it, read for the few titles shown, by id.
func markSearch(ctx context.Context, deps Deps, out *SearchResults) error {
	user, ok := auth.UserFrom(ctx)
	if !ok {
		return nil
	}
	q := deps.Queries
	ids := make([]int64, 0, len(out.Movies))
	for _, m := range out.Movies {
		ids = append(ids, m.ID)
	}
	if len(ids) > 0 {
		states, err := q.ListWatchStatesForItems(ctx, db.ListWatchStatesForItemsParams{UserID: user.ID, ItemType: "movie", Ids: ids})
		if err != nil {
			return err
		}
		byID := map[int64]db.ListWatchStatesForItemsRow{}
		for _, s := range states {
			byID[s.ItemID] = s
		}
		for i := range out.Movies {
			s := byID[out.Movies[i].ID]
			out.Movies[i].Watched = new(s.Completed == 1)
			if s.Completed == 0 && s.DurationMs > 0 && s.PositionMs > 0 {
				out.Movies[i].Progress = float64(s.PositionMs) / float64(s.DurationMs)
			}
		}
	}
	ids = ids[:0]
	for _, s := range out.Shows {
		ids = append(ids, s.ID)
	}
	if len(ids) > 0 {
		cards, err := q.ListShowCardsByIDs(ctx, ids)
		if err != nil {
			return err
		}
		watched, err := q.ListShowWatchedCountsByIDs(ctx, db.ListShowWatchedCountsByIDsParams{UserID: user.ID, Ids: ids})
		if err != nil {
			return err
		}
		present, seasons, seen := map[int64]int64{}, map[int64]int64{}, map[int64]int64{}
		for _, c := range cards {
			present[c.ID], seasons[c.ID] = c.EpisodeCount, c.SeasonCount
		}
		for _, w := range watched {
			seen[w.ShowID] = w.Watched
		}
		for i := range out.Shows {
			id := out.Shows[i].ID
			out.Shows[i].SeasonCount = seasons[id]
			out.Shows[i].UnwatchedCount = new(max(0, present[id]-seen[id]))
			out.Shows[i].Watched = new(present[id] > 0 && seen[id] >= present[id])
		}
	}
	ids = ids[:0]
	for _, e := range out.Episodes {
		if !e.Watched {
			ids = append(ids, e.ID)
		}
	}
	if len(ids) > 0 {
		states, err := q.ListWatchStatesForItems(ctx, db.ListWatchStatesForItemsParams{UserID: user.ID, ItemType: "episode", Ids: ids})
		if err != nil {
			return err
		}
		progress := map[int64]float64{}
		for _, s := range states {
			if s.Completed == 0 && s.DurationMs > 0 && s.PositionMs > 0 {
				progress[s.ItemID] = float64(s.PositionMs) / float64(s.DurationMs)
			}
		}
		for i := range out.Episodes {
			out.Episodes[i].Progress = progress[out.Episodes[i].ID]
		}
	}
	return nil
}

// searchHit is a title that matches a search, with what orders it among
// the others: how well it matches, then its title. An episode's title here
// is its show's, followed by its season and number, as episodes were
// ordered before they were ranked.
type searchHit struct {
	id             int64
	rank           int
	title          string
	season, number int64
}

func (a searchHit) compare(b searchHit) int {
	return cmp.Or(
		cmp.Compare(a.rank, b.rank),
		compareNoCase(a.title, b.title),
		cmp.Compare(a.season, b.season),
		cmp.Compare(a.number, b.number),
		cmp.Compare(a.id, b.id),
	)
}

// bestHits is the ids of the first limit hits, best first. A common word
// matches thousands of titles, so it keeps the best so far in order rather
// than sorting them all.
func bestHits(hits []searchHit, limit int64) []int64 {
	best := make([]searchHit, 0, min(limit, int64(len(hits)))+1)
	for _, h := range hits {
		if int64(len(best)) == limit && h.compare(best[limit-1]) >= 0 {
			continue
		}
		at, _ := slices.BinarySearchFunc(best, h, searchHit.compare)
		best = slices.Insert(best, at, h)
		best = best[:min(int64(len(best)), limit)]
	}
	ids := make([]int64, len(best))
	for i, h := range best {
		ids[i] = h.id
	}
	return ids
}

// inOrder is rows in the order of ids. A row deleted since its id was
// found is left out.
func inOrder[R any](ids []int64, rows []R, id func(R) int64) []R {
	byID := make(map[int64]R, len(rows))
	for _, r := range rows {
		byID[id(r)] = r
	}
	out := make([]R, 0, len(rows))
	for _, i := range ids {
		if r, ok := byID[i]; ok {
			out = append(out, r)
		}
	}
	return out
}

// searchTitleRow is a movie or show that might match: what the four title
// queries return.
type searchTitleRow struct {
	ID            int64
	Title         string
	OriginalTitle *string
	Year          *int64
	Legacy        int64
}

func (s *searchQuery) titleHits(rows []searchTitleRow) []searchHit {
	hits := make([]searchHit, 0, len(rows))
	for _, r := range rows {
		if rank, ok := s.rank(r.Title, deref(r.OriginalTitle), deref(r.Year), r.Legacy != 0); ok {
			hits = append(hits, searchHit{id: r.ID, rank: rank, title: r.Title})
		}
	}
	return hits
}

// hits finds a group's titles for the search, by these steps.
//
// A search with a word of three letters or more asks the index for the
// titles that hold every such word.
//
// A search of shorter words first asks the index for the titles where each
// word starts a word. When those fill a list of limit, that is the answer:
// a title with the letters elsewhere comes after every one of them. The
// first letter someone types is in most of a library, and reading every
// title for it, then every match's files, took longer than search did
// before it had an index. One letter is only looked up at the start of a
// title and after a space, so while the list is full a title where it
// follows a hyphen or a bracket waits for the second letter
// (startsMatch).
//
// Otherwise it also reads every title for the words anywhere, as search
// always did, and adds what that finds.
func (s *searchQuery) hits(limit int64, indexed func(words string) ([]searchHit, error), scan func() ([]searchHit, error)) ([]searchHit, error) {
	if len(s.index) > 0 {
		return indexed(s.indexMatch())
	}
	var hits []searchHit
	if len(s.scan) > 0 {
		var err error
		hits, err = indexed(s.startsMatch())
		if err != nil {
			return nil, err
		}
		starts := int64(0)
		for _, h := range hits {
			if h.rank <= searchWordsHit {
				starts++
			}
		}
		if starts >= limit {
			return hits, nil
		}
	}
	rest, err := scan()
	if err != nil {
		return nil, err
	}
	seen := make(map[int64]bool, len(hits))
	for _, h := range hits {
		seen[h.id] = true
	}
	for _, h := range rest {
		if !seen[h.id] {
			hits = append(hits, h)
		}
	}
	return hits, nil
}

func searchMovieHits(ctx context.Context, q *db.Queries, s *searchQuery, limit int64) ([]searchHit, error) {
	return s.hits(limit, func(words string) ([]searchHit, error) {
		found, err := q.SearchMovieTitles(ctx, db.SearchMovieTitlesParams{Legacy: s.legacy, Words: words})
		rows := make([]searchTitleRow, len(found))
		for i, r := range found {
			rows[i] = searchTitleRow(r)
		}
		return s.titleHits(rows), err
	}, func() ([]searchHit, error) {
		one, two, three, marks := s.scanPatterns()
		found, err := q.ScanMovieTitles(ctx, db.ScanMovieTitlesParams{Legacy: s.legacy, Word1: one, Word2: two, Word3: three, Marks: marks})
		rows := make([]searchTitleRow, len(found))
		for i, r := range found {
			rows[i] = searchTitleRow(r)
		}
		return s.titleHits(rows), err
	})
}

func searchShowHits(ctx context.Context, q *db.Queries, s *searchQuery, limit int64) ([]searchHit, error) {
	return s.hits(limit, func(words string) ([]searchHit, error) {
		found, err := q.SearchShowTitles(ctx, db.SearchShowTitlesParams{Legacy: s.legacy, Words: words})
		rows := make([]searchTitleRow, len(found))
		for i, r := range found {
			rows[i] = searchTitleRow(r)
		}
		return s.titleHits(rows), err
	}, func() ([]searchHit, error) {
		one, two, three, marks := s.scanPatterns()
		found, err := q.ScanShowTitles(ctx, db.ScanShowTitlesParams{Legacy: s.legacy, Word1: one, Word2: two, Word3: three, Marks: marks})
		rows := make([]searchTitleRow, len(found))
		for i, r := range found {
			rows[i] = searchTitleRow(r)
		}
		return s.titleHits(rows), err
	})
}

// searchEpisodeHits finds episodes by their own titles. A year after the
// words is only a word here: an episode has no year of its own.
func searchEpisodeHits(ctx context.Context, q *db.Queries, s *searchQuery, limit int64) ([]searchHit, error) {
	hits := func(rows []db.SearchEpisodeTitlesRow) []searchHit {
		out := make([]searchHit, 0, len(rows))
		for _, r := range rows {
			if rank, ok := s.rank(r.Title, "", 0, r.Legacy != 0); ok {
				out = append(out, searchHit{id: r.ID, rank: rank, title: r.ShowTitle, season: r.SeasonNumber, number: r.Number})
			}
		}
		return out
	}
	return s.hits(limit, func(words string) ([]searchHit, error) {
		found, err := q.SearchEpisodeTitles(ctx, db.SearchEpisodeTitlesParams{Legacy: s.legacy, Words: words})
		return hits(found), err
	}, func() ([]searchHit, error) {
		one, two, three, marks := s.scanPatterns()
		found, err := q.ScanEpisodeTitles(ctx, db.ScanEpisodeTitlesParams{Legacy: s.legacy, Word1: one, Word2: two, Word3: three, Marks: marks})
		rows := make([]db.SearchEpisodeTitlesRow, len(found))
		for i, r := range found {
			rows[i] = db.SearchEpisodeTitlesRow(r)
		}
		return hits(rows), err
	})
}
