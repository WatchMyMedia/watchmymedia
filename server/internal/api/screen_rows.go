package api

import (
	"context"
	"net/http"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
)

// Screens a person arranges (PLAN-ROWS.md §6): Home, and each library's
// Movies or TV screen as library-<id>.
const (
	ScreenHome          = "home"
	screenPattern       = `^(home|library-[0-9]+)$`
	maxRowsOnScreen     = 30
	maxRowLayoutEntries = 100
)

// Row styles. A style is a plain string in the API, not an enum, so a style
// added later reaches older apps as a value they skip.
const (
	RowStylePoster   = "poster"   // items, 2:3 posters
	RowStyleWide     = "wide"     // items, 16:9 cards (Recently released)
	RowStyleEpisode  = "episode"  // episodes
	RowStyleContinue = "continue" // continue
	RowStyleLive     = "live"     // no items: the app draws its Live TV row here
)

// ScreenRow is one row of a screen, in the person's order (PLAN-ROWS.md
// §6.1). Only rows with something to show are sent, except live, which the
// app fills.
type ScreenRow struct {
	ID       string           `json:"id" doc:"On Home: continue, live, recentlyAddedShows, recentlyAired, recentlyAddedMovies, recentlyReleased. On a library: recentlyAdded, recentlyAired (TV), recentlyReleased (movies). A list: list:<id>."`
	Style    string           `json:"style" doc:"How to draw it: poster (items), wide (items as 16:9 cards), episode (episodes), continue (continue) or live (no items: draw the app's Live TV row in this place). A plain string: skip a row whose style you don't know."`
	Title    string           `json:"title" example:"Recently added · Movies"`
	Subtitle string           `json:"subtitle,omitempty" required:"false" example:"18 of 50 · From TMDB" doc:"Lists: how many of the list's titles the library has, and where it is from."`
	Items    []BrowseItem     `json:"items,omitempty" required:"false" nullable:"false" doc:"Styles poster and wide."`
	Episodes []EpisodeCard    `json:"episodes,omitempty" required:"false" nullable:"false" doc:"Style episode."`
	Continue []ContinueItem   `json:"continue,omitempty" required:"false" nullable:"false" doc:"Style continue."`
	SeeAll   *ScreenRowLink   `json:"seeAll,omitempty" required:"false" doc:"Where the row's See all goes."`
	Source   *ScreenRowSource `json:"source,omitempty" required:"false" doc:"Lists: where the list comes from, for the attribution line."`
	ListID   int64            `json:"listId,omitempty" required:"false" doc:"Lists: the list's ID."`
}

// ScreenRowLink is where a row's See all goes.
type ScreenRowLink struct {
	Kind      string `json:"kind" doc:"library (the library's grid with these options) or list (the list's page). A plain string: hide See all for a kind you don't know."`
	LibraryID int64  `json:"libraryId,omitempty" required:"false"`
	Sort      string `json:"sort,omitempty" required:"false" doc:"Library: as listLibraryItems' sort."`
	Filter    string `json:"filter,omitempty" required:"false" doc:"Library: as listLibraryItems' filter."`
	Genre     string `json:"genre,omitempty" required:"false"`
	Decade    int    `json:"decade,omitempty" required:"false"`
	ListID    int64  `json:"listId,omitempty" required:"false"`
}

// ScreenRowSource names where a list comes from.
type ScreenRowSource struct {
	Kind string `json:"kind" doc:"tmdb, mdblist, library or import. A plain string."`
	Name string `json:"name" example:"TMDB" doc:"The words to show: From <name>."`
	URL  string `json:"url,omitempty" required:"false" doc:"A link to the list on the source's site, when it has one."`
}

// RowLayoutEntry is one row a person can place on a screen.
type RowLayoutEntry struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Style  string `json:"style" doc:"As ScreenRow.style."`
	Kind   string `json:"kind" doc:"builtin or list. A plain string."`
	Hidden bool   `json:"hidden" doc:"The person hid it, or it is a list made for another screen and not placed here yet. Add a row offers the hidden rows."`
	Shown  bool   `json:"shown" doc:"It is drawn when it has something: not hidden and within the screen's 30 rows, and for a list, some of its titles are in the library. A built-in row with nothing in it right now (no Continue watching yet) still counts as shown; the screen leaves it out until it has something."`
	Note   string `json:"note,omitempty" required:"false" example:"Nothing from this list is in your library yet" doc:"Why a row that is not hidden is not shown."`
	ListID int64  `json:"listId,omitempty" required:"false"`
	Owner  string `json:"owner,omitempty" required:"false" doc:"Lists: me (the person's own) or everyone (the household's)."`
}

// RowLayout is a screen's rows in order, for the editor.
type RowLayout struct {
	Screen     string           `json:"screen" example:"home"`
	Rows       []RowLayoutEntry `json:"rows" nullable:"false"`
	Customized bool             `json:"customized" doc:"Saved by this person (or, for the household default, by an admin); false means it follows the default."`
}

type RowLayoutOutput struct{ Body RowLayout }

type ScreenInput struct {
	Screen string `path:"screen" pattern:"^(home|library-[0-9]+)$" doc:"home, or library-<id> for a library's Movies or TV screen."`
}

// RowPlacement is one row in a saved order.
type RowPlacement struct {
	ID     string `json:"id" minLength:"1" maxLength:"64"`
	Hidden bool   `json:"hidden,omitempty" required:"false"`
}

type UpdateRowLayoutInput struct {
	Screen string `path:"screen" pattern:"^(home|library-[0-9]+)$"`
	Body   struct {
		Rows []RowPlacement `json:"rows" maxItems:"100" nullable:"false" doc:"Rows in order. Rows left out keep their default place, shown. Unknown IDs are refused."`
	}
}

// RowsChanged is the rows.changed event, sent to the person alone after
// their layout for a screen changes (or the household default, to everyone).
type RowsChanged struct {
	Screen string `json:"screen"`
}

// RowsChangedEvent is the rows.changed event.
const RowsChangedEvent = "rows.changed"

// publishRowsChanged sends rows.changed: to the person alone for their own
// layout (userID), to everyone for the household's (nil).
func publishRowsChanged(deps Deps, screen string, userID *int64) {
	if deps.Bus == nil {
		return
	}
	e := events.Event{Type: RowsChangedEvent, Data: RowsChanged{Screen: screen}}
	if userID != nil {
		e.To = &events.Audience{UserID: *userID, Private: true}
	}
	deps.Bus.Publish(e)
}

func registerScreenRows(h huma.API, deps Deps) {
	// mine is the signed-in person's catalogue of a screen.
	mine := func(ctx context.Context, screen string) (auth.User, screenCat, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return u, screenCat{}, err
		}
		ref, err := parseScreen(screen)
		if err != nil {
			return u, screenCat{}, err
		}
		c, err := catalogue(ctx, deps, ref, &u)
		return u, c, err
	}
	household := func(ctx context.Context, screen string) (screenCat, error) {
		ref, err := parseScreen(screen)
		if err != nil {
			return screenCat{}, err
		}
		return catalogue(ctx, deps, ref, nil)
	}
	layout := func(ctx context.Context, c screenCat) (*RowLayoutOutput, error) {
		l, _, _, err := screenLayout(ctx, deps, c, nil)
		if err != nil {
			return nil, err
		}
		return &RowLayoutOutput{Body: l}, nil
	}

	huma.Register(h, huma.Operation{
		OperationID: "getRowLayout",
		Method:      http.MethodGet,
		Path:        Prefix + "/me/rows/{screen}",
		Summary:     "Every row the signed-in person can place on a screen, in their order",
		Description: "Hidden rows are listed too: Add a row offers them. From API level 22.",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *ScreenInput) (*RowLayoutOutput, error) {
		_, c, err := mine(ctx, in.Screen)
		if err != nil {
			return nil, err
		}
		return layout(ctx, c)
	})

	huma.Register(h, huma.Operation{
		OperationID: "updateRowLayout",
		Method:      http.MethodPut,
		Path:        Prefix + "/me/rows/{screen}",
		Summary:     "Save the signed-in person's order of a screen's rows, and which are hidden",
		Description: "Rows left out keep their default place. Sends rows.changed to the person. From API level 22.",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *UpdateRowLayoutInput) (*RowLayoutOutput, error) {
		u, c, err := mine(ctx, in.Screen)
		if err != nil {
			return nil, err
		}
		if err := checkPlacements(c, in.Body.Rows); err != nil {
			return nil, err
		}
		if err := deps.Queries.UpsertUserPreference(ctx, db.UpsertUserPreferenceParams{UserID: u.ID, Key: c.ref.prefKey(), Value: encodeRows(in.Body.Rows)}); err != nil {
			return nil, err
		}
		publishRowsChanged(deps, c.ref.Name, &u.ID)
		return layout(ctx, c)
	})

	huma.Register(h, huma.Operation{
		OperationID: "resetRowLayout",
		Method:      http.MethodDelete,
		Path:        Prefix + "/me/rows/{screen}",
		Summary:     "Put a screen's rows back to the household's default for the signed-in person",
		Tags:        []string{"browse"},
	}, func(ctx context.Context, in *ScreenInput) (*RowLayoutOutput, error) {
		u, c, err := mine(ctx, in.Screen)
		if err != nil {
			return nil, err
		}
		if err := deps.Queries.DeleteUserPreference(ctx, db.DeleteUserPreferenceParams{UserID: u.ID, Key: c.ref.prefKey()}); err != nil {
			return nil, err
		}
		publishRowsChanged(deps, c.ref.Name, &u.ID)
		return layout(ctx, c)
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getDefaultRowLayout",
		Method:      http.MethodGet,
		Path:        Prefix + "/rows/defaults/{screen}",
		Summary:     "The household's default order of a screen's rows (admins)",
		Tags:        []string{"browse"},
	}), func(ctx context.Context, in *ScreenInput) (*RowLayoutOutput, error) {
		c, err := household(ctx, in.Screen)
		if err != nil {
			return nil, err
		}
		return layout(ctx, c)
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "updateDefaultRowLayout",
		Method:      http.MethodPut,
		Path:        Prefix + "/rows/defaults/{screen}",
		Summary:     "Save the household's default order of a screen's rows (admins)",
		Description: "People who never saved their own order follow it. Sends rows.changed to everyone.",
		Tags:        []string{"browse"},
	}), func(ctx context.Context, in *UpdateRowLayoutInput) (*RowLayoutOutput, error) {
		c, err := household(ctx, in.Screen)
		if err != nil {
			return nil, err
		}
		if err := checkPlacements(c, in.Body.Rows); err != nil {
			return nil, err
		}
		if err := deps.Settings.Set(ctx, c.ref.defaultKey(), encodeRows(in.Body.Rows)); err != nil {
			return nil, err
		}
		publishRowsChanged(deps, c.ref.Name, nil)
		return layout(ctx, c)
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "resetDefaultRowLayout",
		Method:      http.MethodDelete,
		Path:        Prefix + "/rows/defaults/{screen}",
		Summary:     "Put the household's default order of a screen's rows back to the built-in order (admins)",
		Tags:        []string{"browse"},
	}), func(ctx context.Context, in *ScreenInput) (*RowLayoutOutput, error) {
		c, err := household(ctx, in.Screen)
		if err != nil {
			return nil, err
		}
		if err := deps.Settings.Set(ctx, c.ref.defaultKey(), ""); err != nil {
			return nil, err
		}
		publishRowsChanged(deps, c.ref.Name, nil)
		return layout(ctx, c)
	})
}
