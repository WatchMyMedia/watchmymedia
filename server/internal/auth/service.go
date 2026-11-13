package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/turnstile"
)

const (
	CookieName = "watchmymedia_session"
	// QueryParam lets media URLs carry a media token (never a session
	// token) for players that cannot set headers (TV apps, casting).
	QueryParam = "access_token"
)

// Session kinds. A browser holds its token in an HttpOnly cookie; a device
// (a native app) holds it itself and sends it as a bearer token.
const (
	KindBrowser = "browser"
	KindDevice  = "device"
	// DefaultDeviceTTL is how long a device stays signed in without being
	// used. Re-pairing a TV is a chore, and a device token never lands in a
	// browser, so it is much longer than a browser session.
	DefaultDeviceTTL = 365 * 24 * time.Hour
)

var (
	ErrInvalidCredentials = errors.New("invalid name or password")
	ErrUnauthenticated    = errors.New("not signed in")
	ErrSetupDone          = errors.New("setup already completed")
	ErrNoSession          = errors.New("session not found")
)

// InvalidError is a problem with what was typed (a name or password that
// breaks the rules, or a name in use), safe to show as it is.
type InvalidError struct{ Reason string }

func (e *InvalidError) Error() string { return e.Reason }

// Credentials are what a person types to sign in, plus the token of the
// bot check when sign-ins need one.
type Credentials struct {
	Name     string
	Password string
	// Challenge is the Cloudflare Turnstile token from the sign-in page.
	Challenge string
}

// Challenge is a bot check that password sign-ins and setup must pass
// before the password is tried. *turnstile.Verifier is the real one.
type Challenge interface {
	// Required reports whether sign-ins need a token now.
	Required() bool
	// Verify checks a token from the client at ip. A bad or missing token
	// is an error wrapping turnstile.ErrRejected; any other error means it
	// could not be checked.
	Verify(ctx context.Context, token, ip string) error
}

// Client describes what a new session belongs to.
type Client struct {
	Kind      string // KindBrowser (default) or KindDevice
	DeviceKey string // the client's own stable ID, for devices
	Name      string // shown in the signed-in devices list
	Product   string
	Platform  string
	Version   string
	Model     string // the device's model, such as Pixel 9
	UserAgent string
}

// Grant is a newly created session.
type Grant struct {
	Token     string
	SessionID string
	Kind      string
	ExpiresAt time.Time
}

// Session is a sign-in as the signed-in devices list shows it. The token
// and its hash are never part of it.
type Session struct {
	ID         string    `json:"id"`
	UserID     int64     `json:"userId"`
	UserName   string    `json:"userName"`
	Kind       string    `json:"kind" enum:"browser,device"`
	Name       string    `json:"name"`
	Product    string    `json:"product,omitempty"`
	Platform   string    `json:"platform,omitempty"`
	Version    string    `json:"version,omitempty"`
	Model      string    `json:"model,omitempty" example:"Pixel 9" doc:"The device's model, as the app reported it at sign-in."`
	DeviceID   string    `json:"deviceId,omitempty" doc:"The device's own ID, for device sessions."`
	UserAgent  string    `json:"userAgent,omitempty"`
	LastIP     string    `json:"lastIp,omitempty"`
	CreatedAt  time.Time `json:"createdAt"`
	LastSeenAt time.Time `json:"lastSeenAt" doc:"Updated at most once an hour."`
	ExpiresAt  time.Time `json:"expiresAt"`
	Current    bool      `json:"current" doc:"The session this request was made with."`
}

type User struct {
	// SignInID is a non-secret identity shared by session and media-token auth.
	// It is internal and never changes the public User contract.
	SignInID string `json:"-"`
	ID       int64  `json:"id"`
	Name     string `json:"name"`
	IsAdmin  bool   `json:"isAdmin"`
}

type Service struct {
	q         *db.Queries
	ttl       time.Duration
	deviceTTL time.Duration
	secure    bool
	// limit counts failures per name and client address, and wrong
	// pairing codes per approver; addrLimit per address and nameLimit per
	// name (see limiter.go).
	limit     *limiter
	addrLimit *limiter
	nameLimit *limiter
	pair      *pairings
	challenge Challenge
	// setupMu makes checking for and creating the first account one step.
	setupMu sync.Mutex
	// grantMu orders session/grant issuance against revocation and deletion.
	grantMu    sync.Mutex
	confirmed  map[string]confirmation
	passwords  passwordAdmission
	challenges challengeAdmission
	// mediaKey signs media tokens (see mediatoken.go).
	mediaKey []byte
}

// New creates the service. ttl is the sliding session lifetime; secure
// marks cookies Secure (set when served over HTTPS).
func New(q *db.Queries, ttl time.Duration, secure bool) *Service {
	if ttl <= 0 {
		ttl = 30 * 24 * time.Hour
	}
	s := &Service{q: q, ttl: ttl, deviceTTL: DefaultDeviceTTL, secure: secure, pair: newPairings(),
		limit: newLimiter(pairPolicy), addrLimit: newLimiter(addrPolicy), nameLimit: newLimiter(namePolicy)}
	_ = s.SetMediaKey(NewMediaKey())
	return s
}

// SetChallenge puts a bot check in front of password sign-ins and setup.
func (s *Service) SetChallenge(c Challenge) { s.challenge = c }

// SetDeviceTTL sets the sliding lifetime of device sessions.
func (s *Service) SetDeviceTTL(d time.Duration) {
	if d > 0 {
		s.deviceTTL = d
	}
}

func (s *Service) ttlFor(kind string) time.Duration {
	if kind == KindDevice {
		return s.deviceTTL
	}
	return s.ttl
}

// SetupRequired is true until the first (admin) account exists.
func (s *Service) SetupRequired(ctx context.Context) (bool, error) {
	n, err := s.q.CountUsers(ctx)
	return n == 0, err
}

func (s *Service) CreateUser(ctx context.Context, name, password string, admin bool) (User, error) {
	name = strings.TrimSpace(name)
	if len(name) < 1 || len(name) > 64 {
		return User{}, &InvalidError{"name must be 1-64 characters"}
	}
	release, err := s.admitPassword(ctx, "")
	if err != nil {
		return User{}, err
	}
	defer release()
	hash, err := HashPassword(password)
	if err != nil {
		return User{}, err
	}
	row, err := s.q.CreateUser(ctx, db.CreateUserParams{Name: name, PasswordHash: hash, IsAdmin: db.BoolInt(admin)})
	if err != nil {
		if db.IsUniqueViolation(err) {
			return User{}, &InvalidError{fmt.Sprintf("a user named %q already exists", name)}
		}
		return User{}, err
	}
	return userFromDB(row), nil
}

// ChangePassword sets a new password and signs out every other session of
// the user. keepToken is the session the change was made from, which stays
// signed in; empty signs out everything.
func (s *Service) ChangePassword(ctx context.Context, userID int64, password, keepToken string) error {
	release, err := s.admitPassword(ctx, "")
	if err != nil {
		return err
	}
	defer release()
	hash, err := HashPassword(password)
	if err != nil {
		return err
	}
	s.grantMu.Lock()
	defer s.grantMu.Unlock()
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		if err := q.UpdateUserPassword(ctx, db.UpdateUserPasswordParams{PasswordHash: hash, ID: userID}); err != nil {
			return err
		}
		if keepToken == "" {
			return q.DeleteUserSessions(ctx, userID)
		}
		return q.DeleteUserSessionsExcept(ctx, db.DeleteUserSessionsExceptParams{UserID: userID, TokenHash: hashToken(keepToken)})
	})
	if err == nil {
		s.pair.forgetUser(userID)
		s.forgetConfirmations(userID)
	}
	return err
}

// CheckPassword verifies a name and password without signing in. After
// repeated failures (see limiter.go) it returns a *ThrottledError without
// checking the password. Unknown names are throttled the same way, so the
// limit does not reveal which names exist.
func (s *Service) CheckPassword(ctx context.Context, name, password string) (User, error) {
	key, _, _ := throttleKeys(ctx, name)
	release, err := s.admitPassword(ctx, key)
	if err != nil {
		return User{}, err
	}
	defer release()
	if err := s.throttled(ctx, name); err != nil {
		return User{}, err
	}
	row, err := s.checkPassword(ctx, name, password)
	if err != nil {
		return User{}, err
	}
	return userFromDB(row), nil
}

// checkPassword returns the account whose password was checked, as read
// before checking it.
func (s *Service) checkPassword(ctx context.Context, name, password string) (db.User, error) {
	pair, addr, nm := throttleKeys(ctx, name)
	row, err := s.q.GetUserByName(ctx, strings.TrimSpace(name))
	if err == nil && VerifyPassword(row.PasswordHash, password) {
		if pair != "" {
			s.limit.succeed(pair)
		}
		return row, nil
	}
	if err != nil {
		// Burn the same time as a real check so names cannot be enumerated.
		VerifyPassword(dummyHash(), password)
	}
	if pair != "" {
		s.limit.fail(pair)
	}
	if addr != "" {
		s.addrLimit.fail(addr)
	}
	s.nameLimit.fail(nm)
	return db.User{}, ErrInvalidCredentials
}

// throttleKeys are the keys a sign-in counts under: the name with the
// client's address, the address, and the name. pair and addr are "" when
// the client is unknown (see limitAddr); then only the name's limit counts.
func throttleKeys(ctx context.Context, name string) (pair, addr, nm string) {
	nm = limitKey(name)
	addr, ok := limitAddr(ctx)
	if ok {
		pair = nm + "|" + addr
	}
	return pair, addr, nm
}

// limitAddr is the key the client counts under in limits per address, and
// whether there is one.
//
// Behind a proxy missing from trusted proxies, netguard's IP is the proxy's
// own address, the same for everyone the proxy serves, so failures counted
// there would let one visitor lock every other one out. The client the
// proxy's headers name (netguard's Forwarded) counts instead, scoped to
// that proxy: "203.0.113.9 via 127.0.0.1". The headers are not verified,
// so a visitor can put a new address in each request and spread its
// failures over many keys; the name's own limit, which no address escapes,
// still bounds guessing at each account. What a forged header cannot do is
// count against someone else: the scope keeps it apart from that address
// coming directly, and through a proxy that appends its client, as proxies
// do, the last entry is the visitor's own, whatever it sent. When no header
// names an address, ok is false and no per-address limit applies.
func limitAddr(ctx context.Context) (key string, ok bool) {
	info, _ := netguard.From(ctx)
	if !info.Proxied {
		return addrKey(info.IP), true
	}
	if f := addrKey(info.Forwarded); f != "" {
		return f + " via " + info.IP.String(), true
	}
	return "", false
}

// throttled returns a *ThrottledError while any limit on this name or
// client is in force.
func (s *Service) throttled(ctx context.Context, name string) error {
	pair, addr, nm := throttleKeys(ctx, name)
	d := s.nameLimit.wait(nm)
	if pair != "" {
		d = max(d, s.limit.wait(pair))
	}
	if addr != "" {
		d = max(d, s.addrLimit.wait(addr))
	}
	if d > 0 {
		return &ThrottledError{RetryAfter: d}
	}
	return nil
}

// passChallenge runs the bot check, when one is required. A failed check
// counts against the client's address, so a bot that cannot pass it soon
// stops reaching Cloudflare at all.
func (s *Service) passChallenge(ctx context.Context, token string) error {
	if s.challenge == nil || !s.challenge.Required() {
		return nil
	}
	release, err := s.admitChallenge()
	if err != nil {
		return err
	}
	defer release()
	// Behind an untrusted proxy clientIP is "", so Cloudflare is not told
	// the proxy's address as the visitor's.
	err = s.challenge.Verify(ctx, token, clientIP(ctx))
	if errors.Is(err, turnstile.ErrRejected) {
		if addr, _ := limitAddr(ctx); addr != "" {
			s.addrLimit.fail(addr)
		}
	}
	return err
}

// Login checks the credentials and creates a session for the client. The
// throttle is checked first, then the bot check, then the password.
func (s *Service) Login(ctx context.Context, cr Credentials, c Client) (User, Grant, error) {
	if err := s.throttled(ctx, cr.Name); err != nil {
		return User{}, Grant{}, err
	}
	// The bot check runs before a password slot is taken: it waits on
	// Cloudflare (up to 8 s), and sign-ins holding both slots through it
	// with junk tokens would turn away every password check on the server.
	if err := s.passChallenge(ctx, cr.Challenge); err != nil {
		return User{}, Grant{}, err
	}
	key, _, _ := throttleKeys(ctx, cr.Name)
	release, err := s.admitPassword(ctx, key)
	if err != nil {
		return User{}, Grant{}, err
	}
	defer release()
	if err := s.throttled(ctx, cr.Name); err != nil {
		return User{}, Grant{}, err
	}
	// The password is checked outside grantMu: Argon2 takes a while, and
	// sign-outs, revocations and TVs claiming their pairing wait on grantMu.
	checked, err := s.checkPassword(ctx, cr.Name, cr.Password)
	if err != nil {
		return User{}, Grant{}, err
	}
	// A password change or the account's deletion signs every session out
	// under grantMu. If one came first, the password just checked no longer
	// signs in; if it comes after, it signs this session out with the rest.
	s.grantMu.Lock()
	defer s.grantMu.Unlock()
	row, err := s.q.GetUser(ctx, checked.ID)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && row.PasswordHash != checked.PasswordHash) {
		return User{}, Grant{}, ErrInvalidCredentials
	}
	if err != nil {
		return User{}, Grant{}, err
	}
	g, err := s.CreateSession(ctx, row.ID, c)
	if err != nil {
		return User{}, Grant{}, err
	}
	return userFromDB(row), g, nil
}

// Setup creates the first account, an admin, and signs it in. Once any
// account exists it returns ErrSetupDone; of two setups at once, one wins.
func (s *Service) Setup(ctx context.Context, cr Credentials, c Client) (User, Grant, error) {
	if err := s.passChallenge(ctx, cr.Challenge); err != nil {
		return User{}, Grant{}, err
	}
	s.setupMu.Lock()
	defer s.setupMu.Unlock()
	need, err := s.SetupRequired(ctx)
	if err != nil {
		return User{}, Grant{}, err
	}
	if !need {
		return User{}, Grant{}, ErrSetupDone
	}
	u, err := s.CreateUser(ctx, cr.Name, cr.Password, true)
	if err != nil {
		return User{}, Grant{}, err
	}
	g, err := s.CreateSession(ctx, u.ID, c)
	if err != nil {
		return User{}, Grant{}, err
	}
	return u, g, nil
}

// CreateSession signs the user in on a client without a password, for
// callers that have already established who it is (device pairing).
func (s *Service) CreateSession(ctx context.Context, userID int64, c Client) (Grant, error) {
	if c.Kind != KindDevice {
		c.Kind = KindBrowser
	}
	token, err := newToken()
	if err != nil {
		return Grant{}, err
	}
	id, err := newID()
	if err != nil {
		return Grant{}, err
	}
	exp := time.Now().Add(s.ttlFor(c.Kind))
	if err := s.q.CreateSession(ctx, db.CreateSessionParams{
		TokenHash: hashToken(token), PublicID: id, UserID: userID, UserAgent: clip(c.UserAgent, 200),
		ExpiresAt: db.FormatTime(exp), Kind: c.Kind, DeviceKey: clip(c.DeviceKey, 64), Name: clip(c.Name, 64),
		Product: clip(c.Product, 64), Platform: clip(c.Platform, 32), Version: clip(c.Version, 32), LastIp: clientIP(ctx),
		Model: clip(c.Model, 64),
	}); err != nil {
		return Grant{}, err
	}
	return Grant{Token: token, SessionID: id, Kind: c.Kind, ExpiresAt: exp}, nil
}

func (s *Service) Logout(ctx context.Context, token string) error {
	s.grantMu.Lock()
	defer s.grantMu.Unlock()
	u, err := s.ValidateSession(ctx, token)
	if err != nil {
		return err
	}
	if err := s.q.DeleteSession(ctx, hashToken(token)); err != nil {
		return err
	}
	delete(s.confirmed, u.SignInID)
	return nil
}

// DeleteUser revokes pending grants together with the durable account.
func (s *Service) DeleteUser(ctx context.Context, id int64) error {
	s.grantMu.Lock()
	defer s.grantMu.Unlock()
	if err := s.q.DeleteUser(ctx, id); err != nil {
		return err
	}
	s.pair.forgetUser(id)
	s.forgetConfirmations(id)
	return nil
}

// Sessions lists live sessions: the user's, or everyone's when userID is 0.
// current is the token of the session asking, which is marked as such.
func (s *Service) Sessions(ctx context.Context, userID int64, current string) ([]Session, error) {
	now := db.FormatTime(time.Now())
	var rows []db.ListSessionsRow
	if userID == 0 {
		var err error
		if rows, err = s.q.ListSessions(ctx, now); err != nil {
			return nil, err
		}
	} else {
		ur, err := s.q.ListUserSessions(ctx, db.ListUserSessionsParams{UserID: userID, ExpiresAt: now})
		if err != nil {
			return nil, err
		}
		for _, r := range ur {
			rows = append(rows, db.ListSessionsRow(r))
		}
	}
	cur := ""
	if current != "" {
		cur = hashToken(current)
	}
	out := make([]Session, 0, len(rows))
	for _, r := range rows {
		out = append(out, Session{
			ID: r.PublicID, UserID: r.UserID, UserName: r.UserName, Kind: r.Kind, Name: r.Name, Product: r.Product,
			Platform: r.Platform, Version: r.Version, Model: r.Model, DeviceID: r.DeviceKey, UserAgent: r.UserAgent, LastIP: r.LastIp,
			CreatedAt: db.ParseTime(r.CreatedAt), LastSeenAt: db.ParseTime(r.LastSeenAt), ExpiresAt: db.ParseTime(r.ExpiresAt),
			Current: cur != "" && r.TokenHash == cur,
		})
	}
	return out, nil
}

// SessionOwner returns the user a session belongs to.
func (s *Service) SessionOwner(ctx context.Context, id string) (int64, error) {
	uid, err := s.q.GetSessionOwner(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, ErrNoSession
	}
	return uid, err
}

// Revoke signs a session out by its public ID. Media tokens made from it
// stop working with it.
func (s *Service) Revoke(ctx context.Context, id string) error {
	s.grantMu.Lock()
	defer s.grantMu.Unlock()
	if err := s.q.DeleteSessionByPublicID(ctx, id); err != nil {
		return err
	}
	delete(s.confirmed, id)
	return nil
}

// ValidateSession checks revocation and expiry without extending the lifetime.
// Long-lived event streams use this before sending data and on keep-alives.
func (s *Service) ValidateSession(ctx context.Context, token string) (User, error) {
	if token == "" {
		return User{}, ErrUnauthenticated
	}
	return s.validateSessionHash(ctx, hashToken(token))
}

// validateSessionHash is ValidateSession for the hash of a token. A session
// that signed out or expired is ErrUnauthenticated; a database error is
// returned as it is, so a moment's SQLITE_BUSY does not sign anyone out.
func (s *Service) validateSessionHash(ctx context.Context, hash string) (User, error) {
	return s.sessionUser(ctx, hash, ErrUnauthenticated)
}

// Authenticate resolves a token to its user and slides the expiry forward
// at most once an hour.
func (s *Service) Authenticate(ctx context.Context, token string) (User, error) {
	u, _, err := s.authenticate(ctx, token)
	return u, err
}

// authenticate is Authenticate, also reporting whether it slid the
// session's expiry forward.
func (s *Service) authenticate(ctx context.Context, token string) (User, bool, error) {
	if token == "" {
		return User{}, false, ErrUnauthenticated
	}
	now := time.Now()
	row, err := s.q.GetSession(ctx, db.GetSessionParams{TokenHash: hashToken(token), ExpiresAt: db.FormatTime(now)})
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return User{}, false, ErrUnauthenticated
		}
		return User{}, false, err
	}
	ip := clientIP(ctx)
	// Slide the expiry at most once an hour, and note a new address (but not
	// on every request of a client that alternates between two).
	since := now.Sub(db.ParseTime(row.LastSeenAt))
	slid := false
	if since > time.Hour || (ip != "" && ip != row.LastIp && since > time.Minute) {
		slid = s.q.TouchSession(ctx, db.TouchSessionParams{LastSeenAt: db.FormatTime(now), ExpiresAt: db.FormatTime(now.Add(s.ttlFor(row.Kind))), LastIp: ip, TokenHash: row.TokenHash}) == nil
	}
	return sessionOwner(row), slid, nil
}

// Reap deletes expired sessions.
func (s *Service) Reap(ctx context.Context) error {
	return s.q.DeleteExpiredSessions(ctx, db.FormatTime(time.Now()))
}

// SetCookie writes the session cookie. It is Secure when secure cookies are
// configured or the request came over HTTPS (directly or through a trusted
// proxy), so a cookie set over HTTPS is never sent back over plain HTTP.
func (s *Service) SetCookie(ctx context.Context, w http.ResponseWriter, token string) {
	//nolint:gosec // G124: Secure follows auth.secure_cookies or HTTPS; a server on the LAN over plain HTTP must still keep people signed in.
	http.SetCookie(w, &http.Cookie{
		Name: CookieName, Value: token, Path: "/", HttpOnly: true, Secure: s.secureFor(ctx),
		SameSite: http.SameSiteLaxMode, MaxAge: int(s.ttl.Seconds()),
	})
}

func (s *Service) ClearCookie(ctx context.Context, w http.ResponseWriter) {
	//nolint:gosec // G124: Secure follows auth.secure_cookies or HTTPS; a server on the LAN over plain HTTP must still keep people signed in.
	http.SetCookie(w, &http.Cookie{Name: CookieName, Value: "", Path: "/", HttpOnly: true, Secure: s.secureFor(ctx), SameSite: http.SameSiteLaxMode, MaxAge: -1})
}

func (s *Service) secureFor(ctx context.Context) bool {
	info, _ := netguard.From(ctx)
	return s.secure || info.HTTPS
}

// TokenFrom extracts the session token from the cookie or an
// Authorization bearer header.
func TokenFrom(r *http.Request) string {
	if t := cookieToken(r); t != "" {
		return t
	}
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		return strings.TrimSpace(h[len("Bearer "):])
	}
	return ""
}

// cookieToken is the session token in the request's cookie, if any.
func cookieToken(r *http.Request) string {
	if c, err := r.Cookie(CookieName); err == nil {
		return c.Value
	}
	return ""
}

// MediaTokenFrom returns the access_token query parameter.
func MediaTokenFrom(r *http.Request) string { return r.URL.Query().Get(QueryParam) }

func userFromDB(u db.User) User { return User{ID: u.ID, Name: u.Name, IsAdmin: u.IsAdmin == 1} }

// sessionOwner is the user a session row belongs to.
func sessionOwner(r db.GetSessionRow) User {
	return User{ID: r.UserID, Name: r.Name, IsAdmin: r.IsAdmin == 1, SignInID: r.PublicID}
}

func newToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// newID makes a session's public ID: 16 hex characters.
func newID() (string, error) {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// clientIP is the request's client address, from the netguard middleware,
// as sessions (lastIp), pairings and the bot check record it. It is ""
// when the client is unknown: behind a proxy missing from trusted proxies,
// the address netguard has is the proxy's, and like history, sessions show
// no address rather than the proxy's.
func clientIP(ctx context.Context) string {
	info, _ := netguard.From(ctx)
	if info.Proxied || !info.IP.IsValid() {
		return ""
	}
	return info.IP.String()
}

// clip trims s and cuts it to at most n bytes without splitting a
// character (discovery's instanceName cuts names the same way).
func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) <= n {
		return s
	}
	for n > 0 && !utf8.RuneStart(s[n]) {
		n--
	}
	return s[:n]
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// ValidateSignIn rechecks an original sign-in without retaining its secret
// token or sliding its expiry. A second sign-in cannot revive its streams.
func (s *Service) ValidateSignIn(ctx context.Context, id string) (User, error) {
	row, err := s.q.GetActiveSessionByID(ctx, db.GetActiveSessionByIDParams{PublicID: id, ExpiresAt: db.FormatTime(time.Now())})
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrUnauthenticated
	}
	if err != nil {
		return User{}, err
	}
	return User{ID: row.UserID, Name: row.Name, IsAdmin: row.IsAdmin != 0, SignInID: row.PublicID}, nil
}
