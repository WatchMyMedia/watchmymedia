package netguard

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRemoteAdminMatrix(t *testing.T) {
	t.Parallel()
	proxies, _ := Parse([]string{"127.0.0.1"})
	local, deny, _ := ParseRules([]string{"local", "!192.168.65.0/24"})
	for _, on := range []bool{false, true} {
		for _, https := range []bool{false, true} {
			for _, tc := range []struct {
				name, ip string
				trusted  bool
			}{
				{"home", "192.168.1.5", true}, {"tailnet", "100.100.1.5", true}, {"away", "198.51.100.7", false}, {"excluded", "192.168.65.1", false}, {"unnamed", "invalid", false},
			} {
				t.Run(tc.name+map[bool]string{true: "/on", false: "/off"}[on]+map[bool]string{true: "/https", false: "/http"}[https], func(t *testing.T) {
					g := New(proxies, local, deny...)
					g.SetRemoteAdmin(on)
					req := httptest.NewRequest("GET", "/", nil)
					req.RemoteAddr = "127.0.0.1:1234"
					req.Header.Set("X-Forwarded-For", tc.ip)
					if https {
						req.Header.Set("X-Forwarded-Proto", "https")
					}
					g.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
						i, _ := From(r.Context())
						want := tc.trusted || on && https && tc.name != "unnamed"
						if i.Trusted != tc.trusted || i.Admin != want || g.Allowed(r.Context()) != want {
							t.Fatalf("got %+v, want trusted=%v admin=%v", i, tc.trusted, want)
						}
						g.SetRemoteAdmin(false)
						if g.Allowed(r.Context()) != tc.trusted {
							t.Fatal("long-lived context retained remote admin")
						}
					})).ServeHTTP(httptest.NewRecorder(), req)
				})
			}
		}
	}
}
