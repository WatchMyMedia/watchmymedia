package api

import (
	"context"
	"encoding/json"
	"maps"
	"net/http"
	"slices"
	"strconv"
	"strings"

	"github.com/danielgtaylor/huma/v2"
	"golang.org/x/text/language"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv/guide"
	"watchmymedia/server/internal/playback"
)

// Skip modes for one kind of segment.
const (
	SkipNone      = "none"       // no button
	SkipAsk       = "ask"        // show the Skip button
	SkipAuto      = "auto"       // skip as soon as the segment starts
	SkipAutoDelay = "auto-delay" // show the button, then skip after 3 s
)

// SkipKinds are the segment kinds a person can set a mode for, in display order.
var SkipKinds = []string{"intro", "recap", "credits", "preview", "commercial"}

var skipModes = map[string]bool{SkipNone: true, SkipAsk: true, SkipAuto: true, SkipAutoDelay: true}

// Preferences are a person's playback settings, the same on every device.
type Preferences struct {
	// Skip maps a segment kind (intro, recap, credits, preview, commercial)
	// to none, ask, auto or auto-delay.
	Skip         map[string]string `json:"skip" doc:"Per segment kind: none (no button), ask (a Skip button), auto (skip at once) or auto-delay (skip after 3 s)."`
	AutoplayNext bool              `json:"autoplayNext" doc:"Play the next episode by itself after the credits."`
	// LiveTVReminderMinutes is how long before a programme starts a Live TV
	// reminder comes (PLAN-LIVE-TV-GUIDE.md §5.7).
	LiveTVReminderMinutes int                          `json:"liveTVReminderMinutes" required:"false" doc:"Minutes before a programme starts that a Live TV reminder comes: 0, 1, 5, 10 or 15 (API level 15)."`
	Subtitles             playback.SubtitlePreferences `json:"subtitles" required:"false" doc:"The person's subtitle languages, starting mode and opt-in automatic download (API level 18)."`
	Audio                 playback.AudioPreferences    `json:"audio" required:"false" doc:"The person's audio languages: a play that names no audioIndex starts on the best track in the first of them the file has (API level 21)."`
}

// DefaultPreferences: a button for intros, recaps and credits, nothing for
// previews and ads, and the next episode plays by itself.
func DefaultPreferences() Preferences {
	return Preferences{
		Skip:                  map[string]string{"intro": SkipAsk, "recap": SkipAsk, "credits": SkipAsk, "preview": SkipNone, "commercial": SkipAsk},
		AutoplayNext:          true,
		LiveTVReminderMinutes: defaultReminderMinutes,
		Subtitles:             playback.SubtitlePreferences{Languages: []string{}, Mode: "forced"},
		Audio:                 playback.AudioPreferences{Languages: []string{}},
	}
}

// Live TV reminders' lead times, in minutes.
const defaultReminderMinutes = 5

var reminderMinutes = []int{0, 1, 5, 10, 15}

type PreferencesOutput struct {
	Body Preferences
}

type UpdatePreferencesInput struct {
	Body struct {
		Skip                  map[string]string          `json:"skip,omitempty" doc:"Kinds to change; others keep their setting."`
		AutoplayNext          *bool                      `json:"autoplayNext,omitempty"`
		LiveTVReminderMinutes *int                       `json:"liveTVReminderMinutes,omitempty" required:"false" doc:"0, 1, 5, 10 or 15."`
		Subtitles             *UpdateSubtitlePreferences `json:"subtitles,omitempty" required:"false" doc:"Subtitle fields to change; omitted fields keep their values (API level 18)."`
		Audio                 *UpdateAudioPreferences    `json:"audio,omitempty" required:"false" doc:"Audio fields to change; omitted fields keep their values. From API level 21; older servers reject it."`
	}
}

type UpdateAudioPreferences struct {
	Languages *[]string `json:"languages,omitempty" maxItems:"16" doc:"Ordered BCP 47 languages. An empty list clears them. Saving languages for a person who never saved a subtitle mode also turns on subtitles.foreignAudio."`
}

type UpdateSubtitlePreferences struct {
	Languages         *[]string `json:"languages,omitempty" maxItems:"16" doc:"Ordered BCP 47 languages. An empty list clears them."`
	Mode              *string   `json:"mode,omitempty" doc:"off, forced or on."`
	ForeignAudio      *bool     `json:"foreignAudio,omitempty" required:"false" doc:"With mode forced, subtitles as on does when the audio isn't in the person's languages. From API level 21; older servers reject it."`
	AutomaticDownload *bool     `json:"automaticDownload,omitempty" doc:"Allow exact-file automatic downloads, off by default."`
}

func registerPreferences(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "getPreferences",
		Method:      http.MethodGet,
		Path:        Prefix + "/me/preferences",
		Summary:     "Get the signed-in user's playback preferences",
		Tags:        []string{"users"},
	}, func(ctx context.Context, _ *struct{}) (*PreferencesOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		p, err := loadPreferences(ctx, deps, user.ID)
		if err != nil {
			return nil, err
		}
		return &PreferencesOutput{Body: p}, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "updatePreferences",
		Method:      http.MethodPut,
		Path:        Prefix + "/me/preferences",
		Summary:     "Change the signed-in user's playback preferences",
		Tags:        []string{"users"},
	}, func(ctx context.Context, in *UpdatePreferencesInput) (*PreferencesOutput, error) {
		user, err := currentUser(ctx)
		if err != nil {
			return nil, err
		}
		// Validate the complete patch before writing any field: a bad
		// language must not partially change the person's playback settings.
		if err := validatePreferencePatch(in); err != nil {
			return nil, err
		}
		if err := savePreferencePatch(ctx, deps, user.ID, in); err != nil {
			return nil, err
		}
		if in.Body.LiveTVReminderMinutes != nil && deps.Bus != nil {
			deps.Bus.Publish(events.Event{Type: guide.RemindersChangedEvent, Data: guide.RemindersChanged{}, To: &events.Audience{UserID: user.ID, Private: true}})
		}
		p, err := loadPreferences(ctx, deps, user.ID)
		if err != nil {
			return nil, err
		}
		return &PreferencesOutput{Body: p}, nil
	})
}

func loadPreferences(ctx context.Context, deps Deps, userID int64) (Preferences, error) {
	p := DefaultPreferences()
	rows, err := deps.Queries.ListUserPreferences(ctx, userID)
	if err != nil {
		return p, err
	}
	for _, r := range rows {
		switch {
		case r.Key == "autoplayNext":
			p.AutoplayNext = r.Value == "true"
		case r.Key == "liveTV.reminderMinutes":
			if m, err := strconv.Atoi(r.Value); err == nil && slices.Contains(reminderMinutes, m) {
				p.LiveTVReminderMinutes = m
			}
		case r.Key == "subtitles.mode":
			if validSubtitleMode(r.Value) {
				p.Subtitles.Mode = r.Value
			}
		case r.Key == "subtitles.automaticDownload":
			p.Subtitles.AutomaticDownload = r.Value == "true"
		case r.Key == "subtitles.foreignAudio":
			p.Subtitles.ForeignAudio = r.Value == "true"
		case r.Key == "subtitles.languages":
			if langs, ok := storedLanguages(r.Value); ok {
				p.Subtitles.Languages = langs
			}
		case r.Key == "audio.languages":
			if langs, ok := storedLanguages(r.Value); ok {
				p.Audio.Languages = langs
			}
		case len(r.Key) > 5 && r.Key[:5] == "skip." && skipModes[r.Value]:
			if kind := r.Key[5:]; slices.Contains(SkipKinds, kind) {
				p.Skip[kind] = r.Value
			}
		}
	}
	return p, nil
}

func validSubtitleMode(mode string) bool { return mode == "off" || mode == "forced" || mode == "on" }

func storedLanguages(value string) ([]string, bool) {
	var stored []string
	if json.Unmarshal([]byte(value), &stored) != nil {
		return nil, false
	}
	langs, err := canonicalLanguages(stored, "subtitle")
	return langs, err == nil
}

// canonicalLanguages checks an ordered language list; kind (subtitle,
// audio) names it in errors.
func canonicalLanguages(langs []string, kind string) ([]string, error) {
	if len(langs) > 16 {
		return nil, huma.Error422UnprocessableEntity("choose at most 16 " + kind + " languages")
	}
	out := []string{}
	for _, raw := range langs {
		if len(raw) > 64 || strings.TrimSpace(raw) != raw || raw == "" {
			return nil, huma.Error422UnprocessableEntity(kind + " languages must be BCP 47 language tags")
		}
		tag, err := language.Parse(raw)
		if err != nil || tag == language.Und {
			return nil, huma.Error422UnprocessableEntity(kind + " languages must be BCP 47 language tags")
		}
		lang := tag.String()
		if !slices.Contains(out, lang) {
			out = append(out, lang)
		}
	}
	return out, nil
}

func validatePreferencePatch(in *UpdatePreferencesInput) error {
	for kind, mode := range in.Body.Skip {
		if !slices.Contains(SkipKinds, kind) {
			return huma.Error422UnprocessableEntity("unknown segment kind " + kind)
		}
		if !skipModes[mode] {
			return huma.Error422UnprocessableEntity("unknown skip mode " + mode + " for " + kind)
		}
	}
	if m := in.Body.LiveTVReminderMinutes; m != nil && !slices.Contains(reminderMinutes, *m) {
		return huma.Error422UnprocessableEntity("liveTVReminderMinutes must be 0, 1, 5, 10 or 15")
	}
	if sub := in.Body.Subtitles; sub != nil {
		if sub.Mode != nil && !validSubtitleMode(*sub.Mode) {
			return huma.Error422UnprocessableEntity("subtitle mode must be off, forced or on")
		}
		if sub.Languages != nil {
			if _, err := canonicalLanguages(*sub.Languages, "subtitle"); err != nil {
				return err
			}
		}
	}
	if audio := in.Body.Audio; audio != nil && audio.Languages != nil {
		if _, err := canonicalLanguages(*audio.Languages, "audio"); err != nil {
			return err
		}
	}
	return nil
}

// savePreferencePatch keeps all values in one transaction. Individual rows
// preserve partial-update concurrency, while a failed write cannot turn on
// automatic downloads without also saving the matching language and mode.
func savePreferencePatch(ctx context.Context, deps Deps, userID int64, in *UpdatePreferencesInput) error {
	changes := map[string]string{}
	for kind, mode := range in.Body.Skip {
		changes["skip."+kind] = mode
	}
	if enabled := in.Body.AutoplayNext; enabled != nil {
		changes["autoplayNext"] = strconv.FormatBool(*enabled)
	}
	if minutes := in.Body.LiveTVReminderMinutes; minutes != nil {
		changes["liveTV.reminderMinutes"] = strconv.Itoa(*minutes)
	}
	if update := in.Body.Subtitles; update != nil {
		if update.Languages != nil {
			encoded, err := encodedLanguages(*update.Languages, "subtitle")
			if err != nil {
				return err
			}
			changes["subtitles.languages"] = encoded
		}
		if update.Mode != nil {
			changes["subtitles.mode"] = *update.Mode
		}
		if update.ForeignAudio != nil {
			changes["subtitles.foreignAudio"] = strconv.FormatBool(*update.ForeignAudio)
		}
		if update.AutomaticDownload != nil {
			changes["subtitles.automaticDownload"] = strconv.FormatBool(*update.AutomaticDownload)
		}
	}
	addsAudioLanguages := false
	if update := in.Body.Audio; update != nil && update.Languages != nil {
		encoded, err := encodedLanguages(*update.Languages, "audio")
		if err != nil {
			return err
		}
		changes["audio.languages"] = encoded
		addsAudioLanguages = len(*update.Languages) > 0
	}
	if len(changes) == 0 {
		return nil
	}
	tx, err := deps.Conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	q := deps.Queries.WithTx(tx)
	// Someone who sets audio languages and never chose how subtitles start
	// gets subtitles for audio they don't follow (PLAN-LANGUAGES.md §3). It
	// is saved now rather than worked out on each read, so a later save from
	// an older app, which always sends the mode it shows, leaves it alone.
	_, setsMode := changes["subtitles.mode"]
	_, setsForeign := changes["subtitles.foreignAudio"]
	if addsAudioLanguages && !setsMode && !setsForeign {
		rows, err := q.ListUserPreferences(ctx, userID)
		if err != nil {
			return err
		}
		if !slices.ContainsFunc(rows, func(r db.ListUserPreferencesRow) bool {
			return r.Key == "subtitles.mode" || r.Key == "subtitles.foreignAudio"
		}) {
			changes["subtitles.foreignAudio"] = "true"
		}
	}
	for _, key := range slices.Sorted(maps.Keys(changes)) {
		if err := q.UpsertUserPreference(ctx, db.UpsertUserPreferenceParams{UserID: userID, Key: key, Value: changes[key]}); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func encodedLanguages(langs []string, kind string) (string, error) {
	canonical, err := canonicalLanguages(langs, kind)
	if err != nil {
		return "", err
	}
	encoded, err := json.Marshal(canonical)
	return string(encoded), err
}
