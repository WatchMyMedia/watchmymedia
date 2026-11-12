package netguard

import (
	"bytes"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
)

func mustParse(t *testing.T, list ...string) *Guard {
	t.Helper()
	trusted, err := Parse(list)
	if err != nil {
		t.Fatal(err)
	}
	local, _ := Parse([]string{Local})
	return New(trusted, local)
}

func TestParse(t *testing.T) {
	ps, err := Parse([]string{" 10.1.2.3 ", "192.168.1.0/24", "::ffff:8.8.8.8", "fd00::/8", ""})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"10.1.2.3/32", "192.168.1.0/24", "8.8.8.8/32", "fd00::/8"}
	if len(ps) != len(want) {
		t.Fatalf("got %v", ps)
	}
	for i, p := range ps {
		if p.String() != want[i] {
			t.Errorf("prefix %d = %s, want %s", i, p, want[i])
		}
	}
	for _, bad := range []string{"nope", "10.0.0.0/99", "1.2.3"} {
		if _, err := Parse([]string{bad}); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	if any, _ := Parse([]string{"ANY"}); len(any) != 2 {
		t.Errorf("any = %v", any)
	}
}

func TestClientIP(t *testing.T) {
	g := mustParse(t, "172.31.250.0/24")
	cases := []struct {
		name, remote, xff, want string
		named                   bool // the address is the client's, not a proxy's that named no client
	}{
		{"direct, header ignored", "203.0.113.9:5000", "10.0.0.1", "203.0.113.9", true},
		{"through proxy", "172.31.250.2:40000", "198.51.100.7", "198.51.100.7", true},
		{"spoofed left entry", "172.31.250.2:40000", "10.0.0.1, 198.51.100.7", "198.51.100.7", true},
		{"chain of trusted proxies", "172.31.250.2:40000", "198.51.100.7, 172.31.250.3", "198.51.100.7", true},
		{"only trusted proxies", "172.31.250.2:40000", "172.31.250.4, 172.31.250.3", "172.31.250.4", true},
		{"proxy without header", "172.31.250.2:40000", "", "172.31.250.2", true},
		{"garbage header", "172.31.250.2:40000", "not-an-ip", "172.31.250.2", false},
		{"garbage behind a trusted hop", "172.31.250.2:40000", "not-an-ip, 172.31.250.3", "172.31.250.3", false},
		{"garbage ahead of the client", "172.31.250.2:40000", "not-an-ip, 198.51.100.7", "198.51.100.7", true},
		{"ipv6 client", "172.31.250.2:40000", "2001:db8::5", "2001:db8::5", true},
		{"mapped remote", "[::ffff:192.168.1.4]:80", "", "192.168.1.4", true},
	}
	for _, c := range cases {
		r := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil)
		r.RemoteAddr = c.remote
		if c.xff != "" {
			r.Header.Set("X-Forwarded-For", c.xff)
		}
		ip, named := g.clientIP(r)
		if got := g.ClientIP(r); got != ip || got.String() != c.want || named != c.named {
			t.Errorf("%s: got %s (named %v), want %s (%v)", c.name, got, named, c.want, c.named)
		}
	}
}

func TestHTTPS(t *testing.T) {
	g := mustParse(t, "172.31.250.0/24")
	cases := []struct {
		name, remote, proto string
		want                bool
	}{
		{"trusted proxy over https", "172.31.250.2:40000", "https", true},
		{"trusted proxy, list", "172.31.250.2:40000", "HTTPS, http", true},
		{"trusted proxy over http", "172.31.250.2:40000", "http", false},
		{"untrusted client claims https", "203.0.113.9:5000", "https", false},
		{"untrusted internet proxy claims https", "203.0.113.9:5000", "https+xff", false},
		{"untrusted proxy on this machine over https", "127.0.0.1:5000", "https+xff", true},
		{"untrusted proxy on the LAN over https", "192.168.1.9:5000", "https+xff", true},
		{"untrusted proxy on this machine over http", "127.0.0.1:5000", "http+xff", false},
		{"this machine claims https without forwarding", "127.0.0.1:5000", "https", false},
		{"plain connection", "203.0.113.9:5000", "", false},
	}
	for _, c := range cases {
		r := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil)
		r.RemoteAddr = c.remote
		if proto, forwarded := strings.CutSuffix(c.proto, "+xff"); c.proto != "" {
			r.Header.Set("X-Forwarded-Proto", proto)
			if forwarded {
				r.Header.Set("X-Forwarded-For", "198.51.100.7")
			}
		}
		if got := g.HTTPS(r); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}

func TestMiddlewareAdmin(t *testing.T) {
	g := mustParse(t, "172.31.250.0/24")
	var got Info
	h := g.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		got, _ = From(r.Context())
		if AdminAllowed(r.Context()) != got.Admin {
			t.Error("AdminAllowed disagrees with Info")
		}
	}))
	for _, c := range []struct {
		remote, xff string
		admin       bool
	}{
		{"192.168.1.20:1", "", true},
		{"100.101.102.103:1", "", true}, // tailnet
		{"[::1]:1", "", true},
		{"203.0.113.9:1", "", false},
		{"172.31.250.2:1", "203.0.113.9", false}, // internet through the proxy
		{"172.31.250.2:1", "192.168.1.20", true}, // LAN through the proxy
		{"172.31.250.2:1", "", true},             // no client named: the proxy's own machine, on the LAN
		{"172.31.250.2:1", "garbage", false},
	} {
		r := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil)
		r.RemoteAddr = c.remote
		if c.xff != "" {
			r.Header.Set("X-Forwarded-For", c.xff)
		}
		h.ServeHTTP(httptest.NewRecorder(), r)
		if got.Admin != c.admin {
			t.Errorf("%s via %q: admin = %v, want %v", c.remote, c.xff, got.Admin, c.admin)
		}
	}
	if AdminAllowed(httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil).Context()) {
		t.Error("request without the middleware allowed")
	}
}

func TestAdminExclusions(t *testing.T) {
	allow, deny, err := ParseRules([]string{"local", " !192.168.65.0/24"})
	if err != nil || len(deny) != 1 {
		t.Fatalf("rules = %v %v %v", allow, deny, err)
	}
	if _, err := Parse([]string{"!10.0.0.0/8"}); err == nil {
		t.Error("exclusion accepted where it is not allowed")
	}
	trusted, _ := Parse([]string{"172.30.0.0/16"})
	g := New(trusted, allow, deny...)
	var got Info
	h := g.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { got, _ = From(r.Context()) }))
	for _, c := range []struct {
		xff   string
		admin bool
	}{
		{"192.168.65.1", false}, // Docker Desktop's gateway: could be anyone
		{"192.168.1.20", true},
		{"100.101.102.103", true},
		{"203.0.113.9", false},
	} {
		r := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil)
		r.RemoteAddr = "172.30.0.5:1"
		r.Header.Set("X-Forwarded-For", c.xff)
		h.ServeHTTP(httptest.NewRecorder(), r)
		if got.Admin != c.admin {
			t.Errorf("%s: admin = %v, want %v", c.xff, got.Admin, c.admin)
		}
	}
}

func TestClassify(t *testing.T) {
	for ip, want := range map[string]string{
		"192.168.1.20":        NetLocal,
		"10.0.0.5":            NetLocal,
		"::1":                 NetLocal,
		"::ffff:192.168.1.20": NetLocal,
		"100.101.102.103":     NetTailnet,
		"fd7a:115c:a1e0::1":   NetTailnet,
		"fd00::1":             NetLocal,
		"203.0.113.9":         NetRemote,
		"2001:db8::1":         NetRemote,
	} {
		if got := Classify(netip.MustParseAddr(ip)); got != want {
			t.Errorf("Classify(%s) = %s, want %s", ip, got, want)
		}
	}
	if got := Classify(netip.Addr{}); got != NetRemote {
		t.Errorf("Classify(invalid) = %s", got)
	}
}

// A proxy on this machine that is not trusted makes every client it relays
// look like loopback. Those clients count as remote instead, while apps on
// the machine itself, which send no forwarding headers, stay loopback.
func TestUntrustedProxy(t *testing.T) {
	local, _ := Parse([]string{Local})
	trusted, _ := Parse([]string{"127.0.0.1", "::1"})
	anyNet, _ := Parse([]string{Any})
	allowAny, denySome, _ := ParseRules([]string{Any, "!198.51.100.0/24"})
	for _, c := range []struct {
		name           string
		g              *Guard
		remote, header string
		value          string
		ip             string
		proxied, admin bool
		loopback       bool
	}{
		{"menu bar app", New(nil, local), "127.0.0.1:1", "", "", "127.0.0.1", false, true, true},
		{"menu bar app over ipv6", New(nil, local), "[::1]:1", "", "", "::1", false, true, true},
		{"proxy, x-forwarded-for", New(nil, local), "127.0.0.1:1", "X-Forwarded-For", "203.0.113.9", "127.0.0.1", true, false, false},
		{"proxy, forwarded", New(nil, local), "[::1]:1", "Forwarded", "for=203.0.113.9;proto=https", "::1", true, false, false},
		{"proxy, x-real-ip", New(nil, local), "127.0.0.1:1", "X-Real-IP", "203.0.113.9", "127.0.0.1", true, false, false},
		{"proxy, empty header", New(nil, local), "127.0.0.1:1", "X-Forwarded-For", "", "127.0.0.1", true, false, false},
		{"proxy claims loopback", New(nil, local), "127.0.0.1:1", "X-Forwarded-For", "127.0.0.1", "127.0.0.1", true, false, false},
		{"proxy, admin from any", New(nil, anyNet), "127.0.0.1:1", "X-Forwarded-For", "203.0.113.9", "127.0.0.1", true, true, false},
		{"proxy, any with an exclusion", New(nil, allowAny, denySome...), "127.0.0.1:1", "X-Forwarded-For", "203.0.113.9", "127.0.0.1", true, false, false},
		{"trusted proxy, internet client", New(trusted, local), "127.0.0.1:1", "X-Forwarded-For", "203.0.113.9", "203.0.113.9", false, false, false},
		{"trusted proxy, client on this machine", New(trusted, local), "127.0.0.1:1", "X-Forwarded-For", "127.0.0.1", "127.0.0.1", false, true, true},
		// A request from the trusted proxy's address that names no client is
		// that machine's own, like the menu bar app's; one whose header ends
		// in garbage could be anyone the proxy serves.
		{"trusted proxy, no header", New(trusted, local), "127.0.0.1:1", "", "", "127.0.0.1", false, true, true},
		{"trusted proxy, empty header", New(trusted, local), "127.0.0.1:1", "X-Forwarded-For", "", "127.0.0.1", false, true, true},
		{"trusted proxy, no header, over ipv6", New(trusted, local), "[::1]:1", "", "", "::1", false, true, true},
		{"trusted proxy, garbage header", New(trusted, local), "127.0.0.1:1", "X-Forwarded-For", "not-an-ip", "127.0.0.1", true, false, false},
		{"trusted proxy, garbage header, admin from any", New(trusted, anyNet), "[::1]:1", "X-Forwarded-For", "not-an-ip", "::1", true, true, false},
		{"LAN client sending the header", New(nil, local), "192.168.1.20:1", "X-Forwarded-For", "203.0.113.9", "192.168.1.20", true, false, false},
	} {
		var got Info
		h := c.g.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { got, _ = From(r.Context()) }))
		r := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil)
		r.RemoteAddr = c.remote
		if c.header != "" {
			r.Header[http.CanonicalHeaderKey(c.header)] = []string{c.value}
		}
		h.ServeHTTP(httptest.NewRecorder(), r)
		if got.IP.String() != c.ip || got.Proxied != c.proxied || got.Admin != c.admin || got.Loopback() != c.loopback {
			t.Errorf("%s: ip %s proxied %v admin %v loopback %v, want %s %v %v %v",
				c.name, got.IP, got.Proxied, got.Admin, got.Loopback(), c.ip, c.proxied, c.admin, c.loopback)
		}
	}
}

// The first request from each untrusted proxy is logged once, naming it.
func TestUntrustedProxyWarning(t *testing.T) {
	var buf bytes.Buffer
	g := mustParse(t)
	g.log = slog.New(slog.NewTextHandler(&buf, nil))
	h := g.Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	send := func(remote string, forwarded bool) {
		r := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil)
		r.RemoteAddr = remote
		if forwarded {
			r.Header.Set("X-Forwarded-For", "203.0.113.9")
		}
		h.ServeHTTP(httptest.NewRecorder(), r)
	}
	send("127.0.0.1:1", false) // the menu bar app: nothing to say
	if buf.Len() != 0 {
		t.Fatalf("warned without a proxy: %s", buf.String())
	}
	send("127.0.0.1:1", true)
	send("127.0.0.1:2", true)
	send("[::1]:1", true)
	lines := strings.Split(strings.TrimSpace(buf.String()), "\n")
	if len(lines) != 2 || !strings.Contains(lines[0], "proxy=127.0.0.1") || !strings.Contains(lines[1], "proxy=::1") ||
		!strings.Contains(lines[0], "WATCHMYMEDIA_TRUSTED_PROXIES") {
		t.Errorf("warnings:\n%s", buf.String())
	}
	// A trusted proxy's own requests (no header) need no warning; one whose
	// header ends in garbage is warned about with its own advice.
	buf.Reset()
	trusted, _ := Parse([]string{"10.9.0.1"})
	g2 := New(trusted, nil)
	g2.log = slog.New(slog.NewTextHandler(&buf, nil))
	viaTrusted := func(xff string) {
		r := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/", nil)
		r.RemoteAddr = "10.9.0.1:1"
		if xff != "" {
			r.Header.Set("X-Forwarded-For", xff)
		}
		g2.Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {})).ServeHTTP(httptest.NewRecorder(), r)
	}
	viaTrusted("")
	if buf.Len() != 0 {
		t.Fatalf("warned about a trusted proxy's own request: %s", buf.String())
	}
	viaTrusted("not-an-ip")
	if got := buf.String(); !strings.Contains(got, "proxy=10.9.0.1") || !strings.Contains(got, "X-Forwarded-For") || strings.Contains(got, "not trusted") {
		t.Errorf("warning for a trusted proxy with a garbage header: %s", got)
	}
	buf.Reset()
	send("127.0.0.1:1", true)
	for i := range 2 * maxWarned {
		send(fmt.Sprintf("127.0.0.%d:1", i+2), true)
	}
	if n := strings.Count(buf.String(), "\n"); n != maxWarned-2 { // two were warned about above
		t.Errorf("%d warnings, want at most %d", n, maxWarned)
	}
}
