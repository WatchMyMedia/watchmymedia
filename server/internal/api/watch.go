package api

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/watchstate"
)

// WatchState is a user's progress on a movie or episode.
type WatchState struct {
	PositionMs int64     `json:"positionMs"`
	DurationMs int64     `json:"durationMs"`
	Completed  bool      `json:"completed"`
	FileID     *int64    `json:"fileId,omitempty" doc:"Version the position refers to."`
	UpdatedAt  time.Time `json:"updatedAt"`
}

type ProgressInput struct {
	ID   int64 `path:"id" doc:"Media file ID."`
	Body ProgressInputBody
}

type ProgressInputBody struct {
	RenditionHeight  int   `json:"renditionHeight,omitempty" minimum:"0" maximum:"4320" required:"false"`
	RenditionBitrate int64 `json:"renditionBitrate,omitempty" minimum:"0" required:"false"`
	PositionMs       int64 `json:"positionMs" minimum:"0"`
	DurationMs       int64 `json:"durationMs" minimum:"0" required:"false"`
	// The play this report belongs to, for activity: players send one
	// at every state change, every 10 s while playing and every 30 s
	// while paused, and stopped (or closed, as the page unloads) at the end.
	PlayID     string `json:"playId,omitempty" pattern:"^[0-9a-f]{16}$" doc:"The playId the playback decision returned."`
	State      string `json:"state,omitempty" enum:"playing,paused,buffering,stopped,closed" doc:"Defaults to playing."`
	BufferedMs int64  `json:"bufferedMs,omitempty" minimum:"0" doc:"How far ahead of the position the player has buffered."`
	// What the player measures, for Automatic quality (PLAN-AUTO-QUALITY.md §3.1).
	ThroughputBps          int64  `json:"throughputBps,omitempty" minimum:"0" doc:"The player's own estimate of its link to the server, from its downloads, in bits per second; omit when it has none."`
	ThroughputAgeMs        *int64 `json:"throughputAgeMs,omitempty" minimum:"0" required:"false" doc:"API level 24: age in milliseconds of the latest completed media transfer used for throughputBps. Missing or stale samples never justify recovery."`
	SwitchGeneration       uint64 `json:"switchGeneration,omitempty" required:"false" doc:"API level 24: acknowledge this quality generation only after its first frame plays."`
	SwitchFailedGeneration uint64 `json:"switchFailedGeneration,omitempty" required:"false" doc:"API level 24: this quality generation failed to prepare or play; keep the previous stream when available."`
	Stalls                 *int   `json:"stalls,omitempty" minimum:"0" doc:"Rebuffers since this playback decision: buffering after the first frame that no seek, reload or resume caused. Omit when the player does not count them."`
	StallMs                int64  `json:"stallMs,omitempty" minimum:"0" doc:"Time those rebuffers took."`
	StartupMs              int64  `json:"startupMs,omitempty" minimum:"0" doc:"From asking for this decision to its first frame."`
}

// Progress is the saved state plus what the player should do next.
type Progress struct {
	WatchState
	// Directive tells the player to stop, with a message from the admin,
	// or at Automatic quality to reload at a lower step.
	Directive *activity.Directive `json:"directive,omitempty"`
	PlayID    string              `json:"playId,omitempty" doc:"Set when the play had ended (it timed out or the server restarted) and a new one carries on; send this one from now on."`
}

type ProgressOutput struct{ Body Progress }

type WatchItemInput struct {
	Type string `path:"type" enum:"movie,episode"`
	ID   int64  `path:"id"`
}

type SeasonWatchInput struct {
	ID     int64 `path:"id" doc:"Show ID."`
	Number int64 `path:"number"`
}

// ContinueItem is a row on the home screen's "Continue watching".
type ContinueItem struct {
	Kind          string    `json:"kind" enum:"movie,episode"`
	Title         string    `json:"title"`
	Subtitle      string    `json:"subtitle,omitempty"`
	PosterPath    string    `json:"posterPath,omitempty"`
	BackdropPath  string    `json:"backdropPath,omitempty"`
	StillPath     string    `json:"stillPath,omitempty"`
	FileID        int64     `json:"fileId"`
	MovieID       *int64    `json:"movieId,omitempty"`
	ShowID        *int64    `json:"showId,omitempty"`
	EpisodeID     *int64    `json:"episodeId,omitempty"`
	SeasonNumber  int64     `json:"seasonNumber,omitempty"`
	EpisodeNumber int64     `json:"episodeNumber,omitempty"`
	PositionMs    int64     `json:"positionMs"`
	DurationMs    int64     `json:"durationMs"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

type ContinueList struct {
	Items []ContinueItem `json:"items" nullable:"false"`
}

type ContinueOutput struct{ Body ContinueList }

// WatchChanged is the watch.changed event, which the watch state service
// sends to the user alone after each change commits.
type WatchChanged = watchstate.WatchChanged

func watchFromDB(w db.WatchState) WatchState {
	return WatchState{PositionMs: w.PositionMs, DurationMs: w.DurationMs, Completed: w.Completed == 1, FileID: w.MediaFileID, UpdatedAt: db.ParseTime(w.UpdatedAt)}
}

// itemsForFile resolves the movie or episodes a file belongs to. A trailer
// belongs to neither: it has no watch state of its own (PLAN-TRAILERS.md §4.6).
func itemsForFile(ctx context.Context, deps Deps, f db.MediaFile) (itemType string, ids []int64, err error) {
	if f.ExtraKind != nil {
		return "", nil, nil
	}
	if f.MovieID != nil {
		return "movie", []int64{*f.MovieID}, nil
	}
	eps, err := deps.Queries.ListEpisodeIDsForFile(ctx, f.ID)
	if err != nil {
		return "", nil, err
	}
	return "episode", eps, nil
}

// watchForFile returns the user's state for the file's item, if any.
func watchForFile(ctx context.Context, deps Deps, user auth.User, f db.MediaFile) *WatchState {
	t, ids, err := itemsForFile(ctx, deps, f)
	if err != nil || len(ids) == 0 {
		return nil
	}
	w, err := deps.Queries.GetWatchState(ctx, db.GetWatchStateParams{UserID: user.ID, ItemType: t, ItemID: ids[0]})
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			deps.Log.WarnContext(ctx, "could not read watch state", "file", f.ID, "err", err)
		}
		return nil
	}
	ws := watchFromDB(w)
	return &ws
}

// watchable checks that the movie or episode a mark is for exists, so no
// watch state is written for an ID that names nothing.
func watchable(ctx context.Context, deps Deps, itemType string, id int64) error {
	var err error
	if itemType == "movie" {
		_, err = deps.Queries.GetMovie(ctx, id)
	} else {
		_, err = deps.Queries.GetEpisode(ctx, id)
	}
	return notFound(err, itemType+" not found")
}

func registerWatch(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "reportProgress",
		Method:      http.MethodPut,
		Path:        Prefix + "/files/{id}/progress",
		Summary:     "Report the playback position of a file",
		Description: "Stores the position against the movie or episode(s) the file belongs to. Past the start of the final credits, or 92% of the duration without them, the item is marked watched. A trailer's reports are taken and not stored: it has no watch state.",
		Tags:        []string{"watch"},
	}, func(ctx context.Context, in *ProgressInput) (*ProgressOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		f, err := deps.Queries.GetMediaFile(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "file not found")
		}
		out := &ProgressOutput{}
		// A running play worked out where its item counts as watched when
		// it began. It is read before the heartbeat, which ends a stopped
		// play, and again from a play the heartbeat started in its place.
		var playWatchedAt, playDuration int64
		if deps.Activity != nil && in.Body.PlayID != "" {
			playWatchedAt, playDuration, _ = deps.Activity.WatchedAt(in.Body.PlayID, user.ID, f.ID)
			out.Body.Directive, out.Body.PlayID = heartbeat(ctx, deps, user, f, in)
			if out.Body.PlayID != "" {
				playWatchedAt, playDuration, _ = deps.Activity.WatchedAt(out.Body.PlayID, user.ID, f.ID)
			}
		}
		itemType, ids, err := itemsForFile(ctx, deps, f)
		if err != nil {
			return nil, err
		}
		if len(ids) == 0 {
			// Activity still counts plays of unmatched files. A trailer's
			// report is taken and dropped: there is no movie or episode to
			// keep it against.
			if in.Body.PlayID != "" || f.ExtraKind != nil {
				out.Body.WatchState = WatchState{PositionMs: in.Body.PositionMs, DurationMs: in.Body.DurationMs, UpdatedAt: time.Now()}
				return out, nil
			}
			return nil, huma.Error409Conflict("file is not matched to a movie or episode yet")
		}
		duration := in.Body.DurationMs
		if duration == 0 && f.DurationMs != nil {
			duration = *f.DurationMs
		}
		completed := false
		position := in.Body.PositionMs
		if duration > 0 && position >= watchedPoint(ctx, deps, f.ID, duration, playWatchedAt, playDuration) {
			completed, position = true, 0
		}
		state := in.Body.State
		last, err := deps.Watch.Report(ctx, watchstate.Progress{
			UserID: user.ID, ItemType: itemType, ItemIDs: ids, FileID: f.ID, PositionMs: position, DurationMs: duration, Completed: completed,
			Playing: in.Body.PlayID != "" && (state == "" || state == "playing" || state == "buffering"),
		})
		if err != nil {
			return nil, err
		}
		out.Body.WatchState = watchFromDB(last)
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID:   "markWatched",
		Method:        http.MethodPost,
		Path:          Prefix + "/watch/{type}/{id}/watched",
		Summary:       "Mark a movie or episode watched",
		Tags:          []string{"watch"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *WatchItemInput) (*struct{}, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if err := watchable(ctx, deps, in.Type, in.ID); err != nil {
			return nil, err
		}
		return nil, deps.Watch.MarkWatched(ctx, user.ID, in.Type, in.ID)
	})

	huma.Register(h, huma.Operation{
		OperationID:   "markUnwatched",
		Method:        http.MethodDelete,
		Path:          Prefix + "/watch/{type}/{id}/watched",
		Summary:       "Clear progress and the watched mark of a movie or episode",
		Tags:          []string{"watch"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *WatchItemInput) (*struct{}, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		return nil, deps.Watch.MarkUnwatched(ctx, user.ID, in.Type, in.ID)
	})

	huma.Register(h, huma.Operation{
		OperationID:   "markSeasonWatched",
		Method:        http.MethodPost,
		Path:          Prefix + "/shows/{id}/seasons/{number}/watched",
		Summary:       "Mark every episode of a season watched",
		Tags:          []string{"watch"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *SeasonWatchInput) (*struct{}, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		season, err := deps.Queries.GetSeasonByNumber(ctx, db.GetSeasonByNumberParams{ShowID: in.ID, Number: in.Number})
		if err != nil {
			return nil, notFound(err, "season not found")
		}
		return nil, deps.Watch.MarkSeason(ctx, user.ID, season.ID, true)
	})

	huma.Register(h, huma.Operation{
		OperationID:   "markSeasonUnwatched",
		Method:        http.MethodDelete,
		Path:          Prefix + "/shows/{id}/seasons/{number}/watched",
		Summary:       "Clear the watched marks of a season",
		Tags:          []string{"watch"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *SeasonWatchInput) (*struct{}, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		season, err := deps.Queries.GetSeasonByNumber(ctx, db.GetSeasonByNumberParams{ShowID: in.ID, Number: in.Number})
		if err != nil {
			return nil, notFound(err, "season not found")
		}
		return nil, deps.Watch.MarkSeason(ctx, user.ID, season.ID, false)
	})

	huma.Register(h, huma.Operation{
		OperationID:   "markShowWatched",
		Method:        http.MethodPost,
		Path:          Prefix + "/shows/{id}/watched",
		Summary:       "Mark every episode of a show watched",
		Description:   "Every episode TMDB lists, specials included, as marking each season does; one watch.changed event lists them all.",
		Tags:          []string{"watch"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *ShowIDInput) (*struct{}, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if _, err := deps.Queries.GetShow(ctx, in.ID); err != nil {
			return nil, notFound(err, "show not found")
		}
		return nil, deps.Watch.MarkShow(ctx, user.ID, in.ID, true)
	})

	huma.Register(h, huma.Operation{
		OperationID:   "markShowUnwatched",
		Method:        http.MethodDelete,
		Path:          Prefix + "/shows/{id}/watched",
		Summary:       "Clear the watched marks and progress of a whole show",
		Tags:          []string{"watch"},
		DefaultStatus: http.StatusNoContent,
	}, func(ctx context.Context, in *ShowIDInput) (*struct{}, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if _, err := deps.Queries.GetShow(ctx, in.ID); err != nil {
			return nil, notFound(err, "show not found")
		}
		return nil, deps.Watch.MarkShow(ctx, user.ID, in.ID, false)
	})

	huma.Register(h, huma.Operation{
		OperationID: "listContinueWatching",
		Method:      http.MethodGet,
		Path:        Prefix + "/watch/continue",
		Summary:     "Items the user is partway through, newest first",
		Tags:        []string{"watch"},
	}, func(ctx context.Context, _ *struct{}) (*ContinueOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		items, err := continueWatching(ctx, deps, user, 20)
		if err != nil {
			return nil, err
		}
		return &ContinueOutput{Body: ContinueList{Items: items}}, nil
	})
}

// watchedPoint is where an item of duration counts as watched: the play's
// point (watchedAt, worked out for playDuration) when the reported duration
// is within two seconds of the play's, as it is for the same file; else
// from the file's stored segments, which is a query.
func watchedPoint(ctx context.Context, deps Deps, fileID, duration, watchedAt, playDuration int64) int64 {
	if watchedAt > 0 && max(duration-playDuration, playDuration-duration) <= 2000 {
		return watchedAt
	}
	return playback.WatchedAt(storedSegments(ctx, deps, fileID, duration), duration)
}

// heartbeat passes a progress report to activity. A report for a play that
// has ended (it timed out, or the server restarted) starts a new play that
// carries on from it, whose ID is returned for the player to use.
func heartbeat(ctx context.Context, deps Deps, user auth.User, f db.MediaFile, in *ProgressInput) (*activity.Directive, string) {
	state := in.Body.State
	if state == "" {
		state = activity.StatePlaying
	}
	report := activity.Report{PositionMs: in.Body.PositionMs, State: state, BufferedMs: in.Body.BufferedMs,
		RenditionHeight: in.Body.RenditionHeight, RenditionBitrate: in.Body.RenditionBitrate, ThroughputBps: in.Body.ThroughputBps, ThroughputAgeMs: in.Body.ThroughputAgeMs, SwitchGeneration: in.Body.SwitchGeneration, SwitchFailedGeneration: in.Body.SwitchFailedGeneration, Stalls: -1, StallMs: in.Body.StallMs, StartupMs: in.Body.StartupMs}
	if in.Body.Stalls != nil {
		report.Stalls = *in.Body.Stalls
	}
	dir, err := deps.Activity.Heartbeat(ctx, in.Body.PlayID, user.ID, f.ID, report)
	if !errors.Is(err, activity.ErrUnknownPlay) || state == activity.StateStopped || state == activity.StateClosed {
		if err != nil {
			deps.Log.Warn("could not record heartbeat", "play", in.Body.PlayID, "err", err)
		}
		return dir, ""
	}
	start := playStart(ctx, deps, requestFrom(ctx), user, f, nil)
	start.Continues, start.PositionMs = in.Body.PlayID, in.Body.PositionMs
	if start.DurationMs > 0 {
		start.WatchedAtMs = playback.WatchedAt(storedSegments(ctx, deps, f.ID, start.DurationMs), start.DurationMs)
	}
	id, err := deps.Activity.Begin(ctx, start)
	if err != nil {
		deps.Log.Warn("could not restart play", "play", in.Body.PlayID, "err", err)
		return nil, ""
	}
	dir, _ = deps.Activity.Heartbeat(ctx, id, user.ID, f.ID, report)
	return dir, id
}

// continueWatching lists the user's unfinished movies and episodes whose
// file is still present, newest first.
func continueWatching(ctx context.Context, deps Deps, user auth.User, limit int) ([]ContinueItem, error) {
	rows, err := deps.Queries.ListContinueWatchingItems(ctx, db.ListContinueWatchingItemsParams{UserID: user.ID, Limit: int64(limit)})
	if err != nil {
		return nil, err
	}
	out := make([]ContinueItem, 0, len(rows))
	for _, w := range rows {
		item := ContinueItem{FileID: deref(w.MediaFileID), PositionMs: w.PositionMs, DurationMs: w.DurationMs, UpdatedAt: db.ParseTime(w.UpdatedAt)}
		switch w.ItemType {
		case "movie":
			id := w.ItemID
			item.Kind, item.Title, item.MovieID = "movie", deref(w.MovieTitle), &id
			item.PosterPath, item.BackdropPath = deref(w.MoviePosterPath), deref(w.MovieBackdropPath)
			if w.MovieYear != nil {
				item.Subtitle = strconv.FormatInt(*w.MovieYear, 10)
			}
		case "episode":
			epID := w.ItemID
			item.Kind, item.Title, item.ShowID, item.EpisodeID = "episode", deref(w.ShowTitle), w.ShowID, &epID
			item.SeasonNumber, item.EpisodeNumber = deref(w.SeasonNumber), deref(w.EpisodeNumber)
			item.Subtitle = seCode(item.SeasonNumber, item.EpisodeNumber) + " " + deref(w.EpisodeTitle)
			item.PosterPath, item.BackdropPath, item.StillPath = deref(w.ShowPosterPath), deref(w.ShowBackdropPath), deref(w.EpisodeStillPath)
		}
		out = append(out, item)
	}
	return out, nil
}

// seCode names an episode the way every list does: S01E02.
func seCode(season, episode int64) string {
	return fmt.Sprintf("S%02dE%02d", season, episode)
}
