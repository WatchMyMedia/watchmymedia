package api

import (
	"context"
	"net"
	"net/http"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/netguard"
)

// CastOptions turns on casting to Google Cast devices (PLAN-CAST.md). The
// receiver, a web page on the TV, runs the play itself with a cast token:
// it asks for the decision, fetches the media and reports progress.
type CastOptions struct {
	// Enabled issues cast tokens and lets receiver pages call the API.
	Enabled bool
	// AppID is the receiver application senders launch; empty, the web UI
	// shows no Cast button (the apps carry their own).
	AppID string
	// URL is an address TVs can reach, offered to receivers first.
	URL string
	// ReceiverOrigins are where receiver pages are served from (CORS).
	ReceiverOrigins []string
	// Host and Port are the listen address, for the LAN addresses offered
	// to receivers: an empty or unspecified host offers every private
	// address of this machine, a loopback one none.
	Host string
	Port int
}

// CastToken is what a sender hands the receiver with each cast.
type CastToken struct {
	Token     string    `json:"token" doc:"Send as Authorization: Bearer to the playback routes, and as ?access_token= on media URLs."`
	ExpiresAt time.Time `json:"expiresAt" doc:"Renew before this by calling this route with the cast token."`
	Param     string    `json:"param" doc:"The query parameter name for media URLs, access_token."`
	URLs      []string  `json:"urls" nullable:"false" doc:"Addresses a TV may reach this server at, HTTPS first. A receiver uses the first whose GET /api/server answers with this server's id."`
}

type CastTokenOutput struct{ Body CastToken }

func registerCast(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "createCastToken",
		Method:      http.MethodPost,
		Path:        Prefix + "/auth/cast-token",
		Summary:     "Get a token for a Cast receiver, or renew one",
		Description: "A sender calls this with its session before casting and passes the token to the receiver, which may use it only to fetch media, ask for playback decisions, report progress, read the user's playback preferences, play a Live TV channel (start, keep and stop its session, and read the channels, listings and their pictures; API level 14) and renew it here. It lasts 12 hours and stops working when the sender's session signs out. Called with a cast token, it returns a fresh one.",
		Tags:        []string{"auth"},
	}, func(ctx context.Context, _ *struct{}) (*CastTokenOutput, error) {
		if !deps.Cast.Enabled {
			return nil, huma.Error404NotFound("casting is turned off on this server")
		}
		if _, err := currentUser(ctx); err != nil {
			return nil, err
		}
		var tok string
		var exp time.Time
		var err error
		if session, ok := auth.SessionTokenFrom(ctx); ok {
			tok, exp, err = deps.Auth.IssueCastToken(ctx, session)
		} else if cast, ok := auth.CastTokenFromContext(ctx); ok {
			tok, exp, err = deps.Auth.RenewCastToken(ctx, cast)
		} else {
			return nil, huma.Error401Unauthorized("sign in with a session to cast")
		}
		if err != nil {
			return nil, huma.Error401Unauthorized("sign in required")
		}
		return &CastTokenOutput{Body: CastToken{Token: tok, ExpiresAt: exp.UTC(), Param: auth.QueryParam, URLs: castURLs(requestFrom(ctx), deps)}}, nil
	})
}

// castRoute lists what a cast token opens: media, and the few routes a
// receiver needs to run a play.
func castRoute(method, p string) bool {
	if strings.HasPrefix(p, Prefix+"/live-tv/") {
		return castLiveRoute(method, p)
	}
	switch {
	case mediaPath(p):
		return method == http.MethodGet || method == http.MethodHead ||
			(method == http.MethodDelete && strings.HasPrefix(p, Prefix+"/playback/"))
	case p == Prefix+"/server", p == Prefix+"/me/preferences":
		return method == http.MethodGet
	case p == Prefix+"/auth/cast-token":
		return method == http.MethodPost
	}
	if id, rest, ok := strings.Cut(strings.TrimPrefix(p, Prefix+"/files/"), "/"); ok && strings.HasPrefix(p, Prefix+"/files/") {
		if _, err := strconv.ParseInt(id, 10, 64); err != nil {
			return false
		}
		return (rest == "playback" && method == http.MethodPost) || (rest == "progress" && method == http.MethodPut)
	}
	return false
}

// castLiveRoute lists what a cast token opens under /live-tv/: what a
// receiver needs to play a channel for the person who cast (PLAN-LIVE-TV.md
// §4.1). The session belongs to the sender's sign-in, as the token does.
// Settings, sources, Activity and the admin stop stay closed.
func castLiveRoute(method, p string) bool {
	read := method == http.MethodGet || method == http.MethodHead
	rest := strings.TrimPrefix(p, Prefix+"/live-tv/")
	switch {
	case rest == "sessions":
		return method == http.MethodPost
	case liveMediaPath(p):
		return read
	case rest == "channels", rest == "capabilities", rest == "guide":
		return method == http.MethodGet
	}
	if id, ok := strings.CutPrefix(rest, "art/"); ok {
		return read && id != "" && !strings.Contains(id, "/")
	}
	if id, ok := strings.CutPrefix(rest, "sessions/"); ok && id != "" && !strings.Contains(id, "/") {
		return method == http.MethodGet || method == http.MethodPut || method == http.MethodDelete
	}
	return false
}

// castURLs lists the addresses a TV may reach this server at.
func castURLs(r *http.Request, deps Deps) []string {
	own := ""
	if r != nil {
		own = ownOrigin(r, deps.Net)
	}
	return castURLList(deps.Cast.URL, own, lanAddrs(deps.Cast.Host), deps.Cast.Port)
}

// castURLList puts the configured address first, then the others with
// HTTPS before HTTP: a receiver page is HTTPS, and plain HTTP from it may be
// blocked as mixed content. With nothing a TV can reach it is empty, never
// nil, so the JSON is [] as the schema promises.
func castURLList(configured, own string, lan []netip.Addr, port int) []string {
	urls := []string{}
	add := func(u string) {
		if u != "" && !slices.Contains(urls, u) {
			urls = append(urls, u)
		}
	}
	add(configured)
	if strings.HasPrefix(own, "https://") {
		add(own)
	}
	if port > 0 {
		for _, a := range lan {
			add("http://" + net.JoinHostPort(a.String(), strconv.Itoa(port)))
		}
	}
	add(own)
	return urls
}

// ownOrigin is the address this request came in at, unless a TV cannot
// reach it: loopback, or a tailnet name or address.
func ownOrigin(r *http.Request, g *netguard.Guard) string {
	host := r.Host
	name := host
	if h, _, err := net.SplitHostPort(host); err == nil {
		name = h
	}
	name = strings.ToLower(strings.Trim(name, "[]"))
	if name == "" || name == "localhost" || strings.HasSuffix(name, ".localhost") || strings.HasSuffix(name, ".ts.net") {
		return ""
	}
	if a, err := netip.ParseAddr(name); err == nil && (a.IsLoopback() || netguard.Classify(a) == netguard.NetTailnet) {
		return ""
	}
	scheme := "http"
	if g != nil && g.HTTPS(r) {
		scheme = "https"
	}
	return scheme + "://" + host
}

// lanAddrs lists this machine's private IPv4 addresses that a server
// listening on host can be reached at.
func lanAddrs(host string) []netip.Addr {
	if host != "" {
		a, err := netip.ParseAddr(host)
		if err != nil || a.IsLoopback() {
			return nil
		}
		if !a.IsUnspecified() {
			if a.Is4() && a.IsPrivate() {
				return []netip.Addr{a}
			}
			return nil
		}
	}
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []netip.Addr
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, ad := range addrs {
			pfx, err := netip.ParsePrefix(ad.String())
			if err != nil {
				continue
			}
			if a := pfx.Addr().Unmap(); a.Is4() && a.IsPrivate() && netguard.Classify(a) == netguard.NetLocal {
				out = append(out, a)
			}
		}
	}
	return out
}

// castCORS lets Cast receiver pages, served from their own origin, use the
// API: the receiver origins get the playback routes with an Authorization
// header (never cookies), and anyone gets media routes, whose token rides
// in the URL. Cast devices fetch HLS and subtitles with XHR, which needs
// this even for Google's own receivers.
func castCORS(c CastOptions) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		if !c.Enabled {
			return next
		}
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			p := r.URL.Path
			if !strings.HasPrefix(p, Prefix+"/") {
				next.ServeHTTP(w, r)
				return
			}
			h := w.Header()
			// The receiver measures completed segment transfers in its own
			// PerformanceResourceTiming. Media still requires its normal token.
			if mediaPath(p) {
				h.Set("Timing-Allow-Origin", "*")
			}
			origin := r.Header.Get("Origin")
			preflight := r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != ""
			h.Add("Vary", "Origin")
			switch {
			case origin != "" && slices.Contains(c.ReceiverOrigins, origin):
				h.Set("Access-Control-Allow-Origin", origin)
				h.Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
				if preflight {
					h.Set("Access-Control-Allow-Methods", "GET, HEAD, POST, PUT, DELETE")
					h.Set("Access-Control-Allow-Headers", "Authorization, Content-Type, Range")
					h.Set("Access-Control-Max-Age", "600")
					w.WriteHeader(http.StatusNoContent)
					return
				}
			case mediaPath(p):
				h.Set("Access-Control-Allow-Origin", "*")
				h.Set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
				if preflight {
					h.Set("Access-Control-Allow-Methods", "GET, HEAD")
					h.Set("Access-Control-Allow-Headers", "Range")
					h.Set("Access-Control-Max-Age", "600")
					w.WriteHeader(http.StatusNoContent)
					return
				}
			}
			next.ServeHTTP(w, r)
		})
	}
}
