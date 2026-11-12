package playback

import (
	"cmp"
	"strings"

	"golang.org/x/text/language"

	"watchmymedia/server/internal/probe"
)

// AudioPreferences follow a person between players (PLAN-LANGUAGES.md).
// Unlike subtitles, no request opts in: a request that names no audioIndex
// starts on the preferred track, and every client already plays and shows
// the decision's track.
type AudioPreferences struct {
	Languages []string `json:"languages" nullable:"false" doc:"Preferred BCP 47 languages for the audio track, in order. Empty plays the file's default track."`
}

// DefaultAudioIndex is the stream Decide plays when a request names none:
// the first default-flagged audio stream, else the first; -1 when the file
// has no audio.
func DefaultAudioIndex(tracks []probe.AudioStream) int {
	if len(tracks) == 0 {
		return -1
	}
	for _, a := range tracks {
		if a.Default {
			return a.Index
		}
	}
	return tracks[0].Index
}

// PreferredAudio is the audio stream to start on for the person's languages:
// the best track in the first language that the file has. ok is false when
// none matches, and the file's default stays.
//
// Within a language it prefers the exact tag to the same base language, a
// track the profile can copy to one that needs transcoding, the file's
// default flag, more channels, then the lower index. Commentary and audio
// description are only ever picked by hand.
func PreferredAudio(tracks []probe.AudioStream, p Profile, languages []string) (index int, ok bool) {
	for _, want := range languages {
		var best *probe.AudioStream
		bestMatch := 0
		for i := range tracks {
			a := &tracks[i]
			if SecondaryAudio(*a) {
				continue
			}
			match := AudioLanguageMatch(a.Language, want)
			if match == 0 {
				continue
			}
			if best == nil || betterAudio(*a, match, *best, bestMatch, p) {
				best, bestMatch = a, match
			}
		}
		if best != nil {
			return best.Index, true
		}
	}
	return 0, false
}

func betterAudio(a probe.AudioStream, aMatch int, b probe.AudioStream, bMatch int, p Profile) bool {
	if aMatch != bMatch {
		return aMatch > bMatch
	}
	if ac, bc := copyableAudio(a, p), copyableAudio(b, p); ac != bc {
		return ac
	}
	if a.Default != b.Default {
		return a.Default
	}
	if c := cmp.Compare(a.Channels, b.Channels); c != 0 {
		return c > 0
	}
	return a.Index < b.Index
}

func copyableAudio(a probe.AudioStream, p Profile) bool {
	return has(p.AudioCodecs, audioKey(a)) && (p.MaxAudioChannels == 0 || a.Channels <= p.MaxAudioChannels)
}

// secondaryAudioWords mark a track nobody wants by default. "Director's Cut"
// is a main track, so "director" alone is not one of them.
var secondaryAudioWords = []string{"commentary", "description", "descriptive", "described"}

// SecondaryAudio is a commentary or audio-description track: by ffprobe's
// comment and visual_impaired dispositions where the probe has them, else by
// its title.
func SecondaryAudio(a probe.AudioStream) bool {
	if a.Commentary || a.Descriptive {
		return true
	}
	title := strings.ToLower(a.Title)
	for _, w := range secondaryAudioWords {
		if strings.Contains(title, w) {
			return true
		}
	}
	return false
}

// AudioLanguageMatch is LanguageMatch for speech: the script a language is
// written in does not matter, so a zh-Hant reader matches Chinese audio.
// Exact language and region is 2, the same base language 1.
func AudioLanguageMatch(got, want string) int {
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
	ab, _ := a.Base()
	bb, _ := b.Base()
	if ab == bb {
		return 1
	}
	return 0
}

// UnderstoodAudio says whether a person follows an audio track: its
// language is one of their audio languages or, when they have none, one of
// their subtitle languages. An untagged track, or a person with no languages
// at all, counts as understood, so files with und audio don't start with
// subtitles.
func UnderstoodAudio(audioLanguage string, audioLanguages, subtitleLanguages []string) bool {
	tag, err := language.Parse(strings.TrimSpace(audioLanguage))
	if err != nil || tag == language.Und {
		return true
	}
	known := audioLanguages
	if len(known) == 0 {
		known = subtitleLanguages
	}
	if len(known) == 0 {
		return true
	}
	for _, l := range known {
		if AudioLanguageMatch(audioLanguage, l) > 0 {
			return true
		}
	}
	return false
}
