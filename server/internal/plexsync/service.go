// Package plexsync connects a Plex server and imports watched marks and separate
// history under explicit mappings and durable, opt-in runs. It only reads Plex
// data; sign-in and grant renewal are the only remote mutations. Local watch
// choices always win, and imported history never contributes measured plays.
package plexsync

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"sync"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/watchstate"
)

const (
	clientsOrigin = "https://clients.plex.tv"
	accountOrigin = "https://plex.tv"
	// product names WatchMyMedia in the owner's Plex authorized devices.
	product = "WatchMyMedia"
	// maxSignIns bounds the sign-ins waiting for Plex at once.
	maxSignIns = 8
)

// Kind is what sort of refusal an Error is, which decides its HTTP status.
type Kind int

const (
	NotFound    Kind = iota + 1
	Invalid          // the request can't be done as asked
	Conflict         // the connection's state doesn't allow it now
	Unavailable      // Plex couldn't be reached, or answered wrongly
)

// Error is a refusal with a stable reason code for the settings page to
// explain. It never holds anything Plex sent beyond the adapter's reason
// codes: no addresses, tokens, names or response text.
type Error struct {
	Kind       Kind
	Code       string
	RetryAfter time.Duration
	statusCode int
}

func (e *Error) Error() string { return "plex: " + e.Code }

func fail(kind Kind, code string) error { return &Error{Kind: kind, Code: code} }

// plexFailed passes on an adapter error as its reason code.
func plexFailed(err error) error {
	if e, ok := errors.AsType[*Error](err); ok {
		return e
	}
	if e, ok := errors.AsType[*plex.Error](err); ok {
		return &Error{Kind: Unavailable, Code: plex.ErrorKind(err), RetryAfter: e.RetryAfter, statusCode: e.StatusCode}
	}
	return fail(Unavailable, plex.ErrorKind(err))
}

// retryablePlexError keeps retry policy aligned with the adapter's reason codes.
// A missing/unsupported endpoint is not a transient server failure.
func retryablePlexError(err error) bool {
	switch reasonCode(err) {
	case "transport", "timeout", "response_read", "rate_limit":
		return true
	case "http":
		if e, ok := errors.AsType[*plex.Error](err); ok {
			return e.StatusCode >= 500 && e.StatusCode <= 599
		}
		if e, ok := errors.AsType[*Error](err); ok {
			return e.statusCode >= 500 && e.statusCode <= 599
		}
	}
	return false
}

func isAuthorization(err error) bool { return plex.ErrorKind(err) == "authorization" }

// Options are for tests: Plex's own services and both HTTP clients can be
// replaced with fakes. Nothing in the server's configuration or API can
// change where credentials go.
type Options struct {
	ClientsOrigin, AccountOrigin string
	CloudHTTP, ServerHTTP        *http.Client
	Log                          *slog.Logger
	Now                          func() time.Time
	Bus                          *events.Bus
	// Test barriers run outside database transactions. Production leaves them nil.
	BeforeFinalize      func()
	BeforeHistoryCommit func()
	BeforeImportCommit  func()
}

// Service is the server's Plex connection.
type Service struct {
	q                                                       *db.Queries
	watch                                                   *watchstate.Service
	store                                                   *credStore
	log                                                     *slog.Logger
	base                                                    context.Context
	clients                                                 string
	account                                                 string
	cloud                                                   *http.Client
	server                                                  *http.Client
	now                                                     func() time.Time
	bus                                                     *events.Bus
	historyPages                                            int
	lastCleanup                                             time.Time
	beforeFinalize, beforeHistoryCommit, beforeImportCommit func()

	mu           sync.Mutex
	work         sync.WaitGroup
	closing      bool
	signIns      map[string]*signIn
	previews     map[int64]*preview
	undoPreviews map[string]undoPreview
	wake         chan struct{}
	renewals     map[int64]*sync.Mutex
}

// New opens the grant store under dataDir and removes grants no connection
// refers to (left by a crash, or by restoring a backup). Previews run
// under ctx, so they stop when the server does.
func New(ctx context.Context, q *db.Queries, watch *watchstate.Service, dataDir string, opts Options) (*Service, error) {
	store, err := openCredStore(dataDir)
	if err != nil {
		return nil, err
	}
	s := &Service{q: q, watch: watch, store: store, log: opts.Log, base: ctx,
		clients: clientsOrigin, account: accountOrigin, cloud: opts.CloudHTTP, server: opts.ServerHTTP, now: opts.Now, bus: opts.Bus, historyPages: 250, beforeFinalize: opts.BeforeFinalize, beforeHistoryCommit: opts.BeforeHistoryCommit, beforeImportCommit: opts.BeforeImportCommit,
		signIns: map[string]*signIn{}, previews: map[int64]*preview{}, undoPreviews: map[string]undoPreview{}, wake: make(chan struct{}, 1), renewals: map[int64]*sync.Mutex{}}
	if opts.ClientsOrigin != "" {
		s.clients = opts.ClientsOrigin
	}
	if opts.AccountOrigin != "" {
		s.account = opts.AccountOrigin
	}
	if s.log == nil {
		s.log = slog.Default()
	}
	if s.server == nil {
		s.server = serverHTTPClient()
	}
	if s.now == nil {
		s.now = time.Now
	}
	refs, err := q.ListPlexCredentialRefs(ctx)
	if err != nil {
		return nil, err
	}
	live := make(map[string]bool, len(refs))
	for _, r := range refs {
		if r != nil {
			live[*r] = true
		}
	}
	if n, err := store.reconcile(live, time.Minute, s.now()); err != nil {
		return nil, err
	} else if n > 0 {
		s.log.Info("plex: removed credentials no connection uses", "count", n)
	}
	return s, nil
}

// Requester is the admin making a request and a digest of the session
// they made it in: a sign-in started in one session finishes only there.
type Requester struct {
	UserID  int64
	Session string
}

// PlexConnection is a connected Plex server as the settings page shows it.
type PlexConnection struct {
	ID                int64  `json:"id"`
	MachineIdentifier string `json:"machineIdentifier"`
	Name              string `json:"name" doc:"The server's name in Plex."`
	Origin            string `json:"origin" doc:"The address WatchMyMedia reads the server at."`
	ServerVersion     string `json:"serverVersion"`
	Status            string `json:"status" enum:"connected,needs_reconnect,needs_authorization" doc:"needs_reconnect: sign in to Plex again (Plex withdrew the grant, or the server was restored from a backup). needs_authorization: the admin who connected it is gone; another admin connects it again."`
	Problem           string `json:"problem,omitempty" doc:"Why it can't be used, as a reason code."`
	HistoryCapability string `json:"historyCapability" enum:"available,unavailable,unknown" doc:"Whether the server let its owner read play history when connected."`
	SyncEnabled       bool   `json:"syncEnabled" doc:"Whether imports run on a schedule. Connecting alone imports nothing; recurring sync is explicitly enabled."`
	HasApprovedImport bool   `json:"hasApprovedImport" required:"false" doc:"A completed or partial import approved the current mapping generation, so recurring checks may be resumed. Independent of run-list pagination."`
	Generation        int64  `json:"generation" doc:"Goes up with every change to the links; send it back when saving them."`
	LinkedUsers       int    `json:"linkedUsers"`
	LinkedLibraries   int    `json:"linkedLibraries"`
}

// Connections lists the connected server (at most one for now).
func (s *Service) Connections(ctx context.Context) ([]PlexConnection, error) {
	rows, err := s.q.ListPlexConnections(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]PlexConnection, 0, len(rows))
	for _, c := range rows {
		v, err := s.view(ctx, c)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, nil
}

func (s *Service) view(ctx context.Context, c db.PlexConnection) (PlexConnection, error) {
	users, err := s.q.ListPlexUserLinks(ctx, c.ID)
	if err != nil {
		return PlexConnection{}, err
	}
	libs, err := s.q.ListPlexLibraryLinks(ctx, c.ID)
	if err != nil {
		return PlexConnection{}, err
	}
	approved, err := s.q.HasApprovedPlexRun(ctx, db.HasApprovedPlexRunParams{ConnectionID: &c.ID, Generation: c.Generation})
	if err != nil {
		return PlexConnection{}, err
	}
	status := "connected"
	switch {
	case c.CredentialRef == nil:
		status = "needs_reconnect"
	case !s.authorized(ctx, c):
		status = "needs_authorization"
	}
	return PlexConnection{ID: c.ID, MachineIdentifier: c.MachineIdentifier, Name: c.Name, Origin: c.Origin,
		ServerVersion: c.ServerVersion, Status: status, Problem: c.Problem, HistoryCapability: c.HistoryCapability,
		SyncEnabled: c.SyncEnabled == 1, HasApprovedImport: approved, Generation: c.Generation, LinkedUsers: len(users), LinkedLibraries: len(libs)}, nil
}

// authorized reports whether the admin whose sign-in made the grant is
// still an admin. Without them the connection does nothing until another
// admin connects it again.
func (s *Service) authorized(ctx context.Context, c db.PlexConnection) bool {
	if c.AuthorizedBy == nil {
		return false
	}
	u, err := s.q.GetUser(ctx, *c.AuthorizedBy)
	return err == nil && u.IsAdmin == 1
}

func (s *Service) connection(ctx context.Context, id int64) (db.PlexConnection, error) {
	c, err := s.q.GetPlexConnection(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return c, fail(NotFound, "connection_not_found")
	}
	return c, err
}

// Disconnect removes the connection, its links and its grant. Plex is not
// told: removing WatchMyMedia from the owner's authorized devices is done
// in Plex. Nothing has been imported, so nothing else changes.
func (s *Service) Disconnect(ctx context.Context, id int64) error {
	c, err := s.connection(ctx, id)
	if err != nil {
		return err
	}
	s.dropPreview(id)
	if err := s.q.DeletePlexConnection(ctx, id); err != nil {
		return err
	}
	if c.CredentialRef != nil {
		if err := s.store.remove(*c.CredentialRef); err != nil {
			s.log.Warn("plex: could not remove a disconnected grant", "connection", id, "err", err)
		}
	}
	s.publishAdmins(ctx, id)
	s.log.Info("plex: disconnected", "connection", id)
	return nil
}

// owner is a verified session as the server's owner: a renewed sign-in that
// is still the connection's owner, and that owner's credential for the
// server, checked to be the connected server at the connected address.
type owner struct {
	conn       db.PlexConnection
	cloudOpts  plex.Options
	serverOpts plex.Options
	cloud      plex.Credential
	account    plex.Account
	resource   plex.Resource
	pms        *plex.Client
	pmsToken   string
	sections   []plex.LibrarySection
}

// openOwner renews the connection's grant and checks it before any
// credential derived from it is used. A grant Plex refuses, or that now
// belongs to someone else, is removed: the connection waits for a new
// sign-in rather than retrying as the owner.
func (s *Service) openOwner(ctx context.Context, c db.PlexConnection) (*owner, error) {
	if c.CredentialRef == nil {
		return nil, fail(Conflict, "needs_reconnect")
	}
	if !s.authorized(ctx, c) {
		return nil, fail(Conflict, "needs_authorization")
	}
	unlock := s.renewal(c.ID)
	defer unlock()
	g, err := s.store.get(*c.CredentialRef)
	if err != nil {
		return nil, s.lose(ctx, c, "credential_unreadable")
	}
	cloudOpts := plex.Options{ClientIdentifier: g.ClientIdentifier, Product: product, HTTPClient: s.cloud}
	renewer, err := plex.NewClient(s.clients, "", cloudOpts)
	if err != nil {
		return nil, plexFailed(err)
	}
	cloud, err := renewer.RefreshJWT(ctx, g.Key)
	if isAuthorization(err) {
		return nil, s.lose(ctx, c, "authorization_lost")
	} else if err != nil {
		return nil, plexFailed(err)
	}
	account, err := s.verifyAccount(ctx, cloudOpts, cloud)
	if isAuthorization(err) {
		return nil, s.lose(ctx, c, "authorization_lost")
	} else if err != nil {
		return nil, plexFailed(err)
	}
	if account.ID != c.OwnerPlexID || account.Restricted {
		return nil, s.lose(ctx, c, "owner_changed")
	}
	o, err := s.ownerServer(ctx, cloudOpts, cloud, account, c.MachineIdentifier, c.Origin)
	if e, ok := errors.AsType[*Error](err); ok && e.Code == "server_not_owned" {
		return nil, s.lose(ctx, c, "server_not_owned")
	} else if err != nil {
		return nil, err
	}
	o.conn = c
	return o, nil
}

func (s *Service) verifyAccount(ctx context.Context, opts plex.Options, cred plex.Credential) (plex.Account, error) {
	c, err := plex.NewClient(s.account, cred.Token(), opts)
	if err != nil {
		return plex.Account{}, err
	}
	return c.Account(ctx)
}

// ownerServer finds the owner's credential for the server: the resource's
// own token, or, only when the server refuses that with HTTP 401, the
// owner's device token for it (PLAN-PLEX-SYNC.md, "Authentication result").
// The address must answer with the server's identity before either is sent.
func (s *Service) ownerServer(ctx context.Context, cloudOpts plex.Options, cloud plex.Credential, account plex.Account, machine, origin string) (*owner, error) {
	rc, err := plex.NewClient(s.clients, cloud.Token(), cloudOpts)
	if err != nil {
		return nil, plexFailed(err)
	}
	resources, err := rc.Resources(ctx)
	if err != nil {
		return nil, plexFailed(err)
	}
	resource, ok := exactResource(resources, machine)
	if !ok || !resource.Owned {
		return nil, fail(Conflict, "server_not_owned")
	}
	serverOpts := plex.Options{ClientIdentifier: cloudOpts.ClientIdentifier, Product: product, HTTPClient: s.server}
	if err := s.verifyServer(ctx, origin, machine, serverOpts); err != nil {
		return nil, err
	}
	token := resource.Credential.Token()
	pms, sections, err := s.serverClient(ctx, origin, token, serverOpts)
	if plex.ErrorKind(err) == "authorization" {
		if pe, ok := errors.AsType[*plex.Error](err); ok && pe.StatusCode == http.StatusUnauthorized {
			device, derr := rc.OwnerDeviceCredential(ctx, account, resource)
			if derr != nil {
				return nil, plexFailed(derr)
			}
			if err := s.verifyServer(ctx, origin, machine, serverOpts); err != nil {
				return nil, err
			}
			token = device.Token()
			pms, sections, err = s.serverClient(ctx, origin, token, serverOpts)
		}
	}
	if err != nil {
		return nil, plexFailed(err)
	}
	return &owner{cloudOpts: cloudOpts, serverOpts: serverOpts, cloud: cloud, account: account, resource: resource,
		pms: pms, pmsToken: token, sections: sections}, nil
}

func (s *Service) verifyServer(ctx context.Context, origin, machine string, opts plex.Options) error {
	c, err := plex.NewClient(origin, "", opts)
	if err != nil {
		return fail(Invalid, "origin_not_allowed")
	}
	if _, err := c.VerifyIdentity(ctx, machine); plex.ErrorKind(err) == "identity_mismatch" {
		return fail(Invalid, "server_identity_mismatch")
	} else if err != nil {
		return plexFailed(err)
	}
	return nil
}

func (s *Service) serverClient(ctx context.Context, origin, token string, opts plex.Options) (*plex.Client, []plex.LibrarySection, error) {
	if token == "" {
		return nil, nil, fail(Unavailable, "server_credential_unavailable")
	}
	c, err := plex.NewClient(origin, token, opts)
	if err != nil {
		return nil, nil, err
	}
	sections, err := c.Libraries(ctx)
	if err != nil {
		return nil, nil, err
	}
	return c, sections, nil
}

func exactResource(resources []plex.Resource, machine string) (plex.Resource, bool) {
	var found plex.Resource
	n := 0
	for _, r := range resources {
		if r.MachineIdentifier == machine {
			found, n = r, n+1
		}
	}
	return found, n == 1
}

// lose removes a grant that can no longer be used and records why.
func (s *Service) lose(ctx context.Context, c db.PlexConnection, problem string) error {
	if c.CredentialRef != nil {
		if err := s.q.ClearPlexCredential(ctx, db.ClearPlexCredentialParams{Problem: problem, ID: c.ID, CredentialRef: c.CredentialRef}); err != nil {
			return err
		}
		_ = s.store.remove(*c.CredentialRef)
	}
	s.dropPreview(c.ID)
	s.log.Warn("plex: connection needs a new sign-in", "connection", c.ID, "reason", problem)
	return fail(Conflict, "needs_reconnect")
}

// renewal serializes renewing one connection's grant.
func (s *Service) renewal(id int64) func() {
	s.mu.Lock()
	m := s.renewals[id]
	if m == nil {
		m = &sync.Mutex{}
		s.renewals[id] = m
	}
	s.mu.Unlock()
	m.Lock()
	return m.Unlock
}

// People lists everyone on the server: the owner, Home members (managed
// profiles included) and the users it is shared with, each with their kind,
// whether they can be linked, and who they are linked to.
func (s *Service) People(ctx context.Context, id int64) (int64, []PlexPerson, error) {
	c, err := s.connection(ctx, id)
	if err != nil {
		return 0, nil, err
	}
	o, err := s.openOwner(ctx, c)
	if err != nil {
		return 0, nil, err
	}
	people, err := s.people(ctx, o)
	if err != nil {
		return 0, nil, err
	}
	links, err := s.q.ListPlexUserLinks(ctx, id)
	if err != nil {
		return 0, nil, err
	}
	linked := make(map[int64]int64, len(links))
	for _, l := range links {
		linked[l.PlexUserID] = l.UserID
	}
	for i := range people {
		people[i].LinkedUserID = linked[people[i].PlexUserID]
	}
	return c.Generation, people, nil
}

func (s *Service) people(ctx context.Context, o *owner) ([]PlexPerson, error) {
	return s.readPeople(ctx, o, false)
}

func (s *Service) readPeople(ctx context.Context, o *owner, requireHistory bool) ([]PlexPerson, error) {
	ac, err := plex.NewClient(s.account, o.cloud.Token(), o.cloudOpts)
	if err != nil {
		return nil, plexFailed(err)
	}
	home, err := ac.HomeUsers(ctx)
	if plex.ErrorKind(err) == "unavailable" {
		home, err = nil, nil // an account without a Plex Home
	}
	if err != nil {
		return nil, plexFailed(err)
	}
	users, err := ac.Users(ctx)
	if err != nil {
		return nil, plexFailed(err)
	}
	shared, err := ac.SharedServerUsers(ctx, o.resource.MachineIdentifier)
	if err != nil {
		return nil, plexFailed(err)
	}
	// Without the server's history accounts nobody's history identity is
	// known, which doesn't stop reading their watch state.
	inventory, err := o.pms.HistoryAccounts(ctx)
	if err != nil {
		if requireHistory && retryablePlexError(err) {
			return nil, plexFailed(err)
		}
		inventory = nil
	}
	return discoverPeople(o.account, o.resource, home, users, shared, inventory), nil
}

// personClient reads one person's own watch state on the server, through
// their own credential and never the owner's in their place.
func (s *Service) personClient(ctx context.Context, o *owner, p PlexPerson) (*plex.Client, error) {
	machine, origin := o.resource.MachineIdentifier, o.conn.Origin
	switch p.access {
	case accessOwner:
		return o.pms, nil
	case accessShared:
		ac, err := plex.NewClient(s.account, o.cloud.Token(), o.cloudOpts)
		if err != nil {
			return nil, err
		}
		cred, err := ac.SharedUserCredential(ctx, machine, p.PlexUserID)
		if err != nil {
			return nil, err
		}
		return s.personServer(origin, cred.Token(), o)
	case accessHome:
		// A Home switch from the owner, verified to be the profile, then
		// the profile's own credential for this server. Renewing the
		// owner's grant does not renew a profile's (P0), so this happens
		// again each time.
		ac, err := plex.NewClient(s.account, o.cloud.Token(), o.cloudOpts)
		if err != nil {
			return nil, err
		}
		profile, err := ac.SwitchHomeUser(ctx, p.PlexUserID, "")
		if err != nil {
			return nil, err
		}
		account, err := s.verifyAccount(ctx, o.cloudOpts, profile)
		if err != nil {
			return nil, err
		}
		if account.ID != p.PlexUserID {
			return nil, &plex.Error{Kind: "identity_mismatch"}
		}
		rc, err := plex.NewClient(s.clients, profile.Token(), o.cloudOpts)
		if err != nil {
			return nil, err
		}
		resources, err := rc.Resources(ctx)
		if err != nil {
			return nil, err
		}
		resource, ok := exactResource(resources, machine)
		if !ok || resource.Owned {
			return nil, &plex.Error{Kind: "user_access_unavailable"}
		}
		return s.personServer(origin, resource.Credential.Token(), o)
	}
	return nil, &plex.Error{Kind: "user_access_unavailable"}
}

// personServer refuses a credential that is the owner's: someone else's
// watch state is never read as the owner's.
func (s *Service) personServer(origin, token string, o *owner) (*plex.Client, error) {
	if token == "" || token == o.pmsToken || token == o.cloud.Token() {
		return nil, &plex.Error{Kind: "identity_mismatch"}
	}
	return plex.NewClient(origin, token, o.serverOpts)
}

// PlexSection is a movie or TV library on the Plex server.
type PlexSection struct {
	SectionID       int64  `json:"sectionId"`
	Title           string `json:"title" doc:"The library's name in Plex."`
	Type            string `json:"type" enum:"movie,show"`
	LinkedLibraryID int64  `json:"linkedLibraryId,omitempty" doc:"The local library it is linked to."`
}

// Libraries lists the server's movie and TV libraries and their links.
func (s *Service) Libraries(ctx context.Context, id int64) (int64, []PlexSection, error) {
	c, err := s.connection(ctx, id)
	if err != nil {
		return 0, nil, err
	}
	o, err := s.openOwner(ctx, c)
	if err != nil {
		return 0, nil, err
	}
	links, err := s.q.ListPlexLibraryLinks(ctx, id)
	if err != nil {
		return 0, nil, err
	}
	linked := make(map[int64]int64, len(links))
	for _, l := range links {
		linked[l.SectionID] = l.LibraryID
	}
	out := []PlexSection{}
	for _, sec := range o.sections {
		n, err := strconv.ParseInt(sec.ID, 10, 64)
		if err != nil || (sec.Type != "movie" && sec.Type != "show") {
			continue
		}
		out = append(out, PlexSection{SectionID: n, Title: sec.Title, Type: sec.Type, LinkedLibraryID: linked[n]})
	}
	return c.Generation, out, nil
}

// PlexLinks are the people and libraries an admin links, replacing the
// connection's links as a whole.
type PlexLinks struct {
	Generation int64             `json:"generation" doc:"The generation last read; a save made from an older one is refused (409)."`
	Users      []PlexUserLink    `json:"users" maxItems:"200" nullable:"false"`
	Libraries  []PlexLibraryLink `json:"libraries" maxItems:"200" nullable:"false"`
}

type PlexUserLink struct {
	PlexUserID int64 `json:"plexUserId" minimum:"1"`
	UserID     int64 `json:"userId" minimum:"1" doc:"The local account."`
}

type PlexLibraryLink struct {
	SectionID int64 `json:"sectionId" minimum:"1"`
	LibraryID int64 `json:"libraryId" minimum:"1" doc:"A local library of the same type: movies to movies, shows to TV."`
}

// SaveLinks replaces the links, checking each against the server as it is
// now: a person must be ready to read (so not PIN-protected), and a
// library must be the same type as its local one. One Plex person per
// local account and one local account per Plex person.
func (s *Service) SaveLinks(ctx context.Context, id int64, in PlexLinks) (PlexConnection, error) {
	seenPlex, seenUser, seenSection := map[int64]bool{}, map[int64]bool{}, map[int64]bool{}
	for _, l := range in.Users {
		if seenPlex[l.PlexUserID] || seenUser[l.UserID] {
			return PlexConnection{}, fail(Invalid, "duplicate_link")
		}
		seenPlex[l.PlexUserID], seenUser[l.UserID] = true, true
	}
	for _, l := range in.Libraries {
		if seenSection[l.SectionID] {
			return PlexConnection{}, fail(Invalid, "duplicate_link")
		}
		seenSection[l.SectionID] = true
	}
	c, err := s.connection(ctx, id)
	if err != nil {
		return PlexConnection{}, err
	}
	if c.Generation != in.Generation {
		return PlexConnection{}, fail(Conflict, "stale_generation")
	}
	o, err := s.openOwner(ctx, c)
	if err != nil {
		return PlexConnection{}, err
	}
	people, err := s.people(ctx, o)
	if err != nil {
		return PlexConnection{}, err
	}
	byID := make(map[int64]PlexPerson, len(people))
	for _, p := range people {
		byID[p.PlexUserID] = p
	}
	users := make([]db.InsertPlexUserLinkParams, 0, len(in.Users))
	for _, l := range in.Users {
		p, ok := byID[l.PlexUserID]
		if !ok {
			return PlexConnection{}, fail(Invalid, "unknown_plex_user")
		}
		if p.Status != StatusReady {
			return PlexConnection{}, fail(Invalid, "plex_user_not_ready")
		}
		if _, err := s.q.GetUser(ctx, l.UserID); errors.Is(err, sql.ErrNoRows) {
			return PlexConnection{}, fail(Invalid, "unknown_user")
		} else if err != nil {
			return PlexConnection{}, err
		}
		var history *int64
		if p.HistoryAccountID > 0 {
			history = &p.HistoryAccountID
		}
		users = append(users, db.InsertPlexUserLinkParams{ConnectionID: id, PlexUserID: p.PlexUserID, Kind: p.Kind, PmsAccountID: history, UserID: l.UserID})
	}
	sections := make(map[int64]plex.LibrarySection, len(o.sections))
	for _, sec := range o.sections {
		if n, err := strconv.ParseInt(sec.ID, 10, 64); err == nil {
			sections[n] = sec
		}
	}
	libs := make([]db.InsertPlexLibraryLinkParams, 0, len(in.Libraries))
	for _, l := range in.Libraries {
		sec, ok := sections[l.SectionID]
		if !ok || (sec.Type != "movie" && sec.Type != "show") {
			return PlexConnection{}, fail(Invalid, "unknown_section")
		}
		lib, err := s.q.GetLibrary(ctx, l.LibraryID)
		if errors.Is(err, sql.ErrNoRows) {
			return PlexConnection{}, fail(Invalid, "unknown_library")
		} else if err != nil {
			return PlexConnection{}, err
		}
		if localType(sec.Type) != lib.Type {
			return PlexConnection{}, fail(Invalid, "library_type_mismatch")
		}
		libs = append(libs, db.InsertPlexLibraryLinkParams{ConnectionID: id, SectionID: l.SectionID, SectionUuid: sec.UUID, LibraryID: l.LibraryID})
	}
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		n, err := q.AdvancePlexGeneration(ctx, db.AdvancePlexGenerationParams{ID: id, Generation: in.Generation})
		if err != nil {
			return err
		}
		if n == 0 {
			return fail(Conflict, "stale_generation")
		}
		if err := q.DeletePlexUserLinks(ctx, id); err != nil {
			return err
		}
		if err := q.DeletePlexLibraryLinks(ctx, id); err != nil {
			return err
		}
		for _, u := range users {
			if err := q.InsertPlexUserLink(ctx, u); err != nil {
				return err
			}
		}
		for _, l := range libs {
			if err := q.InsertPlexLibraryLink(ctx, l); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return PlexConnection{}, err
	}
	s.dropPreview(id)
	s.publishAdmins(ctx, id)
	c, err = s.connection(ctx, id)
	if err != nil {
		return PlexConnection{}, err
	}
	return s.view(ctx, c)
}

// localType is the local library type a Plex section type links to.
func localType(sectionType string) string {
	if sectionType == "show" {
		return "tv"
	}
	return sectionType
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
