package activity

import (
	"context"
	"errors"
	"testing"
	"time"
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
}
