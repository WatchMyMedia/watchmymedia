// Package apiclient is what the Radarr, Sonarr and Seerr clients share: a
// JSON call with the API key in a header, the apps' error answers, and an
// http.Client that keeps the key on the app's own host.
package apiclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"slices"
	"strings"
	"time"

	"watchmymedia/server/internal/httpx"
)

// Timeout bounds every call: what the apps say is never worth holding up a
// page for.
const Timeout = 10 * time.Second

// HTTPClient returns a client that gives up after timeout and follows a
// redirect only to the same host and never from https to http. Go drops
// Authorization and cookies on a redirect to another host, but keeps every
// other header, X-Api-Key and X-Api-User among them.
func HTTPClient(timeout time.Duration) *http.Client {
	return &http.Client{Timeout: timeout, CheckRedirect: SameHost}
}

// SameHost is an http.Client CheckRedirect that refuses a redirect to
// another host, or from https to http.
func SameHost(req *http.Request, via []*http.Request) error {
	if len(via) >= 10 {
		return errors.New("stopped after 10 redirects")
	}
	from := via[0].URL
	switch {
	case !strings.EqualFold(req.URL.Host, from.Host):
		return fmt.Errorf("the app redirected to another address (%s); enter that address instead", req.URL.Host)
	case from.Scheme == "https" && req.URL.Scheme != "https":
		return errors.New("the app redirected from https to http; enter its address with http://")
	}
	return nil
}

// Client calls one app.
type Client struct {
	// App names the app in its errors: Radarr, Sonarr or Seerr.
	App string
	// Base is the app's address with its API root, without a trailing
	// slash: "http://nas:7878/radarr/api/v3".
	Base string
	Key  string
	// HTTP sends the calls; nil means HTTPClient(Timeout).
	HTTP *http.Client
	// MaxBody bounds a reply; past it the call fails. Zero means 16 MB.
	MaxBody int64
}

// Call is one call.
type Call struct {
	Method string
	Path   string
	// Query is already encoded, for apps that want their own encoding.
	Query string
	Body  any
	// Header is added to the request.
	Header http.Header
	// OK lists the statuses that mean success; none means any 2xx.
	OK []int
}

var defaultHTTP = HTTPClient(Timeout)

// Do makes the call and reads a JSON answer into out (when not nil).
func (c *Client) Do(ctx context.Context, call Call, out any) error {
	var rd io.Reader
	if call.Body != nil {
		b, err := json.Marshal(call.Body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(b)
	}
	u := c.Base + call.Path
	if call.Query != "" {
		u += "?" + call.Query
	}
	req, err := http.NewRequestWithContext(ctx, call.Method, u, rd)
	if err != nil {
		return err
	}
	req.Header.Set("X-Api-Key", c.Key)
	req.Header.Set("Accept", "application/json")
	if call.Body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	maps.Copy(req.Header, call.Header)
	hc := c.HTTP
	if hc == nil {
		hc = defaultHTTP
	}
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	limit := c.MaxBody
	if limit <= 0 {
		limit = 16 << 20
	}
	data, err := httpx.ReadLimited(resp.Body, limit)
	if err != nil {
		return err
	}
	if !success(resp.StatusCode, call.OK) {
		return &Error{App: c.App, Status: resp.StatusCode, Message: Message(resp.StatusCode, data)}
	}
	if out == nil || len(data) == 0 {
		return nil
	}
	if err := json.Unmarshal(data, out); err != nil {
		return fmt.Errorf("unexpected answer from %s: %w", call.Path, err)
	}
	return nil
}

func success(status int, ok []int) bool {
	if len(ok) == 0 {
		return status >= 200 && status < 300
	}
	return slices.Contains(ok, status)
}

// Error is the app's own answer to a call that failed.
type Error struct {
	App     string
	Status  int
	Message string
}

func (e *Error) Error() string {
	if e.Message == "" {
		return fmt.Sprintf("answered %d", e.Status)
	}
	return e.Message
}

// StatusOf is the HTTP status of the app's answer, or 0 when err is not one.
func StatusOf(err error) int {
	if e, ok := errors.AsType[*Error](err); ok {
		return e.Status
	}
	return 0
}

// IsNotFound reports whether the app said the thing asked for is not there.
func IsNotFound(err error) bool { return StatusOf(err) == http.StatusNotFound }

// Message reads the apps' error bodies: {"message": …} from their routes and
// validators, {"error": …} from Seerr's sign-in check, or Radarr's and
// Sonarr's list of validation failures [{"errorMessage": …}].
func Message(status int, data []byte) string {
	var one struct {
		Message string `json:"message"`
		Error   string `json:"error"`
	}
	if json.Unmarshal(data, &one) == nil && (one.Message != "" || one.Error != "") {
		if one.Message != "" {
			return one.Message
		}
		return one.Error
	}
	var many []struct {
		ErrorMessage string `json:"errorMessage"`
	}
	if json.Unmarshal(data, &many) == nil && len(many) > 0 {
		msgs := make([]string, 0, len(many))
		for _, m := range many {
			if m.ErrorMessage != "" {
				msgs = append(msgs, m.ErrorMessage)
			}
		}
		if len(msgs) > 0 {
			return strings.Join(msgs, "; ")
		}
	}
	switch status {
	case http.StatusUnauthorized, http.StatusForbidden:
		return "the API key was refused"
	case http.StatusNotFound:
		return "not found"
	}
	return http.StatusText(status)
}
