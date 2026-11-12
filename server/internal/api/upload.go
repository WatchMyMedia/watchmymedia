package api

import (
	"context"
	"net/http"
	"strings"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/livetv/guide/art"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/upload"
)

// uploadMiddleware runs after authentication, before every media route. No
// play ID is required: direct files, HLS, Live TV, artwork and offline media
// all share the remote aggregate budget, including repeat/range requests.
func uploadMiddleware(l *upload.Limiter) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if (r.Method != http.MethodGet && r.Method != http.MethodHead) || !uploadMediaRoute(r.URL.Path) {
				next.ServeHTTP(w, r)
				return
			}
			if !remoteUpload(r.Context()) {
				next.ServeHTTP(w, r)
				return
			}
			key := "address:" + clientIP(r)
			if u, ok := auth.UserFrom(r.Context()); ok && u.SignInID != "" {
				key = "sign-in:" + u.SignInID
			}
			_, download := offlineRoute(r.URL.Path)
			if strings.HasPrefix(r.URL.Path, Prefix+"/images/") || strings.HasPrefix(r.URL.Path, art.Path) || isTrickplaySheet(r.URL.Path) {
				download = true
			}
			key += ":" + mediaFlowPath(r.URL.Path)
			w, done := l.Wrap(w, r.Context(), key, download)
			defer done()
			next.ServeHTTP(w, r)
		})
	}
}

func remoteUpload(ctx context.Context) bool {
	i, ok := netguard.From(ctx)
	return !ok || i.Proxied || netguard.Classify(i.IP) != netguard.NetLocal
}

func markServerPaced(deps Deps, ctx context.Context, play string) {
	if play != "" && deps.Upload != nil && deps.Activity != nil && remoteUpload(ctx) {
		s := deps.Upload.Snapshot()
		if s.Enforcing && s.ConfiguredBps > 0 {
			deps.Activity.MarkServerPaced(play, s.Generation)
		}
	}
}

func uploadMediaRoute(p string) bool {
	if _, ok := offlineRoute(p); ok {
		return true
	}
	if liveMediaPath(p) || strings.HasPrefix(p, Prefix+"/images/") || strings.HasPrefix(p, art.Path) || isTrickplaySheet(p) {
		return true
	}
	if strings.HasPrefix(p, Prefix+"/files/") && strings.HasSuffix(p, "/stream") {
		return true
	}
	if rest, ok := strings.CutPrefix(p, Prefix+"/playback/"); ok {
		id, name, found := strings.Cut(rest, "/")
		return found && id != "" && name != ""
	}
	return strings.Contains(p, "/subtitles/") && (strings.HasSuffix(p, ".vtt") || strings.HasSuffix(p, ".ass"))
}

func mediaFlowPath(path string) string {
	for _, prefix := range []string{Prefix + "/playback/", Prefix + "/live-tv/sessions/", Prefix + "/offline/", Prefix + "/files/"} {
		if rest, ok := strings.CutPrefix(path, prefix); ok {
			id, _, _ := strings.Cut(rest, "/")
			return prefix + id
		}
	}
	return path
}
