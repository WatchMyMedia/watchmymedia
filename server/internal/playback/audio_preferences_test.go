package playback

import (
	"testing"

	"watchmymedia/server/internal/probe"
)

func TestPreferredAudio(t *testing.T) {
	t.Parallel()
	tracks := []probe.AudioStream{
		{Index: 1, Codec: "ac3", Channels: 6, Language: "eng", Default: true},
		{Index: 2, Codec: "truehd", Channels: 8, Language: "eng", Title: "TrueHD Atmos"},
		{Index: 3, Codec: "aac", Channels: 2, Language: "spa"},
		{Index: 4, Codec: "aac", Channels: 2, Language: "spa", Title: "Director's Commentary"},
		{Index: 5, Codec: "aac", Channels: 2, Language: "por"},
		{Index: 6, Codec: "aac", Channels: 2, Language: "pt-BR"},
		{Index: 7, Codec: "aac", Channels: 2, Language: "jpn", Descriptive: true},
		{Index: 8, Codec: "aac", Channels: 2, Language: "chi"},
		{Index: 9, Codec: "aac", Channels: 2, Language: "fre", Commentary: true},
		{Index: 10, Codec: "aac", Channels: 2, Language: "fre", Title: "Director's Cut"},
		{Index: 11, Codec: "aac", Channels: 2, Language: "und"},
	}
	ac3 := Profile{AudioCodecs: []string{"aac", "ac3"}}
	truehd := Profile{AudioCodecs: []string{"aac", "ac3", "truehd"}, MaxAudioChannels: 8}
	for _, tc := range []struct {
		name      string
		languages []string
		profile   Profile
		want      int // 0: no match, keep the file's default
	}{
		{"no languages", nil, ac3, 0},
		{"first language the file has", []string{"de", "es", "en"}, ac3, 3},
		{"ISO 639-2 against BCP 47", []string{"en"}, ac3, 1},
		{"copyable before more channels", []string{"en"}, Profile{AudioCodecs: []string{"aac", "truehd"}}, 2},
		{"default before more channels when both copy", []string{"en"}, truehd, 1},
		{"commentary never by language", []string{"es"}, Profile{AudioCodecs: []string{"aac"}}, 3},
		{"exact region before the base language", []string{"pt-BR"}, ac3, 6},
		{"base language when the region is missing", []string{"pt-PT"}, ac3, 5},
		{"audio description skipped", []string{"ja"}, ac3, 0},
		{"script does not matter for speech", []string{"zh-Hant"}, ac3, 8},
		{"commentary disposition skipped, Director's Cut kept", []string{"fr"}, ac3, 10},
		{"und never matches", []string{"und"}, ac3, 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := PreferredAudio(tracks, tc.profile, tc.languages)
			if tc.want == 0 {
				if ok {
					t.Fatalf("chose %d, want no match", got)
				}
				return
			}
			if !ok || got != tc.want {
				t.Fatalf("chose %d (%v), want %d", got, ok, tc.want)
			}
		})
	}
}

func TestDefaultAudioIndex(t *testing.T) {
	t.Parallel()
	if got := DefaultAudioIndex(nil); got != -1 {
		t.Errorf("no audio = %d", got)
	}
	if got := DefaultAudioIndex([]probe.AudioStream{{Index: 1}, {Index: 2, Default: true}}); got != 2 {
		t.Errorf("default-flagged = %d", got)
	}
	if got := DefaultAudioIndex([]probe.AudioStream{{Index: 3}, {Index: 4}}); got != 3 {
		t.Errorf("unflagged = %d", got)
	}
}

func TestUnderstoodAudio(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		audio          string
		audioLanguages []string
		subtitles      []string
		understood     bool
	}{
		{"eng", []string{"en"}, nil, true},
		{"kor", []string{"en"}, nil, false},
		{"kor", nil, []string{"en"}, false},
		{"kor", nil, nil, true},
		{"und", []string{"en"}, nil, true},
		{"", []string{"en"}, nil, true},
		{"spa", []string{"en", "es"}, []string{"fr"}, true},
		{"fra", []string{"en"}, []string{"fr"}, false},
		{"zho", []string{"zh-Hant"}, nil, true},
	} {
		if got := UnderstoodAudio(tc.audio, tc.audioLanguages, tc.subtitles); got != tc.understood {
			t.Errorf("UnderstoodAudio(%q, %v, %v) = %v", tc.audio, tc.audioLanguages, tc.subtitles, got)
		}
	}
}

func TestForeignAudioSubtitles(t *testing.T) {
	t.Parallel()
	tracks := []SubtitleTrack{
		{ID: "en-forced", Language: "eng", Forced: true, Format: "webvtt"},
		{ID: "en-full", Language: "eng", Format: "webvtt"},
		{ID: "es-full", Language: "spa", Format: "webvtt"},
	}
	foreign := SubtitlePreferences{Mode: "forced", ForeignAudio: true}
	withLanguages := func(p SubtitlePreferences, langs ...string) SubtitlePreferences {
		p.Languages = langs
		return p
	}
	for _, tc := range []struct {
		name           string
		prefs          SubtitlePreferences
		audio          string
		audioLanguages []string
		want           string
	}{
		{"followed audio: forced only", withLanguages(foreign, "es"), "eng", []string{"en"}, "en-forced"},
		{"foreign audio: subtitle languages", withLanguages(foreign, "es", "en"), "kor", []string{"en"}, "es-full"},
		{"foreign audio: audio languages when no subtitle languages", foreign, "kor", []string{"en"}, "en-full"},
		{"foreign by subtitle languages when no audio languages", withLanguages(foreign, "en"), "kor", nil, "en-full"},
		{"untagged audio counts as followed", withLanguages(foreign, "en"), "und", []string{"en"}, ""},
		{"no languages at all: forced only", foreign, "kor", nil, ""},
		{"on ignores the flag", SubtitlePreferences{Mode: "on", ForeignAudio: true, Languages: []string{"en"}}, "eng", []string{"en"}, "en-full"},
		{"off ignores the flag", SubtitlePreferences{Mode: "off", ForeignAudio: true, Languages: []string{"en"}}, "kor", []string{"en"}, ""},
		{"forced without the flag stays forced", SubtitlePreferences{Mode: "forced", Languages: []string{"en"}}, "kor", []string{"en"}, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := PreferredSubtitle(tracks, tc.audio, tc.audioLanguages, tc.prefs); got != tc.want {
				t.Errorf("selected %q, want %q", got, tc.want)
			}
		})
	}
}

// A track other than the file's default needs a remux for a player that
// can't switch it itself, and stays direct play for one that can.
func TestSelectsAudioTrackKeepsDirectPlay(t *testing.T) {
	t.Parallel()
	info := &probe.MediaInfo{Container: "mov", DurationMs: 60_000,
		Video: []probe.VideoStream{{Index: 0, Codec: "h264", Width: 1280, Height: 720}},
		Audio: []probe.AudioStream{
			{Index: 1, Codec: "aac", Channels: 2, Language: "eng", Default: true},
			{Index: 2, Codec: "aac", Channels: 2, Language: "spa"},
		},
	}
	spanish, english := 2, 1
	browser := Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}}
	native := browser
	native.SelectsAudioTrack = true
	for _, tc := range []struct {
		name    string
		profile Profile
		audio   *int
		mode    Mode
		index   int
	}{
		{"browser, default track", browser, nil, ModeDirect, 1},
		{"browser, default named", browser, &english, ModeDirect, 1},
		{"browser, another track", browser, &spanish, ModeRemux, 2},
		{"native player, another track", native, &spanish, ModeDirect, 2},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d := Decide(info, ".mp4", Request{Profile: tc.profile, AudioIndex: tc.audio}, nil, Capabilities{})
			if d.Mode != tc.mode || d.Audio.Index != tc.index {
				t.Fatalf("mode %s audio %d, want %s audio %d (%v)", d.Mode, d.Audio.Index, tc.mode, tc.index, d.Reasons)
			}
		})
	}
}
