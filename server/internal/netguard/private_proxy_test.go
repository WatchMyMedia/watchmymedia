package netguard

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestUntrustedPrivateProxyCannotGrantAdmin(t *testing.T) {
	for _, remote := range []string{"127.0.0.1:40000", "172.31.250.2:40000", "[fd00::2]:40000", "192.168.1.2:40000"} {
		t.Run(remote, func(t *testing.T) {
			g := Default()
			req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/auth/status", nil)
			req.RemoteAddr = remote
			req.Header.Set("X-Forwarded-For", "203.0.113.9")
			var got Info
			g.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
				got, _ = From(r.Context())
			})).ServeHTTP(httptest.NewRecorder(), req)
			wantAdmin := false
			if got.Admin != wantAdmin {
				t.Fatalf("unexpected classification: %+v", got)
			}
			t.Logf("untrusted forwarding peer=%s, adminAllowed=%v, unknownProxy=%v", remote, got.Admin, got.Proxied)
		})
	}
}

// A reverse proxy on a trusted network that nobody listed in trusted
// proxies still says whether the visit was HTTPS. Its clients are unknown,
// so never trusted, but an admin among them is an admin away from home.
func TestUntrustedProxyOnTrustedNetworkOpensRemoteAdmin(t *testing.T) {
	local, deny, _ := ParseRules([]string{Local, "!192.168.65.0/24"})
	for _, c := range []struct {
		name, remote, proto string
		on, admin           bool
	}{
		{"this machine, https", "127.0.0.1:40000", "https", true, true},
		{"this machine, https, ipv6", "[::1]:40000", "https", true, true},
		{"LAN proxy, https", "192.168.1.2:40000", "https", true, true},
		{"this machine, http", "127.0.0.1:40000", "http", true, false},
		{"this machine, no proto", "127.0.0.1:40000", "", true, false},
		{"this machine, https, remote admin off", "127.0.0.1:40000", "https", false, false},
		{"excluded range, https", "192.168.65.1:40000", "https", true, false},
		{"internet peer, https", "203.0.113.50:40000", "https", true, false},
	} {
		t.Run(c.name, func(t *testing.T) {
			g := New(nil, local, deny...)
			g.SetRemoteAdmin(c.on)
			req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/users", nil)
			req.RemoteAddr = c.remote
			req.Header.Set("X-Forwarded-For", "203.0.113.9")
			if c.proto != "" {
				req.Header.Set("X-Forwarded-Proto", c.proto)
			}
			g.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
				got, _ := From(r.Context())
				if got.Trusted || !got.Proxied || got.Loopback() {
					t.Errorf("the proxy's client must stay unknown: %+v", got)
				}
				if got.Admin != c.admin || g.Allowed(r.Context()) != c.admin {
					t.Errorf("admin = %v (Allowed %v), want %v: %+v", got.Admin, g.Allowed(r.Context()), c.admin, got)
				}
			})).ServeHTTP(httptest.NewRecorder(), req)
		})
	}
}
