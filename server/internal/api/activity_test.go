package api

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
)

func TestActivityEndToEnd(t *testing.T) {
	t.Parallel()
	var tracker *activity.Tracker
	srv := newTestServerWith(t, func(d *Deps) {
		tracker = activity.New(d.Conn, d.Bus, nil, activity.Options{Config: activity.Config{Group: true}})
		d.Activity = tracker
	})
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "Clip (2020).mp4")
	if err := os.WriteFile(path, []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"mov","durationMs":600000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2,"language":"eng","default":true}],"subtitles":[]}`
	dur := int64(600_000)
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 16, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, DurationMs: &dur, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	base := srv.URL + "/api"
	admin := srv.client

	// Plays shorter than two minutes would not be kept; keep everything here.
	var s Settings
	if code := doJSON(t, admin, http.MethodPut, base+"/settings", `{"activity":{"minPlaySeconds":0,"groupPlays":true,"retentionDays":0,"trimStats":false,"household":false}}`, &s).StatusCode; code != 200 || s.Activity.MinPlaySeconds != 0 {
		t.Fatalf("settings -> %d %+v", code, s.Activity)
	}
	if tracker.Config().MinPlay != 0 {
		t.Fatalf("tracker config not updated: %+v", tracker.Config())
	}

	// Start a play, naming the device.
	var d playback.Decision
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"original","client":{"deviceId":"dev-1","name":"Test laptop","product":"WatchMyMedia Web"}}`
	if code := doJSON(t, admin, http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, f.ID), body, &d).StatusCode; code != 200 || d.Mode != playback.ModeDirect || !strings.Contains(d.URL, "?play="+d.PlayID) {
		t.Fatalf("playback -> %d %+v", code, d)
	}
	var live LiveActivity
	doJSON(t, admin, http.MethodGet, base+"/activity", "", &live)
	if len(live.Plays) != 1 || live.Plays[0].ID != d.PlayID || live.Plays[0].Device.Name != "Test laptop" || live.Plays[0].Item.Type != "file" ||
		live.Plays[0].Item.Title != "Clip (2020)" || live.Plays[0].Stream.Quality != "original" || live.Plays[0].Network != "local" || live.Summary.Direct != 1 {
		t.Fatalf("live = %+v", live)
	}

	// Heartbeats work for unmatched files when they carry a play.
	var p Progress
	if code := doJSON(t, admin, http.MethodPut, fmt.Sprintf("%s/files/%d/progress", base, f.ID), `{"positionMs":5000,"playId":"`+d.PlayID+`","state":"playing"}`, &p).StatusCode; code != 200 || p.Directive != nil {
		t.Fatalf("heartbeat -> %d %+v", code, p)
	}
	// Bytes are counted on the stream.
	if code := doJSON(t, admin, http.MethodGet, srv.URL+d.URL, "", nil).StatusCode; code != 200 {
		t.Fatalf("stream -> %d", code)
	}
	doJSON(t, admin, http.MethodGet, base+"/activity", "", &live)
	if live.Plays[0].BytesSent != 16 || live.Plays[0].State != "playing" {
		t.Errorf("after stream: %+v", live.Plays[0])
	}

	// The menu bar app sees the play too.
	var local LocalStatus
	doJSON(t, admin, http.MethodGet, base+"/playback/local", "", &local)
	if len(local.Plays) != 1 || local.Plays[0].User != "admin" || local.Plays[0].Title != "Clip (2020)" {
		t.Errorf("local plays = %+v", local.Plays)
	}

	// Another user sees none of it and cannot stop it.
	doJSON(t, admin, http.MethodPost, base+"/users", `{"name":"kid","password":"kidpass1"}`, nil)
	jar, _ := cookiejar.New(nil)
	kid := &http.Client{Jar: jar}
	if code := doJSON(t, kid, http.MethodPost, base+"/auth/login", `{"name":"kid","password":"kidpass1"}`, nil).StatusCode; code != 200 {
		t.Fatalf("kid login -> %d", code)
	}
	var kidLive LiveActivity
	doJSON(t, kid, http.MethodGet, base+"/activity", "", &kidLive)
	if len(kidLive.Plays) != 0 {
		t.Errorf("kid sees %d plays", len(kidLive.Plays))
	}
	if code := doJSON(t, kid, http.MethodPost, base+"/activity/"+d.PlayID+"/stop", `{}`, nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("kid stop -> %d", code)
	}

	// The admin stops it with a message.
	if code := doJSON(t, admin, http.MethodPost, base+"/activity/"+d.PlayID+"/stop", `{"message":"Dinner time"}`, nil).StatusCode; code != http.StatusNoContent {
		t.Fatalf("stop -> %d", code)
	}
	doJSON(t, admin, http.MethodPut, fmt.Sprintf("%s/files/%d/progress", base, f.ID), `{"positionMs":6000,"playId":"`+d.PlayID+`"}`, &p)
	if p.Directive == nil || p.Directive.Action != "stop" || p.Directive.Message != "Dinner time" {
		t.Errorf("directive = %+v", p.Directive)
	}
	if code := doJSON(t, admin, http.MethodGet, srv.URL+d.URL, "", nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("stream after stop -> %d", code)
	}
	again := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"playId":"` + d.PlayID + `"}`
	if code := doJSON(t, admin, http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, f.ID), again, nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("restart after stop -> %d", code)
	}

	// History has it, for the admin only.
	var page activity.Page
	doJSON(t, admin, http.MethodGet, base+"/history?q=clip", "", &page)
	if page.Total != 1 || len(page.Items) != 1 || page.Items[0].EndReason != "terminated" || page.Items[0].Device == nil || page.Items[0].User.Name != "admin" {
		t.Fatalf("history = %+v", page)
	}
	var kidPage activity.Page
	doJSON(t, kid, http.MethodGet, base+"/history?user=1", "", &kidPage)
	if len(kidPage.Items) != 0 {
		t.Errorf("kid reads admin's history: %+v", kidPage.Items)
	}
	if code := doJSON(t, kid, http.MethodGet, fmt.Sprintf("%s/history/%d", base, page.Items[0].GroupID), "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("kid group -> %d", code)
	}
	var group GroupOutput
	doJSON(t, admin, http.MethodGet, fmt.Sprintf("%s/history/%d", base, page.Items[0].GroupID), "", &group.Body)
	if len(group.Body.Items) != 1 || group.Body.Items[0].Detail == nil || group.Body.Items[0].Detail.Stream.Source.VideoCodec != "h264" || group.Body.Items[0].Detail.BytesSent != 16 {
		t.Errorf("group = %+v", group.Body.Items)
	}

	// CSV export.
	res, err := admin.Get(base + "/history.csv")
	if err != nil {
		t.Fatal(err)
	}
	csvBody, _ := io.ReadAll(res.Body)
	_ = res.Body.Close()
	if !strings.HasPrefix(res.Header.Get("Content-Type"), "text/csv") || strings.Count(string(csvBody), "\n") != 2 || !strings.Contains(string(csvBody), "Clip (2020)") {
		t.Errorf("csv -> %s %q", res.Header.Get("Content-Type"), csvBody)
	}

	// Statistics: the kid's are their own, whatever they ask for.
	var cards activity.Cards
	doJSON(t, admin, http.MethodGet, base+"/stats/cards?days=7", "", &cards)
	if cards.Plays != 1 || len(cards.TopUsers) != 1 {
		t.Errorf("cards = %+v", cards)
	}
	doJSON(t, kid, http.MethodGet, base+"/stats/cards?days=7&user=1", "", &cards)
	if cards.Plays != 0 {
		t.Errorf("kid cards = %+v", cards)
	}
	var series activity.Series
	if code := doJSON(t, admin, http.MethodGet, base+"/stats/series?days=7&split=mode", "", &series).StatusCode; code != 200 || len(series.Buckets) != 7 || len(series.Lines) != 1 {
		t.Errorf("series -> %d %+v", code, series)
	}
	var heat activity.Heatmap
	if code := doJSON(t, admin, http.MethodGet, base+"/stats/heatmap", "", &heat).StatusCode; code != 200 || len(heat.Values) != 7 {
		t.Errorf("heatmap -> %d", code)
	}
	var users ActivityUsersOutput
	doJSON(t, admin, http.MethodGet, base+"/activity/users", "", &users.Body)
	if len(users.Body.Items) != 2 {
		t.Errorf("activity users = %+v", users.Body.Items)
	}
	if code := doJSON(t, kid, http.MethodGet, base+"/activity/users", "", nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("kid activity users -> %d", code)
	}

	// With the kid's history off, their plays show live but are not kept.
	var list UserList
	doJSON(t, admin, http.MethodGet, base+"/users", "", &list)
	var kidID int64
	for _, u := range list.Items {
		if u.Name == "kid" {
			kidID = u.ID
			if !u.RecordHistory {
				t.Error("history off by default")
			}
		}
	}
	var acct Account
	if code := doJSON(t, admin, http.MethodPatch, fmt.Sprintf("%s/users/%d", base, kidID), `{"recordHistory":false}`, &acct).StatusCode; code != 200 || acct.RecordHistory {
		t.Fatalf("patch user -> %d %+v", code, acct)
	}
	var kd playback.Decision
	doJSON(t, kid, http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, f.ID), body, &kd)
	doJSON(t, admin, http.MethodGet, base+"/activity", "", &live)
	if len(live.Plays) != 1 || live.Plays[0].User.Name != "kid" {
		t.Errorf("kid's live play = %+v", live.Plays)
	}
	doJSON(t, kid, http.MethodPut, fmt.Sprintf("%s/files/%d/progress", base, f.ID), `{"positionMs":1000,"playId":"`+kd.PlayID+`","state":"stopped"}`, nil)
	doJSON(t, admin, http.MethodGet, base+"/history", "", &page)
	if page.Total != 1 {
		t.Errorf("history after an unrecorded play = %d rows", page.Total)
	}

	// Deleting the row empties history and the totals.
	var del DeleteHistoryOutput
	if code := doJSON(t, kid, http.MethodDelete, base+"/history", fmt.Sprintf(`{"groups":[%d]}`, page.Items[0].GroupID), nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("kid delete -> %d", code)
	}
	if code := doJSON(t, admin, http.MethodDelete, base+"/history", fmt.Sprintf(`{"groups":[%d]}`, page.Items[0].GroupID), &del.Body).StatusCode; code != 200 || del.Body.Deleted != 1 {
		t.Errorf("delete -> %d %+v", code, del.Body)
	}
	doJSON(t, admin, http.MethodGet, base+"/stats/cards?days=7", "", &cards)
	if cards.Plays != 0 {
		t.Errorf("cards after delete = %+v", cards)
	}
}

func TestHeartbeatAfterRestart(t *testing.T) {
	t.Parallel()
	var tracker *activity.Tracker
	srv := newTestServerWith(t, func(d *Deps) {
		tracker = activity.New(d.Conn, d.Bus, nil, activity.Options{Config: activity.Config{Group: true}})
		d.Activity = tracker
	})
	base := srv.URL + "/api"
	// A player reports for a play this server never started (it restarted):
	// a new play carries on and the player is told its ID.
	var p Progress
	if code := doJSON(t, srv.client, http.MethodPut, base+"/files/1/progress", `{"positionMs":60000,"playId":"0123456789abcdef","state":"playing"}`, &p).StatusCode; code != 200 {
		t.Fatalf("heartbeat -> %d", code)
	}
	if len(p.PlayID) != 16 || p.PlayID == "0123456789abcdef" {
		t.Fatalf("new play id = %q", p.PlayID)
	}
	var live LiveActivity
	doJSON(t, srv.client, http.MethodGet, base+"/activity", "", &live)
	if len(live.Plays) != 1 || live.Plays[0].ID != p.PlayID || live.Plays[0].PositionMs < 60000 || live.Plays[0].Stream.Mode != "direct" {
		t.Errorf("carried-on play = %+v", live.Plays)
	}
	// A stop for an unknown play does nothing.
	doJSON(t, srv.client, http.MethodPut, base+"/files/1/progress", `{"positionMs":1,"playId":"fedcba9876543210","state":"closed"}`, &p)
	if tracker.Playing() != 1 {
		t.Errorf("playing = %d", tracker.Playing())
	}
}

// Admins read everyone's activity from any network, as they do at home, and
// get its events there; stopping plays and deleting history still need an
// admin network, and other admin events stay on one.
func TestActivityForAdminsAway(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		admin, _ := netguard.Parse([]string{"local"})
		d.Net = netguard.New(trusted, admin)
		d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{Config: activity.Config{Group: true}})
	})
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "Clip (2020).mp4")
	if err := os.WriteFile(path, []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"mov","durationMs":600000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2,"language":"eng","default":true}],"subtitles":[]}`
	dur := int64(600_000)
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 16, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, DurationMs: &dur, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	base := srv.URL + "/api"
	admin := srv.client
	away := []string{"X-Forwarded-For", "203.0.113.9"}
	if code := doJSON(t, admin, http.MethodPut, base+"/settings", `{"activity":{"minPlaySeconds":0,"groupPlays":true,"retentionDays":0,"trimStats":false,"household":false}}`, nil).StatusCode; code != 200 {
		t.Fatalf("settings -> %d", code)
	}
	doJSON(t, admin, http.MethodPost, base+"/users", `{"name":"kid","password":"kidpass1"}`, nil)
	jar, _ := cookiejar.New(nil)
	kid := &http.Client{Jar: jar}
	if code := doJSON(t, kid, http.MethodPost, base+"/auth/login", `{"name":"kid","password":"kidpass1"}`, nil).StatusCode; code != 200 {
		t.Fatalf("kid login -> %d", code)
	}

	// The away admin's event stream gets the kid's play but not a problem
	// report, which is for admins on an admin network.
	sctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(sctx, http.MethodGet, base+"/events", nil)
	req.Header.Set(away[0], away[1])
	res, err := admin.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	lines := bufio.NewScanner(res.Body)
	var seen []string
	readUntil := func(event string) {
		t.Helper()
		for lines.Scan() {
			if name, ok := strings.CutPrefix(lines.Text(), "event: "); ok {
				seen = append(seen, name)
				if name == event {
					return
				}
			}
		}
		t.Fatalf("stream ended before %q (saw %v): %v", event, seen, lines.Err())
	}
	readUntil("ping")
	srv.bus.Publish(events.Event{Type: "problems.changed", Data: integrations.ProblemsChanged{ProblemID: 1}, To: &events.Audience{}})
	var d playback.Decision
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"original","client":{"deviceId":"dev-1","name":"Kid's laptop","product":"WatchMyMedia Web"}}`
	if code := doJSON(t, kid, http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, f.ID), body, &d).StatusCode; code != 200 {
		t.Fatalf("kid playback -> %d", code)
	}
	readUntil("activity.started")
	if slices.Contains(seen, "problems.changed") {
		t.Errorf("away admin got an admin-network event: %v", seen)
	}

	var live LiveActivity
	if r := doJSON(t, admin, http.MethodGet, base+"/activity", "", &live, away...); r.StatusCode != 200 || len(live.Plays) != 1 || live.Plays[0].User.Name != "kid" || live.Plays[0].IP == "" {
		t.Errorf("away admin's now playing -> %d %+v", r.StatusCode, live.Plays)
	}
	if r := doJSON(t, admin, http.MethodPost, base+"/activity/"+d.PlayID+"/stop", `{}`, nil, away...); r.StatusCode != http.StatusForbidden || !strings.Contains(string(r.Body), "home network") {
		t.Errorf("away admin stops a play -> %d %s", r.StatusCode, r.Body)
	}
	var users ActivityUsersOutput
	if code := doJSON(t, admin, http.MethodGet, base+"/activity/users", "", &users.Body, away...).StatusCode; code != 200 || len(users.Body.Items) != 2 {
		t.Errorf("away admin's activity users -> %d %+v", code, users.Body.Items)
	}
	if code := doJSON(t, kid, http.MethodGet, base+"/activity/users", "", nil, away...).StatusCode; code != http.StatusForbidden {
		t.Errorf("kid's activity users -> %d", code)
	}
	if code := doJSON(t, admin, http.MethodGet, base+"/offline/downloads?all=true", "", nil, away...).StatusCode; code != 200 {
		t.Errorf("away admin's downloads -> %d", code)
	}
	if code := doJSON(t, kid, http.MethodGet, base+"/offline/downloads?all=true", "", nil, away...).StatusCode; code != http.StatusForbidden {
		t.Errorf("kid's everyone's downloads -> %d", code)
	}

	doJSON(t, kid, http.MethodPut, fmt.Sprintf("%s/files/%d/progress", base, f.ID), `{"positionMs":1000,"playId":"`+d.PlayID+`","state":"stopped"}`, nil)
	var page activity.Page
	if code := doJSON(t, admin, http.MethodGet, base+"/history", "", &page, away...).StatusCode; code != 200 || page.Total != 1 || page.Items[0].User.Name != "kid" {
		t.Fatalf("away admin's history -> %d %+v", code, page)
	}
	var cards activity.Cards
	if doJSON(t, admin, http.MethodGet, base+"/stats/cards?days=7", "", &cards, away...); cards.Plays != 1 || len(cards.TopUsers) != 1 {
		t.Errorf("away admin's cards = %+v", cards)
	}
	if r := doJSON(t, admin, http.MethodGet, base+"/history.csv", "", nil, away...); r.StatusCode != 200 || !strings.Contains(string(r.Body), "kid") {
		t.Errorf("away admin's export -> %d %q", r.StatusCode, r.Body)
	}
	if r := doJSON(t, admin, http.MethodDelete, base+"/history", fmt.Sprintf(`{"groups":[%d]}`, page.Items[0].GroupID), nil, away...); r.StatusCode != http.StatusForbidden || !strings.Contains(string(r.Body), "home network") {
		t.Errorf("away admin deletes history -> %d %s", r.StatusCode, r.Body)
	}
	// Admin settings stay on the admin network.
	if code := doJSON(t, admin, http.MethodGet, base+"/settings", "", nil, away...).StatusCode; code != http.StatusForbidden {
		t.Errorf("away admin's settings -> %d", code)
	}
}
