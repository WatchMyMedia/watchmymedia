package api

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"net/http/httptest"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/scanner"
)

var remoteHTTPS = []string{"X-Forwarded-For", "198.51.100.7", "X-Forwarded-Proto", "https"}

func remoteAdminDeps(d *Deps) {
	proxies, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
	local, _ := netguard.Parse([]string{"local"})
	d.Net = netguard.New(proxies, local)
}

func TestRemoteAdminAccessAndStatus(t *testing.T) {
	t.Parallel()
	var guard *netguard.Guard
	srv := newTestServerWith(t, func(d *Deps) { remoteAdminDeps(d); guard = d.Net })
	for _, on := range []bool{true, false} {
		guard.SetRemoteAdmin(on)
		for _, tc := range []struct {
			name          string
			headers       []string
			allowed, home bool
			limit         string
		}{
			{"home", nil, true, true, ""},
			{"away HTTPS", remoteHTTPS, on, false, map[bool]string{true: "", false: "network"}[on]},
			{"away HTTP", remoteHTTPS[:2], false, false, map[bool]string{true: "https", false: "network"}[on]},
		} {
			var st AuthStatus
			doJSON(t, srv.client, "GET", srv.URL+"/api/auth/status", "", &st, tc.headers...)
			if st.AdminAllowed != tc.allowed || st.OnAdminNetwork == nil || *st.OnAdminNetwork != tc.home || st.AdminLimit != tc.limit {
				t.Fatalf("%s on=%v: %+v", tc.name, on, st)
			}
			res := doJSON(t, srv.client, "GET", srv.URL+"/api/users", "", nil, tc.headers...)
			want := http.StatusForbidden
			if tc.allowed {
				want = http.StatusOK
			}
			if res.StatusCode != want {
				t.Fatalf("%s users: %d", tc.name, res.StatusCode)
			}
		}
	}
	guard.SetRemoteAdmin(true)
	// Public visitors and members receive only the setup network check.
	for _, member := range []bool{false, true} {
		var client *http.Client
		if member {
			if _, err := srv.q.CreateUser(t.Context(), db.CreateUserParams{Name: "Member", PasswordHash: adminHash()}); err != nil {
				t.Fatal(err)
			}
			// Use an ordinary device login, without relying on browser cookie state.
			var sign SignIn
			r := doJSON(t, nil, "POST", srv.URL+"/api/auth/login", `{"name":"Member","password":"password1","client":{"name":"Fixture","deviceId":"remote-member"}}`, &sign)
			if r.StatusCode != 200 {
				t.Fatalf("member login: %s", r.Body)
			}
			client = &http.Client{Transport: forwarding{"Authorization": "Bearer " + sign.Token}}
		}
		var raw map[string]json.RawMessage
		doJSON(t, client, "GET", srv.URL+"/api/auth/status", "", &raw, remoteHTTPS...)
		for _, key := range []string{"adminLimit", "onAdminNetwork"} {
			if _, ok := raw[key]; ok {
				t.Fatalf("%s leaked to member=%v", key, member)
			}
		}
		if string(raw["adminAllowed"]) != "false" {
			t.Fatal("visitor was offered remote setup")
		}
	}
	if err := srv.q.DeleteUser(t.Context(), 1); err != nil {
		t.Fatal(err)
	}
	if err := srv.q.DeleteUser(t.Context(), 2); err != nil {
		t.Fatal(err)
	}
	if r := doJSON(t, nil, "POST", srv.URL+"/api/setup", `{"name":"Intruder","password":"fictional-password"}`, nil, remoteHTTPS...); r.StatusCode != 403 {
		t.Fatalf("remote HTTPS setup: %d", r.StatusCode)
	}
}

func TestEveryRemoteGuardAndConditionalChanges(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, remoteAdminDeps)
	folder := t.TempDir()
	cases := []struct{ method, path, body string }{
		{"POST", "/users", `{"name":"Other admin","password":"fictional-password","isAdmin":true}`},
		{"PUT", "/users/2/password", `{"password":"fictional-password"}`},
		{"DELETE", "/users/2", ""}, {"POST", "/backups", ""}, {"POST", "/backups/download", ""}, {"GET", "/backups/download", ""},
		{"PUT", "/settings", `{"turnstile":{"siteKey":""}}`},
		{"POST", "/libraries", fmt.Sprintf(`{"type":"movie","name":"Meadow","path":%q}`, folder)},
		{"PUT", "/libraries/1", fmt.Sprintf(`{"name":"Movies","path":%q}`, folder)},
		{"POST", "/integrations/plex/auth", ""},
		{"POST", "/integrations/plex/connections", `{"signInId":"0123456789abcdef","machineIdentifier":"fictional","origin":"http://plex.test:32400"}`},
		{"DELETE", "/integrations/plex/connections/1", ""},
		{"POST", "/settings/live-tv/test", `{"address":"tuner.test"}`},
		{"POST", "/settings/live-tv/sources", `{"address":"tuner.test"}`},
		{"POST", "/integrations", `{"kind":"radarr","name":"Fictional","url":"http://radarr.test","apiKey":"fictional-key"}`},
		{"PATCH", "/integrations/1", `{"name":"Fictional","url":"http://radarr.test"}`},
		{"DELETE", "/integrations/1", ""},
		{"POST", "/integrations/test", `{"kind":"radarr","url":"http://radarr.test","apiKey":"fictional-key"}`},
		{"POST", "/integrations/1/webhook/connect", `{"address":"https://fixture.test"}`},
	}
	for _, tc := range cases {
		r := doJSON(t, srv.client, tc.method, srv.URL+"/api"+tc.path, tc.body, nil, remoteHTTPS...)
		var problem struct{ Type string }
		_ = json.Unmarshal(r.Body, &problem)
		if r.StatusCode != 403 || problem.Type != confirmPasswordType {
			t.Errorf("%s %s: %d %s", tc.method, tc.path, r.StatusCode, r.Body)
		}
	}
	// Renaming a library keeps its folder and does not ask for confirmation.
	if _, err := srv.q.UpdateLibrary(t.Context(), db.UpdateLibraryParams{ID: 1, Name: "Movies", Path: folder}); err != nil {
		t.Fatal(err)
	}
	r := doJSON(t, srv.client, "PUT", srv.URL+"/api/libraries/1", fmt.Sprintf(`{"name":"Renamed","path":%q}`, folder), nil, remoteHTTPS...)
	if r.StatusCode != 200 {
		t.Fatalf("rename: %d %s", r.StatusCode, r.Body)
	}
	// Creating a member and changing ordinary settings remain unguarded.
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/users", `{"name":"Member","password":"fictional-password"}`, nil, remoteHTTPS...); r.StatusCode != 201 {
		t.Fatal(string(r.Body))
	}
	if r := doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"backupKeep":5}`, nil, remoteHTTPS...); r.StatusCode != 200 {
		t.Fatal(string(r.Body))
	}
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/auth/confirm", `{"password":"password1"}`, nil, remoteHTTPS...); r.StatusCode != 204 {
		t.Fatalf("confirm: %s", r.Body)
	}
	if r := doJSON(t, srv.client, "PUT", srv.URL+"/api/users/2/password", `{"password":"new-fictional-password"}`, nil, remoteHTTPS...); r.StatusCode != 204 {
		t.Fatalf("confirmed reset: %s", r.Body)
	}
	if r := doJSON(t, srv.client, "GET", srv.URL+"/api/backups/download", "", nil, remoteHTTPS...); r.StatusCode != 200 || !strings.Contains(r.Header.Get("Content-Type"), "gzip") {
		t.Fatalf("confirmed backup: %d", r.StatusCode)
	}
}

func TestRemoteTunerAddressGuard(t *testing.T) {
	t.Parallel()
	fake := hdhrtest.New(nil)
	tuner := httptest.NewServer(fake)
	defer tuner.Close()
	srv := newTestServerWith(t, func(d *Deps) {
		remoteAdminDeps(d)
		d.LiveTV = livetv.New(d.Queries, d.Settings, func(a string) (*hdhomerun.Client, error) { return hdhomerun.New(a, hdhrtest.Transport(tuner.URL)) })
	})
	var source livetv.Source
	r := doJSON(t, srv.client, "POST", srv.URL+"/api/settings/live-tv/sources", `{"address":"tuner.test"}`, &source)
	if r.StatusCode != 200 {
		t.Fatalf("source: %d %s", r.StatusCode, r.Body)
	}
	for _, address := range []string{source.Address, "other.test"} {
		body := fmt.Sprintf(`{"address":%q,"name":"Fictional tuner","enabled":true}`, address)
		r = doJSON(t, srv.client, "PUT", srv.URL+"/api/settings/live-tv/sources/"+source.ID, body, nil, remoteHTTPS...)
		want := 200
		if address != source.Address {
			want = 403
		}
		if r.StatusCode != want {
			t.Fatalf("address %s: %d %s", address, r.StatusCode, r.Body)
		}
	}
}

type auditBuffer struct {
	sync.Mutex
	b bytes.Buffer
}

func (b *auditBuffer) Write(p []byte) (int, error) { b.Lock(); defer b.Unlock(); return b.b.Write(p) }
func (b *auditBuffer) String() string              { b.Lock(); defer b.Unlock(); return b.b.String() }

func TestRemoteConfirmationThrottleAndAudit(t *testing.T) {
	t.Parallel()
	var logs auditBuffer
	srv := newTestServerWith(t, func(d *Deps) { remoteAdminDeps(d); d.Log = slog.New(slog.NewJSONHandler(&logs, nil)) })
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/auth/confirm", `{"password":"password1"}`, nil, remoteHTTPS...); r.StatusCode != 204 {
		t.Fatal(string(r.Body))
	}
	if r := doJSON(t, srv.client, "POST", srv.URL+"/api/users", `{"name":"Other","password":"fictional-secret","isAdmin":true}`, nil, remoteHTTPS...); r.StatusCode != 201 {
		t.Fatal(string(r.Body))
	}
	// A home action does not add an away audit line.
	doJSON(t, srv.client, "POST", srv.URL+"/api/users", `{"name":"Home","password":"fictional-secret","isAdmin":true}`, nil)
	saw429 := false
	for range 12 {
		r := doJSON(t, srv.client, "POST", srv.URL+"/api/auth/confirm", `{"password":"wrong-fictional-secret"}`, nil, remoteHTTPS...)
		if r.StatusCode == 429 {
			if r.Header.Get("Retry-After") == "" {
				t.Fatal("no Retry-After")
			}
			saw429 = true
			break
		}
		if r.StatusCode != 401 {
			t.Fatalf("wrong password: %d", r.StatusCode)
		}
	}
	if !saw429 {
		t.Fatal("confirmation not throttled")
	}
	doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"remoteAdmin":false}`, nil, remoteHTTPS...)
	out := logs.String()
	for _, msg := range []string{"password confirmed away from home", "password confirmation failed away from home", "password confirmation throttled away from home", "remote admin turned off"} {
		if !strings.Contains(out, msg) {
			t.Error("missing " + msg)
		}
	}
	if strings.Count(out, "admin action away from home") != 1 {
		t.Fatalf("wrong action log count: %s", out)
	}
	for _, secret := range []string{"password1", "fictional-secret", "wrong-fictional-secret", "passwordHash"} {
		if strings.Contains(out, secret) {
			t.Fatal("secret in audit log")
		}
	}
	if !strings.Contains(out, `"user_id":1`) || !strings.Contains(out, `"ip":"198.51.100.7"`) || !strings.Contains(out, `"operation":"createUser"`) || !strings.Contains(out, `"status":201`) {
		t.Fatal("missing audit fields")
	}
}

func TestRemoteAdminSwitchAndOpenEvents(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, remoteAdminDeps)
	ctx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, "GET", srv.URL+"/api/events", nil)
	for i := 0; i < len(remoteHTTPS); i += 2 {
		req.Header.Set(remoteHTTPS[i], remoteHTTPS[i+1])
	}
	res, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	lines := bufio.NewScanner(res.Body)
	read := func() string {
		t.Helper()
		for lines.Scan() {
			if strings.HasPrefix(lines.Text(), "data: ") {
				return lines.Text()
			}
		}
		t.Fatalf("events ended: %v", lines.Err())
		return ""
	}
	read()
	if r := doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"remoteAdmin":true}`, nil, remoteHTTPS...); r.StatusCode != 403 {
		t.Fatal("enabled away from home")
	}
	if r := doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", `{"remoteAdmin":false}`, nil, remoteHTTPS...); r.StatusCode != 200 {
		t.Fatal(string(r.Body))
	}
	if r := doJSON(t, srv.client, "GET", srv.URL+"/api/settings", "", nil, remoteHTTPS...); r.StatusCode != 403 {
		t.Fatal("switch did not take effect")
	}
	srv.bus.Publish(events.Event{Type: "scan.started", Data: scanner.ScanStarted{LibraryID: 991}, To: &events.Audience{}})
	srv.bus.Publish(events.Event{Type: "watch.changed", Data: WatchChanged{ItemType: "movie", ItemIDs: []int64{992}}, To: &events.Audience{UserID: 1, Private: true}})
	if got := read(); strings.Contains(got, "991") || !strings.Contains(got, "992") {
		t.Fatalf("household event after switch off: %s", got)
	}
}

func TestRemoteAdminConfigWins(t *testing.T) {
	t.Parallel()
	for _, enabled := range []bool{true, false} {
		srv := newTestServerWith(t, func(d *Deps) { remoteAdminDeps(d); d.RemoteAdminConfig = &enabled; d.Net.SetRemoteAdmin(enabled) })
		var settings Settings
		doJSON(t, srv.client, "GET", srv.URL+"/api/settings", "", &settings)
		if settings.RemoteAdmin == nil || !settings.RemoteAdmin.FromConfig || settings.RemoteAdmin.Enabled != enabled {
			t.Fatal("config status mismatch")
		}
		if r := doJSON(t, srv.client, "PUT", srv.URL+"/api/settings", fmt.Sprintf(`{"remoteAdmin":%v}`, !enabled), nil); r.StatusCode != 409 {
			t.Fatal("config override changed")
		}
	}
}
