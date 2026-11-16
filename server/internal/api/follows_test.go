package api

import (
	"fmt"
	"net/http"
	"slices"
	"strconv"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

// followEnv is listsEnv with a way to add shows, episodes and arrivals.
type followEnv struct {
	*listsEnv
}

func newFollowEnv(t *testing.T) followEnv {
	t.Helper()
	return followEnv{newListsEnv(t, nil)}
}

func (e followEnv) exec(query string, args ...any) int64 {
	e.t.Helper()
	res, err := e.conn.ExecContext(e.t.Context(), query, args...)
	if err != nil {
		e.t.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}

// show adds a show to a TV library with n episodes in season 1, each with
// a present file, and returns the show and its episodes.
func (e followEnv) show(lib, tmdbID int64, title string, n int) (int64, []int64) {
	e.t.Helper()
	show := e.exec(`INSERT INTO shows (library_id, tmdb_id, title, genres, poster_path, backdrop_path) VALUES (?, ?, ?, '[]', '/p.jpg', '/b.jpg')`, lib, tmdbID, title)
	season := e.exec(`INSERT INTO seasons (show_id, number, episode_count) VALUES (?, 1, ?)`, show, n)
	var eps []int64
	for i := 1; i <= n; i++ {
		eps = append(eps, e.episode(lib, show, season, i))
	}
	return show, eps
}

func (e followEnv) episode(lib, show, season int64, number int) int64 {
	e.t.Helper()
	ep := e.exec(`INSERT INTO episodes (season_id, show_id, number, title, still_path) VALUES (?, ?, ?, ?, '/still.jpg')`, season, show, number, fmt.Sprintf("Chapter %d", number))
	f := e.exec(`INSERT INTO media_files (library_id, path, size, mtime, status, parsed, height, added_at, match_status, first_linked_at)
		VALUES (?, ?, 500, '2026-01-01T00:00:00.000Z', 'present', '{"kind":"episode"}', 1080, '2026-01-01T00:00:00.000Z', 'matched', '2026-01-01T00:00:00.000Z')`,
		lib, fmt.Sprintf("/t/%d/%d/%d.mkv", show, season, number))
	e.exec(`INSERT INTO episode_files (episode_id, media_file_id) VALUES (?, ?)`, ep, f)
	return ep
}

// special adds season 0's first episode to a show.
func (e followEnv) special(lib, show int64) int64 {
	e.t.Helper()
	season := e.exec(`INSERT INTO seasons (show_id, number, episode_count) VALUES (?, 0, 1)`, show)
	return e.episode(lib, show, season, 1)
}

// arrive records an episode's arrival ago in the past, as the matcher does:
// for its TMDB episode, on its row.
func (e followEnv) arrive(episode int64, ago time.Duration, quiet bool) int64 {
	e.t.Helper()
	q := 0
	if quiet {
		q = 1
	}
	return e.exec(`INSERT INTO episode_arrivals (tmdb_id, season, number, episode_id, arrived_at, quiet)
		SELECT s.tmdb_id, se.number, ep.number, ep.id, ?, ? FROM episodes ep JOIN seasons se ON se.id = ep.season_id JOIN shows s ON s.id = ep.show_id
		WHERE ep.id = ?`, db.FormatTime(time.Now().Add(-ago)), q, episode)
}

func (e followEnv) feed(as func(string, string, string, any) reply, after string) NewEpisodes {
	e.t.Helper()
	path := "/api/me/new-episodes"
	if after != "" {
		path += "?after=" + after
	}
	var out NewEpisodes
	if res := as(http.MethodGet, path, "", &out); res.StatusCode != http.StatusOK {
		e.t.Fatalf("GET %s = %d %s", path, res.StatusCode, res.Body)
	}
	return out
}

func (e followEnv) follow(as func(string, string, string, any) reply, show int64) {
	e.t.Helper()
	if res := as(http.MethodPut, fmt.Sprintf("/api/shows/%d/follow", show), "", nil); res.StatusCode != http.StatusNoContent {
		e.t.Fatalf("follow %d = %d %s", show, res.StatusCode, res.Body)
	}
}

// groups writes a feed's shows as "Title: S1E2 S1E3 (3) open S1E2".
func groups(f NewEpisodes) []string {
	var out []string
	for _, g := range f.Shows {
		s := g.Show.Title + ":"
		for _, ep := range g.Episodes {
			s += fmt.Sprintf(" S%dE%d", ep.Season, ep.Number)
		}
		out = append(out, s+fmt.Sprintf(" (%d) open S%dE%d", g.Count, g.Open.Season, g.Open.Number))
	}
	return out
}

func cursorOf(t *testing.T, f NewEpisodes) int64 {
	t.Helper()
	n, err := strconv.ParseInt(f.Cursor, 10, 64)
	if err != nil {
		t.Fatalf("cursor %q", f.Cursor)
	}
	return n
}

// Follow, unfollow and follow again; the bell on the show's page; the list,
// newest first, with a show that left the library; one follow for a show
// in two libraries; the limit; and the event, to that person alone
// (PLAN-NEW-EPISODES.md section 5.3).
func TestFollowShows(t *testing.T) {
	t.Parallel()
	e := newFollowEnv(t)
	tide, tideEps := e.show(e.tv, 7001, "Tidewater", 2)
	point, _ := e.show(e.tv, 7002, "Lantern Point", 1)
	e.arrive(tideEps[1], time.Hour, false)
	sub, cancel := e.bus.Subscribe()
	defer cancel()

	e.follow(e.admin, tide)
	select {
	case ev := <-sub:
		c, ok := ev.Data.(FollowsChanged)
		if ev.Type != FollowsChangedEvent || !ok || c != (FollowsChanged{TMDBID: 7001, Following: true}) ||
			ev.To == nil || ev.To.UserID != 1 || !ev.To.Private || ev.For(e.memberID, false, true) || ev.For(e.memberID, true, true) {
			t.Errorf("event = %+v", ev)
		}
	default:
		t.Error("no follows.changed")
	}
	var show ShowDetail
	e.admin(http.MethodGet, fmt.Sprintf("/api/shows/%d", tide), "", &show)
	if !show.Following {
		t.Error("the admin's show page says not following")
	}
	e.asMember(http.MethodGet, fmt.Sprintf("/api/shows/%d", tide), "", &show)
	if show.Following {
		t.Error("the member's show page says following")
	}
	// Again: nothing changes, and nobody is told.
	e.follow(e.admin, tide)
	if len(sub) != 0 {
		t.Errorf("following again sent %d events", len(sub))
	}
	// The same show in a second library is the same follow.
	other := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'TV 4K', '/t4k')`)
	tide4K, _ := e.show(other, 7001, "Tidewater", 1)
	e.follow(e.admin, tide4K)
	e.exec(`UPDATE show_follows SET created_at = '2026-01-01T00:00:00.000Z'`) // the first follow is older
	e.follow(e.admin, point)
	<-sub

	var list FollowList
	e.admin(http.MethodGet, "/api/me/follows", "", &list)
	if len(list.Follows) != 2 || list.Follows[0].TMDBID != 7002 || list.Follows[1].TMDBID != 7001 {
		t.Fatalf("follows = %+v", list.Follows)
	}
	if f := list.Follows[1]; f.Title != "Tidewater" || f.Show == nil || f.Show.ID != tide || f.Show.PosterPath != "/p.jpg" ||
		f.Latest == nil || f.Latest.Season != 1 || f.Latest.Number != 2 || f.Latest.Title != "Chapter 2" || f.FollowedAt.IsZero() {
		t.Errorf("Tidewater = %+v show %+v latest %+v", f, f.Show, f.Latest)
	}
	if f := list.Follows[0]; f.Latest != nil {
		t.Errorf("Lantern Point has had no arrival: %+v", f.Latest)
	}
	e.asMember(http.MethodGet, "/api/me/follows", "", &list)
	if len(list.Follows) != 0 {
		t.Errorf("the member sees the admin's follows: %+v", list.Follows)
	}

	// A show that left the library is still listed, by its title, and can
	// be removed.
	e.exec(`DELETE FROM shows WHERE id = ?`, point)
	e.admin(http.MethodGet, "/api/me/follows", "", &list)
	if len(list.Follows) != 2 || list.Follows[0].Title != "Lantern Point" || list.Follows[0].Show != nil {
		t.Fatalf("after the show left = %+v", list.Follows)
	}
	if res := e.admin(http.MethodDelete, "/api/me/follows/7002", "", nil); res.StatusCode != http.StatusNoContent {
		t.Fatalf("remove = %d %s", res.StatusCode, res.Body)
	}
	if ev := <-sub; ev.Data != (FollowsChanged{TMDBID: 7002, Following: false}) {
		t.Errorf("event = %+v", ev)
	}
	if res := e.admin(http.MethodDelete, "/api/me/follows/7002", "", nil); res.StatusCode != http.StatusNoContent || len(sub) != 0 {
		t.Errorf("removing again = %d, %d events", res.StatusCode, len(sub))
	}

	// Unfollow from either library's show, then follow again.
	if res := e.admin(http.MethodDelete, fmt.Sprintf("/api/shows/%d/follow", tide4K), "", nil); res.StatusCode != http.StatusNoContent {
		t.Fatalf("unfollow = %d", res.StatusCode)
	}
	<-sub
	e.admin(http.MethodGet, fmt.Sprintf("/api/shows/%d", tide), "", &show)
	if show.Following {
		t.Error("still following after unfollowing")
	}
	e.follow(e.admin, tide)
	e.admin(http.MethodGet, "/api/me/follows", "", &list)
	if len(list.Follows) != 1 || list.Follows[0].TMDBID != 7001 {
		t.Errorf("follows again = %+v", list.Follows)
	}

	for _, method := range []string{http.MethodPut, http.MethodDelete} {
		if res := e.admin(method, "/api/shows/99999/follow", "", nil); res.StatusCode != http.StatusNotFound {
			t.Errorf("%s an unknown show = %d", method, res.StatusCode)
		}
	}

	// At most 500 a person.
	for i := range maxFollows {
		e.exec(`INSERT INTO show_follows (user_id, tmdb_id, title) VALUES (?, ?, 'Someone else')`, e.memberID, 100000+i)
	}
	if res := e.asMember(http.MethodPut, fmt.Sprintf("/api/shows/%d/follow", point), "", nil); res.StatusCode != http.StatusNotFound {
		t.Errorf("a deleted show = %d", res.StatusCode)
	}
	if res := e.asMember(http.MethodPut, fmt.Sprintf("/api/shows/%d/follow", tide), "", nil); res.StatusCode != http.StatusConflict {
		t.Errorf("the 501st follow = %d %s", res.StatusCode, res.Body)
	}
	if res := e.asMember(http.MethodDelete, "/api/me/follows/100000", "", nil); res.StatusCode != http.StatusNoContent {
		t.Fatal(res.StatusCode)
	}
	e.follow(e.asMember, tide)
}

// The feed a device checks (PLAN-NEW-EPISODES.md section 5.4): a first call
// gives only the cursor; then what arrived after both the cursor and the
// follow, announced, with a file, unfinished and within the week, grouped
// by show, earliest first with specials last; the wait, which the cursor
// doesn't pass; and one person never sees another's.
func TestNewEpisodesFeed(t *testing.T) {
	t.Parallel()
	e := newFollowEnv(t)
	tide, tideEps := e.show(e.tv, 7001, "Tidewater", 8)
	point, pointEps := e.show(e.tv, 7002, "Lantern Point", 5)
	_, keelEps := e.show(e.tv, 7003, "Keel", 1)
	e.arrive(tideEps[0], 10*time.Minute, false) // before the follow
	e.follow(e.admin, tide)
	e.follow(e.admin, point)

	start := e.feed(e.admin, "")
	if len(start.Shows) != 0 || cursorOf(t, start) == 0 {
		t.Fatalf("a first call = %+v", start)
	}
	if got := e.feed(e.admin, start.Cursor); len(got.Shows) != 0 || got.Cursor != start.Cursor {
		t.Fatalf("nothing new = %+v", got)
	}

	special := e.special(e.tv, tide)
	e.arrive(tideEps[2], 9*time.Minute, false)
	e.arrive(special, 9*time.Minute, false)
	e.arrive(tideEps[1], 9*time.Minute, false)
	e.arrive(pointEps[0], 9*time.Minute, true)  // quiet
	e.arrive(pointEps[1], 9*time.Minute, false) // finished
	e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed) VALUES (1, 'episode', ?, 1)`, pointEps[1])
	e.arrive(pointEps[2], 9*time.Minute, false) // its file has gone
	e.exec(`UPDATE media_files SET status = 'missing' WHERE id IN (SELECT media_file_id FROM episode_files WHERE episode_id = ?)`, pointEps[2])
	e.arrive(pointEps[3], 8*24*time.Hour, false) // over a week ago
	e.arrive(keelEps[0], 9*time.Minute, false)   // not followed
	last := e.arrive(pointEps[4], 8*time.Minute, false)

	got := e.feed(e.admin, start.Cursor)
	if want := []string{"Lantern Point: S1E5 (1) open S1E5", "Tidewater: S1E2 S1E3 S0E1 (3) open S1E2"}; !slices.Equal(groups(got), want) {
		t.Fatalf("feed = %q, want %q", groups(got), want)
	}
	if cursorOf(t, got) != last {
		t.Errorf("cursor %s, want %d", got.Cursor, last)
	}
	if g := got.Shows[1]; g.Show.ID != tide || g.Show.TMDBID != 7001 || g.Show.PosterPath != "/p.jpg" || g.Open.EpisodeID != tideEps[1] ||
		g.Episodes[0].Title != "Chapter 2" || g.Episodes[0].StillPath != "/still.jpg" || g.Episodes[0].ArrivedAt.IsZero() {
		t.Errorf("Tidewater's group = %+v", g)
	}
	// The member follows nothing yet; then only what arrives after.
	if m := e.feed(e.asMember, start.Cursor); len(m.Shows) != 0 {
		t.Errorf("the member sees %q", groups(m))
	}
	e.follow(e.asMember, point)
	e.arrive(tideEps[3], 7*time.Minute, false)
	if m := e.feed(e.asMember, start.Cursor); len(m.Shows) != 0 {
		t.Errorf("the member sees %q", groups(m))
	}

	// An arrival waits, and nothing after it is listed: the cursor stops
	// short of it.
	waiting := e.arrive(tideEps[4], 30*time.Second, false)
	e.arrive(tideEps[5], 6*time.Minute, false)
	held := e.feed(e.admin, got.Cursor)
	if want := []string{"Tidewater: S1E4 (1) open S1E4"}; !slices.Equal(groups(held), want) || cursorOf(t, held) != waiting-1 {
		t.Errorf("with one waiting = %q cursor %s, want %q cursor %d", groups(held), held.Cursor, want, waiting-1)
	}
	if again := e.feed(e.admin, held.Cursor); len(again.Shows) != 0 || again.Cursor != held.Cursor {
		t.Errorf("still waiting = %+v", again)
	}
	// A device starting now starts short of it too.
	if fresh := e.feed(e.admin, ""); cursorOf(t, fresh) != waiting-1 {
		t.Errorf("a new device's cursor = %s, want %d", fresh.Cursor, waiting-1)
	}
	e.exec(`UPDATE episode_arrivals SET arrived_at = ? WHERE id = ?`, db.FormatTime(time.Now().Add(-3*time.Minute)), waiting)
	if done := e.feed(e.admin, held.Cursor); !slices.Equal(groups(done), []string{"Tidewater: S1E5 S1E6 (2) open S1E5"}) {
		t.Errorf("after the wait = %q", groups(done))
	}

	// A cursor that isn't one is refused; one past the newest arrival (a
	// restored backup) starts again from now.
	if res := e.admin(http.MethodGet, "/api/me/new-episodes?after=soon", "", nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("after=soon = %d", res.StatusCode)
	}
	if f := e.feed(e.admin, "99999999"); len(f.Shows) != 0 || cursorOf(t, f) >= 99999999 {
		t.Errorf("a cursor from the future = %+v", f)
	}
}

// One reply has at most 50 shows and 200 arrivals; the cursor stops short
// of the rest, which the next check gets.
func TestNewEpisodesFeedLimits(t *testing.T) {
	t.Parallel()
	e := newFollowEnv(t)
	big, bigEps := e.show(e.tv, 8000, "Long Haul", feedMaxArrivals+5)
	e.follow(e.admin, big)
	start := e.feed(e.admin, "").Cursor
	for _, ep := range bigEps {
		e.arrive(ep, time.Hour, false)
	}
	first := e.feed(e.admin, start)
	if len(first.Shows) != 1 || first.Shows[0].Count != feedMaxArrivals || len(first.Shows[0].Episodes) != feedListed || first.Shows[0].Open.Number != 1 {
		t.Fatalf("first = %q", groups(first))
	}
	if next := e.feed(e.admin, first.Cursor); !slices.Equal(groups(next), []string{"Long Haul: S1E201 S1E202 S1E203 S1E204 S1E205 (5) open S1E201"}) {
		t.Errorf("next = %q", groups(next))
	}
	cursor := e.feed(e.admin, first.Cursor).Cursor

	// 55 more shows.
	var shows []int64
	for i := range feedMaxShows + 5 {
		id, eps := e.show(e.tv, int64(8001+i), fmt.Sprintf("Show %02d", i), 1)
		e.follow(e.admin, id)
		shows = append(shows, eps[0])
	}
	for _, ep := range shows {
		e.arrive(ep, time.Hour, false)
	}
	page := e.feed(e.admin, cursor)
	if len(page.Shows) != feedMaxShows {
		t.Fatalf("%d shows in a reply", len(page.Shows))
	}
	// Newest arrival first.
	if page.Shows[0].Show.Title != "Show 49" || page.Shows[len(page.Shows)-1].Show.Title != "Show 00" {
		t.Errorf("order = %q ... %q", page.Shows[0].Show.Title, page.Shows[len(page.Shows)-1].Show.Title)
	}
	rest := e.feed(e.admin, page.Cursor)
	if want := []string{"Show 54: S1E1 (1) open S1E1", "Show 53: S1E1 (1) open S1E1", "Show 52: S1E1 (1) open S1E1", "Show 51: S1E1 (1) open S1E1", "Show 50: S1E1 (1) open S1E1"}; !slices.Equal(groups(rest), want) {
		t.Errorf("the rest = %q", groups(rest))
	}
}

// An arrival belongs to the TMDB episode, so it reaches a person whichever
// library has the episode now: the feed names an episode row with a
// present file in any library, its own first, leaves out one finished in
// any library, and skips one no library has any more. The list keeps the
// latest arrival of a show whose library went.
func TestNewEpisodesAcrossLibraries(t *testing.T) {
	t.Parallel()
	e := newFollowEnv(t)
	tide, tideEps := e.show(e.tv, 7001, "Tidewater", 2)
	e.follow(e.admin, tide)
	start := e.feed(e.admin, "").Cursor
	e.arrive(tideEps[0], time.Hour, false)
	e.arrive(tideEps[1], time.Hour, false)
	uhd := e.exec(`INSERT INTO libraries (type, name, path) VALUES ('tv', 'TV 4K', '/t4k')`)
	tide4K, tide4KEps := e.show(uhd, 7001, "Tidewater", 1)

	got := e.feed(e.admin, start)
	if g := groups(got); len(g) != 1 || g[0] != "Tidewater: S1E1 S1E2 (2) open S1E1" || got.Shows[0].Open.EpisodeID != tideEps[0] || got.Shows[0].Show.ID != tide {
		t.Fatalf("both libraries = %q %+v", g, got.Shows)
	}
	// E01's file in the first library goes: the 4K copy opens.
	e.exec(`UPDATE media_files SET status = 'missing' WHERE id IN (SELECT media_file_id FROM episode_files WHERE episode_id = ?)`, tideEps[0])
	got = e.feed(e.admin, start)
	if len(got.Shows) != 1 || got.Shows[0].Open.EpisodeID != tide4KEps[0] || got.Shows[0].Show.ID != tide4K || got.Shows[0].Count != 2 {
		t.Fatalf("opened from the 4K library = %q %+v", groups(got), got.Shows)
	}
	// Finished in one library is finished.
	e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed) VALUES (1, 'episode', ?, 1)`, tideEps[0])
	if g := groups(e.feed(e.admin, start)); len(g) != 1 || g[0] != "Tidewater: S1E2 (1) open S1E2" {
		t.Errorf("finished in the other library = %q", g)
	}
	e.exec(`DELETE FROM watch_state WHERE item_id = ?`, tideEps[0])
	// The first library goes: E02 is nowhere now; E01 is in 4K.
	e.exec(`DELETE FROM libraries WHERE id = ?`, e.tv)
	if g := groups(e.feed(e.admin, start)); len(g) != 1 || g[0] != "Tidewater: S1E1 (1) open S1E1" {
		t.Errorf("after the library went = %q", g)
	}
	var home HomeData
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	if at := slices.Index(rowIDs(home.Rows), rowFollowed); at < 0 || len(home.Rows[at].Episodes) != 1 || home.Rows[at].Episodes[0].EpisodeID != tide4KEps[0] {
		t.Errorf("home's row = %v", rowIDs(home.Rows))
	}
	var list FollowList
	e.admin(http.MethodGet, "/api/me/follows", "", &list)
	if len(list.Follows) != 1 || list.Follows[0].Show == nil || list.Follows[0].Show.ID != tide4K ||
		list.Follows[0].Latest == nil || list.Follows[0].Latest.Number != 2 || list.Follows[0].Latest.Title != "" {
		t.Errorf("follows = %+v", list.Follows)
	}
}

// Home's row "New from shows you follow" (PLAN-NEW-EPISODES.md section
// 5.5): left out while empty; after Continue watching by default, also in
// a layout saved before it existed; newest first, at most 20, within the
// week and unfinished.
func TestFollowedHomeRow(t *testing.T) {
	t.Parallel()
	e := newFollowEnv(t)
	tide, tideEps := e.show(e.tv, 7001, "Tidewater", followedRowLength+3)
	var home HomeData
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	if slices.Contains(rowIDs(home.Rows), rowFollowed) {
		t.Fatalf("an empty row is drawn: %v", rowIDs(home.Rows))
	}
	var layout RowLayout
	e.admin(http.MethodGet, "/api/me/rows/home", "", &layout)
	if ids := layoutIDs(layout); len(ids) < 2 || ids[0] != rowContinue || ids[1] != rowFollowed {
		t.Fatalf("layout = %v", ids)
	}

	e.follow(e.admin, tide)
	for i, ep := range tideEps {
		e.arrive(ep, time.Duration(len(tideEps)-i)*time.Minute, false)
	}
	e.exec(`UPDATE episode_arrivals SET arrived_at = ? WHERE episode_id = ?`, db.FormatTime(time.Now().Add(-8*24*time.Hour)), tideEps[len(tideEps)-1])
	e.exec(`INSERT INTO watch_state (user_id, item_type, item_id, completed) VALUES (1, 'episode', ?, 1)`, tideEps[len(tideEps)-2])
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	ids := rowIDs(home.Rows)
	at := slices.Index(ids, rowFollowed)
	if at != 1 || ids[0] != rowContinue {
		t.Fatalf("home rows = %v", ids)
	}
	row := home.Rows[at]
	if row.Title != "New from shows you follow" || row.Style != RowStyleEpisode || len(row.Episodes) != followedRowLength || row.SeeAll != nil {
		t.Fatalf("row = %s %s %d episodes, see all %+v", row.Title, row.Style, len(row.Episodes), row.SeeAll)
	}
	// Newest first, leaving out the finished and the one over a week ago.
	if c := row.Episodes[0]; c.EpisodeNumber != int64(len(tideEps)-2) || c.ShowID != tide || c.ShowTitle != "Tidewater" || !c.HasFile ||
		c.FileID == nil || !c.IsNew || c.Watched || c.AddedAt == nil || c.ShowPosterPath != "/p.jpg" {
		t.Errorf("first card = %+v", c)
	}
	if n := row.Episodes[followedRowLength-1].EpisodeNumber; n != 2 {
		t.Errorf("last card is E%d", n)
	}
	// The member follows nothing: no row.
	e.asMember(http.MethodGet, "/api/home?arranged=true", "", &home)
	if slices.Contains(rowIDs(home.Rows), rowFollowed) {
		t.Errorf("the member's Home has the admin's row")
	}

	// A layout saved before the row existed shows it in its default place,
	// after Continue watching; hidden, it isn't drawn.
	e.admin(http.MethodPut, "/api/me/rows/home", `{"rows":[{"id":"recentlyAired"},{"id":"continue"}]}`, nil)
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	if ids := rowIDs(home.Rows); slices.Index(ids, rowFollowed) != slices.Index(ids, rowContinue)+1 {
		t.Errorf("saved before = %v", ids)
	}
	e.admin(http.MethodPut, "/api/me/rows/home", `{"rows":[{"id":"followed","hidden":true}]}`, nil)
	e.admin(http.MethodGet, "/api/home?arranged=true", "", &home)
	if slices.Contains(rowIDs(home.Rows), rowFollowed) {
		t.Errorf("a hidden row is drawn: %v", rowIDs(home.Rows))
	}
}
