package api

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"slices"
	"sync"
	"time"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/lists"
	"watchmymedia/server/internal/memo"
)

// listsState is what the list routes keep between requests: the
// libraries' items for library lists, previews' fetches and TMDB's genres.
type listsState struct {
	library libraryItemsCache
	// matches and cards keep what lists' entries match in the library,
	// and those titles' cards, until the library changes.
	matches      genCache[listMatches]
	movieCards   genCache[BrowseItem]
	showCards    genCache[BrowseItem]
	preview      *memo.Map[string, lists.Result]
	previewGroup memo.Group
	genres       *memo.Map[string, []MediaListGenre]
	genreGroup   memo.Group
}

func newListsState() *listsState {
	return &listsState{
		library:      libraryItemsCache{m: map[int64]libraryItemsEntry{}},
		preview:      memo.NewMap[string, lists.Result](5*time.Minute, 64),
		previewGroup: memo.Group{Timeout: time.Minute},
		genres:       memo.NewMap[string, []MediaListGenre](24*time.Hour, 4),
		genreGroup:   memo.Group{Timeout: 30 * time.Second},
	}
}

// genCache keeps values by ID while the libraries are unchanged: for the
// library generation lists.Service counts while it runs, and at most ten
// minutes. Without a running service nothing is kept.
type genCache[V any] struct {
	mu  sync.Mutex
	gen uint64
	m   map[int64]genEntry[V]
}

type genEntry[V any] struct {
	at time.Time
	v  V
}

// generation is the libraries' generation now, and whether values may be
// kept for it.
func generation(deps Deps) (uint64, bool) {
	if deps.Lists == nil {
		return 0, false
	}
	return deps.Lists.LibraryGeneration()
}

func (c *genCache[V]) get(gen uint64, id int64) (V, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[id]
	if !ok || c.gen != gen || time.Since(e.at) > 10*time.Minute {
		var zero V
		return zero, false
	}
	return e.v, true
}

func (c *genCache[V]) put(gen uint64, id int64, v V) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil || c.gen != gen || len(c.m) >= 20000 {
		c.m, c.gen = map[int64]genEntry[V]{}, gen
	}
	c.m[id] = genEntry[V]{at: time.Now(), v: v}
}

// listMatches are what a list's entries match in the library, for every
// screen: entries per media type, and each entry the library has, once
// per library that has it, in rank order. version says which of the
// list's fetches it is.
type listMatches struct {
	version string
	totals  map[string]listCounts
	refs    []rankRef
}

func listVersion(l db.MediaList) string { return deref(l.FetchedAt) + "|" + l.UpdatedAt }

// matchesOf reads lists' matches, from the cache where it has them.
func matchesOf(ctx context.Context, deps Deps, ls []db.MediaList) (map[int64]listMatches, error) {
	gen, live := generation(deps)
	out := map[int64]listMatches{}
	var need []int64
	for _, l := range ls {
		if m, ok := deps.listsState.matches.get(gen, l.ID); live && ok && m.version == listVersion(l) {
			out[l.ID] = m
			continue
		}
		need = append(need, l.ID)
		out[l.ID] = listMatches{version: listVersion(l), totals: map[string]listCounts{}}
	}
	if len(need) == 0 {
		return out, nil
	}
	totals, err := deps.Queries.CountMediaListEntries(ctx, need)
	if err != nil {
		return nil, err
	}
	for _, t := range totals {
		m := out[t.ListID]
		n := listCounts{entries: int(t.Entries)}
		if t.NotFound != nil {
			n.notFound = int(*t.NotFound)
		}
		m.totals[t.MediaType] = n
	}
	movies, err := deps.Queries.ListMediaListMovieRanks(ctx, need)
	if err != nil {
		return nil, err
	}
	for _, r := range movies {
		m := out[r.ListID]
		m.refs = append(m.refs, rankRef{rank: r.Rank, id: r.ID, libraryID: r.LibraryID})
		out[r.ListID] = m
	}
	shows, err := deps.Queries.ListMediaListShowRanks(ctx, need)
	if err != nil {
		return nil, err
	}
	for _, r := range shows {
		m := out[r.ListID]
		m.refs = append(m.refs, rankRef{rank: r.Rank, show: true, id: r.ID, libraryID: r.LibraryID})
		out[r.ListID] = m
	}
	for _, id := range need {
		m := out[id]
		slices.SortStableFunc(m.refs, func(a, b rankRef) int { return int(a.rank - b.rank) })
		out[id] = m
		if live {
			deps.listsState.matches.put(gen, id, m)
		}
	}
	return out, nil
}

// libraryItemsCache keeps each library's grid items for library lists,
// until the library changes (lists.Service counts changes while it runs)
// or ten minutes pass. Without a running service nothing is kept.
type libraryItemsCache struct {
	mu sync.Mutex
	m  map[int64]libraryItemsEntry
}

type libraryItemsEntry struct {
	gen   uint64
	at    time.Time
	items []BrowseItem
}

// items is a copy of lib's items, which the caller may mark and sort.
func (c *libraryItemsCache) items(ctx context.Context, deps Deps, lib db.Library) ([]BrowseItem, error) {
	var gen uint64
	live := false
	if deps.Lists != nil {
		gen, live = deps.Lists.LibraryGeneration()
	}
	if live {
		c.mu.Lock()
		e, ok := c.m[lib.ID]
		c.mu.Unlock()
		if ok && e.gen == gen && time.Since(e.at) < 10*time.Minute {
			return slices.Clone(e.items), nil
		}
	}
	items, err := libraryItems(ctx, deps, lib)
	if err != nil {
		return nil, err
	}
	if live {
		c.mu.Lock()
		c.m[lib.ID] = libraryItemsEntry{gen: gen, at: time.Now(), items: items}
		c.mu.Unlock()
	}
	return slices.Clone(items), nil
}

// libraryListItems is a library list's items: its library's movies or
// shows through the browse filter and sort it saved, marked for the person.
func libraryListItems(ctx context.Context, deps Deps, l db.MediaList, marks *watchMarks) ([]BrowseItem, error) {
	p := lists.DecodeParams(l.Params)
	lib, err := deps.Queries.GetLibrary(ctx, p.LibraryID)
	if errors.Is(err, sql.ErrNoRows) {
		return []BrowseItem{}, nil
	}
	if err != nil {
		return nil, err
	}
	items, err := deps.listsState.library.items(ctx, deps, lib)
	if err != nil {
		return nil, err
	}
	marks.apply(items)
	items, _ = filterItems(items, &ItemsInput{Filter: p.Filter, Genre: p.Genre, Decade: p.Decade})
	sortItems(items, lists.LibrarySort(p))
	return items, nil
}

// listCounts are a list's entries, those the library has, and those TMDB
// couldn't find.
type listCounts struct {
	entries, inLibrary, notFound int
}

// rankRef is a list entry the library has: its movie or show.
type rankRef struct {
	rank      int64
	show      bool
	id        int64
	libraryID int64
}

// rankedItem is a list entry the library has, as its card.
type rankedItem struct {
	rank int64
	item BrowseItem
}

// listData is what some lists hold for a screen, read once for its layout
// and its rows: each list's counts, which of its entries the library has,
// and library lists' items.
type listData struct {
	counts  map[int64]listCounts
	ranks   map[int64][]rankRef
	library map[int64][]BrowseItem
	marks   *watchMarks
}

// loadListData reads lists' entries of types. A title in two libraries
// counts once, from the library prefer when it has it, else the first.
// marks marks library lists' items (and filters them, for unwatched);
// when nil and a library list needs them, they are read here.
func loadListData(ctx context.Context, deps Deps, ls []db.MediaList, types []string, prefer int64, marks *watchMarks) (listData, error) {
	d := listData{counts: map[int64]listCounts{}, ranks: map[int64][]rankRef{}, library: map[int64][]BrowseItem{}, marks: marks}
	var ids []int64
	for _, l := range ls {
		if l.SourceKind != lists.KindLibrary {
			ids = append(ids, l.ID)
			continue
		}
		if d.marks == nil {
			var err error
			if d.marks, err = loadWatchMarks(ctx, deps); err != nil {
				return d, err
			}
		}
		items, err := libraryListItems(ctx, deps, l, d.marks)
		if err != nil {
			return d, err
		}
		d.library[l.ID] = items
		d.counts[l.ID] = listCounts{entries: len(items), inLibrary: len(items)}
	}
	if len(ids) == 0 {
		return d, nil
	}
	var fetched []db.MediaList
	for _, l := range ls {
		if l.SourceKind != lists.KindLibrary {
			fetched = append(fetched, l)
		}
	}
	matches, err := matchesOf(ctx, deps, fetched)
	if err != nil {
		return d, err
	}
	for _, id := range ids {
		m := matches[id]
		var n listCounts
		for _, t := range types {
			n.entries += m.totals[t].entries
			n.notFound += m.totals[t].notFound
		}
		// One entry per rank: the library prefer's copy, else the first.
		var refs []rankRef
		for _, r := range m.refs {
			if r.show && !slices.Contains(types, "tv") || !r.show && !slices.Contains(types, "movie") {
				continue
			}
			if k := len(refs) - 1; k >= 0 && refs[k].rank == r.rank {
				if r.libraryID == prefer && refs[k].libraryID != prefer {
					refs[k] = r
				}
				continue
			}
			refs = append(refs, r)
		}
		n.inLibrary = len(refs)
		d.counts[id], d.ranks[id] = n, refs
	}
	return d, nil
}

// cards are the first limit (0: every) entries the library has of each
// list, as cards in rank order, marked for the person. Only those cards
// are read.
func (d listData) cards(ctx context.Context, deps Deps, listIDs []int64, limit int) (map[int64][]rankedItem, error) {
	var movies, shows []int64
	for _, id := range listIDs {
		refs := d.ranks[id]
		if limit > 0 {
			refs = refs[:min(len(refs), limit)]
		}
		for _, r := range refs {
			if r.show {
				shows = append(shows, r.id)
			} else {
				movies = append(movies, r.id)
			}
		}
	}
	gen, live := generation(deps)
	movieCards, showCards := map[int64]BrowseItem{}, map[int64]BrowseItem{}
	var needMovies, needShows []int64
	for _, id := range movies {
		if it, ok := deps.listsState.movieCards.get(gen, id); live && ok {
			movieCards[id] = it
		} else {
			needMovies = append(needMovies, id)
		}
	}
	for _, id := range shows {
		if it, ok := deps.listsState.showCards.get(gen, id); live && ok {
			showCards[id] = it
		} else {
			needShows = append(needShows, id)
		}
	}
	if len(needMovies) > 0 {
		rows, err := deps.Queries.ListMovieCardsByIDs(ctx, slices.Compact(slices.Sorted(slices.Values(needMovies))))
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			it := movieItem(db.ListMovieItemsRow(r))
			movieCards[r.ID] = it
			if live {
				deps.listsState.movieCards.put(gen, r.ID, it)
			}
		}
	}
	if len(needShows) > 0 {
		rows, err := deps.Queries.ListShowCardsByIDs(ctx, slices.Compact(slices.Sorted(slices.Values(needShows))))
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			it := showItem(db.ListShowItemsRow(r))
			showCards[r.ID] = it
			if live {
				deps.listsState.showCards.put(gen, r.ID, it)
			}
		}
	}
	out := map[int64][]rankedItem{}
	for _, id := range listIDs {
		refs := d.ranks[id]
		if limit > 0 {
			refs = refs[:min(len(refs), limit)]
		}
		items := make([]BrowseItem, 0, len(refs))
		ranks := make([]int64, 0, len(refs))
		for _, r := range refs {
			card, ok := movieCards[r.id]
			if r.show {
				card, ok = showCards[r.id]
			}
			if ok {
				items = append(items, card)
				ranks = append(ranks, r.rank)
			}
		}
		d.marks.apply(items)
		ranked := make([]rankedItem, len(items))
		for i := range items {
			ranked[i] = rankedItem{rank: ranks[i], item: items[i]}
		}
		out[id] = ranked
	}
	return out, nil
}

// listCountsOf counts lists' entries of both kinds, for their pages.
func listCountsOf(ctx context.Context, deps Deps, ls []db.MediaList) (map[int64]listCounts, error) {
	d, err := loadListData(ctx, deps, ls, []string{"movie", "tv"}, 0, nil)
	return d.counts, err
}

// rowItems is how many items a row draws (PLAN-ROWS.md Decision 13).
const rowItems = 20

// listScreenRows fills the rows of lists for a screen: the first 20 of
// each list's entries the library has, in the list's order, marked for
// the person, with how many it has and where the list is from.
func listScreenRows(ctx context.Context, deps Deps, d listData, ls []*db.MediaList) (map[int64]ScreenRow, error) {
	out := map[int64]ScreenRow{}
	var ids []int64
	for _, l := range ls {
		if l.SourceKind != lists.KindLibrary {
			ids = append(ids, l.ID)
		}
	}
	cards, err := d.cards(ctx, deps, ids, rowItems)
	if err != nil {
		return nil, err
	}
	for _, l := range ls {
		row := listRow(deps, l)
		n := d.counts[l.ID]
		if l.SourceKind == lists.KindLibrary {
			items := d.library[l.ID]
			if len(items) == 0 {
				continue
			}
			row.Items = items[:min(len(items), rowItems)]
			row.Subtitle = fmt.Sprintf("%s · From %s", plural(len(items), "title"), row.Source.Name)
			out[l.ID] = row
			continue
		}
		ranked := cards[l.ID]
		if len(ranked) == 0 {
			continue
		}
		row.Items = make([]BrowseItem, len(ranked))
		for i, r := range ranked {
			row.Items[i] = r.item
		}
		row.Subtitle = fmt.Sprintf("%d of %d · From %s", n.inLibrary, n.entries, row.Source.Name)
		out[l.ID] = row
	}
	return out, nil
}

// listRow is a list's row without its items.
func listRow(deps Deps, l *db.MediaList) ScreenRow {
	p := lists.DecodeParams(l.Params)
	name := l.SourceKind
	if deps.Lists != nil {
		name = deps.Lists.Name(l.SourceKind, p)
	}
	return ScreenRow{
		ID: listRowID(l.ID), Style: RowStylePoster, Title: l.Name, ListID: l.ID,
		SeeAll: &ScreenRowLink{Kind: "list", ListID: l.ID},
		Source: &ScreenRowSource{Kind: l.SourceKind, Name: name, URL: l.SourceUrl},
	}
}

func plural(n int, word string) string {
	if n == 1 {
		return "1 " + word
	}
	return fmt.Sprintf("%d %ss", n, word)
}

// libraryItemsByTMDB are the library's cards for some TMDB IDs of each
// media type, one per title (from the first library that has it), marked
// for the person.
func libraryItemsByTMDB(ctx context.Context, deps Deps, entries []lists.Entry, marks *watchMarks) (map[string]BrowseItem, error) {
	var movies, shows []int64
	for _, e := range entries {
		switch {
		case !e.Found():
		case e.MediaType == "movie":
			movies = append(movies, e.TMDBID)
		case e.MediaType == "tv":
			shows = append(shows, e.TMDBID)
		}
	}
	out := map[string]BrowseItem{}
	keep := func(kind string, tmdbID int64, it BrowseItem) {
		k := fmt.Sprintf("%s/%d", kind, tmdbID)
		if _, ok := out[k]; !ok {
			out[k] = it
		}
	}
	if len(movies) > 0 {
		rows, err := deps.Queries.ListMovieItemsByTMDB(ctx, movies)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			keep("movie", r.TmdbID, movieItem(db.ListMovieItemsRow{ID: r.ID, LibraryID: r.LibraryID, Title: r.Title, Year: r.Year, ReleaseDate: r.ReleaseDate,
				Overview: r.Overview, RuntimeMin: r.RuntimeMin, Genres: r.Genres, Rating: r.Rating, PosterPath: r.PosterPath, BackdropPath: r.BackdropPath,
				ContentRating: r.ContentRating, FileCount: r.FileCount, AddedAt: r.AddedAt, MaxHeight: r.MaxHeight, HasHdr: r.HasHdr, TotalSize: r.TotalSize}))
		}
	}
	if len(shows) > 0 {
		rows, err := deps.Queries.ListShowItemsByTMDB(ctx, shows)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			keep("tv", r.TmdbID, showItem(db.ListShowItemsRow{ID: r.ID, LibraryID: r.LibraryID, Title: r.Title, Year: r.Year, Overview: r.Overview,
				Status: r.Status, Genres: r.Genres, Rating: r.Rating, PosterPath: r.PosterPath, BackdropPath: r.BackdropPath, LastAirDate: r.LastAirDate,
				NextAirDate: r.NextAirDate, ContentRating: r.ContentRating, EpisodeCount: r.EpisodeCount, AddedAt: r.AddedAt, MaxHeight: r.MaxHeight,
				HasHdr: r.HasHdr, TotalSize: r.TotalSize}))
		}
	}
	for k, it := range out {
		one := []BrowseItem{it}
		marks.apply(one)
		out[k] = one[0]
	}
	return out, nil
}

func entryKey(e lists.Entry) string { return fmt.Sprintf("%s/%d", e.MediaType, e.TMDBID) }

// apiEntry is a list entry as the API shows it.
func apiEntry(e lists.Entry) MediaListEntry {
	return MediaListEntry{Rank: e.Rank, MediaType: e.MediaType, TMDBID: e.TMDBID, IMDbID: e.IMDbID, Title: e.Title, Year: e.Year, PosterPath: e.PosterPath, Found: e.Found()}
}

// requestStates fills in what Request would do for the entries the
// library doesn't have, the way searchRequestable says it, when the person
// may request that kind: requested or downloading when a request is open,
// else requestable. It reads the open requests once for all of them. With
// Seerr, whose state comes from Seerr one title at a time, missing titles
// are requestable here and the request dialog's lookup says more.
func requestStates(ctx context.Context, deps Deps, u auth.User, entries []MediaListEntry) {
	s := deps.Integrations
	if s == nil {
		return
	}
	p := person(ctx, u)
	var movies, shows bool
	seerr := s.Seerr()
	if seerr {
		a := s.SeerrAccess(ctx, p)
		movies, shows = a.Movies, a.Shows
	} else {
		movies, shows = s.Requests()
	}
	if !movies && !shows {
		return
	}
	open := map[string]string{}
	if !seerr {
		rows, err := deps.Queries.ListOpenRequests(ctx)
		if err != nil {
			deps.Log.WarnContext(ctx, "could not read open requests for a list", "err", err)
		}
		for _, r := range rows {
			k := fmt.Sprintf("%s/%d", r.MediaType, r.TmdbID)
			if r.Status == "approved" {
				open[k] = "downloading"
			} else if open[k] == "" {
				open[k] = "requested"
			}
		}
	}
	for i := range entries {
		e := &entries[i]
		switch {
		case !e.Found, (e.MediaType == "movie" && !movies) || (e.MediaType == "tv" && !shows):
		case e.Item != nil:
			e.RequestState = "available"
		default:
			e.RequestState = "requestable"
			if st, ok := open[fmt.Sprintf("%s/%d", e.MediaType, e.TMDBID)]; ok {
				e.RequestState = st
			}
		}
	}
}
