package api

import (
	"encoding/json"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"strings"
	"testing"

	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/netguard"
)

func TestLiveTVConfigurationAndAccess(t *testing.T) {
	t.Parallel()
	fake := hdhrtest.New(nil)
	tuner := httptest.NewServer(fake)
	defer tuner.Close()
	srv := newTestServerWith(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		admin, _ := netguard.Parse([]string{"local"})
		d.Net = netguard.New(trusted, admin)
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
	})
	call := func(method, path, body string, out any) reply {
		t.Helper()
		return doJSON(t, srv.client, method, srv.URL+path, body, out)
	}
	var source livetv.Source
	r := call("POST", "/api/settings/live-tv/sources", `{"address":"tuner.test"}`, &source)
	if r.StatusCode < 200 || r.StatusCode >= 300 || source.ID == "" {
		t.Fatalf("create: %d %s", r.StatusCode, r.Body)
	}
	if strings.Contains(string(r.Body), "DeviceAuth") || strings.Contains(string(r.Body), "fictional-never") {
		t.Fatal("device credential leaked")
	}
	var channels struct {
		Items []livetv.Channel `json:"items"`
	}
	r = call("GET", "/api/live-tv/channels", "", &channels)
	if r.StatusCode != 200 || len(channels.Items) != 2 {
		t.Fatalf("channels: %s", r.Body)
	}
	if strings.Contains(string(r.Body), "tuner.test") || strings.Contains(string(r.Body), "/auto/") {
		t.Fatal("viewer got tuner address/path")
	}
	if code := call("POST", "/api/users", `{"name":"viewer","password":"password1"}`, nil).StatusCode; code != 201 {
		t.Fatal(code)
	}
	jar, _ := cookiejar.New(nil)
	member := &http.Client{Jar: jar}
	if code := doJSON(t, member, "POST", srv.URL+"/api/auth/login", `{"name":"viewer","password":"password1"}`, nil).StatusCode; code != 200 {
		t.Fatal(code)
	}
	if code := doJSON(t, member, "GET", srv.URL+"/api/live-tv/channels", "", nil).StatusCode; code != 403 {
		t.Fatalf("member default: %d", code)
	}
	for _, c := range []*http.Client{member, srv.client} {
		r := doJSON(t, c, "POST", srv.URL+"/api/settings/live-tv/test", `{"address":"tuner.test"}`, nil, "X-Forwarded-For", "203.0.113.2")
		if r.StatusCode != 403 {
			t.Fatalf("configuration restriction: %d", r.StatusCode)
		}
	}
	if code := call("PUT", "/api/settings/live-tv", `{"access":"everyone"}`, nil).StatusCode; code >= 300 {
		t.Fatal(code)
	}
	if code := doJSON(t, member, "GET", srv.URL+"/api/live-tv/channels", "", nil).StatusCode; code != 200 {
		t.Fatal(code)
	}
	r = call("GET", "/api/settings/live-tv", "", nil)
	var settings LiveTVSettings
	if err := json.Unmarshal(r.Body, &settings); err != nil {
		t.Fatal(err)
	}
	if len(settings.Sources) != 1 {
		t.Fatal("missing source")
	}
	if active, _, opens := fake.Counts(); active != 0 || opens != 0 {
		t.Fatal("configuration consumed a tuner")
	}
}
