package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/danielgtaylor/huma/v2"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/netguard"
)

type LiveTVRecordingsInput struct {
	Section string `query:"section" default:"upcoming" enum:"now,conflicts,upcoming,series,recorded"`
	Cursor  string `query:"cursor" maxLength:"256"`
}
type LiveTVRecordingsOutput struct{ Body dvr.Recordings }
type LiveTVRecordingOutput struct{ Body dvr.Recording }
type LiveTVRecordingCreateInput struct{ Body dvr.Create }
type LiveTVRecordingManualInput struct{ Body dvr.Manual }
type LiveTVRecordingUpdateInput struct {
	ID   string `path:"id" maxLength:"64"`
	Body dvr.Update
}
type LiveTVRecordingRuleUpdateInput struct {
	ID   string `path:"id" maxLength:"64"`
	Body dvr.UpdateRule
}
type LiveTVRecordingRuleOutput struct{ Body dvr.RecordingRule }
type LiveTVRecordingSettingsOutput struct{ Body dvr.Settings }
type LiveTVRecordingSettingsInput struct{ Body dvr.Settings }
type LiveTVRecordingDeleteFileInput struct {
	ID          string `path:"id" maxLength:"64"`
	RecordAgain bool   `query:"recordAgain" default:"false"`
}

func recordingError(err error) error {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, dvr.ErrInvalid):
		return huma.Error422UnprocessableEntity(err.Error())
	case errors.Is(err, dvr.ErrMissing):
		return huma.Error404NotFound(err.Error())
	case errors.Is(err, dvr.ErrUnavailable):
		return huma.Error409Conflict(err.Error())
	case errors.Is(err, dvr.ErrDenied):
		return huma.Error403Forbidden(err.Error())
	default:
		return err
	}
}
func registerLiveTVRecordings(h huma.API, deps Deps) {
	op := func(id, method, path, summary string, admin bool) huma.Operation {
		o := huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary, Tags: []string{"live-tv"}}
		if admin {
			return adminOp(h, o)
		}
		return o
	}
	available := func() error {
		if deps.Recordings == nil {
			return huma.Error503ServiceUnavailable("Live TV recording is unavailable")
		}
		return nil
	}
	// people returns who may record (access) and who acts on recordings
	// (a). Who may record follows Live TV access, which an admin account
	// has from any network. Acting on other people's recordings and rules
	// is an admin power, so it needs an admin network as other admin powers
	// do; elsewhere an admin acts as a member (recordingActor).
	people := func(ctx context.Context) (access, a livetv.Owner, err error) {
		u, err := currentUser(ctx)
		if err != nil {
			return access, a, err
		}
		if err = available(); err != nil {
			return access, a, err
		}
		access, a = liveOwner(u), recordingActor(ctx, u)
		if !deps.Recordings.Allowed(ctx, access) {
			return access, a, recordingError(dvr.ErrDenied)
		}
		return access, a, nil
	}
	actor := func(ctx context.Context) (livetv.Owner, error) {
		_, a, err := people(ctx)
		return a, err
	}
	huma.Register(h, op("listLiveTVRecordings", "GET", "/live-tv/recordings", "A page of household recordings or recording rules", false), func(ctx context.Context, in *LiveTVRecordingsInput) (*LiveTVRecordingsOutput, error) {
		a, err := actor(ctx)
		if err != nil {
			return nil, err
		}
		out, err := deps.Recordings.List(ctx, a, in.Section, in.Cursor)
		var rows []*dvr.Recording
		for i := range out.Items {
			rows = append(rows, &out.Items[i])
		}
		markRecordingDeletion(ctx, deps, rows)
		return &LiveTVRecordingsOutput{Body: out}, recordingError(err)
	})
	huma.Register(h, op("getLiveTVRecording", "GET", "/live-tv/recordings/{id}", "One household recording", false), func(ctx context.Context, in *LiveTVIDInput) (*LiveTVRecordingOutput, error) {
		a, err := actor(ctx)
		if err != nil {
			return nil, err
		}
		out, err := deps.Recordings.Get(ctx, in.ID, a)
		markRecordingDeletion(ctx, deps, []*dvr.Recording{&out})
		return &LiveTVRecordingOutput{Body: out}, recordingError(err)
	})
	// Creating checks who may record itself, so it takes access; the reply
	// (which can be someone else's recording of the same programme) says
	// what a may do with it.
	huma.Register(h, op("createLiveTVRecording", "POST", "/live-tv/recordings", "Record a programme or its series", false), func(ctx context.Context, in *LiveTVRecordingCreateInput) (*LiveTVRecordingOutput, error) {
		access, a, err := people(ctx)
		if err != nil {
			return nil, err
		}
		out, err := deps.Recordings.Create(ctx, access, in.Body)
		out.CanCancel = ownsRecording(a, out.UserID)
		return &LiveTVRecordingOutput{Body: out}, recordingError(err)
	})
	huma.Register(h, op("createManualLiveTVRecording", "POST", "/live-tv/recordings/manual", "Record a channel at a time, once or repeatedly", false), func(ctx context.Context, in *LiveTVRecordingManualInput) (*LiveTVRecordingOutput, error) {
		access, a, err := people(ctx)
		if err != nil {
			return nil, err
		}
		out, err := deps.Recordings.Manual(ctx, access, in.Body)
		out.CanCancel = ownsRecording(a, out.UserID)
		return &LiveTVRecordingOutput{Body: out}, recordingError(err)
	})
	huma.Register(h, op("updateLiveTVRecording", "PATCH", "/live-tv/recordings/{id}", "Change padding or prefer this recording over a pending clash", false), func(ctx context.Context, in *LiveTVRecordingUpdateInput) (*LiveTVRecordingOutput, error) {
		a, err := actor(ctx)
		if err != nil {
			return nil, err
		}
		out, err := deps.Recordings.Update(ctx, in.ID, a, in.Body)
		return &LiveTVRecordingOutput{Body: out}, recordingError(err)
	})
	huma.Register(h, op("cancelLiveTVRecording", "DELETE", "/live-tv/recordings/{id}", "Cancel an upcoming recording or stop and keep its captured part", false), func(ctx context.Context, in *LiveTVIDInput) (*struct{}, error) {
		a, err := actor(ctx)
		if err != nil {
			return nil, err
		}
		return nil, recordingError(deps.Recordings.Cancel(ctx, in.ID, a))
	})
	huma.Register(h, op("deleteLiveTVRecordingFile", "DELETE", "/live-tv/recordings/{id}/file", "Delete only the file owned by a finished recording", false), func(ctx context.Context, in *LiveTVRecordingDeleteFileInput) (*struct{}, error) {
		if _, err := currentUser(ctx); err != nil {
			return nil, err
		}
		if err := available(); err != nil {
			return nil, err
		}
		access := "admins"
		if deps.Settings != nil {
			access, _ = deps.Settings.Get(ctx, "delete.access", "admins")
		}
		if access != "everyone" {
			if _, err := requireAdmin(ctx); err != nil {
				return nil, err
			}
		}
		return nil, recordingError(deps.Recordings.DeleteRecording(ctx, in.ID, in.RecordAgain))
	})
	huma.Register(h, op("getLiveTVRecordingRule", "GET", "/live-tv/recording-rules/{id}", "A series or repeating manual recording rule", false), func(ctx context.Context, in *LiveTVIDInput) (*LiveTVRecordingRuleOutput, error) {
		a, err := actor(ctx)
		if err != nil {
			return nil, err
		}
		out, err := deps.Recordings.Rule(ctx, in.ID, a)
		return &LiveTVRecordingRuleOutput{Body: out}, recordingError(err)
	})
	huma.Register(h, op("updateLiveTVRecordingRule", "PATCH", "/live-tv/recording-rules/{id}", "Change a series or repeating recording rule", false), func(ctx context.Context, in *LiveTVRecordingRuleUpdateInput) (*LiveTVRecordingRuleOutput, error) {
		a, err := actor(ctx)
		if err != nil {
			return nil, err
		}
		out, err := deps.Recordings.UpdateRule(ctx, in.ID, a, in.Body)
		return &LiveTVRecordingRuleOutput{Body: out}, recordingError(err)
	})
	huma.Register(h, op("deleteLiveTVRecordingRule", "DELETE", "/live-tv/recording-rules/{id}", "Delete a recording rule, keeping finished and running recordings", false), func(ctx context.Context, in *LiveTVIDInput) (*struct{}, error) {
		a, err := actor(ctx)
		if err != nil {
			return nil, err
		}
		return nil, recordingError(deps.Recordings.DeleteRule(ctx, in.ID, a))
	})
	huma.Register(h, op("getLiveTVRecordingSettings", "GET", "/settings/live-tv/recordings", "Recording folders, access, disk reserve and padding", true), func(ctx context.Context, _ *struct{}) (*LiveTVRecordingSettingsOutput, error) {
		if err := available(); err != nil {
			return nil, err
		}
		out, err := deps.Recordings.Settings(ctx)
		return &LiveTVRecordingSettingsOutput{Body: out}, err
	})
	huma.Register(h, op("setLiveTVRecordingSettings", "PUT", "/settings/live-tv/recordings", "Configure household recording (admin)", true), func(ctx context.Context, in *LiveTVRecordingSettingsInput) (*LiveTVRecordingSettingsOutput, error) {
		if err := available(); err != nil {
			return nil, err
		}
		if err := deps.Recordings.SetSettings(ctx, in.Body); err != nil {
			return nil, recordingError(err)
		}
		out, err := deps.Recordings.Settings(ctx)
		return &LiveTVRecordingSettingsOutput{Body: out}, err
	})
	_ = http.MethodGet
}

func recordingMarks(ctx context.Context, deps Deps, programmes []*livetv.Programme) error {
	if deps.Recordings == nil || len(programmes) == 0 {
		return nil
	}
	u, err := currentUser(ctx)
	if err != nil {
		return err
	}
	ids := make([]string, 0, len(programmes))
	for _, p := range programmes {
		ids = append(ids, p.ID)
	}
	// Marks follow who may record; what this person may cancel follows
	// recordingActor, which differs only for an admin off the admin network.
	marks, err := deps.Recordings.Marks(ctx, ids, liveOwner(u))
	if err != nil {
		return err
	}
	if a := recordingActor(ctx, u); a.Admin != u.IsAdmin {
		for _, m := range marks {
			if m.CanCancel {
				r, err := deps.Recordings.Get(ctx, m.ID, a)
				m.CanCancel = err == nil && r.CanCancel
			}
		}
	}
	for _, p := range programmes {
		p.Recording = marks[p.ID]
	}
	return nil
}

// recordingActor is u acting on recordings: an admin only from an admin
// network (effectiveAdmin), so an admin elsewhere may cancel, change and
// delete only their own recordings and rules, as a member may.
func recordingActor(ctx context.Context, u auth.User) livetv.Owner {
	a := liveOwner(u)
	a.Admin = effectiveAdmin(ctx, u)
	return a
}

// ownsRecording reports whether a may cancel a recording set by userID.
func ownsRecording(a livetv.Owner, userID *int64) bool {
	return a.Admin || userID != nil && *userID == a.UserID
}

// takeoverCanCancel puts right the tuner warning's Don't record it for this
// request. The live session computed it from the account's admin flag when
// the warning was set; an admin off the admin network may cancel only a
// recording of their own.
func takeoverCanCancel(ctx context.Context, recordings *dvr.Service, u auth.User, v *livetv.SessionView) {
	a := recordingActor(ctx, u)
	if recordings == nil || v.TakeoverRecordingID == "" || !v.TakeoverCanCancel || a.Admin == u.IsAdmin {
		return
	}
	r, err := recordings.Get(ctx, v.TakeoverRecordingID, a)
	v.TakeoverCanCancel = err == nil && r.CanCancel
}

func markRecordingDeletion(ctx context.Context, deps Deps, rows []*dvr.Recording) {
	u, err := currentUser(ctx)
	if err != nil {
		return
	}
	allowed := u.IsAdmin && netguard.AdminAllowed(ctx)
	if !allowed && deps.Settings != nil {
		access, _ := deps.Settings.Get(ctx, "delete.access", "admins")
		allowed = access == "everyone"
	}
	for _, r := range rows {
		r.CanDelete = allowed && r.Reason != "finish_pending" && r.Bytes > 0 && (r.State == "done" || r.State == "partial")
	}
}
