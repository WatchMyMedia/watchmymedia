package api

import (
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/livetv"
)

// TestLiveTVFavouritesSearchReminders covers G6's routes
// (PLAN-LIVE-TV-GUIDE.md §5.7): each person's own favourites and
// reminders, search on the channels they may watch, the lead time, and who
// may call them.
func TestLiveTVFavouritesSearchReminders(t *testing.T) {
	t.Parallel()
	e := newGuideEnv(t)
	admin := e.client
	if r := e.call(t, admin, "POST", "/api/settings/live-tv/sources", `{"address":"tuner.test"}`, nil); r.StatusCode >= 300 {
		t.Fatalf("add tuner: %d %s", r.StatusCode, r.Body)
	}
	e.call(t, admin, "PUT", "/api/settings/live-tv/guide", `{"enabled":true,"tmdb":false}`, nil)
	e.guide.Fetch(t.Context())
	if code := e.call(t, admin, "POST", "/api/users", `{"name":"viewer","password":"password1"}`, nil).StatusCode; code != 201 {
		t.Fatal(code)
	}
	jar, _ := cookiejar.New(nil)
	member := &http.Client{Jar: jar}
	if code := doJSON(t, member, "POST", e.URL+"/api/auth/login", `{"name":"viewer","password":"password1"}`, nil).StatusCode; code != 200 {
		t.Fatal(code)
	}
	var channels struct {
		Items []livetv.Channel `json:"items"`
	}
	e.call(t, admin, "GET", "/api/live-tv/channels", "", &channels)
	first := channels.Items[0]

	// Members may not use them while Live TV is for admins only.
	for _, c := range []struct{ method, path, body string }{
		{"PUT", "/api/live-tv/channels/" + first.ID + "/favourite", ""},
		{"GET", "/api/live-tv/guide/search?q=sleuth", ""},
		{"GET", "/api/live-tv/reminders", ""},
	} {
		if code := e.call(t, member, c.method, c.path, c.body, nil).StatusCode; code != 403 {
			t.Errorf("member %s %s while admins only: %d", c.method, c.path, code)
		}
	}
	e.call(t, admin, "PUT", "/api/settings/live-tv", `{"access":"everyone"}`, nil)

	// Favourites are each person's own and show on channels and the guide.
	if code := e.call(t, admin, "PUT", "/api/live-tv/channels/"+first.ID+"/favourite", "", nil).StatusCode; code != 204 {
		t.Fatalf("star: %d", code)
	}
	if code := e.call(t, admin, "PUT", "/api/live-tv/channels/nope/favourite", "", nil).StatusCode; code != 404 {
		t.Errorf("star an unknown channel: %d", code)
	}
	e.call(t, admin, "GET", "/api/live-tv/channels", "", &channels)
	if !channels.Items[0].Favourite || channels.Items[1].Favourite {
		t.Errorf("admin's channels: %+v", channels.Items)
	}
	var g LiveTVGuide
	e.call(t, admin, "GET", "/api/live-tv/guide", "", &g)
	if !g.Channels[0].Favourite {
		t.Error("guide channel not starred")
	}
	r := e.call(t, member, "GET", "/api/live-tv/channels", "", nil)
	if strings.Contains(string(r.Body), `"favourite"`) {
		t.Errorf("the member sees the admin's favourite: %s", r.Body)
	}
	if code := e.call(t, admin, "DELETE", "/api/live-tv/channels/"+first.ID+"/favourite", "", nil).StatusCode; code != 204 {
		t.Fatalf("unstar: %d", code)
	}
	var after struct {
		Items []livetv.Channel `json:"items"`
	}
	e.call(t, admin, "GET", "/api/live-tv/channels", "", &after)
	if after.Items[0].Favourite {
		t.Error("still starred")
	}

	// Search.
	var found LiveTVSearchOutput
	if r := e.call(t, member, "GET", "/api/live-tv/guide/search?q="+url.QueryEscape("sleuth"), "", &found.Body); r.StatusCode != 200 || len(found.Body.Items) == 0 {
		t.Fatalf("search: %d %s", r.StatusCode, r.Body)
	}
	hit := found.Body.Items[0]
	if hit.Programme.Title != "The Sleuth Files" || hit.Channel.ID == "" || hit.Channel.Number == "" || hit.Channel.Now != nil {
		t.Fatalf("hit: %+v", hit)
	}
	if code := e.call(t, member, "GET", "/api/live-tv/guide/search?q=s", "", nil).StatusCode; code != 422 {
		t.Errorf("one letter: %d", code)
	}
	var one LiveTVSearchOutput
	if r := e.call(t, member, "GET", "/api/live-tv/guide/search?q=sleuth&limit=1", "", &one.Body); r.StatusCode != 200 || len(one.Body.Items) != 1 {
		t.Errorf("limit 1: %s", r.Body)
	}

	// Reminders: for a programme still to start, the person's own.
	var later livetv.Programme
	for _, h := range found.Body.Items {
		if h.Programme.Start.After(time.Now()) {
			later = h.Programme
			break
		}
	}
	if later.ID == "" {
		t.Fatal("search found nothing still to start")
	}
	var rem LiveTVReminder
	if r := e.call(t, member, "PUT", "/api/live-tv/reminders", fmt.Sprintf(`{"programmeId":%q}`, later.ID), &rem); r.StatusCode != 200 || rem.State != "set" || rem.ChannelID == "" || !rem.Start.Equal(later.Start) {
		t.Fatalf("remind: %d %s", r.StatusCode, r.Body)
	}
	if code := e.call(t, member, "PUT", "/api/live-tv/reminders", `{"programmeId":"nope"}`, nil).StatusCode; code != 404 {
		t.Errorf("unknown programme: %d", code)
	}
	var list LiveTVRemindersOutput
	if r := e.call(t, member, "GET", "/api/live-tv/reminders", "", &list.Body); r.StatusCode != 200 || len(list.Body.Items) != 1 || list.Body.LeadMinutes != 5 {
		t.Fatalf("list: %s", r.Body)
	}
	e.call(t, admin, "GET", "/api/live-tv/reminders", "", &list.Body)
	if len(list.Body.Items) != 0 {
		t.Error("the admin sees the member's reminder")
	}
	e.call(t, member, "GET", "/api/live-tv/guide/search?q=sleuth", "", &found.Body)
	marked := false
	for _, h := range found.Body.Items {
		marked = marked || (h.Programme.ID == later.ID && h.Programme.Reminder)
	}
	if !marked {
		t.Error("search doesn't mark the reminder")
	}
	if code := e.call(t, admin, "DELETE", "/api/live-tv/reminders/"+rem.ID, "", nil).StatusCode; code != 204 {
		t.Errorf("another's delete: %d", code)
	}
	e.call(t, member, "GET", "/api/live-tv/reminders", "", &list.Body)
	if len(list.Body.Items) != 1 {
		t.Fatal("the admin removed the member's reminder")
	}
	if code := e.call(t, member, "DELETE", "/api/live-tv/reminders/"+rem.ID, "", nil).StatusCode; code != 204 {
		t.Errorf("delete: %d", code)
	}
	e.call(t, member, "GET", "/api/live-tv/reminders", "", &list.Body)
	if len(list.Body.Items) != 0 {
		t.Error("deleted reminder still listed")
	}

	// The lead time is a preference.
	var prefs Preferences
	if r := e.call(t, member, "PUT", "/api/me/preferences", `{"liveTVReminderMinutes":10}`, &prefs); r.StatusCode != 200 || prefs.LiveTVReminderMinutes != 10 {
		t.Fatalf("lead time: %s", r.Body)
	}
	if code := e.call(t, member, "PUT", "/api/me/preferences", `{"liveTVReminderMinutes":7}`, nil).StatusCode; code != 422 {
		t.Errorf("lead time 7: %d", code)
	}
	e.call(t, member, "GET", "/api/live-tv/reminders", "", &list.Body)
	if list.Body.LeadMinutes != 10 {
		t.Errorf("list's lead time: %d", list.Body.LeadMinutes)
	}
	e.call(t, admin, "GET", "/api/me/preferences", "", &prefs)
	if prefs.LiveTVReminderMinutes != 5 {
		t.Errorf("admin's default lead time: %d", prefs.LiveTVReminderMinutes)
	}
}
