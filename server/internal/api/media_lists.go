package api

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/lists"
	"watchmymedia/server/internal/memo"
)

// List limits (PLAN-ROWS.md Decision 13).
const (
	maxListsPerPerson = 25
	maxListsOnServer  = 200
	maxListEntries    = 250
	maxImportBytes    = 1 << 20
)

// MediaListSource says where a list comes from and with what options
// (PLAN-ROWS.md §3, §7.2). One flat object for every kind: each kind reads
// the fields it names and ignores the rest.
type MediaListSource struct {
	Kind string `json:"kind" doc:"tmdb, mdblist, library or import. A plain string: show a list whose kind you don't know, and don't offer to edit it."`

	// TMDB.
	Chart     string  `json:"chart,omitempty" required:"false" doc:"tmdb: trending-day, trending-week, popular, top-rated, now-playing (movies), upcoming (movies), on-the-air (TV), airing-today (TV), discover, list or collection."`
	Ref       string  `json:"ref,omitempty" required:"false" maxLength:"200" doc:"tmdb list or collection: its TMDB ID or link. mdblist: the list's ID, user/name, or its mdblist.com link."`
	Genres    []int64 `json:"genres,omitempty" required:"false" maxItems:"10" doc:"tmdb discover: TMDB genre IDs (from getMediaListSources), all of which must match."`
	YearFrom  int     `json:"yearFrom,omitempty" required:"false" minimum:"0" maximum:"3000" doc:"tmdb discover: released (or first aired) in or after this year."`
	YearTo    int     `json:"yearTo,omitempty" required:"false" minimum:"0" maximum:"3000" doc:"tmdb discover: released in or before this year."`
	MinRating float64 `json:"minRating,omitempty" required:"false" minimum:"0" maximum:"10" doc:"tmdb discover: TMDB rating at least this."`
	MinVotes  int     `json:"minVotes,omitempty" required:"false" minimum:"0" doc:"tmdb discover: at least this many votes on TMDB."`
	Language  string  `json:"language,omitempty" required:"false" maxLength:"8" doc:"tmdb discover: original language, ISO 639-1 (ko, fr)."`
	SortBy    string  `json:"sortBy,omitempty" required:"false" doc:"tmdb discover: popularity (default), rating, votes or newest."`

	// The library.
	LibraryID int64  `json:"libraryId,omitempty" required:"false" doc:"library: the library to filter."`
	Sort      string `json:"sort,omitempty" required:"false" doc:"library: as listLibraryItems' sort."`
	Filter    string `json:"filter,omitempty" required:"false" doc:"library: as listLibraryItems' filter."`
	Genre     string `json:"genre,omitempty" required:"false" doc:"library: one of the library's genres."`
	Decade    int    `json:"decade,omitempty" required:"false" doc:"library: e.g. 1990."`

	// An import.
	Text   string `json:"text,omitempty" required:"false" maxLength:"1048576" doc:"import, when making the list: the pasted text or file: an IMDb list export (CSV), a Letterboxd export (CSV), or lines of tt IDs, TMDB links or Title (Year). Never sent back."`
	Format string `json:"format,omitempty" required:"false" doc:"import, in replies: what the text was read as: imdb, letterboxd or lines."`
}

// MediaList is a list a row is made from.
type MediaList struct {
	ID        int64           `json:"id"`
	Name      string          `json:"name"`
	MediaType string          `json:"mediaType" doc:"movie, tv or all (imports and MDBList lists can hold both). A plain string."`
	Source    MediaListSource `json:"source"`
	Owner     string          `json:"owner" doc:"me (the person's own) or everyone (the household's). A plain string."`
	CanEdit   bool            `json:"canEdit" doc:"The person may change or delete it: their own, or any household list for an admin."`
	Screen    string          `json:"screen" doc:"The screen its row was made on and first shows on: home or library-<id>."`
	Limit     int             `json:"limit" doc:"Most entries kept."`

	Entries     int        `json:"entries" doc:"Entries fetched."`
	InLibrary   int        `json:"inLibrary" doc:"Entries the library has now."`
	NotFound    int        `json:"notFound,omitempty" required:"false" doc:"Imports: entries TMDB could not find."`
	Fetching    bool       `json:"fetching" doc:"A fetch is running."`
	FetchedAt   *time.Time `json:"fetchedAt,omitempty" required:"false" doc:"The last fetch that worked."`
	NextFetchAt *time.Time `json:"nextFetchAt,omitempty" required:"false" doc:"Absent for imports and library lists, which are not fetched."`
	Error       string     `json:"error,omitempty" required:"false" example:"MDBList refused the key" doc:"Why the last fetch failed; the list keeps its last good entries."`
	ErrorAt     *time.Time `json:"errorAt,omitempty" required:"false"`
	CreatedAt   time.Time  `json:"createdAt"`
	UpdatedAt   time.Time  `json:"updatedAt"`

	SourceName string `json:"sourceName,omitempty" required:"false" example:"TMDB" doc:"The words for From <name>, as ScreenRowSource.name."`
	SourceURL  string `json:"sourceUrl,omitempty" required:"false" doc:"The list's page on TMDB or mdblist.com, when it has one."`
}

type MediaListOutput struct{ Body MediaList }

type MediaListsOutput struct {
	Body struct {
		Items []MediaList `json:"items" nullable:"false" doc:"The person's own lists, then the household's."`
	}
}

// MediaListEntry is one title on a list, in the list's order.
type MediaListEntry struct {
	Rank       int    `json:"rank" doc:"1 for the first."`
	MediaType  string `json:"mediaType" doc:"movie or tv."`
	TMDBID     int64  `json:"tmdbId,omitempty" required:"false" doc:"Absent when TMDB could not find the title (imports)."`
	IMDbID     string `json:"imdbId,omitempty" required:"false"`
	Title      string `json:"title"`
	Year       int64  `json:"year,omitempty" required:"false"`
	PosterPath string `json:"posterPath,omitempty" required:"false" doc:"TMDB's poster, for titles the library doesn't have."`
	Found      bool   `json:"found" doc:"False for an imported title TMDB could not find: show it as Not found."`
	// Item is the library's movie or show, when it has the title.
	Item *BrowseItem `json:"item,omitempty" required:"false" doc:"The library's movie or show, when it has the title."`
	// RequestState is what Request would do, as Requestable.state, when the
	// person may request and the server has somewhere to send requests.
	RequestState string `json:"requestState,omitempty" required:"false" doc:"requestable, requested, downloading or available, as Requestable.state, when the person may request. Absent when requests aren't set up. A plain string."`
}

type MediaListEntries struct {
	Items     []MediaListEntry `json:"items" nullable:"false"`
	Total     int              `json:"total"`
	InLibrary int              `json:"inLibrary"`
	Limit     int              `json:"limit"`
	Offset    int              `json:"offset"`
}

type MediaListIDInput struct {
	ID int64 `path:"id" doc:"List ID."`
}

type MediaListEntriesInput struct {
	ID     int64 `path:"id" doc:"List ID."`
	Limit  int   `query:"limit" default:"100" minimum:"1" maximum:"250"`
	Offset int   `query:"offset" default:"0" minimum:"0"`
}

// MediaListBody is what makes a list, for createMediaList and previewMediaList.
type MediaListBody struct {
	Name      string          `json:"name" minLength:"1" maxLength:"80"`
	MediaType string          `json:"mediaType" enum:"movie,tv,all"`
	Source    MediaListSource `json:"source"`
	Screen    string          `json:"screen" pattern:"^(home|library-[0-9]+)$" doc:"Where its row first shows: home or library-<id>."`
	Limit     int             `json:"limit,omitempty" required:"false" minimum:"1" maximum:"250" default:"50"`
	Shared    bool            `json:"shared,omitempty" required:"false" doc:"Admins: a list for everyone in the household."`
}

type CreateMediaListInput struct {
	Body MediaListBody
}

type UpdateMediaListInput struct {
	ID   int64 `path:"id" doc:"List ID."`
	Body struct {
		Name   *string          `json:"name,omitempty" minLength:"1" maxLength:"80"`
		Source *MediaListSource `json:"source,omitempty" required:"false" doc:"The whole source, replaced; the list is fetched again. An import's text can't be changed: make a new list."`
		Limit  *int             `json:"limit,omitempty" minimum:"1" maximum:"250"`
		Shared *bool            `json:"shared,omitempty" required:"false" doc:"Admins only."`
	}
}

type PreviewMediaListInput struct {
	Body struct {
		MediaType string          `json:"mediaType" enum:"movie,tv,all"`
		Source    MediaListSource `json:"source"`
		Limit     int             `json:"limit,omitempty" required:"false" minimum:"1" maximum:"250" default:"50"`
	}
}

type MediaListPreview struct {
	Items     []MediaListEntry `json:"items" nullable:"false" doc:"The first 50 entries."`
	Total     int              `json:"total" doc:"Entries the list would keep."`
	InLibrary int              `json:"inLibrary"`
	NotFound  int              `json:"notFound,omitempty" required:"false"`
	Format    string           `json:"format,omitempty" required:"false" doc:"Imports: imdb, letterboxd or lines."`
}

// MediaListSourceKind is a source the server can make lists from.
type MediaListSourceKind struct {
	Kind   string   `json:"kind" doc:"tmdb, mdblist, library or import. A plain string: offer only the kinds you know."`
	Name   string   `json:"name" example:"TMDB"`
	Ready  bool     `json:"ready" doc:"It can be used now."`
	Reason string   `json:"reason,omitempty" required:"false" example:"An admin can add an MDBList key in Settings › Lists" doc:"Why it isn't ready."`
	Charts []string `json:"charts,omitempty" required:"false" nullable:"false" doc:"tmdb: the charts MediaListSource.chart takes."`
}

type MediaListGenre struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type MediaListSources struct {
	Sources     []MediaListSourceKind `json:"sources" nullable:"false"`
	MovieGenres []MediaListGenre      `json:"movieGenres" nullable:"false" doc:"TMDB's movie genres, for discover; empty without a TMDB key."`
	TVGenres    []MediaListGenre      `json:"tvGenres" nullable:"false" doc:"TMDB's TV genres, for discover."`
	Lists       int                   `json:"lists" doc:"The person's own lists."`
	MaxLists    int                   `json:"maxLists" doc:"Most lists a person may make."`
}

// ListsChanged is the lists.changed event, sent to a list's audience when it
// is made, changed or deleted, or a fetch changes its entries.
type ListsChanged struct {
	ListID int64 `json:"listId"`
}

func registerMediaLists(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "listMediaLists",
		Method:      http.MethodGet,
		Path:        Prefix + "/lists",
		Summary:     "The signed-in person's lists and the household's",
		Description: "From API level 22.",
		Tags:        []string{"lists"},
	}, func(ctx context.Context, _ *struct{}) (*MediaListsOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		all, err := deps.Queries.ListVisibleMediaLists(ctx, &u.ID)
		if err != nil {
			return nil, err
		}
		counts, err := listCountsOf(ctx, deps, all)
		if err != nil {
			return nil, err
		}
		out := &MediaListsOutput{}
		out.Body.Items = make([]MediaList, 0, len(all))
		for _, l := range all {
			out.Body.Items = append(out.Body.Items, mediaListView(ctx, deps, u, l, counts[l.ID]))
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "createMediaList",
		Method:        http.MethodPost,
		Path:          Prefix + "/lists",
		Summary:       "Make a list; its row goes at the top of the screen it was made on",
		Description:   "Fetches it at once (for tmdb and mdblist). Members may make 25; shared lists are for admins. Sends lists.changed.",
		Tags:          []string{"lists"},
		DefaultStatus: http.StatusCreated,
	}, func(ctx context.Context, in *CreateMediaListInput) (*MediaListOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		b := in.Body
		name := strings.TrimSpace(b.Name)
		if name == "" {
			return nil, huma.Error422UnprocessableEntity("give the list a name")
		}
		if b.Shared && !effectiveAdmin(ctx, u) {
			return nil, huma.Error403Forbidden("only admins can make lists for everyone")
		}
		ref, err := parseScreen(b.Screen)
		if err != nil {
			return nil, err
		}
		mediaType, err := listMediaType(ctx, deps, ref, b.MediaType, b.Source)
		if err != nil {
			return nil, err
		}
		if err := listRoom(ctx, deps, u, b.Shared); err != nil {
			return nil, err
		}
		kind := b.Source.Kind
		p := sourceParams(b.Source)
		limit := listLimit(b.Limit)
		res, err := collectList(ctx, deps, kind, lists.Request{Params: p, MediaType: mediaType, Limit: limit})
		if err != nil {
			return nil, err
		}
		if kind == lists.KindImport {
			p.Format = res.Format
		}
		now := time.Now()
		params := db.CreateMediaListParams{
			CreatedBy: &u.ID, Screen: ref.Name, Name: name, SourceKind: kind, Params: p.Encode(), MediaType: mediaType,
			EntryLimit: int64(limit), SourceUrl: res.URL, NextFetchAt: lists.NextFetch(kind, p, now), WantedAt: new(db.FormatTime(now)),
		}
		if !b.Shared {
			params.OwnerID = &u.ID
		}
		if lists.Fetched(kind) {
			params.FetchedAt = new(db.FormatTime(now))
		}
		l, err := deps.Lists.Create(ctx, params, res.Entries)
		if err != nil {
			return nil, err
		}
		publishListChanged(deps.Bus, l)
		return listOutput(ctx, deps, u, l)
	})

	huma.Register(h, huma.Operation{
		OperationID: "previewMediaList",
		Method:      http.MethodPost,
		Path:        Prefix + "/lists/preview",
		Summary:     "What a list would hold, and how much of it the library has, without saving it",
		Tags:        []string{"lists"},
	}, func(ctx context.Context, in *PreviewMediaListInput) (*struct{ Body MediaListPreview }, error) {
		if _, err := currentUser(ctx); err != nil {
			return nil, err
		}
		b := in.Body
		mediaType, err := listMediaType(ctx, deps, screenRef{Name: ScreenHome}, b.MediaType, b.Source)
		if err != nil {
			return nil, err
		}
		marks, err := loadWatchMarks(ctx, deps)
		if err != nil {
			return nil, err
		}
		kind, p, limit := b.Source.Kind, sourceParams(b.Source), listLimit(b.Limit)
		out := MediaListPreview{Items: []MediaListEntry{}}
		if kind == lists.KindLibrary {
			if _, err := collectList(ctx, deps, kind, lists.Request{Params: p, MediaType: mediaType, Limit: limit}); err != nil {
				return nil, err
			}
			items, err := libraryListItems(ctx, deps, db.MediaList{SourceKind: kind, Params: p.Encode()}, marks)
			if err != nil {
				return nil, err
			}
			items = items[:min(len(items), limit)]
			out.Total, out.InLibrary = len(items), len(items)
			for i := range items[:min(len(items), previewEntries)] {
				it := items[i]
				out.Items = append(out.Items, MediaListEntry{Rank: i + 1, MediaType: mediaOf(it), Title: it.Title, Year: it.Year, PosterPath: it.PosterPath, Found: true, Item: &it})
			}
			return &struct{ Body MediaListPreview }{Body: out}, nil
		}
		key := previewKey(kind, mediaType, limit, p)
		res, err := memo.Load(ctx, deps.listsState.preview, &deps.listsState.previewGroup, key, func(ctx context.Context) (lists.Result, error) {
			return collectList(ctx, deps, kind, lists.Request{Params: p, MediaType: mediaType, Limit: limit})
		})
		if err != nil {
			return nil, err
		}
		have, err := libraryItemsByTMDB(ctx, deps, res.Entries, marks)
		if err != nil {
			return nil, err
		}
		out.Total, out.Format = len(res.Entries), res.Format
		for _, e := range res.Entries {
			it, ok := have[entryKey(e)]
			if ok && e.Found() {
				out.InLibrary++
			}
			if !e.Found() {
				out.NotFound++
			}
			if len(out.Items) < previewEntries {
				v := apiEntry(e)
				if ok && e.Found() {
					v.Item = &it
				}
				out.Items = append(out.Items, v)
			}
		}
		return &struct{ Body MediaListPreview }{Body: out}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getMediaListSources",
		Method:      http.MethodGet,
		Path:        Prefix + "/list-sources",
		Summary:     "The sources lists can be made from, and their options",
		Tags:        []string{"lists"},
	}, func(ctx context.Context, _ *struct{}) (*struct{ Body MediaListSources }, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		libs, err := deps.Queries.ListLibraries(ctx)
		if err != nil {
			return nil, err
		}
		out := MediaListSources{Sources: []MediaListSourceKind{}, MovieGenres: []MediaListGenre{}, TVGenres: []MediaListGenre{}, MaxLists: maxListsPerPerson}
		names := map[string]string{lists.KindTMDB: "TMDB", lists.KindMDBList: "MDBList", lists.KindLibrary: "Your library", lists.KindImport: "An import"}
		for _, kind := range lists.Kinds {
			ready, reason := deps.Lists.Ready(kind)
			if kind == lists.KindLibrary && len(libs) == 0 {
				ready, reason = false, "Add a library first"
			}
			s := MediaListSourceKind{Kind: kind, Name: names[kind], Ready: ready, Reason: reason}
			if kind == lists.KindTMDB {
				s.Charts = slices.Clone(lists.Charts)
			}
			out.Sources = append(out.Sources, s)
		}
		if ready, _ := deps.Lists.Ready(lists.KindTMDB); ready {
			out.MovieGenres = tmdbGenres(ctx, deps, "movie")
			out.TVGenres = tmdbGenres(ctx, deps, "tv")
		}
		n, err := deps.Queries.CountOwnedMediaLists(ctx, &u.ID)
		if err != nil {
			return nil, err
		}
		out.Lists = int(n)
		return &struct{ Body MediaListSources }{Body: out}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getMediaList",
		Method:      http.MethodGet,
		Path:        Prefix + "/lists/{id}",
		Summary:     "A list",
		Tags:        []string{"lists"},
	}, func(ctx context.Context, in *MediaListIDInput) (*MediaListOutput, error) {
		u, l, err := visibleList(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		deps.Lists.Want(ctx, l)
		return listOutput(ctx, deps, u, l)
	})

	huma.Register(h, huma.Operation{
		OperationID: "updateMediaList",
		Method:      http.MethodPatch,
		Path:        Prefix + "/lists/{id}",
		Summary:     "Rename a list, change its source or limit, or share it with the household",
		Tags:        []string{"lists"},
	}, func(ctx context.Context, in *UpdateMediaListInput) (*MediaListOutput, error) {
		u, l, err := editableList(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		b := in.Body
		was := l
		up := db.UpdateMediaListParams{ID: l.ID, Name: l.Name, SourceKind: l.SourceKind, Params: l.Params, EntryLimit: l.EntryLimit,
			OwnerID: l.OwnerID, SourceUrl: l.SourceUrl, NextFetchAt: l.NextFetchAt}
		if b.Name != nil {
			if up.Name = strings.TrimSpace(*b.Name); up.Name == "" {
				return nil, huma.Error422UnprocessableEntity("give the list a name")
			}
		}
		if b.Shared != nil {
			if !effectiveAdmin(ctx, u) {
				return nil, huma.Error403Forbidden("only admins can share lists with everyone")
			}
			if *b.Shared {
				up.OwnerID = nil
			} else if l.OwnerID == nil {
				up.OwnerID = &u.ID
			}
		}
		refetch := false
		if b.Limit != nil && int64(*b.Limit) != l.EntryLimit {
			up.EntryLimit = int64(listLimit(*b.Limit))
			refetch = true
		}
		p := lists.DecodeParams(l.Params)
		if b.Source != nil {
			if l.SourceKind == lists.KindImport || b.Source.Kind == lists.KindImport {
				return nil, huma.Error422UnprocessableEntity("an import's titles can't be changed: make a new list")
			}
			mediaType, err := listMediaType(ctx, deps, screenRef{Name: ScreenHome}, l.MediaType, *b.Source)
			if err != nil {
				return nil, err
			}
			if mediaType != l.MediaType {
				return nil, huma.Error422UnprocessableEntity("a list's media type can't change: make a new list")
			}
			up.SourceKind, p = b.Source.Kind, sourceParams(*b.Source)
			up.Params = p.Encode()
			refetch = true
		}
		var entries []lists.Entry
		fetched := false
		if refetch {
			switch up.SourceKind {
			case lists.KindImport:
				// Its text isn't kept: a lower limit drops the last entries.
				have, err := deps.Lists.Entries(ctx, l.ID)
				if err != nil {
					return nil, err
				}
				entries, fetched = have[:min(len(have), int(up.EntryLimit))], true
			case lists.KindLibrary:
				// Read from the library: entries fetched before go.
				if _, err := collectList(ctx, deps, up.SourceKind, lists.Request{Params: p, MediaType: l.MediaType, Limit: int(up.EntryLimit)}); err != nil {
					return nil, err
				}
				if err := deps.Queries.DeleteMediaListEntries(ctx, l.ID); err != nil {
					return nil, err
				}
				up.SourceUrl, up.NextFetchAt = "", nil
			default:
				res, err := collectList(ctx, deps, up.SourceKind, lists.Request{Params: p, MediaType: l.MediaType, Limit: int(up.EntryLimit)})
				if err != nil {
					return nil, err
				}
				entries, fetched = res.Entries, lists.Fetched(up.SourceKind)
				up.SourceUrl = res.URL
				up.NextFetchAt = lists.NextFetch(up.SourceKind, p, time.Now())
			}
		}
		l, err = deps.Queries.UpdateMediaList(ctx, up)
		if err != nil {
			return nil, err
		}
		if fetched {
			if _, err := deps.Lists.Replace(ctx, l, lists.Result{Entries: entries, URL: up.SourceUrl}, time.Now()); err != nil {
				return nil, err
			}
			if l, err = deps.Queries.GetMediaList(ctx, l.ID); err != nil {
				return nil, err
			}
		}
		publishListChanged(deps.Bus, l)
		if (was.OwnerID == nil) != (l.OwnerID == nil) {
			publishListChanged(deps.Bus, was)
		}
		return listOutput(ctx, deps, u, l)
	})

	huma.Register(h, huma.Operation{
		OperationID:   "deleteMediaList",
		Method:        http.MethodDelete,
		Path:          Prefix + "/lists/{id}",
		Summary:       "Delete a list and its row from every screen",
		Tags:          []string{"lists"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *MediaListIDInput) (*struct{}, error) {
		_, l, err := editableList(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if _, err := deps.Queries.DeleteMediaList(ctx, l.ID); err != nil {
			return nil, err
		}
		publishListChanged(deps.Bus, l)
		return nil, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "refreshMediaList",
		Method:      http.MethodPost,
		Path:        Prefix + "/lists/{id}/refresh",
		Summary:     "Fetch a list again now",
		Description: "409 while a fetch runs. Imports and library lists are not fetched.",
		Tags:        []string{"lists"},
	}, func(ctx context.Context, in *MediaListIDInput) (*MediaListOutput, error) {
		u, l, err := editableList(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		if !lists.Outside(l.SourceKind) {
			return nil, huma.Error422UnprocessableEntity("imports and library lists are not fetched")
		}
		deps.Lists.Want(ctx, l)
		got, err := deps.Lists.Fetch(ctx, l.ID)
		switch {
		case errors.Is(err, lists.ErrFetching):
			return nil, huma.Error409Conflict("this list is being fetched")
		case errors.Is(err, lists.ErrDisabled):
			return nil, huma.Error422UnprocessableEntity(err.Error())
		case err != nil && got.ID == 0:
			return nil, err
		}
		// A fetch that failed is on the list: its error says why, and it
		// keeps the entries it had.
		return listOutput(ctx, deps, u, got)
	})

	huma.Register(h, huma.Operation{
		OperationID: "listMediaListEntries",
		Method:      http.MethodGet,
		Path:        Prefix + "/lists/{id}/entries",
		Summary:     "Every title on a list in order, with the library's item where it has one",
		Description: "The list's See all page. Missing titles carry requestState when the person may request.",
		Tags:        []string{"lists"},
	}, func(ctx context.Context, in *MediaListEntriesInput) (*struct{ Body MediaListEntries }, error) {
		u, l, err := visibleList(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		deps.Lists.Want(ctx, l)
		marks, err := loadWatchMarks(ctx, deps)
		if err != nil {
			return nil, err
		}
		out := MediaListEntries{Items: []MediaListEntry{}, Limit: in.Limit, Offset: in.Offset}
		var all []MediaListEntry
		if l.SourceKind == lists.KindLibrary {
			items, err := libraryListItems(ctx, deps, l, marks)
			if err != nil {
				return nil, err
			}
			items = items[:min(len(items), int(l.EntryLimit))]
			for i := range items {
				it := items[i]
				all = append(all, MediaListEntry{Rank: i + 1, MediaType: mediaOf(it), Title: it.Title, Year: it.Year, PosterPath: it.PosterPath, Found: true, Item: &it})
			}
		} else {
			entries, err := deps.Lists.Entries(ctx, l.ID)
			if err != nil {
				return nil, err
			}
			data, err := loadListData(ctx, deps, []db.MediaList{l}, []string{"movie", "tv"}, 0, marks)
			if err != nil {
				return nil, err
			}
			cards, err := data.cards(ctx, deps, []int64{l.ID}, 0)
			if err != nil {
				return nil, err
			}
			byRank := map[int64]BrowseItem{}
			for _, r := range cards[l.ID] {
				byRank[r.rank] = r.item
			}
			for _, e := range entries {
				v := apiEntry(e)
				if it, ok := byRank[int64(e.Rank)]; ok {
					v.Item = &it
				}
				all = append(all, v)
			}
		}
		for _, e := range all {
			if e.Item != nil {
				out.InLibrary++
			}
		}
		out.Total = len(all)
		start := min(in.Offset, len(all))
		page := all[start:min(start+in.Limit, len(all))]
		requestStates(ctx, deps, u, page)
		out.Items = append(out.Items, page...)
		return &struct{ Body MediaListEntries }{Body: out}, nil
	})
}

// previewEntries is how many entries a preview shows.
const previewEntries = 50

func listLimit(n int) int {
	if n <= 0 {
		return 50
	}
	return min(n, maxListEntries)
}

// sourceParams are a source's options as a list keeps them.
func sourceParams(s MediaListSource) lists.Params {
	return lists.Params{
		Chart: s.Chart, Ref: strings.TrimSpace(s.Ref), Genres: s.Genres, YearFrom: s.YearFrom, YearTo: s.YearTo, MinRating: s.MinRating,
		MinVotes: s.MinVotes, Language: strings.ToLower(strings.TrimSpace(s.Language)), SortBy: s.SortBy,
		LibraryID: s.LibraryID, Sort: s.Sort, Filter: s.Filter, Genre: s.Genre, Decade: s.Decade, Text: s.Text,
	}
}

// listSource is a list's source as the API shows it, without an import's text.
func listSource(kind string, p lists.Params) MediaListSource {
	return MediaListSource{
		Kind: kind, Chart: p.Chart, Ref: p.Ref, Genres: p.Genres, YearFrom: p.YearFrom, YearTo: p.YearTo, MinRating: p.MinRating,
		MinVotes: p.MinVotes, Language: p.Language, SortBy: p.SortBy, LibraryID: p.LibraryID, Sort: p.Sort, Filter: p.Filter,
		Genre: p.Genre, Decade: p.Decade, Format: p.Format,
	}
}

// listMediaType checks a list's media type against the screen it is made
// on (a library's screen shows its own type) and, for a library list, is
// the library's type.
func listMediaType(ctx context.Context, deps Deps, ref screenRef, mediaType string, src MediaListSource) (string, error) {
	if ref.LibraryID != 0 {
		lib, err := deps.Queries.GetLibrary(ctx, ref.LibraryID)
		if errors.Is(err, sql.ErrNoRows) {
			return "", huma.Error422UnprocessableEntity("there is no such library to show the list on")
		}
		if err != nil {
			return "", err
		}
		if mediaType != "all" && mediaType != lib.Type {
			return "", huma.Error422UnprocessableEntity(fmt.Sprintf("a list on the %s library's screen holds %s", lib.Name, map[string]string{"movie": "movies", "tv": "TV shows"}[lib.Type]))
		}
	}
	if src.Kind == lists.KindLibrary {
		if src.LibraryID <= 0 {
			return "", huma.Error422UnprocessableEntity("choose a library")
		}
		lib, err := deps.Queries.GetLibrary(ctx, src.LibraryID)
		if errors.Is(err, sql.ErrNoRows) {
			return "", huma.Error422UnprocessableEntity("there is no such library to filter")
		}
		if err != nil {
			return "", err
		}
		if ref.LibraryID != 0 && ref.LibraryID != lib.ID {
			other, err := deps.Queries.GetLibrary(ctx, ref.LibraryID)
			if err == nil && other.Type != lib.Type {
				return "", huma.Error422UnprocessableEntity("a list on this screen filters a library of its type")
			}
		}
		return lib.Type, nil
	}
	return mediaType, nil
}

// listRoom refuses a new list past the limits: 25 of a person's own, 200
// on the server.
func listRoom(ctx context.Context, deps Deps, u auth.User, shared bool) error {
	total, err := deps.Queries.CountMediaLists(ctx)
	if err != nil {
		return err
	}
	if total >= maxListsOnServer {
		return huma.Error409Conflict(fmt.Sprintf("this server has %d lists, the most it keeps; delete one to make another", maxListsOnServer))
	}
	if shared {
		return nil
	}
	mine, err := deps.Queries.CountOwnedMediaLists(ctx, &u.ID)
	if err != nil {
		return err
	}
	if mine >= maxListsPerPerson {
		return huma.Error409Conflict(fmt.Sprintf("you have %d lists, the most one person may make; delete one to make another", maxListsPerPerson))
	}
	return nil
}

// collectList fetches a new list or a preview, and words what went wrong.
func collectList(ctx context.Context, deps Deps, kind string, r lists.Request) (lists.Result, error) {
	res, err := deps.Lists.Collect(ctx, kind, r)
	if err == nil {
		return res, nil
	}
	if _, ok := errors.AsType[*lists.InvalidError](err); ok {
		return res, huma.Error422UnprocessableEntity(err.Error())
	}
	if _, ok := errors.AsType[*lists.RefusedError](err); ok {
		return res, huma.Error422UnprocessableEntity(err.Error())
	}
	if lists.IsMDBListLimit(err) {
		return res, huma.Error503ServiceUnavailable(err.Error())
	}
	if errors.Is(err, lists.ErrDisabled) {
		return res, huma.Error422UnprocessableEntity(err.Error())
	}
	if ctx.Err() != nil {
		return res, ctx.Err()
	}
	deps.Log.WarnContext(ctx, "could not fetch a list", "source", kind, "err", err)
	return res, huma.Error502BadGateway(lists.Describe(err))
}

func previewKey(kind, mediaType string, limit int, p lists.Params) string {
	text := ""
	if p.Text != "" {
		sum := sha256.Sum256([]byte(p.Text))
		text = hex.EncodeToString(sum[:])
	}
	return fmt.Sprintf("%s|%s|%d|%s|%s", kind, mediaType, limit, p.Encode(), text)
}

// tmdbGenres is TMDB's genres for discover, kept a day; none when TMDB
// can't be reached.
func tmdbGenres(ctx context.Context, deps Deps, mediaType string) []MediaListGenre {
	got, err := memo.Load(ctx, deps.listsState.genres, &deps.listsState.genreGroup, mediaType, func(ctx context.Context) ([]MediaListGenre, error) {
		genres, err := deps.TMDB.Genres(ctx, mediaType)
		if err != nil {
			return nil, err
		}
		out := make([]MediaListGenre, 0, len(genres))
		for _, g := range genres {
			out = append(out, MediaListGenre{ID: int64(g.ID), Name: g.Name})
		}
		return out, nil
	})
	if err != nil {
		deps.Log.WarnContext(ctx, "could not read TMDB's genres", "media_type", mediaType, "err", err)
		return []MediaListGenre{}
	}
	return got
}

func mediaOf(it BrowseItem) string {
	if it.Kind == "show" {
		return "tv"
	}
	return "movie"
}

// visibleList is a list the signed-in person sees: their own or the
// household's. Admins don't see members' own lists.
func visibleList(ctx context.Context, deps Deps, id int64) (auth.User, db.MediaList, error) {
	u, err := currentUser(ctx)
	if err != nil {
		return u, db.MediaList{}, err
	}
	l, err := deps.Queries.GetMediaList(ctx, id)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && l.OwnerID != nil && *l.OwnerID != u.ID) {
		return u, db.MediaList{}, huma.Error404NotFound("list not found")
	}
	return u, l, err
}

// editableList is a list the person may change: their own, or for an
// admin any of the household's.
func editableList(ctx context.Context, deps Deps, id int64) (auth.User, db.MediaList, error) {
	u, l, err := visibleList(ctx, deps, id)
	if err != nil {
		return u, l, err
	}
	if !canEditList(ctx, u, l) {
		return u, l, huma.Error403Forbidden("only an admin can change the household's lists")
	}
	return u, l, nil
}

func canEditList(ctx context.Context, u auth.User, l db.MediaList) bool {
	if l.OwnerID != nil {
		return *l.OwnerID == u.ID
	}
	return effectiveAdmin(ctx, u)
}

func listOutput(ctx context.Context, deps Deps, u auth.User, l db.MediaList) (*MediaListOutput, error) {
	counts, err := listCountsOf(ctx, deps, []db.MediaList{l})
	if err != nil {
		return nil, err
	}
	return &MediaListOutput{Body: mediaListView(ctx, deps, u, l, counts[l.ID])}, nil
}

// mediaListView is a list as the API shows it to u.
func mediaListView(ctx context.Context, deps Deps, u auth.User, l db.MediaList, n listCounts) MediaList {
	p := lists.DecodeParams(l.Params)
	v := MediaList{
		ID: l.ID, Name: l.Name, MediaType: l.MediaType, Source: listSource(l.SourceKind, p), Owner: "everyone", CanEdit: canEditList(ctx, u, l),
		Screen: l.Screen, Limit: int(l.EntryLimit), Entries: n.entries, InLibrary: n.inLibrary, NotFound: n.notFound,
		Fetching: deps.Lists.Fetching(l.ID), FetchedAt: db.ParseTimePtr(l.FetchedAt), ErrorAt: db.ParseTimePtr(l.ErrorAt),
		CreatedAt: db.ParseTime(l.CreatedAt), UpdatedAt: db.ParseTime(l.UpdatedAt),
	}
	if l.OwnerID != nil {
		v.Owner = "me"
	}
	if src := listRow(deps, &l).Source; src != nil {
		v.SourceName, v.SourceURL = src.Name, src.URL
	}
	if lists.Outside(l.SourceKind) {
		v.NextFetchAt = db.ParseTimePtr(l.NextFetchAt)
	}
	if l.Error != nil {
		v.Error = *l.Error
	}
	return v
}

// ListsChangedEvent is the lists.changed event.
const ListsChangedEvent = "lists.changed"

// publishListChanged sends lists.changed to the list's audience: its
// owner alone, or everyone for the household's.
func publishListChanged(bus *events.Bus, l db.MediaList) {
	if bus == nil {
		return
	}
	e := events.Event{Type: ListsChangedEvent, Data: ListsChanged{ListID: l.ID}}
	if l.OwnerID != nil {
		e.To = &events.Audience{UserID: *l.OwnerID, Private: true}
	}
	bus.Publish(e)
}

// ListsChangedPublisher is what lists.Service tells when a fetch changes
// a list's entries: lists.changed on bus.
func ListsChangedPublisher(bus *events.Bus) func(db.MediaList) {
	return func(l db.MediaList) { publishListChanged(bus, l) }
}
