package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
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

// refused stands for the cross-origin check's 403 in the tables below,
// told apart from a route's own 403 by its detail.
const refused = -1

// sendWith makes one request for a table: c nil sends no cookie, and the
// reply's status is refused when the cross-origin check stopped it.
func sendWith(t *testing.T, c *http.Client, method, url, body string, header map[string]string) (int, reply) {
	t.Helper()
	var kv []string
	for k, v := range header {
		kv = append(kv, k, v)
	}
	res := doJSON(t, c, method, url, body, nil, kv...)
	if res.StatusCode == http.StatusForbidden && strings.Contains(string(res.Body), "cross-origin request refused") {
		return refused, res
	}
	return res.StatusCode, res
}

// deviceToken signs name in as a device, as an approved pairing would.
func deviceToken(t *testing.T, srv *testEnv, accounts *auth.Service, name string) string {
	t.Helper()
	u, err := srv.q.GetUserByName(t.Context(), name)
	if err != nil {
		t.Fatal(err)
	}
	g, err := accounts.CreateSession(t.Context(), u.ID, auth.Client{Kind: auth.KindDevice, Name: "Living room TV"})
	if err != nil {
		t.Fatal(err)
	}
	return g.Token
}

// Writes from another origin are refused when the browser's cookie could
// sign them in, and pass with a bearer token, which a page on another site
// can't attach for someone and which signs the request in alone.
func TestCrossOriginRefused(t *testing.T) {
	t.Parallel()
	var accounts *auth.Service
	srv := newTestServerWith(t, func(d *Deps) { accounts = d.Auth })
	if _, err := srv.q.CreateUser(t.Context(), db.CreateUserParams{Name: "member", PasswordHash: adminHash()}); err != nil {
		t.Fatal(err)
	}
	admin, member := deviceToken(t, srv, accounts, "admin"), deviceToken(t, srv, accounts, "member")
	crossSite := func(origin, authorization string) map[string]string {
		h := map[string]string{"Sec-Fetch-Site": "cross-site", "Origin": origin}
		if authorization != "" {
			h["Authorization"] = authorization
		}
		return h
	}
	for _, c := range []struct {
		name   string
		cookie bool // the admin's browser cookie
		header map[string]string
		want   int
	}{
		{"another site", true, map[string]string{"Sec-Fetch-Site": "cross-site"}, refused},
		{"a sibling subdomain", true, map[string]string{"Sec-Fetch-Site": "same-site"}, refused},
		{"old browser, foreign origin", true, map[string]string{"Origin": "https://evil.example"}, refused},
		{"old browser, null origin", true, map[string]string{"Origin": "null"}, refused},
		{"a file:// page", true, crossSite("file://", ""), refused},
		{"another site, with a proxy's Basic sign-in", true, crossSite("https://evil.example", "Basic YWxpY2U6c2VjcmV0"), refused},
		{"another site, with an empty bearer", true, crossSite("https://evil.example", "Bearer "), refused},
		{"this site", true, map[string]string{"Sec-Fetch-Site": "same-origin"}, http.StatusCreated},
		{"native app or script", true, nil, http.StatusCreated},
		{"a packaged TV app's bearer", false, crossSite("file://", "Bearer "+admin), http.StatusCreated},
		{"a bearer from a null origin", false, crossSite("null", "Bearer "+admin), http.StatusCreated},
		{"a bearer from another site", false, crossSite("https://evil.example", "Bearer "+admin), http.StatusCreated},
		{"a bad bearer beside the cookie", true, crossSite("file://", "Bearer nope"), http.StatusUnauthorized},
		{"a bad bearer beside the cookie, this site", true, map[string]string{"Authorization": "Bearer nope"}, http.StatusUnauthorized},
		{"a member's bearer beside the admin's cookie", true, crossSite("file://", "Bearer "+member), http.StatusForbidden},
	} {
		client := (*http.Client)(nil)
		if c.cookie {
			client = srv.client
		}
		// Creating a backup is an admin's POST with no body: a plain HTML
		// form on another site could send it.
		if got, res := sendWith(t, client, http.MethodPost, srv.URL+"/api/backups", "", c.header); got != c.want {
			t.Errorf("%s -> %d %s", c.name, res.StatusCode, res.Body)
		}
	}
	// Reads are not affected, and the bearer, not the cookie, says who asks.
	for _, c := range []struct{ authorization, want string }{
		{"", "admin"},
		{"Bearer " + member, "member"},
		{"Bearer nope", ""},
	} {
		var me auth.User
		res := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/auth/me", "", &me, "Sec-Fetch-Site", "cross-site", "Authorization", c.authorization)
		if c.want == "" && res.StatusCode != http.StatusUnauthorized || c.want != "" && (res.StatusCode != http.StatusOK || me.Name != c.want) {
			t.Errorf("cross-site GET with %q beside the admin's cookie -> %d %s", c.authorization, res.StatusCode, res.Body)
		}
	}
}

// A packaged TV app pairs from its file:// page: starting and claiming carry
// no credentials and work from any origin, and the token they hand out
// works there too, while approving the code stays a signed-in person's
// request from this site.
func TestPairingCrossOrigin(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	tv := map[string]string{"Origin": "file://", "Sec-Fetch-Site": "cross-site"}
	var start PairingStart
	res := doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/pair", `{"client":{"deviceId":"tv-1","name":"Living room TV","platform":"tizen"}}`, &start,
		"Origin", "file://", "Sec-Fetch-Site", "cross-site")
	if res.StatusCode != http.StatusOK || start.Secret == "" || res.Header.Get("Access-Control-Allow-Origin") != "file://" || res.Header.Get("Access-Control-Allow-Credentials") != "" {
		t.Fatalf("start from file:// -> %d %v %s", res.StatusCode, res.Header, res.Body)
	}
	claim := `{"secret":"` + start.Secret + `"}`
	approve := srv.URL + "/api/auth/pair/" + start.Code + "/approve"
	for _, c := range []struct {
		name      string
		client    *http.Client
		url, body string
		header    map[string]string
		want      int
	}{
		{"start from a null origin", nil, srv.URL + "/api/auth/pair", `{"client":{"deviceId":"tv-2","name":"Bedroom TV"}}`,
			map[string]string{"Origin": "null", "Sec-Fetch-Site": "cross-site"}, http.StatusOK},
		{"claim before approval", nil, srv.URL + "/api/auth/pair/claim", claim, tv, http.StatusOK},
		{"a route below claim", nil, srv.URL + "/api/auth/pair/claim/x", claim, tv, refused},
		{"approve from another site", srv.client, approve, "", map[string]string{"Sec-Fetch-Site": "cross-site"}, refused},
		{"approve from a file:// page with the cookie", srv.client, approve, "", tv, refused},
		{"approve on this site", srv.client, approve, "", map[string]string{"Sec-Fetch-Site": "same-origin"}, http.StatusOK},
	} {
		if got, res := sendWith(t, c.client, http.MethodPost, c.url, c.body, c.header); got != c.want {
			t.Errorf("%s -> %d %s", c.name, res.StatusCode, res.Body)
		}
	}
	var approved PairingClaim
	if res := doJSON(t, nil, http.MethodPost, srv.URL+"/api/auth/pair/claim", claim, &approved, "Origin", "file://", "Sec-Fetch-Site", "cross-site"); res.StatusCode != http.StatusOK || approved.Token == "" {
		t.Fatalf("claim after approval from file:// -> %d %s", res.StatusCode, res.Body)
	}
	if got, res := sendWith(t, nil, http.MethodPost, srv.URL+"/api/backups", "", map[string]string{"Origin": "file://", "Sec-Fetch-Site": "cross-site", "Authorization": "Bearer " + approved.Token}); got != http.StatusCreated {
		t.Errorf("the paired TV's write from file:// -> %d %s", res.StatusCode, res.Body)
	}
}

// Pages on other origins get CORS as apiCORS says: Cast receivers and media
// only while casting is on, the packaged TV apps' file:// pages either way,
// never null or another site, and never with credentials.
func TestCrossOriginCORS(t *testing.T) {
	t.Parallel()
	preflight := func(origin, method, headers string) map[string]string {
		return map[string]string{"Origin": origin, "Access-Control-Request-Method": method, "Access-Control-Request-Headers": headers}
	}
	for _, cast := range []bool{false, true} {
		srv := newTestServerWith(t, func(d *Deps) {
			castServerDeps(d)
			d.Cast.Enabled = cast
		})
		for _, c := range []struct {
			name, method, path string
			header             map[string]string
			off, on            string // Access-Control-Allow-Origin with casting off and on
		}{
			{"the TV app's preflight to pair", http.MethodOptions, "/api/auth/pair", preflight("file://", "POST", "content-type"), "file://", "file://"},
			{"the TV app's preflight with its token", http.MethodOptions, "/api/libraries", preflight("file://", "PATCH", "authorization"), "file://", "file://"},
			{"the TV app's read", http.MethodGet, "/api/server", map[string]string{"Origin": "file://"}, "file://", "file://"},
			{"a null origin's preflight", http.MethodOptions, "/api/auth/pair", preflight("null", "POST", "content-type"), "", ""},
			{"a null origin's read", http.MethodGet, "/api/server", map[string]string{"Origin": "null"}, "", ""},
			{"another site's preflight", http.MethodOptions, "/api/libraries", preflight("https://evil.example", "GET", "authorization"), "", ""},
			{"a file URL with a host", http.MethodOptions, "/api/libraries", preflight("file://evil.example", "GET", "authorization"), "", ""},
			{"the receiver's preflight", http.MethodOptions, "/api/files/1/playback", preflight("https://cast.example", "POST", "authorization,content-type"), "", "https://cast.example"},
			{"the receiver's read", http.MethodGet, "/api/server", map[string]string{"Origin": "https://cast.example"}, "", "https://cast.example"},
			{"the TV app's media preflight", http.MethodOptions, "/api/playback/abc/master.m3u8", preflight("file://", "GET", "range"), "", "*"},
			{"another site's media preflight", http.MethodOptions, "/api/playback/abc/master.m3u8", preflight("https://evil.example", "GET", "range"), "", "*"},
		} {
			want := c.off
			if cast {
				want = c.on
			}
			_, res := sendWith(t, nil, c.method, srv.URL+c.path, "", c.header)
			h := res.Header
			if got := h.Get("Access-Control-Allow-Origin"); got != want || h.Get("Access-Control-Allow-Credentials") != "" {
				t.Errorf("cast %t: %s -> %d, allows %q (want %q), credentials %q", cast, c.name, res.StatusCode, got, want, h.Get("Access-Control-Allow-Credentials"))
				continue
			}
			if want == "file://" && c.method == http.MethodOptions && (res.StatusCode != http.StatusNoContent ||
				h.Get("Access-Control-Allow-Methods") != "GET, POST, PUT, PATCH, DELETE" || h.Get("Access-Control-Allow-Headers") != "Authorization, Content-Type") {
				t.Errorf("cast %t: %s -> %d %v", cast, c.name, res.StatusCode, h)
			}
			if !mediaPath(c.path) && !slices.Contains(h.Values("Vary"), "Origin") {
				t.Errorf("cast %t: %s -> Vary %v", cast, c.name, h.Values("Vary"))
			}
		}
		// The receiver's writes pass the cross-origin check only while
		// casting is on, as before; without a token they then need a sign-in.
		want := refused
		if cast {
			want = http.StatusUnauthorized
		}
		if got, res := sendWith(t, nil, http.MethodPost, srv.URL+"/api/files/1/playback", `{"profile":{}}`, map[string]string{"Origin": "https://cast.example", "Sec-Fetch-Site": "cross-site"}); got != want {
			t.Errorf("cast %t: the receiver's write -> %d %s", cast, res.StatusCode, res.Body)
		}
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

// Behind a reverse proxy on this machine that is not in trusted proxies,
// every visitor arrives from 127.0.0.1. One visitor's failures must not
// lock out the others: the limits count the client the proxy names.
func TestSignInThrottledBehindUntrustedProxy(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		admin, _ := netguard.Parse([]string{"local"})
		d.Net = netguard.New(nil, admin)
	})
	anon := &http.Client{}
	stranger := map[string]string{"X-Forwarded-For": "203.0.113.9", "X-Forwarded-Proto": "https"}
	owner := map[string]string{"X-Forwarded-For": "198.51.100.7", "X-Forwarded-Proto": "https"}
	for range 6 {
		post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"guess123"}`, stranger)
	}
	if res, _ := post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, stranger); res.StatusCode != http.StatusTooManyRequests {
		t.Errorf("stranger after 6 guesses -> %d", res.StatusCode)
	}
	if res, body := post(t, anon, srv.URL+"/api/auth/login", `{"name":"admin","password":"password1"}`, owner); res.StatusCode != http.StatusOK {
		t.Errorf("owner behind the same proxy -> %d %s", res.StatusCode, body)
	}
	// TV pairing likewise: the stranger's starts use up only its own.
	for i := range 11 {
		res, _ := post(t, anon, srv.URL+"/api/auth/pair", `{"client":{"deviceId":"tv-1","name":"Test TV"}}`, stranger)
		if want := http.StatusOK; i == 10 {
			if res.StatusCode != http.StatusTooManyRequests {
				t.Errorf("stranger's 11th pairing -> %d", res.StatusCode)
			}
		} else if res.StatusCode != want {
			t.Fatalf("stranger's pairing %d -> %d", i+1, res.StatusCode)
		}
	}
	if res, body := post(t, anon, srv.URL+"/api/auth/pair", `{"client":{"deviceId":"tv-2","name":"Test TV"}}`, owner); res.StatusCode != http.StatusOK {
		t.Errorf("owner's pairing behind the same proxy -> %d %s", res.StatusCode, body)
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
