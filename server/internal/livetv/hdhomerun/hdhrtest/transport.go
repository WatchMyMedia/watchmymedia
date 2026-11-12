package hdhrtest

import (
	"net/http"
	"net/url"
)

type transport struct {
	target   *url.URL
	upstream http.RoundTripper
}

// Transport rewrites requests only inside tests/fixture tooling. No device
// address is contacted, even when a test is exercising a hostile lineup.
func Transport(target string, upstream ...http.RoundTripper) http.RoundTripper {
	u, err := url.Parse(target)
	if err != nil {
		panic(err)
	}
	base := http.DefaultTransport
	if len(upstream) > 0 && upstream[0] != nil {
		base = upstream[0]
	}
	return transport{u, base}
}
func (t transport) RoundTrip(r *http.Request) (*http.Response, error) {
	clone := r.Clone(r.Context())
	u := *r.URL
	u.Scheme = t.target.Scheme
	u.Host = t.target.Host
	clone.URL = &u
	return t.upstream.RoundTrip(clone)
}
