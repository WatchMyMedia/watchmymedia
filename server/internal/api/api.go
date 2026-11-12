// Package api exposes the HTTP API. Routes are declared with huma so the
// OpenAPI 3.1 document is generated from the Go types; the web client and
// future TV clients consume that document rather than hand-written contracts.
package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"maps"
	"net/http"
	"reflect"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/danielgtaylor/huma/v2/adapters/humachi"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/artwork"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/lists"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/livetv/guide/art"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/netguard"
	"watchmymedia/server/internal/offline"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/plexsync"
	"watchmymedia/server/internal/probe"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/subtitles"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/trailers"
	"watchmymedia/server/internal/trickplay"
	"watchmymedia/server/internal/turnstile"
	"watchmymedia/server/internal/updates"
	"watchmymedia/server/internal/upload"
	"watchmymedia/server/internal/watchstate"
)

const (
	Prefix      = "/api"
	OpenAPIPath = Prefix + "/openapi" // served as .json and .yaml
	DocsPath    = Prefix + "/docs"
)

// Deps are the services the handlers need. Queries may be nil when the API is
// only built to emit its OpenAPI document.
type Deps struct {
	AdaptiveHLS bool // explicit experimental operator opt-in; false until M2 qualification
	Version     string
	// Host is the platform a container install runs on when known
	// ("unraid"), for ServerInfo.host; empty otherwise.
	Host    string
	Queries *db.Queries
	Log     *slog.Logger
	Bus     *events.Bus
	Scanner *scanner.Scanner
	Matcher *metadata.Matcher
	// Images serves cached TMDB artwork at /api/images/{size}/{file}.
	Images http.Handler
	// Artwork keeps the pictures admins chose in place of TMDB's posters,
	// and ImagesDir is where Images caches their sizes (PLAN-POSTERS.md);
	// without Artwork, uploads and links are refused.
	Artwork   *artwork.Store
	ImagesDir string
	Playback  *playback.Manager
	Subtitles *playback.Subtitles
	// OnlineSubtitles searches a provider and keeps downloads; nil turns
	// online subtitles off.
	OnlineSubtitles *subtitles.Service
	// Keyframes finds keyframes for VOD playlists of copied video; nil
	// means copied video always gets event playlists.
	Keyframes *playback.Keyframes
	// Prober reads chapters at playback start; nil skips them.
	Prober *probe.Prober
	// Auth guards every API route except the public ones; nil disables auth (tests, spec dumps).
	Auth *auth.Service
	// Settings, TMDB, Conn and DataDir back the settings page and backups.
	Settings        *settings.Store
	TMDB            *tmdb.Client
	Conn            *sql.DB
	DataDir         string
	DefaultHWDevice *int
	DefaultHWAccel  string
	// Net resolves client addresses behind trusted proxies and limits admin
	// access and setup; nil means no proxies and default trusted networks.
	Net               *netguard.Guard
	Upload            *upload.Limiter
	RemoteAdminConfig *bool
	// HSTS sends Strict-Transport-Security (set when served over HTTPS).
	HSTS bool
	// Activity records plays; nil turns Now playing, history and statistics off.
	Activity *activity.Tracker
	// Discovery re-announces the server when it is renamed; nil when the
	// local network announcement is off.
	Discovery interface{ Rename(name string) }
	// Turnstile is the bot check on sign-in, also given to Auth; nil or
	// without keys, it is off.
	Turnstile *turnstile.Verifier
	// Cast is casting to Google Cast receivers; the zero value is off.
	Cast CastOptions
	// Updates checks for a newer release when an admin asks; nil, or
	// without a URL, Check for updates is off.
	Updates *updates.Checker
	// Integrations are the Radarr and Sonarr connections; nil turns them off.
	Integrations *integrations.Service
	// Watch makes every change to watch state; nil makes one over Queries
	// and Bus.
	Watch *watchstate.Service
	// Plex connects a Plex server and previews importing from it; nil
	// turns the Plex routes off.
	Plex *plexsync.Service
	// Trickplay makes and serves seek thumbnails; nil turns them off.
	Trickplay *trickplay.Generator
	// Trailers refreshes movies' TMDB trailers and says whether YouTube
	// trailers are on; nil makes one that reads the setting but doesn't run.
	Trailers *trailers.Refresher
	// Offline keeps the offline downloads being fetched now; nil makes one.
	Offline      *offline.Transfers
	LiveTV       *livetv.Service
	LiveSessions *livetv.Sessions
	Recordings   *dvr.Service
	// Guide keeps Live TV listings; nil leaves them off.
	Guide *guide.Service
	// Lists makes the lists rows come from and fetches them again
	// (PLAN-ROWS.md 7.2); nil makes one that doesn't run its refresh job.
	Lists *lists.Service
	// listsState keeps the list routes' caches.
	listsState *listsState
	// automaticSubtitles binds opt-in work to the exact sign-in and play.
	automaticSubtitles *automaticSubtitlePlays
}

func init() {
	// A handler error that is not a status (a database or file error)
	// becomes a bare 500: its text, which can name files, queries and
	// addresses, goes to the log rather than to whoever made the request.
	huma.NewErrorWithContext = func(ctx huma.Context, status int, msg string, errs ...error) huma.StatusError {
		if status >= http.StatusInternalServerError && len(errs) > 0 {
			path, reqID := "", ""
			if ctx != nil {
				path, reqID = ctx.URL().Path, middleware.GetReqID(ctx.Context())
			}
			slog.Error("request failed", "path", path, "err", errors.Join(errs...), "req_id", reqID)
			errs = nil
		}
		return huma.NewError(status, msg, errs...)
	}
}

// New builds the router with the API mounted. The caller may attach more
// handlers (such as the embedded web UI) to the returned mux.
func New(deps Deps) (*chi.Mux, huma.API) {
	if deps.Log == nil {
		deps.Log = slog.Default()
	}
	if deps.Net == nil {
		deps.Net = netguard.Default()
	}
	if deps.automaticSubtitles == nil {
		deps.automaticSubtitles = newAutomaticSubtitlePlays()
	}
	if deps.Watch == nil && deps.Queries != nil {
		deps.Watch = watchstate.New(deps.Queries, deps.Bus)
	}
	if deps.Offline == nil {
		deps.Offline = offline.NewTransfers(nil)
	}
	if deps.listsState == nil {
		deps.listsState = newListsState()
	}
	if deps.Lists == nil && deps.Queries != nil {
		deps.Lists = lists.New(lists.Options{Queries: deps.Queries, Conn: deps.Conn, TMDB: deps.TMDB, Bus: deps.Bus, Log: deps.Log, Changed: ListsChangedPublisher(deps.Bus)})
	}
	if deps.Trailers == nil && deps.Queries != nil {
		deps.Trailers = trailers.New(deps.Queries, deps.TMDB, deps.Log, TrailersYouTube(context.Background(), deps.Settings))
	}
	if deps.LiveTV == nil && deps.Queries != nil && deps.Settings != nil {
		deps.LiveTV = livetv.New(deps.Queries, deps.Settings, nil)
	}
	if deps.Recordings == nil && deps.LiveTV != nil && deps.Queries != nil && deps.Settings != nil {
		deps.Recordings = dvr.New(deps.Queries, deps.Settings, deps.LiveTV, deps.LiveSessions, dvr.Options{Scanner: deps.Scanner, Bus: deps.Bus, Watch: deps.Watch})
	}
	r := chi.NewMux()
	r.Use(middleware.RequestID)
	r.Use(middleware.Recoverer)
	r.Use(deps.Net.Middleware)
	r.Use(castCORS(deps.Cast))
	r.Use(sameOrigin(castTrusted(deps.Cast)))
	r.Use(securityHeaders(deps.HSTS, deps.Turnstile, deps.Cast.Enabled && deps.Cast.AppID != "", deps.Trailers.YouTube))
	r.Use(requestLogger(deps.Log))
	r.Use(adminAuditLogger(deps.Log))
	r.Use(compress)
	r.Use(withRequest)
	if deps.Auth != nil {
		var cast func(method, path string) bool
		if deps.Cast.Enabled {
			cast = castRoute
		}
		r.Use(deps.Auth.Middleware(exemptPath, mediaPath, cast, offlineRoute))
	}
	if deps.Upload != nil {
		r.Use(uploadMiddleware(deps.Upload))
	}

	cfg := huma.DefaultConfig("WatchMyMedia API", deps.Version)
	cfg.OpenAPIPath = OpenAPIPath
	cfg.DocsPath = DocsPath
	cfg.SchemasPath = Prefix + "/schemas"
	cfg.Components.Schemas = huma.NewMapRegistry("#/components/schemas/", schemaName)
	cfg.CreateHooks = nil // no $schema field injected into every response
	// DefaultConfig hands out huma's package-wide format map: delete from
	// a copy, or two routers built at once race on it.
	cfg.Formats = maps.Clone(cfg.Formats)
	delete(cfg.Formats, "application/cbor")
	delete(cfg.Formats, "cbor")

	h := humachi.New(r, cfg)
	registerSystem(h, deps)
	registerAuth(h, deps)
	registerCast(h, deps)
	registerPairing(h, deps)
	registerWatch(h, deps)
	registerSearch(h, deps)
	registerEvents(h, deps)
	registerLibraries(h, deps)
	registerFiles(h, deps)
	registerUnmatched(h, deps)
	registerRematch(h, deps)
	registerIgnore(h, deps)
	registerSeasonLineUps(h, deps)
	registerPreferences(h, deps)
	registerSegments(h, deps)
	registerBrowse(h, deps)
	registerRows(h, deps)
	registerScreenRows(h, deps)
	registerMediaLists(h, deps)
	registerPlayback(h, r, deps)
	registerSubtitles(h, deps)
	registerPlans(h, deps)
	registerActivity(h, deps)
	registerSettings(h, r, deps)
	registerUpdates(h, deps)
	registerIntegrations(h, r, deps)
	registerPlex(h, deps)
	registerRequests(h, deps)
	registerDelete(h, deps)
	registerPosters(h, deps)
	registerTrickplay(h, r, deps)
	registerOffline(h, r, deps)
	registerLiveTV(h, deps)
	registerLiveTVSessions(h, r, deps)
	registerLiveTVGuide(h, r, deps)
	registerLiveTVPersonal(h, deps)
	registerLiveTVRecordings(h, deps)
	if deps.Images != nil {
		r.Get(Prefix+"/images/{size}/{file}", deps.Images.ServeHTTP)
	}
	return r, h
}

var (
	activityPkg     = reflect.TypeFor[activity.Item]().PkgPath()
	integrationsPkg = reflect.TypeFor[integrations.Connection]().PkgPath()
	trickplayPkg    = reflect.TypeFor[trickplay.Status]().PkgPath()
)

// integrationNames are the schema names of integrations types whose short
// names are too general for the one namespace.
var integrationNames = map[string]string{
	"Connection": "Integration",
	"Status":     "IntegrationStatus",
	"Webhook":    "IntegrationWebhook",
	"Details":    "IntegrationDetails",
	"RootFolder": "IntegrationRootFolder",
	"Target":     "RequestTarget",
	"Mapping":    "PathMapping",
	"Download":   "QueueDownload",
	"Request":    "MediaRequest",
}

// schemaName is huma's default, except that types from the activity package
// are prefixed Activity: their short names (Item, Device, MediaInfo) would
// collide with other packages' in the one OpenAPI namespace, and so are
// trickplay's (Status, Current, Ready) with Trickplay. Some
// integrations types are renamed for the same reason.
func schemaName(t reflect.Type, hint string) string {
	name := huma.DefaultSchemaNamer(t, hint)
	for t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	if t.PkgPath() == reflect.TypeFor[livetv.Source]().PkgPath() || t.PkgPath() == reflect.TypeFor[hdhomerun.Device]().PkgPath() {
		return "LiveTV" + name
	}
	if t.PkgPath() == reflect.TypeFor[dvr.Recording]().PkgPath() {
		switch name {
		case "Settings":
			return "LiveTVRecordingSettings"
		case "Create":
			return "LiveTVRecordingCreate"
		case "Manual":
			return "LiveTVRecordingManual"
		case "Update":
			return "LiveTVRecordingUpdate"
		case "UpdateRule":
			return "LiveTVRecordingRuleUpdate"
		default:
			return "LiveTV" + name
		}
	}
	if t.PkgPath() == reflect.TypeFor[guide.Status]().PkgPath() {
		return "LiveTVGuide" + name
	}
	if t.PkgPath() == activityPkg && !strings.HasPrefix(name, "Activity") {
		return "Activity" + name
	}
	if t.PkgPath() == trickplayPkg && !strings.HasPrefix(name, "Trickplay") {
		return "Trickplay" + name
	}
	if t.PkgPath() == integrationsPkg {
		if n, ok := integrationNames[name]; ok {
			return n
		}
	}
	return name
}

// sameOrigin refuses POST, PUT, PATCH and DELETE requests that a browser
// makes on behalf of another site, including a sibling subdomain, which
// SameSite=Lax cookies do not stop (CSRF). Browsers say where a request
// comes from in Sec-Fetch-Site or Origin; native apps and scripts send
// neither and are not affected. Trusted origins (Cast receiver pages) may:
// they get no cookies, since CORS never allows credentials, and act with
// the cast token they were given.
func sameOrigin(trusted []string) func(http.Handler) http.Handler {
	p := http.NewCrossOriginProtection()
	for _, o := range trusted {
		if err := p.AddTrustedOrigin(o); err != nil {
			slog.Warn("cast receiver origin ignored", "origin", o, "err", err)
		}
	}
	p.SetDenyHandler(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusForbidden)
		_ = json.NewEncoder(w).Encode(map[string]any{"title": "Forbidden", "status": http.StatusForbidden, "detail": "cross-origin request refused"})
	}))
	return p.Handler
}

func castTrusted(c CastOptions) []string {
	if !c.Enabled {
		return nil
	}
	return c.ReceiverOrigins
}

// requestLogger logs every request at Info, except the media a player
// fetches many times a minute (HLS segments and playlists, file streams,
// artwork), which are logged at Debug unless they fail.
func requestLogger(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			ww := middleware.NewWrapResponseWriter(w, r.ProtoMajor)
			next.ServeHTTP(ww, r)
			level := slog.LevelInfo
			if mediaRoute(r.URL.Path) && ww.Status() < http.StatusBadRequest {
				level = slog.LevelDebug
			}
			log.Log(r.Context(), level, "http",
				"method", r.Method,
				"path", r.URL.Path,
				"ip", clientIP(r),
				"status", ww.Status(),
				"bytes", ww.BytesWritten(),
				"dur", time.Since(start).Round(time.Microsecond),
				"req_id", middleware.GetReqID(r.Context()),
			)
		})
	}
}

// mediaRoute lists the routes players and downloaders fetch over and over.
func mediaRoute(p string) bool {
	_, download := offlineRoute(p)
	return liveMediaPath(p) || strings.HasPrefix(p, Prefix+"/playback/") || strings.HasSuffix(p, "/stream") || strings.HasPrefix(p, Prefix+"/images/") || strings.HasPrefix(p, art.Path) || isTrickplaySheet(p) || download
}

func clientIP(r *http.Request) string {
	if i, ok := netguard.From(r.Context()); ok && i.IP.IsValid() {
		return i.IP.String()
	}
	return r.RemoteAddr
}
