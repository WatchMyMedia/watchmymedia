package artwork

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"syscall"
	"time"

	"watchmymedia/server/internal/httpx"
	"watchmymedia/server/internal/netguard"
)

// fetchTimeout bounds getting a linked picture, body included.
const fetchTimeout = 30 * time.Second

var errBlocked = errors.New("address not allowed")

// publicAddr reports whether a dialled address ("ip:port") is on the
// internet: not this machine, the home network, a tailnet or any other
// range that is not routed globally (PLAN-POSTERS.md, decision 10).
func publicAddr(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return false
	}
	a, err := netip.ParseAddr(host)
	if err != nil {
		return false
	}
	a = a.Unmap()
	return a.IsGlobalUnicast() && !a.IsPrivate() && !a.IsLoopback() && !a.IsLinkLocalUnicast() &&
		netguard.Classify(a) == netguard.NetRemote
}

// get opens the picture at rawURL. Each connection's address is checked
// after DNS, so a name that resolves into the home network, and a redirect
// to one, are refused too. No proxy is used: the check must see the host
// the picture comes from.
func (s *Store) get(ctx context.Context, rawURL string) (io.ReadCloser, error) {
	u, err := url.Parse(rawURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return nil, &FetchError{Err: errors.New("the link must start with http:// or https://")}
	}
	dialer := &net.Dialer{Timeout: 10 * time.Second, Control: func(_, address string, _ syscall.RawConn) error {
		if !s.allowAddr(address) {
			return errBlocked
		}
		return nil
	}}
	client := &http.Client{
		Timeout: fetchTimeout,
		Transport: &http.Transport{
			DialContext:           dialer.DialContext,
			TLSHandshakeTimeout:   10 * time.Second,
			ResponseHeaderTimeout: fetchTimeout,
			DisableKeepAlives:     true,
		},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) > 3 {
				return errors.New("too many redirects")
			}
			if req.URL.Scheme != "http" && req.URL.Scheme != "https" {
				return errors.New("redirect to another scheme")
			}
			return nil
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, &FetchError{Err: err}
	}
	req.Header.Set("Accept", "image/jpeg, image/png, image/webp")
	res, err := client.Do(req)
	if err != nil {
		return nil, &FetchError{Blocked: errors.Is(err, errBlocked), Err: httpx.Redact(err)}
	}
	if res.StatusCode != http.StatusOK {
		_ = res.Body.Close()
		return nil, &FetchError{Err: errors.New("the host answered " + res.Status)}
	}
	return res.Body, nil
}
