package api

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"slices"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
)

// ShowTracks are the sound and subtitles a person picked while watching a
// show, which its next episodes start with. From API level 34.
type ShowTracks struct {
	AudioLanguage    string `json:"audioLanguage,omitempty" required:"false" example:"ja" doc:"BCP 47. A play that names no audioIndex starts on this language when the file has it, before the person's audio languages. Absent: their audio languages alone."`
	SubtitleLanguage string `json:"subtitleLanguage,omitempty" required:"false" example:"en" doc:"BCP 47. A play with subtitleMode auto starts subtitles in this language, before the person's subtitle languages. Absent: their subtitle settings, unless subtitlesOff."`
	SubtitlesOff     bool   `json:"subtitlesOff" doc:"A play with subtitleMode auto starts without subtitles."`
}

type ShowTracksInput struct {
	ID   int64 `path:"id" doc:"Show ID."`
	Body struct {
		AudioLanguage    *string `json:"audioLanguage,omitempty" required:"false" doc:"BCP 47; an empty string forgets it. Omitted: kept."`
		SubtitleLanguage *string `json:"subtitleLanguage,omitempty" required:"false" doc:"BCP 47, which also turns subtitlesOff off; an empty string forgets it. Omitted: kept."`
		SubtitlesOff     *bool   `json:"subtitlesOff,omitempty" required:"false" doc:"true forgets subtitleLanguage. Omitted: kept."`
	}
}

type ShowTracksOutput struct{ Body ShowTracks }

func showTracksFrom(c db.ShowTrackChoice) ShowTracks {
	return ShowTracks{AudioLanguage: deref(c.AudioLanguage), SubtitleLanguage: deref(c.SubtitleLanguage), SubtitlesOff: c.SubtitleOff == 1}
}

func registerShowTracks(h huma.API, deps Deps) {
	op := func(id, method, summary, description string) huma.Operation {
		return huma.Operation{OperationID: id, Method: method, Path: Prefix + "/shows/{id}/tracks", Summary: summary, Description: description, Tags: []string{"playback"}}
	}
	huma.Register(h, op("setShowTracks", http.MethodPut, "Remember the sound and subtitles the signed-in person picked for a show",
		"Apps send it when the person picks a track in the player during one of the show's episodes, so the next episodes start the same way. Kept by the show's TMDB id, for every library's copy. Fields left out keep their values. From API level 34."),
		func(ctx context.Context, in *ShowTracksInput) (*ShowTracksOutput, error) {
			u, err := currentUser(ctx)
			if err != nil {
				return nil, err
			}
			show, err := deps.Queries.GetShow(ctx, in.ID)
			if err != nil {
				return nil, notFound(err, "show not found")
			}
			audio, err := trackLanguage(in.Body.AudioLanguage, "audio")
			if err != nil {
				return nil, err
			}
			subtitle, err := trackLanguage(in.Body.SubtitleLanguage, "subtitle")
			if err != nil {
				return nil, err
			}
			var saved db.ShowTrackChoice
			err = deps.Queries.Transaction(ctx, func(q *db.Queries) error {
				c, err := q.GetShowTrackChoice(ctx, db.GetShowTrackChoiceParams{UserID: u.ID, TmdbID: show.TmdbID})
				if err != nil && !errors.Is(err, sql.ErrNoRows) {
					return err
				}
				c.UserID, c.TmdbID, c.UpdatedAt = u.ID, show.TmdbID, db.FormatTime(time.Now())
				if audio != nil {
					c.AudioLanguage = nilIfBlank(*audio)
				}
				if subtitle != nil {
					if c.SubtitleLanguage = nilIfBlank(*subtitle); c.SubtitleLanguage != nil {
						c.SubtitleOff = 0
					}
				}
				if off := in.Body.SubtitlesOff; off != nil {
					c.SubtitleOff = db.BoolInt(*off)
					if *off {
						c.SubtitleLanguage = nil
					}
				}
				saved = c
				return q.SetShowTrackChoice(ctx, db.SetShowTrackChoiceParams(c))
			})
			if err != nil {
				return nil, err
			}
			return &ShowTracksOutput{Body: showTracksFrom(saved)}, nil
		})

	forget := op("forgetShowTracks", http.MethodDelete, "Forget the sound and subtitles remembered for a show",
		"Its episodes start on the person's own languages again. From API level 34.")
	forget.DefaultStatus = http.StatusNoContent
	huma.Register(h, forget, func(ctx context.Context, in *ShowIDInput) (*struct{}, error) {
		u, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		show, err := deps.Queries.GetShow(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "show not found")
		}
		return nil, deps.Queries.DeleteShowTrackChoice(ctx, db.DeleteShowTrackChoiceParams{UserID: u.ID, TmdbID: show.TmdbID})
	})
}

// trackLanguage is a language a person picked, in canonical form: nil when
// left out, "" to forget one.
func trackLanguage(raw *string, kind string) (*string, error) {
	if raw == nil || *raw == "" {
		return raw, nil
	}
	langs, err := canonicalLanguages([]string{*raw}, kind)
	if err != nil {
		return nil, err
	}
	return &langs[0], nil
}

func nilIfBlank(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// showTracks is the person's choice for a show, for its page; nil when
// they have none.
func showTracks(ctx context.Context, deps Deps, userID, tmdbID int64) (*ShowTracks, error) {
	c, err := deps.Queries.GetShowTrackChoice(ctx, db.GetShowTrackChoiceParams{UserID: userID, TmdbID: tmdbID})
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return nil, nil
	case err != nil:
		return nil, err
	}
	t := showTracksFrom(c)
	return &t, nil
}

// withShowTracks puts the person's choice for the show an episode file
// belongs to ahead of their own languages: its audio language first, and
// its subtitle language (on) or subtitles off in place of their mode. A
// play that names its tracks is not affected, since those win over any
// preference. Nothing changes for a movie, or a show with no choice.
func withShowTracks(ctx context.Context, deps Deps, userID, fileID int64, p trackPreferences) (trackPreferences, error) {
	c, err := deps.Queries.GetShowTrackChoiceForFile(ctx, db.GetShowTrackChoiceForFileParams{UserID: userID, MediaFileID: fileID})
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return p, nil
	case err != nil:
		return p, err
	}
	if lang := deref(c.AudioLanguage); lang != "" {
		p.audio = prepend(lang, p.audio)
	}
	switch {
	case c.SubtitleOff == 1:
		p.subtitles.Mode = "off"
	case deref(c.SubtitleLanguage) != "":
		p.subtitles.Mode = "on"
		p.subtitles.Languages = prepend(*c.SubtitleLanguage, p.subtitles.Languages)
	}
	return p, nil
}

// prepend puts lang first in langs, once, without changing langs.
func prepend(lang string, langs []string) []string {
	out := []string{lang}
	for _, l := range langs {
		if l != lang {
			out = append(out, l)
		}
	}
	return slices.Clip(out)
}
