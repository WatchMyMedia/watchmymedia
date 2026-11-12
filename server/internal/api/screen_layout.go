package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strconv"
	"strings"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
)

// Row kinds in a layout (RowLayoutEntry.kind).
const (
	rowKindBuiltin = "builtin"
	rowKindList    = "list"
	listRowPrefix  = "list:"
)

// Built-in row IDs (PLAN-ROWS.md 6.1).
const (
	rowContinue            = "continue"
	rowLive                = "live"
	rowRecentlyAddedShows  = "recentlyAddedShows"
	rowRecentlyAired       = "recentlyAired"
	rowRecentlyAddedMovies = "recentlyAddedMovies"
	rowRecentlyReleased    = "recentlyReleased"
	rowRecentlyAdded       = "recentlyAdded"
)

// rowDef is a row a screen can show: built in, or made from a list.
type rowDef struct {
	ID    string
	Title string
	Style string
	Kind  string
	// List is the row's list, for list rows.
	List  *db.MediaList
	Owner string // me or everyone, for lists
}

// The built-in rows of each screen, in their default order.
var (
	homeBuiltins = []rowDef{
		{ID: rowContinue, Title: "Continue watching", Style: RowStyleContinue},
		{ID: rowLive, Title: "Live TV", Style: RowStyleLive},
		{ID: rowRecentlyAddedShows, Title: "Recently added · TV Shows", Style: RowStylePoster},
		{ID: rowRecentlyAired, Title: "Recently aired · Episodes", Style: RowStyleEpisode},
		{ID: rowRecentlyAddedMovies, Title: "Recently added · Movies", Style: RowStylePoster},
		{ID: rowRecentlyReleased, Title: "Recently released · Movies", Style: RowStyleWide},
	}
	movieLibraryBuiltins = []rowDef{
		{ID: rowRecentlyAdded, Title: "Recently added", Style: RowStylePoster},
		{ID: rowRecentlyReleased, Title: "Recently released", Style: RowStyleWide},
	}
	tvLibraryBuiltins = []rowDef{
		{ID: rowRecentlyAdded, Title: "Recently added", Style: RowStylePoster},
		{ID: rowRecentlyAired, Title: "Recently aired", Style: RowStyleEpisode},
	}
)

// screenRef is a screen a person arranges: Home, or a library's.
type screenRef struct {
	Name      string
	LibraryID int64 // 0 for Home
}

func parseScreen(s string) (screenRef, error) {
	if s == ScreenHome {
		return screenRef{Name: s}, nil
	}
	if rest, ok := strings.CutPrefix(s, "library-"); ok {
		if id, err := strconv.ParseInt(rest, 10, 64); err == nil && id > 0 && strconv.FormatInt(id, 10) == rest {
			return screenRef{Name: s, LibraryID: id}, nil
		}
	}
	return screenRef{}, huma.Error422UnprocessableEntity("a screen is home or library-<id>")
}

func libraryScreenName(id int64) string { return fmt.Sprintf("library-%d", id) }

// prefKey is where a person's layout of the screen is kept in
// user_preferences, defaultKey the household's in settings.
func (s screenRef) prefKey() string {
	if s.LibraryID == 0 {
		return "rows.home"
	}
	return fmt.Sprintf("rows.library.%d", s.LibraryID)
}

func (s screenRef) defaultKey() string {
	if s.LibraryID == 0 {
		return "rows.default.home"
	}
	return fmt.Sprintf("rows.default.library.%d", s.LibraryID)
}

// screenCat is every row a screen can show for a person (or, with user
// nil, for the household's default).
type screenCat struct {
	ref  screenRef
	lib  db.Library // library screens
	libs []db.Library
	user *auth.User
	// builtins are in their default order; own are the lists made on this
	// screen, newest first; other the other lists allowed here.
	builtins, own, other []rowDef
	byID                 map[string]rowDef
}

// entryTypes are the entry media types the screen shows: a library's own,
// or both on Home.
func (c screenCat) entryTypes() []string {
	if c.ref.LibraryID == 0 {
		return []string{"movie", "tv"}
	}
	return []string{c.lib.Type}
}

func (c screenCat) ids(defs []rowDef) []string {
	out := make([]string, len(defs))
	for i, d := range defs {
		out[i] = d.ID
	}
	return out
}

// catalogue lists the rows screen can show user (nil: the household
// default): its built-in rows, then the lists allowed on it. On Home
// every list the person sees is allowed; on a library's screen, lists of
// the library's media type or of both.
func catalogue(ctx context.Context, deps Deps, ref screenRef, user *auth.User) (screenCat, error) {
	libs, err := deps.Queries.ListLibraries(ctx)
	if err != nil {
		return screenCat{}, err
	}
	c := screenCat{ref: ref, libs: libs, user: user, byID: map[string]rowDef{}}
	has := map[string]bool{}
	for _, l := range libs {
		has[l.Type] = true
		if l.ID == ref.LibraryID {
			c.lib = l
		}
	}
	if ref.LibraryID != 0 && c.lib.ID == 0 {
		return c, huma.Error404NotFound("library not found")
	}
	var builtins []rowDef
	switch {
	case ref.LibraryID == 0:
		for _, d := range homeBuiltins {
			switch d.ID {
			case rowLive:
				if !liveRowAvailable(ctx, deps, user) {
					continue
				}
			case rowRecentlyAddedShows, rowRecentlyAired:
				if !has[library.TypeTV] {
					continue
				}
			case rowRecentlyAddedMovies, rowRecentlyReleased:
				if !has[library.TypeMovie] {
					continue
				}
			}
			builtins = append(builtins, d)
		}
	case c.lib.Type == library.TypeMovie:
		builtins = movieLibraryBuiltins
	default:
		builtins = tvLibraryBuiltins
	}
	for _, d := range builtins {
		d.Kind = rowKindBuiltin
		c.builtins = append(c.builtins, d)
		c.byID[d.ID] = d
	}
	var all []db.MediaList
	if user != nil {
		all, err = deps.Queries.ListVisibleMediaLists(ctx, &user.ID)
	} else {
		all, err = deps.Queries.ListHouseholdMediaLists(ctx)
	}
	if err != nil {
		return c, err
	}
	// Newest first, the household's among them.
	slices.SortStableFunc(all, func(a, b db.MediaList) int { return int(b.ID - a.ID) })
	for i := range all {
		l := &all[i]
		if ref.LibraryID != 0 && l.MediaType != "all" && l.MediaType != c.lib.Type {
			continue
		}
		d := rowDef{ID: listRowID(l.ID), Title: l.Name, Style: RowStylePoster, Kind: rowKindList, List: l, Owner: "everyone"}
		if l.OwnerID != nil {
			d.Owner = "me"
		}
		if l.Screen == ref.Name {
			c.own = append(c.own, d)
		} else {
			c.other = append(c.other, d)
		}
		c.byID[d.ID] = d
	}
	return c, nil
}

func listRowID(id int64) string { return listRowPrefix + strconv.FormatInt(id, 10) }

// liveRowAvailable is whether Live TV is set up for user: a tuner is on
// and the person may watch (for the household default, anyone may).
func liveRowAvailable(ctx context.Context, deps Deps, user *auth.User) bool {
	if deps.LiveTV == nil {
		return false
	}
	sources, err := deps.LiveTV.Sources(ctx)
	if err != nil {
		return false
	}
	on := false
	for _, s := range sources {
		on = on || s.Enabled
	}
	if !on {
		return false
	}
	if user == nil {
		return deps.LiveTV.Access(ctx) != "none"
	}
	return deps.LiveTV.Allowed(ctx, user.IsAdmin)
}

// mergeRows places a screen's rows (PLAN-ROWS.md 7.1). It starts from base,
// the saved order (nil for none: the built-in order), and drops IDs the
// screen doesn't have. A built-in row base leaves out goes right after the
// nearest row before it in defaults, the effective default order, or
// first when none is there. Lists made on this screen and left out go
// first, shown; other lists allowed here go last, hidden, for Add a row.
func mergeRows(base []RowPlacement, defaults, builtins, own, other []string) []RowPlacement {
	known := map[string]bool{}
	for _, ids := range [][]string{builtins, own, other} {
		for _, id := range ids {
			known[id] = true
		}
	}
	out := []RowPlacement{}
	seen := map[string]bool{}
	if base == nil {
		for _, id := range builtins {
			out = append(out, RowPlacement{ID: id})
			seen[id] = true
		}
	}
	for _, p := range base {
		if known[p.ID] && !seen[p.ID] {
			out = append(out, p)
			seen[p.ID] = true
		}
	}
	order := slices.Clone(defaults)
	for _, id := range builtins {
		if !slices.Contains(order, id) {
			order = append(order, id)
		}
	}
	for i, id := range order {
		if seen[id] || !slices.Contains(builtins, id) {
			continue
		}
		at := 0
		for j := i - 1; j >= 0; j-- {
			if k := slices.IndexFunc(out, func(p RowPlacement) bool { return p.ID == order[j] }); k >= 0 {
				at = k + 1
				break
			}
		}
		out = slices.Insert(out, at, RowPlacement{ID: id})
		seen[id] = true
	}
	var first []RowPlacement
	for _, id := range own {
		if !seen[id] {
			first = append(first, RowPlacement{ID: id})
			seen[id] = true
		}
	}
	out = append(first, out...)
	for _, id := range other {
		if !seen[id] {
			out = append(out, RowPlacement{ID: id, Hidden: true})
			seen[id] = true
		}
	}
	return out
}

// storedRows reads a saved layout; ok is false when nothing usable is
// stored.
func storedRows(raw string) (rows []RowPlacement, ok bool) {
	if raw == "" || json.Unmarshal([]byte(raw), &rows) != nil {
		return nil, false
	}
	if rows == nil {
		rows = []RowPlacement{}
	}
	return rows, true
}

func encodeRows(rows []RowPlacement) string {
	if rows == nil {
		rows = []RowPlacement{}
	}
	b, _ := json.Marshal(rows)
	return string(b)
}

// householdRows is the household's saved default for the screen, if any.
func householdRows(ctx context.Context, deps Deps, ref screenRef) ([]RowPlacement, bool, error) {
	if deps.Settings == nil {
		return nil, false, nil
	}
	raw, err := deps.Settings.Get(ctx, ref.defaultKey(), "")
	if err != nil {
		return nil, false, err
	}
	rows, ok := storedRows(raw)
	return rows, ok, nil
}

// personRows is the person's saved layout of the screen, if any.
func personRows(ctx context.Context, deps Deps, userID int64, ref screenRef) ([]RowPlacement, bool, error) {
	raw, err := deps.Queries.GetUserPreference(ctx, db.GetUserPreferenceParams{UserID: userID, Key: ref.prefKey()})
	if errors.Is(err, sql.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	rows, ok := storedRows(raw)
	return rows, ok, nil
}

// placedRow is a row in a screen's order.
type placedRow struct {
	rowDef
	Hidden bool
}

// arrange is the screen's rows in order for c's person: their saved
// order, else the household's, else the built-in order, merged with what
// the screen has now. customized says the order is the person's own (or,
// for the household default, an admin's).
func arrange(ctx context.Context, deps Deps, c screenCat) (rows []placedRow, customized bool, err error) {
	builtins, own, other := c.ids(c.builtins), c.ids(c.own), c.ids(c.other)
	household, hasHousehold, err := householdRows(ctx, deps, c.ref)
	if err != nil {
		return nil, false, err
	}
	defaults := builtins
	if hasHousehold {
		defaults = placementIDs(mergeRows(household, builtins, builtins, own, other))
	}
	base := household
	if !hasHousehold {
		base = nil
	}
	customized = c.user == nil && hasHousehold
	if c.user != nil {
		mine, ok, err := personRows(ctx, deps, c.user.ID, c.ref)
		if err != nil {
			return nil, false, err
		}
		if ok {
			base, customized = mine, true
		}
	}
	if c.user == nil {
		defaults = builtins
	}
	for _, p := range mergeRows(base, defaults, builtins, own, other) {
		rows = append(rows, placedRow{rowDef: c.byID[p.ID], Hidden: p.Hidden})
	}
	return rows, customized, nil
}

func placementIDs(rows []RowPlacement) []string {
	out := make([]string, len(rows))
	for i, r := range rows {
		out[i] = r.ID
	}
	return out
}

// checkPlacements refuses an order naming a row the screen doesn't have,
// or one row twice.
func checkPlacements(c screenCat, rows []RowPlacement) error {
	seen := map[string]bool{}
	for _, r := range rows {
		if _, ok := c.byID[r.ID]; !ok {
			return huma.Error422UnprocessableEntity(fmt.Sprintf("this screen has no row %q", r.ID))
		}
		if seen[r.ID] {
			return huma.Error422UnprocessableEntity(fmt.Sprintf("row %q is placed twice", r.ID))
		}
		seen[r.ID] = true
	}
	return nil
}

// layoutEntries says, for each row in order, whether it is drawn: not
// hidden, something to show (for lists; built-in rows are assumed to have
// some, as filling them is the screen's work) and within the screen's 30
// rows.
func layoutEntries(rows []placedRow, counts map[int64]listCounts) []RowLayoutEntry {
	out := make([]RowLayoutEntry, 0, len(rows))
	drawn := 0
	for _, r := range rows {
		e := RowLayoutEntry{ID: r.ID, Title: r.Title, Style: r.Style, Kind: r.Kind, Hidden: r.Hidden, Owner: r.Owner}
		if r.List != nil {
			e.ListID = r.List.ID
		}
		n := counts[e.ListID]
		switch {
		case r.Hidden:
		case r.List != nil && r.List.SourceKind == "library" && n.inLibrary == 0:
			e.Note = "Nothing in the library matches this list's filter"
		case r.List != nil && n.entries == 0 && r.List.Error != nil:
			e.Note = "This list couldn't be fetched: " + *r.List.Error
		case r.List != nil && n.inLibrary == 0:
			e.Note = "Nothing from this list is in your library yet"
		case drawn >= maxRowsOnScreen:
			e.Note = "Not shown: too many rows"
		default:
			e.Shown = true
			drawn++
		}
		out = append(out, e)
	}
	return out
}

// screenLayout is a screen's layout, which the editor shows and the
// arranged screen fills, and what the lists that aren't hidden hold. No
// rows are filled; marks (nil to read them only if a library list needs
// them) mark library lists' items.
func screenLayout(ctx context.Context, deps Deps, c screenCat, marks *watchMarks) (RowLayout, []placedRow, listData, error) {
	rows, customized, err := arrange(ctx, deps, c)
	if err != nil {
		return RowLayout{}, nil, listData{}, err
	}
	var ls []db.MediaList
	for _, r := range rows {
		if r.List != nil && !r.Hidden {
			ls = append(ls, *r.List)
		}
	}
	data, err := loadListData(ctx, deps, ls, c.entryTypes(), c.ref.LibraryID, marks)
	if err != nil {
		return RowLayout{}, nil, listData{}, err
	}
	return RowLayout{Screen: c.ref.Name, Rows: layoutEntries(rows, data.counts), Customized: customized}, rows, data, nil
}

// builtinLink is where a built-in row's See all goes: the library's grid
// sorted as the row is, when the screen is a library's or Home has one
// library of that type.
func builtinLink(c screenCat, id string) *ScreenRowLink {
	sort := map[string]string{rowRecentlyAdded: "added", rowRecentlyAddedMovies: "added", rowRecentlyAddedShows: "added",
		rowRecentlyReleased: "released", rowRecentlyAired: "aired"}[id]
	if sort == "" {
		return nil
	}
	if c.ref.LibraryID != 0 {
		return &ScreenRowLink{Kind: "library", LibraryID: c.ref.LibraryID, Sort: sort}
	}
	kind := library.TypeMovie
	if id == rowRecentlyAddedShows || id == rowRecentlyAired {
		kind = library.TypeTV
	}
	var found []int64
	for _, l := range c.libs {
		if l.Type == kind {
			found = append(found, l.ID)
		}
	}
	if len(found) != 1 {
		return nil
	}
	return &ScreenRowLink{Kind: "library", LibraryID: found[0], Sort: sort}
}

// rowWants says which of a screen's fixed rows are drawn, so only those
// are filled.
type rowWants struct {
	continueWatching, released, aired bool
}

var allRows = rowWants{continueWatching: true, released: true, aired: true}

func wantsOf(entries []RowLayoutEntry) rowWants {
	var w rowWants
	for _, e := range entries {
		if !e.Shown {
			continue
		}
		switch e.ID {
		case rowContinue:
			w.continueWatching = true
		case rowRecentlyReleased:
			w.released = true
		case rowRecentlyAired:
			w.aired = true
		}
	}
	return w
}

// arrangedRows fills the drawn rows of a screen in order: built-in rows
// from builtin, which returns false for an empty one, and list rows from
// their lists. A built-in row with nothing to show is left out, except
// live, which the apps fill.
func arrangedRows(ctx context.Context, deps Deps, c screenCat, rows []placedRow, entries []RowLayoutEntry, data listData, builtin func(id string) (ScreenRow, bool)) ([]ScreenRow, error) {
	var lists []*db.MediaList
	for i, e := range entries {
		if e.Shown && rows[i].List != nil {
			lists = append(lists, rows[i].List)
		}
	}
	listRows, err := listScreenRows(ctx, deps, data, lists)
	if err != nil {
		return nil, err
	}
	if deps.Lists != nil && len(lists) > 0 {
		shown := make([]db.MediaList, len(lists))
		for i, l := range lists {
			shown[i] = *l
		}
		deps.Lists.Want(ctx, shown...)
	}
	out := []ScreenRow{}
	for i, e := range entries {
		if !e.Shown {
			continue
		}
		if rows[i].List != nil {
			if r, ok := listRows[rows[i].List.ID]; ok {
				out = append(out, r)
			}
			continue
		}
		r, ok := builtin(e.ID)
		if !ok {
			continue
		}
		r.ID, r.Title, r.Style = e.ID, e.Title, e.Style
		r.SeeAll = builtinLink(c, e.ID)
		out = append(out, r)
	}
	return out, nil
}

// signedInUser is the person on the request, or nil when no one is signed
// in (auth off): they get the household's layout.
func signedInUser(ctx context.Context) *auth.User {
	if u, ok := auth.UserFrom(ctx); ok {
		return &u
	}
	return nil
}

// homeRows is Home's arranged rows from the fixed rows in h, which were
// filled for wants.
func homeRows(ctx context.Context, deps Deps, c screenCat, rows []placedRow, entries []RowLayoutEntry, h HomeData, data listData) ([]ScreenRow, error) {
	return arrangedRows(ctx, deps, c, rows, entries, data, func(id string) (ScreenRow, bool) {
		switch id {
		case rowContinue:
			return ScreenRow{Continue: h.ContinueWatching}, len(h.ContinueWatching) > 0
		case rowLive:
			return ScreenRow{}, true
		case rowRecentlyAddedShows:
			return ScreenRow{Items: h.RecentlyAddedShows}, len(h.RecentlyAddedShows) > 0
		case rowRecentlyAired:
			return ScreenRow{Episodes: h.RecentlyAired}, len(h.RecentlyAired) > 0
		case rowRecentlyAddedMovies:
			return ScreenRow{Items: h.RecentlyAddedMovies}, len(h.RecentlyAddedMovies) > 0
		case rowRecentlyReleased:
			return ScreenRow{Items: h.RecentlyReleased}, len(h.RecentlyReleased) > 0
		}
		return ScreenRow{}, false
	})
}

// libraryScreenRows is a library screen's arranged rows from its fixed rows.
func libraryScreenRows(ctx context.Context, deps Deps, c screenCat, rows []placedRow, entries []RowLayoutEntry, lr LibraryRows, data listData) ([]ScreenRow, error) {
	return arrangedRows(ctx, deps, c, rows, entries, data, func(id string) (ScreenRow, bool) {
		switch id {
		case rowRecentlyAdded:
			return ScreenRow{Items: lr.RecentlyAdded}, len(lr.RecentlyAdded) > 0
		case rowRecentlyAired:
			return ScreenRow{Episodes: lr.RecentlyAired}, len(lr.RecentlyAired) > 0
		case rowRecentlyReleased:
			return ScreenRow{Items: lr.RecentlyReleased}, len(lr.RecentlyReleased) > 0
		}
		return ScreenRow{}, false
	})
}
