package api

import (
	"context"
	"net/http"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/guide"
)

// A viewer's own Live TV: favourite channels, searching the listings and
// reminders (PLAN-LIVE-TV-GUIDE.md §5.7, G6, API level 15).

type LiveTVFavouriteInput struct {
	ID string `path:"id" maxLength:"64" doc:"The channel's id."`
}

type LiveTVSearchInput struct {
	Q     string `query:"q" minLength:"2" maxLength:"100" doc:"Words to find in programmes' titles and episode titles."`
	Limit int    `query:"limit" minimum:"1" maximum:"50" default:"50"`
}

// LiveTVSearchHit is a programme search found and the channel it is on.
type LiveTVSearchHit struct {
	Channel   livetv.Channel   `json:"channel"`
	Programme livetv.Programme `json:"programme"`
}

type LiveTVSearchOutput struct {
	Body struct {
		Items []LiveTVSearchHit `json:"items" nullable:"false" doc:"On now and later, soonest first."`
	}
}

// LiveTVReminder is a reminder for a programme. It keeps its own copy of
// the programme, since the listings it was set from are replaced every day.
type LiveTVReminder struct {
	ID           string    `json:"id"`
	ChannelID    string    `json:"channelId,omitempty" required:"false" doc:"The viewer's channel with this number; absent when no such channel is available now."`
	Number       string    `json:"number"`
	ChannelName  string    `json:"channelName,omitempty" required:"false"`
	ProgrammeID  string    `json:"programmeId" doc:"The listing it is for, as in the guide."`
	Start        time.Time `json:"start"`
	End          time.Time `json:"end"`
	Title        string    `json:"title"`
	EpisodeTitle string    `json:"episodeTitle,omitempty" required:"false"`
	Season       *int      `json:"season,omitempty" required:"false"`
	Episode      *int      `json:"episode,omitempty" required:"false"`
	State        string    `json:"state" doc:"set; moved, when a refresh moved the programme to start; or gone, when it is no longer in the listings and nothing should notify. Other values may follow."`
}

type LiveTVReminderOutput struct{ Body LiveTVReminder }

type LiveTVRemindersOutput struct {
	Body struct {
		Items       []LiveTVReminder `json:"items" nullable:"false" doc:"Those that haven't ended, soonest first."`
		LeadMinutes int              `json:"leadMinutes" doc:"How long before a programme starts to remind the viewer: their liveTVReminderMinutes preference."`
	}
}

type LiveTVRemindInput struct {
	Body struct {
		ProgrammeID string `json:"programmeId" minLength:"1" maxLength:"64" doc:"A programme from the guide or search that hasn't started."`
	}
}

type LiveTVReminderIDInput struct {
	ID string `path:"id" maxLength:"64"`
}

func registerLiveTVPersonal(h huma.API, deps Deps) {
	op := func(id, method, path, summary string) huma.Operation {
		return huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary, Tags: []string{"live-tv"}}
	}
	// viewer is the signed-in person, when they may watch Live TV and
	// listings exist on this server.
	viewer := func(ctx context.Context) (int64, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return 0, err
		}
		if deps.LiveTV == nil || deps.Guide == nil {
			return 0, huma.Error503ServiceUnavailable("Live TV is unavailable")
		}
		if !deps.LiveTV.Allowed(ctx, u.IsAdmin) {
			return 0, liveError(livetv.ErrDisabled)
		}
		return u.ID, nil
	}
	channel := func(ctx context.Context, id string) (livetv.Channel, error) {
		channels, err := deps.LiveTV.Channels(ctx, false)
		if err != nil {
			return livetv.Channel{}, err
		}
		for _, c := range channels {
			if c.ID == id {
				return c, nil
			}
		}
		return livetv.Channel{}, huma.Error404NotFound("channel not found")
	}
	favourite := func(on bool) func(context.Context, *LiveTVFavouriteInput) (*struct{}, error) {
		return func(ctx context.Context, in *LiveTVFavouriteInput) (*struct{}, error) {
			userID, err := viewer(ctx)
			if err != nil {
				return nil, err
			}
			c, err := channel(ctx, in.ID)
			if err != nil {
				return nil, err
			}
			return nil, deps.Guide.SetFavourite(ctx, userID, c.Number, on)
		}
	}
	huma.Register(h, op("addLiveTVFavourite", http.MethodPut, "/live-tv/channels/{id}/favourite", "Star a channel for the signed-in viewer"), favourite(true))
	huma.Register(h, op("removeLiveTVFavourite", http.MethodDelete, "/live-tv/channels/{id}/favourite", "Unstar a channel for the signed-in viewer"), favourite(false))

	huma.Register(h, op("searchLiveTVGuide", http.MethodGet, "/live-tv/guide/search", "Find programmes on now and later by title"), func(ctx context.Context, in *LiveTVSearchInput) (*LiveTVSearchOutput, error) {
		userID, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		channels, err := deps.LiveTV.Channels(ctx, false)
		if err != nil {
			return nil, err
		}
		if err := deps.Guide.Decorate(ctx, channels); err != nil {
			return nil, err
		}
		if err := markFavourites(ctx, deps, userID, channels); err != nil {
			return nil, err
		}
		byID := make(map[string]livetv.Channel, len(channels))
		ids := make([]string, 0, len(channels))
		for _, c := range channels {
			// The hit's channel names it; what is on now and next is the
			// programme's business.
			c.Now, c.Next = nil, nil
			byID[c.ID] = c
			ids = append(ids, c.ID)
		}
		hits, err := deps.Guide.Search(ctx, ids, in.Q, in.Limit)
		if err != nil {
			return nil, err
		}
		reminded, err := remindedProgrammes(ctx, deps, userID)
		if err != nil {
			return nil, err
		}
		out := LiveTVSearchOutput{}
		out.Body.Items = make([]LiveTVSearchHit, 0, len(hits))
		for _, hit := range hits {
			hit.Programme.Reminder = reminded[hit.Programme.ID]
			out.Body.Items = append(out.Body.Items, LiveTVSearchHit{Channel: byID[hit.ChannelID], Programme: hit.Programme})
		}
		var ps []*livetv.Programme
		for i := range out.Body.Items {
			ps = append(ps, &out.Body.Items[i].Programme)
		}
		if err := recordingMarks(ctx, deps, ps); err != nil {
			return nil, err
		}
		return &out, nil
	})

	reminders := func(ctx context.Context, userID int64) (*LiveTVRemindersOutput, error) {
		rows, err := deps.Guide.Reminders(ctx, userID)
		if err != nil {
			return nil, err
		}
		names, err := channelsByNumber(ctx, deps)
		if err != nil {
			return nil, err
		}
		prefs, err := loadPreferences(ctx, deps, userID)
		if err != nil {
			return nil, err
		}
		out := LiveTVRemindersOutput{}
		out.Body.LeadMinutes = prefs.LiveTVReminderMinutes
		out.Body.Items = make([]LiveTVReminder, 0, len(rows))
		for _, r := range rows {
			out.Body.Items = append(out.Body.Items, reminderView(r, names))
		}
		return &out, nil
	}
	huma.Register(h, op("listLiveTVReminders", http.MethodGet, "/live-tv/reminders", "The signed-in viewer's reminders that haven't ended"), func(ctx context.Context, _ *struct{}) (*LiveTVRemindersOutput, error) {
		userID, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		return reminders(ctx, userID)
	})
	huma.Register(h, op("setLiveTVReminder", http.MethodPut, "/live-tv/reminders", "Remind the signed-in viewer before a programme starts"), func(ctx context.Context, in *LiveTVRemindInput) (*LiveTVReminderOutput, error) {
		userID, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		r, err := deps.Guide.Remind(ctx, userID, in.Body.ProgrammeID)
		if err != nil {
			return nil, guideError(err)
		}
		names, err := channelsByNumber(ctx, deps)
		if err != nil {
			return nil, err
		}
		return &LiveTVReminderOutput{Body: reminderView(r, names)}, nil
	})
	huma.Register(h, op("deleteLiveTVReminder", http.MethodDelete, "/live-tv/reminders/{id}", "Remove one of the signed-in viewer's reminders"), func(ctx context.Context, in *LiveTVReminderIDInput) (*struct{}, error) {
		userID, err := viewer(ctx)
		if err != nil {
			return nil, err
		}
		return nil, deps.Guide.Unremind(ctx, userID, in.ID)
	})
}

// markFavourites sets each channel's favourite for the viewer.
func markFavourites(ctx context.Context, deps Deps, userID int64, channels []livetv.Channel) error {
	favourites, err := deps.Guide.Favourites(ctx, userID)
	if err != nil {
		return err
	}
	for i := range channels {
		channels[i].Favourite = favourites[channels[i].Number]
	}
	return nil
}

// remindedProgrammes are the programme ids the viewer has a reminder for.
func remindedProgrammes(ctx context.Context, deps Deps, userID int64) (map[string]bool, error) {
	rows, err := deps.Guide.Reminders(ctx, userID)
	if err != nil {
		return nil, err
	}
	out := make(map[string]bool, len(rows))
	for _, r := range rows {
		if r.State != guide.ReminderGone {
			out[r.ProgrammeID] = true
		}
	}
	return out, nil
}

// channelsByNumber are the channels anyone may watch, by number, the first
// in channel order where two share one.
func channelsByNumber(ctx context.Context, deps Deps) (map[string]livetv.Channel, error) {
	channels, err := deps.LiveTV.Channels(ctx, false)
	if err != nil {
		return nil, err
	}
	out := make(map[string]livetv.Channel, len(channels))
	for _, c := range channels {
		if _, ok := out[c.Number]; !ok {
			out[c.Number] = c
		}
	}
	return out, nil
}

func reminderView(r db.LiveTvReminder, channels map[string]livetv.Channel) LiveTVReminder {
	v := LiveTVReminder{
		ID: r.ID, Number: r.Number, ProgrammeID: r.ProgrammeID, Start: time.Unix(r.StartAt, 0).UTC(), End: time.Unix(r.EndAt, 0).UTC(),
		Title: r.Title, EpisodeTitle: r.EpisodeTitle, Season: intPtr(r.Season), Episode: intPtr(r.Episode), State: r.State,
	}
	if c, ok := channels[r.Number]; ok {
		v.ChannelID, v.ChannelName = c.ID, c.Name
	}
	return v
}

func intPtr(n *int64) *int {
	if n == nil {
		return nil
	}
	v := int(*n)
	return &v
}
