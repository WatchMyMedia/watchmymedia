package plex

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestOriginValidation(t *testing.T) {
	for _, origin := range []string{"file:///private", "https://user:secret@example.test", "https://example.test?X-Plex-Token=secret", "https://example.test/#secret", "https://example.test/api", "https://example.test:99999", "//example.test", "https://"} {
		t.Run(origin, func(t *testing.T) {
			_, err := NewClient(origin, "secret", Options{ClientIdentifier: "fixture"})
			if ErrorKind(err) != "invalid_origin" || strings.Contains(err.Error(), "secret") {
				t.Fatalf("unsafe origin accepted or exposed: %v", err)
			}
		})
	}
}

func TestTransportBindsHeadersAndRefusesRedirects(t *testing.T) {
	for _, status := range []int{301, 302, 307, 308} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			leaked := false
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { leaked = true }))
			defer target.Close()
			source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("X-Plex-Token") != "synthetic-token" || r.Header.Get("X-Plex-Client-Identifier") != "fixture" || r.Header.Get("Accept") != "application/json" {
					t.Error("required headers not bound to source")
				}
				if r.URL.Query().Has("X-Plex-Token") || r.Header.Get("Cookie") != "" {
					t.Error("credential in query or unrelated cookie forwarded")
				}
				http.Redirect(w, r, target.URL+"/identity", status)
			}))
			defer source.Close()
			jar, _ := cookiejar.New(nil)
			u, _ := url.Parse(source.URL)
			jar.SetCookies(u, []*http.Cookie{{Name: "unrelated", Value: "private"}}) //nolint:gosec // G124: a cookie the client must not send, not one served
			c, err := NewClient(source.URL, "synthetic-token", Options{ClientIdentifier: "fixture", HTTPClient: &http.Client{Jar: jar}})
			if err != nil {
				t.Fatal(err)
			}
			_, err = c.do(t.Context(), http.MethodGet, "/identity", nil, nil)
			if ErrorKind(err) != "redirect" || leaked {
				t.Fatalf("redirect was not contained: %v, leaked=%v", err, leaked)
			}
		})
	}
}

func TestTransportRejectsMutatingAndArbitraryPaths(t *testing.T) {
	c, _ := NewClient("https://example.test", "synthetic-token", Options{ClientIdentifier: "fixture"})
	for _, pair := range [][2]string{{"GET", "/:/scrobble"}, {"GET", "/:/unscrobble"}, {"DELETE", "/library/metadata/1"}, {"POST", "/api/v2/auth/jwk"}, {"POST", "/api/home/users"}, {"GET", "https://other.test/identity"}, {"GET", "/library/metadata/../identity"}, {"GET", "//other.test/identity"}} {
		_, err := c.do(t.Context(), pair[0], pair[1], nil, nil)
		if ErrorKind(err) != "unsupported_operation" {
			t.Fatalf("%v: %v", pair, err)
		}
	}
}

func TestTransportRedactionAndBounds(t *testing.T) {
	for _, tc := range []struct {
		name, body, want string
		status           int
		limit            int64
	}{
		{"invalid_json", `{"private": "synthetic-secret"`, "invalid_response", 200, 256},
		{"large", strings.Repeat("synthetic-secret", 30), "response_too_large", 200, 16},
		{"expired", "synthetic-secret", "authorization", 498, 256},
		{"denied", "synthetic-secret", "authorization", 403, 256},
		{"rate", "synthetic-secret", "rate_limit", 429, 256},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Retry-After", "120")
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()
			c, _ := NewClient(srv.URL, "synthetic-secret", Options{ClientIdentifier: "fixture", MaxResponseBytes: tc.limit})
			var out any
			err := c.get(t.Context(), "/api/v2/pins/1", url.Values{"deviceJWT": {"synthetic-secret"}}, &out)
			if ErrorKind(err) != tc.want || strings.Contains(fmt.Sprint(err), "synthetic-secret") || strings.Contains(fmt.Sprint(err), srv.URL) {
				t.Fatalf("unsafe/wrong error: %v", err)
			}
			if tc.status == 429 {
				var pe *Error
				if !errors.As(err, &pe) || pe.RetryAfter != 2*time.Minute {
					t.Fatalf("lost retry delay: %v", err)
				}
			}
		})
	}
}

type failingTransport struct{}

func (failingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, errors.New("https://private.test/api/v2/pins/1?deviceJWT=synthetic-secret")
}

func TestTransportErrorsCannotExposeSignedURLs(t *testing.T) {
	c, _ := NewClient("https://private.test", "synthetic-secret", Options{ClientIdentifier: "fixture", HTTPClient: &http.Client{Transport: failingTransport{}}})
	_, err := c.do(t.Context(), "GET", "/api/v2/pins/1", url.Values{"deviceJWT": {"synthetic-secret"}}, nil)
	if ErrorKind(err) != "transport" || strings.Contains(err.Error(), "secret") || strings.Contains(err.Error(), "private.test") {
		t.Fatalf("unsafe error: %v", err)
	}
	if strings.Contains(fmt.Sprintf("%+v %#v %+v %#v", c, c, *c, *c), "secret") {
		t.Fatal("formatted client exposed credential")
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	_, err = c.do(ctx, "GET", "/identity", nil, nil)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("lost cancellation: %v", err)
	}
}
