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
		// Tracks is from API level 34.
		Tracks bool `json:"tracks,omitempty" required:"false" doc:"Also list each file's audio tracks and subtitles, those beside the file and downloaded ones included, so a person can choose them before playing. From API level 34; older servers ignore it."`
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
	// AudioTracks and Subtitles are from API level 34, with tracks.
	AudioTracks []playback.AudioTrack    `json:"audioTracks,omitempty" required:"false" nullable:"false" doc:"With tracks: the file's audio tracks, as a decision lists them. Send the chosen one's index as audioIndex."`
	Subtitles   []playback.SubtitleTrack `json:"subtitles,omitempty" required:"false" nullable:"false" doc:"With tracks: every subtitle a play could show, inside the file, beside it and downloaded, as a decision lists them but without URLs. Send the chosen one's id as subtitleId."`
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
		// the person's audio languages (a remux for some files) and their
		// sound settings (the sound converted).
		var audioLanguages []string
		var effects playback.AudioEffects
		if user, ok := auth.UserFrom(ctx); ok {
			prefs, err := loadPreferences(ctx, deps, user.ID)
			if err != nil {
				return nil, err
			}
			audioLanguages, effects = prefs.Audio.Languages, prefs.Audio.Effects()
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
			// folder is listed only when the caller asks for the tracks.
			var sidecars []playback.Sidecar
			if in.Body.Tracks {
				sidecars = playback.FindSidecars(row.Path)
			}
			languages := audioLanguages
			if user, ok := auth.UserFrom(ctx); ok {
				// An episode would start on the sound picked for its show.
				tracks, err := withShowTracks(ctx, deps, user.ID, row.ID, trackPreferences{audio: audioLanguages})
				if err != nil {
					return nil, err
				}
				languages = tracks.audio
			}
			req, why := withPreferredAudio(playback.Request{Profile: in.Body.Profile, Effects: effects}, info, languages)
			d := playback.Decide(info, filepath.Ext(row.Path), req, sidecars, caps)
			if why != "" {
				d.Reasons = append(d.Reasons, why)
			}
			plan := PlaybackPlan{FileID: row.ID, Mode: d.Mode, Protocol: d.Protocol, Reasons: d.Reasons, Video: d.Video, Audio: d.Audio}
			if in.Body.Tracks {
				plan.AudioTracks, plan.Subtitles = d.AudioTracks, d.Subtitles
				for _, r := range deps.OnlineSubtitles.List(ctx, row.ID) {
					plan.Subtitles = append(plan.Subtitles, downloadedTrack(r))
				}
				for i := range plan.Subtitles {
					plan.Subtitles[i].URL = ""
				}
			}
			out.Body.Items = append(out.Body.Items, plan)
		}
		return out, nil
	})
}
