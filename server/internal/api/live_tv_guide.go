package api

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"github.com/go-chi/chi/v5"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/guide"
)

type LiveTVGuideState struct {
	Enabled       bool       `json:"enabled" doc:"Listings are turned on."`
	UpdatedAt     *time.Time `json:"updatedAt,omitempty" required:"false" doc:"When listings were last fetched."`
	Stale         bool       `json:"stale" doc:"Listings haven't been fetched for over 36 hours and may be out of date. Channels still play."`
	ListedThrough *time.Time `json:"listedThrough,omitempty" required:"false" doc:"When the last listing ends."`
}

// LiveTVGuideChannel is a channel the viewer may watch, with its listings.
type LiveTVGuideChannel struct {
	livetv.Channel
	Listings   bool               `json:"listings" doc:"The provider lists this channel; false shows No listings, and the channel still plays."`
	Programmes []livetv.Programme `json:"programmes" nullable:"false" doc:"Programmes overlapping the window, in start order. Gaps stay gaps."`
}

type LiveTVGuide struct {
	Start    time.Time            `json:"start"`
	End      time.Time            `json:"end"`
	State    LiveTVGuideState     `json:"state"`
	Channels []LiveTVGuideChannel `json:"channels" nullable:"false"`
}
type LiveTVGuideOutput struct{ Body LiveTVGuide }
type LiveTVGuideInput struct {
	Start time.Time `query:"start" required:"false" doc:"Where the window starts; when left out, the last half hour."`
	Hours int       `query:"hours" minimum:"1" maximum:"6" default:"3" doc:"How long the window is."`
}

type LiveTVGuideSettingsOutput struct{ Body guide.Settings }
type LiveTVGuideSettingsInput struct {
	Body struct {
		Enabled bool   `json:"enabled"`
		TMDB    bool   `json:"tmdb"`
		Email   string `json:"email,omitempty" required:"false" maxLength:"254" doc:"Empty to send DeviceAuth only."`
	}
}
type LiveTVGuideStatusOutput struct{ Body guide.Status }
type LiveTVGuideChannelInput struct {
	ID   string `path:"id" maxLength:"64"`
	Body struct {
		Listings bool `json:"listings"`
	}
}

func guideError(err error) error {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, guide.ErrOff):
		return huma.Error409Conflict(err.Error())
	case errors.Is(err, guide.ErrLimit):
		return huma.Error429TooManyRequests(err.Error())
	case errors.Is(err, guide.ErrMissing):
		return huma.Error404NotFound(err.Error())
	case errors.Is(err, guide.ErrEmail), errors.Is(err, guide.ErrReminders):
		return huma.Error422UnprocessableEntity(err.Error())
	case errors.Is(err, guide.ErrProgramme):
		return huma.Error404NotFound(err.Error())
	case errors.Is(err, guide.ErrStarted):
		return huma.Error409Conflict(err.Error())
	default:
		return err
	}
}

func registerLiveTVGuide(h huma.API, r chi.Router, deps Deps) {
	op := func(id, method, path, summary string, admin bool) huma.Operation {
		o := huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary, Tags: []string{"live-tv"}}
		if admin {
			return adminOp(h, o)
		}
		return o
	}
	available := func() error {
		if deps.Guide == nil {
			return huma.Error503ServiceUnavailable("Live TV listings are unavailable")
		}
		return nil
	}
	huma.Register(h, op("getLiveTVGuide", http.MethodGet, "/live-tv/guide", "Listings for the viewer's channels over a window of up to 6 hours", false), func(ctx context.Context, in *LiveTVGuideInput) (*LiveTVGuideOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if deps.LiveTV == nil {
			return nil, huma.Error503ServiceUnavailable("Live TV is unavailable")
		}
		if !deps.LiveTV.Allowed(ctx, u.IsAdmin) {
			return nil, liveError(livetv.ErrDisabled)
		}
		start := in.Start
		if start.IsZero() {
			start = time.Now().Truncate(30 * time.Minute)
		}
		start = start.UTC()
		out := LiveTVGuideOutput{Body: LiveTVGuide{Start: start, End: start.Add(time.Duration(in.Hours) * time.Hour), Channels: []LiveTVGuideChannel{}}}
		channels, err := deps.LiveTV.Channels(ctx, false)
		if err != nil {
			return nil, err
		}
		info := map[string]guide.Info{}
		window := map[string][]livetv.Programme{}
		if deps.Guide != nil {
			state, err := deps.Guide.State(ctx)
			if err != nil {
				return nil, err
			}
			out.Body.State = LiveTVGuideState{Enabled: state.Enabled, UpdatedAt: state.UpdatedAt, Stale: state.Stale, ListedThrough: state.ListedThrough}
			if info, err = deps.Guide.Channels(ctx); err != nil {
				return nil, err
			}
			ids := make([]string, 0, len(channels))
			for _, c := range channels {
				ids = append(ids, c.ID)
			}
			if window, err = deps.Guide.Window(ctx, ids, out.Body.Start, out.Body.End); err != nil {
				return nil, err
			}
		}
		reminded := map[string]bool{}
		if deps.Guide != nil {
			if err := markFavourites(ctx, deps, u.ID, channels); err != nil {
				return nil, err
			}
			if reminded, err = remindedProgrammes(ctx, deps, u.ID); err != nil {
				return nil, err
			}
		}
		for _, c := range channels {
			c.LogoURL = info[c.ID].LogoURL
			programmes := window[c.ID]
			if programmes == nil {
				programmes = []livetv.Programme{}
			}
			for i := range programmes {
				programmes[i].Reminder = reminded[programmes[i].ID]
			}
			out.Body.Channels = append(out.Body.Channels, LiveTVGuideChannel{Channel: c, Listings: info[c.ID].Listings, Programmes: programmes})
		}
		var ps []*livetv.Programme
		for i := range out.Body.Channels {
			for j := range out.Body.Channels[i].Programmes {
				ps = append(ps, &out.Body.Channels[i].Programmes[j])
			}
		}
		if err := recordingMarks(ctx, deps, ps); err != nil {
			return nil, err
		}
		return &out, nil
	})
	huma.Register(h, op("getLiveTVGuideSettings", http.MethodGet, "/settings/live-tv/guide", "Listings: on or off, their status and each channel's match", true), func(ctx context.Context, _ *struct{}) (*LiveTVGuideSettingsOutput, error) {
		if err := available(); err != nil {
			return nil, err
		}
		s, err := deps.Guide.Settings(ctx)
		return &LiveTVGuideSettingsOutput{Body: s}, err
	})
	huma.Register(h, op("setLiveTVGuideSettings", http.MethodPut, "/settings/live-tv/guide", "Turn listings on or off; turning them off deletes them and their pictures", true), func(ctx context.Context, in *LiveTVGuideSettingsInput) (*LiveTVGuideSettingsOutput, error) {
		if err := available(); err != nil {
			return nil, err
		}
		if err := deps.Guide.Configure(ctx, in.Body.Enabled, in.Body.TMDB, in.Body.Email); err != nil {
			return nil, guideError(err)
		}
		s, err := deps.Guide.Settings(ctx)
		return &LiveTVGuideSettingsOutput{Body: s}, err
	})
	refresh := op("refreshLiveTVGuide", http.MethodPost, "/settings/live-tv/guide/refresh", "Fetch listings now, at most 4 times a day", true)
	refresh.DefaultStatus = http.StatusAccepted
	refresh.Description = "The fetch runs in the background; the settings' status says how it went. 409 while listings are off, 429 after 4 in a day."
	huma.Register(h, refresh, func(ctx context.Context, _ *struct{}) (*LiveTVGuideStatusOutput, error) {
		if err := available(); err != nil {
			return nil, err
		}
		if err := deps.Guide.RefreshNow(ctx); err != nil {
			return nil, guideError(err)
		}
		st, err := deps.Guide.Status(ctx)
		return &LiveTVGuideStatusOutput{Body: st}, err
	})
	huma.Register(h, op("setLiveTVGuideChannel", http.MethodPut, "/settings/live-tv/guide/channels/{id}", "Show or hide one channel's listings", true), func(ctx context.Context, in *LiveTVGuideChannelInput) (*struct{}, error) {
		if err := available(); err != nil {
			return nil, err
		}
		return nil, guideError(deps.Guide.SetChannelListings(ctx, in.ID, in.Body.Listings))
	})
	if deps.Guide != nil {
		r.Get(Prefix+"/live-tv/art/{id}", deps.Guide.Art.Handler())
	}
}
