package plexsync

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/plex/plextest"
)

func wantCode(t *testing.T, err error, kind Kind, code string) {
	t.Helper()
	e, ok := errors.AsType[*Error](err)
	if !ok || e.Kind != kind || e.Code != code {
		t.Fatalf("error = %v; want %s (%d)", err, code, kind)
	}
}

// Connecting signs in on Plex's page, picks an owned server, checks the
// address answers as that server, keeps only the device grant, and turns
// nothing on: sync stays off.
func TestConnectSignsInAndKeepsOnlyTheGrant(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	in, err := e.svc.StartSignIn(ctx, e.who)
	if err != nil {
		t.Fatal(err)
	}
	if in.Status != "pending" || !strings.HasPrefix(in.AuthURL, "https://app.plex.tv/auth#?") || !strings.Contains(in.AuthURL, "WatchMyMedia") {
		t.Fatalf("sign-in = %+v", in)
	}
	if got, err := e.svc.SignInStatus(ctx, e.who, in.ID); err != nil || got.Status != "pending" {
		t.Fatalf("before signing in: %+v %v", got, err)
	}
	_, err = e.svc.Connect(ctx, e.who, PlexConnect{SignInID: in.ID, MachineIdentifier: e.f.Machine, Origin: e.f.PMS.URL})
	wantCode(t, err, Conflict, "sign_in_pending")
	e.f.Set(func(f *plextest.Fake) { f.SignedIn = true })
	ready, err := e.svc.SignInStatus(ctx, e.who, in.ID)
	if err != nil || ready.Status != "ready" || ready.AuthURL != "" {
		t.Fatalf("after signing in: %+v %v", ready, err)
	}
	if len(ready.Servers) != 1 || ready.Servers[0].MachineIdentifier != e.f.Machine || ready.Servers[0].Name != "Fictional Server" {
		t.Fatalf("servers = %+v; want only the owned one", ready.Servers)
	}
	if addrs := ready.Servers[0].Addresses; len(addrs) != 1 || addrs[0].URL != e.f.PMS.URL || !addrs[0].Local {
		t.Fatalf("addresses = %+v; want the local one, relay left out", addrs)
	}
	c, err := e.svc.Connect(ctx, e.who, PlexConnect{SignInID: in.ID, MachineIdentifier: e.f.Machine, Origin: e.f.PMS.URL + "/"})
	if err != nil {
		t.Fatal(err)
	}
	if c.Status != "connected" || c.SyncEnabled || c.Origin != e.f.PMS.URL || c.HistoryCapability != "available" || c.ServerVersion != "1.43.5.11079-fictional" {
		t.Fatalf("connection = %+v", c)
	}
	// The server refused the resource token with 401, so the owner's
	// device token was used, as P0 found.
	if len(e.f.ReadsBy(plextest.OwnerDeviceToken)) == 0 {
		t.Error("the owner's device token was not used after the resource token was refused")
	}
	if files := e.grantFiles(); len(files) != 1 || !strings.HasSuffix(files[0], ".json") {
		t.Fatalf("grant folder = %v; want one grant", files)
	}
	row, _ := e.q.GetPlexConnection(ctx, c.ID)
	if row.CredentialRef == nil || !strings.HasPrefix(e.grantFiles()[0], *row.CredentialRef) || row.AuthorizedBy == nil || *row.AuthorizedBy != e.admin {
		t.Fatalf("row = %+v", row)
	}
	// A sign-in connects once.
	_, err = e.svc.Connect(ctx, e.who, PlexConnect{SignInID: in.ID, MachineIdentifier: e.f.Machine, Origin: e.f.PMS.URL})
	wantCode(t, err, NotFound, "sign_in_not_found")
}

// A sign-in is the admin's who started it, in the session they started it
// in.
func TestSignInBelongsToItsSession(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	in, err := e.svc.StartSignIn(ctx, e.who)
	if err != nil {
		t.Fatal(err)
	}
	for _, other := range []Requester{{UserID: e.admin, Session: "session-b"}, {UserID: e.viewer, Session: "session-a"}} {
		_, err := e.svc.SignInStatus(ctx, other, in.ID)
		wantCode(t, err, NotFound, "sign_in_not_found")
		e.svc.CancelSignIn(other, in.ID)
	}
	if _, err := e.svc.SignInStatus(ctx, e.who, in.ID); err != nil {
		t.Fatalf("another session's cancel removed the sign-in: %v", err)
	}
	e.svc.CancelSignIn(e.who, in.ID)
	_, err = e.svc.SignInStatus(ctx, e.who, in.ID)
	wantCode(t, err, NotFound, "sign_in_not_found")
}

func TestConnectRefusesOtherServersAndAddresses(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	in, _ := e.svc.StartSignIn(ctx, e.who)
	e.f.Set(func(f *plextest.Fake) { f.SignedIn = true })
	if _, err := e.svc.SignInStatus(ctx, e.who, in.ID); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		machine, origin string
		kind            Kind
		code            string
	}{
		{"fictional-friend-server", e.f.PMS.URL, Invalid, "server_not_owned"},
		{e.f.Machine, "http://169.254.169.254", Invalid, "origin_not_allowed"},
		{e.f.Machine, e.f.PMS.URL + "/path", Invalid, "origin_not_allowed"},
		{e.f.Machine, "ftp://192.0.2.1", Invalid, "origin_not_allowed"},
	} {
		_, err := e.svc.Connect(ctx, e.who, PlexConnect{SignInID: in.ID, MachineIdentifier: tc.machine, Origin: tc.origin})
		wantCode(t, err, tc.kind, tc.code)
	}
	// An address that answers as another server gets no credential.
	e.f.Set(func(f *plextest.Fake) { f.Identity = "fictional-impostor" })
	_, err := e.svc.Connect(ctx, e.who, PlexConnect{SignInID: in.ID, MachineIdentifier: e.f.Machine, Origin: e.f.PMS.URL})
	wantCode(t, err, Invalid, "server_identity_mismatch")
	if reads := e.f.ReadsBy(plextest.OwnerDeviceToken); len(reads) != 0 {
		t.Fatalf("credentials went to an unverified server: %v", reads)
	}
	if files := e.grantFiles(); len(files) != 0 {
		t.Fatalf("a failed connect kept a grant: %v", files)
	}
	e.f.Set(func(f *plextest.Fake) { f.Identity = f.Machine })
	if _, err := e.svc.Connect(ctx, e.who, PlexConnect{SignInID: in.ID, MachineIdentifier: e.f.Machine, Origin: e.f.PMS.URL}); err != nil {
		t.Fatalf("the sign-in should survive refused attempts: %v", err)
	}
}

// One server for now, and a reconnect must be by the same Plex owner. A
// reconnect keeps the links and replaces the grant.
func TestReconnectKeepsLinksAndReplacesGrant(t *testing.T) {
	e := newEnv(t)
	c := e.connect()
	c = e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	old := e.grantFiles()
	again := e.connect()
	if again.ID != c.ID || again.LinkedUsers != 1 || again.LinkedLibraries != 1 || again.Generation <= c.Generation {
		t.Fatalf("reconnect = %+v", again)
	}
	if now := e.grantFiles(); len(now) != 1 || now[0] == old[0] {
		t.Fatalf("grants before %v, after %v; want the old one replaced", old, now)
	}
	e.f.Set(func(f *plextest.Fake) { f.OwnerID = 799 })
	ctx := t.Context()
	in, _ := e.svc.StartSignIn(ctx, e.who)
	if _, err := e.svc.SignInStatus(ctx, e.who, in.ID); err != nil {
		t.Fatal(err)
	}
	_, err := e.svc.Connect(ctx, e.who, PlexConnect{SignInID: in.ID, MachineIdentifier: e.f.Machine, Origin: e.f.PMS.URL})
	wantCode(t, err, Conflict, "different_owner")
}

// The list is everyone on the server, merged by Plex user ID: the owner,
// Home members including managed profiles, and invited users. A
// PIN-protected profile is listed as needing its PIN, and no PIN is ever
// sent. Names and emails are never kept.
func TestPeopleListsEveryoneByID(t *testing.T) {
	e := newEnv(t)
	c := e.connect()
	generation, people, err := e.svc.People(t.Context(), c.ID)
	if err != nil {
		t.Fatal(err)
	}
	if generation != c.Generation {
		t.Errorf("generation %d, want %d", generation, c.Generation)
	}
	got := map[int64]PlexPerson{}
	var order []int64
	for _, p := range people {
		got[p.PlexUserID] = p
		order = append(order, p.PlexUserID)
	}
	want := map[int64]struct{ kind, status, reason string }{
		701: {KindOwner, StatusReady, ""},
		702: {KindAccount, StatusReady, ""},
		704: {KindAccount, StatusNeedsProfileAuthorization, "no_server_credential"},
		705: {KindAccount, StatusReady, ""},
		706: {KindManaged, StatusReady, ""},
		707: {KindManaged, StatusNeedsPIN, "pin_protected"},
		708: {"", StatusUnavailable, "guest"},
	}
	if len(people) != len(want) || order[0] != 701 {
		t.Fatalf("people = %v; want %d with the owner first", order, len(want))
	}
	for id, w := range want {
		p := got[id]
		if p.Kind != w.kind || p.Status != w.status || p.Reason != w.reason {
			t.Errorf("%d = %+v; want %+v", id, p, w)
		}
	}
	if got[701].HistoryAccountID != 1 || got[706].HistoryAccountID != 706 || got[704].HistoryAccountID != 0 {
		t.Errorf("history accounts: owner %d, 706 %d, 704 %d", got[701].HistoryAccountID, got[706].HistoryAccountID, got[704].HistoryAccountID)
	}
	if got[702].Title != got[704].Title {
		t.Error("the fixture's same-named people should keep their shared title")
	}
	b, _ := json.Marshal(people)
	for _, secret := range []string{"example.invalid", "fictional-viewer", "fictional-member"} {
		if strings.Contains(string(b), secret) {
			t.Errorf("the list carries %q", secret)
		}
	}
	if len(e.f.Pins()) != 0 {
		t.Errorf("a PIN was sent: %d", len(e.f.Pins()))
	}
}

// The merge rules on their own: one ID on both lists is one person; the
// same ID with different kinds, or a Home admin who isn't the owner, is
// unavailable; nobody is matched by name.
func TestDiscoverPeopleRules(t *testing.T) {
	owner := plex.Account{ID: 701, Title: "owner"}
	resource := plex.Resource{MachineIdentifier: "m", Owned: true}
	inventory := []plex.HistoryAccount{{ID: 1, Key: "/accounts/1"}, {ID: 706, Key: "/accounts/706"}}
	people := discoverPeople(owner, resource,
		[]plex.HomeUser{
			{ID: 701, Title: "Same", Admin: true, HasLogin: true},
			{ID: 706, Title: "Same"},
			{ID: 709, Title: "Not the owner", Admin: true, HasLogin: true},
			{ID: 710, Title: "Login at home", HasLogin: true},
			{ID: 713, Title: "Managed at home"},
		},
		[]plex.HomeUser{
			{ID: 706, Title: "Same", Home: true},
			{ID: 710, Title: "Login at home", Home: true},
			{ID: 711, Title: "Same"},
			{ID: 712, Title: "Profile not at home", Home: true},
			{ID: 713, Title: "Managed at home", Home: true, HasLogin: true},
		},
		map[int64]bool{711: true}, inventory)
	got := map[int64]PlexPerson{}
	for _, p := range people {
		got[p.PlexUserID] = p
	}
	for id, w := range map[int64]struct{ kind, status, reason, access string }{
		701: {KindOwner, StatusReady, "", accessOwner},
		706: {KindManaged, StatusReady, "", accessHome},
		709: {"", StatusUnavailable, "conflicting_kind", ""},
		710: {KindAccount, StatusReady, "", accessHome},
		711: {KindAccount, StatusReady, "", accessShared},
		712: {KindManaged, StatusUnavailable, "not_in_home", ""},
		713: {"", StatusUnavailable, "conflicting_kind", ""},
	} {
		p := got[id]
		if p.Kind != w.kind || p.Status != w.status || p.Reason != w.reason || p.access != w.access {
			t.Errorf("%d = %+v; want %+v", id, p, w)
		}
	}
	if len(people) != 7 {
		t.Errorf("%d people; want 7, each ID once", len(people))
	}
	// The owner listed again as an invited user is a conflict too.
	people = discoverPeople(owner, resource, nil, []plex.HomeUser{{ID: 701, Title: "owner"}}, nil, nil)
	if len(people) != 1 || people[0].Status != StatusUnavailable || people[0].Reason != "conflicting_kind" {
		t.Errorf("owner on the shared list = %+v", people)
	}
}

func TestSaveLinksChecksEachLink(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	c := e.connect()
	save := func(users []PlexUserLink, libs []PlexLibraryLink) error {
		_, err := e.svc.SaveLinks(ctx, c.ID, PlexLinks{Generation: c.Generation, Users: users, Libraries: libs})
		return err
	}
	movies := []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}}
	for _, tc := range []struct {
		users []PlexUserLink
		libs  []PlexLibraryLink
		code  string
	}{
		{[]PlexUserLink{{PlexUserID: 707, UserID: e.viewer}}, movies, "plex_user_not_ready"},
		{[]PlexUserLink{{PlexUserID: 704, UserID: e.viewer}}, movies, "plex_user_not_ready"},
		{[]PlexUserLink{{PlexUserID: 999, UserID: e.viewer}}, movies, "unknown_plex_user"},
		{[]PlexUserLink{{PlexUserID: 706, UserID: 9999}}, movies, "unknown_user"},
		{[]PlexUserLink{{PlexUserID: 706, UserID: e.viewer}, {PlexUserID: 702, UserID: e.viewer}}, movies, "duplicate_link"},
		{[]PlexUserLink{{PlexUserID: 706, UserID: e.viewer}, {PlexUserID: 706, UserID: e.admin}}, movies, "duplicate_link"},
		{nil, []PlexLibraryLink{{SectionID: 1, LibraryID: e.tv}}, "library_type_mismatch"},
		{nil, []PlexLibraryLink{{SectionID: 3, LibraryID: e.movies}}, "unknown_section"},
		{nil, []PlexLibraryLink{{SectionID: 1, LibraryID: 9999}}, "unknown_library"},
	} {
		wantCode(t, save(tc.users, tc.libs), Invalid, tc.code)
	}
	got := e.link(c, []PlexUserLink{{PlexUserID: 701, UserID: e.admin}, {PlexUserID: 706, UserID: e.viewer}},
		[]PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}, {SectionID: 2, LibraryID: e.tv}})
	if got.LinkedUsers != 2 || got.LinkedLibraries != 2 || got.Generation <= c.Generation {
		t.Fatalf("saved = %+v", got)
	}
	links, _ := e.q.ListPlexUserLinks(ctx, c.ID)
	if links[1].PlexUserID != 706 || links[1].Kind != KindManaged || links[1].PmsAccountID == nil || *links[1].PmsAccountID != 706 {
		t.Fatalf("managed link = %+v", links[1])
	}
	// Saving from the generation before is refused.
	wantCode(t, save(nil, nil), Conflict, "stale_generation")
	_, people, _ := e.svc.People(ctx, c.ID)
	for _, p := range people {
		if (p.PlexUserID == 706) != (p.LinkedUserID == e.viewer) {
			t.Errorf("%d linked to %d", p.PlexUserID, p.LinkedUserID)
		}
	}
}

// A grant Plex refuses is removed and the connection waits for a new
// sign-in; nothing retries as the owner.
func TestRevokedGrantWaitsForReconnect(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	c := e.connect()
	e.f.Set(func(f *plextest.Fake) { f.Revoked = true })
	_, _, err := e.svc.People(ctx, c.ID)
	wantCode(t, err, Conflict, "needs_reconnect")
	if files := e.grantFiles(); len(files) != 0 {
		t.Fatalf("the refused grant was kept: %v", files)
	}
	list, _ := e.svc.Connections(ctx)
	if len(list) != 1 || list[0].Status != "needs_reconnect" || list[0].Problem != "authorization_lost" {
		t.Fatalf("connections = %+v", list)
	}
	_, err = e.svc.StartPreview(ctx, c.ID)
	wantCode(t, err, Conflict, "needs_reconnect")
	e.f.Set(func(f *plextest.Fake) { f.Revoked = false })
	if again := e.connect(); again.Status != "connected" || again.Problem != "" {
		t.Fatalf("reconnect = %+v", again)
	}
}

// Deleting the admin whose sign-in made the grant pauses the connection
// until another admin connects it again.
func TestAuthorizingAdminDeletedPauses(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	c := e.connect()
	if err := e.q.DeleteUser(ctx, e.admin); err != nil {
		t.Fatal(err)
	}
	list, _ := e.svc.Connections(ctx)
	if list[0].Status != "needs_authorization" || list[0].Generation <= c.Generation {
		t.Fatalf("connections = %+v", list)
	}
	_, _, err := e.svc.People(ctx, c.ID)
	wantCode(t, err, Conflict, "needs_authorization")
}

func TestDisconnectRemovesConnectionLinksAndGrant(t *testing.T) {
	e := newEnv(t)
	ctx := t.Context()
	c := e.connect()
	e.link(c, []PlexUserLink{{PlexUserID: 706, UserID: e.viewer}}, []PlexLibraryLink{{SectionID: 1, LibraryID: e.movies}})
	if err := e.svc.Disconnect(ctx, c.ID); err != nil {
		t.Fatal(err)
	}
	if list, _ := e.svc.Connections(ctx); len(list) != 0 {
		t.Fatalf("connections = %+v", list)
	}
	if links, _ := e.q.ListPlexUserLinks(ctx, c.ID); len(links) != 0 {
		t.Fatal("links survived")
	}
	if files := e.grantFiles(); len(files) != 0 {
		t.Fatalf("grant survived: %v", files)
	}
	wantCode(t, e.svc.Disconnect(ctx, c.ID), NotFound, "connection_not_found")
}

// A server start reads the kept grant (renewing with the same device key
// Plex registered) and removes grants no connection refers to, once they
// are older than the grace period.
func TestRestartUsesTheKeptGrantAndRemovesOrphans(t *testing.T) {
	e := newEnv(t)
	c := e.connect()
	dir := filepath.Join(e.dataDir, CredentialsDir)
	orphan := filepath.Join(dir, "0123456789abcdef0123456789abcdef.json")
	young := filepath.Join(dir, "fedcba9876543210fedcba9876543210.json")
	for _, p := range []string{orphan, young} {
		if err := os.WriteFile(p, []byte("{}"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	past := time.Now().Add(-time.Hour)
	if err := os.Chtimes(orphan, past, past); err != nil {
		t.Fatal(err)
	}
	restarted := e.open()
	if _, err := os.Stat(orphan); !errors.Is(err, os.ErrNotExist) {
		t.Error("an old orphan grant survived the start")
	}
	if _, err := os.Stat(young); err != nil {
		t.Error("a grant written moments ago was removed")
	}
	if _, _, err := restarted.People(t.Context(), c.ID); err != nil {
		t.Fatalf("after a restart: %v", err)
	}
}

func TestOrigins(t *testing.T) {
	for raw, want := range map[string]string{
		"http://192.168.1.20:32400":         "http://192.168.1.20:32400",
		"https://plex.example.invalid:443/": "https://plex.example.invalid:443",
		" http://127.0.0.1:32400 ":          "http://127.0.0.1:32400",
		"http://[fd12::1]:32400":            "http://[fd12::1]:32400",
	} {
		if got, err := cleanOrigin(raw); err != nil || got != want {
			t.Errorf("cleanOrigin(%q) = %q, %v; want %q", raw, got, err, want)
		}
	}
	for _, raw := range []string{"", "192.168.1.20:32400", "ftp://h", "http://user:pass@h", "http://h/web", "http://h?x=1", "http://h#f",
		"http://169.254.169.254", "http://[fe80::1]:32400", "http://0.0.0.0:32400", "http://[::]:1", "http://224.0.0.1",
		"http://[fd00:ec2::254]", "http://100.100.100.200", "http://h:0", "http://h:70000", "http://[::ffff:169.254.169.254]"} {
		if got, err := cleanOrigin(raw); err == nil {
			t.Errorf("cleanOrigin(%q) = %q; want refused", raw, got)
		}
	}
	// A name is checked again on the address it resolves to.
	for addr, ok := range map[string]bool{"192.168.1.20:32400": true, "127.0.0.1:1": true, "169.254.169.254:80": false, "[fe80::1]:80": false, "nonsense": false} {
		if err := allowedDial("tcp", addr, nil); (err == nil) != ok {
			t.Errorf("allowedDial(%q) = %v", addr, err)
		}
	}
}
