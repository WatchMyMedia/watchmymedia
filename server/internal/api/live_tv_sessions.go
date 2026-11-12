package api

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/dvr"
)

type LiveTVStartInput struct {
	Body struct {
		ChannelID            string              `json:"channelId" minLength:"1" maxLength:"64"`
		RequestID            string              `json:"requestId" minLength:"1" maxLength:"64" doc:"Retry with the same ID to retrieve the same session for two minutes after it ends; use a new ID for a deliberate retry or channel change."`
		Quality              string              `json:"quality,omitempty" enum:"auto,1080,720,480,360" doc:"Fixed output cap; defaults to 1080p. API-level-26 clients may opt into auto with a live profile and autoRecovery."`
		ClientName           string              `json:"clientName,omitempty" maxLength:"128"`
		Profile              *livetv.LiveProfile `json:"profile,omitempty" required:"false" doc:"Optional negotiated live HLS capabilities from API level 25; older clients omit it and retain H.264/AAC conversion."`
		Adaptive             bool                `json:"adaptive,omitempty" required:"false" doc:"API level 27: accept experimental adaptive live HLS with Automatic; server opt-in required."`
		AutoRecovery         bool                `json:"autoRecovery,omitempty" required:"false" doc:"API level 26: explicitly opt into live Automatic recovery; requires quality auto, a profile and shared sources."`
		InitialThroughputBps int64               `json:"initialThroughputBps,omitempty" required:"false" minimum:"0" doc:"A fresh measured link estimate for the first live output; zero starts at the usual 1080p cap."`
	}
}
type LiveTVSessionOutput struct{ Body livetv.SessionView }
type LiveTVHeartbeatInput struct {
	ID   string `path:"id"`
	Body struct {
		RenditionHeight        int    `json:"renditionHeight,omitempty" minimum:"0" maximum:"4320" required:"false"`
		RenditionBitrate       int64  `json:"renditionBitrate,omitempty" minimum:"0" required:"false"`
		AdaptiveFailed         bool   `json:"adaptiveFailed,omitempty" required:"false"`
		Paused                 bool   `json:"paused"`
		Interacted             bool   `json:"interacted,omitempty" doc:"True only after viewer input, not playback progress; releases unattended viewing after four hours and one minute."`
		State                  string `json:"state,omitempty" enum:"playing,paused,buffering" required:"false" doc:"API level 26: current live player state for Automatic."`
		BufferedMs             int64  `json:"bufferedMs,omitempty" minimum:"0" required:"false"`
		ThroughputBps          int64  `json:"throughputBps,omitempty" minimum:"0" required:"false"`
		ThroughputAgeMs        *int64 `json:"throughputAgeMs,omitempty" minimum:"0" required:"false"`
		Stalls                 *int   `json:"stalls,omitempty" minimum:"0" required:"false"`
		SwitchGeneration       uint64 `json:"switchGeneration,omitempty" required:"false" doc:"Acknowledge a prepared live output after its first frame."`
		SwitchFailedGeneration uint64 `json:"switchFailedGeneration,omitempty" required:"false" doc:"Reject a prepared live output that failed to play."`
	}
}
type LiveTVActivityOutput struct {
	Body struct {
		Items      []livetv.SessionView `json:"items" nullable:"false"`
		Recordings []dvr.Recording      `json:"recordings,omitempty" required:"false" nullable:"false"`
	}
}

func liveOwner(u auth.User) livetv.Owner {
	return livetv.Owner{UserID: u.ID, SignInID: u.SignInID, Name: u.Name, Admin: u.IsAdmin}
}
func registerLiveTVSessions(h huma.API, r chi.Router, deps Deps) {
	op := func(id, method, path, summary string) huma.Operation {
		return huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary, Tags: []string{"live-tv"}}
	}
	viewer := func(ctx context.Context) (livetv.Owner, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return livetv.Owner{}, err
		}
		if deps.LiveSessions == nil {
			return livetv.Owner{}, huma.Error503ServiceUnavailable("Live TV playback is unavailable")
		}
		return liveOwner(u), nil
	}
	huma.Register(h, op("startLiveTV", "POST", "/live-tv/sessions", "Start a channel at the live edge"), func(ctx context.Context, in *LiveTVStartInput) (*LiveTVSessionOutput, error) {
		owner, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		if in.Body.AutoRecovery && in.Body.Quality != "auto" {
			return nil, liveError(livetv.ErrQuality)
		}
		var v livetv.SessionView
		if in.Body.Quality == "auto" && in.Body.AutoRecovery {
			start := deps.LiveSessions.StartAutomatic
			if deps.AdaptiveHLS && in.Body.Adaptive {
				start = deps.LiveSessions.StartAdaptive
			}
			v, err = start(ctx, owner, in.Body.ChannelID, in.Body.RequestID, in.Body.ClientName, in.Body.Profile, in.Body.InitialThroughputBps, remoteUpload(ctx))
		} else {
			v, err = deps.LiveSessions.StartWithProfile(ctx, owner, in.Body.ChannelID, in.Body.Quality, in.Body.RequestID, in.Body.ClientName, in.Body.Profile)
		}
		u, _ := currentUser(ctx)
		takeoverCanCancel(ctx, deps.Recordings, u, &v)
		return &LiveTVSessionOutput{Body: v}, liveError(err)
	})
	huma.Register(h, op("getLiveTVSession", "GET", "/live-tv/sessions/{id}", "Read the state of this sign-in's live session"), func(ctx context.Context, in *LiveTVIDInput) (*LiveTVSessionOutput, error) {
		owner, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		v, err := deps.LiveSessions.Status(ctx, owner, in.ID)
		u, _ := currentUser(ctx)
		takeoverCanCancel(ctx, deps.Recordings, u, &v)
		return &LiveTVSessionOutput{Body: v}, liveError(err)
	})
	huma.Register(h, op("heartbeatLiveTV", "PUT", "/live-tv/sessions/{id}", "Keep a live session while playing or briefly paused"), func(ctx context.Context, in *LiveTVHeartbeatInput) (*LiveTVSessionOutput, error) {
		owner, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		paced := false
		if deps.Upload != nil && remoteUpload(ctx) {
			snapshot := deps.Upload.Snapshot()
			paced = snapshot.Enforcing && snapshot.ConfiguredBps > 0
		}
		v, err := deps.LiveSessions.HeartbeatAutomatic(ctx, owner, in.ID, in.Body.Paused, in.Body.Interacted,
			livetv.LiveMeasurement{AdaptiveFailed: in.Body.AdaptiveFailed, RenditionHeight: in.Body.RenditionHeight, RenditionBitrate: in.Body.RenditionBitrate, State: in.Body.State, BufferedMs: in.Body.BufferedMs,
				ThroughputBps: in.Body.ThroughputBps, ThroughputAgeMs: in.Body.ThroughputAgeMs,
				Stalls: in.Body.Stalls, SwitchGeneration: in.Body.SwitchGeneration,
				SwitchFailedGeneration: in.Body.SwitchFailedGeneration, ServerPaced: paced, Remote: remoteUpload(ctx)})
		u, _ := currentUser(ctx)
		takeoverCanCancel(ctx, deps.Recordings, u, &v)
		return &LiveTVSessionOutput{Body: v}, liveError(err)
	})
	huma.Register(h, op("stopLiveTV", "DELETE", "/live-tv/sessions/{id}", "Release this sign-in's tuner and encoder before returning"), func(ctx context.Context, in *LiveTVIDInput) (*struct{}, error) {
		owner, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		err = deps.LiveSessions.Stop(owner, in.ID)
		if errors.Is(err, livetv.ErrOwner) {
			return nil, nil
		}
		return nil, liveError(err)
	})
	huma.Register(h, op("getLiveTVActivity", "GET", "/live-tv/activity", "Live TV sessions, separate from file history"), func(ctx context.Context, _ *struct{}) (*LiveTVActivityOutput, error) {
		owner, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		// Admins read everyone's from any network, as with other activity;
		// stopping someone's needs an admin network (stopLiveTVAsAdmin).
		out := LiveTVActivityOutput{}
		out.Body.Items = deps.LiveSessions.Activity(owner, owner.Admin)
		if deps.Recordings != nil && deps.Recordings.Allowed(ctx, owner) {
			u, _ := currentUser(ctx)
			a := recordingActor(ctx, u)
			out.Body.Recordings = deps.Recordings.Activity()
			for i := range out.Body.Recordings {
				r := &out.Body.Recordings[i]
				r.CanCancel = ownsRecording(a, r.UserID)
			}
		}
		return &out, nil
	})
	huma.Register(h, adminOp(h, op("stopLiveTVAsAdmin", "DELETE", "/live-tv/activity/{id}", "Stop a specific Live TV viewer")), func(ctx context.Context, in *LiveTVIDInput) (*struct{}, error) {
		if _, err := viewer(ctx); err != nil {
			return nil, err
		}
		if deps.Recordings != nil {
			if _, err := deps.Recordings.Get(ctx, in.ID, livetv.Owner{Admin: true}); err == nil {
				return nil, recordingError(deps.Recordings.Cancel(ctx, in.ID, livetv.Owner{Admin: true}))
			}
		}
		err := deps.LiveSessions.AdminStop(in.ID)
		if errors.Is(err, livetv.ErrOwner) {
			return nil, nil
		}
		return nil, liveError(err)
	})
	serve := func(w http.ResponseWriter, r *http.Request) {
		owner, err := viewer(r.Context())
		if err != nil {
			http.Error(w, "sign in required", http.StatusUnauthorized)
			return
		}
		name := chi.URLParam(r, "name")
		file, err := deps.LiveSessions.Media(r.Context(), owner, chi.URLParam(r, "id"), name)
		if err != nil {
			status := http.StatusNotFound
			if errors.Is(err, livetv.ErrDisabled) {
				status = http.StatusForbidden
			}
			http.Error(w, "live media unavailable", status)
			return
		}
		defer func() { _ = file.Close() }()
		w.Header().Set("Cache-Control", "private, no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if strings.HasSuffix(name, ".m3u8") {
			b, err := io.ReadAll(io.LimitReader(file, 64<<10))
			if err != nil {
				http.Error(w, "playlist unavailable", 500)
				return
			}
			if token, ok := auth.MediaTokenFromContext(r.Context()); ok {
				b = tokenizePlaylist(b, token)
			}
			w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
			_, _ = w.Write(b) //nolint:gosec // G705: server-generated HLS, explicit non-HTML type and nosniff; only URL-escaped media tokens are added.
			return
		}
		switch {
		case strings.HasSuffix(name, ".mp4"):
			w.Header().Set("Content-Type", "video/mp4")
		case strings.HasSuffix(name, ".m4s"):
			w.Header().Set("Content-Type", "video/iso.segment")
		default:
			w.Header().Set("Content-Type", "video/mp2t")
		}
		http.ServeContent(w, r, name, time.Time{}, file)
	}
	r.Get(Prefix+"/live-tv/sessions/{id}/hls/{name}", serve)
	r.Head(Prefix+"/live-tv/sessions/{id}/hls/{name}", serve)
}

// Live media-token permissions do not cover session creation, control,
// settings or Activity. Cast tokens also start and control their sender's
// sessions (castLiveRoute).
func liveMediaPath(p string) bool {
	parts := strings.Split(strings.TrimPrefix(p, Prefix+"/live-tv/sessions/"), "/")
	return strings.HasPrefix(p, Prefix+"/live-tv/sessions/") && len(parts) == 3 && parts[0] != "" && parts[1] == "hls" && parts[2] != ""
}
