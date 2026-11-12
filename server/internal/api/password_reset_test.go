package api

import (
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"testing"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/netguard"
)

func TestResetUserPassword(t *testing.T) {
	t.Parallel()
	// Both members and other admins can need a reset.
	for _, admin := range []bool{false, true} {
		t.Run(fmt.Sprintf("admin=%t", admin), func(t *testing.T) {
			t.Parallel()
			srv := newTestServer(t)
			var user auth.User
			if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", fmt.Sprintf(`{"name":"viewer","password":"old-password","isAdmin":%t}`, admin), &user).StatusCode; code != http.StatusCreated {
				t.Fatalf("create -> %d", code)
			}
			jar, _ := cookiejar.New(nil)
			browser := &http.Client{Jar: jar}
			if code := doJSON(t, browser, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"viewer","password":"old-password"}`, nil).StatusCode; code != http.StatusOK {
				t.Fatalf("browser login -> %d", code)
			}
			device := deviceLogin(t, srv.URL, "viewer", "old-password", "phone-1", "Viewer's phone")
			var media MediaToken
			if code := doJSON(t, browser, http.MethodPost, srv.URL+"/api/auth/media-token", "", &media).StatusCode; code != http.StatusOK {
				t.Fatalf("media token -> %d", code)
			}
			var pair PairingStart
			if code := doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/pair", `{"client":{"deviceId":"tv-1","name":"Test TV"}}`, &pair).StatusCode; code != http.StatusOK {
				t.Fatalf("pair -> %d", code)
			}
			if code := doJSON(t, browser, http.MethodPost, srv.URL+"/api/auth/pair/"+pair.Code+"/approve", "", nil).StatusCode; code != http.StatusOK {
				t.Fatalf("approve -> %d", code)
			}
			before, err := srv.q.GetUser(t.Context(), user.ID)
			if err != nil {
				t.Fatal(err)
			}
			path := fmt.Sprintf("%s/api/users/%d/password", srv.URL, user.ID)
			if code := doJSON(t, srv.client, http.MethodPut, path, `{"password":"new-password"}`, nil).StatusCode; code != http.StatusNoContent {
				t.Fatalf("reset -> %d", code)
			}
			for name, client := range map[string]*http.Client{"admin": srv.client, "viewer": browser} {
				want := http.StatusUnauthorized
				if name == "admin" {
					want = http.StatusOK
				}
				if code := doJSON(t, client, http.MethodGet, srv.URL+"/api/auth/me", "", nil).StatusCode; code != want {
					t.Errorf("%s browser -> %d, want %d", name, code, want)
				}
			}
			if code := doJSON(t, nil, http.MethodGet, srv.URL+"/api/auth/me", "", nil, "Authorization", "Bearer "+device.Token).StatusCode; code != http.StatusUnauthorized {
				t.Errorf("old device token -> %d", code)
			}
			if code := doJSON(t, nil, http.MethodGet, srv.URL+"/api/files/1/stream?access_token="+media.Token, "", nil).StatusCode; code != http.StatusUnauthorized {
				t.Errorf("old media token -> %d", code)
			}
			var claim PairingClaim
			if code := doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/pair/claim", `{"secret":"`+pair.Secret+`"}`, &claim).StatusCode; code != http.StatusNotFound {
				t.Errorf("pending device approval after reset -> %d, want 404", code)
			}
			if code := doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"viewer","password":"old-password"}`, nil).StatusCode; code != http.StatusUnauthorized {
				t.Errorf("old password -> %d", code)
			}
			after, err := srv.q.GetUser(t.Context(), user.ID)
			if err != nil {
				t.Fatal(err)
			}
			if before.ID != after.ID || before.Name != after.Name || before.IsAdmin != after.IsAdmin || before.RecordHistory != after.RecordHistory || before.CreatedAt != after.CreatedAt {
				t.Error("reset changed account details")
			}
			deviceLogin(t, srv.URL, "viewer", "new-password", "phone-1", "Viewer's phone")
		})
	}
}

func TestResetUserPasswordGuards(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		admin, _ := netguard.Parse([]string{netguard.Local})
		d.Net = netguard.New(trusted, admin)
	})
	var viewer auth.User
	doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"viewer","password":"old-password"}`, &viewer)
	member := deviceLogin(t, srv.URL, "viewer", "old-password", "phone-1", "Viewer's phone")
	path := fmt.Sprintf("%s/api/users/%d/password", srv.URL, viewer.ID)
	for _, tc := range []struct {
		name, path, body string
		client           *http.Client
		headers          []string
		want             int
	}{
		{"anonymous", path, `{}`, nil, nil, http.StatusUnauthorized},
		{"member", path, `{}`, nil, []string{"Authorization", "Bearer " + member.Token}, http.StatusForbidden},
		{"off network", path, `{}`, srv.client, []string{"X-Forwarded-For", "203.0.113.9"}, http.StatusForbidden},
		{"cross site", path, `{"password":"new-password"}`, srv.client, []string{"Origin", "https://other.example", "Sec-Fetch-Site", "cross-site"}, http.StatusForbidden},
		{"self", srv.URL + "/api/users/1/password", `{"password":"new-password"}`, srv.client, nil, http.StatusConflict},
		{"missing user", srv.URL + "/api/users/9999/password", `{"password":"new-password"}`, srv.client, nil, http.StatusNotFound},
		{"missing password", path, `{}`, srv.client, nil, http.StatusUnprocessableEntity},
		{"short password", path, `{"password":"short"}`, srv.client, nil, http.StatusUnprocessableEntity},
		{"long password", path, `{"password":"` + strings.Repeat("a", 257) + `"}`, srv.client, nil, http.StatusUnprocessableEntity},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if res := doJSON(t, tc.client, http.MethodPut, tc.path, tc.body, nil, tc.headers...); res.StatusCode != tc.want {
				t.Errorf("reset -> %d, want %d: %s", res.StatusCode, tc.want, res.Body)
			}
		})
	}
	// Refused requests must leave both existing sessions and credentials intact.
	if code := doJSON(t, nil, http.MethodGet, srv.URL+"/api/auth/me", "", nil, "Authorization", "Bearer "+member.Token).StatusCode; code != http.StatusOK {
		t.Errorf("member session after refused resets -> %d", code)
	}
	deviceLogin(t, srv.URL, "viewer", "old-password", "phone-2", "Second phone")
	deviceLogin(t, srv.URL, "admin", "password1", "admin-phone", "Admin's phone")
}
