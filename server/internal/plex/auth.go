package plex

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// Credential is deliberately opaque to formatting and JSON. Token is the only
// accessor for handing the secret to a new, explicitly scoped Client.
type Credential struct{ token string }

func (c Credential) Token() string              { return c.token }
func (Credential) String() string               { return "[redacted]" }
func (Credential) GoString() string             { return "plex.Credential{[redacted]}" }
func (Credential) MarshalJSON() ([]byte, error) { return []byte(`"[redacted]"`), nil }

// PublicJWK is the public half of a device key. It contains no private material.
type PublicJWK struct {
	KeyType   string `json:"kty"`
	Curve     string `json:"crv"`
	X         string `json:"x"`
	KeyID     string `json:"kid"`
	Algorithm string `json:"alg"`
}

// DeviceKey is the device identity a Plex grant is registered to. The probe
// keeps it in memory; the connection keeps its Seed in a private file store.
type DeviceKey struct {
	privateKey ed25519.PrivateKey
	publicJWK  PublicJWK
}

func (k *DeviceKey) PublicJWK() PublicJWK { return k.publicJWK }
func (DeviceKey) String() string          { return "[redacted device key]" }
func (DeviceKey) GoString() string        { return "plex.DeviceKey{[redacted]}" }

// GenerateDeviceKey creates a fresh identity for the new-device PIN flow. This
// package intentionally does not implement migration of an existing legacy token:
// Plex documents that completing that migration expires the old token.
func GenerateDeviceKey() (*DeviceKey, error) {
	_, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, &Error{Kind: "key_generation"}
	}
	return deviceKey(private), nil
}

// DeviceKeyFromSeed restores a key that Seed saved. The seed is the whole
// secret: whoever holds it can renew the Plex grant registered for the key.
func DeviceKeyFromSeed(seed []byte) (*DeviceKey, error) {
	if len(seed) != ed25519.SeedSize {
		return nil, &Error{Kind: "invalid_device_key"}
	}
	return deviceKey(ed25519.NewKeyFromSeed(seed)), nil
}

// Seed returns the private key's seed, for the private credential store
// only. It is never logged, returned in a reply or written anywhere else.
func (k *DeviceKey) Seed() []byte {
	if !validDeviceKey(k) {
		return nil
	}
	return k.privateKey.Seed()
}

func deviceKey(private ed25519.PrivateKey) *DeviceKey {
	public, _ := private.Public().(ed25519.PublicKey)
	x := base64.RawURLEncoding.EncodeToString(public)
	// RFC 7638 thumbprint over the canonical public JWK members.
	thumbprint := sha256.Sum256([]byte(`{"crv":"Ed25519","kty":"OKP","x":"` + x + `"}`))
	return &DeviceKey{privateKey: private, publicJWK: PublicJWK{
		KeyType: "OKP", Curve: "Ed25519", X: x,
		KeyID: base64.RawURLEncoding.EncodeToString(thumbprint[:]), Algorithm: "EdDSA",
	}}
}

// PIN binds a linking attempt to its original client and device key. The local
// deadline never exceeds ten minutes, even when Plex gives the PIN a longer life.
// It is not a durable, single-use authorization record; that belongs to P2.
type PIN struct {
	ID        int64     `json:"id"`
	Code      string    `json:"-"`
	ExpiresAt time.Time `json:"expiresAt"`
	clientID  string
	product   string
	keyID     string
}

func (PIN) String() string   { return "[redacted Plex PIN]" }
func (PIN) GoString() string { return "plex.PIN{[redacted]}" }

// AuthURL deliberately has no caller-controlled forward URL. Open it only for
// this explicit linking attempt; do not put it in logs or a compatibility report.
func (p PIN) AuthURL() string {
	q := url.Values{
		"clientID": {p.clientID}, "code": {p.Code},
		"context[device][product]": {p.product},
	}
	return "https://app.plex.tv/auth#?" + q.Encode()
}

// CreatePIN uses clients.plex.tv for a new device. It does not collect a password
// or register a key against an existing token.
func (c *Client) CreatePIN(ctx context.Context, key *DeviceKey) (PIN, error) {
	if c.token != "" {
		return PIN{}, &Error{Kind: "credentialed_auth_client"}
	}
	if !validDeviceKey(key) {
		return PIN{}, &Error{Kind: "invalid_device_key"}
	}
	body := struct {
		JWK    PublicJWK `json:"jwk"`
		Strong bool      `json:"strong"`
	}{key.publicJWK, true}
	data, err := c.do(ctx, http.MethodPost, "/api/v2/pins", nil, body)
	if err != nil {
		return PIN{}, err
	}
	var out struct {
		ID               int64      `json:"id"`
		Code             string     `json:"code"`
		ClientIdentifier string     `json:"clientIdentifier"`
		ExpiresAt        *time.Time `json:"expiresAt"`
		ExpiresIn        *int64     `json:"expiresIn"`
	}
	if json.Unmarshal(data, &out) != nil || out.ID <= 0 || out.Code == "" {
		return PIN{}, &Error{Kind: "invalid_response"}
	}
	if out.ClientIdentifier != "" && out.ClientIdentifier != c.clientID {
		return PIN{}, &Error{Kind: "identity_mismatch"}
	}
	now := time.Now()
	expires := now.Add(10 * time.Minute)
	if out.ExpiresIn != nil {
		if *out.ExpiresIn <= 0 {
			return PIN{}, &Error{Kind: "pin_expired"}
		}
		if *out.ExpiresIn < 600 {
			expires = now.Add(time.Duration(*out.ExpiresIn) * time.Second)
		}
	}
	if out.ExpiresAt != nil && out.ExpiresAt.Before(expires) {
		expires = *out.ExpiresAt
	}
	if !expires.After(now) {
		return PIN{}, &Error{Kind: "pin_expired"}
	}
	return PIN{ID: out.ID, Code: out.Code, ExpiresAt: expires, clientID: c.clientID, product: c.product, keyID: key.publicJWK.KeyID}, nil
}

// PollPIN returns ready=false until Plex supplies an authToken. The returned
// credential must be verified with Account before a local identity is linked.
func (c *Client) PollPIN(ctx context.Context, pin PIN, key *DeviceKey) (Credential, bool, error) {
	if c.token != "" {
		return Credential{}, false, &Error{Kind: "credentialed_auth_client"}
	}
	if !validDeviceKey(key) || pin.ID <= 0 || pin.clientID != c.clientID || pin.keyID != key.publicJWK.KeyID {
		return Credential{}, false, &Error{Kind: "invalid_link_attempt"}
	}
	if !pin.ExpiresAt.After(time.Now()) {
		return Credential{}, false, &Error{Kind: "pin_expired"}
	}
	proof := key.deviceJWT(c.clientID, "", time.Now())
	var out struct {
		ID               int64  `json:"id"`
		ClientIdentifier string `json:"clientIdentifier"`
		AuthToken        string `json:"authToken"`
	}
	err := c.get(ctx, "/api/v2/pins/"+strconv.FormatInt(pin.ID, 10), url.Values{"deviceJWT": {proof}}, &out)
	if err != nil {
		return Credential{}, false, err
	}
	if out.ID != pin.ID || (out.ClientIdentifier != "" && out.ClientIdentifier != c.clientID) {
		return Credential{}, false, &Error{Kind: "identity_mismatch"}
	}
	return Credential{token: out.AuthToken}, out.AuthToken != "", nil
}

// RefreshJWT requests a fresh nonce and exchanges a signed proof. The key must
// already have been registered by CreatePIN. No legacy-token migration occurs.
func (c *Client) RefreshJWT(ctx context.Context, key *DeviceKey) (Credential, error) {
	if c.token != "" {
		return Credential{}, &Error{Kind: "credentialed_auth_client"}
	}
	if !validDeviceKey(key) {
		return Credential{}, &Error{Kind: "invalid_device_key"}
	}
	var nonce struct {
		Nonce string `json:"nonce"`
	}
	if err := c.get(ctx, "/api/v2/auth/nonce", nil, &nonce); err != nil {
		return Credential{}, err
	}
	if nonce.Nonce == "" {
		return Credential{}, &Error{Kind: "invalid_response"}
	}
	body := struct {
		JWT string `json:"jwt"`
	}{key.deviceJWT(c.clientID, nonce.Nonce, time.Now())}
	data, err := c.do(ctx, http.MethodPost, "/api/v2/auth/token", nil, body)
	if err != nil {
		return Credential{}, err
	}
	var out struct {
		AuthToken string `json:"auth_token"`
	}
	if json.Unmarshal(data, &out) != nil || out.AuthToken == "" {
		return Credential{}, &Error{Kind: "invalid_response"}
	}
	return Credential{token: out.AuthToken}, nil
}

func validDeviceKey(key *DeviceKey) bool {
	return key != nil && len(key.privateKey) == ed25519.PrivateKeySize && key.publicJWK.KeyID != ""
}

func (k *DeviceKey) deviceJWT(clientID, nonce string, now time.Time) string {
	header, _ := json.Marshal(struct {
		KeyID string `json:"kid"`
		Alg   string `json:"alg"`
		Type  string `json:"typ"`
	}{k.publicJWK.KeyID, "EdDSA", "JWT"})
	payload, _ := json.Marshal(struct {
		Audience string `json:"aud"`
		Issuer   string `json:"iss"`
		IssuedAt int64  `json:"iat"`
		Expires  int64  `json:"exp"`
		Nonce    string `json:"nonce,omitempty"`
		Scope    string `json:"scope"`
	}{"plex.tv", clientID, now.Unix(), now.Add(5 * time.Minute).Unix(), nonce, "restricted"})
	unsigned := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(payload)
	signature := ed25519.Sign(k.privateKey, []byte(unsigned))
	return fmt.Sprintf("%s.%s", unsigned, base64.RawURLEncoding.EncodeToString(signature))
}
