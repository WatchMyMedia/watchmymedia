package auth

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
)

type ctxKey struct{}

type sessionKey struct{}

// SessionTokenFrom returns the session token the request authenticated
// with, if it used one (not a media token).
func SessionTokenFrom(ctx context.Context) (string, bool) {
	t, ok := ctx.Value(sessionKey{}).(string)
	return t, ok
}

// MediaTokenFromContext returns the media token the request authenticated
// with, if it used one, so playlists can pass it on to their segments.
func MediaTokenFromContext(ctx context.Context) (string, bool) {
	t, ok := ctx.Value(mediaKey{}).(string)
	return t, ok
}

type mediaKey struct{}

type castKey struct{}

// CastTokenFromContext returns the cast token the request authenticated
// with, if it used one, so the cast token route can renew it.
func CastTokenFromContext(ctx context.Context) (string, bool) {
	t, ok := ctx.Value(castKey{}).(string)
	return t, ok
}

// UserFrom returns the authenticated user placed on the context by Middleware.
func UserFrom(ctx context.Context) (User, bool) {
	u, ok := ctx.Value(ctxKey{}).(User)
	return u, ok
}

// WithUser is for tests and internal callers.
func WithUser(ctx context.Context, u User) context.Context {
	return context.WithValue(ctx, ctxKey{}, u)
}

// Middleware authenticates every request except those exempt returns true
// for. Media paths (mediaPath true) may instead carry a media token in the
// access_token query parameter; session tokens are never read from URLs.
// A cast token works on the routes castRoute allows (nil allows none), as a
// bearer token or, on media paths, in access_token. An offline token works
// only on the routes of its own download record: offlineRoute names the
// record a path belongs to (nil names none). mediaPath must not match
// offline routes, so media and cast tokens never open them.
func (s *Service) Middleware(exempt, mediaPath func(path string) bool, castRoute func(method, path string) bool, offlineRoute func(path string) (string, bool)) func(http.Handler) http.Handler {
	castOK := func(r *http.Request) bool { return castRoute != nil && castRoute(r.Method, r.URL.Path) }
	// offline authenticates an offline token on its own record's routes.
	offline := func(ctx context.Context, r *http.Request, token string) (User, context.Context, error) {
		if offlineRoute == nil || (r.Method != http.MethodGet && r.Method != http.MethodHead) {
			return User{}, ctx, ErrUnauthenticated
		}
		record, ok := offlineRoute(r.URL.Path)
		if !ok {
			return User{}, ctx, ErrUnauthenticated
		}
		user, id, err := s.AuthenticateOffline(ctx, token)
		if err != nil {
			return User{}, ctx, err
		}
		if id != record {
			return User{}, ctx, ErrBadOfflineToken
		}
		return user, context.WithValue(ctx, offlineKey{}, Offline{Record: id, Token: token}), nil
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Attach the user whenever the token is good, even on public
			// routes, so /auth/status can report who is signed in.
			ctx := r.Context()
			var user User
			var err error
			if token := TokenFrom(r); token != "" && IsOfflineToken(token) {
				user, ctx, err = offline(ctx, r, token)
			} else if token != "" && IsCastToken(token) {
				err = ErrUnauthenticated
				if castOK(r) {
					user, err = s.AuthenticateCast(ctx, token)
					ctx = context.WithValue(ctx, castKey{}, token)
					if mediaPath(r.URL.Path) {
						ctx = context.WithValue(ctx, mediaKey{}, token)
					}
				}
			} else if token != "" {
				var slid bool
				user, slid, err = s.authenticate(ctx, token)
				ctx = context.WithValue(ctx, sessionKey{}, token)
				// The cookie's Max-Age dates from when it was last sent: send
				// it again when the session's expiry slides, or a browser in
				// daily use is signed out when the first cookie runs out.
				if slid && token == cookieToken(r) {
					s.SetCookie(ctx, w, token)
				}
			} else if mt := MediaTokenFrom(r); mt != "" && IsOfflineToken(mt) {
				user, ctx, err = offline(ctx, r, mt)
			} else if mt != "" && mediaPath(r.URL.Path) {
				// Playlists pass whichever token fetched them on to their segments.
				if IsCastToken(mt) {
					err = ErrUnauthenticated
					if castOK(r) {
						user, err = s.AuthenticateCast(ctx, mt)
						ctx = context.WithValue(ctx, castKey{}, mt)
					}
				} else {
					user, err = s.AuthenticateMedia(ctx, mt)
				}
				ctx = context.WithValue(ctx, mediaKey{}, mt)
			} else {
				err = ErrUnauthenticated
			}
			if err != nil {
				if exempt(r.URL.Path) {
					next.ServeHTTP(w, r)
					return
				}
				if strings.HasPrefix(r.URL.Path, "/api/") {
					w.Header().Set("Content-Type", "application/problem+json")
					w.WriteHeader(http.StatusUnauthorized)
					_ = json.NewEncoder(w).Encode(map[string]any{"title": "Unauthorized", "status": 401, "detail": "sign in required"})
					return
				}
				next.ServeHTTP(w, r) // the SPA shell handles its own sign-in screen
				return
			}
			next.ServeHTTP(w, r.WithContext(WithUser(ctx, user)))
		})
	}
}
