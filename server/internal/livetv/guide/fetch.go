package guide

import (
	"bufio"
	"compress/gzip"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"watchmymedia/server/internal/httpx"
	"watchmymedia/server/internal/integrations/apiclient"
	"watchmymedia/server/internal/livetv/guide/xmltv"
)

// feedURL is SiliconDust's XMLTV feed. There is no other: tests swap the
// transport, never the address.
const feedURL = "https://api.hdhomerun.com/api/xmltv"

// The feed's size limits, G0's measurement of 14 days times four (§5.1),
// past which a fetch fails rather than keeping part of the listings.
var (
	maxCompressed int64 = 32 << 20
	maxXML        int64 = 256 << 20
)

// feedClient is the client for SiliconDust's API: 30 s to the reply's
// headers, 3 minutes in all, and no redirect to another host or from HTTPS.
func feedClient(transport http.RoundTripper) *http.Client {
	if transport == nil {
		t := http.DefaultTransport.(*http.Transport).Clone()
		t.ResponseHeaderTimeout = 30 * time.Second
		t.MaxResponseHeaderBytes = 64 << 10
		transport = t
	}
	return &http.Client{Transport: transport, Timeout: 3 * time.Minute, CheckRedirect: apiclient.SameHost}
}

// fetchError is a failed fetch: the state it leaves and, for 429 and 5xx,
// how long SiliconDust asked us to wait.
type fetchError struct {
	state string
	wait  time.Duration
	err   error
}

func (e *fetchError) Error() string { return e.state + ": " + e.err.Error() }
func (e *fetchError) Unwrap() error { return e.err }

func failed(state string, err error) *fetchError { return &fetchError{state: state, err: err} }

// fetchFeed asks for the feed with query (DeviceAuth, or Email and
// DeviceIDs) and parses it. Errors never carry the query: http.Client's
// errors go through httpx.Redact, and no other error repeats the URL.
func (s *Service) fetchFeed(ctx context.Context, query url.Values, opt xmltv.Options) (xmltv.Feed, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, feedURL+"?"+query.Encode(), nil)
	if err != nil {
		return xmltv.Feed{}, failed(StateFailed, errors.New("could not make the request"))
	}
	// Asking for gzip ourselves turns off Go's transparent decompression, so
	// the compressed size can be limited apart from the XML's.
	req.Header.Set("Accept-Encoding", "gzip")
	req.Header.Set("User-Agent", "WatchMyMedia")
	res, err := s.client.Do(req)
	if err != nil {
		return xmltv.Feed{}, failed(StateUnavailable, httpx.Redact(err))
	}
	defer func() { _ = res.Body.Close() }()
	switch {
	case res.StatusCode == http.StatusOK:
	case res.StatusCode == http.StatusForbidden || res.StatusCode == http.StatusUnauthorized:
		return xmltv.Feed{}, failed(StateRefused, fmt.Errorf("SiliconDust answered %d", res.StatusCode))
	case res.StatusCode == http.StatusTooManyRequests || res.StatusCode >= 500:
		e := failed(StateUnavailable, fmt.Errorf("SiliconDust answered %d", res.StatusCode))
		e.wait, _ = httpx.RetryAfter(res.Header.Get("Retry-After"), s.now(), 24*time.Hour)
		return xmltv.Feed{}, e
	default:
		return xmltv.Feed{}, failed(StateFailed, fmt.Errorf("SiliconDust answered %d", res.StatusCode))
	}
	body := bufio.NewReader(&limited{r: res.Body, n: maxCompressed})
	var xml io.Reader = body
	// The reply is gzip when it says so, and in practice always; sniff too,
	// in case a proxy drops the header but not the encoding.
	if magic, _ := body.Peek(2); strings.EqualFold(res.Header.Get("Content-Encoding"), "gzip") || string(magic) == "\x1f\x8b" {
		gz, err := gzip.NewReader(body)
		if err != nil {
			return xmltv.Feed{}, failed(StateFailed, fmt.Errorf("the feed isn't valid gzip: %w", err))
		}
		defer func() { _ = gz.Close() }()
		xml = gz
	}
	feed, err := xmltv.Parse(&limited{r: xml, n: maxXML}, opt)
	if err != nil {
		return xmltv.Feed{}, failed(StateFailed, fmt.Errorf("the feed couldn't be read: %w", err))
	}
	return feed, nil
}

// limited reads at most n bytes from r and then fails with
// httpx.ErrTooLarge if r has more, rather than ending early as
// io.LimitReader does.
type limited struct {
	r io.Reader
	n int64
}

func (l *limited) Read(p []byte) (int, error) {
	if l.n <= 0 {
		var one [1]byte
		n, err := l.r.Read(one[:])
		if n > 0 {
			return 0, httpx.ErrTooLarge
		}
		return 0, err
	}
	if int64(len(p)) > l.n {
		p = p[:l.n]
	}
	n, err := l.r.Read(p)
	l.n -= int64(n)
	return n, err
}
