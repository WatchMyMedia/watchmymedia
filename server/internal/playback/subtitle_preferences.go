package playback

import (
	"strings"

	"golang.org/x/text/language"
)

// SubtitlePreferences follow a person between players. A client opts into
// these defaults with Request.SubtitleMode; older players keep their picks.
type SubtitlePreferences struct {
	Languages []string `json:"languages" nullable:"false" doc:"Preferred BCP 47 languages, in order; empty until the person chooses or accepts their device's language."`
	Mode      string   `json:"mode" doc:"off, forced (only foreign-language parts in the audio's language), or on."`
	// ForeignAudio is a flag beside mode rather than a fourth mode: released
	// apps save the whole subtitle object, and read a mode they don't know
	// as Off (dropping the languages, on Apple). They leave out a field they
	// don't know, so a partial update keeps it.
	ForeignAudio      bool `json:"foreignAudio" required:"false" doc:"With mode forced: when the audio isn't in the person's languages, start as on does, in their subtitle languages or else their audio languages. Turned on when a person first saves audio languages without having saved a subtitle mode (API level 21)."`
	AutomaticDownload bool `json:"automaticDownload" doc:"When mode is on (or forced with foreignAudio, for foreign audio) and no local track matches, allow one exact-file subtitle download per play. Off by default."`
}

// StartingSubtitles resolves foreignAudio against the audio track: forced
// while the person follows the audio, on when they don't. It returns the
// mode to apply and the languages it uses (PLAN-LANGUAGES.md §5.3).
func StartingSubtitles(audioLanguage string, audioLanguages []string, p SubtitlePreferences) (mode string, languages []string) {
	if p.Mode == "forced" && p.ForeignAudio && !UnderstoodAudio(audioLanguage, audioLanguages, p.Languages) {
		if len(p.Languages) > 0 {
			return "on", p.Languages
		}
		return "on", audioLanguages
	}
	return p.Mode, p.Languages
}

// PreferredSubtitle chooses without network access. Full tracks take priority
// over forced-only tracks, and text over burn-in, in preferred language order.
// audioLanguages are the person's audio languages, which foreignAudio reads.
func PreferredSubtitle(tracks []SubtitleTrack, audioLanguage string, audioLanguages []string, p SubtitlePreferences) string {
	mode, languages := StartingSubtitles(audioLanguage, audioLanguages, p)
	switch mode {
	case "forced":
		return preferredTrack(tracks, audioLanguage, true)
	case "on":
		for _, forced := range []bool{false, true} {
			for _, lang := range languages {
				if id := preferredTrack(tracks, lang, forced); id != "" {
					return id
				}
			}
		}
	}
	return ""
}

func preferredTrack(tracks []SubtitleTrack, lang string, forced bool) string {
	bestID, bestScore := "", 0
	for _, t := range tracks {
		if t.Forced != forced {
			continue
		}
		match := LanguageMatch(t.Language, lang)
		if match == 0 {
			continue
		}
		score := match * 2
		if t.Format != "image" && !t.BurnInOnly {
			score += 8
		}
		if t.Default {
			score++
		}
		if score > bestScore {
			bestID, bestScore = t.ID, score
		}
	}
	return bestID
}

// LanguageMatch accepts the ISO 639-2 tags in media metadata as well as
// BCP 47 tags in preferences: exact language/region is 2, same base is 1.
// Unknown languages never match each other accidentally.
func LanguageMatch(got, want string) int {
	a, err := language.Parse(strings.TrimSpace(got))
	if err != nil || a == language.Und {
		return 0
	}
	b, err := language.Parse(strings.TrimSpace(want))
	if err != nil || b == language.Und {
		return 0
	}
	if a == b {
		return 2
	}
	// Do not substitute Simplified for Traditional Chinese (or another
	// explicitly different script) merely because the base language matches.
	_, wantedScript, wantedRegion := b.Raw()
	if wantedScript.String() != "Zzzz" || wantedRegion.String() != "ZZ" {
		as, _ := a.Script()
		bs, _ := b.Script()
		if as != bs {
			return 0
		}
	}
	ab, _ := a.Base()
	bb, _ := b.Base()
	if ab == bb {
		return 1
	}
	return 0
}

// The forced label and regional language are needed by starting-track
// selection too; keep the existing sidecar order and identifiers intact.
func sidecarSubtitleLabels(suffix string) (lang string, forced bool) {
	for part := range strings.SplitSeq(suffix, ".") {
		switch strings.ToLower(part) {
		case "forced":
			forced = true
			continue
		case "", "sdh", "cc", "default":
			continue
		}
		if lang == "" {
			if tag, err := language.Parse(part); err == nil && tag != language.Und {
				lang = tag.String()
			}
		}
	}
	return lang, forced
}
