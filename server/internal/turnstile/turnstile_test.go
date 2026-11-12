package turnstile

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"sync/atomic"
	"testing"
)

// Cloudflare's published test keys.
const (
	passSite   = "1x00000000000000000000AA"
	passSecret = "1x0000000000000000000000000000000AA"
	failSecret = "2x0000000000000000000000000000000AA"
)

// fake answers like siteverify does for the test secrets: the passing one
// accepts the dummy token, the failing one rejects every token, and any
// other secret is unknown.
func fake(t *testing.T, seen *[]url.Values) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = r.ParseForm()
		if seen != nil {
			*seen = append(*seen, r.PostForm)
		}
		var out result
		switch r.PostForm.Get("secret") {
		case passSecret:
			out.Success = r.PostForm.Get("response") == DummyToken
			if !out.Success {
				out.ErrorCodes = []string{"invalid-input-response"}
			}
		case failSecret:
			out.ErrorCodes = []string{"invalid-input-response"}
		default:
			out.ErrorCodes = []string{"invalid-input-secret"}
		}
		_ = json.NewEncoder(w).Encode(out)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestOffUntilBothKeys(t *testing.T) {
	v := New()
	if v.Required() || v.SiteKey() != "" {
		t.Fatal("on without keys")
	}
	if err := v.Verify(context.Background(), "", ""); err != nil {
		t.Errorf("verify while off -> %v", err)
	}
	v.SetKeys(passSite, "")
	if v.Required() {
		t.Error("on without a secret")
	}
	v.SetKeys(passSite, passSecret)
	if !v.Required() || v.SiteKey() != passSite {
		t.Error("off with both keys")
	}
	v.Disabled = true
	if v.Required() || v.SiteKey() != "" {
		t.Error("on while disabled")
	}
	var nilV *Verifier
	if nilV.Required() {
		t.Error("nil verifier on")
	}
}

func TestVerify(t *testing.T) {
	var seen []url.Values
	srv := fake(t, &seen)
	v := New()
	v.URL = srv.URL
	ctx := context.Background()

	v.SetKeys(passSite, passSecret)
	if err := v.Verify(ctx, DummyToken, "198.51.100.7"); err != nil {
		t.Fatalf("passing token -> %v", err)
	}
	if got := seen[0].Get("remoteip"); got != "198.51.100.7" {
		t.Errorf("remoteip = %q", got)
	}
	if seen[0].Get("idempotency_key") == "" {
		t.Error("no idempotency key")
	}
	// A missing or oversized token is refused without asking Cloudflare.
	n := len(seen)
	if err := v.Verify(ctx, "", ""); !errors.Is(err, ErrRejected) {
		t.Errorf("no token -> %v", err)
	}
	if err := v.Verify(ctx, string(make([]byte, maxTokenLen+1)), ""); !errors.Is(err, ErrRejected) {
		t.Errorf("huge token -> %v", err)
	}
	if len(seen) != n {
		t.Error("asked Cloudflare about a missing token")
	}

	v.SetKeys(passSite, failSecret)
	if err := v.Verify(ctx, DummyToken, ""); !errors.Is(err, ErrRejected) {
		t.Errorf("failing secret -> %v", err)
	}
	v.SetKeys(passSite, "0xunknownsecret00000000")
	if err := v.Verify(ctx, DummyToken, ""); !errors.Is(err, ErrUnavailable) {
		t.Errorf("unknown secret -> %v", err)
	}
}

func TestUnreachableFailsClosed(t *testing.T) {
	var calls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusBadGateway)
	}))
	t.Cleanup(srv.Close)
	v := New()
	v.URL = srv.URL
	v.SetKeys(passSite, passSecret)
	if err := v.Verify(context.Background(), DummyToken, ""); !errors.Is(err, ErrUnavailable) {
		t.Errorf("5xx -> %v", err)
	}
	if calls.Load() != 2 {
		t.Errorf("%d calls, want one retry", calls.Load())
	}
	srv.Close()
	if err := v.Verify(context.Background(), DummyToken, ""); !errors.Is(err, ErrUnavailable) {
		t.Errorf("connection refused -> %v", err)
	}
}

func TestCheckSecret(t *testing.T) {
	srv := fake(t, nil)
	v := New()
	v.URL = srv.URL
	ctx := context.Background()
	for _, s := range []string{passSecret, failSecret} {
		if err := v.CheckSecret(ctx, s); err != nil {
			t.Errorf("%s -> %v", s, err)
		}
	}
	if err := v.CheckSecret(ctx, "0xnotarealsecret0000000"); !errors.Is(err, ErrBadSecret) {
		t.Errorf("unknown secret -> %v", err)
	}
}

func TestValidKey(t *testing.T) {
	for _, k := range []string{passSite, passSecret, "0x4AAAAAAABkMYinukE8nzY"} {
		if !ValidKey(k) {
			t.Errorf("%q refused", k)
		}
	}
	for _, k := range []string{"", "short", "has space 0000000000", `"><script>`} {
		if ValidKey(k) {
			t.Errorf("%q accepted", k)
		}
	}
}

// TestCloudflare runs against the real siteverify with the published test
// keys when WATCHMYMEDIA_TEST_TURNSTILE=1 (it needs the internet).
func TestCloudflare(t *testing.T) {
	if os.Getenv("WATCHMYMEDIA_TEST_TURNSTILE") != "1" {
		t.Skip("set WATCHMYMEDIA_TEST_TURNSTILE=1 to call Cloudflare")
	}
	v := New()
	ctx := context.Background()
	v.SetKeys(passSite, passSecret)
	if err := v.Verify(ctx, DummyToken, "198.51.100.7"); err != nil {
		t.Errorf("always-pass secret -> %v", err)
	}
	v.SetKeys(passSite, failSecret)
	if err := v.Verify(ctx, DummyToken, ""); !errors.Is(err, ErrRejected) {
		t.Errorf("always-fail secret -> %v", err)
	}
	if err := v.CheckSecret(ctx, failSecret); err != nil {
		t.Errorf("check always-fail secret -> %v", err)
	}
	if err := v.CheckSecret(ctx, "0x0000000000000000000000000000000AA"); !errors.Is(err, ErrBadSecret) {
		t.Errorf("check made-up secret -> %v", err)
	}
}
