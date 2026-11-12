package api

import (
	"context"
	"net/http"
	"path/filepath"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/playback"
)

type PlanInput struct {
	Body struct {
		Profile playback.Profile `json:"profile"`
		FileIDs []int64          `json:"fileIds" minItems:"1" maxItems:"100" doc:"The files to plan, such as a movie's versions."`
	}
}

// PlaybackPlan is how a file would play for a profile: the mode and streams
// POST /api/files/{id}/playback would pick, with its default audio and no
// subtitles, without starting anything.
type PlaybackPlan struct {
	FileID   int64              `json:"fileId"`
	Mode     playback.Mode      `json:"mode" enum:"direct,remux,audio,transcode"`
	Protocol string             `json:"protocol" enum:"file,hls"`
	Reasons  []string           `json:"reasons" nullable:"false" doc:"Why a cheaper mode was not possible."`
	Video    playback.VideoPlan `json:"video"`
	Audio    playback.AudioPlan `json:"audio"`
}

type PlanList struct {
	Items []PlaybackPlan `json:"items" nullable:"false" doc:"In the order asked; files that are missing, not probed yet or unknown are left out."`
}

type PlanOutput struct{ Body PlanList }

func registerPlans(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "planPlayback",
		Method:      http.MethodPost,
		Path:        Prefix + "/playback/plans",
		Summary:     "How files would play for a device, without starting playback",
		Description: "Runs the playback decision for each file against the client's profile, as starting playback would, but starts no session and records no play. Clients show it on version lists (Direct play, Remux, audio to AAC).",
		Tags:        []string{"playback"},
	}, func(ctx context.Context, in *PlanInput) (*PlanOutput, error) {
		var caps playback.Capabilities
		if deps.Playback != nil {
			caps = deps.Playback.Capabilities()
		}
		// Version lists say what starting playback would do, which follows
		// the person's audio languages (a remux for some files).
		var audioLanguages []string
		if user, ok := auth.UserFrom(ctx); ok {
			prefs, err := loadPreferences(ctx, deps, user.ID)
			if err != nil {
				return nil, err
			}
			audioLanguages = prefs.Audio.Languages
		}
		out := &PlanOutput{Body: PlanList{Items: []PlaybackPlan{}}}
		seen := map[int64]bool{}
		for _, id := range in.Body.FileIDs {
			if seen[id] {
				continue
			}
			seen[id] = true
			row, _, info, err := playableFile(ctx, deps, id)
			if err != nil {
				continue
			}
			// Sidecar subtitles only matter once one is picked, so the
			// folder is not listed.
			req, why := withPreferredAudio(playback.Request{Profile: in.Body.Profile}, info, audioLanguages)
			d := playback.Decide(info, filepath.Ext(row.Path), req, nil, caps)
			if why != "" {
				d.Reasons = append(d.Reasons, why)
			}
			out.Body.Items = append(out.Body.Items, PlaybackPlan{FileID: row.ID, Mode: d.Mode, Protocol: d.Protocol, Reasons: d.Reasons, Video: d.Video, Audio: d.Audio})
		}
		return out, nil
	})
}
