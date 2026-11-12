package plex

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func authTestClient(t *testing.T, srv *httptest.Server, token, clientID string) *Client {
	t.Helper()
	c, err := NewClient(srv.URL, token, Options{ClientIdentifier: clientID, HTTPClient: srv.Client()})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func authFixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("testdata/auth-" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// The stand-in checks the signed protocol, not just a canned successful reply.
// It proves our request construction; acceptance by Plex remains a live gate.
func TestAuthNewPINAndJWTRefresh(t *testing.T) {
	ctx := context.Background()
	key, err := GenerateDeviceKey()
	if err != nil {
		t.Fatal(err)
	}
	var registered PublicJWK
	polls, nonces, refreshes := 0, 0, 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Plex-Client-Identifier") != "synthetic-client" || r.Header.Get("X-Plex-Token") != "" {
			t.Error("new-device request has wrong client identity or a legacy credential")
		}
		switch r.Method + " " + r.URL.Path {
		case "POST /api/v2/pins":
			var in struct {
				JWK    PublicJWK `json:"jwk"`
				Strong bool      `json:"strong"`
			}
			if json.NewDecoder(r.Body).Decode(&in) != nil || !in.Strong || in.JWK.Algorithm != "EdDSA" || in.JWK.KeyType != "OKP" || in.JWK.Curve != "Ed25519" {
				t.Error("incorrect new-device PIN request")
			}
			registered = in.JWK
			fmt.Fprint(w, `{"id":420,"code":"synthetic-link-code","expiresIn":1800,"clientIdentifier":"synthetic-client"}`)
		case "GET /api/v2/pins/420":
			verifyDeviceProof(t, r.URL.Query().Get("deviceJWT"), registered, "synthetic-client", "")
			polls++
			if polls == 1 {
				fmt.Fprint(w, `{"id":420,"authToken":null}`)
			} else {
				fmt.Fprint(w, `{"id":420,"authToken":"synthetic-pin-token"}`)
			}
		case "GET /api/v2/auth/nonce":
			nonces++
			fmt.Fprint(w, `{"nonce":"synthetic-one-time-nonce"}`)
		case "POST /api/v2/auth/token":
			var in struct {
				JWT string `json:"jwt"`
			}
			if json.NewDecoder(r.Body).Decode(&in) != nil {
				t.Error("incorrect refresh body")
			}
			verifyDeviceProof(t, in.JWT, registered, "synthetic-client", "synthetic-one-time-nonce")
			refreshes++
			fmt.Fprint(w, `{"auth_token":"synthetic-refreshed-token"}`)
		default:
			t.Error("unexpected auth operation")
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	c := authTestClient(t, srv, "", "synthetic-client")
	pin, err := c.CreatePIN(ctx, key)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(pin.AuthURL(), "https://app.plex.tv/auth#?") || strings.Contains(pin.AuthURL(), "forwardUrl") || pin.ExpiresAt.After(time.Now().Add(10*time.Minute)) {
		t.Error("unsafe linking URL or deadline")
	}
	credential, ready, err := c.PollPIN(ctx, pin, key)
	if err != nil || ready || credential.Token() != "" {
		t.Fatalf("pending PIN: ready=%v error=%v", ready, err)
	}
	credential, ready, err = c.PollPIN(ctx, pin, key)
	if err != nil || !ready || credential.Token() != "synthetic-pin-token" {
		t.Fatalf("claimed PIN: ready=%v error=%v", ready, err)
	}
	credential, err = c.RefreshJWT(ctx, key)
	if err != nil || credential.Token() != "synthetic-refreshed-token" || nonces != 1 || refreshes != 1 {
		t.Fatalf("refresh did not exchange a new nonce: %v", err)
	}
}

func verifyDeviceProof(t *testing.T, proof string, jwk PublicJWK, clientID, nonce string) {
	t.Helper()
	parts := strings.Split(proof, ".")
	if len(parts) != 3 {
		t.Error("device proof is not a JWT")
		return
	}
	public, err := base64.RawURLEncoding.DecodeString(jwk.X)
	if err != nil || len(public) != ed25519.PublicKeySize {
		t.Error("invalid registered public key")
		return
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || !ed25519.Verify(public, []byte(parts[0]+"."+parts[1]), signature) {
		t.Error("device proof signature does not match registered JWK")
	}
	headerBytes, _ := base64.RawURLEncoding.DecodeString(parts[0])
	var header map[string]string
	if json.Unmarshal(headerBytes, &header) != nil || header["kid"] != jwk.KeyID || header["alg"] != "EdDSA" || header["typ"] != "JWT" {
		t.Error("incorrect JWT header")
	}
	payloadBytes, _ := base64.RawURLEncoding.DecodeString(parts[1])
	var claims struct {
		Audience string `json:"aud"`
		Issuer   string `json:"iss"`
		Nonce    string `json:"nonce"`
		Scope    string `json:"scope"`
		IssuedAt int64  `json:"iat"`
		Expires  int64  `json:"exp"`
	}
	if json.Unmarshal(payloadBytes, &claims) != nil || claims.Audience != "plex.tv" || claims.Issuer != clientID || claims.Nonce != nonce || claims.Scope != "restricted" {
		t.Error("incorrect JWT claims")
	}
	if claims.Expires-claims.IssuedAt != 300 || claims.IssuedAt > time.Now().Unix() || claims.Expires < time.Now().Unix() {
		t.Error("JWT proof has an invalid validity window")
	}
}

func TestAuthPINBindingAndExpiry(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		fmt.Fprint(w, `{"id":420,"code":"synthetic-code","expiresIn":60}`)
	}))
	defer srv.Close()
	c := authTestClient(t, srv, "", "synthetic-client")
	key, _ := GenerateDeviceKey()
	otherKey, _ := GenerateDeviceKey()
	pin, err := c.CreatePIN(context.Background(), key)
	if err != nil {
		t.Fatal(err)
	}
	wrongClient := authTestClient(t, srv, "", "another-client")
	for _, item := range []struct {
		name string
		c    *Client
		key  *DeviceKey
		pin  PIN
	}{
		{"different key", c, otherKey, pin},
		{"different client", wrongClient, key, pin},
		{"missing key", c, nil, pin},
		{"expired", c, key, PIN{ID: pin.ID, clientID: pin.clientID, keyID: pin.keyID, ExpiresAt: time.Now().Add(-time.Second)}},
	} {
		t.Run(item.name, func(t *testing.T) {
			_, ready, err := item.c.PollPIN(context.Background(), item.pin, item.key)
			if err == nil || ready || calls != 1 {
				t.Fatalf("invalid attempt made a request or succeeded: %v", err)
			}
		})
	}
}

func TestAuthRejectsWrongTokenExchangeShapeAndIdentity(t *testing.T) {
	for _, tc := range []struct {
		name, path, response string
	}{
		{"wrong refresh casing", "/api/v2/auth/token", `{"authToken":"synthetic-not-a-refresh-response"}`},
		{"empty refresh", "/api/v2/auth/token", `{}`},
		{"empty nonce", "/api/v2/auth/nonce", `{"nonce":""}`},
		{"wrong pin identity", "/api/v2/pins/420", `{"id":999,"authToken":"synthetic-wrong-person"}`},
		{"wrong pin client", "/api/v2/pins/420", `{"id":420,"clientIdentifier":"another-client","authToken":"synthetic-wrong-person"}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == tc.path {
					fmt.Fprint(w, tc.response)
					return
				}
				switch r.URL.Path {
				case "/api/v2/pins":
					fmt.Fprint(w, `{"id":420,"code":"synthetic-code","expiresIn":60}`)
				case "/api/v2/auth/nonce":
					fmt.Fprint(w, `{"nonce":"synthetic-nonce"}`)
				default:
					t.Error("unexpected auth operation")
					w.WriteHeader(404)
				}
			}))
			defer srv.Close()
			c := authTestClient(t, srv, "", "synthetic-client")
			key, _ := GenerateDeviceKey()
			var err error
			if strings.Contains(tc.name, "pin") {
				pin, createErr := c.CreatePIN(context.Background(), key)
				if createErr != nil {
					t.Fatal(createErr)
				}
				_, _, err = c.PollPIN(context.Background(), pin, key)
			} else {
				_, err = c.RefreshJWT(context.Background(), key)
			}
			if err == nil || strings.Contains(err.Error(), "synthetic-") {
				t.Fatalf("invalid response accepted or reflected: %v", err)
			}
		})
	}
}

func TestAuthDiscoveryAndUserScopes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Plex-Token") != "synthetic-owner-token" || r.URL.Query().Get("X-Plex-Token") != "" {
			t.Error("owner credential was missing or sent in URL")
		}
		switch r.Method + " " + r.URL.Path {
		case "GET /api/v2/user":
			_, _ = w.Write(authFixture(t, "account.json"))
		case "GET /api/v2/resources":
			if r.URL.Query().Get("includeHttps") != "1" || r.URL.Query().Get("includeIPv6") != "1" {
				t.Error("resource discovery did not request secure/IPv6 connections")
			}
			_, _ = w.Write(authFixture(t, "resources.json"))
		case "GET /api/users/":
			_, _ = w.Write(authFixture(t, "users.xml"))
		case "GET /api/home/users":
			_, _ = w.Write(authFixture(t, "home.xml"))
		case "GET /api/servers/synthetic-machine/shared_servers":
			_, _ = w.Write(authFixture(t, "shares.xml"))
		case "POST /api/home/users/703/switch":
			if r.URL.Query().Get("pin") != "1234" {
				t.Error("profile PIN not confined to explicit switch request")
			}
			fmt.Fprint(w, `<user id="703" authenticationToken="synthetic-home-token"/>`)
		default:
			t.Error("unexpected discovery operation")
			w.WriteHeader(404)
		}
	}))
	defer srv.Close()
	c := authTestClient(t, srv, "synthetic-owner-token", "synthetic-client")
	ctx := context.Background()
	account, err := c.Account(ctx)
	if err != nil || account.ID != 701 || !account.HomeAdmin || !account.SubscriptionActive {
		t.Fatalf("account identity: %v", err)
	}
	resources, err := c.Resources(ctx)
	if err != nil || len(resources) != 2 || resources[0].MachineIdentifier != "synthetic-machine" || !resources[0].Owned || resources[0].Credential.Token() != "synthetic-server-token" {
		t.Fatalf("server resources: %v", err)
	}
	if resources[1].Credential.Token() != "" {
		t.Error("missing resource token fell back to account credential")
	}
	users, err := c.Users(ctx)
	if err != nil || len(users) != 2 || users[0].ID == users[1].ID || users[0].Title != users[1].Title {
		t.Fatalf("same-name users not kept distinct: %v", err)
	}
	home, err := c.HomeUsers(ctx)
	if err != nil || len(home) != 2 || home[1].ID != 703 || !home[1].Protected || !home[1].Restricted {
		t.Fatalf("Home profile identity: %v", err)
	}
	shared, err := c.SharedUserCredential(ctx, "synthetic-machine", 702)
	if err != nil || shared.Token() != "synthetic-shared-token" {
		t.Fatalf("shared-user credential: %v", err)
	}
	if _, err := c.SharedUserCredential(ctx, "synthetic-machine", 999); err == nil {
		t.Error("unknown user received a credential")
	}
	profile, err := c.SwitchHomeUser(ctx, 703, "1234")
	if err != nil || profile.Token() != "synthetic-home-token" || profile.Token() == shared.Token() {
		t.Fatalf("Home credential not separate from shared-server credential: %v", err)
	}
}

func TestAuthSharedCredentialFailsClosed(t *testing.T) {
	for _, tc := range []struct{ name, response string }{
		{"empty", `<MediaContainer/>`},
		{"wrong user", `<MediaContainer><SharedServer userID="999" accessToken="synthetic-other"/></MediaContainer>`},
		{"missing token", `<MediaContainer><SharedServer userID="702"/></MediaContainer>`},
		{"duplicate", `<MediaContainer><SharedServer userID="702" accessToken="synthetic-a"/><SharedServer userID="702" accessToken="synthetic-b"/></MediaContainer>`},
		{"wrong envelope", `{"token":"synthetic-other"}`},
		{"wrong XML root", `<User userID="702" accessToken="synthetic-other"/>`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, tc.response) }))
			defer srv.Close()
			c := authTestClient(t, srv, "synthetic-owner", "synthetic-client")
			credential, err := c.SharedUserCredential(context.Background(), "synthetic-machine", 702)
			if err == nil || credential.Token() != "" || strings.Contains(err.Error(), "synthetic-") {
				t.Fatalf("bad share response returned/reflected a credential: %v", err)
			}
		})
	}
}

func TestAuthHomeIdentityMismatchAndDeniedPIN(t *testing.T) {
	for _, status := range []int{http.StatusOK, http.StatusUnauthorized} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
				fmt.Fprint(w, `<user id="701" authenticationToken="synthetic-owner-token"/>`)
			}))
			defer srv.Close()
			c := authTestClient(t, srv, "synthetic-owner-token", "synthetic-client")
			credential, err := c.SwitchHomeUser(context.Background(), 703, "1234")
			if err == nil || credential.Token() != "" || strings.Contains(err.Error(), "1234") || strings.Contains(err.Error(), "synthetic-owner-token") {
				t.Fatalf("Home failure reflected a secret or accepted wrong identity: %v", err)
			}
		})
	}
}

func TestAuthCredentialFormattingIsRedacted(t *testing.T) {
	credential := Credential{"synthetic-secret-token"}
	for _, rendered := range []string{fmt.Sprint(credential), fmt.Sprintf("%+v", credential), fmt.Sprintf("%#v", credential)} {
		if strings.Contains(rendered, credential.Token()) || !strings.Contains(rendered, "redacted") {
			t.Error("credential formatter exposes secret")
		}
	}
	b, err := json.Marshal(credential)
	if err != nil || strings.Contains(string(b), credential.Token()) {
		t.Error("credential JSON exposes secret")
	}
	key, _ := GenerateDeviceKey()
	for _, value := range []any{key, *key} {
		for _, rendered := range []string{fmt.Sprint(value), fmt.Sprintf("%+v", value), fmt.Sprintf("%#v", value)} {
			if strings.Contains(rendered, "privateKey") || !strings.Contains(rendered, "redacted") {
				t.Error("device-key formatter exposes private fields")
			}
		}
	}
}

func TestAuthNewDeviceMethodsNeverSendExistingToken(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	c := authTestClient(t, srv, "synthetic-existing-legacy-token", "synthetic-client")
	key, _ := GenerateDeviceKey()
	if _, err := c.CreatePIN(context.Background(), key); err == nil {
		t.Error("credentialed new-device PIN creation was allowed")
	}
	if _, _, err := c.PollPIN(context.Background(), PIN{}, key); err == nil {
		t.Error("credentialed PIN exchange was allowed")
	}
	if _, err := c.RefreshJWT(context.Background(), key); err == nil {
		t.Error("credentialed refresh was allowed")
	}
	if calls != 0 {
		t.Error("existing token reached new-device endpoint")
	}
}

func TestAuthExplicitlyExpiredPINIsRejected(t *testing.T) {
	for _, response := range []string{
		`{"id":420,"code":"synthetic-code","expiresIn":0}`,
		`{"id":420,"code":"synthetic-code","expiresIn":-1}`,
		`{"id":420,"code":"synthetic-code","expiresAt":"2020-01-01T00:00:00Z"}`,
	} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, response) }))
		c := authTestClient(t, srv, "", "synthetic-client")
		key, _ := GenerateDeviceKey()
		_, err := c.CreatePIN(context.Background(), key)
		srv.Close()
		if err == nil || ErrorKind(err) != "pin_expired" {
			t.Errorf("expired PIN not rejected: %v", err)
		}
	}
}

func TestDeviceKeySeedRestoresTheSameIdentity(t *testing.T) {
	key, err := GenerateDeviceKey()
	if err != nil {
		t.Fatal(err)
	}
	restored, err := DeviceKeyFromSeed(key.Seed())
	if err != nil {
		t.Fatal(err)
	}
	if restored.PublicJWK() != key.PublicJWK() {
		t.Fatal("restored key has a different public identity")
	}
	proof := restored.deviceJWT("synthetic-client", "synthetic-nonce", time.Now())
	verifyDeviceProof(t, proof, key.PublicJWK(), "synthetic-client", "synthetic-nonce")
	for _, seed := range [][]byte{nil, make([]byte, 31), make([]byte, 64)} {
		if _, err := DeviceKeyFromSeed(seed); err == nil {
			t.Errorf("seed of %d bytes accepted", len(seed))
		}
	}
	if (&DeviceKey{}).Seed() != nil {
		t.Error("empty key gave a seed")
	}
	formatted := fmt.Sprintf("%v %+v %#v", restored, restored, restored)
	if strings.Contains(formatted, base64.RawURLEncoding.EncodeToString(key.Seed())) || strings.Contains(formatted, string(key.Seed())) {
		t.Fatal("formatting a device key printed its seed")
	}
}

func TestHomeUsersKeepLoginEvidenceButNotNames(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `<MediaContainer size="4">
  <User id="701" title="Synthetic Owner" username="synthetic-owner" email="owner@example.invalid" admin="1"/>
  <User id="705" title="Synthetic Member" username="" email="member@example.invalid"/>
  <User id="706" title="Synthetic Kid" username="" email="" restricted="1"/>
  <User id="707" title="Guest" guest="1"/>
</MediaContainer>`)
	}))
	defer srv.Close()
	home, err := authTestClient(t, srv, "synthetic-owner-token", "synthetic-client").HomeUsers(context.Background())
	if err != nil || len(home) != 4 {
		t.Fatalf("home users: %v %+v", err, home)
	}
	if !home[0].HasLogin || !home[0].Admin || !home[1].HasLogin || home[2].HasLogin || !home[3].Guest || home[3].HasLogin {
		t.Fatalf("login evidence wrong: %+v", home)
	}
	if b, _ := json.Marshal(home); strings.Contains(string(b), "example.invalid") || strings.Contains(string(b), "synthetic-owner\"") {
		t.Fatalf("a username or email was kept: %s", b)
	}
}

func TestSharedServerUsersListsAccessWithoutCredentials(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `<MediaContainer>
  <SharedServer userID="702" accessToken="synthetic-a"/>
  <SharedServer userID="704" accessToken="synthetic-b"/>
  <SharedServer userID="704" accessToken="synthetic-c"/>
  <SharedServer userID="708"/>
  <SharedServer userID="0" accessToken="synthetic-zero"/>
</MediaContainer>`)
	}))
	defer srv.Close()
	users, err := authTestClient(t, srv, "synthetic-owner-token", "synthetic-client").SharedServerUsers(context.Background(), "synthetic-machine")
	if err != nil || len(users) != 1 || !users[702] {
		t.Fatalf("shared users = %v %v; want only 702 (704 is duplicated, 708 has no token, 0 is no one)", users, err)
	}
	if _, err := authTestClient(t, srv, "synthetic-owner-token", "synthetic-client").SharedServerUsers(context.Background(), "bad/machine"); err == nil {
		t.Error("invalid machine identifier was requested")
	}
}
