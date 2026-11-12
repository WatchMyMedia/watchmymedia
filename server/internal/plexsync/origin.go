package plexsync

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// errOrigin is a Plex server address WatchMyMedia will not send credentials
// to.
var errOrigin = errors.New("plexsync: address not allowed")

// cleanOrigin accepts an admin-chosen Plex server address: http or https, a
// host and an optional port, and nothing else (no user, path, query or
// fragment). A LAN or loopback server is allowed, being the usual case;
// link-local and cloud metadata addresses are not. Names are checked again
// when dialled (allowedDial), so a name that later resolves elsewhere
// cannot redirect the server's credentials.
func cleanOrigin(raw string) (string, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil ||
		u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || (u.Path != "" && u.Path != "/") || u.Opaque != "" {
		return "", errOrigin
	}
	if p := u.Port(); p != "" {
		if n, err := strconv.Atoi(p); err != nil || n < 1 || n > 65535 {
			return "", errOrigin
		}
	}
	if addr, err := netip.ParseAddr(u.Hostname()); err == nil && !allowedAddr(addr) {
		return "", errOrigin
	}
	return u.Scheme + "://" + u.Host, nil
}

// Cloud providers' instance metadata services, beyond the link-local
// 169.254.169.254 that allowedAddr already refuses.
var metadataAddrs = []netip.Addr{
	netip.MustParseAddr("fd00:ec2::254"),   // AWS over IPv6
	netip.MustParseAddr("100.100.100.200"), // Alibaba Cloud
}

func allowedAddr(addr netip.Addr) bool {
	addr = addr.Unmap()
	if !addr.IsValid() || addr.IsUnspecified() || addr.IsLinkLocalUnicast() || addr.IsLinkLocalMulticast() ||
		addr.IsInterfaceLocalMulticast() || addr.IsMulticast() || addr == netip.AddrFrom4([4]byte{255, 255, 255, 255}) {
		return false
	}
	return !slices.Contains(metadataAddrs, addr)
}

// allowedDial is the dialer's last check, on the address a name actually
// resolved to.
func allowedDial(_, address string, _ syscall.RawConn) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return errOrigin
	}
	addr, err := netip.ParseAddr(host)
	if err != nil || !allowedAddr(addr) {
		return errOrigin
	}
	return nil
}

// serverHTTPClient talks to the chosen Plex server only: no proxy (it is on
// the LAN), every dial checked, bounded timeouts. The adapter refuses
// redirects and cookies itself.
func serverHTTPClient() *http.Client {
	dialer := &net.Dialer{Timeout: 10 * time.Second, Control: allowedDial}
	return &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.DialContext(ctx, network, addr)
			},
			TLSHandshakeTimeout:   10 * time.Second,
			ResponseHeaderTimeout: 30 * time.Second,
			MaxIdleConnsPerHost:   2,
			IdleConnTimeout:       time.Minute,
			ForceAttemptHTTP2:     true,
		},
	}
}
