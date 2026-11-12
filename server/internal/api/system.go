package api

import (
	"context"
	"net/http"
	"runtime"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/settings"
)

type Health struct {
	Status  string    `json:"status" enum:"ok" doc:"Overall server status."`
	Version string    `json:"version" example:"0.1.0" doc:"Server build version."`
	Time    time.Time `json:"time" doc:"Current server time in UTC."`
	// TMDBConfigured is false until an API key is set; matching is off until then.
	TMDBConfigured bool `json:"tmdbConfigured"`
}

type HealthOutput struct {
	Body Health
}

// APILevel goes up by one with every change native apps must know about.
// Apps refuse servers below the level they were built for and say so, rather
// than failing in odd ways. 1: device sign-in, pairing, sessions, discovery.
// 2: cast tokens and castAppId (apps offer casting only from this level).
// 3: Automatic quality (apps offer it only from this level; it is no reason
// to refuse an older server). 4: Profile.separateAudio, audio as its own VOD
// rendition (the Cast receiver asks for it from this level). 5: requests,
// problem reports and missing episodes' state (/api/requests, /api/problems,
// Episode.download and .request; apps offer them only from this level),
// including Seerr as the backend (RequestOptions.backend "seerr",
// Problem.seerrIssueId), which landed before level 5 shipped. 6: deleting
// from the apps (RequestOptions.delete; members may delete episodes and
// movies while Who can delete is everyone, /api/settings/deleting; apps
// offer Delete only from this level).
// 7: ItemList.genreCounts (optional aggregate counts for library filters).
// 8: Profile.hdrTypes and the dovi5, dovi7 and dovi8 video keys; HDR is
// copied only to a screen that shows it (for every profile), and Dolby
// Vision is kept only for its key, else sent as its base layer or
// transcoded (PLAN-HDR-PROFILE.md). VideoPlan.dvProfile and
// Capabilities.doviToneMap. Apps send hdrTypes and the dovi keys, and offer
// hevc10 without an HDR display, only from this level: older servers
// reject hdrTypes and would copy HDR to a screen that was offered hevc10.
// 9: seek thumbnails (Decision.trickplay, GET /files/{id}/trickplay and its
// sheets, Settings.trickplay, trickplayEnabled, /trickplay; apps show
// thumbnails only when a decision carries them).
// 10: offline downloads (/api/offline, RequestOptions.offline,
// /api/settings/offline); apps offer Download only from this level.
// 11: Live TV source configuration and the separate live channel/session contract.
// 12: Library.lastScanError and lastScanErrorAt, why a library's last scan
// failed (PLAN-ONBOARDING.md §4); apps explain an empty library with it.
// 13: Live TV listings (PLAN-LIVE-TV-GUIDE.md): GET /live-tv/guide,
// LiveTVChannel.logoUrl, now and next, pictures at /live-tv/art/{id},
// /settings/live-tv/guide; capabilities.guide is true while any channel has
// listings. Apps show listings only from this level and with that flag.
// 14: casting Live TV (PLAN-LIVE-TV.md §4.1): a cast token may start, keep
// and stop its sender's live sessions and fetch their HLS, and read
// /live-tv/channels, capabilities, guide and art; capabilities.cast says the
// account may. Senders offer a channel to a receiver only from this level,
// with that flag, and when the receiver says it plays live.
// 15: Live TV favourites, listings search and reminders
// (PLAN-LIVE-TV-GUIDE.md §5.7): PUT and DELETE
// /live-tv/channels/{id}/favourite and LiveTVChannel.favourite, GET
// /live-tv/guide/search, /live-tv/reminders and Programme.reminder,
// Preferences.liveTVReminderMinutes, and the live.favourites.changed and
// live.reminders.changed events. Apps offer them only from this level.
// 16: admin hardware preferences (Settings.hwDevice and hardwareDiagnostics),
// explicit backend adapter selection and Capabilities.device. New reply fields
// are optional; clients that edit an adapter gate the new input on this level.
// ServerInfo.platform supplies server-side folder path examples.
// 17: optional admin Settings.hardwareAdapters inventory with separate native
// CUDA and DirectX ordinals. Clients retain default/manual selection when absent.
// 18: per-person Preferences.subtitles, Request.subtitleMode and
// Decision.subtitleId; POST /files/{id}/subtitles/automatic performs one
// opt-in exact-file subtitle download after the stream starts.
// 19: household DVR schedules, recording rules, tuner takeover warnings and growing playback.
// 20: movie trailers (PLAN-TRAILERS.md §5.3): MovieDetail.trailers on
// getMovie, best first, and Settings.trailersYouTube. A trailer's source is
// a plain string, "youtube" (key) or "file" (fileId); apps play the first
// trailer whose source they know and skip the others.
// 21: audio languages (PLAN-LANGUAGES.md): Preferences.audio.languages
// chooses the starting audio track of any play, cast, version list or hls
// download that names no audioIndex; SubtitlePreferences.foreignAudio turns
// subtitles on for audio the person doesn't follow; Profile.selectsAudioTrack
// keeps direct play for a track other than the file's default. Apps send the
// new request fields only from this level: older servers reject them.
// 22: rows people arrange and rows made from lists (PLAN-ROWS.md §6):
// getHome and getLibraryRows take arranged=true and send rows (ScreenRow:
// id, style, items, episodes or continue) in the person's order; the
// layout routes /me/rows/{screen} and /rows/defaults/{screen}; lists under
// /lists and /list-sources; Settings.listsMdblistConfigured; the
// rows.changed and lists.changed events. Row styles, list source kinds and
// row link kinds are plain strings: apps skip what they don't know.
// 23: optional Now playing preparation ranges and admin playback cache
// telemetry. Clients may display them when present; older replies omit them.
// 24: opt-in Automatic recovery, fresh transfer age and acknowledged quality
// switch generations. Older clients keep their step-down-only contract.
// 25: optional Live TV device profiles and output mode/reason. Older clients
// continue the fixed H.264/AAC conversion; clients send a live profile only
// from this level to opt into compatible copy, remux or audio conversion.
// 26: opt-in Live TV Automatic, fresh measurements and acknowledged output
// generations. Fixed-quality clients keep the same session and heartbeat.
// 27: opt-in adaptive HLS families and observed rendition measurements.
// Servers keep experimental family creation disabled unless configured.
const APILevel = 27

// ServerInfo identifies the server to apps before anyone signs in.
type ServerInfo struct {
	Platform      string `json:"platform,omitempty" required:"false" doc:"Server operating system, such as windows, darwin or linux. Used for server-side folder path examples; absent on older servers."`
	Host          string `json:"host,omitempty" required:"false" doc:"The platform a container install runs on, when the server can tell: unraid. Settings word updates and GPU setup for it. Absent otherwise and on older servers."`
	ID            string `json:"id" doc:"Stable random ID; apps remember servers by it, not by address."`
	Name          string `json:"name" doc:"Shown in apps' server lists; set under Settings."`
	Version       string `json:"version"`
	APILevel      int    `json:"apiLevel" doc:"Apps compare this with the level they need."`
	SetupRequired bool   `json:"setupRequired" doc:"No account exists yet; finish setup in a browser."`
	Turnstile     bool   `json:"turnstile" doc:"Password sign-in needs a Cloudflare Turnstile check, which only the web page can show: sign in by pairing instead (/auth/pair, opening linkPath in a browser on a phone)."`
	Casting       bool   `json:"casting" required:"false" doc:"The server issues cast tokens (POST /auth/cast-token), so apps may offer casting."`
	CastAppID     string `json:"castAppId,omitempty" doc:"The Google Cast receiver application the web UI launches; absent when it shows no Cast button."`
}

type ServerInfoOutput struct{ Body ServerInfo }

func registerSystem(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "getHealth",
		Method:      http.MethodGet,
		Path:        Prefix + "/health",
		Summary:     "Health check",
		Tags:        []string{"system"},
	}, func(ctx context.Context, _ *struct{}) (*HealthOutput, error) {
		return &HealthOutput{Body: Health{
			Status:         "ok",
			Version:        deps.Version,
			Time:           time.Now().UTC(),
			TMDBConfigured: deps.Matcher != nil && deps.Matcher.Configured(),
		}}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getServer",
		Method:      http.MethodGet,
		Path:        Prefix + "/server",
		Summary:     "Which server this is",
		Description: "No sign-in needed. Apps call it when a server is found on the network or typed in, to show its name and check its API level.",
		Tags:        []string{"system"},
	}, func(ctx context.Context, _ *struct{}) (*ServerInfoOutput, error) {
		out := &ServerInfoOutput{Body: ServerInfo{Platform: runtime.GOOS, Host: deps.Host, Version: deps.Version, APILevel: APILevel, Name: settings.DefaultServerName()}}
		if deps.Settings != nil {
			id, err := deps.Settings.ServerID(ctx)
			if err != nil {
				return nil, err
			}
			out.Body.ID, out.Body.Name = id, deps.Settings.ServerName(ctx)
		}
		if deps.Auth != nil {
			need, err := deps.Auth.SetupRequired(ctx)
			if err != nil {
				return nil, err
			}
			out.Body.SetupRequired = need
		}
		out.Body.Turnstile = deps.Turnstile.Required()
		if deps.Cast.Enabled {
			out.Body.Casting, out.Body.CastAppID = true, deps.Cast.AppID
		}
		return out, nil
	})
}
