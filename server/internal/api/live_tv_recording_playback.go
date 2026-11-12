package api

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"time"

	"github.com/danielgtaylor/huma/v2"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/growing"
	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/memo"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

type LiveTVRecordingPlaybackInput struct {
	ID   string `path:"id" maxLength:"64"`
	Body playback.Request
}
type LiveTVRecordingProgressInput struct {
	ID   string `path:"id" maxLength:"64"`
	Body ProgressInputBody
}

func registerRecordingPlayback(h huma.API, deps Deps) {
	// A growing transport stream is probed once per recording. Track changes
	// and seeks reuse this bounded cache; list/guide requests never probe.
	probes := memo.NewMap[string, *probe.MediaInfo](24*time.Hour, 64)
	group := memo.Group{Timeout: 5 * time.Second}
	viewer := func(ctx context.Context) error {
		u, err := currentUser(ctx)
		if err != nil {
			return err
		}
		if deps.Recordings == nil || deps.LiveTV == nil {
			return huma.Error503ServiceUnavailable("Live TV recording is unavailable")
		}
		if !deps.LiveTV.Allowed(ctx, u.IsAdmin) {
			return huma.Error403Forbidden("Live TV is unavailable to this account")
		}
		return nil
	}
	op := func(id, method, path, summary string) huma.Operation {
		return huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary, Tags: []string{"live-tv"}}
	}
	huma.Register(h, op("startLiveTVRecordingPlayback", "POST", "/live-tv/recordings/{id}/playback", "Watch a recording from the start, including while it grows"), func(ctx context.Context, in *LiveTVRecordingPlaybackInput) (*PlaybackOutput, error) {
		if err := viewer(ctx); err != nil {
			return nil, err
		}
		src, err := deps.Recordings.Source(ctx, in.ID)
		if err != nil {
			return nil, recordingError(err)
		}
		if src.Recording.MediaFileID != nil && src.Recording.State != "recording" {
			return startFilePlayback(ctx, deps, &PlaybackInput{ID: *src.Recording.MediaFileID, Body: in.Body})
		}
		if deps.Playback == nil || deps.Prober == nil {
			return nil, huma.Error503ServiceUnavailable("transcoder not available")
		}
		st, err := os.Stat(src.Path)
		if err != nil || st.Size() < 188 {
			return nil, recordingNotReady("The recording is just starting; try again in a moment")
		}
		info, ok := probes.Get(in.ID)
		if !ok {
			info, err = memo.Do(ctx, &group, in.ID, func(probeCtx context.Context) (*probe.MediaInfo, error) {
				if cached, ok := probes.Get(in.ID); ok {
					return cached, nil
				}
				p, err := deps.Prober.Probe(probeCtx, src.Path)
				if err == nil && len(p.Video) > 0 {
					probes.Set(in.ID, p)
				}
				return p, err
			})
			if err != nil {
				return nil, recordingNotReady("The broadcast is not ready to play yet")
			}
		}
		u, _ := currentUser(ctx)
		started := src.Recording.Start
		if src.Recording.StartedAt != nil {
			started = *src.Recording.StartedAt
		}
		end := src.Recording.End.Add(time.Duration(src.Recording.PadAfter) * time.Minute)
		if src.Recording.EndedAt != nil {
			end = *src.Recording.EndedAt
		}
		recordedThrough := time.Now()
		if recordedThrough.After(end) {
			recordedThrough = end
		}
		available := max(int64(0), recordedThrough.Sub(started).Milliseconds())
		request := in.Body
		request.Playlist = "event"
		request.StartMs = min(max(request.StartMs, 0), max(available-8000, 0))
		// Growing recordings always use HLS, even when a client accepts MPEG-TS.
		request.Profile.Containers = nil
		if request.Quality == playback.QualityAuto {
			request.Profile = playback.StepByID("1080").Cap(request.Profile)
		}
		// A broadcast often carries a second language (SAP): the person's
		// audio languages choose, as for a finished recording.
		why := ""
		if request.AudioIndex == nil && u.ID != 0 {
			prefs, err := loadPreferences(ctx, deps, u.ID)
			if err != nil {
				return nil, err
			}
			request, why = withPreferredAudio(request, info, prefs.Audio.Languages)
		}
		d := playback.Decide(info, ".ts", request, nil, deps.Playback.Capabilities())
		if why != "" {
			d.Reasons = append(d.Reasons, why)
		}
		d.Protocol, d.Playlist, d.MimeType = "hls", "event", "application/vnd.apple.mpegurl"
		d.PlayID = request.PlayID
		if d.PlayID == "" {
			d.PlayID = playback.NewPlayID()
		}
		d.DurationMs = available
		d.ResumeMs, _, err = deps.Recordings.Progress(ctx, u.ID, in.ID)
		if err != nil {
			return nil, err
		}
		if src.Recording.State == "recording" {
			d.Growing = &playback.Growing{StartedAt: started, EndsAt: end}
		}
		// All unlinked inputs are owned by the recording table; no media-file ID
		// is invented. Session ownership still binds every HLS read to this user.
		spec := sessionSpec(db.MediaFile{Path: src.Path}, u.ID, d, request.Profile.MaxBitrate, nil)
		spec.Growing = func(run context.Context, startMs int64) (playback.GrowingInput, error) {
			current, err := deps.Recordings.Source(run, in.ID)
			if err != nil {
				return playback.GrowingInput{}, err
			}
			written, err := os.Stat(current.Path)
			if err != nil {
				return playback.GrowingInput{}, err
			}
			through := time.Now()
			if through.After(end) {
				through = end
			}
			elapsed := max(int64(0), through.Sub(started).Milliseconds())
			effective := min(max(startMs, 0), max(elapsed-growing.SeekLookbackMs, 0))
			reader, err := growing.Open(run, current.Path, growing.SeekOffset(startMs, elapsed, written.Size()), current.Done)
			return playback.GrowingInput{Reader: reader, StartMs: effective}, err
		}
		session, err := deps.Playback.Start(ctx, spec)
		if err != nil {
			return nil, recordingStartError(err)
		}
		d.SessionID = session.ID
		d.URL = fmt.Sprintf("%s/playback/%s/%s", Prefix, session.ID, playback.PlaylistName)
		if session.Fallback != "" {
			d.Video = session.Video
			d.Reasons = append(d.Reasons, session.Fallback)
		}
		// Caption/subtitle conversion from a file still being appended is not
		// supported; never advertise unserved track URLs.
		d.Subtitles = []playback.SubtitleTrack{}
		return &PlaybackOutput{Body: d}, nil
	})
	huma.Register(h, op("reportLiveTVRecordingProgress", "PUT", "/live-tv/recordings/{id}/progress", "Keep a viewer’s recording position as it grows and joins the library"), func(ctx context.Context, in *LiveTVRecordingProgressInput) (*ProgressOutput, error) {
		if err := viewer(ctx); err != nil {
			return nil, err
		}
		u, _ := currentUser(ctx)
		if err := deps.Recordings.ReportProgress(ctx, u.ID, in.ID, in.Body.PositionMs, in.Body.DurationMs); err != nil {
			return nil, recordingError(err)
		}
		pos, dur, err := deps.Recordings.Progress(ctx, u.ID, in.ID)
		if err != nil {
			return nil, err
		}
		if deps.Playback != nil && (in.Body.State == "stopped" || in.Body.State == "closed") {
			deps.Playback.StopPlay(in.Body.PlayID, u.ID)
		}
		return &ProgressOutput{Body: Progress{WatchState: WatchState{PositionMs: pos, DurationMs: dur, UpdatedAt: time.Now()}}}, nil
	})
}

// recordingNotReady is a 409 a player retries after a moment.
func recordingNotReady(msg string) error {
	return huma.ErrorWithHeaders(huma.Error409Conflict(msg), http.Header{"Retry-After": {"2"}})
}

// recordingStartError is startError for a recording's playback. The
// recording was there a moment ago, when the request began, but it can
// finish, and its file move into the library, while the session opens its
// input; the player tries again in a moment and gets the finished file.
func recordingStartError(err error) error {
	if errors.Is(err, dvr.ErrUnavailable) || errors.Is(err, fs.ErrNotExist) {
		return recordingNotReady("The recording is finishing; try again in a moment")
	}
	return startError(err)
}
