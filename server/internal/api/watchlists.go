package api

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"net/http"
	"reflect"
	"slices"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/lists"
)

// Watchlists are the movies and shows a person means to watch, in lists
// they name (API level 33). A watchlist is one of the person's own lists
// (media_lists with source kind watchlist), so its rows, its places in the
// layouts and its page are a list's. What differs is who may change it,
// where its rows go, their order, and that a finished title leaves it.

// Watchlist is one of the signed-in person's watchlists.
type Watchlist struct {
	ID            int64     `json:"id" doc:"The watchlist's ID, which is also its list's: getMediaList and listMediaListEntries take it, and its rows are list:<id>."`
	Name          string    `json:"name" example:"Watchlist"`
	Titles        int       `json:"titles" doc:"Titles on it, in the library or not."`
	InLibrary     int       `json:"inLibrary" doc:"Titles on it that some library has now."`
	RemoveWatched bool      `json:"removeWatched" doc:"A title leaves once the person finishes it: a movie when it is watched, a show when every episode in the library is. Off, watched titles stay."`
	Posters       []string  `json:"posters" nullable:"false" doc:"The poster paths of its first four titles that have one, in its order: TMDB paths, as posterPath elsewhere."`
	Contains      bool      `json:"contains" required:"false" doc:"With mediaType and tmdbId in listWatchlists' query: it holds that title. False otherwise."`
	CreatedAt     time.Time `json:"createdAt"`
	UpdatedAt     time.Time `json:"updatedAt" doc:"When it was renamed or its titles last changed."`
}

// WatchlistList is the person's watchlists.
type WatchlistList struct {
	Items     []Watchlist `json:"items" nullable:"false" doc:"In the order they were made."`
	Max       int         `json:"max" doc:"Most watchlists one person may have."`
	MaxTitles int         `json:"maxTitles" doc:"Most titles one watchlist holds."`
}

type WatchlistListOutput struct{ Body WatchlistList }

type WatchlistOutput struct{ Body Watchlist }

type ListWatchlistsInput struct {
	MediaType string `query:"mediaType" required:"false" enum:"movie,tv" doc:"With tmdbId: each watchlist says whether it holds this title (contains), for the picker."`
	TMDBID    int64  `query:"tmdbId" required:"false" doc:"The title's TMDB id, with mediaType."`
}

// WatchlistBody makes a watchlist.
type WatchlistBody struct {
	Name          string `json:"name" minLength:"1" maxLength:"60" doc:"Different from the person's other watchlists' names, whatever the case."`
	RemoveWatched *bool  `json:"removeWatched,omitempty" required:"false" doc:"Remove titles once watched. On when left out."`
}

type CreateWatchlistInput struct {
	Body WatchlistBody
}

type UpdateWatchlistInput struct {
	ID   int64 `path:"id" doc:"The watchlist's ID."`
	Body struct {
		Name          *string `json:"name,omitempty" required:"false" minLength:"1" maxLength:"60" doc:"Different from the person's other watchlists' names, whatever the case."`
		RemoveWatched *bool   `json:"removeWatched,omitempty" required:"false"`
	}
}

type WatchlistIDInput struct {
	ID int64 `path:"id" doc:"The watchlist's ID."`
}

// WatchlistTitleDetails describe a title the server has no record of, for
// the watchlist's page to show it by. The server prefers what it knows: the
// library's movie or show, then another list's entry for the title.
type WatchlistTitleDetails struct {
	Title      string `json:"title,omitempty" required:"false" maxLength:"300" doc:"Needed for a title the server has no record of: without one the save is refused."`
	Year       int64  `json:"year,omitempty" required:"false" doc:"Kept when it is between 1870 and 2100."`
	PosterPath string `json:"posterPath,omitempty" required:"false" maxLength:"200" example:"/kqjL17yufvn9OVLyXYpvtyrFfak.jpg" doc:"TMDB's poster path, as Requestable.posterPath. Kept only in TMDB's shape: a slash, then letters, digits, _ or -, then .jpg or .png."`
}

// WatchlistTitlePath names one title on one watchlist.
type WatchlistTitlePath struct {
	ID        int64  `path:"id" doc:"The watchlist's ID."`
	MediaType string `path:"mediaType" enum:"movie,tv"`
	TMDBID    int64  `path:"tmdbId" minimum:"1" doc:"The title's TMDB id: BrowseItem.tmdbId, MovieDetail.tmdbId, ShowDetail.tmdbId, MediaListEntry.tmdbId or Requestable.tmdbId."`
}

type SaveToWatchlistInput struct {
	WatchlistTitlePath
	Body *WatchlistTitleDetails
}

// AnyWatchlistTitlePath names a title on whichever of the person's
// watchlists hold it.
type AnyWatchlistTitlePath struct {
	MediaType string `path:"mediaType" enum:"movie,tv"`
	TMDBID    int64  `path:"tmdbId" minimum:"1" doc:"The title's TMDB id."`
}

type SaveToOnlyWatchlistInput struct {
	AnyWatchlistTitlePath
	Body *WatchlistTitleDetails
}

// WatchlistTitleChange is where a title stands after a save or a removal.
type WatchlistTitleChange struct {
	Watchlists []int64    `json:"watchlists" nullable:"false" doc:"The person's watchlists that hold the title now, in the order they were made: what MovieDetail.watchlists and ShowDetail.watchlists now say."`
	Watchlist  *Watchlist `json:"watchlist,omitempty" required:"false" doc:"saveToWatchlist, removeFromWatchlist and saveToOnlyWatchlist: the watchlist it was saved to or taken from, as it is now (for its name in Saved to Watchlist)."`
	Created    bool       `json:"created,omitempty" required:"false" doc:"saveToOnlyWatchlist: the person had none, and Watchlist was made for this save."`
	Removed    []int64    `json:"removed,omitempty" required:"false" nullable:"false" doc:"removeFromWatchlists: the watchlists it was taken off, for Undo."`
}

type WatchlistTitleChangeOutput struct{ Body WatchlistTitleChange }

// WatchlistsConflict is saveToOnlyWatchlist's 409: the person has two or
// more watchlists, so the app asks which with the picker.
type WatchlistsConflict struct {
	huma.ErrorModel
	Watchlists []Watchlist `json:"watchlists" nullable:"false" doc:"The person's watchlists, as listWatchlists sends them for this title (with contains): what the picker shows."`
}

func registerWatchlists(h huma.API, deps Deps) {
	op := func(id, method, path, summary, description string) huma.Operation {
		return huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary,
			Description: description + " From API level 33.", Tags: []string{"watchlists"}}
	}

	huma.Register(h, op("listWatchlists", http.MethodGet, "/me/watchlists", "The signed-in person's watchlists",
		"Apps read it when they start and again on rows.changed, and press Watchlist with saveToOnlyWatchlist when there is at most one, else open the picker."),
		func(ctx context.Context, in *ListWatchlistsInput) (*WatchlistListOutput, error) {
			u, err := currentUser(ctx)
			if err != nil {
				return nil, err
			}
			ls, err := deps.Queries.ListWatchlists(ctx, &u.ID)
			if err != nil {
				return nil, err
			}
			items, err := watchlistViews(ctx, deps, u.ID, ls, titleRef{in.MediaType, in.TMDBID})
			if err != nil {
				return nil, err
			}
			return &WatchlistListOutput{Body: WatchlistList{Items: items, Max: lists.MaxWatchlists, MaxTitles: lists.MaxWatchlistTitles}}, nil
		})

	create := op("createWatchlist", http.MethodPost, "/me/watchlists", "Make a watchlist",
		"422 when the person has 25, or another of theirs has the name. Its rows go on Home after Continue watching and New from shows you follow, and first on Movies and TV screens. Sends rows.changed to the person.")
	create.DefaultStatus = http.StatusCreated
	huma.Register(h, create, func(ctx context.Context, in *CreateWatchlistInput) (*WatchlistOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		l, err := deps.Lists.CreateWatchlist(ctx, u.ID, in.Body.Name, in.Body.RemoveWatched == nil || *in.Body.RemoveWatched)
		if err != nil {
			return nil, watchlistError(err)
		}
		watchlistMoved(deps, l)
		return watchlistOutput(ctx, deps, u.ID, l)
	})

	huma.Register(h, op("updateWatchlist", http.MethodPatch, "/me/watchlists/{id}", "Rename a watchlist, or turn Remove titles once watched on or off",
		"422 for a name another of the person's watchlists has. Another person's watchlist is not found, for admins too. Sends rows.changed to the person."),
		func(ctx context.Context, in *UpdateWatchlistInput) (*WatchlistOutput, error) {
			u, l, err := ownWatchlist(ctx, deps, in.ID)
			if err != nil {
				return nil, err
			}
			if l, err = deps.Lists.UpdateWatchlist(ctx, l, in.Body.Name, in.Body.RemoveWatched); err != nil {
				return nil, watchlistError(err)
			}
			watchlistMoved(deps, l)
			return watchlistOutput(ctx, deps, u.ID, l)
		})

	del := op("deleteWatchlist", http.MethodDelete, "/me/watchlists/{id}", "Delete a watchlist",
		"Its titles stay in the library; its rows leave every screen. deleteMediaList does the same. Sends rows.changed to the person.")
	del.DefaultStatus = http.StatusNoContent
	huma.Register(h, del, func(ctx context.Context, in *WatchlistIDInput) (*struct{}, error) {
		_, l, err := ownWatchlist(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		return nil, deleteList(ctx, deps, l)
	})

	huma.Register(h, op("saveToWatchlist", http.MethodPut, "/me/watchlists/{id}/titles/{mediaType}/{tmdbId}", "Save a movie or show to a watchlist",
		"Any title, the library's or not. The body describes a title the server has no record of (one only Request search has shown); it is ignored when the server knows the title. A title already there keeps its place. 422 when the watchlist holds 500. Sends lists.changed to the person."),
		func(ctx context.Context, in *SaveToWatchlistInput) (*WatchlistTitleChangeOutput, error) {
			u, l, err := ownWatchlist(ctx, deps, in.ID)
			if err != nil {
				return nil, err
			}
			saved, err := deps.Lists.SaveToWatchlist(ctx, l, savedTitle(in.MediaType, in.TMDBID, in.Body))
			if err != nil {
				return nil, watchlistError(err)
			}
			if saved {
				publishListChanged(deps.Bus, l)
			}
			return titleChange(ctx, deps, u.ID, titleRef{in.MediaType, in.TMDBID}, l.ID, WatchlistTitleChange{})
		})

	huma.Register(h, op("removeFromWatchlist", http.MethodDelete, "/me/watchlists/{id}/titles/{mediaType}/{tmdbId}", "Take a movie or show off a watchlist",
		"Succeeds when it is not there. Sends lists.changed to the person."),
		func(ctx context.Context, in *WatchlistTitlePath) (*WatchlistTitleChangeOutput, error) {
			u, l, err := ownWatchlist(ctx, deps, in.ID)
			if err != nil {
				return nil, err
			}
			removed, err := deps.Lists.RemoveFromWatchlist(ctx, l, in.MediaType, in.TMDBID)
			if err != nil {
				return nil, err
			}
			if removed {
				publishListChanged(deps.Bus, l)
			}
			return titleChange(ctx, deps, u.ID, titleRef{in.MediaType, in.TMDBID}, l.ID, WatchlistTitleChange{})
		})

	only := op("saveToOnlyWatchlist", http.MethodPut, "/me/watchlist/titles/{mediaType}/{tmdbId}", "Save a movie or show to the person's watchlist, when they have one",
		"The Watchlist button pressed once. With no watchlist it makes one called Watchlist (created) and saves there; with one it saves there; with two or more it saves nothing and answers 409 with the watchlists (WatchlistsConflict), and the app opens the picker. The body is saveToWatchlist's.")
	only.Responses = watchlistsConflictResponses(h)
	huma.Register(h, only, func(ctx context.Context, in *SaveToOnlyWatchlistInput) (*WatchlistTitleChangeOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		title := titleRef{in.MediaType, in.TMDBID}
		l, created, saved, err := deps.Lists.SaveToOnlyWatchlist(ctx, u.ID, savedTitle(in.MediaType, in.TMDBID, in.Body))
		if several, ok := errors.AsType[*lists.SeveralError](err); ok {
			views, verr := watchlistViews(ctx, deps, u.ID, several.Watchlists, title)
			if verr != nil {
				return nil, verr
			}
			return nil, &WatchlistsConflict{Title: http.StatusText(http.StatusConflict), Status: http.StatusConflict, Detail: several.Error(), Watchlists: views}
		}
		if err != nil {
			return nil, watchlistError(err)
		}
		if created {
			watchlistMoved(deps, l)
		} else if saved {
			publishListChanged(deps.Bus, l)
		}
		return titleChange(ctx, deps, u.ID, title, l.ID, WatchlistTitleChange{Created: created})
	})

	huma.Register(h, op("removeFromWatchlists", http.MethodDelete, "/me/watchlist/titles/{mediaType}/{tmdbId}", "Take a movie or show off every one of the person's watchlists",
		"On Watchlist pressed again. Succeeds when no watchlist holds it. Sends lists.changed for each watchlist it left."),
		func(ctx context.Context, in *AnyWatchlistTitlePath) (*WatchlistTitleChangeOutput, error) {
			u, err := currentUser(ctx)
			if err != nil {
				return nil, err
			}
			left, err := deps.Lists.RemoveFromWatchlists(ctx, u.ID, in.MediaType, in.TMDBID)
			if err != nil {
				return nil, err
			}
			change := WatchlistTitleChange{Removed: []int64{}}
			for _, l := range left {
				publishListChanged(deps.Bus, l)
				change.Removed = append(change.Removed, l.ID)
			}
			return titleChange(ctx, deps, u.ID, titleRef{in.MediaType, in.TMDBID}, 0, change)
		})
}

// titleRef is a movie or show by its TMDB id.
type titleRef struct {
	mediaType string
	tmdbID    int64
}

func (t titleRef) key() string { return fmt.Sprintf("%s/%d", t.mediaType, t.tmdbID) }

// watchlisted is the titles on a person's watchlists, by titleRef.key.
type watchlisted map[string]bool

func (w watchlisted) has(mediaType string, tmdbID int64) bool {
	return tmdbID > 0 && w[titleRef{mediaType, tmdbID}.key()]
}

// loadWatchlisted reads what is on the person's watchlists, once for a
// request however many cards it marks.
func loadWatchlisted(ctx context.Context, deps Deps, userID int64) (watchlisted, error) {
	rows, err := deps.Queries.ListWatchlistEntries(ctx, &userID)
	out := make(watchlisted, len(rows))
	for _, r := range rows {
		out[titleRef{r.MediaType, r.TmdbID}.key()] = true
	}
	return out, err
}

// markWatchlisted says which of some titles to request are on the
// person's watchlists.
func markWatchlisted(ctx context.Context, deps Deps, items []Requestable) error {
	u, ok := auth.UserFrom(ctx)
	if !ok || len(items) == 0 {
		return nil
	}
	saved, err := loadWatchlisted(ctx, deps, u.ID)
	for i := range items {
		items[i].Watchlisted = saved.has(items[i].MediaType, items[i].TMDBID)
	}
	return err
}

// watchlistsHolding is which of the person's watchlists hold a title, for
// its page.
func watchlistsHolding(ctx context.Context, deps Deps, userID int64, mediaType string, tmdbID int64) ([]int64, error) {
	ids, err := deps.Queries.ListWatchlistsHolding(ctx, db.ListWatchlistsHoldingParams{MediaType: mediaType, TmdbID: &tmdbID, OwnerID: &userID})
	if ids == nil {
		ids = []int64{}
	}
	return ids, err
}

// ownWatchlist is one of the signed-in person's watchlists. Anything else,
// another person's watchlist above all, is not found, for admins too.
func ownWatchlist(ctx context.Context, deps Deps, id int64) (auth.User, db.MediaList, error) {
	u, l, err := visibleList(ctx, deps, id)
	se, isStatus := errors.AsType[huma.StatusError](err)
	if isStatus && se.GetStatus() == http.StatusNotFound || err == nil && (!lists.IsWatchlist(l) || l.OwnerID == nil) {
		return u, db.MediaList{}, huma.Error404NotFound("watchlist not found")
	}
	return u, l, err
}

// watchlistError words what the watchlist rules refused, which apps show.
func watchlistError(err error) error {
	if e, ok := errors.AsType[*lists.InvalidError](err); ok {
		return huma.Error422UnprocessableEntity(e.Reason)
	}
	return err
}

func savedTitle(mediaType string, tmdbID int64, d *WatchlistTitleDetails) lists.Title {
	t := lists.Title{MediaType: mediaType, TMDBID: tmdbID}
	if d != nil {
		t.Title, t.Year, t.PosterPath = d.Title, d.Year, d.PosterPath
	}
	return t
}

// watchlistMoved tells the person's devices that a watchlist was made,
// renamed or deleted: its rows change (rows.changed) and so does its page
// (lists.changed).
func watchlistMoved(deps Deps, l db.MediaList) {
	publishRowsChanged(deps, ScreenHome, l.OwnerID)
	publishListChanged(deps.Bus, l)
}

// deleteList deletes a list and its rows. A watchlist's rows are the
// person's, so they hear of it as a layout change too.
func deleteList(ctx context.Context, deps Deps, l db.MediaList) error {
	if _, err := deps.Queries.DeleteMediaList(ctx, l.ID); err != nil {
		return err
	}
	if lists.IsWatchlist(l) {
		watchlistMoved(deps, l)
	} else {
		publishListChanged(deps.Bus, l)
	}
	return nil
}

func watchlistOutput(ctx context.Context, deps Deps, userID int64, l db.MediaList) (*WatchlistOutput, error) {
	views, err := watchlistViews(ctx, deps, userID, []db.MediaList{l}, titleRef{})
	if err != nil {
		return nil, err
	}
	return &WatchlistOutput{Body: views[0]}, nil
}

// titleChange is where a title stands after a save or a removal: the
// watchlists holding it now and, with changed, that watchlist as it is.
func titleChange(ctx context.Context, deps Deps, userID int64, t titleRef, changed int64, out WatchlistTitleChange) (*WatchlistTitleChangeOutput, error) {
	var err error
	if out.Watchlists, err = watchlistsHolding(ctx, deps, userID, t.mediaType, t.tmdbID); err != nil {
		return nil, err
	}
	if changed != 0 {
		l, err := deps.Queries.GetMediaList(ctx, changed)
		if err != nil {
			return nil, err
		}
		v, err := watchlistOutput(ctx, deps, userID, l)
		if err != nil {
			return nil, err
		}
		out.Watchlist = &v.Body
	}
	return &WatchlistTitleChangeOutput{Body: out}, nil
}

// watchlistViews are the person's watchlists ls as the API shows them,
// with whether each holds t when t names a title.
func watchlistViews(ctx context.Context, deps Deps, userID int64, ls []db.MediaList, t titleRef) ([]Watchlist, error) {
	out := make([]Watchlist, 0, len(ls))
	if len(ls) == 0 {
		return out, nil
	}
	data, err := loadListData(ctx, deps, ls, []string{"movie", "tv"}, 0, nil)
	if err != nil {
		return nil, err
	}
	rows, err := deps.Queries.ListWatchlistEntries(ctx, &userID)
	if err != nil {
		return nil, err
	}
	entries := map[int64][]db.ListWatchlistEntriesRow{}
	for _, r := range rows {
		entries[r.ListID] = append(entries[r.ListID], r)
	}
	for _, l := range ls {
		n := data.counts[l.ID]
		v := Watchlist{ID: l.ID, Name: l.Name, Titles: n.entries, InLibrary: n.inLibrary, RemoveWatched: l.RemoveWatched == 1,
			Posters: []string{}, CreatedAt: db.ParseTime(l.CreatedAt), UpdatedAt: db.ParseTime(l.UpdatedAt)}
		es := entries[l.ID]
		at := savedPlaces(data.ranks[l.ID])
		sortSaved(es, func(e db.ListWatchlistEntriesRow) (string, int64) { return at.of(e.Rank, e.AddedAt), e.Rank })
		for _, e := range es {
			if p := deref(e.PosterPath); p != "" && len(v.Posters) < 4 {
				v.Posters = append(v.Posters, p)
			}
			if e.MediaType == t.mediaType && e.TmdbID == t.tmdbID {
				v.Contains = true
			}
		}
		out = append(out, v)
	}
	return out, nil
}

// savedPlace is where each of a watchlist's titles the library has goes in
// its order (rankRef.at), by rank.
type savedPlace map[int64]string

func savedPlaces(refs []rankRef) savedPlace {
	out := savedPlace{}
	for _, r := range refs {
		out[r.rank] = r.at
	}
	return out
}

// of is an entry's place: the library's title's, else when it was saved.
func (p savedPlace) of(rank int64, saved string) string {
	if at, ok := p[rank]; ok {
		return at
	}
	return saved
}

// sortSaved puts a watchlist's entries in its order, as its row has them
// (savedOrder): newest first, the newer saved first among equals. place
// says each entry's place and rank.
func sortSaved[E any](es []E, place func(E) (string, int64)) {
	slices.SortStableFunc(es, func(a, b E) int {
		at, ra := place(a)
		bt, rb := place(b)
		if c := strings.Compare(bt, at); c != 0 {
			return c
		}
		return cmp.Compare(ra, rb)
	})
}

// watchlistsConflictResponses document saveToOnlyWatchlist's 409, whose
// problem carries the person's watchlists, beside the usual problem for
// every other error (which huma leaves out once an operation names one).
func watchlistsConflictResponses(h huma.API) map[string]*huma.Response {
	schemas := h.OpenAPI().Components.Schemas
	problem := func(t reflect.Type, hint string) map[string]*huma.MediaType {
		return map[string]*huma.MediaType{"application/problem+json": {Schema: schemas.Schema(t, true, hint)}}
	}
	return map[string]*huma.Response{
		"409":     {Description: "The person has two or more watchlists: choose one.", Content: problem(reflect.TypeFor[WatchlistsConflict](), "WatchlistsConflict")},
		"default": {Description: "Error", Content: problem(reflect.TypeFor[huma.ErrorModel](), "ErrorModel")},
	}
}
