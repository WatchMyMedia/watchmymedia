package playback

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPreferredSubtitle(t *testing.T) {
	t.Parallel()
	tracks := []SubtitleTrack{
		{ID: "en-forced", Language: "eng", Forced: true, Format: "webvtt"},
		{ID: "en-image", Language: "en", Format: "image", BurnInOnly: true},
		{ID: "en-full", Language: "en", Format: "webvtt"},
		{ID: "es-full", Language: "spa", Format: "webvtt"},
		{ID: "fr-forced", Language: "fra", Forced: true, Format: "webvtt"},
	}
	for _, tc := range []struct {
		name  string
		prefs SubtitlePreferences
		audio string
		want  string
	}{
		{"off", SubtitlePreferences{Mode: "off", Languages: []string{"en"}}, "eng", ""},
		{"unknown future mode is safe", SubtitlePreferences{Mode: "future", Languages: []string{"en"}}, "eng", ""},
		{"forced follows audio", SubtitlePreferences{Mode: "forced", Languages: []string{"es"}}, "eng", "en-forced"},
		{"forced unknown audio stays off", SubtitlePreferences{Mode: "forced"}, "und", ""},
		{"language order", SubtitlePreferences{Mode: "on", Languages: []string{"es", "en"}}, "eng", "es-full"},
		{"text before image and forced", SubtitlePreferences{Mode: "on", Languages: []string{"en"}}, "eng", "en-full"},
		{"full before forced fallback", SubtitlePreferences{Mode: "on", Languages: []string{"fr", "es"}}, "eng", "es-full"},
		{"forced fallback", SubtitlePreferences{Mode: "on", Languages: []string{"fr"}}, "eng", "fr-forced"},
		{"empty languages stays off", SubtitlePreferences{Mode: "on"}, "eng", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := PreferredSubtitle(tracks, tc.audio, nil, tc.prefs); got != tc.want {
				t.Errorf("selected %q, want %q", got, tc.want)
			}
		})
	}
}

func TestSubtitleLanguageMatch(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		got, want string
		match     int
	}{
		{"eng", "en", 2}, {"fra", "fr", 2}, {"pt-BR", "pt-BR", 2}, {"pt", "pt-BR", 1},
		{"en-US", "en-GB", 1}, {"und", "und", 0}, {"", "en", 0}, {"en", "es", 0},
		{"zh-Hans", "zh-Hant", 0}, {"zh-CN", "zh-Hant", 0}, {"zh-TW", "zh-Hant", 1},
	} {
		if got := LanguageMatch(tc.got, tc.want); got != tc.match {
			t.Errorf("LanguageMatch(%q, %q) = %d, want %d", tc.got, tc.want, got, tc.match)
		}
	}
}

func TestSubtitleSidecarPreferenceLabels(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	for _, name := range []string{"Clip.en.forced.srt", "Clip.pt-BR.srt", "Clip.sdh.eng.srt"} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte("subtitle"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	tracks := FindSidecars(filepath.Join(dir, "Clip.mp4"))
	if len(tracks) != 3 || tracks[0].Language != "en" || !tracks[0].Forced || tracks[1].Language != "pt-BR" || tracks[1].Forced || tracks[2].Language != "en" {
		t.Fatalf("sidecar labels = %+v", tracks)
	}
}
