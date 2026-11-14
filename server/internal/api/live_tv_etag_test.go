package api

import (
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"strings"
	"testing"

	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
)

// Live TV's capabilities and channel list carry an ETag of their body and
// answer 304 to it (API level 28), so a client's HTTP cache revalidates
// them instead of downloading them again. The tag is of what this person
// is sent, so another account's tag matches only an identical reply.
func TestLiveTVReadsAnswerNotModified(t *testing.T) {
	t.Parallel()
	tuner := httptest.NewServer(hdhrtest.New(nil))
	t.Cleanup(tuner.Close)
	srv := newTestServerWith(t, func(d *Deps) {
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
	})
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/settings/live-tv/sources", `{"address":"tuner.test"}`, nil); r.StatusCode >= 300 {
		t.Fatalf("source: %d %s", r.StatusCode, r.Body)
	}
	get := func(c *http.Client, path string, header ...string) reply {
		t.Helper()
		return doJSON(t, c, http.MethodGet, srv.URL+path, "", nil, header...)
	}
	var channels struct {
		Items []livetv.Channel `json:"items"`
	}
	if r := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/live-tv/channels", "", &channels); r.StatusCode != 200 || len(channels.Items) < 2 {
		t.Fatalf("channels: %d %s", r.StatusCode, r.Body)
	}

	tags := map[string]string{}
	for _, path := range []string{"/api/live-tv/capabilities", "/api/live-tv/channels"} {
		res := get(srv.client, path)
		tag := res.Header.Get("ETag")
		if res.StatusCode != 200 || !strings.HasPrefix(tag, `W/"`) || res.Header.Get("Cache-Control") != "private, no-cache" {
			t.Fatalf("%s -> %d, ETag %q, Cache-Control %q", path, res.StatusCode, tag, res.Header.Get("Cache-Control"))
		}
		if res := get(srv.client, path, "If-None-Match", tag); res.StatusCode != http.StatusNotModified || len(res.Body) != 0 || res.Header.Get("ETag") != tag {
			t.Errorf("%s with its tag -> %d, %d bytes, ETag %q", path, res.StatusCode, len(res.Body), res.Header.Get("ETag"))
		}
		tags[path] = tag
	}

	// Hiding a channel changes the list, and so its tag.
	if r := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings/live-tv/channels/"+channels.Items[1].ID, `{"enabled":false}`, nil); r.StatusCode >= 300 {
		t.Fatalf("hide channel: %d %s", r.StatusCode, r.Body)
	}
	if res := get(srv.client, "/api/live-tv/channels", "If-None-Match", tags["/api/live-tv/channels"]); res.StatusCode != 200 || res.Header.Get("ETag") == tags["/api/live-tv/channels"] {
		t.Errorf("channels after hiding one -> %d, ETag %q", res.StatusCode, res.Header.Get("ETag"))
	}

	// With Live TV for admins only, a member is told it isn't allowed: the
	// admin's tag doesn't match the member's reply, and the refused channel
	// list has no tag at all.
	if r := doJSON(t, srv.client, http.MethodPut, srv.URL+"/api/settings/live-tv", `{"access":"admins"}`, nil); r.StatusCode >= 300 {
		t.Fatalf("access: %d %s", r.StatusCode, r.Body)
	}
	adminTag := get(srv.client, "/api/live-tv/capabilities").Header.Get("ETag")
	if r := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"casey","password":"password1"}`, nil); r.StatusCode != 201 {
		t.Fatalf("member: %d", r.StatusCode)
	}
	jar, _ := cookiejar.New(nil)
	member := &http.Client{Jar: jar}
	if r := doJSON(t, member, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"casey","password":"password1"}`, nil); r.StatusCode != 200 {
		t.Fatalf("member login: %d", r.StatusCode)
	}
	res := get(member, "/api/live-tv/capabilities", "If-None-Match", adminTag)
	if res.StatusCode != 200 || res.Header.Get("ETag") == adminTag || !strings.Contains(string(res.Body), `"allowed":false`) {
		t.Errorf("member with the admin's tag -> %d, ETag %q: %s", res.StatusCode, res.Header.Get("ETag"), res.Body)
	}
	if res := get(member, "/api/live-tv/channels"); res.StatusCode != http.StatusForbidden || res.Header.Get("ETag") != "" {
		t.Errorf("member's refused channel list -> %d with ETag %q", res.StatusCode, res.Header.Get("ETag"))
	}

	// The routes beside them get none: a channel's favourite is a write,
	// sessions change by the second.
	for _, path := range []string{"/api/live-tv/sessions", "/api/live-tv/channels/x/favourite", "/api/live-tv/guide", fmt.Sprintf("/api/live-tv/sessions/%s", "x")} {
		if etagged(path) {
			t.Errorf("%s would get an ETag", path)
		}
	}
}
