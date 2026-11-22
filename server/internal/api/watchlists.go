package api

import (
	"context"
	"net/http"
	"reflect"
	"time"

	"github.com/danielgtaylor/huma/v2"
)

// Watchlists are the movies and shows a person means to watch, in lists
// they name (API level 33). A watchlist is one of the person's own lists
// (media_lists with source kind watchlist), so its rows, its places in the
// layouts and its page are a list's. What differs is who may change it,
// where its rows go, their order, and that a finished title leaves it.

// The limits on watchlists, apart from the lists' own.
const (
	// maxWatchlists is how many watchlists one person may have.
	maxWatchlists = 25
	// maxWatchlistTitles is how many titles one watchlist holds.
	maxWatchlistTitles = 500
	// maxWatchlistName is the longest name, in characters.
	maxWatchlistName = 60
)

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
	notYet := huma.Error501NotImplemented("watchlists are not ready on this server yet")
	_ = deps

	huma.Register(h, op("listWatchlists", http.MethodGet, "/me/watchlists", "The signed-in person's watchlists",
		"Apps read it when they start and again on rows.changed, and press Watchlist with saveToOnlyWatchlist when there is at most one, else open the picker."),
		func(ctx context.Context, in *ListWatchlistsInput) (*WatchlistListOutput, error) {
			return nil, notYet
		})

	create := op("createWatchlist", http.MethodPost, "/me/watchlists", "Make a watchlist",
		"422 when the person has 25, or another of theirs has the name. Its rows go on Home after Continue watching and New from shows you follow, and first on Movies and TV screens. Sends rows.changed to the person.")
	create.DefaultStatus = http.StatusCreated
	huma.Register(h, create, func(ctx context.Context, in *CreateWatchlistInput) (*WatchlistOutput, error) {
		return nil, notYet
	})

	huma.Register(h, op("updateWatchlist", http.MethodPatch, "/me/watchlists/{id}", "Rename a watchlist, or turn Remove titles once watched on or off",
		"422 for a name another of the person's watchlists has. Another person's watchlist is not found, for admins too. Sends rows.changed to the person."),
		func(ctx context.Context, in *UpdateWatchlistInput) (*WatchlistOutput, error) {
			return nil, notYet
		})

	del := op("deleteWatchlist", http.MethodDelete, "/me/watchlists/{id}", "Delete a watchlist",
		"Its titles stay in the library; its rows leave every screen. deleteMediaList does the same. Sends rows.changed to the person.")
	del.DefaultStatus = http.StatusNoContent
	huma.Register(h, del, func(ctx context.Context, in *WatchlistIDInput) (*struct{}, error) {
		return nil, notYet
	})

	huma.Register(h, op("saveToWatchlist", http.MethodPut, "/me/watchlists/{id}/titles/{mediaType}/{tmdbId}", "Save a movie or show to a watchlist",
		"Any title, the library's or not. The body describes a title the server has no record of (one only Request search has shown); it is ignored when the server knows the title. A title already there keeps its place. 422 when the watchlist holds 500. Sends lists.changed to the person."),
		func(ctx context.Context, in *SaveToWatchlistInput) (*WatchlistTitleChangeOutput, error) {
			return nil, notYet
		})

	huma.Register(h, op("removeFromWatchlist", http.MethodDelete, "/me/watchlists/{id}/titles/{mediaType}/{tmdbId}", "Take a movie or show off a watchlist",
		"Succeeds when it is not there. Sends lists.changed to the person."),
		func(ctx context.Context, in *WatchlistTitlePath) (*WatchlistTitleChangeOutput, error) {
			return nil, notYet
		})

	only := op("saveToOnlyWatchlist", http.MethodPut, "/me/watchlist/titles/{mediaType}/{tmdbId}", "Save a movie or show to the person's watchlist, when they have one",
		"The Watchlist button pressed once. With no watchlist it makes one called Watchlist (created) and saves there; with one it saves there; with two or more it saves nothing and answers 409 with the watchlists (WatchlistsConflict), and the app opens the picker. The body is saveToWatchlist's.")
	only.Responses = watchlistsConflictResponses(h)
	huma.Register(h, only, func(ctx context.Context, in *SaveToOnlyWatchlistInput) (*WatchlistTitleChangeOutput, error) {
		return nil, notYet
	})

	huma.Register(h, op("removeFromWatchlists", http.MethodDelete, "/me/watchlist/titles/{mediaType}/{tmdbId}", "Take a movie or show off every one of the person's watchlists",
		"On Watchlist pressed again. Succeeds when no watchlist holds it. Sends lists.changed for each watchlist it left."),
		func(ctx context.Context, in *AnyWatchlistTitlePath) (*WatchlistTitleChangeOutput, error) {
			return nil, notYet
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
