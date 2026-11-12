// Package guidetest holds the listings' fakes: a fictional XMLTV feed, a
// fake SiliconDust API and a fake picture host. Nothing here contacts
// SiliconDust or a tuner.
package guidetest

import (
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

type transport struct{ hosts map[string]*url.URL }

// Transport sends requests for each named host (such as
// "api.hdhomerun.com") to its fake's address (an httptest server's URL),
// over plain HTTP. A request for any other host fails, so a test can't
// reach the internet by mistake.
func Transport(hosts map[string]string) http.RoundTripper {
	t := transport{hosts: map[string]*url.URL{}}
	for host, target := range hosts {
		u, err := url.Parse(target)
		if err != nil {
			panic(err)
		}
		t.hosts[strings.ToLower(host)] = u
	}
	return t
}

func (t transport) RoundTrip(r *http.Request) (*http.Response, error) {
	target, ok := t.hosts[strings.ToLower(r.URL.Hostname())]
	if !ok {
		return nil, fmt.Errorf("guidetest: no fake for %s", r.URL.Hostname())
	}
	clone := r.Clone(r.Context())
	u := *r.URL
	u.Scheme, u.Host = target.Scheme, target.Host
	clone.URL = &u
	clone.Host = r.URL.Host
	return http.DefaultTransport.RoundTrip(clone)
}
