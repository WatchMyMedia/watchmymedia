package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/turnstile"
)

// Cloudflare's published Turnstile test keys.
const (
	tsPassSite   = "1x00000000000000000000AA"
	tsPassSecret = "1x0000000000000000000000000000000AA"
	tsFailSecret = "2x0000000000000000000000000000000AA"
)

// fakeSiteverify answers like Cloudflare does for the test secrets and
// remembers the remoteip of each call. down makes it fail like an outage.
type fakeSiteverify struct {
	mu   sync.Mutex
	ips  []string
	down bool
}

func (f *fakeSiteverify) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	_ = r.ParseForm()
	f.mu.Lock()
	f.ips = append(f.ips, r.PostForm.Get("remoteip"))
	down := f.down
	f.mu.Unlock()
	if down {
		w.WriteHeader(http.StatusBadGateway)
		return
	}
	out := map[string]any{"success": false, "error-codes": []string{"invalid-input-response"}}
	switch r.PostForm.Get("secret") {
	case tsPassSecret:
		if r.PostForm.Get("response") == turnstile.DummyToken {
			out = map[string]any{"success": true, "error-codes": []string{}, "hostname": "localhost"}
		}
	case tsFailSecret:
	default:
		out["error-codes"] = []string{"invalid-input-secret"}
	}
	_ = json.NewEncoder(w).Encode(out)
}

func (f *fakeSiteverify) lastIP() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.ips) == 0 {
		return ""
	}
	return f.ips[len(f.ips)-1]
}

// newTurnstileServer is a test server whose sign-in can be put behind
// Turnstile, checked against a fake siteverify. Turnstile starts off, so the
// helper's own sign-in works; tests turn it on with SetKeys.
func newTurnstileServer(t *testing.T, adjust func(*Deps)) (*testEnv, *turnstile.Verifier, *fakeSiteverify) {
	t.Helper()
	fake := &fakeSiteverify{}
	cf := httptest.NewServer(fake)
	t.Cleanup(cf.Close)
	v := turnstile.New()
	v.URL = cf.URL
	srv := newTestServerWith(t, func(d *Deps) {
		d.Turnstile = v
		d.Auth.SetChallenge(v)
		if adjust != nil {
			adjust(d)
		}
	})
	return srv, v, fake
}

func post(t *testing.T, c *http.Client, url, body string, header map[string]string) (*http.Response, string) {
	t.Helper()
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodPost, url, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	for k, v := range header {
		req.Header.Set(k, v)
	}
	res, err := c.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res, string(b)
}

func TestTurnstileSignIn(t *testing.T) {
	t.Parallel()
	srv, v, fake := newTurnstileServer(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		admin, _ := netguard.Parse([]string{"local"})
		d.Net = netguard.New(trusted, admin)
	})
	anon := &http.Client{}
	lan := map[string]string{"X-Forwarded-For": "192.168.1.5"}

	// Off: nothing changes.
	var st AuthStatus
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/auth/status", "", &st)
	if st.TurnstileSiteKey != "" {
		t.Errorf("site key while off: %q", st.TurnstileSiteKey)
	}
	if csp := doJSON(t, srv.client, http.MethodGet, srv.URL+"/", "", nil).Header.Get("Content-Security-Policy"); strings.Contains(csp, "cloudflare") {
		t.Errorf("CSP allows Cloudflare while off: %q", csp)
	}
	if res, _ := post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, nil); res.StatusCode != http.StatusOK {
		t.Fatalf("login while off -> %d", res.StatusCode)
	}

	v.SetKeys(tsPassSite, tsPassSecret)
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/auth/status", "", &st)
	if st.TurnstileSiteKey != tsPassSite {
		t.Errorf("status site key = %q", st.TurnstileSiteKey)
	}
	var info ServerInfo
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/server", "", &info)
	if !info.Turnstile {
		t.Error("server info does not tell apps about Turnstile")
	}
	csp := doJSON(t, srv.client, http.MethodGet, srv.URL+"/", "", nil).Header.Get("Content-Security-Policy")
	if !strings.Contains(csp, "script-src 'self' 'wasm-unsafe-eval' https://challenges.cloudflare.com;") || !strings.Contains(csp, "frame-src https://challenges.cloudflare.com") {
		t.Errorf("CSP while on: %q", csp)
	}

	// No token, or a bad one: refused before the password is tried.
	if res, body := post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, lan); res.StatusCode != http.StatusForbidden || !strings.Contains(body, "not a bot") {
		t.Errorf("no token -> %d %s", res.StatusCode, body)
	}
	// Native apps get the same answer: they sign in by pairing instead.
	if res, _ := post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1","client":{"deviceId":"p1","name":"Phone"}}`, lan); res.StatusCode != http.StatusForbidden {
		t.Errorf("device sign-in without token -> %d", res.StatusCode)
	}
	good := `{"name":"admin","password":"password1","turnstileToken":"` + turnstile.DummyToken + `"}`
	res, _ := post(t, anon, srv.URL+"/api/auth/login", good, map[string]string{"X-Forwarded-For": "198.51.100.7"})
	if res.StatusCode != http.StatusOK || res.Header.Get("Set-Cookie") == "" {
		t.Errorf("with token -> %d", res.StatusCode)
	}
	// Cloudflare is told the real client's address, not the proxy's.
	if ip := fake.lastIP(); ip != "198.51.100.7" {
		t.Errorf("remoteip = %q", ip)
	}
	// A right token does not make a wrong password right.
	if res, _ := post(t, anon, srv.URL+"/api/auth/login", strings.Replace(good, "password1", "wrong123", 1), lan); res.StatusCode != http.StatusUnauthorized {
		t.Errorf("wrong password with token -> %d", res.StatusCode)
	}

	v.SetKeys(tsPassSite, tsFailSecret)
	if res, _ := post(t, anon, srv.URL+"/api/auth/login", good, lan); res.StatusCode != http.StatusForbidden {
		t.Errorf("always-fail secret -> %d", res.StatusCode)
	}
	// Cloudflare unreachable: sign-in fails closed with a 503.
	v.SetKeys(tsPassSite, tsPassSecret)
	fake.mu.Lock()
	fake.down = true
	fake.mu.Unlock()
	if res, body := post(t, anon, srv.URL+"/api/auth/login", good, lan); res.StatusCode != http.StatusServiceUnavailable || strings.Contains(body, "502") {
		t.Errorf("Cloudflare down -> %d %s", res.StatusCode, body)
	}
	fake.mu.Lock()
	fake.down = false
	fake.mu.Unlock()

	// Pairing a TV needs no password, so it works with Turnstile on: the
	// person approving is already signed in.
	var start PairingStart
	if res := doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/pair", `{"client":{"deviceId":"tv-9","name":"Den TV"}}`, &start); res.StatusCode != http.StatusOK {
		t.Fatalf("start pairing -> %d", res.StatusCode)
	}
	if res, _ := post(t, srv.client, srv.URL+"/api/auth/pair/"+start.Code+"/approve", "", nil); res.StatusCode != http.StatusOK {
		t.Fatalf("approve -> %d", res.StatusCode)
	}
	var claim PairingClaim
	if res := doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/pair/claim", `{"secret":"`+start.Secret+`"}`, &claim); res.StatusCode != http.StatusOK || claim.Status != "approved" || claim.Token == "" {
		t.Errorf("claim -> %d %+v", res.StatusCode, claim)
	}
}

func TestTurnstileDisabledByConfig(t *testing.T) {
	t.Parallel()
	srv, v, _ := newTurnstileServer(t, func(d *Deps) { d.Turnstile.Disabled = true })
	v.SetKeys(tsPassSite, tsPassSecret)
	if res, _ := post(t, &http.Client{}, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, nil); res.StatusCode != http.StatusOK {
		t.Errorf("login while disabled -> %d", res.StatusCode)
	}
	var s Settings
	doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/settings", "", &s)
	if s.Turnstile.Enabled || !s.Turnstile.Disabled || s.Turnstile.SiteKey != tsPassSite {
		t.Errorf("settings = %+v", s.Turnstile)
	}
}

func TestTurnstileSetup(t *testing.T) {
	t.Parallel()
	srv, v, _ := newTurnstileServer(t, nil)
	if err := srv.q.DeleteUser(t.Context(), 1); err != nil {
		t.Fatal(err)
	}
	v.SetKeys(tsPassSite, tsPassSecret)
	anon := &http.Client{}
	if res, _ := post(t, anon, srv.URL+"/api/setup", `{"name":"owner","password":"password1"}`, nil); res.StatusCode != http.StatusForbidden {
		t.Errorf("setup without token -> %d", res.StatusCode)
	}
	if res, _ := post(t, anon, srv.URL+"/api/setup", `{"name":"owner","password":"short12","turnstileToken":"`+turnstile.DummyToken+`"}`, nil); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("setup with a 7-character password -> %d", res.StatusCode)
	}
	if res, _ := post(t, anon, srv.URL+"/api/setup", `{"name":"owner","password":"password1","turnstileToken":"`+turnstile.DummyToken+`"}`, nil); res.StatusCode != http.StatusCreated {
		t.Errorf("setup with token -> %d", res.StatusCode)
	}
}

func TestTurnstileSettings(t *testing.T) {
	t.Parallel()
	srv, v, _ := newTurnstileServer(t, nil)
	put := func(body string) (*http.Response, Settings) {
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+"/api/settings", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		res, err := srv.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		var s Settings
		_ = json.NewDecoder(res.Body).Decode(&s)
		return res, s
	}
	// A secret Cloudflare does not know is refused, so a typo cannot lock
	// everyone out.
	if res, _ := put(`{"turnstile":{"siteKey":"` + tsPassSite + `","secretKey":"0xnot-a-real-secret-0000"}}`); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("unknown secret -> %d", res.StatusCode)
	}
	if res, _ := put(`{"turnstile":{"siteKey":"` + tsPassSite + `"}}`); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("no secret -> %d", res.StatusCode)
	}
	if res, _ := put(`{"turnstile":{"siteKey":"<script>","secretKey":"` + tsPassSecret + `"}}`); res.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("malformed site key -> %d", res.StatusCode)
	}
	if v.Required() {
		t.Fatal("turned on by a refused update")
	}
	res, s := put(`{"turnstile":{"siteKey":"` + tsPassSite + `","secretKey":"` + tsPassSecret + `"}}`)
	if res.StatusCode != http.StatusOK || !s.Turnstile.Enabled || s.Turnstile.SiteKey != tsPassSite || s.Turnstile.SecretKeyHint != "…00AA" {
		t.Fatalf("turn on -> %d %+v", res.StatusCode, s.Turnstile)
	}
	if !v.Required() {
		t.Error("verifier not updated")
	}
	if site, _ := srv.q.GetSetting(t.Context(), "auth.turnstile.site_key"); site != tsPassSite {
		t.Errorf("stored site key = %q", site)
	}
	// The secret never comes back.
	b, _ := json.Marshal(s)
	if strings.Contains(string(b), tsPassSecret) {
		t.Error("settings expose the secret key")
	}
	// Admins can try the widget from Settings.
	var check TestResult
	if res := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/settings/turnstile/check", `{"token":"`+turnstile.DummyToken+`"}`, &check).StatusCode; res != http.StatusOK || !check.OK {
		t.Errorf("check -> %d %+v", res, check)
	}
	if res := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/settings/turnstile/check", `{"token":"bad"}`, &check).StatusCode; res != http.StatusOK || check.OK {
		t.Errorf("check bad token -> %d %+v", res, check)
	}
	// Changing only the site key keeps the saved secret.
	if res, s := put(`{"turnstile":{"siteKey":"3x00000000000000000000FF"}}`); res.StatusCode != http.StatusOK || s.Turnstile.SecretKeyHint != "…00AA" {
		t.Errorf("site key only -> %d %+v", res.StatusCode, s.Turnstile)
	}
	if res, s := put(`{"turnstile":{"siteKey":""}}`); res.StatusCode != http.StatusOK || s.Turnstile.Enabled || s.Turnstile.SiteKey != "" {
		t.Errorf("turn off -> %d %+v", res.StatusCode, s.Turnstile)
	}
	if v.Required() {
		t.Error("still on after turning off")
	}
}

func TestTurnstileKeysFromConfig(t *testing.T) {
	t.Parallel()
	srv, _, _ := newTurnstileServer(t, func(d *Deps) { d.Turnstile.Fixed = true })
	req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+"/api/settings", strings.NewReader(`{"turnstile":{"siteKey":""}}`))
	req.Header.Set("Content-Type", "application/json")
	res, err := srv.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusConflict {
		t.Errorf("change config keys -> %d", res.StatusCode)
	}
}

func TestCrossOriginRefused(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	for _, c := range []struct {
		name   string
		header map[string]string
		want   int
	}{
		{"another site", map[string]string{"Sec-Fetch-Site": "cross-site"}, http.StatusForbidden},
		{"a sibling subdomain", map[string]string{"Sec-Fetch-Site": "same-site"}, http.StatusForbidden},
		{"old browser, foreign origin", map[string]string{"Origin": "https://evil.example"}, http.StatusForbidden},
		{"this site", map[string]string{"Sec-Fetch-Site": "same-origin"}, http.StatusCreated},
		{"native app or script", nil, http.StatusCreated},
	} {
		// Creating a backup is a POST with no body: a plain HTML form on
		// another site could send it.
		res, body := post(t, srv.client, srv.URL+"/api/backups", "", c.header)
		if res.StatusCode != c.want {
			t.Errorf("%s -> %d %s", c.name, res.StatusCode, body)
		}
	}
	// Reads are not affected.
	if res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/auth/me", "", nil, "Sec-Fetch-Site", "cross-site"); res.StatusCode != http.StatusOK {
		t.Errorf("cross-site GET -> %d", res.StatusCode)
	}
}

func TestInternalErrorsHidden(t *testing.T) {
	t.Parallel()
	var deps Deps
	srv := newTestServerWith(t, func(d *Deps) { deps = *d })
	_ = deps.Conn.Close()
	res, err := http.Get(srv.URL + "/api/auth/status")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	if res.StatusCode != http.StatusInternalServerError || strings.Contains(string(b), "sql") || strings.Contains(string(b), "closed") {
		t.Errorf("-> %d %s", res.StatusCode, b)
	}
}

func TestSignInThrottledPerAddress(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		d.Net = netguard.New(trusted, nil)
	})
	anon := &http.Client{}
	stranger := map[string]string{"X-Forwarded-For": "203.0.113.9"}
	for range 6 {
		post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"guess123"}`, stranger)
	}
	if res, _ := post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, stranger); res.StatusCode != http.StatusTooManyRequests || res.Header.Get("Retry-After") == "" {
		t.Errorf("stranger after 6 guesses -> %d", res.StatusCode)
	}
	// The owner, at home, still signs in; over HTTPS the cookie is Secure.
	res, _ := post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, map[string]string{"X-Forwarded-For": "198.51.100.7", "X-Forwarded-Proto": "https"})
	if res.StatusCode != http.StatusOK {
		t.Errorf("owner -> %d", res.StatusCode)
	}
	if c := res.Header.Get("Set-Cookie"); !strings.Contains(c, "Secure") || !strings.Contains(c, "HttpOnly") {
		t.Errorf("cookie over HTTPS = %q", c)
	}
}
