package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"path"
	"slices"
	"sort"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/metadata"
)

// newWindow is how long after its newest file arrived an item can carry the
// New badge. The badge also needs the signed-in person not to have watched
// it (PLAN-HOME.md section 4); the window alone marks items for no one.
const newWindow = 7 * 24 * time.Hour

// BrowseItem is a movie or show as shown on a poster card or grid tile.
// Files that are not matched yet appear too, as kind "unmatched": one item
// per file, or per show folder, with ID the first file's.
type BrowseItem struct {
	Kind      string   `json:"kind" enum:"movie,show,unmatched"`
	ID        int64    `json:"id"`
	LibraryID int64    `json:"libraryId"`
	Title     string   `json:"title"`
	Year      int64    `json:"year,omitempty"`
	Overview  string   `json:"overview"`
	Genres    []string `json:"genres" nullable:"false"`
	Rating    float64  `json:"rating,omitempty"`
	// ContentRating is the US certification from TMDB (TV-14, R).
	ContentRating string    `json:"contentRating,omitempty"`
	PosterPath    string    `json:"posterPath,omitempty"`
	BackdropPath  string    `json:"backdropPath,omitempty"`
	Quality       string    `json:"quality,omitempty" enum:",4K,1080p,720p,SD" doc:"Best resolution across present files."`
	HDR           bool      `json:"hdr"`
	AddedAt       time.Time `json:"addedAt" doc:"When the newest present file was added (see MediaFile.addedAt)."`
	IsNew         bool      `json:"isNew" doc:"A present file arrived within the last 7 days and the signed-in user has not watched it: the film, or for a show an episode added in that time."`
	TotalSize     int64     `json:"totalSize" doc:"Bytes across present files."`
	// Subtitle is a row-specific line such as "S03E05" or "Season 1" for recently added shows.
	Subtitle string `json:"subtitle,omitempty"`
	// EpisodeID and SeasonNumber name the episode a recently added show
	// stands for when it was the only one added, so apps open its page.
	EpisodeID    int64  `json:"episodeId,omitempty" required:"false" doc:"Recently added shows: the one episode added, when the subtitle is its code. Apps open its page rather than the show's."`
	SeasonNumber *int64 `json:"seasonNumber,omitempty" required:"false" doc:"Recently added shows: that episode's season."`
	// Watched is true when the signed-in user finished the movie, or every available episode of the show.
	Watched bool `json:"watched"`
	// Progress is the fraction of a movie watched (0-1) when partway through.
	Progress float64 `json:"progress,omitempty"`

	FileCount   int64  `json:"fileCount,omitempty" doc:"Movies: versions on disk."`
	RuntimeMin  int64  `json:"runtimeMin,omitempty"`
	ReleaseDate string `json:"releaseDate,omitempty"`

	EpisodeCount int64  `json:"episodeCount,omitempty" doc:"Shows: episodes with a present file."`
	Status       string `json:"status,omitempty"`
	LastAirDate  string `json:"lastAirDate,omitempty"`
	NextAirDate  string `json:"nextAirDate,omitempty"`

	FileName    string `json:"fileName,omitempty" doc:"Unmatched: the file's path in the library, or the folder for a group of files."`
	LibraryType string `json:"libraryType,omitempty" enum:",movie,tv" doc:"Unmatched: the library's type, for the link to its Unmatched view."`
}

// EpisodeCard is an episode as shown in a "recently aired" row.
type EpisodeCard struct {
	EpisodeID        int64      `json:"episodeId"`
	ShowID           int64      `json:"showId"`
	ShowTitle        string     `json:"showTitle"`
	SeasonNumber     int64      `json:"seasonNumber"`
	EpisodeNumber    int64      `json:"episodeNumber"`
	Title            string     `json:"title"`
	Overview         string     `json:"overview"`
	AirDate          string     `json:"airDate,omitempty"`
	StillPath        string     `json:"stillPath,omitempty"`
	ShowPosterPath   string     `json:"showPosterPath,omitempty"`
	ShowBackdropPath string     `json:"showBackdropPath,omitempty"`
	HasFile          bool       `json:"hasFile"`
	FileID           *int64     `json:"fileId,omitempty"`
	AddedAt          *time.Time `json:"addedAt,omitempty"`
	IsNew            bool       `json:"isNew" doc:"A present file arrived within the last 7 days and the signed-in user has not watched the episode."`
	Watched          bool       `json:"watched" required:"false" doc:"The signed-in user has watched it."`
}

type LibraryRows struct {
	RecentlyAdded    []BrowseItem  `json:"recentlyAdded" nullable:"false"`
	RecentlyAired    []EpisodeCard `json:"recentlyAired" nullable:"false" doc:"TV libraries only."`
	RecentlyReleased []BrowseItem  `json:"recentlyReleased" nullable:"false" doc:"Movie libraries only."`
	// Rows is the screen in the person's order, sent instead of the fixed
	// arrays when the request asks for arranged=true (PLAN-ROWS.md §6.1).
	Rows []ScreenRow `json:"rows,omitempty" required:"false" nullable:"false" doc:"With arranged=true: the library screen's rows in the person's order, and the three arrays above are empty. From API level 22."`
}

type LibraryRowsOutput struct{ Body LibraryRows }

type LibraryRowsInput struct {
	ID       int64 `path:"id" doc:"Library ID."`
	Arranged bool  `query:"arranged" required:"false" doc:"Send rows in the person's order instead of the fixed arrays (API level 22)."`
}

type HomeInput struct {
	Arranged bool `query:"arranged" required:"false" doc:"Send rows in the person's order instead of continueWatching and the four fixed arrays, which come back empty (API level 22)."`
}

type HomeHero struct {
	Kind         string `json:"kind" enum:"episode,movie,show"`
	Kicker       string `json:"kicker" example:"New episode · Breaking Bad"`
	Title        string `json:"title"`
	Facts        string `json:"facts" example:"S02E01 · Aired 3 days ago · 47 min"`
	Overview     string `json:"overview"`
	BackdropPath string `json:"backdropPath,omitempty"`
	PosterPath   string `json:"posterPath,omitempty"`
	ShowID       *int64 `json:"showId,omitempty"`
	MovieID      *int64 `json:"movieId,omitempty"`
	EpisodeID    *int64 `json:"episodeId,omitempty"`
	FileID       *int64 `json:"fileId,omitempty"`
	SeasonNumber int64  `json:"seasonNumber,omitempty"`
	// ContentRating is the show's or movie's US certification (TV-14, R).
	ContentRating string `json:"contentRating,omitempty"`
	Quality       string `json:"quality,omitempty" enum:",4K,1080p,720p,SD" doc:"The episode's best file, or the movie's or show's best."`
	HDR           bool   `json:"hdr" required:"false"`
}

type HomeTotals struct {
	Movies    int64 `json:"movies"`
	Shows     int64 `json:"shows"`
	Episodes  int64 `json:"episodes"`
	Files     int64 `json:"files"`
	TotalSize int64 `json:"totalSize"`
}

type HomeData struct {
	Hero                *HomeHero      `json:"hero,omitempty"`
	ContinueWatching    []ContinueItem `json:"continueWatching" nullable:"false"`
	MovieLibraryID      *int64         `json:"movieLibraryId,omitempty"`
	TVLibraryID         *int64         `json:"tvLibraryId,omitempty"`
	RecentlyAddedShows  []BrowseItem   `json:"recentlyAddedShows" nullable:"false"`
	RecentlyAired       []EpisodeCard  `json:"recentlyAired" nullable:"false"`
	RecentlyAddedMovies []BrowseItem   `json:"recentlyAddedMovies" nullable:"false"`
	RecentlyReleased    []BrowseItem   `json:"recentlyReleased" nullable:"false"`
	Totals              HomeTotals     `json:"totals"`
	LastScannedAt       *time.Time     `json:"lastScannedAt,omitempty"`
	// Rows is Home in the person's order, sent instead of the fixed arrays
	// when the request asks for arranged=true (PLAN-ROWS.md §6.1).
	Rows []ScreenRow `json:"rows,omitempty" required:"false" nullable:"false" doc:"With arranged=true: Home's rows in the person's order, and continueWatching and the four arrays above are empty. The hero and totals are sent either way. From API level 22."`
}

type HomeOutput struct{ Body HomeData }

type ItemsInput struct {
	ID     int64  `path:"id" doc:"Library ID."`
	Sort   string `query:"sort" enum:"title,added,year,rating,released,runtime,aired" default:"title"`
	Filter string `query:"filter" enum:",continuing,ended,4k,hdr,unwatched" required:"false"`
	Genre  string `query:"genre" required:"false"`
	Decade int    `query:"decade" required:"false" doc:"e.g. 1990"`
	Q      string `query:"q" required:"false" doc:"Title contains."`
	Limit  int64  `query:"limit" default:"100" minimum:"1" maximum:"1000"`
	Offset int64  `query:"offset" default:"0" minimum:"0"`
}

type ItemFacets struct {
	Genres  []string `json:"genres" nullable:"false"`
	Decades []int    `json:"decades" nullable:"false"`
}

// ItemTotals count the whole library whatever the filter, for its header
// ("142 shows · 3,418 episodes · 12 continuing").
type ItemTotals struct {
	Items      int64 `json:"items" doc:"Matched movies or shows with a present file."`
	Episodes   int64 `json:"episodes" doc:"TV: episodes with a present file."`
	Continuing int64 `json:"continuing" doc:"TV: shows that have not ended or been canceled."`
	Unmatched  int64 `json:"unmatched" doc:"Items for files not matched yet: one per file, or per show folder."`
	TotalSize  int64 `json:"totalSize" doc:"Bytes across every item's present files."`
}

// LetterOffset is where a letter's first item sits in the whole sorted list,
// so an A–Z bar can jump to a letter on a later page.
type LetterOffset struct {
	Letter string `json:"letter" doc:"A–Z, or # for titles starting with a digit or symbol. Leading The, A and An are skipped."`
	Offset int64  `json:"offset" doc:"Offset of the letter's first item in the filtered list."`
}

type ItemList struct {
	Items   []BrowseItem   `json:"items" nullable:"false"`
	Total   int64          `json:"total" doc:"Items matching the filter."`
	Limit   int64          `json:"limit"`
	Offset  int64          `json:"offset"`
	Facets  ItemFacets     `json:"facets"`
	Totals  ItemTotals     `json:"totals" required:"false"`
	Letters []LetterOffset `json:"letters,omitempty" required:"false" nullable:"false" doc:"Sorting by title: each letter present in the whole filtered list, in list order, with its first item's offset."`
	// Always sent, including an empty object; optional for older servers.
	GenreCounts map[string]int64 `json:"genreCounts" required:"false" nullable:"false" doc:"Items per genre after filter, decade and q, before the genre filter and pagination. Includes every library genre, with zero when none match."`
}

type ItemsOutput struct{ Body ItemList }

func registerRows(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "listLibraryItems",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/items",
		Summary:     "Browse a library's movies or shows with sorting and filters",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *ItemsInput) (*ItemsOutput, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		items, err := libraryItems(ctx, deps, lib)
		if err != nil {
			return nil, err
		}
		unmatched, err := unmatchedItems(ctx, deps, lib, false)
		if err != nil {
			return nil, err
		}
		totals := totalsOf(items, unmatched)
		items = append(items, unmatched...)
		marks, err := loadWatchMarks(ctx, deps)
		if err != nil {
			return nil, err
		}
		marks.apply(items)
		facets := facetsOf(items)
		items, genreCounts := filterItems(items, in)
		sortItems(items, in.Sort)
		var letters []LetterOffset
		if in.Sort == "title" {
			letters = letterOffsets(items)
		}
		total := int64(len(items))
		start := min(in.Offset, total)
		end := min(start+in.Limit, total)
		return &ItemsOutput{Body: ItemList{Items: items[start:end], Total: total, Limit: in.Limit, Offset: in.Offset, Facets: facets, Totals: totals, Letters: letters, GenreCounts: genreCounts}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getLibraryRows",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/rows",
		Summary:     "Recently added / aired / released rows for a library",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *LibraryRowsInput) (*LibraryRowsOutput, error) {
		lib, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		marks, err := loadWatchMarks(ctx, deps)
		if err != nil {
			return nil, err
		}
		if !in.Arranged {
			rows, err := libraryRows(ctx, deps, lib, 20, marks, allRows)
			if err != nil {
				return nil, err
			}
			return &LibraryRowsOutput{Body: rows}, nil
		}
		// The library's screen in the person's order (PLAN-ROWS.md 6.1):
		// only the rows drawn are filled, and the fixed arrays go empty.
		c, err := catalogue(ctx, deps, screenRef{Name: libraryScreenName(lib.ID), LibraryID: lib.ID}, signedInUser(ctx))
		if err != nil {
			return nil, err
		}
		layout, placed, data, err := screenLayout(ctx, deps, c, marks)
		if err != nil {
			return nil, err
		}
		fixed, err := libraryRows(ctx, deps, lib, 20, marks, wantsOf(layout.Rows))
		if err != nil {
			return nil, err
		}
		arranged, err := libraryScreenRows(ctx, deps, c, placed, layout.Rows, fixed, data)
		if err != nil {
			return nil, err
		}
		return &LibraryRowsOutput{Body: LibraryRows{RecentlyAdded: []BrowseItem{}, RecentlyAired: []EpisodeCard{}, RecentlyReleased: []BrowseItem{}, Rows: arranged}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getHome",
		Method:      http.MethodGet,
		Path:        Prefix + "/home",
		Summary:     "Everything the home screen shows",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *HomeInput) (*HomeOutput, error) {
		marks, err := loadWatchMarks(ctx, deps)
		if err != nil {
			return nil, err
		}
		if !in.Arranged {
			out, err := homeData(ctx, deps, marks, allRows)
			if err != nil {
				return nil, err
			}
			return &HomeOutput{Body: out}, nil
		}
		// Home in the person's order (PLAN-ROWS.md 6.1): the hero and
		// totals as ever, only the rows drawn filled, and the fixed arrays
		// empty. Recently added is always read: the hero comes from it.
		c, err := catalogue(ctx, deps, screenRef{Name: ScreenHome}, signedInUser(ctx))
		if err != nil {
			return nil, err
		}
		layout, placed, data, err := screenLayout(ctx, deps, c, marks)
		if err != nil {
			return nil, err
		}
		out, err := homeData(ctx, deps, marks, wantsOf(layout.Rows))
		if err != nil {
			return nil, err
		}
		if out.Rows, err = homeRows(ctx, deps, c, placed, layout.Rows, out, data); err != nil {
			return nil, err
		}
		out.ContinueWatching, out.RecentlyAddedShows, out.RecentlyAired, out.RecentlyAddedMovies, out.RecentlyReleased = []ContinueItem{}, []BrowseItem{}, []EpisodeCard{}, []BrowseItem{}, []BrowseItem{}
		return &HomeOutput{Body: out}, nil
	})
}

// homeData is Home's hero, totals and fixed rows, filling the rows want
// asks for (all of them for apps that don't arrange rows).
func homeData(ctx context.Context, deps Deps, marks *watchMarks, want rowWants) (HomeData, error) {
	libs, err := deps.Queries.ListLibraries(ctx)
	if err != nil {
		return HomeData{}, err
	}
	out := HomeData{ContinueWatching: []ContinueItem{}, RecentlyAddedShows: []BrowseItem{}, RecentlyAired: []EpisodeCard{}, RecentlyAddedMovies: []BrowseItem{}, RecentlyReleased: []BrowseItem{}}
	if user, ok := auth.UserFrom(ctx); ok && want.continueWatching {
		if out.ContinueWatching, err = continueWatching(ctx, deps, user, 20); err != nil {
			return out, err
		}
	}
	counts, err := fileStatusCounts(ctx, deps)
	if err != nil {
		return out, err
	}
	var newEpisodes []db.ListNewEpisodeFilesRow
	// Each library gives its rows' few items and light counts for the
	// totals, not every item it has.
	for _, lib := range libs {
		size, err := deps.Queries.SumLibrarySize(ctx, lib.ID)
		if err != nil {
			return out, err
		}
		out.Totals.Files += counts[lib.ID].present
		out.Totals.TotalSize += toInt64(size)
		if t := db.ParseTimePtr(lib.LastScannedAt); t != nil && (out.LastScannedAt == nil || t.After(*out.LastScannedAt)) {
			out.LastScannedAt = t
		}
		rows, err := libraryRows(ctx, deps, lib, 20, marks, want)
		if err != nil {
			return out, err
		}
		switch lib.Type {
		case library.TypeMovie:
			n, err := deps.Queries.CountMovieItems(ctx, lib.ID)
			if err != nil {
				return out, err
			}
			if out.MovieLibraryID == nil {
				id := lib.ID
				out.MovieLibraryID = &id
			}
			out.RecentlyAddedMovies = append(out.RecentlyAddedMovies, rows.RecentlyAdded...)
			out.RecentlyReleased = append(out.RecentlyReleased, rows.RecentlyReleased...)
			out.Totals.Movies += n
		case library.TypeTV:
			n, err := deps.Queries.CountShowItems(ctx, lib.ID)
			if err != nil {
				return out, err
			}
			if out.TVLibraryID == nil {
				id := lib.ID
				out.TVLibraryID = &id
			}
			out.RecentlyAddedShows = append(out.RecentlyAddedShows, rows.RecentlyAdded...)
			out.RecentlyAired = append(out.RecentlyAired, rows.RecentlyAired...)
			out.Totals.Shows += n.Shows
			out.Totals.Episodes += n.Episodes
			eps, err := newEpisodeFiles(ctx, deps, lib.ID)
			if err != nil {
				return out, err
			}
			newEpisodes = append(newEpisodes, eps...)
		}
	}
	out.Hero = pickHero(out, newEpisodes)
	episodeHeroDetails(ctx, deps, out.Hero)
	return out, nil
}

// --- building blocks --------------------------------------------------------

func qualityLabel(maxHeight int64) string {
	switch {
	case maxHeight >= 2000:
		return "4K"
	case maxHeight >= 1000:
		return "1080p"
	case maxHeight >= 700:
		return "720p"
	case maxHeight > 0:
		return "SD"
	}
	return ""
}

// toInt64 reads the loosely typed aggregate columns sqlc produces for
// MAX/SUM/CASE expressions (int64, float64, or pointers to either).
func toInt64(v any) int64 {
	switch n := v.(type) {
	case int64:
		return n
	case float64:
		return int64(n)
	case int:
		return int64(n)
	case *int64:
		if n != nil {
			return *n
		}
	case *float64:
		if n != nil {
			return int64(*n)
		}
	}
	return 0
}

func toString(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

func movieItem(r db.ListMovieItemsRow) BrowseItem {
	added := db.ParseTime(toString(r.AddedAt))
	return BrowseItem{
		Kind: "movie", ID: r.ID, LibraryID: r.LibraryID, Title: r.Title, Year: deref(r.Year), Overview: r.Overview,
		Genres: genres(r.Genres), Rating: deref(r.Rating), PosterPath: deref(r.PosterPath), BackdropPath: deref(r.BackdropPath),
		Quality: qualityLabel(toInt64(r.MaxHeight)), HDR: toInt64(r.HasHdr) == 1, AddedAt: added, IsNew: time.Since(added) < newWindow,
		TotalSize: toInt64(r.TotalSize), FileCount: r.FileCount, RuntimeMin: deref(r.RuntimeMin), ReleaseDate: deref(r.ReleaseDate),
		ContentRating: deref(r.ContentRating),
	}
}

func showItem(r db.ListShowItemsRow) BrowseItem {
	added := db.ParseTime(toString(r.AddedAt))
	return BrowseItem{
		Kind: "show", ID: r.ID, LibraryID: r.LibraryID, Title: r.Title, Year: deref(r.Year), Overview: r.Overview,
		Genres: genres(r.Genres), Rating: deref(r.Rating), PosterPath: deref(r.PosterPath), BackdropPath: deref(r.BackdropPath),
		Quality: qualityLabel(toInt64(r.MaxHeight)), HDR: toInt64(r.HasHdr) == 1, AddedAt: added, IsNew: time.Since(added) < newWindow,
		TotalSize: toInt64(r.TotalSize), EpisodeCount: r.EpisodeCount, Status: deref(r.Status),
		LastAirDate: deref(r.LastAirDate), NextAirDate: deref(r.NextAirDate), ContentRating: deref(r.ContentRating),
	}
}

func libraryItems(ctx context.Context, deps Deps, lib db.Library) ([]BrowseItem, error) {
	switch lib.Type {
	case library.TypeMovie:
		rows, err := deps.Queries.ListMovieItems(ctx, lib.ID)
		if err != nil {
			return nil, err
		}
		out := make([]BrowseItem, 0, len(rows))
		for _, r := range rows {
			out = append(out, movieItem(r))
		}
		return out, nil
	case library.TypeTV:
		rows, err := deps.Queries.ListShowItems(ctx, lib.ID)
		if err != nil {
			return nil, err
		}
		out := make([]BrowseItem, 0, len(rows))
		for _, r := range rows {
			out = append(out, showItem(r))
		}
		return out, nil
	}
	return nil, fmt.Errorf("unsupported library type %q", lib.Type)
}

// totalsOf counts a library's matched items (from libraryItems) and its
// unmatched ones.
func totalsOf(items, unmatched []BrowseItem) ItemTotals {
	t := ItemTotals{Items: int64(len(items)), Unmatched: int64(len(unmatched))}
	for _, it := range items {
		t.Episodes += it.EpisodeCount
		t.TotalSize += it.TotalSize
		if it.Kind == "show" && !ended(it.Status) {
			t.Continuing++
		}
	}
	for _, it := range unmatched {
		t.TotalSize += it.TotalSize
	}
	return t
}

// ended reports whether a show's TMDB status says no more episodes come.
func ended(status string) bool {
	return status == "Ended" || status == "Canceled"
}

func facetsOf(items []BrowseItem) ItemFacets {
	gset, dset := map[string]bool{}, map[int]bool{}
	for _, it := range items {
		for _, g := range it.Genres {
			gset[g] = true
		}
		if it.Year > 0 {
			dset[int(it.Year)/10*10] = true
		}
	}
	f := ItemFacets{Genres: []string{}, Decades: []int{}}
	for g := range gset {
		f.Genres = append(f.Genres, g)
	}
	for d := range dset {
		f.Decades = append(f.Decades, d)
	}
	sort.Strings(f.Genres)
	sort.Sort(sort.Reverse(sort.IntSlice(f.Decades)))
	return f
}

// filterItems also counts genres before applying the selected genre, so a
// filter panel can show each choice's total without loading the library again.
func filterItems(items []BrowseItem, in *ItemsInput) ([]BrowseItem, map[string]int64) {
	q := strings.ToLower(strings.TrimSpace(in.Q))
	out := items[:0:0]
	genreCounts := map[string]int64{}
	for _, it := range items {
		for _, genre := range it.Genres {
			if _, ok := genreCounts[genre]; !ok {
				genreCounts[genre] = 0
			}
		}
		if it.Kind == "unmatched" && (in.Filter == "continuing" || in.Filter == "unwatched") {
			continue
		}
		switch in.Filter {
		case "continuing":
			if ended(it.Status) {
				continue
			}
		case "ended":
			if !ended(it.Status) {
				continue
			}
		case "4k":
			if it.Quality != "4K" {
				continue
			}
		case "hdr":
			if !it.HDR {
				continue
			}
		case "unwatched":
			if it.Watched {
				continue
			}
		}
		if in.Decade > 0 && (it.Year < int64(in.Decade) || it.Year >= int64(in.Decade)+10) {
			continue
		}
		if q != "" && !strings.Contains(strings.ToLower(it.Title), q) {
			continue
		}
		for i, genre := range it.Genres {
			if !slices.Contains(it.Genres[:i], genre) {
				genreCounts[genre]++
			}
		}
		if in.Genre != "" && !containsFold(it.Genres, in.Genre) {
			continue
		}
		out = append(out, it)
	}
	return out, genreCounts
}

func containsFold(list []string, s string) bool {
	for _, v := range list {
		if strings.EqualFold(v, s) {
			return true
		}
	}
	return false
}

func sortItems(items []BrowseItem, by string) {
	less := func(i, j int) bool { return titleKey(items[i].Title) < titleKey(items[j].Title) }
	switch by {
	case "added":
		less = func(i, j int) bool { return items[i].AddedAt.After(items[j].AddedAt) }
	case "year":
		less = func(i, j int) bool { return items[i].Year > items[j].Year }
	case "rating":
		less = func(i, j int) bool { return items[i].Rating > items[j].Rating }
	case "released":
		less = func(i, j int) bool { return items[i].ReleaseDate > items[j].ReleaseDate }
	case "runtime":
		less = func(i, j int) bool { return items[i].RuntimeMin > items[j].RuntimeMin }
	case "aired":
		less = func(i, j int) bool { return items[i].LastAirDate > items[j].LastAirDate }
	}
	sort.SliceStable(items, less)
}

// titleKey sorts "The Matrix" under M.
func titleKey(t string) string {
	t = strings.ToLower(t)
	for _, a := range []string{"the ", "a ", "an "} {
		if strings.HasPrefix(t, a) {
			return t[len(a):]
		}
	}
	return t
}

// letterOf is an item's A–Z bar letter, from the key it sorts by: "The
// Matrix" is under M, and titles that start with a digit or symbol under #.
func letterOf(title string) string {
	k := titleKey(title)
	if k != "" && k[0] >= 'a' && k[0] <= 'z' {
		return string(k[0] - 'a' + 'A')
	}
	return "#"
}

// letterOffsets lists the offset of each letter's first item in items, which
// are sorted by title. # can come twice in the sort (punctuation and digits
// before a, accented letters after z); it keeps the first.
func letterOffsets(items []BrowseItem) []LetterOffset {
	out := []LetterOffset{}
	seen := map[string]bool{}
	for i, it := range items {
		l := letterOf(it.Title)
		if !seen[l] {
			seen[l] = true
			out = append(out, LetterOffset{Letter: l, Offset: int64(i)})
		}
	}
	return out
}

// libraryRows builds a library's rows from the few items each shows, marked
// watched for the signed-in user with marks. Recently added is always
// built (Home's hero comes from it); released and aired as want says.
func libraryRows(ctx context.Context, deps Deps, lib db.Library, limit int, marks *watchMarks, want rowWants) (LibraryRows, error) {
	rows := LibraryRows{RecentlyAdded: []BrowseItem{}, RecentlyAired: []EpisodeCard{}, RecentlyReleased: []BrowseItem{}}
	switch lib.Type {
	case library.TypeMovie:
		added, err := deps.Queries.ListRecentlyAddedMovieItems(ctx, db.ListRecentlyAddedMovieItemsParams{LibraryID: lib.ID, Limit: int64(limit)})
		if err != nil {
			return rows, err
		}
		for _, r := range added {
			rows.RecentlyAdded = append(rows.RecentlyAdded, movieItem(db.ListMovieItemsRow(r)))
		}
		if want.released {
			released, err := deps.Queries.ListRecentlyReleasedMovieItems(ctx, db.ListRecentlyReleasedMovieItemsParams{LibraryID: lib.ID, Limit: int64(limit)})
			if err != nil {
				return rows, err
			}
			for _, r := range released {
				rows.RecentlyReleased = append(rows.RecentlyReleased, movieItem(db.ListMovieItemsRow(r)))
			}
			marks.apply(rows.RecentlyReleased)
		}
	case library.TypeTV:
		added, err := recentlyAddedShows(ctx, deps, lib, limit)
		if err != nil {
			return rows, err
		}
		rows.RecentlyAdded = added
		if want.aired {
			if rows.RecentlyAired, err = recentlyAired(ctx, deps, lib, limit); err != nil {
				return rows, err
			}
		}
	}
	marks.apply(rows.RecentlyAdded)
	// Unmatched files are added things too: they take their place by date,
	// including new episodes of a known show that still need one picked.
	unmatched, err := unmatchedItems(ctx, deps, lib, true)
	if err != nil {
		return rows, err
	}
	added := append(rows.RecentlyAdded, unmatched...)
	sortItems(added, "added")
	rows.RecentlyAdded = added[:min(limit, len(added))]
	return rows, nil
}

// recentlyAired is a TV library's latest aired episodes, newest first. It
// looks back a month, then a year, then over everything, until it has
// limit episodes.
func recentlyAired(ctx context.Context, deps Deps, lib db.Library, limit int) ([]EpisodeCard, error) {
	now := time.Now().UTC()
	today := now.Format(time.DateOnly)
	var aired []db.ListRecentlyAiredEpisodesRow
	for _, days := range []int{30, 365, 0} {
		since := ""
		if days > 0 {
			since = now.AddDate(0, 0, -days).Format(time.DateOnly)
		}
		var err error
		aired, err = deps.Queries.ListRecentlyAiredEpisodes(ctx, db.ListRecentlyAiredEpisodesParams{LibraryID: lib.ID, Since: &since, Today: &today, Limit: int64(limit)})
		if err != nil {
			return nil, err
		}
		if len(aired) >= limit {
			break
		}
	}
	ids := make([]int64, len(aired))
	for i, r := range aired {
		ids[i] = r.ID
	}
	watched, err := watchedEpisodes(ctx, deps, ids)
	if err != nil {
		return nil, err
	}
	out := make([]EpisodeCard, 0, len(aired))
	for _, r := range aired {
		c := EpisodeCard{
			EpisodeID: r.ID, ShowID: r.ShowID, ShowTitle: r.ShowTitle, SeasonNumber: r.SeasonNumber, EpisodeNumber: r.Number,
			Title: r.Title, Overview: r.Overview, AirDate: deref(r.AirDate), StillPath: deref(r.StillPath),
			ShowPosterPath: deref(r.ShowPosterPath), ShowBackdropPath: deref(r.ShowBackdropPath), Watched: watched[r.ID],
		}
		if id := toInt64(r.FileID); id > 0 {
			c.HasFile, c.FileID = true, &id
		}
		if s := toString(r.AddedAt); s != "" {
			t := db.ParseTime(s)
			c.AddedAt = &t
			c.IsNew = time.Since(t) < newWindow && !watched[r.ID]
		}
		out = append(out, c)
	}
	return out, nil
}

// recentlyAddedShows groups the newest episode files per show and labels
// each group: a single episode, a whole season, the complete series, or a
// count of episodes. Only the shows shown are read in full.
func recentlyAddedShows(ctx context.Context, deps Deps, lib db.Library, limit int) ([]BrowseItem, error) {
	files, err := deps.Queries.ListRecentlyAddedEpisodeFiles(ctx, db.ListRecentlyAddedEpisodeFilesParams{LibraryID: lib.ID, Limit: 500})
	if err != nil {
		return nil, err
	}
	type group struct {
		first    db.ListRecentlyAddedEpisodeFilesRow
		episodes map[int64]bool
		seasons  map[int64]int64 // season number -> episode_count on TMDB
		perSeas  map[int64]map[int64]bool
	}
	var order []int64
	groups := map[int64]*group{}
	for _, f := range files {
		g, ok := groups[f.ShowID]
		if !ok {
			g = &group{first: f, episodes: map[int64]bool{}, seasons: map[int64]int64{}, perSeas: map[int64]map[int64]bool{}}
			groups[f.ShowID] = g
			order = append(order, f.ShowID)
		}
		// Only files added within a day of the newest one count as the same "batch".
		if db.ParseTime(g.first.AddedAt).Sub(db.ParseTime(f.AddedAt)) > 24*time.Hour {
			continue
		}
		g.episodes[f.EpisodeID] = true
		g.seasons[f.SeasonNumber] = f.SeasonEpisodeCount
		if g.perSeas[f.SeasonNumber] == nil {
			g.perSeas[f.SeasonNumber] = map[int64]bool{}
		}
		g.perSeas[f.SeasonNumber][f.EpisodeID] = true
	}
	out := make([]BrowseItem, 0, min(limit, len(order)))
	// The shows' cards, a row's worth at a time: a show missing from the
	// library's items (it has no present file there) leaves room for the next.
	for start := 0; start < len(order) && len(out) < limit; start += limit {
		ids := order[start:min(start+limit, len(order))]
		shows, err := deps.Queries.ListShowItemsByIDs(ctx, db.ListShowItemsByIDsParams{LibraryID: lib.ID, Ids: ids})
		if err != nil {
			return nil, err
		}
		items := make(map[int64]BrowseItem, len(shows))
		for _, r := range shows {
			items[r.ID] = showItem(db.ListShowItemsRow(r))
		}
		counts, err := deps.Queries.CountEpisodesByShows(ctx, ids)
		if err != nil {
			return nil, err
		}
		totals := make(map[int64]int64, len(counts))
		for _, c := range counts {
			totals[c.ShowID] = c.Episodes
		}
		for _, showID := range ids {
			item, ok := items[showID]
			if !ok || len(out) == limit {
				continue
			}
			g := groups[showID]
			item.AddedAt = db.ParseTime(g.first.AddedAt)
			item.IsNew = time.Since(item.AddedAt) < newWindow
			total := totals[showID]
			switch {
			case len(g.episodes) == 1:
				item.Subtitle = seCode(g.first.SeasonNumber, g.first.EpisodeNumber)
				item.EpisodeID = g.first.EpisodeID
				item.SeasonNumber = &g.first.SeasonNumber
			case total > 0 && int64(len(g.episodes)) >= total:
				item.Subtitle = "Complete series"
			case len(g.seasons) == 1 && int64(len(g.perSeas[g.first.SeasonNumber])) >= g.seasons[g.first.SeasonNumber] && g.seasons[g.first.SeasonNumber] > 0:
				item.Subtitle = fmt.Sprintf("Season %d", g.first.SeasonNumber)
			default:
				item.Subtitle = fmt.Sprintf("%d episodes", len(g.episodes))
			}
			out = append(out, item)
		}
	}
	// New only while an episode the card stands for is unwatched: a card
	// for a watched episode, or a season watched through, is not New.
	var batch []int64
	for _, item := range out {
		if item.IsNew {
			for id := range groups[item.ID].episodes {
				batch = append(batch, id)
			}
		}
	}
	watched, err := watchedEpisodes(ctx, deps, batch)
	if err != nil {
		return nil, err
	}
	for i := range out {
		item := &out[i]
		if !item.IsNew {
			continue
		}
		item.IsNew = false
		for id := range groups[item.ID].episodes {
			if !watched[id] {
				item.IsNew = true
				break
			}
		}
	}
	return out, nil
}

// watchedEpisodes says which of some episodes the signed-in user has
// finished; none when no one is signed in.
func watchedEpisodes(ctx context.Context, deps Deps, ids []int64) (map[int64]bool, error) {
	out := map[int64]bool{}
	user, ok := auth.UserFrom(ctx)
	if !ok || len(ids) == 0 {
		return out, nil
	}
	watched, err := deps.Queries.ListWatchedEpisodeIDs(ctx, db.ListWatchedEpisodeIDsParams{UserID: user.ID, Ids: ids})
	for _, id := range watched {
		out[id] = true
	}
	return out, err
}

// watchMarks are the signed-in user's movie states, per-show watched
// counts and the shows with a New episode they have not watched, read once
// per request however many items it marks.
type watchMarks struct {
	movies   map[int64]db.WatchState
	shows    map[int64]int64
	newShows map[int64]bool
}

// loadWatchMarks reads the signed-in user's marks; nil, which marks
// nothing, when no one is signed in.
func loadWatchMarks(ctx context.Context, deps Deps) (*watchMarks, error) {
	user, ok := auth.UserFrom(ctx)
	if !ok {
		return nil, nil
	}
	movieStates, err := deps.Queries.ListWatchStatesByType(ctx, db.ListWatchStatesByTypeParams{UserID: user.ID, ItemType: "movie"})
	if err != nil {
		return nil, err
	}
	showCounts, err := deps.Queries.ListShowWatchedCounts(ctx, user.ID)
	if err != nil {
		return nil, err
	}
	newShows, err := deps.Queries.ListShowsWithNewEpisodes(ctx, db.ListShowsWithNewEpisodesParams{Since: db.FormatTime(time.Now().Add(-newWindow)), UserID: user.ID})
	if err != nil {
		return nil, err
	}
	m := &watchMarks{movies: make(map[int64]db.WatchState, len(movieStates)), shows: make(map[int64]int64, len(showCounts)), newShows: make(map[int64]bool, len(newShows))}
	for _, w := range movieStates {
		m.movies[w.ItemID] = w
	}
	for _, c := range showCounts {
		m.shows[c.ShowID] = c.Watched
	}
	for _, id := range newShows {
		m.newShows[id] = true
	}
	return m, nil
}

// apply fills Watched and Progress: a movie's from its state, a show's
// when every episode on disk is watched. It also takes New off what the
// person has watched: a finished movie, or a show none of whose recently
// added episodes is still unwatched. It only ever clears New, so a card
// that already judged its own episodes (recentlyAddedShows) keeps its
// answer.
func (m *watchMarks) apply(items []BrowseItem) {
	if m == nil {
		return
	}
	for i := range items {
		it := &items[i]
		switch it.Kind {
		case "movie":
			if w, ok := m.movies[it.ID]; ok {
				it.Watched = w.Completed == 1
				if !it.Watched && w.DurationMs > 0 {
					it.Progress = float64(w.PositionMs) / float64(w.DurationMs)
				}
			}
			if it.Watched {
				it.IsNew = false
			}
		case "show":
			it.Watched = it.EpisodeCount > 0 && m.shows[it.ID] >= it.EpisodeCount
			it.IsNew = it.IsNew && m.newShows[it.ID]
		}
	}
}

// heroWindow is how long after airing an episode counts as new for Home's
// banner.
const heroWindow = 14 * 24 * time.Hour

// newEpisodeFiles are a TV library's files for episodes that aired in the
// last two weeks, newest file first, marked for the signed-in person.
func newEpisodeFiles(ctx context.Context, deps Deps, libraryID int64) ([]db.ListNewEpisodeFilesRow, error) {
	var userID int64
	if user, ok := auth.UserFrom(ctx); ok {
		userID = user.ID
	}
	now := time.Now().UTC()
	today := now.Format(time.DateOnly)
	return deps.Queries.ListNewEpisodeFiles(ctx, db.ListNewEpisodeFilesParams{
		UserID: userID, LibraryID: libraryID, Since: now.Add(-heroWindow).Format(time.DateOnly),
		Today: &today, Limit: 500,
	})
}

// pickHero chooses Home's banner for the signed-in person. First a new
// episode they haven't watched or started (a started one is in Continue
// watching already), from shows they watch before others; else the newest
// movie they haven't watched, then the newest show.
func pickHero(h HomeData, newEpisodes []db.ListNewEpisodeFilesRow) *HomeHero {
	if e, ok := heroEpisode(newEpisodes); ok {
		showID, epID, fileID := e.ShowID, e.EpisodeID, e.FileID
		return &HomeHero{
			Kind: "episode", Kicker: "New episode · " + e.ShowTitle, Title: e.Title,
			Facts:    seCode(e.SeasonNumber, e.EpisodeNumber) + " · Aired " + deref(e.AirDate),
			Overview: e.Overview, BackdropPath: deref(e.ShowBackdropPath), PosterPath: deref(e.ShowPosterPath),
			ShowID: &showID, EpisodeID: &epID, FileID: &fileID, SeasonNumber: e.SeasonNumber,
		}
	}
	if m, ok := firstMatched(h.RecentlyAddedMovies); ok {
		id := m.ID
		facts := []string{}
		if m.Year > 0 {
			facts = append(facts, fmt.Sprintf("%d", m.Year))
		}
		if m.RuntimeMin > 0 {
			facts = append(facts, fmt.Sprintf("%d min", m.RuntimeMin))
		}
		if m.Quality != "" {
			facts = append(facts, m.Quality)
		}
		return &HomeHero{Kind: "movie", Kicker: "Recently added", Title: m.Title, Facts: strings.Join(facts, " · "),
			Overview: m.Overview, BackdropPath: m.BackdropPath, PosterPath: m.PosterPath, MovieID: &id,
			ContentRating: m.ContentRating, Quality: m.Quality, HDR: m.HDR}
	}
	if s, ok := firstMatched(h.RecentlyAddedShows); ok {
		id := s.ID
		return &HomeHero{Kind: "show", Kicker: "Recently added", Title: s.Title, Facts: s.Subtitle,
			Overview: s.Overview, BackdropPath: s.BackdropPath, PosterPath: s.PosterPath, ShowID: &id,
			ContentRating: s.ContentRating, Quality: s.Quality, HDR: s.HDR}
	}
	return nil
}

// episodeHeroDetails gives an episode hero its show's content rating and
// its file's quality; the episode card it came from has neither.
func episodeHeroDetails(ctx context.Context, deps Deps, h *HomeHero) {
	if h == nil || h.Kind != "episode" {
		return
	}
	if h.ShowID != nil {
		if s, err := deps.Queries.GetShow(ctx, *h.ShowID); err == nil {
			h.ContentRating = deref(s.ContentRating)
		} else if !errors.Is(err, sql.ErrNoRows) {
			deps.Log.WarnContext(ctx, "could not read the hero's show", "show", *h.ShowID, "err", err)
		}
	}
	if h.FileID != nil {
		if f, err := deps.Queries.GetMediaFile(ctx, *h.FileID); err == nil {
			h.Quality, h.HDR = qualityLabel(deref(f.Height)), f.Hdr != ""
		} else if !errors.Is(err, sql.ErrNoRows) {
			deps.Log.WarnContext(ctx, "could not read the hero's file", "file", *h.FileID, "err", err)
		}
	}
}

// heroEpisode picks the banner's episode from newEpisodeFiles' rows, leaving
// out those the person has watched or started. The show is one they watch if
// any, then the one whose episode arrived last, so the banner moves on as
// episodes arrive rather than resting on one show all week. Of that show's
// new episodes it takes the first, so a season released at once starts at
// episode 1.
func heroEpisode(rows []db.ListNewEpisodeFilesRow) (db.ListNewEpisodeFilesRow, bool) {
	var show *db.ListNewEpisodeFilesRow
	first := map[int64]db.ListNewEpisodeFilesRow{}
	for i := range rows {
		r := &rows[i]
		if r.Seen {
			continue
		}
		if show == nil || (r.Follows && !show.Follows) || (r.Follows == show.Follows && r.AddedAt > show.AddedAt) {
			show = r
		}
		if f, ok := first[r.ShowID]; !ok || episodeBefore(*r, f) {
			first[r.ShowID] = *r
		}
	}
	if show == nil {
		return db.ListNewEpisodeFilesRow{}, false
	}
	return first[show.ShowID], true
}

// episodeBefore orders a show's new episodes by season, specials last, then
// by episode; of one episode's files, the taller comes first.
func episodeBefore(a, b db.ListNewEpisodeFilesRow) bool {
	season := func(n int64) int64 {
		if n == 0 {
			return math.MaxInt64
		}
		return n
	}
	if season(a.SeasonNumber) != season(b.SeasonNumber) {
		return season(a.SeasonNumber) < season(b.SeasonNumber)
	}
	if a.EpisodeNumber != b.EpisodeNumber {
		return a.EpisodeNumber < b.EpisodeNumber
	}
	return deref(a.Height) > deref(b.Height)
}

// firstMatched is the first matched item the person hasn't watched, else
// the first matched item.
func firstMatched(items []BrowseItem) (BrowseItem, bool) {
	watched := -1
	for i, it := range items {
		switch {
		case it.Kind == "unmatched":
		case !it.Watched:
			return it, true
		case watched < 0:
			watched = i
		}
	}
	if watched >= 0 {
		return items[watched], true
	}
	return BrowseItem{}, false
}

// unmatchedItems are the library's files that are not matched to a movie or
// show yet, for lists: one per file, or per show folder, named after the file
// or folder. Ignored files are left out, and so are episodes of a show that
// is already in the list (Pick episodes) unless withEpisodes is set, as for
// Recently added, where they are new arrivals the show's card does not show.
func unmatchedItems(ctx context.Context, deps Deps, lib db.Library, withEpisodes bool) ([]BrowseItem, error) {
	rows, err := deps.Queries.ListFilesNeedingMatch(ctx, lib.ID)
	if err != nil || len(rows) == 0 {
		return []BrowseItem{}, err
	}
	overrides, err := deps.Queries.ListMatchOverrides(ctx, lib.ID)
	if err != nil {
		return nil, err
	}
	groups, err := buildUnmatchedGroups(ctx, deps.Queries, lib, rows, overrides)
	if err != nil {
		return nil, err
	}
	byID := make(map[int64]db.ListFilesNeedingMatchRow, len(rows))
	for _, r := range rows {
		byID[r.ID] = r
	}
	out := make([]BrowseItem, 0, len(groups))
	for _, g := range groups {
		if g.Kind == GroupIgnored || (g.Kind == GroupEpisodes && !withEpisodes) || len(g.Files) == 0 {
			continue
		}
		it := BrowseItem{Kind: "unmatched", ID: g.Files[0].ID, LibraryID: lib.ID, LibraryType: lib.Type, Genres: []string{}, FileCount: int64(len(g.Files))}
		var maxHeight int64
		for _, f := range g.Files {
			r := byID[f.ID]
			if t := db.ParseTime(r.AddedAt); t.After(it.AddedAt) {
				it.AddedAt = t
			}
			maxHeight = max(maxHeight, deref(r.Height))
			it.TotalSize += r.Size
		}
		it.Quality = qualityLabel(maxHeight)
		it.IsNew = time.Since(it.AddedAt) < newWindow
		if len(g.Files) == 1 {
			it.FileName = g.Files[0].RelativePath
			it.Title = path.Base(it.FileName)
		} else {
			it.FileName = g.Folder
			it.Title = path.Base(g.Folder)
			if g.Folder == "" {
				it.Title = g.Title
			}
		}
		if g.Kind == GroupEpisodes && g.Show != nil {
			it.Title = g.Show.Title
		}
		switch {
		case g.Kind == GroupEpisodes && len(g.Files) > 1:
			it.Subtitle = fmt.Sprintf("%d episodes to pick", len(g.Files))
		case g.Kind == GroupEpisodes:
			it.Subtitle = "Episode to pick"
		case g.Kind == GroupLookup:
			it.Subtitle = "Not looked up yet"
		case len(g.Files) > 1:
			it.Subtitle = fmt.Sprintf("Not matched · %d files", len(g.Files))
		default:
			it.Subtitle = "Not matched"
		}
		out = append(out, it)
	}
	return out, nil
}

// credits decodes the stored cast list.
func credits(raw string) []metadata.Credit {
	var out []metadata.Credit
	if err := json.Unmarshal([]byte(raw), &out); err != nil || out == nil {
		return []metadata.Credit{}
	}
	return out
}
