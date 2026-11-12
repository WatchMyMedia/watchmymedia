package api

import (
	"encoding/json"
	"net/http"
	"net/netip"
	"slices"
	"strings"
	"testing"
)

func castServerDeps(d *Deps) {
	d.Cast = CastOptions{Enabled: true, ReceiverOrigins: []string{"https://cast.example"}, URL: "https://media.example.com", Port: 27587}
}

func TestCastTokenAccess(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, castServerDeps)
	res, err := srv.client.Post(srv.URL+"/api/auth/cast-token", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	var ct CastToken
	_ = json.NewDecoder(res.Body).Decode(&ct)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK || ct.Token == "" || ct.Param != "access_token" || len(ct.URLs) == 0 || ct.URLs[0] != "https://media.example.com" {
		t.Fatalf("cast token -> %d %+v", res.StatusCode, ct)
	}
	anon := &http.Client{}
	do := func(method, path, body string, bearer bool) *http.Response {
		req, _ := http.NewRequestWithContext(t.Context(), method, srv.URL+path, strings.NewReader(body))
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		if bearer {
			req.Header.Set("Authorization", "Bearer "+ct.Token)
		}
		res, err := anon.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res
	}
	q := "?access_token=" + ct.Token
	for _, c := range []struct {
		method, path, body string
		bearer             bool
		open               bool
	}{
		{http.MethodGet, "/api/me/preferences", "", true, true},
		{http.MethodPost, "/api/files/1/playback", `{"profile":{"containers":["mkv"],"videoCodecs":["h264"]}}`, true, true},
		{http.MethodPut, "/api/files/1/progress", `{"positionMs":1000,"durationMs":5000}`, true, true},
		{http.MethodGet, "/api/files/1/stream" + q, "", false, true},
		{http.MethodGet, "/api/libraries", "", true, false},
		{http.MethodGet, "/api/libraries" + q, "", false, false},
		{http.MethodGet, "/api/auth/sessions", "", true, false},
		{http.MethodPost, "/api/auth/media-token", "", true, false},
		{http.MethodPut, "/api/me/preferences", `{}`, true, false},
		{http.MethodPost, "/api/files/1/playback" + q, `{"profile":{}}`, false, false},
	} {
		res := do(c.method, c.path, c.body, c.bearer)
		if open := res.StatusCode != http.StatusUnauthorized; open != c.open {
			t.Errorf("%s %s -> %d, want open=%t", c.method, c.path, res.StatusCode, c.open)
		}
	}
	// The receiver renews with the token it holds.
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodPost, srv.URL+"/api/auth/cast-token", nil)
	req.Header.Set("Authorization", "Bearer "+ct.Token)
	res, err = anon.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var renewed CastToken
	_ = json.NewDecoder(res.Body).Decode(&renewed)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK || renewed.Token == "" {
		t.Fatalf("renew -> %d %+v", res.StatusCode, renewed)
	}
	// Signing the sender out ends the cast.
	doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/auth/logout", "", nil)
	if res := do(http.MethodGet, "/api/me/preferences", "", true); res.StatusCode != http.StatusUnauthorized {
		t.Errorf("after the sender signed out -> %d", res.StatusCode)
	}
}

func TestCastOff(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	res, err := srv.client.Post(srv.URL+"/api/auth/cast-token", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusNotFound {
		t.Errorf("cast token with casting off -> %d", res.StatusCode)
	}
	res, _ = http.Get(srv.URL + "/api/server")
	var info ServerInfo
	_ = json.NewDecoder(res.Body).Decode(&info)
	_ = res.Body.Close()
	if info.Casting || info.CastAppID != "" {
		t.Errorf("server info with casting off: %+v", info)
	}
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodOptions, srv.URL+"/api/files/1/stream", nil)
	req.Header.Set("Origin", "https://cast.example")
	req.Header.Set("Access-Control-Request-Method", "GET")
	res, _ = http.DefaultClient.Do(req)
	_ = res.Body.Close()
	if res.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Error("CORS headers with casting off")
	}
}

func TestCastCORS(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, castServerDeps)
	send := func(method, path, origin string, hdr map[string]string) *http.Response {
		req, _ := http.NewRequestWithContext(t.Context(), method, srv.URL+path, nil)
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		for k, v := range hdr {
			req.Header.Set(k, v)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		return res
	}
	preflight := map[string]string{"Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "authorization,content-type"}
	// The receiver origin may call the playback routes with a bearer token.
	res := send(http.MethodOptions, "/api/files/1/playback", "https://cast.example", preflight)
	if res.StatusCode != http.StatusNoContent || res.Header.Get("Access-Control-Allow-Origin") != "https://cast.example" ||
		!strings.Contains(res.Header.Get("Access-Control-Allow-Headers"), "Authorization") || res.Header.Get("Access-Control-Allow-Credentials") != "" {
		t.Errorf("receiver preflight -> %d %v", res.StatusCode, res.Header)
	}
	// Another site gets nothing for the API.
	res = send(http.MethodOptions, "/api/files/1/playback", "https://evil.example", preflight)
	if res.Header.Get("Access-Control-Allow-Origin") != "" {
		t.Errorf("other origin preflight allowed: %v", res.Header)
	}
	// Media routes are open to any origin: the token is in the URL.
	res = send(http.MethodOptions, "/api/playback/abc/master.m3u8", "https://evil.example", map[string]string{"Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "range"})
	if res.StatusCode != http.StatusNoContent || res.Header.Get("Access-Control-Allow-Origin") != "*" {
		t.Errorf("media preflight -> %d %v", res.StatusCode, res.Header)
	}
	if res.Header.Get("Timing-Allow-Origin") != "*" {
		t.Errorf("media timing unavailable to receiver: %v", res.Header)
	}
	if res := send(http.MethodGet, "/api/server", "https://cast.example", nil); res.Header.Get("Access-Control-Allow-Origin") != "https://cast.example" {
		t.Errorf("server probe CORS: %v", res.Header)
	}
	// Cross-site writes: the receiver origin passes the CSRF check, others do not.
	cross := map[string]string{"Sec-Fetch-Site": "cross-site", "Authorization": "Bearer c1.x.y"}
	if res := send(http.MethodPost, "/api/files/1/playback", "https://cast.example", cross); res.StatusCode == http.StatusForbidden {
		t.Errorf("receiver POST refused as cross-site")
	}
	if res := send(http.MethodPost, "/api/files/1/playback", "https://evil.example", cross); res.StatusCode != http.StatusForbidden {
		t.Errorf("other site POST -> %d", res.StatusCode)
	}
}

func TestCastRoute(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		method, path string
		want         bool
	}{
		{"GET", "/api/files/7/stream", true},
		{"GET", "/api/playback/abc/master.m3u8", true},
		{"DELETE", "/api/playback/abc", true},
		{"GET", "/api/files/7/subtitles/s2.vtt", true},
		{"GET", "/api/images/w500/x.jpg", true},
		{"POST", "/api/files/7/playback", true},
		{"PUT", "/api/files/7/progress", true},
		{"GET", "/api/me/preferences", true},
		{"PUT", "/api/me/preferences", false},
		{"POST", "/api/auth/cast-token", true},
		{"POST", "/api/auth/media-token", false},
		{"GET", "/api/files/7", false},
		{"POST", "/api/files/x/playback", false},
		{"POST", "/api/files/7/playback/extra", false},
		{"DELETE", "/api/files/7/stream", false},
		{"GET", "/api/libraries", false},
		{"GET", "/api/settings", false},
		{"POST", "/api/live-tv/sessions", true},
		{"GET", "/api/live-tv/sessions", false},
		{"GET", "/api/live-tv/sessions/s1", true},
		{"PUT", "/api/live-tv/sessions/s1", true},
		{"DELETE", "/api/live-tv/sessions/s1", true},
		{"POST", "/api/live-tv/sessions/s1", false},
		{"GET", "/api/live-tv/sessions/s1/hls/live.m3u8", true},
		{"HEAD", "/api/live-tv/sessions/s1/hls/segment-000000001.ts", true},
		{"DELETE", "/api/live-tv/sessions/s1/hls/live.m3u8", false},
		{"GET", "/api/live-tv/sessions/s1/other", false},
		{"GET", "/api/live-tv/channels", true},
		{"GET", "/api/live-tv/capabilities", true},
		{"GET", "/api/live-tv/guide", true},
		{"PUT", "/api/live-tv/guide", false},
		{"GET", "/api/live-tv/art/abc123", true},
		{"GET", "/api/live-tv/art/abc/def", false},
		{"GET", "/api/live-tv/art/", false},
		{"GET", "/api/live-tv/activity", false},
		{"DELETE", "/api/live-tv/activity/s1", false},
		{"GET", "/api/settings/live-tv", false},
		{"GET", "/api/settings/live-tv/guide", false},
	} {
		if got := castRoute(c.method, c.path); got != c.want {
			t.Errorf("castRoute(%s %s) = %t", c.method, c.path, got)
		}
	}
}

func TestCastURLList(t *testing.T) {
	t.Parallel()
	lan := []netip.Addr{netip.MustParseAddr("192.168.1.10"), netip.MustParseAddr("10.0.0.5")}
	got := castURLList("", "http://192.168.1.10:27587", lan, 27587)
	want := []string{"http://192.168.1.10:27587", "http://10.0.0.5:27587"}
	if !slices.Equal(got, want) {
		t.Errorf("LAN only: %v", got)
	}
	got = castURLList("https://cast.example.com", "https://home.example.com", lan, 27587)
	want = []string{"https://cast.example.com", "https://home.example.com", "http://192.168.1.10:27587", "http://10.0.0.5:27587"}
	if !slices.Equal(got, want) {
		t.Errorf("with HTTPS: %v", got)
	}
	if b, _ := json.Marshal(castURLList("", "", nil, 0)); string(b) != "[]" {
		t.Errorf("nothing reachable: %s", b)
	}
}

// TestCastURLsNoneReachable is a server bound to loopback with no cast.url,
// opened over loopback: nothing a TV can reach, which the settings page and
// receivers must get as [] rather than null.
func TestCastURLsNoneReachable(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		d.Cast = CastOptions{Enabled: true, Host: "127.0.0.1", Port: 27587}
	})
	var st struct {
		Cast struct {
			URLs json.RawMessage `json:"urls"`
		} `json:"cast"`
	}
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &st)
	if string(st.Cast.URLs) != "[]" {
		t.Errorf("settings cast.urls = %s", st.Cast.URLs)
	}
	res, err := srv.client.Post(srv.URL+"/api/auth/cast-token", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	var ct struct {
		URLs json.RawMessage `json:"urls"`
	}
	_ = json.NewDecoder(res.Body).Decode(&ct)
	_ = res.Body.Close()
	if res.StatusCode != http.StatusOK || string(ct.URLs) != "[]" {
		t.Errorf("cast token -> %d urls = %s", res.StatusCode, ct.URLs)
	}
}

func TestOwnOrigin(t *testing.T) {
	t.Parallel()
	for host, want := range map[string]string{
		"192.168.1.10:27587":     "http://192.168.1.10:27587",
		"localhost:27587":        "",
		"127.0.0.1:27587":        "",
		"[::1]:27587":            "",
		"mac.tail1234.ts.net":    "",
		"100.101.102.103:27587":  "",
		"media.example.com:8443": "http://media.example.com:8443",
	} {
		r, _ := http.NewRequestWithContext(t.Context(), http.MethodGet, "http://"+host+"/api/auth/cast-token", nil)
		if got := ownOrigin(r, nil); got != want {
			t.Errorf("ownOrigin(%s) = %q, want %q", host, got, want)
		}
	}
}

func TestCastSenderCSP(t *testing.T) {
	t.Parallel()
	script := func(srv *testEnv) string {
		csp := doJSON(t, srv.client, http.MethodGet, srv.URL+"/", "", nil).Header.Get("Content-Security-Policy")
		for d := range strings.SplitSeq(csp, ";") {
			if d = strings.TrimSpace(d); strings.HasPrefix(d, "script-src ") {
				return d
			}
		}
		return ""
	}
	// Casting on but no receiver App ID: browsers show no Cast button, so no SDK.
	if s := script(newTestServerWith(t, castServerDeps)); strings.Contains(s, "gstatic") {
		t.Errorf("SDK allowed without an App ID: %s", s)
	}
	srv := newTestServerWith(t, func(d *Deps) {
		castServerDeps(d)
		d.Cast.AppID = "ABCD1234"
	})
	// No scheme: on http://localhost the SDK loads its scripts over HTTP.
	if s := script(srv); !strings.Contains(s, " www.gstatic.com") {
		t.Errorf("SDK not allowed: %s", s)
	}
}
