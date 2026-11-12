// Package plex contains the dormant protocol adapter used by the Plex
// compatibility spike. It does not update WatchMyMedia or Plex watch state.
package plex

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/httpx"
)

const defaultMaxResponseBytes = 4 << 20

// Options configures an explicitly selected endpoint. HTTPClient is primarily
// for isolated protocol fixtures; redirects and cookie forwarding are disabled
// even when a caller supplies a client. Server-side discovery/SSRF policy belongs
// to the future connection service, before it constructs this adapter.
type Options struct {
	ClientIdentifier string
	// Product names the device in Plex's list of authorized devices; the
	// probe's name when empty.
	Product          string
	HTTPClient       *http.Client
	MaxResponseBytes int64
}

// Client binds one credential to one origin. It is immutable after construction;
// switching users requires a new client, never a fallback to an owner's token.
type Client struct {
	baseURL          *url.URL
	token            string
	clientID         string
	product          string
	http             *http.Client
	maxResponseBytes int64
}

func (c Client) String() string   { return "plex.Client{redacted}" }
func (c Client) GoString() string { return c.String() }

// Error deliberately excludes upstream messages, bodies, addresses and URLs.
// In particular, PIN polling URLs can contain a signed device JWT.
type Error struct {
	Kind       string
	StatusCode int
	RetryAfter time.Duration
}

func (e *Error) Error() string {
	if e.StatusCode != 0 {
		return fmt.Sprintf("plex: %s (HTTP %d)", e.Kind, e.StatusCode)
	}
	return "plex: " + e.Kind
}

// NewClient validates a server origin, but does not contact it. A missing token
// is permitted for the new-device PIN flow and credential-free identity reads.
// A token must never be supplied in baseURL; callers retain endpoint ownership.
func NewClient(baseURL, token string, opts Options) (*Client, error) {
	u, err := url.Parse(baseURL)
	if err != nil || u == nil || (u.Scheme != "http" && u.Scheme != "https") ||
		u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery ||
		u.Fragment != "" || (u.Path != "" && u.Path != "/") || u.Opaque != "" {
		return nil, &Error{Kind: "invalid_origin"}
	}
	if u.Port() != "" {
		port, err := strconv.Atoi(u.Port())
		if err != nil || port < 1 || port > 65535 {
			return nil, &Error{Kind: "invalid_origin"}
		}
	}
	if strings.ContainsAny(token, "\r\n") || strings.ContainsAny(opts.ClientIdentifier, "\r\n") || strings.ContainsAny(opts.Product, "\r\n") {
		return nil, &Error{Kind: "invalid_credentials"}
	}
	if opts.ClientIdentifier == "" {
		return nil, &Error{Kind: "missing_client_identifier"}
	}
	hc := http.Client{Timeout: 15 * time.Second}
	if opts.HTTPClient != nil {
		hc = *opts.HTTPClient
		if hc.Timeout <= 0 {
			hc.Timeout = 15 * time.Second
		}
	}
	hc.Jar = nil
	hc.CheckRedirect = func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }
	limit := opts.MaxResponseBytes
	if limit == 0 {
		limit = defaultMaxResponseBytes
	}
	if limit < 1 || limit > 64<<20 {
		return nil, &Error{Kind: "invalid_response_limit"}
	}
	product := opts.Product
	if product == "" {
		product = "WatchMyMedia compatibility probe"
	}
	return &Client{baseURL: u, token: token, clientID: opts.ClientIdentifier, product: product, http: &hc, maxResponseBytes: limit}, nil
}

// Even Plex's GET endpoints can mutate state (scrobble/unscrobble). Keep the
// probe's request surface explicit. Never add those endpoints to this list.
var getPath = regexp.MustCompile(`^(?:/|/identity|/accounts|/system/accounts|/media/providers|/library/sections|/library/sections/[0-9]+/all|/library/metadata/[0-9]+|/status/sessions/history/all|/api/v2/user|/api/v2/resources|/api/v2/devices|/api/users/?|/api/home/users|/api/servers/[A-Za-z0-9_-]+/shared_servers|/api/v2/pins/[0-9]+|/api/v2/auth/nonce)$`)
var postPath = regexp.MustCompile(`^(?:/api/v2/pins|/api/v2/auth/token|/api/home/users/[0-9]+/switch)$`)

func (c *Client) do(ctx context.Context, method, path string, query url.Values, body any) ([]byte, error) {
	allowed := method == http.MethodGet && getPath.MatchString(path) ||
		method == http.MethodPost && postPath.MatchString(path)
	if !allowed {
		return nil, &Error{Kind: "unsupported_operation"}
	}
	var reader io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, &Error{Kind: "invalid_request"}
		}
		reader = bytes.NewReader(b)
	}
	u := *c.baseURL
	u.Path, u.RawPath, u.RawQuery = path, "", query.Encode()
	req, err := http.NewRequestWithContext(ctx, method, u.String(), reader)
	if err != nil {
		return nil, &Error{Kind: "invalid_request"}
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("X-Plex-Client-Identifier", c.clientID)
	req.Header.Set("X-Plex-Product", c.product)
	if c.token != "" {
		req.Header.Set("X-Plex-Token", c.token)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := c.http.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, &Error{Kind: "transport"}
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		kind := "http"
		switch {
		case res.StatusCode >= 300 && res.StatusCode < 400:
			kind = "redirect"
		case res.StatusCode == 401 || res.StatusCode == 403 || res.StatusCode == 498:
			kind = "authorization"
		case res.StatusCode == 429:
			kind = "rate_limit"
		case res.StatusCode == 404:
			kind = "unavailable"
		}
		return nil, &Error{Kind: kind, StatusCode: res.StatusCode, RetryAfter: retryAfter(res.Header.Get("Retry-After"))}
	}
	b, err := httpx.ReadLimited(res.Body, c.maxResponseBytes)
	switch {
	case errors.Is(err, httpx.ErrTooLarge):
		return nil, &Error{Kind: "response_too_large"}
	case err != nil && ctx.Err() != nil:
		return nil, ctx.Err()
	case err != nil:
		return nil, &Error{Kind: "response_read"}
	}
	return b, nil
}

func (c *Client) get(ctx context.Context, path string, query url.Values, out any) error {
	b, err := c.do(ctx, http.MethodGet, path, query, nil)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(b, out); err != nil {
		return &Error{Kind: "invalid_response"}
	}
	return nil
}

func retryAfter(value string) time.Duration {
	d, _ := httpx.RetryAfter(value, time.Now(), 24*time.Hour)
	return d
}

// ErrorKind is a stable, non-sensitive reason code for reports. Unknown errors
// are deliberately not formatted into diagnostic output.
func ErrorKind(err error) string {
	if pe, ok := errors.AsType[*Error](err); ok {
		return pe.Kind
	}
	if errors.Is(err, context.Canceled) {
		return "canceled"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	return "unknown"
}
