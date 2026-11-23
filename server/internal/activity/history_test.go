package activity

import (
	"context"
	"errors"
	"maps"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

func titles(p Page) []string {
	var out []string
	for _, r := range p.Items {
		t := r.Item.Title
		if r.Item.ShowTitle != "" {
			t = r.Item.ShowTitle + " " + r.Item.Title
		}
		out = append(out, t)
	}
	return out
}

// seedSmall plays a few things: Ann watches Breaking Bad and Heat at home,
// Bob watches The Bear from outside.
func seedSmall(t *testing.T) (*Tracker, *clock) {
	tr, c, _ := newTracker(t, noMin())
	bb := func(e int64, title string) Item { return episode(100+e, 10, "Breaking Bad", title, 1, e) }
	play(t, tr, c, playOpts{user: 1, item: bb(1, "Pilot"), length: 3 * time.Minute, device: "ann-mac"})
	c.add(time.Hour)
	play(t, tr, c, playOpts{user: 1, item: bb(2, "Cat's in the Bag..."), length: 3 * time.Minute, device: "ann-mac"})
	c.add(time.Hour)
	play(t, tr, c, playOpts{user: 2, item: episode(200, 20, "The Bear", "System", 1, 1), length: 3 * time.Minute, ip: "203.0.113.7", device: "bob-phone", mode: "transcode"})
	c.add(time.Hour)
	play(t, tr, c, playOpts{user: 1, item: movie(1, "Heat"), length: 3 * time.Minute, device: "ann-tv", ip: "100.100.1.2"})
	c.add(time.Hour)
	return tr, c
}

func TestHistorySearch(t *testing.T) {
	tr, _ := seedSmall(t)
	ctx := context.Background()
	for _, tc := range []struct {
		q    string
		want []string
	}{
		{"", []string{"Heat", "The Bear System", "Breaking Bad Cat's in the Bag...", "Breaking Bad Pilot"}},
		{"break", []string{"Breaking Bad Cat's in the Bag...", "Breaking Bad Pilot"}},
		{"BREAKING bad", []string{"Breaking Bad Cat's in the Bag...", "Breaking Bad Pilot"}},
		{"breaking s01e02", []string{"Breaking Bad Cat's in the Bag..."}},
		{"s1", []string{"The Bear System", "Breaking Bad Cat's in the Bag...", "Breaking Bad Pilot"}},
		{"he", []string{"Heat", "The Bear System", "Breaking Bad Cat's in the Bag..."}},                           // two letters: LIKE on titles
		{"bob", []string{"The Bear System"}},                                                                      // a user
		{"safari", []string{"Heat", "The Bear System", "Breaking Bad Cat's in the Bag...", "Breaking Bad Pilot"}}, // a device
		{"203.0.", []string{"The Bear System"}},                                                                   // an address prefix
		{"100.100.1.2", []string{"Heat"}},
		{"nothing like this", nil},
	} {
		p, err := tr.History(ctx, Filter{Q: tc.q})
		if err != nil {
			t.Fatalf("%q: %v", tc.q, err)
		}
		if got := titles(p); !equal(got, tc.want) || p.Total != int64(len(tc.want)) {
			t.Errorf("%q = %v (total %d), want %v", tc.q, got, p.Total, tc.want)
		}
	}
	// Filters.
	for name, tc := range map[string]struct {
		f    Filter
		want int
	}{
		"user":      {Filter{UserID: 2}, 1},
		"type":      {Filter{Type: "movie"}, 1},
		"mode":      {Filter{Mode: "transcode"}, 1},
		"network":   {Filter{Network: "tailnet"}, 1},
		"remote":    {Filter{Network: "remote"}, 1},
		"show":      {Filter{ShowID: 10}, 2},
		"episode":   {Filter{EpisodeID: 102}, 1},
		"movie":     {Filter{MovieID: 1}, 1},
		"day":       {Filter{From: "2026-10-29", To: "2026-10-29"}, 4},
		"no days":   {Filter{From: "2026-10-30"}, 0},
		"completed": {Filter{Completed: new(true)}, 0},
	} {
		p, err := tr.History(ctx, tc.f)
		if err != nil || len(p.Items) != tc.want {
			t.Errorf("%s: %d rows (%v), want %d", name, len(p.Items), err, tc.want)
		}
	}
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestHistoryPaging(t *testing.T) {
	tr, _ := seedSmall(t)
	ctx := context.Background()
	var all []string
	cursor := ""
	for range 3 {
		p, err := tr.History(ctx, Filter{Limit: 3, Cursor: cursor})
		if err != nil {
			t.Fatal(err)
		}
		if cursor == "" && (p.Total != 4 || p.PlayedMs != 4*3*60_000) {
			t.Errorf("first page totals = %d, %d", p.Total, p.PlayedMs)
		}
		all = append(all, titles(p)...)
		cursor = p.NextCursor
		if cursor == "" {
			break
		}
	}
	if len(all) != 4 || all[0] != "Heat" || all[3] != "Breaking Bad Pilot" {
		t.Errorf("paged = %v", all)
	}
	if _, err := tr.History(ctx, Filter{Cursor: "!!"}); !errors.Is(err, ErrBadCursor) {
		t.Errorf("bad cursor: %v", err)
	}
}

func TestDeleteAndRegroup(t *testing.T) {
	tr, c, conn := newTracker(t, noMin())
	ctx := context.Background()
	heat := movie(1, "Heat")
	a := play(t, tr, c, playOpts{user: 1, item: heat, length: 10 * time.Minute})
	b := play(t, tr, c, playOpts{user: 1, item: heat, from: 10 * time.Minute, length: 10 * time.Minute})
	cc := play(t, tr, c, playOpts{user: 1, item: heat, from: 20 * time.Minute, length: 10 * time.Minute})
	ra, _ := readRow(t, conn, a)
	rb, _ := readRow(t, conn, b)
	if rc, _ := readRow(t, conn, cc); rc.groupCount != 3 {
		t.Fatalf("group = %+v", rc)
	}
	rows, err := tr.Group(ctx, ra.id, 0)
	if err != nil || len(rows) != 3 || rows[0].Detail == nil || rows[0].Detail.Stream.Mode != "direct" {
		t.Fatalf("group rows = %+v, %v", rows, err)
	}
	if mine, _ := tr.Group(ctx, ra.id, 2); len(mine) != 0 {
		t.Error("bob can read ann's group")
	}
	// Deleting the middle play leaves a group of two.
	if n, err := tr.Delete(ctx, []int64{rb.id}, nil); err != nil || n != 1 {
		t.Fatalf("delete = %d, %v", n, err)
	}
	if rc, _ := readRow(t, conn, cc); rc.groupCount != 2 || rc.groupPlayed != 20*60_000 || rc.groupID != ra.id {
		t.Errorf("after deleting the middle play: %+v", rc)
	}
	// Deleting the first play renames the group after the next.
	if _, err := tr.Delete(ctx, []int64{ra.id}, nil); err != nil {
		t.Fatal(err)
	}
	rc, _ := readRow(t, conn, cc)
	if rc.groupCount != 1 || rc.groupID != rc.id || rc.groupLatest != 1 {
		t.Errorf("after deleting the first play: %+v", rc)
	}
	if _, err := tr.Delete(ctx, nil, []int64{rc.groupID}); err != nil {
		t.Fatal(err)
	}
	var n int64
	_ = conn.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM plays`).Scan(&n)
	var daily int64
	_ = conn.QueryRowContext(t.Context(), `SELECT COUNT(*) FROM activity_daily`).Scan(&daily)
	if n != 0 || daily != 0 {
		t.Errorf("left %d plays and %d daily rows", n, daily)
	}
}

func TestTrim(t *testing.T) {
	tr, c, conn := newTracker(t, Config{Group: true, RetentionDays: 30})
	play(t, tr, c, playOpts{user: 1, item: movie(1, "Heat"), length: 10 * time.Minute})
	c.add(40 * 24 * time.Hour)
	recent := play(t, tr, c, playOpts{user: 1, item: movie(2, "Ronin"), length: 10 * time.Minute})
	n, err := tr.Trim(context.Background())
	if err != nil || n != 1 {
		t.Fatalf("trim = %d, %v", n, err)
	}
	if _, ok := readRow(t, conn, recent); !ok {
		t.Error("trimmed a recent play")
	}
	var days int64
	_ = conn.QueryRowContext(t.Context(), `SELECT COUNT(DISTINCT local_day) FROM activity_daily`).Scan(&days)
	if days != 2 {
		t.Errorf("daily totals for %d days, want both kept", days)
	}
}

// CountOlderThan counts what Trim would delete with that retention: the
// finished plays before the cutoff day, never a live one.
func TestCountOlderThan(t *testing.T) {
	tr, c, _ := newTracker(t, Config{Group: true, RetentionDays: 30})
	ctx := context.Background()
	play(t, tr, c, playOpts{user: 1, item: movie(1, "Heat"), length: 10 * time.Minute})
	play(t, tr, c, playOpts{user: 2, item: movie(3, "Thief"), length: 10 * time.Minute})
	live, err := tr.Begin(ctx, Start{UserID: 1, UserName: "ann", RecordHistory: true, Item: movie(4, "Collateral"), IP: mustAddr("192.168.1.20"), Stream: Stream{Mode: "direct"}})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := tr.Heartbeat(ctx, live, 1, 0, Report{State: StatePlaying}); err != nil {
		t.Fatal(err)
	}
	c.add(40 * 24 * time.Hour)
	play(t, tr, c, playOpts{user: 1, item: movie(2, "Ronin"), length: 10 * time.Minute})
	for _, tc := range []struct {
		days int
		want int64
	}{{0, 0}, {-1, 0}, {30, 2}, {39, 2}, {41, 0}, {1, 2}} {
		if n, err := tr.CountOlderThan(ctx, tc.days); err != nil || n != tc.want {
			t.Errorf("older than %d days = %d, %v; want %d", tc.days, n, err, tc.want)
		}
	}
	n, err := tr.Trim(ctx)
	if err != nil || n != 2 {
		t.Fatalf("trim = %d, %v; the count said 2", n, err)
	}
	if n, err := tr.CountOlderThan(ctx, 30); err != nil || n != 0 {
		t.Errorf("after trimming: %d, %v", n, err)
	}
}

func TestCountLibraryPlays(t *testing.T) {
	tr, c, _ := newTracker(t, noMin())
	inLibrary := func(id int64, title string, library int64) Item {
		m := movie(id, title)
		m.LibraryID = &library
		return m
	}
	play(t, tr, c, playOpts{user: 1, item: inLibrary(1, "Heat", 7), length: 3 * time.Minute})
	c.add(time.Hour)
	play(t, tr, c, playOpts{user: 2, item: inLibrary(1, "Heat", 7), length: 3 * time.Minute})
	c.add(time.Hour)
	play(t, tr, c, playOpts{user: 1, item: inLibrary(2, "Ronin", 8), length: 3 * time.Minute})
	for library, want := range map[int64]int64{7: 2, 8: 1, 9: 0} {
		if n, err := tr.CountLibraryPlays(context.Background(), library); err != nil || n != want {
			t.Errorf("library %d: %d, %v; want %d", library, n, err, want)
		}
	}
}

// The statistics count why plays were converted, and how many streams at
// the busiest moment were.
func TestStatsCountWhyPlaysWereConverted(t *testing.T) {
	tr, c, _ := newTracker(t, noMin())
	ctx := t.Context()
	begin := func(user, item int64, s Stream) string {
		t.Helper()
		id, err := tr.Begin(ctx, Start{UserID: user, UserName: "x", RecordHistory: true, Item: movie(item, "Thief"), IP: mustAddr("192.168.1.5"), Stream: s})
		if err != nil {
			t.Fatal(err)
		}
		_, _ = tr.Heartbeat(ctx, id, user, 0, Report{State: StatePlaying})
		return id
	}
	stop := func(user int64, id string) {
		t.Helper()
		if _, err := tr.Heartbeat(ctx, id, user, 0, Report{State: StateStopped}); err != nil {
			t.Fatal(err)
		}
	}
	direct := begin(1, 1, Stream{Mode: "direct", Reasons: []string{}})
	c.add(time.Minute)
	device := begin(1, 2, Stream{Mode: "transcode", Quality: "original", Reasons: []string{"client cannot decode vp9 video", "3840x2160 exceeds the client's 1920x1080", "audio converted to EAC3 5.1, which keeps it surround"}})
	c.add(time.Minute)
	away := begin(2, 3, Stream{Mode: "transcode", Quality: "720", Reasons: []string{"1920x1080 exceeds the client's 1280x720", "bitrate 8000 kb/s exceeds the client's 4000 kb/s"}})
	c.add(time.Minute)
	stop(1, direct)
	stop(1, device)
	stop(2, away)
	c.add(time.Minute)
	box := begin(2, 4, Stream{Mode: "remux", Quality: "original", Reasons: []string{"client cannot play the mkv container"}})
	c.add(time.Minute)
	stop(2, box)

	cards, err := tr.Cards(ctx, StatsQuery{Days: 30})
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]int64{}
	for _, r := range cards.Conversions.Reasons {
		got[r.Reason] = r.Plays
	}
	want := map[string]int64{"video-format": 1, "resolution": 1, "smaller": 1, "container": 1}
	if cards.Conversions.Plays != 3 || !maps.Equal(got, want) {
		t.Errorf("conversions = %d %v, want 3 %v", cards.Conversions.Plays, got, want)
	}
	if cards.Peak.All.Count != 3 || cards.Peak.All.Converted == nil || *cards.Peak.All.Converted != 2 || cards.Peak.Direct.Converted != nil {
		t.Errorf("peak = %+v, converted %v", cards.Peak.All, cards.Peak.All.Converted)
	}
}

// Automatic's step, kept with the play, says whether it was sent smaller.
func TestConversionKindsReadAutomaticsStep(t *testing.T) {
	t.Parallel()
	reasons := `["1920x1080 exceeds the client's 1280x720"]`
	if k := conversionKinds(reasons, "auto", `{"on":true,"step":"720"}`); !k["smaller"] || len(k) != 1 {
		t.Errorf("a step down: %v", k)
	}
	if k := conversionKinds(reasons, "auto", `{"on":true,"step":"original"}`); !k["resolution"] || len(k) != 1 {
		t.Errorf("at the original: %v", k)
	}
	if k := conversionKinds(`[]`, "", ""); !k["other"] {
		t.Errorf("no reasons kept: %v", k)
	}
}

func TestStats(t *testing.T) {
	tr, c := seedSmall(t)
	ctx := context.Background()
	// Two overlapping plays make a peak of two, one of them transcoded.
	go1 := func(user int64, ip, mode string) string {
		id, err := tr.Begin(ctx, Start{UserID: user, UserName: "x", RecordHistory: true, Item: movie(5, "Thief"), IP: mustAddr(ip), Stream: Stream{Mode: mode}})
		if err != nil {
			t.Fatal(err)
		}
		_, _ = tr.Heartbeat(ctx, id, user, 0, Report{State: StatePlaying})
		return id
	}
	x := go1(1, "192.168.1.5", "direct")
	c.add(time.Minute)
	y := go1(2, "192.168.1.6", "transcode")
	c.add(3 * time.Minute)
	_, _ = tr.Heartbeat(ctx, x, 1, 0, Report{State: StateStopped})
	_, _ = tr.Heartbeat(ctx, y, 2, 0, Report{State: StateStopped})

	cards, err := tr.Cards(ctx, StatsQuery{Days: 30})
	if err != nil {
		t.Fatal(err)
	}
	if cards.Plays != 6 || len(cards.TopShows) != 2 || cards.TopShows[0].Title != "Breaking Bad" || cards.TopShows[0].Plays != 2 {
		t.Errorf("shows = %+v (plays %d)", cards.TopShows, cards.Plays)
	}
	if len(cards.PopularMovies) == 0 || cards.PopularMovies[0].Title != "Thief" || cards.PopularMovies[0].Users != 2 {
		t.Errorf("popular movies = %+v", cards.PopularMovies)
	}
	if len(cards.TopUsers) != 2 || cards.TopUsers[0].Title != "ann" {
		t.Errorf("users = %+v", cards.TopUsers)
	}
	if cards.Peak.All.Count != 2 || cards.Peak.Transcode.Count != 1 || cards.Peak.Direct.Count != 1 {
		t.Errorf("peak = %+v", cards.Peak)
	}
	if len(cards.TopDevices) == 0 || cards.TopDevices[0].Subtitle == "" || len(cards.Recent) != 6 {
		t.Errorf("devices = %+v, recent %d", cards.TopDevices, len(cards.Recent))
	}
	mine, _ := tr.Cards(ctx, StatsQuery{Days: 30, UserID: 2})
	if mine.Plays != 2 {
		t.Errorf("bob's plays = %d", mine.Plays)
	}

	s, err := tr.Series(ctx, SeriesQuery{Days: 7, Split: "type"})
	if err != nil {
		t.Fatal(err)
	}
	if len(s.Buckets) != 7 || s.Buckets[6] != "2026-10-30" || len(s.Lines) != 2 || s.Lines[0].Key != "episode" || s.Lines[0].Values[5] != 3 {
		t.Errorf("series = %+v", s)
	}
	m, _ := tr.Series(ctx, SeriesQuery{Split: "user", Bucket: "month", ByTime: true})
	if len(m.Buckets) != 1 || len(m.Lines) != 2 || m.Lines[0].Label != "ann" {
		t.Errorf("monthly series = %+v", m)
	}

	h, err := tr.Heatmap(ctx, 30, false, 0)
	if err != nil {
		t.Fatal(err)
	}
	// 29 October 2026 is a Thursday; the plays started from 20:00 UTC on.
	if h.Values[4][20] != 1 || h.Values[4][21] != 1 || h.Max != 2 {
		t.Errorf("heatmap Thursday = %v", h.Values[4])
	}

	users, err := tr.Users(ctx)
	if err != nil || len(users) != 2 || users[0].User.Name != "ann" || users[0].Plays != 4 || users[0].LastPlayed == nil || users[0].Devices != 2 {
		t.Fatalf("users = %+v, %v", users, err)
	}
	d, err := tr.User(ctx, 2)
	if err != nil || len(d.Windows) != 4 || d.Windows[0].Plays != 2 || len(d.DeviceSet) != 1 || d.DeviceSet[0].LastIP == "" {
		t.Errorf("bob = %+v, %v", d, err)
	}
	// The first play and the network each address is on.
	var first string
	if err := tr.db.QueryRowContext(ctx, `SELECT MIN(started_at) FROM plays WHERE user_id = 2`).Scan(&first); err != nil {
		t.Fatal(err)
	}
	if d.FirstPlayAt == nil || !d.FirstPlayAt.Equal(db.ParseTime(first)) || d.DeviceSet[0].Network != "remote" { // bob's phone was last seen at 203.0.113.7
		t.Errorf("bob's first play %v (want %s), network %q", d.FirstPlayAt, first, d.DeviceSet[0].Network)
	}
	if none, err := tr.User(ctx, 3); err == nil && none.FirstPlayAt != nil {
		t.Errorf("someone with no plays: %v", none.FirstPlayAt)
	}
}
