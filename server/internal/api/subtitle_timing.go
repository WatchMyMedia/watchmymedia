package api

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

// Subtitle timing moves a track's cues later or earlier, for a track whose
// timing is off in that file. It is saved per person for that track of that
// file, so every device of theirs plays it in step; an admin can also save
// one for everyone who plays the file, which a person's own timing (0
// included) overrides for them. Apps apply it where they draw the cues; the
// server applies it only to the subtitles it burns into the picture.

type SubtitleTimingPath struct {
	ID    int64  `path:"id" doc:"Media file ID."`
	Track string `path:"track" maxLength:"32" doc:"The track's id in a decision: s<index> inside the file, x<n> beside it, d<id> downloaded."`
}

type SetSubtitleTimingInput struct {
	SubtitleTimingPath
	Body struct {
		TimingMs int  `json:"timingMs" minimum:"-60000" maximum:"60000" doc:"Milliseconds: positive shows each cue later, negative earlier. Without everyone, 0 is the person's own \"in step\", which wins over a timing saved for everyone."`
		Everyone bool `json:"everyone,omitempty" required:"false" doc:"Admins only: save it for everyone who plays the file (0 removes that), and remove the admin's own timing for the track, so they hear what everyone does. A person's own timing still wins for them."`
	}
}

// SubtitleTiming is a person's timing for a track, as their next play gets it.
type SubtitleTiming struct {
	TimingMs     int64 `json:"timingMs" doc:"The timing the caller's plays of this track now get: their own, else the one saved for everyone, else 0."`
	TimingShared bool  `json:"timingShared" doc:"timingMs is the one an admin saved for everyone, not the caller's own."`
}

type SubtitleTimingOutput struct{ Body SubtitleTiming }

func registerSubtitleTiming(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "setSubtitleTiming",
		Method:      http.MethodPut,
		Path:        Prefix + "/files/{id}/subtitles/{track}/timing",
		Summary:     "Save how much later or earlier a subtitle track shows, for the caller or (admins) for everyone",
		Description: "From apiLevel 35. Any signed-in person who can play the file saves their own timing; with everyone, an admin saves it for everyone who plays the file. Decisions then carry it on the track as timingMs.",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, in *SetSubtitleTimingInput) (*SubtitleTimingOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		if in.Body.Everyone && !effectiveAdmin(ctx, user) {
			return nil, huma.Error403Forbidden("only admins can save a subtitle timing for everyone")
		}
		row, key, err := timingTrack(ctx, deps, in.SubtitleTimingPath)
		if err != nil {
			return nil, err
		}
		ms := int64(in.Body.TimingMs)
		err = inTx(ctx, deps, func(q *db.Queries) error {
			if in.Body.Everyone {
				if err := q.DeleteSharedSubtitleTiming(ctx, db.DeleteSharedSubtitleTimingParams{FileID: row.ID, TrackKey: key}); err != nil {
					return err
				}
				if ms != 0 {
					if err := q.InsertSubtitleTiming(ctx, db.InsertSubtitleTimingParams{FileID: row.ID, TrackKey: key, TimingMs: ms, FileSize: row.Size}); err != nil {
						return err
					}
				}
				return q.DeleteSubtitleTiming(ctx, db.DeleteSubtitleTimingParams{FileID: row.ID, TrackKey: key, UserID: &user.ID})
			}
			if err := q.DeleteSubtitleTiming(ctx, db.DeleteSubtitleTimingParams{FileID: row.ID, TrackKey: key, UserID: &user.ID}); err != nil {
				return err
			}
			return q.InsertSubtitleTiming(ctx, db.InsertSubtitleTimingParams{UserID: &user.ID, FileID: row.ID, TrackKey: key, TimingMs: ms, FileSize: row.Size})
		})
		if err != nil {
			return nil, err
		}
		return effectiveTiming(ctx, deps, user.ID, row, key)
	})

	huma.Register(h, huma.Operation{
		OperationID: "deleteSubtitleTiming",
		Method:      http.MethodDelete,
		Path:        Prefix + "/files/{id}/subtitles/{track}/timing",
		Summary:     "Remove the caller's own timing for a subtitle track: back to the one saved for everyone, else in step",
		Description: "From apiLevel 35. The player's Reset. A timing an admin saved for everyone stays.",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, in *SubtitleTimingPath) (*SubtitleTimingOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		row, key, err := timingTrack(ctx, deps, *in)
		if err != nil {
			return nil, err
		}
		if err := deps.Queries.DeleteSubtitleTiming(ctx, db.DeleteSubtitleTimingParams{FileID: row.ID, TrackKey: key, UserID: &user.ID}); err != nil {
			return nil, err
		}
		return effectiveTiming(ctx, deps, user.ID, row, key)
	})
}

// inTx runs fn on a transaction's queries and commits.
func inTx(ctx context.Context, deps Deps, fn func(*db.Queries) error) error {
	tx, err := deps.Conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if err := fn(deps.Queries.WithTx(tx)); err != nil {
		return err
	}
	return tx.Commit()
}

// timingTrack finds a present file's track by its decision id and names its
// timing's key.
func timingTrack(ctx context.Context, deps Deps, in SubtitleTimingPath) (db.MediaFile, string, error) {
	row, _, info, err := playableFile(ctx, deps, in.ID)
	if err != nil {
		return row, "", err
	}
	missing := huma.Error404NotFound("no such subtitle track")
	if len(in.Track) < 2 {
		return row, "", missing
	}
	n, err := strconv.ParseInt(in.Track[1:], 10, 64)
	if err != nil || n < 0 || strconv.FormatInt(n, 10) != in.Track[1:] {
		return row, "", missing
	}
	switch in.Track[0] {
	case 's':
		for _, s := range info.Subtitles {
			if int64(s.Index) == n {
				return row, in.Track, nil
			}
		}
	case 'x':
		if sidecars := playback.FindSidecars(row.Path); n < int64(len(sidecars)) {
			return row, sidecarTimingKey(sidecars[n]), nil
		}
	case 'd':
		if deps.OnlineSubtitles != nil {
			if _, err := deps.OnlineSubtitles.Get(ctx, row.ID, n); err == nil {
				return row, in.Track, nil
			}
		}
	}
	return row, "", missing
}

// sidecarTimingKey keys a subtitle file beside the video by its name: its
// x<n> id counts the files beside the video, which another file moves.
func sidecarTimingKey(s playback.Sidecar) string { return "x:" + filepath.Base(s.Path) }

// trackTimingKey is the key of a decision's track; "" when unknown.
func trackTimingKey(id string, sidecars []playback.Sidecar) string {
	if n, ok := strings.CutPrefix(id, "x"); ok {
		if i, err := strconv.Atoi(n); err == nil && i >= 0 && i < len(sidecars) {
			return sidecarTimingKey(sidecars[i])
		}
		return ""
	}
	return id
}

type savedTiming struct {
	ms     int64
	shared bool
}

// fileTimings are a person's timings for a file's tracks, by key: their own
// where they have one, else the shared one.
func fileTimings(ctx context.Context, deps Deps, userID int64, row db.MediaFile) (map[string]savedTiming, error) {
	rows, err := deps.Queries.ListSubtitleTimings(ctx, db.ListSubtitleTimingsParams{FileID: row.ID, FileSize: row.Size, UserID: &userID})
	if err != nil {
		return nil, err
	}
	out := map[string]savedTiming{}
	for _, r := range rows {
		if r.UserID == nil {
			if _, own := out[r.TrackKey]; !own {
				out[r.TrackKey] = savedTiming{ms: r.TimingMs, shared: true}
			}
			continue
		}
		out[r.TrackKey] = savedTiming{ms: r.TimingMs}
	}
	return out, nil
}

func effectiveTiming(ctx context.Context, deps Deps, userID int64, row db.MediaFile, key string) (*SubtitleTimingOutput, error) {
	timings, err := fileTimings(ctx, deps, userID, row)
	if err != nil {
		return nil, err
	}
	t := timings[key]
	return &SubtitleTimingOutput{Body: SubtitleTiming{TimingMs: t.ms, TimingShared: t.shared}}, nil
}

// withSubtitleTimings puts the person's saved timings on a decision's
// tracks.
func withSubtitleTimings(ctx context.Context, deps Deps, userID int64, row db.MediaFile, sidecars []playback.Sidecar, d *playback.Decision) {
	timings, err := fileTimings(ctx, deps, userID, row)
	if err != nil {
		deps.Log.Warn("could not read subtitle timings", "file", row.ID, "err", err)
		return
	}
	if len(timings) == 0 {
		return
	}
	for i := range d.Subtitles {
		t := &d.Subtitles[i]
		if saved := timings[trackTimingKey(t.ID, sidecars)]; saved.ms != 0 {
			t.TimingMs, t.TimingShared = saved.ms, saved.shared
		}
	}
	syncBurnIn(d)
}

// syncBurnIn points the decision's burnIn at its entry among the
// subtitles, as Decide made it, so what is set on one shows on both.
func syncBurnIn(d *playback.Decision) {
	if d.BurnIn == nil {
		return
	}
	for i := range d.Subtitles {
		if d.Subtitles[i].ID == d.BurnIn.ID {
			d.BurnIn = &d.Subtitles[i]
			return
		}
	}
}

// shiftWait is how long a play waits for a burned-in text track to be read
// out of the video, so that it can be burned in at the person's timing.
// Past it the track is burned in as it is.
const shiftWait = 20 * time.Second

// withBurnInTiming applies the burned-in track's saved timing in the
// session: an image track's input is offset; a text track is burned from a
// copy with its cues moved. timingApplied says it was.
func withBurnInTiming(ctx context.Context, deps Deps, row db.MediaFile, sidecars []playback.Sidecar, caps playback.Capabilities, d *playback.Decision, spec *playback.Spec) {
	if d.BurnIn == nil || d.BurnIn.TimingMs == 0 || d.Video.Copy {
		return
	}
	ms := d.BurnIn.TimingMs
	applied := false
	switch {
	case spec.BurnInImage:
		if caps.Overlay {
			spec.BurnInTimingMs, applied = ms, true
		}
	case !caps.LibASS || deps.Subtitles == nil:
	default:
		format := "webvtt"
		if c := d.BurnIn.Codec; c == "ass" || c == "ssa" {
			format = "ass"
		}
		wait, cancel := context.WithTimeout(ctx, shiftWait)
		defer cancel()
		var data []byte
		var err error
		switch {
		case spec.BurnIn >= 0:
			data, err = deps.Subtitles.Embedded(wait, row.ID, row.Path, spec.BurnIn, format)
		case spec.BurnInFile != "":
			n, _ := strconv.Atoi(strings.TrimPrefix(d.BurnIn.ID, "x"))
			if n < 0 || n >= len(sidecars) {
				return
			}
			data, err = deps.Subtitles.Sidecar(wait, row.ID, n, sidecars[n], format)
		default:
			return
		}
		var path string
		if err == nil {
			path, err = deps.Subtitles.Shifted(fmt.Sprintf("%d-%s", row.ID, d.BurnIn.ID), data, format, ms)
		}
		if err != nil {
			deps.Log.Warn("subtitles burned in without their timing", "file", row.ID, "track", d.BurnIn.ID, slog.Any("err", err))
			return
		}
		spec.BurnInFile, applied = path, true
	}
	if !applied {
		return
	}
	for i := range d.Subtitles {
		if d.Subtitles[i].ID == d.BurnIn.ID {
			d.Subtitles[i].TimingApplied = true
		}
	}
	syncBurnIn(d)
}
