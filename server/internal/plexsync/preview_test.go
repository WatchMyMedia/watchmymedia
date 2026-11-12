package plexsync

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex/plextest"
	"watchmymedia/server/internal/watchstate"
)

// previewEnv links the owner to the admin, the managed profile 706 to the
// viewer, and the invited 702 to a third account, with each one's own
// marks in Plex.
func previewEnv(t *testing.T) (*env, PlexConnection, int64) {
	t.Helper()
	e := newEnv(t)
	ctx := t.Context()
	third, err := e.q.CreateUser(ctx, db.CreateUserParams{Name: "third", PasswordHash: "x"})
	if err != nil {
		t.Fatal(err)
	}
	f := e.f
	f.SetItems("owner", "1",
		plextest.MovieJSON("101", "Beacon", "810001", "tt0810001", 2),
		plextest.MovieJSON("102", "Elsewhere", "810002", "", 1),
		plextest.MovieJSON("103", "Dunes", "", "tt0810004", 1),
		plextest.MovieJSON("104", "Harbor", "810005", "tt0810001", 1),
		plextest.MovieJSON("105", "Copper unwatched", "810003", "", 0),
		`{"ratingKey":"106","type":"movie","title":"Copper cut","editionTitle":"Director's Cut","Guid":[{"id":"tmdb://810003"}],"viewCount":1}`,
		`{"ratingKey":"107","type":"movie","title":"Copper unknown","Guid":[{"id":"tmdb://810003"}]}`,
		plextest.MovieJSON("108", "Copper", "810003", "", 3),
		`{"ratingKey":"109","type":"movie","title":"Two IDs","Guid":[{"id":"tmdb://810001"},{"id":"tmdb://810003"}],"viewCount":1}`,
		`{"ratingKey":"110","type":"movie","title":"Only TVDB","Guid":[{"id":"tvdb://555"}],"viewCount":1}`,
	)
	f.SetItems("owner", "2",
		plextest.EpisodeJSON("201", "Pilot", "301", 1, 1, 1),
		plextest.EpisodeJSON("202", "Special", "301", 0, 1, 1),
		plextest.EpisodeJSON("203", "DVD order", "302", 1, 1, 1),
		plextest.EpisodeJSON("204", "TVDB show", "303", 1, 1, 1),
		plextest.EpisodeJSON("205", "Missing here", "301", 1, 5, 1),
		plextest.EpisodeJSON("206", "Gone show", "304", 1, 1, 1),
	)
	f.Set(func(f *plextest.Fake) {
		f.Shows["301"] = `{"ratingKey":"301","type":"show","title":"Signal Keepers","episodeOrdering":"tmdbAiring","Guid":[{"id":"tmdb://820001"},{"id":"tvdb://920001"}]}`
		f.Shows["302"] = `{"ratingKey":"302","type":"show","title":"Signal Keepers DVD","Guid":[{"id":"tmdb://820001"}],"episodeOrdering":"dvd"}`
		f.Shows["303"] = `{"ratingKey":"303","type":"show","title":"TVDB only","Guid":[{"id":"tvdb://920003"}]}`
		f.Visible = map[string][]string{"synthetic-server-702": {"1"}}
	})
	f.SetItems("synthetic-server-706", "1",
		plextest.MovieJSON("101", "Beacon", "810001", "tt0810001", 0),
		plextest.MovieJSON("108", "Copper", "810003", "", 1),
	)
	f.SetItems("synthetic-server-706", "2", plextest.EpisodeJSON("207", "Second", "301", 1, 2, 1))
	f.SetItems("synthetic-server-702", "1", plextest.MovieJSON("101", "Beacon", "810001", "tt0810001", 1))
	// The admin already watched Copper here, and unwatched Dunes.
	if err := e.watch.MarkWatched(ctx, e.admin, watchstate.Movie, e.ids["copper"]); err != nil {
		t.Fatal(err)
	}
	if err := e.watch.MarkUnwatched(ctx, e.admin, watchstate.Movie, e.ids["dunes"]); err != nil {
		t.Fatal(err)
	}
	c := e.connect()
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}, {PlexUserID: 706, UserID: e.viewer}, {PlexUserID: 702, UserID: third.ID}},
		[]PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}, {SectionID: 2, LibraryID: e.tv}})
	return e, c, third.ID
}

func (e *env) count(table string) int {
	e.t.Helper()
	var n int
	if err := e.conn.QueryRowContext(e.t.Context(), "SELECT COUNT(*) FROM "+table).Scan(&n); err != nil {
		e.t.Fatal(err)
	}
	return n
}

// Each linked person's preview is made from their own marks, read through
// their own credential: the managed profile through a Home switch, the
// invited user through their shared credential, never the owner's.
func TestPreviewReadsEachPersonThroughTheirOwnCredential(t *testing.T) {
	e, c, third := previewEnv(t)
	p, err := e.svc.StartPreview(t.Context(), c.ID)
	if err != nil || p.Status != "running" {
		t.Fatalf("start = %+v %v", p, err)
	}
	p = e.waitPreview(c, p.ID)
	if p.Status != "complete" || len(p.Users) != 3 {
		t.Fatalf("preview = %+v", p)
	}
	users := map[int64]PlexPreviewUser{}
	for _, u := range p.Users {
		users[u.UserID] = u
	}
	viewer := users[e.viewer]
	if viewer.Kind != KindManaged || viewer.Status != "complete" || viewer.Counts != (PlexCounts{WillMark: 2, NotWatched: 1}) {
		t.Fatalf("managed profile's preview = %+v; want only its own marks", viewer)
	}
	invited := users[third]
	if invited.Kind != KindAccount || invited.Counts != (PlexCounts{WillMark: 1}) || invited.SkippedSections != 1 {
		t.Fatalf("invited user's preview = %+v; want Beacon only, the TV library not visible to them", invited)
	}
	if reads := e.f.ReadsBy("synthetic-server-706"); !slices.Contains(reads, "/library/sections/1/all") || !slices.Contains(reads, "/library/sections/2/all") {
		t.Errorf("the profile's own credential read %v", reads)
	}
	owned := 0
	for _, r := range e.f.ReadsBy(plextest.OwnerDeviceToken) {
		if strings.HasPrefix(r, "/library/sections/") && strings.HasSuffix(r, "/all") {
			owned++
		}
	}
	if owned != 2 {
		t.Errorf("the owner's credential read %d libraries; want only the owner's two", owned)
	}
	if len(e.f.Pins()) != 0 {
		t.Error("a PIN was sent")
	}
	names := map[int64]string{}
	for _, u := range p.Unlinked {
		names[u.PlexUserID] = u.Status
	}
	if names[707] != StatusNeedsPIN || names[704] != StatusNeedsProfileAuthorization || names[705] != StatusReady || len(names) != 4 {
		t.Errorf("unlinked = %v; want 704, 705, 707 (needs PIN) and the guest named", names)
	}
}

// Matching uses exact IDs within the linked library only, and the outcome
// comes from watchstate's import rules.
func TestPreviewMatchesByExactIDs(t *testing.T) {
	e, c, _ := previewEnv(t)
	p, err := e.svc.StartPreview(t.Context(), c.ID)
	if err != nil {
		t.Fatal(err)
	}
	p = e.waitPreview(c, p.ID)
	var admin PlexPreviewUser
	for _, u := range p.Users {
		if u.UserID == e.admin {
			admin = u
		}
	}
	want := PlexCounts{WillMark: 2, AlreadyWatched: 1, LocalKept: 1, Unmatched: 5, Ambiguous: 5, NotWatched: 1, UnknownState: 1}
	if admin.Counts != want {
		t.Fatalf("owner's counts = %+v\nwant %+v", admin.Counts, want)
	}
	reasons := map[string]string{}
	for _, ex := range admin.Examples {
		reasons[ex.Title] = ex.Category + " " + ex.Reason
		if ex.Title == "Beacon" && ex.ItemID != e.ids["beacon"] {
			t.Errorf("Beacon matched item %d; want %d", ex.ItemID, e.ids["beacon"])
		}
		if ex.Title == "Pilot" && (ex.ItemID != e.ids["s1e1"] || ex.Show != "Signal Keepers" || ex.Season != 1 || ex.Episode != 1) {
			t.Errorf("Pilot = %+v", ex)
		}
	}
	for title, w := range map[string]string{
		"Beacon":       "willMark ",
		"Pilot":        "willMark ",
		"Copper":       "alreadyWatched ",
		"Dunes":        "localKept ", // matched by IMDb alone; the admin unwatched it here
		"Elsewhere":    "unmatched not_in_library",
		"Only TVDB":    "unmatched no_tmdb_or_imdb_id",
		"Harbor":       "ambiguous conflicting_ids",
		"Copper cut":   "ambiguous edition",
		"Two IDs":      "ambiguous conflicting_ids",
		"Special":      "ambiguous special",
		"DVD order":    "ambiguous alternate_order",
		"TVDB show":    "unmatched no_show_tmdb_id",
		"Missing here": "unmatched not_in_library",
	} {
		if reasons[title] != w {
			t.Errorf("%s = %q; want %q", title, reasons[title], w)
		}
	}
	// Five unmatched, but each category names five at most.
	n := 0
	for _, ex := range admin.Examples {
		if ex.Category == "unmatched" {
			n++
		}
	}
	if n != maxExamples {
		t.Errorf("%d unmatched examples; want %d", n, maxExamples)
	}
}

// A preview writes nothing: no watch state, no guards, no links.
func TestPreviewWritesNothing(t *testing.T) {
	e, c, _ := previewEnv(t)
	before := fmt.Sprint(e.count("watch_state"), e.count("watch_guards"), e.count("plex_user_links"), e.count("plex_library_links"))
	p, err := e.svc.StartPreview(t.Context(), c.ID)
	if err != nil {
		t.Fatal(err)
	}
	e.waitPreview(c, p.ID)
	after := fmt.Sprint(e.count("watch_state"), e.count("watch_guards"), e.count("plex_user_links"), e.count("plex_library_links"))
	if before != after {
		t.Fatalf("rows before %s, after %s", before, after)
	}
	conn, _ := e.q.GetPlexConnection(t.Context(), c.ID)
	if conn.SyncEnabled != 0 || conn.Generation != c.Generation {
		t.Fatalf("the preview changed the connection: %+v", conn)
	}
}

// A preview made before the links changed, or before a linked account was
// deleted, shows nothing more; saving links drops it.
func TestPreviewGoesStale(t *testing.T) {
	e, c, _ := previewEnv(t)
	ctx := t.Context()
	p, err := e.svc.StartPreview(ctx, c.ID)
	if err != nil {
		t.Fatal(err)
	}
	_, err = e.svc.StartPreview(ctx, c.ID)
	if err == nil {
		// It may already have finished; a second start is then allowed.
		t.Log("the first preview finished before the second start")
	} else {
		wantCode(t, err, Conflict, "preview_running")
	}
	e.waitPreview(c, p.ID)
	if err := e.q.DeleteUser(ctx, e.viewer); err != nil {
		t.Fatal(err)
	}
	got, err := e.svc.GetPreview(ctx, c.ID, p.ID)
	if err != nil || got.Status != "stale" || got.Users != nil || got.Unlinked != nil {
		t.Fatalf("after deleting a linked account: %+v %v", got, err)
	}
	_, err = e.svc.GetPreview(ctx, c.ID, "another")
	wantCode(t, err, NotFound, "preview_not_found")
	conn, _ := e.q.GetPlexConnection(ctx, c.ID)
	e.link(PlexConnection{ID: c.ID, Generation: conn.Generation}, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	_, err = e.svc.GetPreview(ctx, c.ID, p.ID)
	wantCode(t, err, NotFound, "preview_not_found")
}

// A linked library whose section Plex replaced (same ID, new UUID) is left
// out rather than read into the old link.
func TestPreviewSkipsReplacedSections(t *testing.T) {
	e, c, _ := previewEnv(t)
	e.f.Set(func(f *plextest.Fake) {
		f.Sections = strings.Replace(f.Sections, `"uuid":"fictional-shows"`, `"uuid":"fictional-shows-new"`, 1)
	})
	p, err := e.svc.StartPreview(t.Context(), c.ID)
	if err != nil {
		t.Fatal(err)
	}
	p = e.waitPreview(c, p.ID)
	for _, u := range p.Users {
		if u.UserID == e.admin && (u.SkippedSections != 1 || u.Counts.WillMark != 1) {
			t.Fatalf("owner = %+v; want the TV library left out", u)
		}
	}
}

func TestPreviewNeedsLinks(t *testing.T) {
	e := newEnv(t)
	c := e.connect()
	_, err := e.svc.StartPreview(t.Context(), c.ID)
	wantCode(t, err, Conflict, "nothing_linked")
}

// Nothing secret reaches the log or a reply: no Plex token, no device key,
// no linking code (the sign-in's Plex page carries it, and only that reply
// does), no profile PIN.
func TestSecretsStayOutOfLogsAndReplies(t *testing.T) {
	e, c, _ := previewEnv(t)
	ctx := t.Context()
	g := e.grantOnDisk()
	var replies []any
	add := func(v any, err error) {
		t.Helper()
		replies = append(replies, v)
		if err != nil {
			replies = append(replies, err.Error())
		}
	}
	add(e.svc.Connections(ctx))
	_, people, err := e.svc.People(ctx, c.ID)
	add(people, err)
	_, sections, err := e.svc.Libraries(ctx, c.ID)
	add(sections, err)
	p, err := e.svc.StartPreview(ctx, c.ID)
	add(p, err)
	add(e.waitPreview(c, p.ID), nil)
	in, err := e.svc.StartSignIn(ctx, e.who)
	if err != nil {
		t.Fatal(err)
	}
	add(e.svc.SignInStatus(ctx, e.who, in.ID))
	e.f.Set(func(f *plextest.Fake) { f.Revoked = true })
	_, _, err = e.svc.People(ctx, c.ID)
	add(nil, err)
	_, err = e.svc.SaveLinks(ctx, c.ID, PlexLinks{Generation: c.Generation})
	add(nil, err)
	b, err := json.Marshal(replies)
	if err != nil {
		t.Fatal(err)
	}
	for where, text := range map[string]string{"replies": string(b), "log": e.logs.String()} {
		for _, secret := range []string{"synthetic-", "fictional-link-code", g.ClientIdentifier, base64.StdEncoding.EncodeToString(g.Key.Seed())} {
			if secret != "" && strings.Contains(text, secret) {
				t.Errorf("the %s carry %q", where, secret)
			}
		}
	}
	if !strings.Contains(e.logs.String(), "authorization_lost") {
		t.Error("the log should say why the connection needs a new sign-in")
	}
}
