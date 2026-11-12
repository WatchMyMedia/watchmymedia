package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
)

// forwarding adds a reverse proxy's headers to every request, as nginx or
// Caddy on this machine would; the test client connects over loopback.
type forwarding map[string]string

func (f forwarding) RoundTrip(r *http.Request) (*http.Response, error) {
	r = r.Clone(r.Context())
	for k, v := range f {
		r.Header.Set(k, v)
	}
	return http.DefaultTransport.RoundTrip(r)
}

// throughProxy is the signed-in test client behind a proxy with headers h.
func throughProxy(srv *testEnv, h forwarding) *http.Client {
	return &http.Client{Jar: srv.client.Jar, Transport: h}
}

// A proxy on this machine that is not trusted cannot reach the menu bar
// app's status route for its clients; the app itself still can.
func TestLocalStatusThroughUntrustedProxy(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	for _, h := range []forwarding{
		{"X-Forwarded-For": "203.0.113.9"},
		{"Forwarded": "for=203.0.113.9"},
		{"X-Real-IP": "203.0.113.9"},
		{"X-Forwarded-For": "127.0.0.1"}, // a client may claim anything
	} {
		res, err := throughProxy(srv, h).Get(srv.URL + "/api/playback/local")
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		if res.StatusCode != http.StatusForbidden {
			t.Errorf("via %v -> %d", h, res.StatusCode)
		}
	}
	res, err := http.Get(srv.URL + "/api/playback/local")
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK {
		t.Errorf("menu bar app -> %d", res.StatusCode)
	}
}

// Trusted, the proxy's clients are who it says: the internet is refused,
// and a request it relays from this machine is loopback.
func TestLocalStatusThroughTrustedProxy(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		admin, _ := netguard.Parse([]string{netguard.Local})
		d.Net = netguard.New(trusted, admin)
	})
	for h, want := range map[string]int{"203.0.113.9": http.StatusForbidden, "192.168.1.5": http.StatusForbidden, "127.0.0.1": http.StatusOK} {
		res, err := throughProxy(srv, forwarding{"X-Forwarded-For": h}).Get(srv.URL + "/api/playback/local")
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		if res.StatusCode != want {
			t.Errorf("for %s -> %d, want %d", h, res.StatusCode, want)
		}
	}
}

// Admin settings over plain HTTP and first-run setup refuse what an
// untrusted proxy relays, though the connection comes from loopback.
func TestAdminThroughUntrustedProxy(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	proxied := throughProxy(srv, forwarding{"X-Forwarded-For": "203.0.113.9"})
	if code := doJSON(t, proxied, http.MethodGet, srv.URL+"/api/libraries", "", nil).StatusCode; code != http.StatusOK {
		t.Errorf("libraries through proxy -> %d", code)
	}
	if code := doJSON(t, proxied, http.MethodGet, srv.URL+"/api/users", "", nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("users through proxy -> %d", code)
	}
	var st AuthStatus
	doJSON(t, proxied, http.MethodGet, srv.URL+"/api/auth/status", "", &st)
	if st.AdminAllowed {
		t.Error("status through proxy: admin allowed")
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/users", "", nil).StatusCode; code != http.StatusOK {
		t.Errorf("users on this machine -> %d", code)
	}

	// The proxy says the visit was HTTPS. It is on this machine, so its
	// word is taken: the admin is an admin away from home, asked for the
	// password before guarded actions, and never on the home network.
	secure := throughProxy(srv, forwarding{"X-Forwarded-For": "203.0.113.9", "X-Forwarded-Proto": "https"})
	if code := doJSON(t, secure, http.MethodGet, srv.URL+"/api/users", "", nil).StatusCode; code != http.StatusOK {
		t.Errorf("users through proxy over https -> %d", code)
	}
	st = AuthStatus{}
	doJSON(t, secure, http.MethodGet, srv.URL+"/api/auth/status", "", &st)
	if !st.AdminAllowed || st.AdminLimit != "" || st.OnAdminNetwork == nil || *st.OnAdminNetwork {
		t.Errorf("status through proxy over https: %+v", st)
	}
	if code := doJSON(t, secure, http.MethodPost, srv.URL+"/api/backups", "", nil).StatusCode; code != http.StatusForbidden {
		t.Errorf("backup through proxy over https without the password -> %d", code)
	}

	if err := srv.q.DeleteUser(t.Context(), 1); err != nil {
		t.Fatal(err)
	}
	for _, c := range []*http.Client{proxied, secure} {
		if code := doJSON(t, c, http.MethodPost, srv.URL+"/api/setup", `{"name":"intruder","password":"password1"}`, nil).StatusCode; code != http.StatusForbidden {
			t.Errorf("setup through proxy -> %d", code)
		}
	}
	if n, _ := srv.q.CountUsers(t.Context()); n != 0 {
		t.Errorf("%d users after refused setup", n)
	}
}

// A play through an untrusted proxy is recorded with no address, as remote,
// rather than as the proxy's loopback address on the local network.
func TestPlayThroughUntrustedProxy(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		d.Activity = activity.New(d.Conn, d.Bus, nil, activity.Options{})
	})
	ctx := t.Context()
	path := filepath.Join(t.TempDir(), "Clip (2020).mp4")
	if err := os.WriteFile(path, []byte("0123456789abcdef"), 0o600); err != nil {
		t.Fatal(err)
	}
	probeJSON := `{"container":"mov","durationMs":600000,"video":[{"index":0,"codec":"h264","width":1280,"height":720}],"audio":[{"index":1,"codec":"aac","channels":2,"language":"eng","default":true}],"subtitles":[]}`
	dur := int64(600_000)
	f, err := srv.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: srv.fileLibrary(t, path), Path: path, Size: 16, Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()),
		Parsed: "{}", Probe: &probeJSON, DurationMs: &dur, LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	base := srv.URL + "/api"
	body := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"original"}`
	var d playback.Decision
	if code := doJSON(t, throughProxy(srv, forwarding{"X-Forwarded-For": "203.0.113.9"}), http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, f.ID), body, &d).StatusCode; code != 200 {
		t.Fatalf("playback through proxy -> %d", code)
	}
	var live LiveActivity
	doJSON(t, srv.client, http.MethodGet, base+"/activity", "", &live)
	if len(live.Plays) != 1 || live.Plays[0].IP != "" || live.Plays[0].Network != netguard.NetRemote {
		b, _ := json.Marshal(live.Plays)
		t.Errorf("live plays = %s", b)
	}
	// Automatic quality does not take the proxy's loopback address for a
	// client on the home network either.
	auto := `{"profile":{"containers":["mp4"],"videoCodecs":["h264"],"audioCodecs":["aac"]},"quality":"auto"}`
	if code := doJSON(t, throughProxy(srv, forwarding{"X-Forwarded-For": "203.0.113.9"}), http.MethodPost, fmt.Sprintf("%s/files/%d/playback", base, f.ID), auto, &d).StatusCode; code != 200 {
		t.Fatalf("automatic playback through proxy -> %d", code)
	}
	if d.Auto == nil || d.Auto.Step != "original" || d.Auto.Reason != "no measurement yet" {
		t.Errorf("automatic through proxy = %+v", d.Auto)
	}
}
