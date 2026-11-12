package api

import (
	"context"
	"errors"
	"log/slog"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv/guide/art"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/turnstile"
)

type AuthStatus struct {
	SetupRequired  bool       `json:"setupRequired" doc:"True until the first admin account is created."`
	User           *auth.User `json:"user,omitempty" doc:"The signed-in user, if any."`
	AdminAllowed   bool       `json:"adminAllowed" doc:"Whether this signed-in admin may administer here; for others, whether first-run setup works on this network."`
	AdminLimit     string     `json:"adminLimit,omitempty" required:"false" enum:"network,https"`
	OnAdminNetwork *bool      `json:"onAdminNetwork,omitempty" required:"false"`
	// TurnstileSiteKey is set while sign-in needs Cloudflare Turnstile.
	TurnstileSiteKey string `json:"turnstileSiteKey,omitempty" doc:"Set while password sign-in and setup need a Cloudflare Turnstile token: render the widget with this site key and send its token as turnstileToken."`
}

type AuthStatusOutput struct{ Body AuthStatus }

type CredentialsInput struct {
	UserAgent string `header:"User-Agent" required:"false"`
	Body      struct {
		Name string `json:"name" minLength:"1" maxLength:"64"`
		// Older passwords may be shorter than new ones must be.
		Password       string `json:"password" minLength:"4" maxLength:"256"`
		TurnstileToken string `json:"turnstileToken,omitempty" maxLength:"2048" doc:"The Cloudflare Turnstile widget's token, required while /auth/status reports a turnstileSiteKey. Single use: get a new one for every attempt."`
		// Client is sent by native apps. It signs in as a device: the session
		// token comes back in the body (for the Authorization: Bearer header)
		// instead of a cookie, and the session lasts much longer.
		Client *playback.Client `json:"client,omitempty" doc:"Native apps: sign in as this device and receive a bearer token instead of a cookie."`
	}
}

type SetupInput struct {
	UserAgent string `header:"User-Agent" required:"false"`
	Body      struct {
		Name           string           `json:"name" minLength:"1" maxLength:"64"`
		Password       string           `json:"password" minLength:"8" maxLength:"256"`
		TurnstileToken string           `json:"turnstileToken,omitempty" maxLength:"2048" doc:"The Cloudflare Turnstile widget's token, required while /auth/status reports a turnstileSiteKey."`
		Client         *playback.Client `json:"client,omitempty" doc:"Native apps: sign in as this device and receive a bearer token instead of a cookie."`
	}
}

// SignIn is the signed-in user plus, for devices, the session token.
type SignIn struct {
	auth.User
	SessionID string     `json:"sessionId" doc:"Names this sign-in in the signed-in devices list."`
	Token     string     `json:"token,omitempty" doc:"Device sign-ins only: send as Authorization: Bearer on every request. Browsers get an HttpOnly cookie instead."`
	ExpiresAt *time.Time `json:"expiresAt,omitempty" doc:"Device sign-ins only: when the token lapses if unused; every use slides it forward."`
}

type UserOutput struct {
	SetCookie string `header:"Set-Cookie"`
	Body      SignIn
}

type LogoutOutput struct {
	SetCookie string `header:"Set-Cookie"`
}

type SessionsInput struct {
	All bool `query:"all" required:"false" doc:"Admins: everyone's sessions instead of your own."`
}

type SessionList struct {
	Items []auth.Session `json:"items" nullable:"false"`
}

type SessionListOutput struct{ Body SessionList }

type SessionIDPathInput struct {
	ID string `path:"id" pattern:"^[0-9a-f]{16}$"`
}

type ConfirmPasswordInput struct {
	Body struct {
		Password string `json:"password" maxLength:"256"`
	}
}

type ChangePasswordInput struct {
	Body struct {
		Current string `json:"current"`
		New     string `json:"new" minLength:"8" maxLength:"256"`
	}
}

type ResetUserPasswordInput struct {
	ID   int64 `path:"id"`
	Body struct {
		Password string `json:"password" minLength:"8" maxLength:"256" doc:"The new password; the account's existing password is not needed."`
	}
}

type UserList struct {
	Items []Account `json:"items" nullable:"false"`
}

// Account is a user as the admin's user list shows it.
type Account struct {
	auth.User
	RecordHistory bool  `json:"recordHistory" doc:"Plays are kept in history; off, they show only in Now playing."`
	SeerrUserID   int64 `json:"seerrUserId,omitempty" required:"false" doc:"The Seerr user this person requests as; absent when not linked."`
}

type AccountOutput struct{ Body Account }

type UpdateUserInput struct {
	ID   int64 `path:"id"`
	Body struct {
		RecordHistory *bool  `json:"recordHistory,omitempty"`
		SeerrUserID   *int64 `json:"seerrUserId,omitempty" required:"false" minimum:"0" doc:"Link this person to a Seerr user (from listSeerrUsers) to request through Seerr; 0 unlinks."`
	}
}

func accountFromDB(r db.User) Account {
	a := Account{ID: r.ID, Name: r.Name, IsAdmin: r.IsAdmin == 1, RecordHistory: r.RecordHistory == 1}
	if r.SeerrUserID != nil {
		a.SeerrUserID = *r.SeerrUserID
	}
	return a
}

type UserListOutput struct{ Body UserList }

type CreateUserInput struct {
	Body struct {
		Name     string `json:"name" minLength:"1" maxLength:"64"`
		Password string `json:"password" minLength:"8" maxLength:"256"`
		IsAdmin  bool   `json:"isAdmin" required:"false"`
	}
}

type CreateUserOutput struct {
	Status int
	Body   auth.User
}

type MediaToken struct {
	Token     string    `json:"token" doc:"Append as ?access_token= to media URLs (streams, HLS playlists, subtitles, images)."`
	ExpiresAt time.Time `json:"expiresAt" doc:"Request a new token (and new playback URLs) after this."`
	Param     string    `json:"param" doc:"The query parameter name, access_token."`
}

type MediaTokenOutput struct{ Body MediaToken }

type UserIDInput struct {
	ID int64 `path:"id"`
}

// currentUser returns the user the middleware authenticated.
func currentUser(ctx context.Context) (auth.User, error) {
	u, ok := auth.UserFrom(ctx)
	if !ok {
		return auth.User{}, huma.Error401Unauthorized("sign in required")
	}
	return u, nil
}

func requireAdmin(ctx context.Context) (auth.User, error) {
	u, err := currentUser(ctx)
	if err != nil {
		return u, err
	}
	if !u.IsAdmin {
		return u, huma.Error403Forbidden("admin only")
	}
	if !netguard.AdminAllowed(ctx) {
		i, _ := netguard.From(ctx)
		if !i.RemoteAdmin {
			return u, errAdminNetwork
		}
		return u, errAdminHTTPS
	}
	return u, nil
}

var (
	errAdminNetwork = huma.Error403Forbidden("admin settings are off away from your home network or tailnet; an admin there can turn them on in Settings › Security")
	errAdminHTTPS   = huma.Error403Forbidden("admin settings away from your home network or tailnet need HTTPS")
)

// effectiveAdmin reports whether u acts as an admin on this request: an
// admin account with access on this request, as requireAdmin checks.
func effectiveAdmin(ctx context.Context, u auth.User) bool {
	return u.IsAdmin && netguard.AdminAllowed(ctx)
}

// adminOp limits an operation to admins with access on this request
// (requireAdmin), checked before its input is read.
func adminOp(api huma.API, op huma.Operation) huma.Operation {
	op.Middlewares = append(op.Middlewares, func(ctx huma.Context, next func(huma.Context)) {
		if _, err := requireAdmin(ctx.Context()); err != nil {
			status := http.StatusForbidden
			if se, ok := errors.AsType[huma.StatusError](err); ok {
				status = se.GetStatus()
			}
			_ = huma.WriteErr(api, ctx, status, err.Error())
			return
		}
		next(ctx)
	})
	return op
}

// throttled turns a login throttle into a 429 with Retry-After and passes
// other errors through.
func throttled(err error) error {
	var te *auth.ThrottledError
	if !errors.As(err, &te) {
		return err
	}
	secs := strconv.Itoa(int(math.Ceil(te.RetryAfter.Seconds())))
	return huma.ErrorWithHeaders(huma.Error429TooManyRequests(te.Error()), http.Header{"Retry-After": {secs}})
}

// signInError turns what can go wrong signing in into a status. Its
// messages are the same for unknown names and wrong passwords.
func signInError(ctx context.Context, deps Deps, err error) error {
	var ie *auth.InvalidError
	switch {
	case errors.Is(err, auth.ErrInvalidCredentials):
		return huma.Error401Unauthorized("invalid name or password")
	case errors.Is(err, turnstile.ErrRejected):
		return huma.Error403Forbidden(turnstile.ErrRejected.Error())
	case errors.Is(err, turnstile.ErrUnavailable):
		// Fails closed: without Cloudflare's answer the check cannot pass.
		deps.Log.WarnContext(ctx, "turnstile check failed", "err", err)
		return huma.Error503ServiceUnavailable(turnstile.ErrUnavailable.Error())
	case errors.As(err, &ie):
		return huma.Error422UnprocessableEntity(ie.Reason)
	}
	return throttled(err)
}

// inputError shows an *auth.InvalidError as a 422 and passes other errors
// through (unexpected ones become a bare 500).
func inputError(err error) error {
	if ie, ok := errors.AsType[*auth.InvalidError](err); ok {
		return huma.Error422UnprocessableEntity(ie.Reason)
	}
	return throttled(err)
}

// cookieHeader renders the Set-Cookie value huma puts on the response.
func cookieHeader(ctx context.Context, deps Deps, token string, clear bool) string {
	rec := httptestRecorder{}
	if clear {
		deps.Auth.ClearCookie(ctx, &rec)
	} else {
		deps.Auth.SetCookie(ctx, &rec, token)
	}
	return rec.header.Get("Set-Cookie")
}

type httptestRecorder struct{ header http.Header }

func (r *httptestRecorder) Header() http.Header {
	if r.header == nil {
		r.header = http.Header{}
	}
	return r.header
}
func (r *httptestRecorder) Write([]byte) (int, error) { return 0, nil }
func (r *httptestRecorder) WriteHeader(int)           {}

func registerAuth(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "getAuthStatus",
		Method:      http.MethodGet,
		Path:        Prefix + "/auth/status",
		Summary:     "Whether setup is needed and who is signed in",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, _ *struct{}) (*AuthStatusOutput, error) {
		out := &AuthStatusOutput{}
		out.Body.AdminAllowed = netguard.Trusted(ctx)
		if deps.Auth == nil {
			return out, nil
		}
		need, err := deps.Auth.SetupRequired(ctx)
		if err != nil {
			return nil, err
		}
		out.Body.SetupRequired = need
		if u, ok := auth.UserFrom(ctx); ok {
			out.Body.User = &u
			if u.IsAdmin {
				trusted := netguard.Trusted(ctx)
				out.Body.OnAdminNetwork = &trusted
				out.Body.AdminAllowed = netguard.AdminAllowed(ctx)
				if !out.Body.AdminAllowed {
					out.Body.AdminLimit = "https"
					if !deps.Net.RemoteAdmin() {
						out.Body.AdminLimit = "network"
					}
				}
			}
		}
		out.Body.TurnstileSiteKey = deps.Turnstile.SiteKey()
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "setup",
		Method:        http.MethodPost,
		Path:          Prefix + "/setup",
		Summary:       "Create the first admin account and sign in",
		Description:   "Only while no account exists, and only from an admin network. Needs turnstileToken while Turnstile is on.",
		Tags:          []string{"auth"},
		DefaultStatus: http.StatusCreated,
	}, func(ctx context.Context, in *SetupInput) (*UserOutput, error) {
		if deps.Auth == nil {
			return nil, huma.Error503ServiceUnavailable("auth not configured")
		}
		need, err := deps.Auth.SetupRequired(ctx)
		if err != nil {
			return nil, err
		}
		if !need {
			return nil, huma.Error409Conflict("setup already completed")
		}
		// Whoever finishes setup becomes the admin, so a freshly exposed
		// server must not let the internet claim it.
		if !netguard.Trusted(ctx) {
			return nil, huma.Error403Forbidden("finish setup from your home network or tailnet")
		}
		cr := auth.Credentials{Name: in.Body.Name, Password: in.Body.Password, Challenge: in.Body.TurnstileToken}
		user, grant, err := deps.Auth.Setup(ctx, cr, sessionClient(in.Body.Client, in.UserAgent))
		if errors.Is(err, auth.ErrSetupDone) {
			return nil, huma.Error409Conflict("setup already completed")
		}
		if err != nil {
			return nil, signInError(ctx, deps, err)
		}
		return signInOutput(ctx, deps, user, grant), nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "login",
		Method:      http.MethodPost,
		Path:        Prefix + "/auth/login",
		Summary:     "Sign in",
		Description: "401 for a wrong name or password alike; 429 with Retry-After after repeated failures for a name or from an address. " +
			"While Turnstile is on (/auth/status has a turnstileSiteKey) every password sign-in needs turnstileToken: 403 when it does not pass, 503 when Cloudflare cannot be reached. " +
			"Native apps cannot show the widget, so they sign in by pairing then (/auth/pair, opening linkPath in a browser on a phone).",
		Tags: []string{"auth"},
	}, func(ctx context.Context, in *CredentialsInput) (*UserOutput, error) {
		if deps.Auth == nil {
			return nil, huma.Error503ServiceUnavailable("auth not configured")
		}
		cr := auth.Credentials{Name: in.Body.Name, Password: in.Body.Password, Challenge: in.Body.TurnstileToken}
		user, grant, err := deps.Auth.Login(ctx, cr, sessionClient(in.Body.Client, in.UserAgent))
		if err != nil {
			return nil, signInError(ctx, deps, err)
		}
		return signInOutput(ctx, deps, user, grant), nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "confirmPassword", Method: http.MethodPost, Path: Prefix + "/auth/confirm",
		Summary: "Confirm your password for sensitive admin actions for ten minutes", Tags: []string{"auth"}, DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *ConfirmPasswordInput) (*struct{}, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if deps.Auth == nil {
			return nil, huma.Error503ServiceUnavailable("auth not configured")
		}
		err = deps.Auth.ConfirmPassword(ctx, u, in.Body.Password)
		if !netguard.Trusted(ctx) {
			msg := "password confirmed away from home"
			if err != nil {
				msg = "password confirmation failed away from home"
				if _, ok := errors.AsType[*auth.ThrottledError](err); ok {
					msg = "password confirmation throttled away from home"
				}
			}
			setAdminAudit(ctx, u, "confirmPassword", nil, msg, slog.LevelInfo)
		}
		if errors.Is(err, auth.ErrInvalidCredentials) {
			return nil, huma.Error401Unauthorized("password is wrong")
		}
		if errors.Is(err, auth.ErrUnauthenticated) {
			return nil, huma.Error401Unauthorized("sign in required")
		}
		return nil, throttled(err)
	})

	huma.Register(h, huma.Operation{
		OperationID:   "logout",
		Method:        http.MethodPost,
		Path:          Prefix + "/auth/logout",
		Summary:       "Sign out",
		Description:   "Ends the session the request was made with, whether it came as the cookie or as a bearer token.",
		Tags:          []string{"auth"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, _ *struct{}) (*LogoutOutput, error) {
		if token, ok := auth.SessionTokenFrom(ctx); ok && deps.Auth != nil {
			_ = deps.Auth.Logout(ctx, token)
			if u, ok := auth.UserFrom(ctx); ok {
				publishSessionsChanged(deps, u.ID)
			}
		}
		return &LogoutOutput{SetCookie: cookieHeader(ctx, deps, "", true)}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "listSessions",
		Method:      http.MethodGet,
		Path:        Prefix + "/auth/sessions",
		Summary:     "Signed-in browsers and devices",
		Description: "Your sessions, newest activity first; admins may ask for everyone's with all=true.",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, in *SessionsInput) (*SessionListOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		userID := u.ID
		if in.All {
			if _, err := requireAdmin(ctx); err != nil {
				return nil, err
			}
			userID = 0
		}
		current, _ := auth.SessionTokenFrom(ctx)
		items, err := deps.Auth.Sessions(ctx, userID, current)
		if err != nil {
			return nil, err
		}
		return &SessionListOutput{Body: SessionList{Items: items}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "revokeSession",
		Method:        http.MethodDelete,
		Path:          Prefix + "/auth/sessions/{id}",
		Summary:       "Sign out a browser or device",
		Description:   "Your own sessions, or anyone's for admins. Media tokens made from the session stop working too.",
		Tags:          []string{"auth"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *SessionIDPathInput) (*struct{}, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		owner, err := deps.Auth.SessionOwner(ctx, in.ID)
		if errors.Is(err, auth.ErrNoSession) {
			return nil, huma.Error404NotFound("session not found")
		}
		if err != nil {
			return nil, err
		}
		if owner != u.ID {
			if !u.IsAdmin {
				return nil, huma.Error404NotFound("session not found")
			}
			if _, err := requireAdmin(ctx); err != nil {
				return nil, err
			}
		}
		if err := deps.Auth.Revoke(ctx, in.ID); err != nil {
			return nil, err
		}
		publishSessionsChanged(deps, owner)
		return nil, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "createMediaToken",
		Method:      http.MethodPost,
		Path:        Prefix + "/auth/media-token",
		Summary:     "Get a short-lived token for media URLs",
		Description: "For players that cannot send a cookie or Authorization header. The token works only on media routes, expires after a few hours, and stops working when this session signs out. Session tokens are never accepted in URLs. HLS playlists fetched with a token pass it on to their segments.",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, _ *struct{}) (*MediaTokenOutput, error) {
		if _, err := currentUser(ctx); err != nil {
			return nil, err
		}
		session, ok := auth.SessionTokenFrom(ctx)
		if !ok {
			return nil, huma.Error401Unauthorized("sign in with a session to get a media token")
		}
		tok, exp, err := deps.Auth.IssueMediaToken(ctx, session)
		if err != nil {
			return nil, huma.Error401Unauthorized("sign in required")
		}
		return &MediaTokenOutput{Body: MediaToken{Token: tok, ExpiresAt: exp.UTC(), Param: auth.QueryParam}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getMe",
		Method:      http.MethodGet,
		Path:        Prefix + "/auth/me",
		Summary:     "The signed-in user",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, _ *struct{}) (*struct{ Body auth.User }, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		return &struct{ Body auth.User }{Body: u}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "changePassword",
		Method:        http.MethodPut,
		Path:          Prefix + "/auth/password",
		Summary:       "Change your own password (signs out other devices)",
		Tags:          []string{"auth"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *ChangePasswordInput) (*struct{}, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if _, err := deps.Auth.CheckPassword(ctx, u.Name, in.Body.Current); err != nil {
			if errors.Is(err, auth.ErrInvalidCredentials) {
				return nil, huma.Error401Unauthorized("current password is wrong")
			}
			return nil, throttled(err)
		}
		current, _ := auth.SessionTokenFrom(ctx)
		if err := deps.Auth.ChangePassword(ctx, u.ID, in.Body.New, current); err != nil {
			return nil, inputError(err)
		}
		publishSessionsChanged(deps, u.ID)
		return nil, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "listUsers",
		Method:      http.MethodGet,
		Path:        Prefix + "/users",
		Summary:     "List accounts (admin)",
		Tags:        []string{"users"},
	}), func(ctx context.Context, _ *struct{}) (*UserListOutput, error) {
		rows, err := deps.Queries.ListUsers(ctx)
		if err != nil {
			return nil, err
		}
		out := &UserListOutput{Body: UserList{Items: make([]Account, 0, len(rows))}}
		for _, r := range rows {
			out.Body.Items = append(out.Body.Items, accountFromDB(r))
		}
		return out, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "updateUser",
		Method:      http.MethodPatch,
		Path:        Prefix + "/users/{id}",
		Summary:     "Change an account (admin)",
		Description: "recordHistory off keeps the user's plays out of history from now on; they still show in Now playing. seerrUserId is checked against Seerr's users.",
		Tags:        []string{"users"},
	}), func(ctx context.Context, in *UpdateUserInput) (*AccountOutput, error) {
		if _, err := deps.Queries.GetUser(ctx, in.ID); err != nil {
			return nil, notFound(err, "user not found")
		}
		if in.Body.RecordHistory != nil {
			v := int64(0)
			if *in.Body.RecordHistory {
				v = 1
			}
			if err := deps.Queries.SetUserRecordHistory(ctx, db.SetUserRecordHistoryParams{RecordHistory: v, ID: in.ID}); err != nil {
				return nil, err
			}
		}
		if in.Body.SeerrUserID != nil {
			if deps.Integrations == nil {
				return nil, huma.Error503ServiceUnavailable("integrations are off")
			}
			if err := deps.Integrations.LinkSeerrUser(ctx, in.ID, *in.Body.SeerrUserID); err != nil {
				return nil, integrationError(err)
			}
		}
		u, err := deps.Queries.GetUser(ctx, in.ID)
		if err != nil {
			return nil, err
		}
		return &AccountOutput{Body: accountFromDB(u)}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "resetUserPassword",
		Method:        http.MethodPut,
		Path:          Prefix + "/users/{id}/password",
		Summary:       "Reset another account's password (admin)",
		Description:   "Signs out all of the account's browsers and devices and cancels pending device approvals. Use changePassword for your own account. Away from trusted networks requires HTTPS and password confirmation.",
		Tags:          []string{"users"},
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, in *ResetUserPasswordInput) (*struct{}, error) {
		if err := requireConfirmed(ctx, deps, "resetUserPassword", in.ID); err != nil {
			return nil, err
		}
		me, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if me.ID == in.ID {
			return nil, huma.Error409Conflict("change your own password under Account & devices")
		}
		if _, err := deps.Queries.GetUser(ctx, in.ID); err != nil {
			return nil, notFound(err, "user not found")
		}
		if err := deps.Auth.ChangePassword(ctx, in.ID, in.Body.Password, ""); err != nil {
			return nil, inputError(err)
		}
		publishSessionsChanged(deps, in.ID)
		return nil, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "createUser",
		Method:        http.MethodPost,
		Path:          Prefix + "/users",
		Summary:       "Create an account (admin)",
		Tags:          []string{"users"},
		DefaultStatus: http.StatusCreated,
	}), func(ctx context.Context, in *CreateUserInput) (*CreateUserOutput, error) {
		if in.Body.IsAdmin {
			if err := requireConfirmed(ctx, deps, "createUser", nil); err != nil {
				return nil, err
			}
		}
		u, err := deps.Auth.CreateUser(ctx, in.Body.Name, in.Body.Password, in.Body.IsAdmin)
		if err != nil {
			return nil, inputError(err)
		}
		setAdminAuditTarget(ctx, u.ID)
		return &CreateUserOutput{Status: http.StatusCreated, Body: u}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "deleteUser",
		Method:        http.MethodDelete,
		Path:          Prefix + "/users/{id}",
		Summary:       "Delete an account and its watch history (admin)",
		Tags:          []string{"users"},
		DefaultStatus: http.StatusNoContent,
	}), func(ctx context.Context, in *UserIDInput) (*struct{}, error) {
		if err := requireConfirmed(ctx, deps, "deleteUser", in.ID); err != nil {
			return nil, err
		}
		me, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if me.ID == in.ID {
			return nil, huma.Error409Conflict("you cannot delete your own account")
		}
		if _, err := deps.Queries.GetUser(ctx, in.ID); err != nil {
			return nil, notFound(err, "user not found")
		}
		if err := deps.Auth.DeleteUser(ctx, in.ID); err != nil {
			return nil, err
		}
		publishSessionsChanged(deps, in.ID)
		if deps.Activity != nil {
			deps.Activity.ForgetUser(in.ID)
		}
		return nil, nil
	})
}

// sessionClient describes the browser or device signing in. Without a
// client it is a browser session named from its user agent.
func sessionClient(c *playback.Client, userAgent string) auth.Client {
	if c == nil {
		d := activity.DeviceFrom("", "", "", "", "", userAgent)
		return auth.Client{Kind: auth.KindBrowser, Name: d.Name, Product: d.Product, Platform: d.Platform, UserAgent: userAgent}
	}
	d := activity.DeviceFrom(c.DeviceID, c.Name, c.Product, c.Platform, c.Version, userAgent)
	return auth.Client{Kind: auth.KindDevice, DeviceKey: d.Key, Name: d.Name, Product: d.Product, Platform: d.Platform, Version: d.Version,
		Model: strings.TrimSpace(c.Model), UserAgent: userAgent}
}

// signInOutput hands a browser its cookie and a device its token.
func signInOutput(ctx context.Context, deps Deps, u auth.User, g auth.Grant) *UserOutput {
	out := &UserOutput{Body: SignIn{User: u, SessionID: g.SessionID}}
	if g.Kind == auth.KindDevice {
		exp := g.ExpiresAt.UTC().Truncate(time.Second)
		out.Body.Token, out.Body.ExpiresAt = g.Token, &exp
		return out
	}
	out.SetCookie = cookieHeader(ctx, deps, g.Token, false)
	return out
}

// exemptPath lists API paths that work without a session.
func exemptPath(p string) bool {
	if !strings.HasPrefix(p, Prefix+"/") {
		return true // SPA assets and routes
	}
	switch p {
	case Prefix + "/health", Prefix + "/auth/status", Prefix + "/auth/login", Prefix + "/setup", Prefix + "/playback/local", DocsPath, OpenAPIPath + ".json", OpenAPIPath + ".yaml",
		Prefix + "/auth/pair", Prefix + "/auth/pair/claim", Prefix + "/server":
		return true
	}
	// Radarr's and Sonarr's webhooks sign in with the connection's token.
	if strings.HasPrefix(p, Prefix+"/integrations/") && strings.HasSuffix(p, "/webhook") {
		return true
	}
	return strings.HasPrefix(p, Prefix+"/schemas")
}

// mediaPath lists routes players fetch directly, where the token may ride
// in the query string: streams, sessions, subtitles, artwork and seek
// thumbnail sheets. Offline download routes are not among them: only their
// own offline tokens (offlineRoute) and sessions open those.
func mediaPath(p string) bool {
	if strings.HasPrefix(p, Prefix+"/offline/") {
		return false
	}
	return liveMediaPath(p) || strings.HasSuffix(p, "/stream") || strings.HasPrefix(p, Prefix+"/playback/") ||
		(strings.Contains(p, "/subtitles/") && (strings.HasSuffix(p, ".vtt") || strings.HasSuffix(p, ".ass"))) ||
		strings.HasPrefix(p, Prefix+"/images/") || strings.HasPrefix(p, art.Path) || isTrickplaySheet(p)
}
