package plexsync

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex"
)

// signIn is a Plex sign-in in progress: a new device key, the PIN Plex
// gave it, and, once the admin has signed in on Plex's page, the verified
// account and the servers it owns. It is held in memory only, for one
// admin's session, until the PIN expires (ten minutes at most), and is
// used up by connecting.
type signIn struct {
	id       string
	who      Requester
	clientID string
	key      *plex.DeviceKey
	pin      plex.PIN
	account  *plex.Account
	servers  []plex.Resource
	busy     bool
}

// PlexSignIn is a Plex sign-in in progress as the admin sees it.
type PlexSignIn struct {
	ID        string       `json:"id"`
	Status    string       `json:"status" enum:"pending,ready" doc:"pending until the admin has signed in on Plex's page."`
	AuthURL   string       `json:"authUrl,omitempty" doc:"Plex's own sign-in page for this attempt, while pending. WatchMyMedia never asks for a Plex password."`
	ExpiresAt time.Time    `json:"expiresAt"`
	Servers   []PlexServer `json:"servers,omitempty" doc:"The servers the signed-in Plex account owns, once ready."`
}

// PlexServer is a Plex server the signed-in account owns.
type PlexServer struct {
	MachineIdentifier string        `json:"machineIdentifier"`
	Name              string        `json:"name" doc:"The server's name in Plex."`
	Version           string        `json:"version,omitempty"`
	Addresses         []PlexAddress `json:"addresses" nullable:"false" doc:"Addresses Plex knows for it, relays left out. Another address can be given when connecting."`
}

type PlexAddress struct {
	URL   string `json:"url"`
	Local bool   `json:"local" doc:"On the server's own network."`
}

// StartSignIn begins a Plex sign-in for a new device. The admin finishes it
// on Plex's page at AuthURL.
func (s *Service) StartSignIn(ctx context.Context, who Requester) (PlexSignIn, error) {
	s.mu.Lock()
	s.sweepSignIns()
	full := len(s.signIns) >= maxSignIns
	s.mu.Unlock()
	if full {
		return PlexSignIn{}, fail(Conflict, "too_many_sign_ins")
	}
	key, err := plex.GenerateDeviceKey()
	if err != nil {
		return PlexSignIn{}, plexFailed(err)
	}
	suffix, err := randomHex(16)
	if err != nil {
		return PlexSignIn{}, err
	}
	id, err := randomHex(16)
	if err != nil {
		return PlexSignIn{}, err
	}
	a := &signIn{id: id, who: who, clientID: "watchmymedia-" + suffix, key: key}
	c, err := plex.NewClient(s.clients, "", a.opts(s))
	if err != nil {
		return PlexSignIn{}, plexFailed(err)
	}
	if a.pin, err = c.CreatePIN(ctx, key); err != nil {
		return PlexSignIn{}, plexFailed(err)
	}
	s.mu.Lock()
	s.signIns[id] = a
	s.mu.Unlock()
	return a.view(), nil
}

func (a *signIn) opts(s *Service) plex.Options {
	return plex.Options{ClientIdentifier: a.clientID, Product: product, HTTPClient: s.cloud}
}

func (a *signIn) view() PlexSignIn {
	v := PlexSignIn{ID: a.id, Status: "pending", ExpiresAt: a.pin.ExpiresAt}
	if a.account == nil {
		v.AuthURL = a.pin.AuthURL()
		return v
	}
	v.Status = "ready"
	for _, r := range a.servers {
		srv := PlexServer{MachineIdentifier: r.MachineIdentifier, Name: r.Name, Version: r.ProductVersion, Addresses: []PlexAddress{}}
		for _, conn := range r.Connections {
			if !conn.Relay {
				if origin, err := cleanOrigin(conn.URI); err == nil {
					srv.Addresses = append(srv.Addresses, PlexAddress{URL: origin, Local: conn.Local})
				}
			}
		}
		v.Servers = append(v.Servers, srv)
	}
	return v
}

// sweepSignIns forgets expired sign-ins. Call with s.mu held.
func (s *Service) sweepSignIns() {
	now := s.now()
	for id, a := range s.signIns {
		if !a.pin.ExpiresAt.After(now) {
			delete(s.signIns, id)
		}
	}
}

// take finds the requester's sign-in and marks it busy, so one request at
// a time works on it. The returned func releases it.
func (s *Service) take(who Requester, id string) (*signIn, func(), error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepSignIns()
	a := s.signIns[id]
	if a == nil || a.who != who {
		return nil, nil, fail(NotFound, "sign_in_not_found")
	}
	if a.busy {
		return nil, nil, fail(Conflict, "sign_in_busy")
	}
	a.busy = true
	return a, func() {
		s.mu.Lock()
		a.busy = false
		s.mu.Unlock()
	}, nil
}

// SignInStatus asks Plex whether the admin has signed in. Once they have,
// it verifies the account and lists the servers it owns.
func (s *Service) SignInStatus(ctx context.Context, who Requester, id string) (PlexSignIn, error) {
	a, release, err := s.take(who, id)
	if err != nil {
		return PlexSignIn{}, err
	}
	defer release()
	if a.account != nil {
		return a.view(), nil
	}
	c, err := plex.NewClient(s.clients, "", a.opts(s))
	if err != nil {
		return PlexSignIn{}, plexFailed(err)
	}
	cred, ready, err := c.PollPIN(ctx, a.pin, a.key)
	if plex.ErrorKind(err) == "pin_expired" {
		s.CancelSignIn(who, id)
		return PlexSignIn{}, fail(NotFound, "sign_in_not_found")
	} else if err != nil {
		return PlexSignIn{}, plexFailed(err)
	}
	if !ready {
		return a.view(), nil
	}
	account, err := s.verifyAccount(ctx, a.opts(s), cred)
	if err != nil {
		return PlexSignIn{}, plexFailed(err)
	}
	if account.Restricted {
		s.CancelSignIn(who, id)
		return PlexSignIn{}, fail(Invalid, "restricted_account")
	}
	rc, err := plex.NewClient(s.clients, cred.Token(), a.opts(s))
	if err != nil {
		return PlexSignIn{}, plexFailed(err)
	}
	resources, err := rc.Resources(ctx)
	if err != nil {
		return PlexSignIn{}, plexFailed(err)
	}
	var owned []plex.Resource
	for _, r := range resources {
		if r.Owned {
			owned = append(owned, r)
		}
	}
	a.account, a.servers = &account, owned
	return a.view(), nil
}

// CancelSignIn forgets a sign-in. Its device stays in the account's Plex
// authorized devices only if the admin finished signing in.
func (s *Service) CancelSignIn(who Requester, id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if a := s.signIns[id]; a != nil && a.who == who {
		delete(s.signIns, id)
	}
}

// PlexConnect is the server an admin chose from a finished sign-in, at one of
// its addresses or another they typed.
type PlexConnect struct {
	SignInID          string `json:"signInId" minLength:"1" maxLength:"64"`
	MachineIdentifier string `json:"machineIdentifier" minLength:"1" maxLength:"256"`
	Origin            string `json:"origin" minLength:"1" maxLength:"512" doc:"The server's address: http or https, a host and a port, as http://192.168.1.20:32400."`
}

// Connect connects a server the signed-in account owns. Before saving
// anything it checks that the address answers as that server, that the
// device's grant renews, and that the owner can read the server; it
// connects again (keeping the links) a server already connected by the
// same Plex owner. One server for now.
func (s *Service) Connect(ctx context.Context, who Requester, in PlexConnect) (PlexConnection, error) {
	a, release, err := s.take(who, in.SignInID)
	if err != nil {
		return PlexConnection{}, err
	}
	defer release()
	if a.account == nil {
		return PlexConnection{}, fail(Conflict, "sign_in_pending")
	}
	resource, ok := exactResource(a.servers, in.MachineIdentifier)
	if !ok {
		return PlexConnection{}, fail(Invalid, "server_not_owned")
	}
	origin, err := cleanOrigin(in.Origin)
	if err != nil {
		return PlexConnection{}, fail(Invalid, "origin_not_allowed")
	}
	existing, err := s.q.GetPlexConnectionByMachine(ctx, resource.MachineIdentifier)
	reconnect := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return PlexConnection{}, err
	}
	if reconnect && existing.OwnerPlexID != a.account.ID {
		return PlexConnection{}, fail(Conflict, "different_owner")
	}
	if !reconnect {
		all, err := s.q.ListPlexConnections(ctx)
		if err != nil {
			return PlexConnection{}, err
		}
		if len(all) > 0 {
			return PlexConnection{}, fail(Conflict, "one_server_only")
		}
	}
	// The grant must renew before it is kept: every later use renews it.
	renewer, err := plex.NewClient(s.clients, "", a.opts(s))
	if err != nil {
		return PlexConnection{}, plexFailed(err)
	}
	cloud, err := renewer.RefreshJWT(ctx, a.key)
	if err != nil {
		return PlexConnection{}, plexFailed(err)
	}
	account, err := s.verifyAccount(ctx, a.opts(s), cloud)
	if err != nil {
		return PlexConnection{}, plexFailed(err)
	}
	if account.ID != a.account.ID || account.Restricted {
		return PlexConnection{}, fail(Invalid, "identity_mismatch")
	}
	o, err := s.ownerServer(ctx, a.opts(s), cloud, account, resource.MachineIdentifier, origin)
	if err != nil {
		return PlexConnection{}, err
	}
	identity, err := plex.NewClient(origin, "", o.serverOpts)
	if err != nil {
		return PlexConnection{}, plexFailed(err)
	}
	version := ""
	if id, err := identity.Identity(ctx); err == nil && len(id.Version) <= 64 {
		version = id.Version
	}
	history := historyCapability(ctx, o.pms, s.now())
	ref, err := s.store.put(grant{ClientIdentifier: a.clientID, Key: a.key})
	if err != nil {
		return PlexConnection{}, err
	}
	var c db.PlexConnection
	if reconnect {
		var n int64
		n, err = s.q.ReconnectPlexConnection(ctx, db.ReconnectPlexConnectionParams{Name: resource.Name, Origin: origin, CredentialRef: &ref,
			ServerVersion: version, HistoryCapability: history, AuthorizedBy: &who.UserID, ID: existing.ID, OwnerPlexID: account.ID})
		if err == nil && n == 0 {
			err = fail(Conflict, "different_owner")
		}
		if err == nil {
			c, err = s.q.GetPlexConnection(ctx, existing.ID)
		}
	} else {
		c, err = s.q.InsertPlexConnection(ctx, db.InsertPlexConnectionParams{MachineIdentifier: resource.MachineIdentifier, Name: resource.Name,
			Origin: origin, CredentialRef: &ref, OwnerPlexID: account.ID, ServerVersion: version, HistoryCapability: history, AuthorizedBy: &who.UserID})
	}
	if err != nil {
		_ = s.store.remove(ref)
		return PlexConnection{}, err
	}
	if reconnect && existing.CredentialRef != nil && *existing.CredentialRef != ref {
		_ = s.store.remove(*existing.CredentialRef)
	}
	s.mu.Lock()
	delete(s.signIns, a.id)
	s.mu.Unlock()
	s.dropPreview(c.ID)
	s.publishAdmins(ctx, c.ID)
	s.log.Info("plex: connected", "connection", c.ID, "reconnected", reconnect, "history", history)
	return s.view(ctx, c)
}

// historyCapability tries the smallest history read the owner can make:
// events from now on, which normally finds none. A refusal means the server
// won't share play history; Plex may tie it to Plex Pass.
func historyCapability(ctx context.Context, pms *plex.Client, now time.Time) string {
	_, err := pms.History(ctx, plex.HistoryQuery{AccountID: 1, Since: now, Paging: plex.Paging{PageSize: 1, MaxPages: 1, MaxItems: 1}})
	switch {
	case err == nil:
		return "available"
	case isAuthorization(err):
		return "unavailable"
	}
	return "unknown"
}
