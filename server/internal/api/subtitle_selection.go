package api

import (
	"cmp"
	"context"
	"fmt"
	"path/filepath"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// trackPreferences are what a play's starting audio and subtitle tracks
// follow (PLAN-LANGUAGES.md).
type trackPreferences struct {
	audio     []string
	subtitles playback.SubtitlePreferences
}

func (p Preferences) tracks() trackPreferences {
	return trackPreferences{audio: p.Audio.Languages, subtitles: p.Subtitles}
}

// defaultTrackPreferences are for nobody signed in, or nobody who chose.
func defaultTrackPreferences() trackPreferences { return DefaultPreferences().tracks() }

// decideTracks uses only local metadata and downloaded rows. In particular,
// the playback admission path never searches or downloads from the provider.
// The request it returns names the audio track the person's languages chose.
func decideTracks(ctx context.Context, deps Deps, row db.MediaFile, info *probe.MediaInfo, req playback.Request, sidecars []playback.Sidecar, caps playback.Capabilities, prefs trackPreferences) (playback.Decision, playback.Request) {
	req, why := withPreferredAudio(req, info, prefs.audio)
	decide := func() playback.Decision {
		d := playback.Decide(info, filepath.Ext(row.Path), req, sidecars, caps)
		if why != "" {
			d.Reasons = append(d.Reasons, why)
		}
		return d
	}
	d := decide()
	var downloaded []playback.SubtitleTrack
	for _, r := range deps.OnlineSubtitles.List(ctx, row.ID) {
		downloaded = append(downloaded, downloadedTrack(r))
	}
	d.Subtitles = append(d.Subtitles, downloaded...)
	selected := req.SubtitleID
	if selected == "" && req.SubtitleMode == "auto" {
		selected = playback.PreferredSubtitle(d.Subtitles, selectedAudioLanguage(d), prefs.audio, prefs.subtitles)
	}
	for _, t := range d.Subtitles {
		if t.ID != selected {
			continue
		}
		// Image subtitles change the delivery mode. Make that decision
		// before starting the session, just as for a manual image pick.
		if t.BurnInOnly && req.SubtitleID != selected {
			req.SubtitleID = selected
			d = decide()
			d.Subtitles = append(d.Subtitles, downloaded...)
		}
		d.SubtitleID = selected
		break
	}
	return d, req
}

// withPreferredAudio names the person's preferred audio track in a request
// that names none, and says why for the decision's reasons. It leaves the
// request alone when the preferred track is the file's default anyway, so
// nothing changes for a file that already starts in the right language.
// Naming a track other than the default makes a remux for players without
// selectsAudioTrack, which carries just that track.
func withPreferredAudio(req playback.Request, info *probe.MediaInfo, languages []string) (playback.Request, string) {
	if req.AudioIndex != nil || len(languages) == 0 || info == nil {
		return req, ""
	}
	index, ok := playback.PreferredAudio(info.Audio, req.Profile, languages)
	if !ok || index == playback.DefaultAudioIndex(info.Audio) {
		return req, ""
	}
	req.AudioIndex = &index
	lang := ""
	for _, a := range info.Audio {
		if a.Index == index {
			lang = a.Language
		}
	}
	return req, fmt.Sprintf("audio track %d (%s) from the person's audio languages", index, cmp.Or(lang, "und"))
}

func selectedAudioLanguage(d playback.Decision) string {
	for _, track := range d.AudioTracks {
		if track.Index == d.Audio.Index {
			return track.Language
		}
	}
	return ""
}
