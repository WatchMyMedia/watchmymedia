package auth

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/netguard"
)

func setup(t *testing.T) (*Service, *db.Queries) {
	t.Helper()
	_, q := dbtest.Open(t)
	return New(q, time.Hour, false), q
}

func TestPasswordHashing(t *testing.T) {
	h, err := hashWith(defaultArgon, "correct horse")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(h, "$argon2id$v=19$m=65536,t=3,p=2$") || !VerifyPassword(h, "correct horse") || VerifyPassword(h, "wrong") || VerifyPassword("garbage", "x") {
		t.Errorf("hash = %q", h)
	}
	var ie *InvalidError
	if _, err := HashPassword("seven77"); !errors.As(err, &ie) {
		t.Errorf("seven-character password -> %v", err)
	}
	// The tests' cheaper hashes record their parameters and verify too.
	cheap, err := HashPassword("correct horse")
	if err != nil || cheap == h || !VerifyPassword(cheap, "correct horse") || VerifyPassword(cheap, "wrong") {
		t.Errorf("hash with the tests' parameters = %q %v", cheap, err)
	}
	// Unknown names are checked against a hash made with today's settings,
	// so they take as long as a wrong password.
	if params := strings.Split(cheap, "$")[:4]; !strings.HasPrefix(dummyHash(), strings.Join(params, "$")+"$") {
		t.Errorf("dummy hash %q has other parameters than %q", dummyHash(), cheap)
	}
}

func TestSetupLoginAuthenticateLogout(t *testing.T) {
	s, q := setup(t)
	ctx := context.Background()
	if need, _ := s.SetupRequired(ctx); !need {
		t.Fatal("setup should be required with no users")
	}
	u, err := s.CreateUser(ctx, "Taimoor", "secret12", true)
	if err != nil {
		t.Fatal(err)
	}
	if !u.IsAdmin || u.Name != "Taimoor" {
		t.Errorf("user = %+v", u)
	}
	if _, err := s.CreateUser(ctx, "taimoor", "other123", false); err == nil {
		t.Error("duplicate name (case-insensitive) accepted")
	}
	if _, _, err := s.Login(ctx, Credentials{Name: "Taimoor", Password: "nope"}, Client{}); !errors.Is(err, ErrInvalidCredentials) {
		t.Errorf("bad password -> %v", err)
	}
	if _, _, err := s.Login(ctx, Credentials{Name: "nobody", Password: "secret12"}, Client{}); !errors.Is(err, ErrInvalidCredentials) {
		t.Errorf("unknown user -> %v", err)
	}
	got, grant, err := s.Login(ctx, Credentials{Name: "taimoor", Password: "secret12"}, Client{UserAgent: "test-agent"})
	if err != nil {
		t.Fatal(err)
	}
	token := grant.Token
	if got.ID != u.ID || len(token) != 64 || len(grant.SessionID) != 16 || grant.Kind != KindBrowser {
		t.Errorf("login = %+v grant %+v", got, grant)
	}
	if again, err := s.Authenticate(ctx, token); err != nil || again.ID != u.ID {
		t.Errorf("authenticate = %+v %v", again, err)
	}
	if _, err := s.Authenticate(ctx, "bogus"); !errors.Is(err, ErrUnauthenticated) {
		t.Errorf("bogus token -> %v", err)
	}
	// Expired session.
	_ = q.TouchSession(ctx, db.TouchSessionParams{LastSeenAt: db.FormatTime(time.Now()), ExpiresAt: db.FormatTime(time.Now().Add(-time.Minute)), TokenHash: hashToken(token)})
	if _, err := s.Authenticate(ctx, token); !errors.Is(err, ErrUnauthenticated) {
		t.Errorf("expired token -> %v", err)
	}
	token = login(t, s, "taimoor", "secret12")
	if err := s.Logout(ctx, token); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(ctx, token); !errors.Is(err, ErrUnauthenticated) {
		t.Error("token still valid after logout")
	}
	// Changing the password signs out every other session.
	token = login(t, s, "taimoor", "secret12")
	keep := login(t, s, "taimoor", "secret12")
	if err := s.ChangePassword(ctx, u.ID, "newpass9", keep); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(ctx, token); !errors.Is(err, ErrUnauthenticated) {
		t.Error("token survived a password change")
	}
	if _, err := s.Authenticate(ctx, keep); err != nil {
		t.Errorf("the session that changed the password was signed out: %v", err)
	}
	if _, _, err := s.Login(ctx, Credentials{Name: "taimoor", Password: "newpass9"}, Client{}); err != nil {
		t.Error("new password rejected")
	}
	if _, err := s.CheckPassword(ctx, "taimoor", "secret12"); !errors.Is(err, ErrInvalidCredentials) {
		t.Errorf("old password -> %v", err)
	}
}

func login(t *testing.T, s *Service, name, password string) string {
	t.Helper()
	_, g, err := s.Login(context.Background(), Credentials{Name: name, Password: password}, Client{})
	if err != nil {
		t.Fatal(err)
	}
	return g.Token
}

func TestMiddleware(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	_, _ = s.CreateUser(ctx, "a", "pass1234", true)
	token := login(t, s, "a", "pass1234")

	exempt := func(p string) bool { return p == "/api/health" || !strings.HasPrefix(p, "/api/") }
	media := func(p string) bool { return strings.HasSuffix(p, "/stream") }
	h := s.Middleware(exempt, media, nil, nil)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if u, ok := UserFrom(r.Context()); ok {
			_, _ = w.Write([]byte("user:" + u.Name))
			return
		}
		_, _ = w.Write([]byte("anon"))
	}))
	do := func(path string, mod func(*http.Request)) (int, string) {
		req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, path, nil)
		if mod != nil {
			mod(req)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code, rec.Body.String()
	}
	if code, body := do("/api/health", nil); code != 200 || body != "anon" {
		t.Errorf("exempt -> %d %q", code, body)
	}
	if code, body := do("/api/health", func(r *http.Request) { r.AddCookie(&http.Cookie{Name: CookieName, Value: token}) }); code != 200 || body != "user:a" {
		t.Errorf("exempt with cookie should still see the user -> %d %q", code, body)
	}
	if code, _ := do("/api/libraries", nil); code != 401 {
		t.Errorf("no token -> %d", code)
	}
	if code, body := do("/api/libraries", func(r *http.Request) { r.AddCookie(&http.Cookie{Name: CookieName, Value: token}) }); code != 200 || body != "user:a" {
		t.Errorf("cookie -> %d %q", code, body)
	}
	if code, body := do("/api/libraries", func(r *http.Request) { r.Header.Set("Authorization", "Bearer "+token) }); code != 200 || body != "user:a" {
		t.Errorf("bearer -> %d %q", code, body)
	}
	if code, _ := do("/api/libraries?access_token="+token, nil); code != 401 {
		t.Errorf("query token on a non-media path must be rejected -> %d", code)
	}
	if code, _ := do("/api/files/1/stream?access_token="+token, nil); code != 401 {
		t.Errorf("session token in a media URL must be rejected -> %d", code)
	}
	mt, _, err := s.IssueMediaToken(context.Background(), token)
	if err != nil {
		t.Fatal(err)
	}
	if code, body := do("/api/files/1/stream?access_token="+mt, nil); code != 200 || body != "user:a" {
		t.Errorf("media token on media path -> %d %q", code, body)
	}
	if code, _ := do("/api/libraries?access_token="+mt, nil); code != 401 {
		t.Errorf("media token on a non-media path must be rejected -> %d", code)
	}
	if code, body := do("/movies/1", nil); code != 200 || body != "anon" {
		t.Errorf("SPA path passes through -> %d %q", code, body)
	}
}

func TestDeviceSessions(t *testing.T) {
	s, _ := setup(t)
	s.SetDeviceTTL(48 * time.Hour)
	ctx := context.Background()
	a, _ := s.CreateUser(ctx, "a", "pass1234", true)
	_, _ = s.CreateUser(ctx, "b", "pass1234", false)

	browser := login(t, s, "a", "pass1234")
	_, tv, err := s.Login(ctx, Credentials{Name: "a", Password: "pass1234"}, Client{Kind: KindDevice, DeviceKey: "tv-1", Name: "Living room TV", Product: "WatchMyMedia for Android TV", Platform: "androidtv", Version: "1.0"})
	if err != nil {
		t.Fatal(err)
	}
	if tv.Kind != KindDevice || time.Until(tv.ExpiresAt) < 47*time.Hour {
		t.Errorf("device grant = %+v", tv)
	}
	other := login(t, s, "b", "pass1234")

	mine, err := s.Sessions(ctx, a.ID, browser)
	if err != nil {
		t.Fatal(err)
	}
	if len(mine) != 2 {
		t.Fatalf("a's sessions = %+v", mine)
	}
	var dev, cur *Session
	for i := range mine {
		if mine[i].Kind == KindDevice {
			dev = &mine[i]
		}
		if mine[i].Current {
			cur = &mine[i]
		}
	}
	if dev == nil || dev.Name != "Living room TV" || dev.Platform != "androidtv" || dev.DeviceID != "tv-1" || dev.ID != tv.SessionID || dev.UserName != "a" || dev.Current {
		t.Errorf("device session = %+v", dev)
	}
	if cur == nil || cur.Kind != KindBrowser {
		t.Errorf("current session = %+v", cur)
	}
	all, _ := s.Sessions(ctx, 0, "")
	if len(all) != 3 {
		t.Errorf("all sessions = %d", len(all))
	}
	if owner, err := s.SessionOwner(ctx, tv.SessionID); err != nil || owner != a.ID {
		t.Errorf("owner = %d %v", owner, err)
	}
	if _, err := s.SessionOwner(ctx, "0000000000000000"); !errors.Is(err, ErrNoSession) {
		t.Errorf("unknown session -> %v", err)
	}

	// Revoking the device signs it out, and its media tokens with it.
	mt, _, err := s.IssueMediaToken(ctx, tv.Token)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Revoke(ctx, tv.SessionID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Authenticate(ctx, tv.Token); !errors.Is(err, ErrUnauthenticated) {
		t.Errorf("revoked device still signed in: %v", err)
	}
	if _, err := s.AuthenticateMedia(ctx, mt); !errors.Is(err, ErrBadMediaToken) {
		t.Errorf("media token of a revoked device -> %v", err)
	}
	if _, err := s.Authenticate(ctx, other); err != nil {
		t.Errorf("another user's session was touched: %v", err)
	}
}

func TestCookie(t *testing.T) {
	s, _ := setup(t) // secure cookies not configured
	for _, c := range []struct {
		https bool
		want  string
	}{{false, ""}, {true, "Secure"}} {
		ctx := netguard.NewContext(context.Background(), netguard.Info{HTTPS: c.https})
		w := httptest.NewRecorder()
		s.SetCookie(ctx, w, "t")
		got := w.Header().Get("Set-Cookie")
		if !strings.Contains(got, "HttpOnly") || !strings.Contains(got, "SameSite=Lax") || strings.Contains(got, "Secure") != (c.want != "") {
			t.Errorf("https=%v: Set-Cookie %q", c.https, got)
		}
	}
}

func TestSessionRemembersAddress(t *testing.T) {
	s, _ := setup(t)
	ctx := context.Background()
	u, _ := s.CreateUser(ctx, "a", "pass1234", true)
	g := netguard.Default()
	req := httptest.NewRequestWithContext(t.Context(), http.MethodGet, "/api/libraries", nil)
	req.RemoteAddr = "192.168.1.20:5000"
	var token string
	g.Middleware(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		_, grant, err := s.Login(r.Context(), Credentials{Name: "a", Password: "pass1234"}, Client{})
		if err != nil {
			t.Fatal(err)
		}
		token = grant.Token
	})).ServeHTTP(httptest.NewRecorder(), req)
	list, _ := s.Sessions(ctx, u.ID, token)
	if len(list) != 1 || list[0].LastIP != "192.168.1.20" || !list[0].Current {
		t.Errorf("sessions = %+v", list)
	}
}

// A database that cannot answer is an error of its own, not a sign-out:
// event streams close on ErrUnauthenticated, and a paired TV that polls
// meanwhile keeps its approval for the next poll.
func TestDatabaseErrorIsNotSignOut(t *testing.T) {
	conn, q := dbtest.Open(t)
	s := New(q, time.Hour, false)
	ctx := t.Context()
	u, err := s.CreateUser(ctx, "a", "pass1234", false)
	if err != nil {
		t.Fatal(err)
	}
	approval, g := pairingApprover(t, s, u)
	code, secret, _, err := s.StartPairing(ctx, Client{Name: "TV"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.ApprovePairing(approval, code); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ValidateSession(ctx, g.Token); err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
	if _, err := s.ValidateSession(ctx, g.Token); err == nil || errors.Is(err, ErrUnauthenticated) {
		t.Errorf("validate with the database closed -> %v", err)
	}
	if _, err := s.ClaimPairing(ctx, secret); err == nil || errors.Is(err, ErrPairingNotFound) {
		t.Errorf("claim with the database closed -> %v", err)
	}
	if _, ok := s.pair.bySecret[hashToken(secret)]; !ok {
		t.Error("the approved pairing was dropped")
	}
}

// A browser in daily use stays signed in: when a request slides the
// session's expiry, the middleware sends the cookie again with a fresh
// Max-Age. Bearer-token clients get no cookie.
func TestCookieRenewedWithSession(t *testing.T) {
	s, q := setup(t) // sessions last an hour
	ctx := t.Context()
	if _, err := s.CreateUser(ctx, "a", "pass1234", false); err != nil {
		t.Fatal(err)
	}
	token := login(t, s, "a", "pass1234")
	h := s.Middleware(func(string) bool { return false }, func(string) bool { return false }, nil, nil)(
		http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	send := func(cookie bool) *http.Cookie {
		t.Helper()
		r := httptest.NewRequestWithContext(ctx, http.MethodGet, "/api/libraries", nil)
		if cookie {
			r.AddCookie(&http.Cookie{Name: CookieName, Value: token})
		} else {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("status %d", w.Code)
		}
		for _, c := range w.Result().Cookies() {
			if c.Name == CookieName {
				return c
			}
		}
		return nil
	}
	// Seen 90 minutes ago, with half an hour left.
	age := func() {
		t.Helper()
		now := time.Now()
		if err := q.TouchSession(ctx, db.TouchSessionParams{LastSeenAt: db.FormatTime(now.Add(-90 * time.Minute)),
			ExpiresAt: db.FormatTime(now.Add(30 * time.Minute)), TokenHash: hashToken(token)}); err != nil {
			t.Fatal(err)
		}
	}
	if c := send(true); c != nil {
		t.Errorf("cookie sent again on a request just after sign-in: %v", c)
	}
	age()
	c := send(true)
	if c == nil || c.Value != token || c.MaxAge != 3600 || !c.HttpOnly {
		t.Fatalf("renewed cookie = %v", c)
	}
	list, _ := s.Sessions(ctx, 0, "")
	if len(list) != 1 || time.Until(list[0].ExpiresAt) < 59*time.Minute {
		t.Errorf("session expiry not slid: %+v", list)
	}
	if c := send(true); c != nil {
		t.Errorf("cookie sent again within the hour: %v", c)
	}
	age()
	if c := send(false); c != nil {
		t.Errorf("bearer client got a cookie: %v", c)
	}
}

// A sign-in that checked the old password while the password changed
// either fails or is signed out with every other session; it never leaves
// a session the old password made.
func TestLoginRacingPasswordChange(t *testing.T) {
	s, _ := setup(t)
	ctx := t.Context()
	u, err := s.CreateUser(ctx, "a", "old-password", false)
	if err != nil {
		t.Fatal(err)
	}
	for i := range 10 {
		old, next := "old-password", "new-password"
		if i%2 == 1 {
			old, next = next, old
		}
		var g Grant
		var loginErr error
		done := make(chan struct{})
		go func() {
			defer close(done)
			_, g, loginErr = s.Login(ctx, Credentials{Name: "a", Password: old}, Client{})
		}()
		if err := s.ChangePassword(ctx, u.ID, next, ""); err != nil {
			t.Fatal(err)
		}
		<-done
		if loginErr == nil {
			if _, err := s.Authenticate(ctx, g.Token); err == nil {
				t.Fatalf("round %d: a session signed in with the old password survived the change", i)
			}
		} else if !errors.Is(loginErr, ErrInvalidCredentials) {
			if _, ok := errors.AsType[*ThrottledError](loginErr); !ok {
				t.Fatalf("round %d: login -> %v", i, loginErr)
			}
		}
	}
}

func TestClip(t *testing.T) {
	for _, c := range []struct {
		in   string
		n    int
		want string
	}{
		{"  Living room  ", 64, "Living room"},
		{"abcdef", 3, "abc"},
		{"Amélie", 3, "Am"}, // é is two bytes: not split
		{"Amélie", 4, "Amé"},
		{"日本語", 4, "日"},
		{"\xffabc", 3, "\xffab"}, // invalid bytes before the cut are kept
	} {
		if got := clip(c.in, c.n); got != c.want {
			t.Errorf("clip(%q, %d) = %q, want %q", c.in, c.n, got, c.want)
		}
	}
}
