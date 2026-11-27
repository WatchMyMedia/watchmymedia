package playback

import "strings"

// The kinds of reason a play was converted, for counting them. Decide's
// reasons are prose with figures in them ("bitrate 24000 kb/s exceeds the
// client's 8000 kb/s"); each is written in one of a few fixed shapes, which
// ReasonKind reads back.
const (
	ReasonVideoFormat   = "video-format"   // a codec, profile, bit depth or HDR the device can't play
	ReasonResolution    = "resolution"     // larger than the device takes
	ReasonBitrate       = "bitrate"        // more bits a second than the device takes
	ReasonSmaller       = "smaller"        // a quality below the original, chosen or Automatic's
	ReasonAudioFormat   = "audio-format"   // a sound format the device can't play
	ReasonAudioChannels = "audio-channels" // more channels than the device takes
	ReasonAudioTrack    = "audio-track"    // the chosen track needs a remux
	ReasonContainer     = "container"      // a file type the device can't open
	ReasonSubtitles     = "subtitles"      // subtitles burned into the picture
	ReasonOther         = "other"
)

// ReasonKinds are the kinds, in the order statistics list them.
var ReasonKinds = []string{ReasonVideoFormat, ReasonResolution, ReasonBitrate, ReasonSmaller, ReasonAudioFormat,
	ReasonAudioChannels, ReasonAudioTrack, ReasonContainer, ReasonSubtitles, ReasonOther}

// reasonShapes match a reason's start (or, with a leading "*", its middle)
// to its kind, first match winning.
var reasonShapes = []struct{ shape, kind string }{
	{"no video stream", ReasonVideoFormat},
	{"client cannot play Dolby Vision", ReasonVideoFormat},
	{"Dolby Vision *is sent as its", ReasonVideoFormat},
	{"*video", ReasonVideoFormat}, // client cannot decode <codec> video
	{"the screen cannot show", ReasonVideoFormat},
	{"*exceeds the client's", ""}, // resolution or bitrate, below
	{"client cannot decode", ReasonAudioFormat},
	{"*audio channels exceed", ReasonAudioChannels},
	{"PCM audio is copied only", ReasonAudioFormat},
	{"subtitle burn-in", ReasonSubtitles},
	{"selected audio track requires a remux", ReasonAudioTrack},
	{"audio track * from the person's audio languages", ReasonAudioTrack},
	{"client cannot play the", ReasonContainer},
}

// reasonNotes say what a conversion did, not why there was one: the play
// counts under its other reasons. The person's sound settings are notes
// too: they are a choice, not something the device lacks.
var reasonNotes = []string{
	"audio converted to ",
	"dialogue boosted",
	"loud sounds reduced",
	"loud sounds not reduced",
	"sound settings not applied",
	"the server cannot convert Dolby Vision",
	" failed, so the transcode runs in software",
}

// ReasonKind is the kind of a reason Decide (or a play's start) wrote, ""
// for a note that says how rather than why, and ReasonOther for words it
// doesn't know.
func ReasonKind(reason string) string {
	for _, n := range reasonNotes {
		if strings.Contains(reason, n) {
			return ""
		}
	}
	for _, s := range reasonShapes {
		if !shapeMatches(reason, s.shape) {
			continue
		}
		if s.kind != "" {
			return s.kind
		}
		if strings.HasPrefix(reason, "bitrate ") {
			return ReasonBitrate
		}
		return ReasonResolution
	}
	return ReasonOther
}

// shapeMatches reports whether reason has shape: its words in order, "*"
// standing for any words; a shape starting "*" may match mid-reason.
func shapeMatches(reason, shape string) bool {
	parts := strings.Split(shape, "*")
	if parts[0] != "" && !strings.HasPrefix(reason, parts[0]) {
		return false
	}
	rest := reason[len(parts[0]):]
	for _, p := range parts[1:] {
		i := strings.Index(rest, p)
		if i < 0 {
			return false
		}
		rest = rest[i+len(p):]
	}
	return true
}
