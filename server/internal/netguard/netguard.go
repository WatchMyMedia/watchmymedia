// Package netguard works out the real client address behind trusted
// reverse proxies and decides whether that address may use admin features.
package netguard

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
)

// Local is the keyword for loopback, private LAN, link-local and Tailscale
// addresses. It is the default for admin networks.
const Local = "local"

// Any is the keyword for every address.
const Any = "any"

var localPrefixes = []netip.Prefix{
	netip.MustParsePrefix("127.0.0.0/8"),
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("169.254.0.0/16"),
	netip.MustParsePrefix("100.64.0.0/10"), // Tailscale (and carrier-grade NAT)
	netip.MustParsePrefix("::1/128"),
	netip.MustParsePrefix("fc00::/7"), // unique local, includes Tailscale's fd7a:115c:a1e0::/48
	netip.MustParsePrefix("fe80::/10"),
}

var tailnetPrefixes = []netip.Prefix{
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("fd7a:115c:a1e0::/48"),
}

// Network classes for activity: the home network, a Tailscale tailnet, or
// the internet.
const (
	NetLocal   = "local"
	NetTailnet = "tailnet"
	NetRemote  = "remote"
)

// Classify says which kind of network an address is on. Tailscale's ranges
// win over the wider local ones that contain them.
func Classify(a netip.Addr) string {
	a = a.Unmap()
	switch {
	case !a.IsValid():
		return NetRemote
	case contains(tailnetPrefixes, a):
		return NetTailnet
	case contains(localPrefixes, a):
		return NetLocal
	}
	return NetRemote
}

// ParseRules splits a list into allowed and excluded prefixes. Entries
// starting with "!" are exclusions, which win over any allowed range: for
// example "local,!192.168.65.0/24" allows local networks except Docker
// Desktop's gateway range.
func ParseRules(list []string) (allow, deny []netip.Prefix, err error) {
	var in, out []string
	for _, raw := range list {
		s := strings.TrimSpace(raw)
		if rest, ok := strings.CutPrefix(s, "!"); ok {
			out = append(out, rest)
		} else {
			in = append(in, s)
		}
	}
	if allow, err = Parse(in); err != nil {
		return nil, nil, err
	}
	if deny, err = Parse(out); err != nil {
		return nil, nil, err
	}
	return allow, deny, nil
}

// Parse turns addresses, CIDR ranges and the keywords "local" and "any"
// into prefixes.
func Parse(list []string) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, raw := range list {
		s := strings.TrimSpace(raw)
		if strings.HasPrefix(s, "!") {
			return nil, fmt.Errorf("%q: exclusions are not allowed here", s)
		}
		switch strings.ToLower(s) {
		case "":
			continue
		case Local:
			out = append(out, localPrefixes...)
			continue
		case Any:
			out = append(out, netip.MustParsePrefix("0.0.0.0/0"), netip.MustParsePrefix("::/0"))
			continue
		}
		if strings.Contains(s, "/") {
			p, err := netip.ParsePrefix(s)
			if err != nil {
				return nil, fmt.Errorf("%q is not an address or CIDR range", s)
			}
			out = append(out, p.Masked())
			continue
		}
		a, err := netip.ParseAddr(s)
		if err != nil {
			return nil, fmt.Errorf("%q is not an address or CIDR range", s)
		}
		a = a.Unmap()
		out = append(out, netip.PrefixFrom(a, a.BitLen()))
	}
	return out, nil
}

// SplitList splits a comma-separated env value.
func SplitList(s string) []string {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	return strings.Split(s, ",")
}

func contains(ps []netip.Prefix, a netip.Addr) bool {
	for _, p := range ps {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// Guard holds the trusted proxies and admin networks.
type Guard struct {
	trusted     []netip.Prefix
	admin       []netip.Prefix
	adminDeny   []netip.Prefix
	remoteAdmin atomic.Bool
	adminAll    bool // admin networks take every address, as "any" does

	log    *slog.Logger // nil is slog.Default()
	mu     sync.Mutex
	warned map[netip.Addr]bool // untrusted proxies already logged
}

// New builds a guard from parsed prefixes. adminExcluded wins over
// adminNetworks.
func New(trustedProxies, adminNetworks []netip.Prefix, adminExcluded ...netip.Prefix) *Guard {
	g := &Guard{trusted: trustedProxies, admin: adminNetworks, adminDeny: adminExcluded,
		adminAll: coversAll(adminNetworks, adminExcluded)}
	g.SetRemoteAdmin(true)
	return g
}

// SetRemoteAdmin changes whether HTTPS clients may administer away from trusted networks.
func (g *Guard) SetRemoteAdmin(enabled bool) { g.remoteAdmin.Store(enabled) }

func (g *Guard) RemoteAdmin() bool { return g.remoteAdmin.Load() }

// Allowed rechecks a request against the current switch, including long-lived streams.
func (g *Guard) Allowed(ctx context.Context) bool {
	i, ok := From(ctx)
	return ok && (i.Trusted || (g.RemoteAdmin() && i.HTTPS && !i.unnamed))
}

// Trusted reports whether setup and password-free admin work on this network.
func Trusted(ctx context.Context) bool { i, ok := From(ctx); return ok && i.Trusted }

// coversAll reports whether allow takes every IPv4 and IPv6 address and deny
// takes none back.
func coversAll(allow, deny []netip.Prefix) bool {
	var v4, v6 bool
	for _, p := range allow {
		if p.Bits() == 0 {
			v4, v6 = v4 || p.Addr().Is4(), v6 || p.Addr().Is6()
		}
	}
	return v4 && v6 && len(deny) == 0
}

// Default trusts no proxies and allows admin features from local networks.
func Default() *Guard { return New(nil, localPrefixes) }

// Info is what the middleware learned about a request's client.
type Info struct {
	IP netip.Addr
	// Proxied is true when the real client is unknown: a forwarding peer
	// that is not in trusted proxies forwarded the request (see
	// Guard.untrustedProxy), or a trusted proxy sent it without naming a
	// client in X-Forwarded-For. IP is then the proxy's address and the
	// real client could be anywhere, so it counts as remote: use Loopback
	// rather than IP.IsLoopback, and do not classify IP.
	Proxied     bool
	unnamed     bool // a trusted proxy named no client: no admin, even over HTTPS
	RemoteAdmin bool // snapshot of the switch for refusal wording
	Trusted     bool // setup and password-free admin work here
	Admin       bool // the client may use admin features from here
	// HTTPS is true when the client connected over TLS: to this server, or
	// to a proxy that says so in X-Forwarded-Proto and is either trusted or
	// on a trusted network (see Guard.HTTPS).
	HTTPS bool
}

// Loopback reports whether the client is on this machine itself, not
// relayed by a proxy here.
func (i Info) Loopback() bool { return !i.Proxied && i.IP.IsLoopback() }

type ctxKey struct{}

// NewContext stores info as the middleware would, for tests and internal
// callers.
func NewContext(ctx context.Context, info Info) context.Context {
	return context.WithValue(ctx, ctxKey{}, info)
}

// From returns the client info the middleware stored.
func From(ctx context.Context) (Info, bool) {
	i, ok := ctx.Value(ctxKey{}).(Info)
	return i, ok
}

// AdminAllowed reports whether admin features work on this request.
// Requests that did not pass through the middleware are refused.
func AdminAllowed(ctx context.Context) bool {
	i, ok := From(ctx)
	return ok && i.Admin
}

// ClientIP returns the address of the client that made the request. The
// connection's address is used unless it is a trusted proxy; then
// X-Forwarded-For is read from the right, skipping trusted proxies, so a
// client cannot spoof its address by sending the header itself. When the
// header names no client, it is the last trusted hop's address, and the
// middleware counts the client as unknown (see clientIP).
func (g *Guard) ClientIP(r *http.Request) netip.Addr {
	a, _ := g.clientIP(r)
	return a
}

// clientIP is ClientIP, also reporting whether the address is the client's.
// It is not when a trusted proxy sent an X-Forwarded-For that ends in
// something other than an address: the request could have come from anyone
// the proxy serves, and taking the proxy's own address (loopback, for a
// proxy on this machine) would let every one of them in as local. A request
// from a trusted proxy's address that names no client at all is that
// machine's own, such as the menu bar app asking the server for its status;
// a proxy names the client it forwards (README: trusted proxies must send
// X-Forwarded-For).
func (g *Guard) clientIP(r *http.Request) (netip.Addr, bool) {
	remote := remoteAddr(r.RemoteAddr)
	if !remote.IsValid() || !contains(g.trusted, remote) {
		return remote, true
	}
	var hops []string
	for _, h := range r.Header.Values("X-Forwarded-For") {
		for hop := range strings.SplitSeq(h, ",") {
			if strings.TrimSpace(hop) != "" {
				hops = append(hops, hop)
			}
		}
	}
	client := remote
	for _, hop := range slices.Backward(hops) {
		a := parseHop(hop)
		if !a.IsValid() {
			return client, false // garbage: whoever is behind the last trusted hop is unknown
		}
		client = a
		if !contains(g.trusted, a) {
			return client, true
		}
	}
	// No hop named, or every hop named is a trusted proxy: the first of them
	// (or the connection's own address) made the request itself, such as a
	// browser on the proxy's machine.
	return client, true
}

// HTTPS reports whether the client connected over TLS. Behind a proxy that
// is the proxy's X-Forwarded-Proto, believed from a trusted proxy and from
// a forwarding peer on a trusted network, such as a reverse proxy on this
// machine that nobody listed in trusted proxies. That peer's clients stay
// unknown (no setup, no password-free admin), but an admin among them, on
// the proxy's HTTPS address, is no worse placed than any admin away from
// home. Lying gains such a peer nothing: without forwarding headers it
// would be a trusted client itself, with more than HTTPS gives. A peer
// anywhere else cannot claim it.
func (g *Guard) HTTPS(r *http.Request) bool {
	if r.TLS != nil {
		return true
	}
	remote := remoteAddr(r.RemoteAddr)
	if !remote.IsValid() {
		return false
	}
	if !contains(g.trusted, remote) && (!g.untrustedProxy(r) || !g.onTrustedNetwork(remote)) {
		return false
	}
	proto, _, _ := strings.Cut(r.Header.Get("X-Forwarded-Proto"), ",")
	return strings.EqualFold(strings.TrimSpace(proto), "https")
}

// onTrustedNetwork reports whether a is on the admin networks.
func (g *Guard) onTrustedNetwork(a netip.Addr) bool {
	return a.IsValid() && contains(g.admin, a) && !contains(g.adminDeny, a)
}

// forwardHeaders name the client a proxy forwarded a request for.
var forwardHeaders = []string{"X-Forwarded-For", "Forwarded", "X-Real-Ip"}

// untrustedProxy refuses local privileges when an untrusted peer supplies
// forwarding headers. Native servers can see Docker proxies as RFC1918 or
// IPv6 peers as well as loopback. Never infer the original client's network
// from that peer; a direct client adding a header can only lose privileges.
func (g *Guard) untrustedProxy(r *http.Request) bool {
	remote := remoteAddr(r.RemoteAddr)
	if !remote.IsValid() || contains(g.trusted, remote) {
		return false
	}
	for _, h := range forwardHeaders {
		if len(r.Header.Values(h)) > 0 {
			return true
		}
	}
	return false
}

// maxWarned bounds the untrusted proxies warned about, one line each.
const maxWarned = 8

// Warnings about proxies, logged once per proxy address (warnProxy).
const (
	untrustedWarning = "a proxy on this machine forwarded a request but is not trusted, so its clients count as unknown internet clients: no first-run setup, admin as away from home (HTTPS, remote admin on, the password asked again), and no client address in history; " +
		"if it is your reverse proxy, add it to network.trusted_proxies (WATCHMYMEDIA_TRUSTED_PROXIES)"
	unnamedWarning = "a trusted proxy sent an X-Forwarded-For that does not end in a client address, so the client counts as an unknown internet client: no first-run setup, no admin settings, and no client address in history; " +
		"check the proxy's X-Forwarded-For setting"
)

// warnProxy logs the first request from each proxy that leaves its client
// unknown, saying what to do.
func (g *Guard) warnProxy(proxy netip.Addr, msg string) {
	g.mu.Lock()
	if g.warned[proxy] || len(g.warned) >= maxWarned {
		g.mu.Unlock()
		return
	}
	if g.warned == nil {
		g.warned = map[netip.Addr]bool{}
	}
	g.warned[proxy] = true
	g.mu.Unlock()
	log := g.log
	if log == nil {
		log = slog.Default()
	}
	log.Warn(msg, "proxy", proxy.String())
}

// Middleware stores Info on every request's context.
func (g *Guard) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, named := g.clientIP(r)
		info := Info{IP: ip, Trusted: g.onTrustedNetwork(ip), HTTPS: g.HTTPS(r)}
		switch {
		case g.untrustedProxy(r):
			// The client is unknown: only admin networks that take
			// every address admit it.
			info.Proxied, info.Trusted = true, g.adminAll
			g.warnProxy(ip, untrustedWarning)
		case !named:
			info.Proxied, info.unnamed, info.Trusted = true, true, g.adminAll
			g.warnProxy(ip, unnamedWarning)
		}
		info.RemoteAdmin = g.RemoteAdmin()
		// An untrusted proxy's client is an internet client: over HTTPS
		// it gets what any admin away from home gets, never what Trusted
		// gives. A trusted proxy that named no client is misconfigured,
		// and stays closed.
		info.Admin = info.Trusted || (g.RemoteAdmin() && info.HTTPS && !info.unnamed)
		next.ServeHTTP(w, r.WithContext(NewContext(r.Context(), info)))
	})
}

func remoteAddr(s string) netip.Addr {
	if ap, err := netip.ParseAddrPort(s); err == nil {
		return ap.Addr().Unmap()
	}
	host, _, err := net.SplitHostPort(s)
	if err != nil {
		host = s
	}
	a, err := netip.ParseAddr(strings.Trim(host, "[]"))
	if err != nil {
		return netip.Addr{}
	}
	return a.Unmap()
}

func parseHop(s string) netip.Addr {
	s = strings.TrimSpace(s)
	if a, err := netip.ParseAddr(s); err == nil {
		return a.Unmap()
	}
	return remoteAddr(s) // "1.2.3.4:5678" or "[::1]:80"
}
