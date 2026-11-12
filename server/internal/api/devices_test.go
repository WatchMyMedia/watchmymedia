package api

import (
	"encoding/json"
	"net/http"
	"strings"
	"testing"
)

func deviceLogin(t *testing.T, base, name, password, deviceID, deviceName string) SignIn {
	t.Helper()
	var in SignIn
	body := `{"name":"` + name + `","password":"` + password + `","client":{"deviceId":"` + deviceID + `","name":"` + deviceName + `","product":"WatchMyMedia for Android TV","platform":"androidtv","version":"1.0.0"}}`
	res := doJSON(t, nil, http.MethodPost, base+"/api/auth/login", body, &in)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("device login -> %d", res.StatusCode)
	}
	if c := res.Header.Get("Set-Cookie"); c != "" {
		t.Errorf("device login set a cookie: %q", c)
	}
	if len(in.Token) != 64 || in.ExpiresAt == nil || len(in.SessionID) != 16 || in.Name != name {
		t.Fatalf("device sign-in = %+v", in)
	}
	return in
}

func TestDeviceSignInAndSessions(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	base := srv.URL
	// A browser sign-in gets a cookie and no token in the body.
	var browser SignIn
	res := doJSON(t, nil, http.MethodPost, base+"/api/auth/login", `{"name":"admin","password":"password1"}`, &browser)
	if res.Header.Get("Set-Cookie") == "" || browser.Token != "" || browser.ExpiresAt != nil || browser.SessionID == "" {
		t.Errorf("browser sign-in = %+v, cookie %q", browser, res.Header.Get("Set-Cookie"))
	}

	tv := deviceLogin(t, base, "admin", "password1", "tv-1", "Living room TV")
	var me struct{ Name string }
	if res := doJSON(t, nil, http.MethodGet, base+"/api/auth/me", "", &me, "Authorization", "Bearer "+tv.Token); res.StatusCode != 200 || me.Name != "admin" {
		t.Errorf("me with bearer -> %d %+v", res.StatusCode, me)
	}
	var mt MediaToken
	if res := doJSON(t, nil, http.MethodPost, base+"/api/auth/media-token", "", &mt, "Authorization", "Bearer "+tv.Token); res.StatusCode != 200 || mt.Token == "" {
		t.Errorf("media token with bearer -> %d", res.StatusCode)
	}
	var list SessionList
	doJSON(t, nil, http.MethodGet, base+"/api/auth/sessions", "", &list, "Authorization", "Bearer "+tv.Token)
	var found bool
	for _, s := range list.Items {
		if s.ID == tv.SessionID {
			found = true
			if !s.Current || s.Kind != "device" || s.Name != "Living room TV" || s.Platform != "androidtv" || s.Product != "WatchMyMedia for Android TV" || s.DeviceID != "tv-1" || s.LastIP == "" {
				t.Errorf("device session = %+v", s)
			}
		} else if s.Current {
			t.Errorf("another session marked current: %+v", s)
		}
	}
	if !found || len(list.Items) < 3 { // the test client's, the browser's, the TV's
		t.Errorf("sessions = %+v", list.Items)
	}

	// Another user sees only their own sessions and cannot touch the admin's.
	res = doJSON(t, srv.client, http.MethodPost, base+"/api/users", `{"name":"kid","password":"kidpass1"}`, nil)
	kid := deviceLogin(t, base, "kid", "kidpass1", "phone-1", "Kid's phone")
	var kids SessionList
	doJSON(t, nil, http.MethodGet, base+"/api/auth/sessions", "", &kids, "Authorization", "Bearer "+kid.Token)
	if len(kids.Items) != 1 || kids.Items[0].ID != kid.SessionID || kids.Items[0].UserName != "kid" {
		t.Errorf("kid's sessions = %+v", kids.Items)
	}
	if res := doJSON(t, nil, http.MethodGet, base+"/api/auth/sessions?all=true", "", nil, "Authorization", "Bearer "+kid.Token); res.StatusCode != http.StatusForbidden {
		t.Errorf("kid listing everyone -> %d", res.StatusCode)
	}
	if res := doJSON(t, nil, http.MethodDelete, base+"/api/auth/sessions/"+tv.SessionID, "", nil, "Authorization", "Bearer "+kid.Token); res.StatusCode != http.StatusNotFound {
		t.Errorf("kid revoking the admin's TV -> %d", res.StatusCode)
	}
	if res := doJSON(t, nil, http.MethodDelete, base+"/api/auth/sessions/0123456789abcdef", "", nil, "Authorization", "Bearer "+tv.Token); res.StatusCode != http.StatusNotFound {
		t.Errorf("unknown session -> %d", res.StatusCode)
	}

	// Admins see and revoke everyone's.
	var all SessionList
	doJSON(t, nil, http.MethodGet, base+"/api/auth/sessions?all=true", "", &all, "Authorization", "Bearer "+tv.Token)
	var kidSeen bool
	for _, s := range all.Items {
		kidSeen = kidSeen || s.ID == kid.SessionID
	}
	if !kidSeen {
		t.Errorf("admin's list misses the kid's phone: %+v", all.Items)
	}
	if res := doJSON(t, nil, http.MethodDelete, base+"/api/auth/sessions/"+kid.SessionID, "", nil, "Authorization", "Bearer "+tv.Token); res.StatusCode != http.StatusNoContent {
		t.Errorf("admin revoking -> %d", res.StatusCode)
	}
	if res := doJSON(t, nil, http.MethodGet, base+"/api/auth/me", "", nil, "Authorization", "Bearer "+kid.Token); res.StatusCode != http.StatusUnauthorized {
		t.Errorf("revoked phone -> %d", res.StatusCode)
	}

	// Signing out with the bearer token ends that session only.
	if res := doJSON(t, nil, http.MethodPost, base+"/api/auth/logout", "", nil, "Authorization", "Bearer "+tv.Token); res.StatusCode != http.StatusNoContent {
		t.Errorf("bearer logout -> %d", res.StatusCode)
	}
	if res := doJSON(t, nil, http.MethodGet, base+"/api/auth/me", "", nil, "Authorization", "Bearer "+tv.Token); res.StatusCode != http.StatusUnauthorized {
		t.Errorf("signed-out TV -> %d", res.StatusCode)
	}
	if code := doJSON(t, srv.client, http.MethodGet, base+"/api/auth/me", "", &me).StatusCode; code != 200 {
		t.Errorf("the browser was signed out too -> %d", code)
	}
}

func TestChangePasswordKeepsThisSession(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	base := srv.URL
	tv := deviceLogin(t, base, "admin", "password1", "tv-1", "Living room TV")
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, base+"/api/auth/password", strings.NewReader(`{"current":"password1","new":"password2"}`))
	req.Header.Set("Content-Type", "application/json")
	res, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("change password -> %d", res.StatusCode)
	}
	var me struct{ Name string }
	if code := doJSON(t, srv.client, http.MethodGet, base+"/api/auth/me", "", &me).StatusCode; code != 200 {
		t.Errorf("the session that changed the password -> %d", code)
	}
	if res := doJSON(t, nil, http.MethodGet, base+"/api/auth/me", "", nil, "Authorization", "Bearer "+tv.Token); res.StatusCode != http.StatusUnauthorized {
		t.Errorf("other device after a password change -> %d", res.StatusCode)
	}
}

func TestPairingFlow(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	base := srv.URL
	var start PairingStart
	res := doJSON(t, nil, http.MethodPost, base+"/api/auth/pair", `{"client":{"deviceId":"tv-9","name":"Bedroom TV","product":"WatchMyMedia for Apple TV","platform":"tvos","version":"1.0.0","model":"Apple TV 4K"}}`, &start)
	if res.StatusCode != http.StatusOK || len(start.Code) != 6 || start.Secret == "" || start.IntervalMs != 2000 || start.LinkPath != "/link?code="+start.Code {
		t.Fatalf("start -> %d %+v", res.StatusCode, start)
	}
	claim := func() (int, PairingClaim) {
		var c PairingClaim
		res := doJSON(t, nil, http.MethodPost, base+"/api/auth/pair/claim", `{"secret":"`+start.Secret+`"}`, &c)
		return res.StatusCode, c
	}
	if code, c := claim(); code != 200 || c.Status != "pending" || c.Token != "" {
		t.Errorf("claim before approval -> %d %+v", code, c)
	}
	// Looking up and approving a code needs a signed-in person.
	if res := doJSON(t, nil, http.MethodGet, base+"/api/auth/pair/"+start.Code, "", nil); res.StatusCode != http.StatusUnauthorized {
		t.Errorf("anonymous lookup -> %d", res.StatusCode)
	}
	var info PairingInfo
	if code := doJSON(t, srv.client, http.MethodGet, base+"/api/auth/pair/"+strings.ToLower(start.Code), "", &info).StatusCode; code != 200 || info.Name != "Bedroom TV" || info.Platform != "tvos" || info.Model != "Apple TV 4K" || info.Approved {
		t.Errorf("lookup -> %d %+v", code, info)
	}
	res = doJSON(t, srv.client, http.MethodPost, base+"/api/auth/pair/"+start.Code+"/approve", "", nil)
	if res.StatusCode != http.StatusOK {
		t.Fatalf("approve -> %d", res.StatusCode)
	}
	res = doJSON(t, srv.client, http.MethodPost, base+"/api/auth/pair/"+start.Code+"/approve", "", nil)
	if res.StatusCode != http.StatusConflict {
		t.Errorf("approve twice -> %d", res.StatusCode)
	}
	code, c := claim()
	if code != 200 || c.Status != "approved" || len(c.Token) != 64 || c.User == nil || c.User.Name != "admin" || c.ExpiresAt == nil || c.SessionID == "" {
		t.Fatalf("claim after approval -> %d %+v", code, c)
	}
	var list SessionList
	doJSON(t, nil, http.MethodGet, base+"/api/auth/sessions", "", &list, "Authorization", "Bearer "+c.Token)
	var tv bool
	for _, s := range list.Items {
		if s.Current {
			tv = s.Kind == "device" && s.Name == "Bedroom TV" && s.DeviceID == "tv-9" && s.Model == "Apple TV 4K"
		}
	}
	if !tv {
		t.Errorf("paired session = %+v", list.Items)
	}
	if code, _ := claim(); code != http.StatusNotFound {
		t.Errorf("second claim -> %d", code)
	}
	if res := doJSON(t, nil, http.MethodGet, base+"/api/auth/pair/ZZZZZZ", "", nil, "Authorization", "Bearer "+c.Token); res.StatusCode != http.StatusNotFound {
		t.Errorf("unknown code -> %d", res.StatusCode)
	}
}

type renamer struct{ names []string }

func (r *renamer) Rename(name string) { r.names = append(r.names, name) }

func TestServerInfoAndName(t *testing.T) {
	t.Parallel()
	ren := &renamer{}
	srv := newTestServerWith(t, func(d *Deps) { d.Discovery = ren })
	var first, again ServerInfo
	if res := doJSON(t, nil, http.MethodGet, srv.URL+"/api/server", "", &first); res.StatusCode != 200 {
		t.Fatalf("anonymous server info -> %d", res.StatusCode)
	}
	doJSON(t, nil, http.MethodGet, srv.URL+"/api/server", "", &again)
	if len(first.ID) != 32 || first.ID != again.ID || first.APILevel != APILevel || first.Version != "test" || first.SetupRequired || first.Name == "" {
		t.Errorf("server info = %+v then %+v", first, again)
	}
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+"/api/settings", strings.NewReader(`{"serverName":"  Living room server "}`))
	req.Header.Set("Content-Type", "application/json")
	res, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var st Settings
	_ = json.NewDecoder(res.Body).Decode(&st)
	_ = res.Body.Close()
	if res.StatusCode != 200 || st.ServerName != "Living room server" || st.ServerNameDefault == "" {
		t.Errorf("settings -> %d %+v", res.StatusCode, st)
	}
	doJSON(t, nil, http.MethodGet, srv.URL+"/api/server", "", &again)
	if again.Name != "Living room server" || again.ID != first.ID {
		t.Errorf("renamed server info = %+v", again)
	}
	if len(ren.names) != 1 || ren.names[0] != "Living room server" {
		t.Errorf("re-announced as %v", ren.names)
	}
}

func TestOpenAPI30(t *testing.T) {
	t.Parallel()
	_, h := New(Deps{Version: "test"})
	b, err := OpenAPI30(h)
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(b, &doc); err != nil {
		t.Fatal(err)
	}
	if doc["openapi"] != "3.0.3" || strings.Contains(string(b), `"const"`) || !strings.Contains(string(b), `"/api/auth/pair/claim"`) {
		t.Errorf("3.0 document: openapi %v, const left %v", doc["openapi"], strings.Contains(string(b), `"const"`))
	}
}
