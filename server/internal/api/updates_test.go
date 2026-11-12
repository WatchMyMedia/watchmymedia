package api

import (
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"strings"
	"testing"

	"watchmymedia/server/internal/updates"
)

func TestUpdates(t *testing.T) {
	t.Parallel()
	fetches := 0
	var list *httptest.Server
	list = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fetches++
		_, _ = w.Write([]byte(`{"releases": [{"version": "0.4.0", "date": "2026-11-03", "notes": "- notes",
		  "assets": {"macos": {"url": "` + list.URL + `/mac/WatchMyMedia-0.4.0.dmg", "size": 9}}}]}`))
	}))
	t.Cleanup(list.Close)
	srv := newTestServerWith(t, func(d *Deps) {
		d.Version = "v0.3.4"
		d.Updates = updates.New(list.URL+"/releases.json", d.Version, updates.InstallMacApp)
		if _, err := d.Auth.CreateUser(t.Context(), "viewer", "password2", false); err != nil {
			t.Fatal(err)
		}
	})

	// Opening Settings asks nothing of the internet.
	var s UpdateStatus
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings/updates", "", &s).StatusCode; code != http.StatusOK || !s.Enabled || s.Checked != nil || s.Current != "v0.3.4" || s.Install != "macos-app" {
		t.Fatalf("before a check -> %d %+v", code, s)
	}
	if fetches != 0 {
		t.Fatalf("GET fetched the release list %d times", fetches)
	}

	s = UpdateStatus{}
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/settings/updates/check", ``, &s).StatusCode; code != http.StatusOK || !s.UpdateAvailable || s.Latest != "0.4.0" || s.Download == nil || !strings.HasSuffix(s.Download.URL, "/mac/WatchMyMedia-0.4.0.dmg") {
		t.Fatalf("check -> %d %+v", code, s)
	}
	s = UpdateStatus{}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings/updates", "", &s).StatusCode; code != http.StatusOK || s.Checked == nil || s.Latest != "0.4.0" || fetches != 1 {
		t.Errorf("after a check -> %d %+v (%d fetches)", code, s, fetches)
	}

	// Admins only.
	jar, _ := cookiejar.New(nil)
	viewer := &http.Client{Jar: jar}
	res, err := viewer.Post(srv.URL+"/api/auth/login", "application/json", strings.NewReader(`{"name":"viewer","password":"password2"}`))
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	for _, req := range []struct{ method, path string }{{http.MethodGet, "/api/settings/updates"}, {http.MethodPost, "/api/settings/updates/check"}} {
		r, _ := http.NewRequestWithContext(t.Context(), req.method, srv.URL+req.path, nil)
		res, err := viewer.Do(r)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		if res.StatusCode != http.StatusForbidden {
			t.Errorf("viewer %s %s -> %d", req.method, req.path, res.StatusCode)
		}
	}
	if fetches != 1 {
		t.Errorf("a viewer made the server fetch (%d fetches)", fetches)
	}
}

func TestUpdatesOff(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	var s UpdateStatus
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings/updates", "", &s).StatusCode; code != http.StatusOK || s.Enabled || s.Current != "test" || s.Install != "binary" {
		t.Errorf("status -> %d %+v", code, s)
	}
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/settings/updates/check", ``, &s).StatusCode; code != http.StatusConflict {
		t.Errorf("check -> %d", code)
	}
}
