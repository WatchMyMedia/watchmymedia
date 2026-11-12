package api

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// errNoVOD is a file that cannot have a VOD playlist: no video, no known
// duration, or copied video on a server that does not read keyframes.
var errNoVOD = errors.New("no VOD playlist for this file")

// vodOptions describes a file for a VOD session, or reports that it cannot
// have one (errNoVOD) or not yet (playback.ErrKeyframesPending): copied
// video needs the file's keyframes, which a background scan may still be
// reading (a play then gets an event playlist).
func vodOptions(ctx context.Context, deps Deps, row db.MediaFile, info *probe.MediaInfo, d playback.Decision, req playback.Request, sidecars []playback.Sidecar) (*playback.VODOptions, error) {
	if len(info.Video) == 0 || info.DurationMs <= 0 {
		return nil, errNoVOD
	}
	o := &playback.VODOptions{DurationMs: info.DurationMs, FrameRate: info.Video[0].FrameRate}
	if d.Video.Copy {
		if deps.Keyframes == nil {
			return nil, errNoVOD
		}
		var keys []int64
		var err error
		if info.Extended {
			keys, err = deps.Keyframes.GetFrom(ctx, row.Path, row.Size, row.Mtime, info.StartUs)
		} else {
			keys, err = deps.Keyframes.Get(ctx, row.Path, row.Size, row.Mtime)
		}
		if errors.Is(err, playback.ErrKeyframesPending) {
			return nil, err
		}
		if err != nil {
			deps.Log.Warn("no keyframes for a VOD playlist", "file", row.ID, "err", err)
			return nil, errNoVOD
		}
		o.Keyframes = keys
	}
	o.Bandwidth = vodBandwidth(info, d, req.Profile)
	o.VideoRange = playback.HLSVideoRange(info, d)
	o.Codecs = playback.HLSCodecs(info, d)
	o.SupplementalCodecs = playback.HLSSupplementalCodecs(info, d)
	if req.Profile.SeparateAudio && d.Audio.Codec != "" {
		o.SeparateAudio, o.AudioChannels = true, d.Audio.Channels
		for _, a := range info.Audio {
			if a.Index == d.Audio.Index {
				o.AudioLanguage = bcp47(a.Language)
			}
		}
	}
	for _, t := range d.Subtitles {
		if t.Format == "image" || (d.BurnIn != nil && d.BurnIn.ID == t.ID) {
			continue
		}
		o.Subtitles = append(o.Subtitles, playback.VODSubtitle{ID: t.ID, Name: subtitleName(t), Language: bcp47(t.Language), Forced: t.Forced, Default: t.Default})
	}
	if len(o.Subtitles) > 0 && deps.Subtitles != nil {
		subs, id, path := deps.Subtitles, row.ID, row.Path
		o.Subtitle = func(ctx context.Context, track string) ([]byte, error) {
			n, err := strconv.Atoi(track[1:])
			if err != nil {
				return nil, err
			}
			if track[0] == 'd' {
				r, err := deps.OnlineSubtitles.Get(ctx, id, int64(n))
				if err != nil {
					return nil, err
				}
				return subs.Downloaded(ctx, r.ID, r.Path, "webvtt")
			}
			if track[0] == 'x' {
				if n >= len(sidecars) {
					return nil, fmt.Errorf("no sidecar %d", n)
				}
				return subs.Sidecar(ctx, id, n, sidecars[n], "webvtt")
			}
			return subs.Embedded(ctx, id, path, n, "webvtt")
		}
	}
	return o, nil
}

// vodBandwidth estimates the peak bit rate for the master playlist: the
// file's for copied video, the encoder's cap for a transcode.
func vodBandwidth(info *probe.MediaInfo, d playback.Decision, p playback.Profile) int64 {
	if d.Video.Copy {
		// BANDWIDTH is the peak, and a copied file's segments can run at
		// several times its average (AVPlayer logs a complaint for each
		// one over). With one variant a high figure costs nothing.
		if info.BitRate > 0 {
			return max(info.BitRate*3, 2_000_000)
		}
		return 40_000_000
	}
	video := p.MaxBitrate
	if video <= 0 {
		switch h := max(d.Video.Height, d.Video.Width*9/16); {
		case h >= 2000:
			video = 20_000_000
		case h >= 1000:
			video = 8_000_000
		case h >= 700:
			video = 4_000_000
		default:
			video = 2_000_000
		}
	}
	return playback.PeakBandwidth(video)
}

func subtitleName(t playback.SubtitleTrack) string {
	name := t.Title
	if name == "" {
		name = languageNames[bcp47(t.Language)]
	}
	if name == "" {
		name = "Subtitles"
	}
	if t.Forced && !strings.Contains(strings.ToLower(name), "forced") {
		name += " (forced)"
	}
	return name
}

// bcp47 turns ffprobe's ISO 639-2 language codes into the BCP 47 tags HLS
// wants ("eng" is "en"). Unknown codes are dropped rather than guessed.
func bcp47(lang string) string {
	lang = strings.ToLower(strings.TrimSpace(lang))
	if len(lang) == 2 {
		return lang
	}
	return iso6392[lang]
}

var iso6392 = map[string]string{
	"ara": "ar", "bul": "bg", "cat": "ca", "ces": "cs", "cze": "cs", "chi": "zh", "zho": "zh", "dan": "da", "deu": "de", "ger": "de",
	"ell": "el", "gre": "el", "eng": "en", "est": "et", "fas": "fa", "per": "fa", "fin": "fi", "fra": "fr", "fre": "fr", "heb": "he",
	"hin": "hi", "hrv": "hr", "hun": "hu", "ind": "id", "isl": "is", "ice": "is", "ita": "it", "jpn": "ja", "kor": "ko", "lav": "lv",
	"lit": "lt", "may": "ms", "msa": "ms", "nld": "nl", "dut": "nl", "nor": "no", "nob": "nb", "nno": "nn", "pol": "pl", "por": "pt",
	"ron": "ro", "rum": "ro", "rus": "ru", "slk": "sk", "slo": "sk", "slv": "sl", "spa": "es", "srp": "sr", "swe": "sv", "tam": "ta",
	"tel": "te", "tha": "th", "tur": "tr", "ukr": "uk", "urd": "ur", "vie": "vi", "fil": "fil", "tgl": "tl", "ben": "bn", "mal": "ml",
}

var languageNames = map[string]string{
	"ar": "Arabic", "bg": "Bulgarian", "ca": "Catalan", "cs": "Czech", "zh": "Chinese", "da": "Danish", "de": "German", "el": "Greek",
	"en": "English", "et": "Estonian", "fa": "Persian", "fi": "Finnish", "fr": "French", "he": "Hebrew", "hi": "Hindi", "hr": "Croatian",
	"hu": "Hungarian", "id": "Indonesian", "is": "Icelandic", "it": "Italian", "ja": "Japanese", "ko": "Korean", "lv": "Latvian",
	"lt": "Lithuanian", "ms": "Malay", "nl": "Dutch", "no": "Norwegian", "nb": "Norwegian Bokmål", "nn": "Norwegian Nynorsk",
	"pl": "Polish", "pt": "Portuguese", "ro": "Romanian", "ru": "Russian", "sk": "Slovak", "sl": "Slovenian", "es": "Spanish",
	"sr": "Serbian", "sv": "Swedish", "ta": "Tamil", "te": "Telugu", "th": "Thai", "tr": "Turkish", "uk": "Ukrainian", "ur": "Urdu",
	"vi": "Vietnamese", "fil": "Filipino", "tl": "Tagalog", "bn": "Bengali", "ml": "Malayalam",
}
