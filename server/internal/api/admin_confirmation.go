package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5/middleware"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/netguard"
)

//nolint:gosec // G101: a public problem-type URL, not a credential.
const confirmPasswordType = "https://watchmy.media/problems/confirm-password"

// Per-request audit metadata contains identities only, never input bodies.
type adminAudit struct {
	user      auth.User
	operation string
	target    any
	message   string
	level     slog.Level
}
type adminAuditKey struct{}

func adminAuditLogger(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			a := &adminAudit{}
			ctx := context.WithValue(r.Context(), adminAuditKey{}, a)
			ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
			next.ServeHTTP(ww, r.WithContext(ctx))
			if a.message != "" {
				i, _ := netguard.From(ctx)
				ip, network := "", netguard.NetRemote
				if !i.Proxied && i.IP.IsValid() {
					ip, network = i.IP.String(), netguard.Classify(i.IP)
				}
				log.Log(ctx, a.level, a.message, "user", a.user.Name, "user_id", a.user.ID,
					"operation", a.operation, "target", a.target, "ip", ip, "network", network, "status", ww.Status())
			}
		})
	}
}

func setAdminAudit(ctx context.Context, u auth.User, op string, target any, msg string, level slog.Level) {
	if a, ok := ctx.Value(adminAuditKey{}).(*adminAudit); ok {
		*a = adminAudit{u, op, target, msg, level}
	}
}

func setAdminAuditTarget(ctx context.Context, target any) {
	if a, ok := ctx.Value(adminAuditKey{}).(*adminAudit); ok && a.message != "" {
		a.target = target
	}
}

func requireConfirmed(ctx context.Context, deps Deps, operation string, target any) error {
	u, err := requireAdmin(ctx)
	if err != nil {
		return err
	}
	if netguard.Trusted(ctx) {
		return nil
	}
	setAdminAudit(ctx, u, operation, target, "admin action away from home", slog.LevelInfo)
	if deps.Auth == nil || !deps.Auth.Confirmed(u) {
		setAdminAudit(ctx, u, operation, target, "admin action needs password confirmation", slog.LevelDebug)
		return &huma.ErrorModel{Type: confirmPasswordType, Title: "Forbidden", Status: http.StatusForbidden, Detail: "enter your password to continue"}
	}
	return nil
}

// writeAdminError also preserves the problem type on the streaming backup route.
func writeAdminError(w http.ResponseWriter, err error) {
	status := http.StatusInternalServerError
	if se, ok := errors.AsType[huma.StatusError](err); ok {
		status = se.GetStatus()
	}
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	if p, ok := errors.AsType[*huma.ErrorModel](err); ok {
		_ = json.NewEncoder(w).Encode(p)
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"status": status, "detail": err.Error()})
}

func logRemoteAdminSwitch(ctx context.Context, deps Deps, enabled bool) {
	u, _ := auth.UserFrom(ctx)
	i, _ := netguard.From(ctx)
	ip, network := "", netguard.NetRemote
	if !i.Proxied && i.IP.IsValid() {
		ip, network = i.IP.String(), netguard.Classify(i.IP)
	}
	msg := "remote admin turned off"
	if enabled {
		msg = "remote admin turned on"
	}
	deps.Log.InfoContext(ctx, msg, "user", u.Name, "user_id", u.ID, "operation", "updateSettings", "ip", ip, "network", network)
}
