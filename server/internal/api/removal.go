package api

import (
	"context"
	"database/sql"
	"net/http"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"watchmymedia/server/internal/db"
)

// The numbers Settings shows before something is removed or shortened, so
// the dialog can say what goes (PLAN-ADMIN-SETTINGS.md). They only read.

// LibraryRemoval is what removing a library takes with it.
type LibraryRemoval struct {
	Movies     int64 `json:"movies" doc:"Movies indexed from it."`
	Shows      int64 `json:"shows" doc:"Shows indexed from it."`
	Episodes   int64 `json:"episodes" doc:"Episodes of its shows."`
	Files      int64 `json:"files" doc:"Video files indexed from it, present or missing; trailer files go too but are not counted, as in the library's own counts. The files on disk are not touched."`
	Watched    int64 `json:"watched" doc:"Watched marks on its movies and episodes, everyone's; they are removed with them."`
	InProgress int64 `json:"inProgress" doc:"Resume points on its movies and episodes, everyone's; they are removed with them."`
	People     int64 `json:"people" doc:"People whose watched marks or resume points are removed."`
	PlaysKept  int64 `json:"playsKept" doc:"Plays of its files in Activity history. Removing the library keeps them: history keeps their titles and pictures."`
}

type LibraryRemovalOutput struct{ Body LibraryRemoval }

// LiveTVSourceRemoval is what removing a tuner affects.
type LiveTVSourceRemoval struct {
	ScheduledRecordings int64 `json:"scheduledRecordings" doc:"Recordings still to come on this tuner's channels: scheduled, waiting on a clash, or on a channel gone from its lineup. They won't record unless another tuner has a channel with the same number, or this one is added again."`
	RecordingNow        int64 `json:"recordingNow" doc:"Recordings from this tuner running now."`
}

type LiveTVSourceRemovalOutput struct{ Body LiveTVSourceRemoval }

type PlaysOlderThanInput struct {
	Days int `query:"days" required:"true" minimum:"1" maximum:"36500" doc:"A Keep history for limit, in days, as Settings.activity.retentionDays takes it."`
}

type PlaysOlderThan struct {
	Plays int64 `json:"plays" doc:"Plays the next trim would delete with that limit: finished plays that started before the day that many days ago, in the server's time zone. Statistics keep them unless Trim statistics is on."`
}

type PlaysOlderThanOutput struct{ Body PlaysOlderThan }

func registerRemovalCounts(h huma.API, deps Deps) {
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getLibraryRemoval",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}/removal",
		Summary:     "What removing a library would remove (admin)",
		Description: "Counts for the dialog before deleteLibrary. Nothing changes.",
		Tags:        []string{"libraries"},
	}), func(ctx context.Context, in *LibraryIDInput) (*LibraryRemovalOutput, error) {
		if _, err := getLibrary(ctx, deps, in.ID); err != nil {
			return nil, err
		}
		// One read transaction, so the counts agree with each other.
		q := deps.Queries
		if deps.Conn != nil {
			tx, err := deps.Conn.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
			if err != nil {
				return nil, err
			}
			defer func() { _ = tx.Rollback() }()
			q = q.WithTx(tx)
		}
		items, err := q.CountLibraryRemoval(ctx, in.ID)
		if err != nil {
			return nil, err
		}
		watch, err := q.CountLibraryWatchState(ctx, in.ID)
		if err != nil {
			return nil, err
		}
		out := LibraryRemoval{Movies: items.Movies, Shows: items.Shows, Episodes: items.Episodes, Files: items.Files,
			Watched: watch.Watched, InProgress: watch.InProgress, People: watch.People}
		if deps.Activity != nil {
			if out.PlaysKept, err = deps.Activity.CountLibraryPlays(ctx, in.ID); err != nil {
				return nil, err
			}
		}
		return &LibraryRemovalOutput{Body: out}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getLiveTVSourceRemoval",
		Method:      http.MethodGet,
		Path:        Prefix + "/settings/live-tv/sources/{id}/removal",
		Summary:     "What removing a tuner would affect (admin)",
		Description: "Counts for the dialog before deleteLiveTVSource. Nothing changes.",
		Tags:        []string{"live-tv"},
	}), func(ctx context.Context, in *LiveTVIDInput) (*LiveTVSourceRemovalOutput, error) {
		if _, err := deps.Queries.GetLiveSource(ctx, in.ID); err != nil {
			return nil, liveError(err)
		}
		n, err := deps.Queries.CountDVRSourceRecordings(ctx, db.CountDVRSourceRecordingsParams{SourceID: in.ID, Now: time.Now().Unix()})
		if err != nil {
			return nil, err
		}
		return &LiveTVSourceRemovalOutput{Body: LiveTVSourceRemoval{ScheduledRecordings: n.Scheduled, RecordingNow: n.Recording}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "countPlaysOlderThan",
		Method:      http.MethodGet,
		Path:        Prefix + "/settings/activity/older-than",
		Summary:     "How many plays a shorter Keep history for would delete (admin)",
		Description: "For the dialog before saving a shorter activity.retentionDays. Counts by the rule the daily trim uses; nothing changes.",
		Tags:        []string{"settings"},
	}), func(ctx context.Context, in *PlaysOlderThanInput) (*PlaysOlderThanOutput, error) {
		out := &PlaysOlderThanOutput{}
		if deps.Activity == nil {
			return out, nil
		}
		n, err := deps.Activity.CountOlderThan(ctx, in.Days)
		if err != nil {
			return nil, err
		}
		out.Body.Plays = n
		return out, nil
	})
}
