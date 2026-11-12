package api

import (
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"testing"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/plex/plextest"
	"watchmymedia/server/internal/plexsync"
	"watchmymedia/server/internal/watchstate"
)

func newPlexTestServer(t *testing.T, adjust func(*Deps)) (*testEnv, *plextest.Fake) {
	t.Helper()
	f := plextest.New(t)
	srv := newTestServerWith(t, func(d *Deps) {
		svc, err := plexsync.New(t.Context(), d.Queries, watchstate.New(d.Queries, d.Bus), d.DataDir, plexsync.Options{
			ClientsOrigin: f.Cloud.URL, AccountOrigin: f.Cloud.URL, CloudHTTP: f.Cloud.Client(), ServerHTTP: f.PMS.Client()})
		if err != nil {
			t.Fatal(err)
		}
		d.Plex = svc
		if adjust != nil {
			adjust(d)
		}
	})
	return srv, f
}

var plexRoutes = []struct{ method, path, body string }{
	{http.MethodPost, "/api/integrations/plex/auth", ""},
	{http.MethodGet, "/api/integrations/plex/auth/x", ""},
	{http.MethodDelete, "/api/integrations/plex/auth/x", ""},
	{http.MethodGet, "/api/integrations/plex/connections", ""},
	{http.MethodPost, "/api/integrations/plex/connections", `{"signInId":"x","machineIdentifier":"x","origin":"http://192.0.2.1"}`},
	{http.MethodDelete, "/api/integrations/plex/connections/1", ""},
	{http.MethodGet, "/api/integrations/plex/connections/1/users", ""},
	{http.MethodGet, "/api/integrations/plex/connections/1/libraries", ""},
	{http.MethodPut, "/api/integrations/plex/connections/1/mappings", `{"generation":1,"users":[],"libraries":[]}`},
	{http.MethodPost, "/api/integrations/plex/connections/1/previews", ""},
	{http.MethodGet, "/api/integrations/plex/connections/1/previews/x", ""},
	{http.MethodPost, "/api/integrations/plex/connections/1/runs", `{"previewId":"x","enableSync":false}`},
	{http.MethodGet, "/api/integrations/plex/connections/1/runs", ""},
	{http.MethodGet, "/api/integrations/plex/connections/1/runs/1", ""},
	{http.MethodPost, "/api/integrations/plex/connections/1/runs/1/cancel", ""},
	{http.MethodPost, "/api/integrations/plex/connections/1/runs/1/retry", ""},
	{http.MethodPut, "/api/integrations/plex/connections/1/sync", `{"generation":1,"enabled":false}`},
	{http.MethodGet, "/api/integrations/plex/connections/1/exclusions", ""},
	{http.MethodPut, "/api/integrations/plex/connections/1/exclusions", `{"generation":1,"items":[]}`},
	{http.MethodPost, "/api/integrations/plex/connections/1/runs/1/undo-previews", ""},
	{http.MethodPost, "/api/integrations/plex/connections/1/runs/1/undo", `{"previewId":"x"}`},
	{http.MethodPost, "/api/integrations/plex/connections/1/rebaseline", `{"generation":1}`},
}

// Every Plex route is for admins on an admin network.
func TestPlexRoutesAreForAdminsOnAdminNetworks(t *testing.T) {
	t.Parallel()
	srv, _ := newPlexTestServer(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		admin, _ := netguard.Parse([]string{"local"})
		d.Net = netguard.New(trusted, admin)
	})
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"kid","password":"kidpass1"}`, nil).StatusCode; code != http.StatusCreated {
		t.Fatalf("create user -> %d", code)
	}
	jar, _ := cookiejar.New(nil)
	kid := &http.Client{Jar: jar}
	if code := doJSON(t, kid, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"kid","password":"kidpass1"}`, nil).StatusCode; code != http.StatusOK {
		t.Fatalf("kid login -> %d", code)
	}
	for _, r := range plexRoutes {
		if got := doJSON(t, kid, r.method, srv.URL+r.path, r.body, nil); got.StatusCode != http.StatusForbidden {
			t.Errorf("member %s %s -> %d", r.method, r.path, got.StatusCode)
		}
		if got := doJSON(t, srv.client, r.method, srv.URL+r.path, r.body, nil, "X-Forwarded-For", "203.0.113.9"); got.StatusCode != http.StatusForbidden {
			t.Errorf("admin from the internet %s %s -> %d", r.method, r.path, got.StatusCode)
		}
		if got := doJSON(t, nil, r.method, srv.URL+r.path, r.body, nil); got.StatusCode != http.StatusUnauthorized {
			t.Errorf("signed out %s %s -> %d", r.method, r.path, got.StatusCode)
		}
	}
}

func TestPlexRoutesOffWithoutTheService(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	if got := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/integrations/plex/connections", "", nil); got.StatusCode != http.StatusNotFound {
		t.Fatalf("-> %d", got.StatusCode)
	}
}

// Connecting, listing people, mapping and previewing through the routes;
// no reply carries a Plex credential.
func TestPlexConnectThroughTheAPI(t *testing.T) {
	t.Parallel()
	srv, f := newPlexTestServer(t, nil)
	ctx := t.Context()
	viewer, err := srv.q.CreateUser(ctx, dbCreateUser("viewer"))
	if err != nil {
		t.Fatal(err)
	}
	var bodies []string
	call := func(method, path, body string, out any, want int) {
		t.Helper()
		got := doJSON(t, srv.client, method, srv.URL+path, body, out)
		bodies = append(bodies, string(got.Body))
		if got.StatusCode != want {
			t.Fatalf("%s %s -> %d %s; want %d", method, path, got.StatusCode, got.Body, want)
		}
	}
	var in plexsync.PlexSignIn
	call(http.MethodPost, "/api/integrations/plex/auth", "", &in, http.StatusCreated)
	if in.Status != "pending" || in.AuthURL == "" {
		t.Fatalf("sign-in = %+v", in)
	}
	f.Set(func(f *plextest.Fake) { f.SignedIn = true })
	call(http.MethodGet, "/api/integrations/plex/auth/"+in.ID, "", &in, http.StatusOK)
	if in.Status != "ready" || len(in.Servers) != 1 {
		t.Fatalf("sign-in = %+v", in)
	}
	var c plexsync.PlexConnection
	call(http.MethodPost, "/api/integrations/plex/connections",
		fmt.Sprintf(`{"signInId":%q,"machineIdentifier":%q,"origin":%q}`, in.ID, f.Machine, in.Servers[0].Addresses[0].URL), &c, http.StatusCreated)
	if c.Status != "connected" || c.SyncEnabled {
		t.Fatalf("connection = %+v", c)
	}
	var users PlexUserList
	call(http.MethodGet, fmt.Sprintf("/api/integrations/plex/connections/%d/users", c.ID), "", &users, http.StatusOK)
	kinds := map[int64]string{}
	for _, u := range users.Users {
		kinds[u.PlexUserID] = u.Kind + " " + u.Status
	}
	if kinds[706] != "managed ready" || kinds[707] != "managed needs_pin" || kinds[701] != "owner ready" {
		t.Fatalf("users = %v", kinds)
	}
	var libs PlexLibraryList
	call(http.MethodGet, fmt.Sprintf("/api/integrations/plex/connections/%d/libraries", c.ID), "", &libs, http.StatusOK)
	if len(libs.Libraries) != 2 {
		t.Fatalf("libraries = %+v; want the movie and TV ones", libs.Libraries)
	}
	movies, _ := srv.q.ListLibraries(ctx)
	mapping := fmt.Sprintf(`{"generation":%d,"users":[{"plexUserId":706,"userId":%d}],"libraries":[{"sectionId":1,"libraryId":%d}]}`, users.Generation, viewer.ID, movies[0].ID)
	call(http.MethodPut, fmt.Sprintf("/api/integrations/plex/connections/%d/mappings", c.ID), mapping, &c, http.StatusOK)
	if c.LinkedUsers != 1 || c.LinkedLibraries != 1 {
		t.Fatalf("connection = %+v", c)
	}
	call(http.MethodPut, fmt.Sprintf("/api/integrations/plex/connections/%d/mappings", c.ID), mapping, nil, http.StatusConflict)
	locked := fmt.Sprintf(`{"generation":%d,"users":[{"plexUserId":707,"userId":%d}],"libraries":[]}`, c.Generation, viewer.ID)
	call(http.MethodPut, fmt.Sprintf("/api/integrations/plex/connections/%d/mappings", c.ID), locked, nil, http.StatusUnprocessableEntity)
	var p plexsync.PlexPreview
	call(http.MethodPost, fmt.Sprintf("/api/integrations/plex/connections/%d/previews", c.ID), "", &p, http.StatusAccepted)
	var again plexsync.PlexPreview
	call(http.MethodGet, fmt.Sprintf("/api/integrations/plex/connections/%d/previews/%s", c.ID, p.ID), "", &again, http.StatusOK)
	if again.ID != p.ID {
		t.Fatalf("preview = %+v", again)
	}
	call(http.MethodDelete, fmt.Sprintf("/api/integrations/plex/connections/%d", c.ID), "", nil, http.StatusNoContent)
	call(http.MethodGet, fmt.Sprintf("/api/integrations/plex/connections/%d/previews/%s", c.ID, p.ID), "", nil, http.StatusNotFound)
	all := strings.Join(bodies, "\n")
	for _, secret := range []string{"synthetic-", "example.invalid"} {
		if strings.Contains(all, secret) {
			t.Errorf("a reply carries %q", secret)
		}
	}
	if len(f.Pins()) != 0 {
		t.Error("a PIN was sent")
	}
}

// A sign-in finishes only in the session that started it, even for the
// same admin.
func TestPlexSignInStaysWithItsSession(t *testing.T) {
	t.Parallel()
	srv, _ := newPlexTestServer(t, nil)
	var in plexsync.PlexSignIn
	if got := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/integrations/plex/auth", "", &in); got.StatusCode != http.StatusCreated {
		t.Fatalf("-> %d", got.StatusCode)
	}
	jar, _ := cookiejar.New(nil)
	other := &http.Client{Jar: jar}
	if code := doJSON(t, other, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, nil).StatusCode; code != http.StatusOK {
		t.Fatalf("second session login -> %d", code)
	}
	if got := doJSON(t, other, http.MethodGet, srv.URL+"/api/integrations/plex/auth/"+in.ID, "", nil); got.StatusCode != http.StatusNotFound {
		t.Fatalf("another session read the sign-in -> %d", got.StatusCode)
	}
	if got := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/integrations/plex/auth/"+in.ID, "", nil); got.StatusCode != http.StatusOK {
		t.Fatalf("its own session -> %d", got.StatusCode)
	}
}

func dbCreateUser(name string) db.CreateUserParams {
	return db.CreateUserParams{Name: name, PasswordHash: "x"}
}

func TestPlexHistoryIsPrivateAndScopedTokensCannotManageImports(t *testing.T) {
	t.Parallel()
	srv, _ := newPlexTestServer(t, castServerDeps)
	var user struct {
		ID int64 `json:"id"`
	}
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"private-viewer","password":"viewerpass1"}`, &user).StatusCode; code != http.StatusCreated {
		t.Fatal(code)
	}
	jar, _ := cookiejar.New(nil)
	viewer := &http.Client{Jar: jar}
	if code := doJSON(t, viewer, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"private-viewer","password":"viewerpass1"}`, nil).StatusCode; code != http.StatusOK {
		t.Fatal(code)
	}
	for _, r := range []struct{ method, path string }{{http.MethodGet, "/api/plex-history?userId=1"}, {http.MethodDelete, "/api/plex-history?userId=1"}, {http.MethodDelete, "/api/plex-history/1?userId=1"}} {
		if got := doJSON(t, viewer, r.method, srv.URL+r.path, "", nil).StatusCode; got != http.StatusForbidden {
			t.Errorf("member cross-user %s %s -> %d", r.method, r.path, got)
		}
		if got := doJSON(t, srv.client, r.method, srv.URL+strings.ReplaceAll(r.path, "userId=1", fmt.Sprintf("userId=%d", user.ID)), "", nil, "X-Forwarded-For", "203.0.113.9").StatusCode; got != http.StatusForbidden {
			t.Errorf("offnetwork admin %s -> %d", r.path, got)
		}
	}
	if code := doJSON(t, viewer, http.MethodGet, srv.URL+"/api/plex-history", "", nil).StatusCode; code != http.StatusOK {
		t.Fatalf("own history -> %d", code)
	}
	var ct CastToken
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/auth/cast-token", "", &ct).StatusCode; code != http.StatusOK {
		t.Fatal(code)
	}
	var mt MediaToken
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/auth/media-token", "", &mt).StatusCode; code != http.StatusOK {
		t.Fatal(code)
	}
	for _, token := range []string{ct.Token, mt.Token} {
		for _, r := range append(plexRoutes, struct{ method, path, body string }{http.MethodGet, "/api/plex-history", ""}) {
			if got := doJSON(t, nil, r.method, srv.URL+r.path, r.body, nil, "Authorization", "Bearer "+token).StatusCode; got != http.StatusUnauthorized {
				t.Errorf("scoped token %s %s -> %d", r.method, r.path, got)
			}
		}
	}
}
