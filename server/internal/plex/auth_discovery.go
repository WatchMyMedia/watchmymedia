package plex

import (
	"bytes"
	"context"
	"encoding/json"
	"encoding/xml"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// Account is a Plex cloud identity, not a PMS history account ID. In particular,
// an owner's PMS history ID may be 1 rather than this value.
type Account struct {
	ID   int64  `json:"id"`
	UUID string `json:"uuid"`
	// Title is display text for a person to recognise, never an identifier.
	Title              string `json:"title"`
	HomeAdmin          bool   `json:"homeAdmin"`
	Restricted         bool   `json:"restricted"`
	SubscriptionActive bool   `json:"subscriptionActive"`
}

// Account verifies a cloud credential on plex.tv. Server-specific shared-user
// credentials must be tested on their selected PMS, not against this endpoint.
func (c *Client) Account(ctx context.Context) (Account, error) {
	var out struct {
		ID           int64  `json:"id"`
		UUID         string `json:"uuid"`
		Title        string `json:"title"`
		HomeAdmin    bool   `json:"homeAdmin"`
		Restricted   bool   `json:"restricted"`
		Subscription struct {
			Active bool `json:"active"`
		} `json:"subscription"`
	}
	if err := c.get(ctx, "/api/v2/user", nil, &out); err != nil {
		return Account{}, err
	}
	if out.ID <= 0 {
		return Account{}, &Error{Kind: "invalid_response"}
	}
	return Account{ID: out.ID, UUID: out.UUID, Title: out.Title, HomeAdmin: out.HomeAdmin,
		Restricted: out.Restricted, SubscriptionActive: out.Subscription.Active}, nil
}

type ResourceConnection struct {
	URI   string `json:"uri"`
	Local bool   `json:"local"`
	Relay bool   `json:"relay"`
}

// Resource is a discoverable PMS. Returning a connection does not contact it or
// authorize credential forwarding. The probe's caller explicitly selects one.
type Resource struct {
	MachineIdentifier string `json:"machineIdentifier"`
	// Name is the server's display name, untrusted text for a person to
	// choose by; the machine identifier is what identifies it.
	Name           string               `json:"name"`
	Owned          bool                 `json:"owned"`
	ProductVersion string               `json:"productVersion"`
	Connections    []ResourceConnection `json:"connections"`
	Credential     Credential           `json:"-"`
}

// Resources uses clients.plex.tv's documented v2 JSON endpoint. It includes only
// server resources and never substitutes the cloud credential for a missing
// server credential.
func (c *Client) Resources(ctx context.Context) ([]Resource, error) {
	var wire []struct {
		ClientIdentifier string               `json:"clientIdentifier"`
		Name             string               `json:"name"`
		Provides         string               `json:"provides"`
		Owned            bool                 `json:"owned"`
		ProductVersion   string               `json:"productVersion"`
		AccessToken      string               `json:"accessToken"`
		Connections      []ResourceConnection `json:"connections"`
	}
	q := url.Values{"includeHttps": {"1"}, "includeRelay": {"1"}, "includeIPv6": {"1"}}
	if err := c.get(ctx, "/api/v2/resources", q, &wire); err != nil {
		return nil, err
	}
	out := make([]Resource, 0, len(wire))
	seen := make(map[string]bool)
	for _, row := range wire {
		if !containsCapability(row.Provides, "server") {
			continue
		}
		if !validMachineIdentifier(row.ClientIdentifier) || seen[row.ClientIdentifier] {
			return nil, &Error{Kind: "invalid_response"}
		}
		seen[row.ClientIdentifier] = true
		out = append(out, Resource{MachineIdentifier: row.ClientIdentifier, Name: row.Name, Owned: row.Owned,
			ProductVersion: row.ProductVersion, Connections: row.Connections, Credential: Credential{row.AccessToken}})
	}
	return out, nil
}

// HomeUser IDs belong to Plex cloud/Home. Keep them distinct from PMS account IDs.
// HasLogin says the person has a plex.tv sign-in of their own (a username or
// email was listed); a managed Home profile has neither. The username and
// email themselves are dropped: they are never identifiers.
type HomeUser struct {
	ID         int64  `json:"id"`
	Title      string `json:"title"`
	Home       bool   `json:"home"`
	Admin      bool   `json:"admin"`
	Guest      bool   `json:"guest"`
	Protected  bool   `json:"protected"`
	Restricted bool   `json:"restricted"`
	HasLogin   bool   `json:"hasLogin"`
}

// Users enumerates shared/friend identities; HomeUsers uses the separate Home
// list, which can include managed profiles absent from the shared-user list.
func (c *Client) Users(ctx context.Context) ([]HomeUser, error) {
	return c.authUsers(ctx, "/api/users/")
}

func (c *Client) HomeUsers(ctx context.Context) ([]HomeUser, error) {
	return c.authUsers(ctx, "/api/home/users")
}

type authUser struct {
	ID         int64  `xml:"id,attr" json:"id"`
	Title      string `xml:"title,attr" json:"title"`
	Home       bool   `xml:"home,attr" json:"home"`
	Admin      bool   `xml:"admin,attr" json:"admin"`
	Guest      bool   `xml:"guest,attr" json:"guest"`
	Protected  bool   `xml:"protected,attr" json:"protected"`
	Restricted bool   `xml:"restricted,attr" json:"restricted"`
	Username   string `xml:"username,attr" json:"username"`
	Email      string `xml:"email,attr" json:"email"`
}

func (c *Client) authUsers(ctx context.Context, path string) ([]HomeUser, error) {
	data, err := c.do(ctx, http.MethodGet, path, nil, nil)
	if err != nil {
		return nil, err
	}
	var container struct {
		XMLName xml.Name   `xml:"MediaContainer"`
		Users   []authUser `xml:"User" json:"User"`
	}
	if err := decodeLegacyContainer(data, &container); err != nil {
		return nil, err
	}
	out := make([]HomeUser, 0, len(container.Users))
	seen := make(map[int64]bool)
	for _, row := range container.Users {
		if row.ID <= 0 || seen[row.ID] {
			return nil, &Error{Kind: "invalid_response"}
		}
		seen[row.ID] = true
		out = append(out, HomeUser{ID: row.ID, Title: row.Title, Home: row.Home, Admin: row.Admin, Guest: row.Guest,
			Protected: row.Protected, Restricted: row.Restricted,
			HasLogin: strings.TrimSpace(row.Username) != "" || strings.TrimSpace(row.Email) != ""})
	}
	return out, nil
}

// SharedUserCredential selects only an exact userID on an exact server. A
// missing or duplicate match is an error, never permission to use the owner token.
// This endpoint is maintained-client evidence, not a guaranteed PMS API contract.
func (c *Client) SharedUserCredential(ctx context.Context, machineID string, userID int64) (Credential, error) {
	if !validMachineIdentifier(machineID) || userID <= 0 {
		return Credential{}, &Error{Kind: "invalid_identity"}
	}
	tokens, err := c.sharedServers(ctx, machineID)
	if err != nil {
		return Credential{}, err
	}
	if len(tokens[userID]) != 1 || tokens[userID][0] == "" {
		return Credential{}, &Error{Kind: "user_access_unavailable"}
	}
	return Credential{tokens[userID][0]}, nil
}

// SharedServerUsers lists the users SharedUserCredential would give a
// credential for on this server, without handing out any credential: an
// exact user ID listed once, with a token.
func (c *Client) SharedServerUsers(ctx context.Context, machineID string) (map[int64]bool, error) {
	if !validMachineIdentifier(machineID) {
		return nil, &Error{Kind: "invalid_identity"}
	}
	tokens, err := c.sharedServers(ctx, machineID)
	if err != nil {
		return nil, err
	}
	out := make(map[int64]bool, len(tokens))
	for id, list := range tokens {
		if id > 0 && len(list) == 1 && list[0] != "" {
			out[id] = true
		}
	}
	return out, nil
}

func (c *Client) sharedServers(ctx context.Context, machineID string) (map[int64][]string, error) {
	data, err := c.do(ctx, http.MethodGet, "/api/servers/"+machineID+"/shared_servers", nil, nil)
	if err != nil {
		return nil, err
	}
	var container struct {
		XMLName xml.Name `xml:"MediaContainer"`
		Shared  []struct {
			UserID      int64  `xml:"userID,attr" json:"userID"`
			AccessToken string `xml:"accessToken,attr" json:"accessToken"`
		} `xml:"SharedServer" json:"SharedServer"`
	}
	if err := decodeLegacyContainer(data, &container); err != nil {
		return nil, err
	}
	tokens := make(map[int64][]string, len(container.Shared))
	for _, row := range container.Shared {
		tokens[row.UserID] = append(tokens[row.UserID], row.AccessToken)
	}
	return tokens, nil
}

// SwitchHomeUser performs profile authorization, not server-token discovery. A
// PIN is sent only for this request and is not stored. A returned ID must match.
// Call Account with the returned credential before linking it to a local person.
func (c *Client) SwitchHomeUser(ctx context.Context, userID int64, pin string) (Credential, error) {
	if userID <= 0 || len(pin) > 32 {
		return Credential{}, &Error{Kind: "invalid_identity"}
	}
	q := url.Values{}
	if pin != "" {
		q.Set("pin", pin)
	}
	data, err := c.do(ctx, http.MethodPost, "/api/home/users/"+strconv.FormatInt(userID, 10)+"/switch", q, nil)
	if err != nil {
		return Credential{}, err
	}
	var out struct {
		XMLName             xml.Name
		ID                  int64  `xml:"id,attr" json:"id"`
		AuthenticationToken string `xml:"authenticationToken,attr" json:"authenticationToken"`
	}
	if bytes.HasPrefix(bytes.TrimSpace(data), []byte("<")) {
		if xml.Unmarshal(data, &out) != nil || strings.ToLower(out.XMLName.Local) != "user" {
			return Credential{}, &Error{Kind: "invalid_response"}
		}
	} else if json.Unmarshal(data, &out) != nil {
		return Credential{}, &Error{Kind: "invalid_response"}
	}
	if out.ID != userID {
		return Credential{}, &Error{Kind: "identity_mismatch"}
	}
	if out.AuthenticationToken == "" {
		return Credential{}, &Error{Kind: "user_access_unavailable"}
	}
	return Credential{out.AuthenticationToken}, nil
}

// Legacy cloud endpoints are known to return XML despite Accept: application/json.
// JSON support is confined to the equivalent explicit MediaContainer envelope.
func decodeLegacyContainer(data []byte, out any) error {
	if bytes.HasPrefix(bytes.TrimSpace(data), []byte("<")) {
		if xml.Unmarshal(data, out) == nil {
			return nil
		}
	} else {
		var envelope struct {
			Container json.RawMessage `json:"MediaContainer"`
		}
		if json.Unmarshal(data, &envelope) == nil && len(envelope.Container) > 0 && string(envelope.Container) != "null" {
			if json.Unmarshal(envelope.Container, out) == nil {
				return nil
			}
		}
	}
	return &Error{Kind: "invalid_response"}
}

func validMachineIdentifier(id string) bool {
	if id == "" || len(id) > 256 {
		return false
	}
	for _, ch := range id {
		valid := ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '-' || ch == '_'
		if !valid {
			return false
		}
	}
	return true
}

func containsCapability(list, want string) bool {
	for field := range strings.SplitSeq(list, ",") {
		if strings.TrimSpace(field) == want {
			return true
		}
	}
	return false
}
