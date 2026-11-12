// Package turnstile checks Cloudflare Turnstile tokens: the bot check that
// can guard password sign-in and first-run setup. It is off until a site key
// and a secret key are set, from Settings or from config.
package turnstile

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/httpx"
)

const (
	// VerifyURL is Cloudflare's siteverify endpoint.
	VerifyURL = "https://challenges.cloudflare.com/turnstile/v0/siteverify"
	// Origin serves the widget's script and frame. The web UI's
	// Content-Security-Policy allows it only while Turnstile is on.
	Origin = "https://challenges.cloudflare.com"
	// ScriptURL loads the widget for explicit rendering.
	ScriptURL = Origin + "/turnstile/v0/api.js?render=explicit"
	// DummyToken is what Cloudflare's test site keys produce. Real secret
	// keys reject it, which is how a secret is checked without a widget.
	DummyToken = "XXXX.DUMMY.TOKEN.XXXX"

	maxTokenLen = 2048
)

var (
	// ErrRejected means the token was missing, expired, used already or
	// not from a person: the visitor should try the check again.
	ErrRejected = errors.New("the check that you are not a bot did not pass; try it again")
	// ErrUnavailable means the token could not be checked: Cloudflare was
	// unreachable, failed, or refused the secret key. Sign-ins are refused
	// until it can be checked again.
	ErrUnavailable = errors.New("could not reach Cloudflare to check that you are not a bot; try again in a minute")
	// ErrBadSecret means Cloudflare does not know the secret key.
	ErrBadSecret = errors.New("the secret key was refused by Cloudflare; copy it again from the Turnstile widget's settings")
)

// keyPattern is what Cloudflare's site and secret keys look like
// ("0x4AAAA…", or "1x00…" for the test keys).
var keyPattern = regexp.MustCompile(`^[0-9A-Za-z_-]{10,100}$`)

// ValidKey reports whether s looks like a Turnstile site or secret key.
func ValidKey(s string) bool { return keyPattern.MatchString(s) }

// Verifier holds the keys and checks tokens against Cloudflare. Fixed and
// Disabled are set once at startup, before it is used.
type Verifier struct {
	URL    string
	Client *http.Client
	// Fixed means the keys come from config.yaml or the environment and
	// Settings cannot change them.
	Fixed bool
	// Disabled turns Turnstile off whatever keys are set: the way back in
	// when a key problem stops everyone signing in.
	Disabled bool

	mu      sync.RWMutex
	siteKey string
	secret  string
}

// New returns a verifier with no keys, which is off.
func New() *Verifier {
	return &Verifier{URL: VerifyURL, Client: &http.Client{Timeout: 8 * time.Second}}
}

// SetKeys replaces the keys. Empty keys turn Turnstile off.
func (v *Verifier) SetKeys(siteKey, secret string) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.siteKey, v.secret = siteKey, secret
}

// Keys returns the keys as set, even while Disabled.
func (v *Verifier) Keys() (siteKey, secret string) {
	v.mu.RLock()
	defer v.mu.RUnlock()
	return v.siteKey, v.secret
}

// Required reports whether sign-ins need a token now.
func (v *Verifier) Required() bool {
	if v == nil || v.Disabled {
		return false
	}
	site, secret := v.Keys()
	return site != "" && secret != ""
}

// SiteKey is the key the widget needs, or "" while Turnstile is off.
func (v *Verifier) SiteKey() string {
	if !v.Required() {
		return ""
	}
	site, _ := v.Keys()
	return site
}

// Verify checks a token from the client at ip (which may be empty) with the
// current secret. It returns nil while Turnstile is off, an error wrapping
// ErrRejected for a bad token and one wrapping ErrUnavailable when the
// token could not be checked.
func (v *Verifier) Verify(ctx context.Context, token, ip string) error {
	if !v.Required() {
		return nil
	}
	_, secret := v.Keys()
	return v.VerifyWith(ctx, secret, token, ip)
}

// VerifyWith checks a token with the given secret.
func (v *Verifier) VerifyWith(ctx context.Context, secret, token, ip string) error {
	token = strings.TrimSpace(token)
	if token == "" || len(token) > maxTokenLen {
		return ErrRejected // no need to ask Cloudflare
	}
	res, err := v.siteverify(ctx, secret, token, ip)
	if err != nil {
		return err
	}
	switch {
	case res.Success:
		return nil
	case res.has("invalid-input-secret", "missing-input-secret"):
		return fmt.Errorf("%w (the secret key was refused: %s)", ErrUnavailable, res.codes())
	case res.has("internal-error"):
		return fmt.Errorf("%w (%s)", ErrUnavailable, res.codes())
	}
	return fmt.Errorf("%w (%s)", ErrRejected, res.codes())
}

// CheckSecret asks Cloudflare whether it knows a secret key, by verifying
// the dummy token with it: a known secret only rejects the token.
func (v *Verifier) CheckSecret(ctx context.Context, secret string) error {
	res, err := v.siteverify(ctx, secret, DummyToken, "")
	if err != nil {
		return err
	}
	if res.has("invalid-input-secret", "missing-input-secret") {
		return ErrBadSecret
	}
	if res.has("internal-error") {
		return fmt.Errorf("%w (%s)", ErrUnavailable, res.codes())
	}
	return nil
}

type result struct {
	Success    bool     `json:"success"`
	ErrorCodes []string `json:"error-codes"`
	Hostname   string   `json:"hostname"`
	Action     string   `json:"action"`
}

func (r result) has(codes ...string) bool {
	for _, c := range codes {
		if slices.Contains(r.ErrorCodes, c) {
			return true
		}
	}
	return false
}

func (r result) codes() string {
	if len(r.ErrorCodes) == 0 {
		return "no error code"
	}
	return strings.Join(r.ErrorCodes, ", ")
}

// siteverify posts a token to Cloudflare. A network error or a 5xx is tried
// once more with the same idempotency key, so a retry of a token Cloudflare
// already saw is not reported as a duplicate.
func (v *Verifier) siteverify(ctx context.Context, secret, token, ip string) (result, error) {
	form := url.Values{"secret": {secret}, "response": {token}, "idempotency_key": {newUUID()}}
	if ip != "" {
		form.Set("remoteip", ip)
	}
	var last error
	for range 2 {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, v.URL, strings.NewReader(form.Encode()))
		if err != nil {
			return result{}, err
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		res, err := v.Client.Do(req)
		if err != nil {
			last = err
			if ctx.Err() != nil {
				break
			}
			continue
		}
		body, err := httpx.ReadLimited(res.Body, 64<<10)
		_ = res.Body.Close()
		if err != nil {
			last = fmt.Errorf("siteverify: status %d: %w", res.StatusCode, err)
			continue
		}
		if res.StatusCode >= 500 {
			last = fmt.Errorf("siteverify: status %d", res.StatusCode)
			continue
		}
		var out result
		if err := json.Unmarshal(body, &out); err != nil {
			return result{}, fmt.Errorf("%w (siteverify replied with status %d and no result)", ErrUnavailable, res.StatusCode)
		}
		return out, nil
	}
	return result{}, fmt.Errorf("%w (%w)", ErrUnavailable, last)
}

// newUUID makes a random (version 4) UUID for the idempotency key.
func newUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}
