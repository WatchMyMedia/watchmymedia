package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/netguard"
)

func TestLiveTVRecordingPermissionsRulesAndBatchedMarks(t *testing.T) {
	t.Parallel()
	tuner := httptest.NewServer(hdhrtest.New(nil))
	t.Cleanup(tuner.Close)
	var recorder *dvr.Service
	env := newTestServerWith(t, func(d *Deps) {
		d.Net = netguard.New([]netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")}, []netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")})
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
		recorder = dvr.New(d.Queries, d.Settings, d.LiveTV, nil, dvr.Options{FreeBytes: func(string) (uint64, error) { return 1_000_000_000_000, nil }, Bus: d.Bus})
		d.Recordings = recorder
		t.Cleanup(recorder.Close)
		d.Guide = guide.New(d.Queries, d.Settings, d.LiveTV, guide.Options{ArtDir: t.TempDir()})
	})
	call := func(client *http.Client, method, path, body string, out any, headers ...string) reply {
		return doJSON(t, client, method, env.URL+path, body, out, headers...)
	}
	if got := call(env.client, "POST", "/api/settings/live-tv/sources", `{"address":"tuner.test"}`, nil); got.StatusCode != 200 {
		t.Fatalf("source=%d %s", got.StatusCode, got.Body)
	}
	root := t.TempDir()
	lib, err := env.q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "tv", Name: "Recordings", Path: root})
	if err != nil {
		t.Fatal(err)
	}
	config := dvr.Settings{Enabled: true, Access: "admins", TVLibraryID: lib.ID, TVPath: root, PadBefore: 1, PadAfter: 2, WarningSeconds: 120}
	body, _ := json.Marshal(config)
	if got := call(env.client, "PUT", "/api/settings/live-tv/recordings", string(body), nil); got.StatusCode != 200 {
		t.Fatal(got.StatusCode, string(got.Body))
	}
	away := []string{"X-Forwarded-For", "203.0.113.9"}
	for _, method := range []string{"GET", "PUT"} {
		b := ""
		if method == "PUT" {
			b = string(body)
		}
		if got := call(env.client, method, "/api/settings/live-tv/recordings", b, nil, away...); got.StatusCode != 403 {
			t.Fatalf("admin settings from outside=%d", got.StatusCode)
		}
	}
	member := func(name string) *http.Client {
		t.Helper()
		if got := call(env.client, "POST", "/api/users", fmt.Sprintf(`{"name":%q,"password":"password1"}`, name), nil); got.StatusCode != 201 {
			t.Fatal(got.StatusCode)
		}
		jar, _ := cookiejar.New(nil)
		c := &http.Client{Jar: jar}
		if got := call(c, "POST", "/api/auth/login", fmt.Sprintf(`{"name":%q,"password":"password1"}`, name), nil); got.StatusCode != 200 {
			t.Fatal(got.StatusCode)
		}
		return c
	}
	first, second := member("casey"), member("jordan")
	if got := call(env.client, "PUT", "/api/settings/live-tv", `{"access":"everyone"}`, nil); got.StatusCode != 204 {
		t.Fatal(got.StatusCode)
	}
	channels, err := env.q.ListLiveChannels(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	p := db.InsertProgrammeParams{ID: "vane-pilot", ChannelID: channels[0].ID, StartAt: now.Add(10 * time.Minute).Unix(), EndAt: now.Add(70 * time.Minute).Unix(), Title: "Inspector Vane", EpisodeTitle: "The orchard", Kind: "episode", Season: new(int64(1)), Episode: new(int64(1)), SeriesKey: "vane", IsNew: 1, Categories: "[]"}
	if err = env.q.InsertProgramme(t.Context(), p); err != nil {
		t.Fatal(err)
	}
	if err = env.q.UpsertGuideChannel(t.Context(), db.UpsertGuideChannelParams{ChannelID: channels[0].ID, ProviderID: "fictional-guide", FeedName: "Meadow"}); err != nil {
		t.Fatal(err)
	}
	if got := call(first, "POST", "/api/live-tv/recordings", `{"programmeId":"vane-pilot","scope":"once"}`, nil); got.StatusCode != 403 {
		t.Fatal(got.StatusCode)
	}
	config.Access = "everyone"
	body, _ = json.Marshal(config)
	call(env.client, "PUT", "/api/settings/live-tv/recordings", string(body), nil)
	var rec dvr.Recording
	if got := call(first, "POST", "/api/live-tv/recordings", `{"programmeId":"vane-pilot","scope":"series"}`, &rec); got.StatusCode != 200 || rec.RuleID == "" {
		t.Fatalf("record=%d %s", got.StatusCode, got.Body)
	}
	var again dvr.Recording
	call(first, "POST", "/api/live-tv/recordings", `{"programmeId":"vane-pilot","scope":"series"}`, &again)
	if again.ID != rec.ID {
		t.Fatal("retry scheduled a second capture")
	}
	for _, c := range []struct{ method, path, body string }{
		{"DELETE", "/api/live-tv/recordings/" + rec.ID, ""},
		{"PATCH", "/api/live-tv/recordings/" + rec.ID, `{"prefer":true}`},
		{"PATCH", "/api/live-tv/recording-rules/" + rec.RuleID, `{"keep":2}`},
		{"DELETE", "/api/live-tv/recording-rules/" + rec.RuleID, ""},
	} {
		if got := call(second, c.method, c.path, c.body, nil); got.StatusCode != 403 {
			t.Fatalf("other actor %s=%d", c.path, got.StatusCode)
		}
	}
	var rules dvr.Recordings
	call(second, "GET", "/api/live-tv/recordings?section=series", "", &rules)
	if len(rules.Rules) != 1 || rules.Rules[0].CanCancel {
		t.Fatal(rules)
	}
	var page dvr.Recordings
	call(second, "GET", "/api/live-tv/recordings?section=upcoming", "", &page)
	if len(page.Items) != 1 || page.Items[0].CanCancel {
		t.Fatal(page)
	}
	// A programme's guide mark is authorized with the whole response, never
	// fetched individually. Record-only information disappears with permission.
	call(env.client, "PUT", "/api/settings/live-tv/guide", `{"enabled":true,"tmdb":false}`, nil)
	got := call(first, "GET", "/api/live-tv/guide?hours=3", "", nil)
	if got.StatusCode != 200 || !strings.Contains(string(got.Body), `"recording"`) {
		t.Fatalf("guide=%d %s", got.StatusCode, got.Body)
	}
	config.Access = "admins"
	body, _ = json.Marshal(config)
	call(env.client, "PUT", "/api/settings/live-tv/recordings", string(body), nil)
	got = call(first, "GET", "/api/live-tv/guide?hours=3", "", nil)
	if strings.Contains(string(got.Body), `"recording"`) {
		t.Fatal("recording marks leaked after permission revoked")
	}
	// A privileged media token only opens media. It cannot schedule, list or
	// delete household recordings, even for the admin who issued it.
	var token MediaToken
	call(env.client, "POST", "/api/auth/media-token", "", &token)
	for _, c := range []struct{ method, path, body string }{
		{"GET", "/api/live-tv/recordings", ""},
		{"POST", "/api/live-tv/recordings", `{"programmeId":"vane-pilot","scope":"once"}`},
		{"DELETE", "/api/live-tv/recordings/" + rec.ID + "/file", ""},
	} {
		if got := call(nil, c.method, c.path+"?access_token="+token.Token, c.body, nil); got.StatusCode != 401 {
			t.Fatal(c.path, got.StatusCode)
		}
	}
	if got := call(env.client, "DELETE", "/api/live-tv/recordings/"+rec.ID, "", nil); got.StatusCode != 204 {
		t.Fatal(got.StatusCode)
	}
}

func TestLiveTVRecordingDeleteKeepsForeignFilesAndChecksCurrentPolicy(t *testing.T) {
	t.Parallel()
	var recorder *dvr.Service
	env := newTestServerWith(t, func(d *Deps) {
		d.LiveTV = livetv.New(d.Queries, d.Settings, nil)
		recorder = dvr.New(d.Queries, d.Settings, d.LiveTV, nil, dvr.Options{})
		d.Recordings = recorder
		t.Cleanup(recorder.Close)
	})
	root := t.TempDir()
	lib, err := env.q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "tv", Name: "Television", Path: root})
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(root, "Evening.ts")
	if err = os.WriteFile(path, []byte("fictional-broadcast"), 0o600); err != nil {
		t.Fatal(err)
	}
	data := fmt.Sprintf(`{"id":"owned","title":"Evening","bytes":19,"Path":%q,"LibraryID":%d,"gaps":[]}`, path, lib.ID)
	// Intentionally unmatched: a completed recording can be deleted from its
	// list even before TMDB has matched the library file to an episode.
	err = env.q.PutDVRRecording(t.Context(), db.PutDVRRecordingParams{ID: "owned", SourceID: "source", ChannelID: "channel", Number: "7.1", StartAt: time.Now().Add(-time.Minute).Unix(), EndAt: time.Now().Unix(), Priority: 1, State: "done", Section: "recorded", Path: &path, Data: data, CreatedAt: time.Now().Unix(), UpdatedAt: time.Now().Unix()})
	if err != nil {
		t.Fatal(err)
	}
	if err = recorder.SetSettings(t.Context(), dvr.Settings{Enabled: true, Access: "everyone", TVLibraryID: lib.ID, TVPath: root}); err != nil {
		t.Fatal(err)
	}
	doJSON(t, env.client, "PUT", env.URL+"/api/settings/live-tv", `{"access":"everyone"}`, nil)
	doJSON(t, env.client, "POST", env.URL+"/api/users", `{"name":"member","password":"password1"}`, nil)
	jar, _ := cookiejar.New(nil)
	member := &http.Client{Jar: jar}
	doJSON(t, member, "POST", env.URL+"/api/auth/login", `{"name":"member","password":"password1"}`, nil)
	var page dvr.Recordings
	doJSON(t, member, "GET", env.URL+"/api/live-tv/recordings?section=recorded", "", &page)
	if len(page.Items) != 1 || page.Items[0].CanDelete {
		t.Fatal(page)
	}
	if got := doJSON(t, member, "DELETE", env.URL+"/api/live-tv/recordings/owned/file", "", nil); got.StatusCode != 403 {
		t.Fatal(got.StatusCode)
	}
	if err = env.q.SetSetting(t.Context(), db.SetSettingParams{Key: "delete.access", Value: "everyone"}); err != nil {
		t.Fatal(err)
	}
	page = dvr.Recordings{}
	doJSON(t, member, "GET", env.URL+"/api/live-tv/recordings?section=recorded", "", &page)
	if len(page.Items) != 1 || !page.Items[0].CanDelete {
		t.Fatal(page)
	}
	if err = os.WriteFile(path, []byte("foreign-content"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := doJSON(t, member, "DELETE", env.URL+"/api/live-tv/recordings/owned/file", "", nil); got.StatusCode != 422 {
		t.Fatal(got.StatusCode)
	}
	if got, err := os.ReadFile(path); err != nil || string(got) != "foreign-content" {
		t.Fatal(string(got), err)
	}
	if err = os.WriteFile(path, []byte("fictional-broadcast"), 0o600); err != nil {
		t.Fatal(err)
	}
	for i := range 2 {
		if got := doJSON(t, member, "DELETE", env.URL+"/api/live-tv/recordings/owned/file?recordAgain=false", "", nil); got.StatusCode != 204 {
			t.Fatalf("delete #%d=%d %s", i, got.StatusCode, got.Body)
		}
	}
	if _, err = os.Stat(path); !os.IsNotExist(err) {
		t.Fatal(err)
	}
	page = dvr.Recordings{}
	doJSON(t, member, "GET", env.URL+"/api/live-tv/recordings?section=recorded", "", &page)
	if len(page.Items) != 0 {
		t.Fatalf("deleted tombstone remained in Recorded: %+v", page)
	}

	foreign := filepath.Join(root, "Foreign.ts")
	if err = os.WriteFile(foreign, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := doJSON(t, member, "DELETE", env.URL+"/api/live-tv/recordings/missing/file", "", nil); got.StatusCode != 404 {
		t.Fatal(got.StatusCode)
	}
	if got, err := os.ReadFile(foreign); err != nil || string(got) != "keep" {
		t.Fatal(string(got), err)
	}
}

// An admin away from the admin network records as anyone may, but acts as a
// member on other people's recordings and rules: no cancelling, stopping or
// changing them there, and the replies say so.
func TestLiveTVRecordingAdminPowersNeedTheAdminNetwork(t *testing.T) {
	t.Parallel()
	tuner := httptest.NewServer(hdhrtest.New(nil))
	t.Cleanup(tuner.Close)
	var recorder *dvr.Service
	env := newTestServerWith(t, func(d *Deps) {
		d.Net = netguard.New([]netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")}, []netip.Prefix{netip.MustParsePrefix("127.0.0.0/8")})
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
		recorder = dvr.New(d.Queries, d.Settings, d.LiveTV, nil, dvr.Options{FreeBytes: func(string) (uint64, error) { return 1_000_000_000_000, nil }, Bus: d.Bus})
		d.Recordings = recorder
		t.Cleanup(recorder.Close)
		d.Guide = guide.New(d.Queries, d.Settings, d.LiveTV, guide.Options{ArtDir: t.TempDir()})
	})
	call := func(client *http.Client, method, path, body string, out any, headers ...string) reply {
		return doJSON(t, client, method, env.URL+path, body, out, headers...)
	}
	away := []string{"X-Forwarded-For", "203.0.113.9"}
	if got := call(env.client, "POST", "/api/settings/live-tv/sources", `{"address":"tuner.test"}`, nil); got.StatusCode != 200 {
		t.Fatalf("source=%d %s", got.StatusCode, got.Body)
	}
	root := t.TempDir()
	lib, err := env.q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "tv", Name: "Recordings", Path: root})
	if err != nil {
		t.Fatal(err)
	}
	config := dvr.Settings{Enabled: true, Access: "everyone", TVLibraryID: lib.ID, TVPath: root, PadBefore: 1, PadAfter: 2, WarningSeconds: 120}
	body, _ := json.Marshal(config)
	if got := call(env.client, "PUT", "/api/settings/live-tv/recordings", string(body), nil); got.StatusCode != 200 {
		t.Fatal(got.StatusCode, string(got.Body))
	}
	if got := call(env.client, "PUT", "/api/settings/live-tv", `{"access":"everyone"}`, nil); got.StatusCode != 204 {
		t.Fatal(got.StatusCode)
	}
	if got := call(env.client, "POST", "/api/users", `{"name":"casey","password":"password1"}`, nil); got.StatusCode != 201 {
		t.Fatal(got.StatusCode)
	}
	jar, _ := cookiejar.New(nil)
	casey := &http.Client{Jar: jar}
	if got := call(casey, "POST", "/api/auth/login", `{"name":"casey","password":"password1"}`, nil); got.StatusCode != 200 {
		t.Fatal(got.StatusCode)
	}
	channels, err := env.q.ListLiveChannels(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	for i, id := range []string{"vane", "orchard"} {
		p := db.InsertProgrammeParams{ID: id + "-pilot", ChannelID: channels[0].ID, StartAt: now.Add(time.Duration(10+70*i) * time.Minute).Unix(), EndAt: now.Add(time.Duration(70+70*i) * time.Minute).Unix(),
			Title: "Inspector " + id, EpisodeTitle: "Pilot", Kind: "episode", Season: new(int64(1)), Episode: new(int64(1)), SeriesKey: id, IsNew: 1, Categories: "[]"}
		if err = env.q.InsertProgramme(t.Context(), p); err != nil {
			t.Fatal(err)
		}
	}
	if err = env.q.UpsertGuideChannel(t.Context(), db.UpsertGuideChannelParams{ChannelID: channels[0].ID, ProviderID: "fictional-guide", FeedName: "Meadow"}); err != nil {
		t.Fatal(err)
	}
	call(env.client, "PUT", "/api/settings/live-tv/guide", `{"enabled":true,"tmdb":false}`, nil)
	var theirs dvr.Recording
	if got := call(casey, "POST", "/api/live-tv/recordings", `{"programmeId":"vane-pilot","scope":"series"}`, &theirs); got.StatusCode != 200 || theirs.RuleID == "" {
		t.Fatalf("record=%d %s", got.StatusCode, got.Body)
	}

	// Away, casey's recording and rule are not the admin's to change.
	for _, c := range []struct{ method, path, body string }{
		{"DELETE", "/api/live-tv/recordings/" + theirs.ID, ""},
		{"PATCH", "/api/live-tv/recordings/" + theirs.ID, `{"prefer":true}`},
		{"PATCH", "/api/live-tv/recording-rules/" + theirs.RuleID, `{"keep":2}`},
		{"DELETE", "/api/live-tv/recording-rules/" + theirs.RuleID, ""},
	} {
		if got := call(env.client, c.method, c.path, c.body, nil, away...); got.StatusCode != 403 {
			t.Fatalf("away admin %s %s=%d", c.method, c.path, got.StatusCode)
		}
	}
	var page dvr.Recordings
	call(env.client, "GET", "/api/live-tv/recordings?section=upcoming", "", &page, away...)
	if len(page.Items) == 0 || page.Items[0].CanCancel {
		t.Fatalf("away upcoming=%+v", page)
	}
	var rules dvr.Recordings
	call(env.client, "GET", "/api/live-tv/recordings?section=series", "", &rules, away...)
	if len(rules.Rules) != 1 || rules.Rules[0].CanCancel {
		t.Fatalf("away rules=%+v", rules)
	}
	var again dvr.Recording
	if got := call(env.client, "POST", "/api/live-tv/recordings", `{"programmeId":"vane-pilot","scope":"once"}`, &again, away...); got.StatusCode != 200 || again.ID != theirs.ID || again.CanCancel {
		t.Fatalf("away record of casey's=%d %+v", got.StatusCode, again)
	}
	guideMark := func(headers ...string) *livetv.RecordingMark {
		t.Helper()
		var g struct {
			Channels []struct {
				Programmes []livetv.Programme `json:"programmes"`
			} `json:"channels"`
		}
		call(env.client, "GET", "/api/live-tv/guide?hours=3", "", &g, headers...)
		for _, c := range g.Channels {
			for _, p := range c.Programmes {
				if p.ID == "vane-pilot" {
					return p.Recording
				}
			}
		}
		return nil
	}
	if m := guideMark(away...); m == nil || m.CanCancel {
		t.Fatalf("away guide mark=%+v", m)
	}
	if m := guideMark(); m == nil || !m.CanCancel {
		t.Fatalf("home guide mark=%+v", m)
	}

	// Recording follows Live TV access, which an admin has from anywhere,
	// even when only admins may record; their own stays theirs to cancel.
	config.Access = "admins"
	body, _ = json.Marshal(config)
	call(env.client, "PUT", "/api/settings/live-tv/recordings", string(body), nil)
	var mine dvr.Recording
	if got := call(env.client, "POST", "/api/live-tv/recordings", `{"programmeId":"orchard-pilot","scope":"once"}`, &mine, away...); got.StatusCode != 200 || !mine.CanCancel {
		t.Fatalf("away admin records=%d %+v", got.StatusCode, mine)
	}
	if got := call(env.client, "DELETE", "/api/live-tv/recordings/"+mine.ID, "", nil, away...); got.StatusCode != 204 {
		t.Fatalf("away admin cancels their own=%d", got.StatusCode)
	}

	// The Activity rows and the tuner warning, worked out per request.
	admin := auth.User{ID: 1, Name: "admin", IsAdmin: true}
	member := auth.User{ID: *theirs.UserID, Name: "casey"}
	home := netguard.NewContext(t.Context(), netguard.Info{Admin: true})
	elsewhere := netguard.NewContext(t.Context(), netguard.Info{})
	if ownsRecording(recordingActor(elsewhere, admin), theirs.UserID) || !ownsRecording(recordingActor(home, admin), theirs.UserID) ||
		!ownsRecording(recordingActor(elsewhere, member), theirs.UserID) || !ownsRecording(recordingActor(elsewhere, admin), new(admin.ID)) {
		t.Fatal("activity cancel rights don't follow the admin network")
	}
	for _, c := range []struct {
		name string
		ctx  context.Context
		u    auth.User
		want bool
	}{{"admin away", elsewhere, admin, false}, {"admin home", home, admin, true}, {"setter away", elsewhere, member, true}} {
		v := livetv.SessionView{TakeoverRecordingID: theirs.ID, TakeoverCanCancel: true}
		takeoverCanCancel(c.ctx, recorder, c.u, &v)
		if v.TakeoverCanCancel != c.want {
			t.Errorf("%s: takeoverCanCancel=%v", c.name, v.TakeoverCanCancel)
		}
	}

	// At home the admin may stop it.
	if got := call(env.client, "DELETE", "/api/live-tv/recording-rules/"+theirs.RuleID, "", nil); got.StatusCode != 204 {
		t.Fatalf("home admin deletes the rule=%d", got.StatusCode)
	}
}

//go:fix inline
