package api

import (
	"context"
	"database/sql"
	"errors"
	"net/http"

	"github.com/danielgtaylor/huma/v2"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/upload"
)

type LiveTVCapabilities struct {
	Automatic     bool `json:"automatic,omitempty" required:"false" doc:"API level 26: shared-source live Automatic and output generations are available."`
	Recording     bool `json:"recording" required:"false" doc:"The server has recording enabled and an output folder (API level 19)."`
	CanRecord     bool `json:"canRecord" required:"false" doc:"This person may manage household recordings (API level 19)."`
	SetupComplete bool `json:"setupComplete" required:"false" doc:"A tuner has been added; a library-free server need not return to first-run setup."`
	BufferSeconds int  `json:"bufferSeconds" required:"false"`
	PauseSeconds  int  `json:"pauseSeconds" required:"false"`
	Enabled       bool `json:"enabled" doc:"At least one tuner is configured and enabled."`
	Allowed       bool `json:"allowed" doc:"This account may watch live television."`
	Playback      bool `json:"playback" doc:"Live HLS playback is available."`
	Guide         bool `json:"guide" doc:"Programme listings are on and at least one channel has them (API level 13)."`
	Cast          bool `json:"cast" required:"false" doc:"This account may cast a channel: the server issues cast tokens and a cast token may play Live TV (API level 14)."`
}
type LiveTVCapabilitiesOutput struct{ Body LiveTVCapabilities }
type LiveTVSettings struct {
	Access   string           `json:"access" enum:"none,admins,everyone"`
	Sources  []livetv.Source  `json:"sources" nullable:"false"`
	Channels []livetv.Channel `json:"channels" nullable:"false"`
}
type LiveTVSettingsOutput struct{ Body LiveTVSettings }
type LiveTVAccessInput struct {
	Body struct {
		Access string `json:"access" enum:"none,admins,everyone"`
	}
}
type LiveTVSourceInput struct {
	Body struct {
		Address string `json:"address" minLength:"1" maxLength:"255" doc:"The tuner's LAN IP or hostname; HTTP control port 80 only."`
		Name    string `json:"name,omitempty" maxLength:"128"`
	}
}
type LiveTVSourceUpdateInput struct {
	ID   string `path:"id" maxLength:"64"`
	Body struct {
		Address string `json:"address" minLength:"1" maxLength:"255"`
		Name    string `json:"name" minLength:"1" maxLength:"128"`
		Enabled bool   `json:"enabled"`
	}
}
type LiveTVIDInput struct {
	ID string `path:"id" maxLength:"64"`
}
type LiveTVChannelInput struct {
	ID   string `path:"id" maxLength:"64"`
	Body struct {
		Enabled bool `json:"enabled"`
	}
}
type LiveTVInspectionOutput struct{ Body livetv.Inspection }
type LiveTVSourceOutput struct{ Body livetv.Source }
type LiveTVChannelsOutput struct {
	Body struct {
		Items []livetv.Channel `json:"items" nullable:"false"`
	}
}

func liveError(err error) error {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, livetv.ErrOwner):
		return huma.Error404NotFound(err.Error())
	case errors.Is(err, livetv.ErrRequest):
		return huma.Error409Conflict(err.Error())
	case errors.Is(err, livetv.ErrStaleLiveSwitch):
		return huma.Error409Conflict(err.Error())
	case errors.Is(err, livetv.ErrQuality):
		return huma.Error422UnprocessableEntity(err.Error())
	case errors.Is(err, livetv.ErrProfile):
		return huma.Error422UnprocessableEntity(err.Error())
	case errors.Is(err, upload.ErrLimit):
		return huma.Error429TooManyRequests("remote upload limit is full; stop another stream or choose a lower Live TV quality")
	case errors.Is(err, playback.ErrTooManySessions):
		return huma.Error503ServiceUnavailable("server_busy")
	case errors.Is(err, playback.ErrStorageBudget):
		return huma.Error503ServiceUnavailable("storage_busy")
	case errors.Is(err, hdhomerun.Busy):
		return huma.Error409Conflict("tuners_busy")
	case errors.Is(err, sql.ErrNoRows), errors.Is(err, livetv.ErrMissing):
		return huma.Error404NotFound("source or channel not found")
	case errors.Is(err, livetv.ErrConflict), errors.Is(err, hdhomerun.ErrIdentity):
		return huma.Error409Conflict(err.Error())
	case errors.Is(err, livetv.ErrDisabled):
		return huma.Error403Forbidden(err.Error())
	case errors.Is(err, hdhomerun.ErrOffline):
		return huma.Error503ServiceUnavailable(err.Error())
	case errors.Is(err, hdhomerun.ErrAddress), errors.Is(err, hdhomerun.ErrDevice), errors.Is(err, hdhomerun.ErrLineup):
		return huma.Error422UnprocessableEntity(err.Error())
	default:
		return err
	}
}

func registerLiveTV(h huma.API, deps Deps) {
	op := func(id, method, path, summary string, admin bool) huma.Operation {
		o := huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary, Tags: []string{"live-tv"}}
		if admin {
			return adminOp(h, o)
		}
		return o
	}
	available := func() error {
		if deps.LiveTV == nil {
			return huma.Error503ServiceUnavailable("Live TV is unavailable")
		}
		return nil
	}
	huma.Register(h, op("getLiveTVCapabilities", "GET", "/live-tv/capabilities", "Live TV access and capabilities", false), func(ctx context.Context, _ *struct{}) (*LiveTVCapabilitiesOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		out := LiveTVCapabilitiesOutput{}
		out.Body.Playback = deps.LiveSessions != nil
		out.Body.Automatic = deps.LiveSessions != nil && deps.LiveSessions.SupportsAutomatic()
		out.Body.BufferSeconds = livetv.SegmentSeconds * livetv.WindowSegments
		out.Body.PauseSeconds = int(livetv.PauseAllowance.Seconds())
		if deps.LiveTV != nil {
			out.Body.SetupComplete = deps.LiveTV.SetupComplete(ctx)
			sources, err := deps.LiveTV.Sources(ctx)
			if err != nil {
				return nil, err
			}
			for _, s := range sources {
				out.Body.Enabled = out.Body.Enabled || s.Enabled
			}
			out.Body.Allowed = deps.LiveTV.Allowed(ctx, u.IsAdmin)
			out.Body.Guide = deps.Guide != nil && deps.Guide.Available(ctx)
		}
		if deps.Recordings != nil {
			out.Body.Recording = deps.Recordings.Available(ctx)
			out.Body.CanRecord = out.Body.Recording && deps.Recordings.Allowed(ctx, liveOwner(u))
		}
		out.Body.Cast = deps.Cast.Enabled && out.Body.Playback && out.Body.Allowed
		return &out, nil
	})
	huma.Register(h, op("getLiveTVSettings", "GET", "/settings/live-tv", "Live TV tuners, channels and access", true), func(ctx context.Context, _ *struct{}) (*LiveTVSettingsOutput, error) {
		if err := available(); err != nil {
			return nil, err
		}
		sources, err := deps.LiveTV.Sources(ctx)
		if err != nil {
			return nil, err
		}
		channels, err := deps.LiveTV.Channels(ctx, true)
		if err != nil {
			return nil, err
		}
		return &LiveTVSettingsOutput{Body: LiveTVSettings{Access: deps.LiveTV.Access(ctx), Sources: sources, Channels: channels}}, nil
	})
	huma.Register(h, op("setLiveTVAccess", "PUT", "/settings/live-tv", "Choose who can watch Live TV", true), func(ctx context.Context, in *LiveTVAccessInput) (*struct{}, error) {
		if err := available(); err != nil {
			return nil, err
		}
		return nil, liveError(deps.LiveTV.SetAccess(ctx, in.Body.Access))
	})
	huma.Register(h, op("testLiveTVSource", "POST", "/settings/live-tv/test", "Test a tuner without opening a channel", true), func(ctx context.Context, in *LiveTVSourceInput) (*LiveTVInspectionOutput, error) {
		if err := requireConfirmed(ctx, deps, "testLiveTVSource", nil); err != nil {
			return nil, err
		}
		if err := available(); err != nil {
			return nil, err
		}
		inspection, err := deps.LiveTV.Inspect(ctx, in.Body.Address)
		return &LiveTVInspectionOutput{Body: inspection}, liveError(err)
	})
	huma.Register(h, op("createLiveTVSource", "POST", "/settings/live-tv/sources", "Add a tuner and import its existing lineup", true), func(ctx context.Context, in *LiveTVSourceInput) (*LiveTVSourceOutput, error) {
		if err := requireConfirmed(ctx, deps, "createLiveTVSource", nil); err != nil {
			return nil, err
		}
		if err := available(); err != nil {
			return nil, err
		}
		source, err := deps.LiveTV.Add(ctx, in.Body.Address, in.Body.Name)
		if err == nil {
			setAdminAuditTarget(ctx, source.ID)
		}
		return &LiveTVSourceOutput{Body: source}, liveError(err)
	})
	huma.Register(h, op("updateLiveTVSource", "PUT", "/settings/live-tv/sources/{id}", "Update a tuner, verifying identity at a new address", true), func(ctx context.Context, in *LiveTVSourceUpdateInput) (*LiveTVSourceOutput, error) {
		old, err := deps.Queries.GetLiveSource(ctx, in.ID)
		if err != nil {
			return nil, liveError(err)
		}
		address, err := hdhomerun.New(in.Body.Address, nil)
		if err != nil {
			return nil, liveError(err)
		}
		if address.Address() != old.Address {
			if err := requireConfirmed(ctx, deps, "updateLiveTVSource", in.ID); err != nil {
				return nil, err
			}
		}
		if err := available(); err != nil {
			return nil, err
		}
		source, err := deps.LiveTV.Update(ctx, in.ID, in.Body.Address, in.Body.Name, in.Body.Enabled)
		return &LiveTVSourceOutput{Body: source}, liveError(err)
	})
	huma.Register(h, op("refreshLiveTVSource", "POST", "/settings/live-tv/sources/{id}/refresh", "Refresh the lineup without starting an RF scan", true), func(ctx context.Context, in *LiveTVIDInput) (*LiveTVSourceOutput, error) {
		if err := available(); err != nil {
			return nil, err
		}
		source, err := deps.LiveTV.Refresh(ctx, in.ID)
		return &LiveTVSourceOutput{Body: source}, liveError(err)
	})
	huma.Register(h, op("deleteLiveTVSource", "DELETE", "/settings/live-tv/sources/{id}", "Remove a tuner from this server", true), func(ctx context.Context, in *LiveTVIDInput) (*struct{}, error) {
		if err := available(); err != nil {
			return nil, err
		}
		return nil, liveError(deps.LiveTV.Remove(ctx, in.ID))
	})
	huma.Register(h, op("enableLiveTVChannel", "PUT", "/settings/live-tv/channels/{id}", "Show or hide a channel", true), func(ctx context.Context, in *LiveTVChannelInput) (*struct{}, error) {
		if err := available(); err != nil {
			return nil, err
		}
		return nil, liveError(deps.LiveTV.EnableChannel(ctx, in.ID, in.Body.Enabled))
	})
	huma.Register(h, op("listLiveTVChannels", http.MethodGet, "/live-tv/channels", "Channels available to this viewer", false), func(ctx context.Context, _ *struct{}) (*LiveTVChannelsOutput, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if err := available(); err != nil {
			return nil, err
		}
		if !deps.LiveTV.Allowed(ctx, u.IsAdmin) {
			return nil, liveError(livetv.ErrDisabled)
		}
		channels, err := deps.LiveTV.Channels(ctx, false)
		if err != nil {
			return nil, err
		}
		if deps.Guide != nil {
			if err := deps.Guide.Decorate(ctx, channels); err != nil {
				return nil, err
			}
			if err := markFavourites(ctx, deps, u.ID, channels); err != nil {
				return nil, err
			}
		}
		out := LiveTVChannelsOutput{}
		var ps []*livetv.Programme
		for i := range channels {
			if channels[i].Now != nil {
				ps = append(ps, channels[i].Now)
			}
			if channels[i].Next != nil {
				ps = append(ps, channels[i].Next)
			}
		}
		if err := recordingMarks(ctx, deps, ps); err != nil {
			return nil, err
		}
		out.Body.Items = channels
		return &out, nil
	})
}
