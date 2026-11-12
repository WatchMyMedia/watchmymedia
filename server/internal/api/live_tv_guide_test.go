package api

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/livetv/guide/guidetest"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
)

type guideEnv struct {
	*testEnv
	guide *guide.Service
	sd    *guidetest.SiliconDust
}

func newGuideEnv(t *testing.T) guideEnv {
	t.Helper()
	tuner := hdhrtest.New(nil)
	sd := &guidetest.SiliconDust{Feed: guidetest.Feed(time.Now(), guidetest.StandardChannels)}
	sd.DeviceAuth = func(a string) bool { return a == tuner.DeviceAuth() }
	servers := map[string]string{}
	for host, h := range map[string]http.Handler{"tuner.test": tuner, "api.hdhomerun.com": sd, "img.hdhomerun.com": guidetest.NewPictures()} {
		srv := httptest.NewServer(h)
		t.Cleanup(srv.Close)
		servers[host] = srv.URL
	}
	var g *guide.Service
	env := newTestServerWith(t, func(d *Deps) {
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) {
			return hdhomerun.New(a, hdhrtest.Transport(servers["tuner.test"]))
		})
		g = guide.New(d.Queries, d.Settings, d.LiveTV, guide.Options{Transport: guidetest.Transport(servers), ArtDir: filepath.Join(t.TempDir(), "art")})
		d.Guide = g
	})
	return guideEnv{testEnv: env, guide: g, sd: sd}
}

func (e guideEnv) call(t *testing.T, c *http.Client, method, path, body string, out any) reply {
	t.Helper()
	return doJSON(t, c, method, e.URL+path, body, out)
}

func TestLiveTVGuide(t *testing.T) {
	t.Parallel()
	e := newGuideEnv(t)
	admin := e.client
	if r := e.call(t, admin, "POST", "/api/settings/live-tv/sources", `{"address":"tuner.test"}`, nil); r.StatusCode >= 300 {
		t.Fatalf("add tuner: %d %s", r.StatusCode, r.Body)
	}
	// Off: the channel list is as it was, and the guide has no listings.
	r := e.call(t, admin, "GET", "/api/live-tv/channels", "", nil)
	for _, key := range []string{`"now"`, `"next"`, `"logoUrl"`} {
		if strings.Contains(string(r.Body), key) {
			t.Fatalf("listings off, yet %s in %s", key, r.Body)
		}
	}
	var g LiveTVGuide
	if r := e.call(t, admin, "GET", "/api/live-tv/guide?hours=2", "", &g); r.StatusCode != 200 || g.State.Enabled || len(g.Channels) != 2 || len(g.Channels[0].Programmes) != 0 || g.End.Sub(g.Start) != 2*time.Hour {
		t.Fatalf("guide while off: %d %s", r.StatusCode, r.Body)
	}
	var caps LiveTVCapabilities
	e.call(t, admin, "GET", "/api/live-tv/capabilities", "", &caps)
	if caps.Guide {
		t.Fatal("guide capability while off")
	}
	if r := e.call(t, admin, "POST", "/api/settings/live-tv/guide/refresh", "", nil); r.StatusCode != 409 {
		t.Fatalf("refresh while off: %d", r.StatusCode)
	}

	var s guide.Settings
	if r := e.call(t, admin, "PUT", "/api/settings/live-tv/guide", `{"enabled":true,"tmdb":false}`, &s); r.StatusCode != 200 || !s.Enabled || s.TMDB || s.Status.State != "waiting" {
		t.Fatalf("turn on: %d %s", r.StatusCode, r.Body)
	}
	e.guide.Fetch(t.Context())
	if r := e.call(t, admin, "GET", "/api/settings/live-tv/guide", "", &s); r.StatusCode != 200 || s.Status.State != "ok" || s.Status.Matched != 2 || len(s.Channels) != 2 {
		t.Fatalf("settings: %s", r.Body)
	}
	e.call(t, admin, "GET", "/api/live-tv/capabilities", "", &caps)
	if !caps.Guide {
		t.Fatal("no guide capability with listings")
	}
	r = e.call(t, admin, "GET", "/api/live-tv/guide?hours=6", "", &g)
	if r.StatusCode != 200 || !g.State.Enabled || g.State.Stale || g.State.UpdatedAt == nil || g.State.ListedThrough == nil {
		t.Fatalf("guide: %s", r.Body)
	}
	var picture string
	for _, c := range g.Channels {
		if !c.Listings || len(c.Programmes) == 0 || c.LogoURL == "" {
			t.Fatalf("%s: listings %v, %d programmes, logo %q", c.Number, c.Listings, len(c.Programmes), c.LogoURL)
		}
		for _, p := range c.Programmes {
			if !p.End.After(g.Start) || !p.Start.Before(g.End) {
				t.Fatalf("%s outside the window", p.Title)
			}
			if picture == "" {
				picture = p.PictureURL
			}
		}
	}
	var channels struct {
		Items []livetv.Channel `json:"items"`
	}
	e.call(t, admin, "GET", "/api/live-tv/channels", "", &channels)
	if channels.Items[0].Now == nil || channels.Items[0].Next == nil || channels.Items[0].LogoURL == "" {
		t.Fatalf("channels: %+v", channels.Items[0])
	}

	// Pictures need a sign-in, like /api/images, and come from the fake host.
	if picture == "" {
		t.Fatal("no picture in the guide")
	}
	if code := doJSON(t, nil, "GET", e.URL+picture, "", nil).StatusCode; code != 401 {
		t.Fatalf("picture without a sign-in: %d", code)
	}
	if r := e.call(t, admin, "GET", picture, "", nil); r.StatusCode != 200 || !strings.HasPrefix(r.Header.Get("Content-Type"), "image/") {
		t.Fatalf("picture: %d %s", r.StatusCode, r.Header.Get("Content-Type"))
	}
	if code := e.call(t, admin, "GET", "/api/live-tv/art/0123456789abcdef01234567", "", nil).StatusCode; code != 404 {
		t.Fatalf("unknown picture: %d", code)
	}

	// Members: listings only while they may watch; settings never.
	if code := e.call(t, admin, "POST", "/api/users", `{"name":"viewer","password":"password1"}`, nil).StatusCode; code != 201 {
		t.Fatal(code)
	}
	jar, _ := cookiejar.New(nil)
	member := &http.Client{Jar: jar}
	if code := doJSON(t, member, "POST", e.URL+"/api/auth/login", `{"name":"viewer","password":"password1"}`, nil).StatusCode; code != 200 {
		t.Fatal(code)
	}
	if code := e.call(t, member, "GET", "/api/live-tv/guide", "", nil).StatusCode; code != 403 {
		t.Fatalf("member guide while admins only: %d", code)
	}
	e.call(t, admin, "PUT", "/api/settings/live-tv", `{"access":"everyone"}`, nil)
	if code := e.call(t, member, "GET", "/api/live-tv/guide", "", nil).StatusCode; code != 200 {
		t.Fatalf("member guide: %d", code)
	}
	for _, c := range []struct{ method, path, body string }{
		{"GET", "/api/settings/live-tv/guide", ""},
		{"PUT", "/api/settings/live-tv/guide", `{"enabled":false,"tmdb":false}`},
		{"POST", "/api/settings/live-tv/guide/refresh", ""},
		{"PUT", "/api/settings/live-tv/guide/channels/x", `{"listings":false}`},
	} {
		if code := e.call(t, member, c.method, c.path, c.body, nil).StatusCode; code != 403 {
			t.Errorf("member %s %s: %d", c.method, c.path, code)
		}
	}

	// Hiding a channel's listings hides them for everyone.
	id := g.Channels[1].ID
	if code := e.call(t, admin, "PUT", "/api/settings/live-tv/guide/channels/"+id, `{"listings":false}`, nil).StatusCode; code != 204 {
		t.Fatalf("hide: %d", code)
	}
	var hidden LiveTVGuide
	e.call(t, member, "GET", "/api/live-tv/guide", "", &hidden)
	if hidden.Channels[1].Listings || len(hidden.Channels[1].Programmes) != 0 || hidden.Channels[1].LogoURL != "" {
		t.Fatalf("hidden channel: %+v", hidden.Channels[1])
	}

	// Refresh now: 4 a day.
	for i := range guide.RefreshesPerDay {
		if code := e.call(t, admin, "POST", "/api/settings/live-tv/guide/refresh", "", nil).StatusCode; code != 202 {
			t.Fatalf("refresh %d: %d", i, code)
		}
	}
	if code := e.call(t, admin, "POST", "/api/settings/live-tv/guide/refresh", "", nil).StatusCode; code != 429 {
		t.Fatalf("fifth refresh: %d", code)
	}
	if code := e.call(t, admin, "PUT", "/api/settings/live-tv/guide", `{"enabled":true,"tmdb":true,"email":"not an email"}`, nil).StatusCode; code != 422 {
		t.Fatalf("bad email: %d", code)
	}

	// DeviceAuth reaches no reply.
	for _, path := range []string{"/api/live-tv/guide", "/api/live-tv/channels", "/api/settings/live-tv/guide", "/api/settings/live-tv", "/api/live-tv/capabilities"} {
		if r := e.call(t, admin, "GET", path, "", nil); strings.Contains(string(r.Body), "fictional-never") {
			t.Errorf("DeviceAuth in %s", path)
		}
	}
}

// A guide window for 100 channels over 6 hours stays under the plan's
// 300 KB gzipped (§5.6).
func TestLiveTVGuideReplyBudget(t *testing.T) {
	t.Parallel()
	e := newGuideEnv(t)
	ctx := t.Context()
	if err := e.guide.Configure(ctx, true, true, ""); err != nil {
		t.Fatal(err)
	}
	source, err := e.q.CreateLiveSource(ctx, db.CreateLiveSourceParams{ID: "s", DeviceID: "1045B0E4", Name: "Tuner", Address: "http://tuner.test", Model: "HDHR4-2US", Firmware: "20260313", TunerCount: 2})
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now().Truncate(30 * time.Minute)
	// Descriptions of the feed's median length, of words in a varying
	// order, so they compress about as real ones do.
	words := strings.Fields("the a detective harbor storm quiz night family new old city coast case final team match recipe garden island river town secret week host guests returns finds must before after while")
	seed := uint32(1)
	describe := func() string {
		var b strings.Builder
		for b.Len() < 125 {
			seed = seed*1664525 + 1013904223
			b.WriteString(words[int(seed>>16)%len(words)])
			b.WriteByte(' ')
		}
		return strings.TrimSpace(b.String()) + "."
	}
	for ch := range 100 {
		id := fmt.Sprintf("c%03d", ch)
		number := fmt.Sprintf("%d.%d", ch/4+2, ch%4+1)
		if err := e.q.UpsertLiveChannel(ctx, db.UpsertLiveChannelParams{ID: id, SourceID: source.ID, ProviderKey: number, Number: number, Name: "Channel", StreamPath: "/auto/v" + number, Major: int64(ch/4 + 2), Minor: int64(ch%4 + 1), LastSeenAt: db.FormatTime(start)}); err != nil {
			t.Fatal(err)
		}
		if err := e.q.UpsertGuideChannel(ctx, db.UpsertGuideChannelParams{ChannelID: id, ProviderID: "US" + id + ".hdhomerun.com", FeedName: "WXXX", Logo: fmt.Sprintf("%024x", ch), MatchedBy: "name"}); err != nil {
			t.Fatal(err)
		}
		season, episode := int64(2), int64(ch)
		for slot := range 13 {
			at := start.Add(time.Duration(slot-1) * 30 * time.Minute)
			if err := e.q.InsertProgramme(ctx, db.InsertProgrammeParams{
				ID: fmt.Sprintf("%s-%02d", id, slot), ChannelID: id, StartAt: at.Unix(), EndAt: at.Add(30 * time.Minute).Unix(),
				Title: words[(ch+slot)%len(words)] + " " + words[slot%len(words)], EpisodeTitle: describe()[:20], Season: &season, Episode: &episode, AirDate: "2026-03-04",
				Hd: 1, Categories: `["Series","Drama"]`, Kind: "episode", SeriesKey: fmt.Sprintf("C%07d", ch*13+slot), Description: describe(), DescriptionSource: "provider",
				Picture: fmt.Sprintf("%024x", slot), PictureSource: "provider",
			}); err != nil {
				t.Fatal(err)
			}
		}
	}
	req, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, e.URL+"/api/live-tv/guide?hours=6&start="+start.UTC().Format(time.RFC3339), nil)
	req.Header.Set("Accept-Encoding", "gzip")
	res, err := e.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	compressed, _ := io.ReadAll(res.Body)
	gz, err := gzip.NewReader(strings.NewReader(string(compressed)))
	if err != nil {
		t.Fatalf("%d, not gzip: %v", res.StatusCode, err)
	}
	raw, _ := io.ReadAll(gz)
	var g LiveTVGuide
	if err := json.Unmarshal(raw, &g); err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, c := range g.Channels {
		n += len(c.Programmes)
	}
	// The programme ending as the window starts isn't in it.
	if len(g.Channels) != 100 || n != 1200 {
		t.Fatalf("%d channels, %d programmes", len(g.Channels), n)
	}
	t.Logf("100 channels over 6 hours: %d KB, %d KB gzipped", len(raw)>>10, len(compressed)>>10)
	if len(compressed) > 300<<10 {
		t.Fatalf("%d KB gzipped, over 300 KB", len(compressed)>>10)
	}
}
