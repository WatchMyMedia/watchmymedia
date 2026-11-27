package playback

import (
	"fmt"
	"slices"
	"strconv"
	"strings"
)

// Boost dialogue and Reduce loud sounds are done here, in the converted
// audio, so every app gets the same sound: most players give an app no way
// into the sound they decode, and a device that sends Dolby to a receiver
// can't change it at all. Each is off, medium or high per person.
//
// The chains were measured on a made-up 5.1 film (speech-like sound in the
// centre, a low bed everywhere, two-second bursts of noise in every other
// channel, each its own): the bursts' level against the dialogue's, in the
// result. Medium starts from what narrowed that gap without changing the
// mix's character; High goes further on each, and must still sound like a
// film. dynaudnorm and loudnorm are never used: both hold the sound back
// 2.5 to 3.5 s before any of it comes out, which every start and Live TV
// would wait for.

// Effect strengths.
const (
	EffectOff    = "off"
	EffectMedium = "medium"
	EffectHigh   = "high"
)

// ValidEffect says whether s is a strength a person can choose.
func ValidEffect(s string) bool { return s == EffectOff || s == EffectMedium || s == EffectHigh }

// AudioEffects are what the server does to a play's sound for the person
// watching. Plain strings rather than enums, so a strength added later
// doesn't break a client that reads this.
type AudioEffects struct {
	Dialogue string `json:"dialogue,omitempty" required:"false" doc:"Boost dialogue: medium or high. Absent when off."`
	Loudness string `json:"loudness,omitempty" required:"false" doc:"Reduce loud sounds: medium or high. Absent when off."`
}

// Effects are the person's two settings as the decision takes them: off
// (or anything unknown) leaves that one out.
func Effects(dialogue, loudness string) AudioEffects {
	strength := func(s string) string {
		if s == EffectMedium || s == EffectHigh {
			return s
		}
		return ""
	}
	return AudioEffects{Dialogue: strength(dialogue), Loudness: strength(loudness)}
}

// On says whether either effect is on.
func (e AudioEffects) On() bool { return e.Dialogue != "" || e.Loudness != "" }

// Reasons name the effects for the decision's reasons and Activity.
func (e AudioEffects) Reasons() []string {
	var r []string
	if e.Dialogue != "" {
		r = append(r, "dialogue boosted ("+e.Dialogue+")")
	}
	if e.Loudness != "" {
		r = append(r, "loud sounds reduced ("+e.Loudness+")")
	}
	return r
}

// AudioFilters says which of the optional sound filters the server's ffmpeg
// lacks. The zero value lacks none, as every build since FFmpeg 5.1 does;
// pan, equalizer, highpass and aformat are in every build.
type AudioFilters struct {
	NoDialogueEnhance bool `json:"noDialogueEnhance,omitempty"` // dialoguenhance, from FFmpeg 5.1
	NoCompressor      bool `json:"noCompressor,omitempty"`      // acompressor
	NoLimiter         bool `json:"noLimiter,omitempty"`         // alimiter
}

// audioFiltersFrom reads ffmpeg's -filters listing.
func audioFiltersFrom(listing string) AudioFilters {
	has := func(name string) bool { return strings.Contains(listing, " "+name+" ") }
	return AudioFilters{NoDialogueEnhance: !has("dialoguenhance"), NoCompressor: !has("acompressor"), NoLimiter: !has("alimiter")}
}

// Within is what of e ffmpeg can do with f, and why anything was left out.
// Every chain ends with the limiter, since each effect can raise peaks, so
// without one neither is applied. Without the compressor Reduce loud sounds
// is left out. Without dialoguenhance Boost dialogue still works, from a
// stereo source too (dialogueStereo).
func (e AudioEffects) Within(f AudioFilters) (AudioEffects, []string) {
	if !e.On() {
		return e, nil
	}
	if f.NoLimiter {
		return AudioEffects{}, []string{"sound settings not applied: the server's ffmpeg has no alimiter filter"}
	}
	if e.Loudness != "" && f.NoCompressor {
		e.Loudness = ""
		return e, []string{"loud sounds not reduced: the server's ffmpeg has no acompressor filter"}
	}
	return e, nil
}

// The limiter that ends every chain: no sample above −1 dBFS, without
// changing the level below it.
const effectsLimiter = "alimiter=limit=-1dB:level=false"

// The compressor at each strength, linked across channels so a surround mix
// keeps its balance. High starts lower and presses harder, with more makeup
// so speech stays where Medium puts it.
var loudnessCompressor = map[string]string{
	EffectMedium: "acompressor=threshold=-28dB:ratio=4:attack=5:release=250:makeup=8dB:link=maximum",
	EffectHigh:   "acompressor=threshold=-32dB:ratio=6:attack=5:release=250:makeup=10dB:link=maximum",
}

// Down to stereo from a source with a centre, the share of each output
// taken from the front on its side, the centre and the surrounds on its
// side (ffmpeg's own downmix is about 0.41, 0.29 and 0.29).
var centreStereo = map[string][3]float64{
	EffectMedium: {0.25, 0.55, 0.20},
	EffectHigh:   {0.20, 0.65, 0.15},
}

// Kept in surround, the centre's gain and everything else's: about 4 dB
// between them at Medium (+2, −2) and 7 dB at High (+3, −4).
var centreLift = map[string][2]float64{
	EffectMedium: {1.26, 0.79},
	EffectHigh:   {1.41, 0.63},
}

// dialoguenhance's strength, and the share of its centre in the stereo it
// is folded back to.
var stereoEnhance = map[string]struct {
	enhance int
	centre  float64
}{
	EffectMedium: {2, 0.6},
	EffectHigh:   {3, 0.65},
}

// Without dialoguenhance, a stereo source favours its middle, where
// dialogue sits: each side keeps this share of itself and takes the rest
// from the other, which lowers what is wide against what is centred.
var stereoMiddle = map[string]float64{
	EffectMedium: 0.7,
	EffectHigh:   0.6,
}

// speechLift is a lift in the speech band, for mono, which has no centre to
// find; High lifts it more and cuts a little more of the low end.
var speechLift = map[string]string{
	EffectMedium: "highpass=f=100,equalizer=f=2500:t=o:w=2:g=6",
	EffectHigh:   "highpass=f=120,equalizer=f=2500:t=o:w=2:g=9",
}

// layoutChannels are ffmpeg's standard channel layouts, by name, in the
// order their channels are decoded.
var layoutChannels = map[string]string{
	"mono": "FC", "stereo": "FL+FR", "2.1": "FL+FR+LFE", "3.0": "FL+FR+FC", "3.0(back)": "FL+FR+BC",
	"4.0": "FL+FR+FC+BC", "quad": "FL+FR+BL+BR", "quad(side)": "FL+FR+SL+SR", "3.1": "FL+FR+FC+LFE",
	"5.0": "FL+FR+FC+BL+BR", "5.0(side)": "FL+FR+FC+SL+SR", "4.1": "FL+FR+FC+LFE+BC",
	"5.1": "FL+FR+FC+LFE+BL+BR", "5.1(side)": "FL+FR+FC+LFE+SL+SR", "6.0": "FL+FR+FC+BC+SL+SR",
	"6.0(front)": "FL+FR+FLC+FRC+SL+SR", "3.1.2": "FL+FR+FC+LFE+TFL+TFR", "hexagonal": "FL+FR+FC+BL+BR+BC",
	"6.1": "FL+FR+FC+LFE+BC+SL+SR", "6.1(back)": "FL+FR+FC+LFE+BL+BR+BC", "6.1(front)": "FL+FR+LFE+FLC+FRC+SL+SR",
	"7.0": "FL+FR+FC+BL+BR+SL+SR", "7.0(front)": "FL+FR+FC+FLC+FRC+SL+SR", "7.1": "FL+FR+FC+LFE+BL+BR+SL+SR",
	"7.1(wide)": "FL+FR+FC+LFE+BL+BR+FLC+FRC", "7.1(wide-side)": "FL+FR+FC+LFE+FLC+FRC+SL+SR",
	"5.1.2": "FL+FR+FC+LFE+SL+SR+TFL+TFR", "5.1.2(back)": "FL+FR+FC+LFE+BL+BR+TFL+TFR",
	"octagonal": "FL+FR+FC+BL+BR+BC+SL+SR", "7.1.2": "FL+FR+FC+LFE+BL+BR+SL+SR+TFL+TFR",
	"5.1.4": "FL+FR+FC+LFE+SL+SR+TFL+TFR+TBL+TBR", "7.1.4": "FL+FR+FC+LFE+BL+BR+SL+SR+TFL+TFR+TBL+TBR",
}

// sourceChannels are the source's channels in decoding order: from its
// layout's name, or the channels ffmpeg lists ("FL+FR+FC"), or for a
// layout ffmpeg didn't name the usual one for 1, 2, 6 and 8 channels. nil
// when the count matches none, and nothing is assumed about the channels.
func sourceChannels(layout string, n int) []string {
	names := layoutChannels[layout]
	if names == "" && strings.Contains(layout, "+") {
		names = layout
	}
	if names == "" {
		names = map[int]string{1: layoutChannels["mono"], 2: layoutChannels["stereo"], 6: layoutChannels["5.1"], 8: layoutChannels["7.1"]}[n]
	}
	if names == "" {
		return nil
	}
	chans := strings.Split(names, "+")
	if n > 0 && len(chans) != n {
		return nil
	}
	return chans
}

// targetLayout is what a converted track of n channels is sent as.
func targetLayout(n int) string {
	switch n {
	case 1:
		return "mono"
	case 2:
		return "stereo"
	case 6:
		return "5.1"
	case 8:
		return "7.1"
	}
	return strconv.Itoa(n) + "c"
}

// EffectsFilter is the -af chain for effects e on a source of layout and n
// channels converted to target channels, with ffmpeg's filters f, and notes
// for the reasons when it had to make do. Empty when e is off. Channels are
// named by their place (c0, c1…), which works whatever ffmpeg calls the
// source's layout; the place each has comes from the layout's name.
func EffectsFilter(e AudioEffects, layout string, n, target int, f AudioFilters) (string, []string) {
	e, _ = e.Within(f)
	if !e.On() {
		return "", nil
	}
	var chain, notes []string
	if n <= 0 {
		// The source's channels aren't known (a broadcast that wasn't
		// inspected): convert to the target's first, and work from that.
		n = target
		if n <= 0 {
			n = 2
		}
		layout = targetLayout(n)
		chain = append(chain, "aformat=channel_layouts="+layout)
	}
	if target <= 0 || target > n {
		target = n
	}
	chans := sourceChannels(layout, n)
	at := func(name string) int { return slices.Index(chans, name) }
	// down converts to the target's channels inside the chain, so that the
	// compressor and the limiter work on what is sent.
	down := func(from int) {
		if target < from {
			chain = append(chain, "aformat=channel_layouts="+targetLayout(target))
		}
	}
	switch {
	case e.Dialogue == "":
		down(n)
	case at("FC") >= 0 && n >= 3:
		chain = append(chain, centreDialogue(e.Dialogue, chans, target)...)
	case n == 1:
		chain = append(chain, speechLift[e.Dialogue])
	default:
		// Stereo, or more channels with no centre: dialogue is what the
		// fronts share.
		if n > 2 {
			if target > 2 {
				chain = append(chain, speechLift[e.Dialogue])
				down(n)
				break
			}
			chain = append(chain, "aformat=channel_layouts=stereo")
		}
		stereo, note := dialogueStereo(e.Dialogue, target, f)
		chain = append(chain, stereo...)
		if note != "" {
			notes = append(notes, note)
		}
	}
	if e.Loudness != "" {
		chain = append(chain, loudnessCompressor[e.Loudness])
	}
	chain = append(chain, effectsLimiter)
	return strings.Join(chain, ","), notes
}

// centreDialogue favours a source's centre: down to stereo or mono with
// more of it and less of the rest, or kept in surround with the centre
// raised against the rest, before any conversion to the target's channels.
func centreDialogue(strength string, chans []string, target int) []string {
	index := func(names ...string) []int {
		var out []int
		for _, n := range names {
			if i := slices.Index(chans, n); i >= 0 {
				out = append(out, i)
			}
		}
		return out
	}
	centre := index("FC")[0]
	if target >= 3 {
		g := centreLift[strength]
		terms := make([]string, len(chans))
		for i := range chans {
			gain := g[1]
			if i == centre {
				gain = g[0]
			}
			terms[i] = fmt.Sprintf("c%d=%s*c%d", i, coefficient(gain), i)
		}
		layout := ""
		for name, names := range layoutChannels {
			if names == strings.Join(chans, "+") && (layout == "" || name < layout) {
				layout = name
			}
		}
		if layout == "" {
			layout = strconv.Itoa(len(chans)) + "c"
		}
		chain := []string{"pan=" + layout + "|" + strings.Join(terms, "|")}
		if target < len(chans) {
			chain = append(chain, "aformat=channel_layouts="+targetLayout(target))
		}
		return chain
	}
	w := centreStereo[strength]
	left, right := index("FL"), index("FR")
	leftBack, rightBack := index("BL", "SL"), index("BR", "SR")
	back := index("BC")
	front, surround := w[0], w[2]
	if len(leftBack) == 0 && len(rightBack) == 0 && len(back) == 0 {
		front, surround = front+surround, 0 // 3.0 and 3.1: the surrounds' share goes to the fronts
	}
	side := func(fronts, backs []int) string {
		var terms []string
		for _, i := range fronts {
			terms = append(terms, fmt.Sprintf("%s*c%d", coefficient(front/float64(len(fronts))), i))
		}
		terms = append(terms, fmt.Sprintf("%s*c%d", coefficient(w[1]), centre))
		all := append(slices.Clone(backs), back...)
		for _, i := range all {
			terms = append(terms, fmt.Sprintf("%s*c%d", coefficient(surround/float64(len(all))), i))
		}
		return strings.Join(terms, "+")
	}
	l, r := side(left, leftBack), side(right, rightBack)
	if target == 1 {
		// Mono: both sides' shares, halved, so it is as loud as the stereo.
		return []string{"pan=stereo|c0=" + l + "|c1=" + r, "pan=mono|c0=0.5*c0+0.5*c1"}
	}
	return []string{"pan=stereo|c0=" + l + "|c1=" + r}
}

// dialogueStereo boosts the dialogue of a stereo source: dialoguenhance
// finds what the two sides share and adds a centre (3.0), which is folded
// back into two channels with more of it, or one. Without dialoguenhance
// the middle is favoured with a pan, and the speech band lifted; the note
// says so.
func dialogueStereo(strength string, target int, f AudioFilters) ([]string, string) {
	if f.NoDialogueEnhance {
		keep := stereoMiddle[strength]
		chain := []string{fmt.Sprintf("pan=stereo|c0=%s*c0+%s*c1|c1=%s*c0+%s*c1", coefficient(keep), coefficient(1-keep), coefficient(1-keep), coefficient(keep)), speechLift[EffectMedium]}
		if target == 1 {
			chain = append(chain, "pan=mono|c0=0.5*c0+0.5*c1")
		}
		return chain, "dialogue boosted without dialoguenhance, which the server's ffmpeg lacks"
	}
	s := stereoEnhance[strength]
	chain := []string{fmt.Sprintf("dialoguenhance=original=1:enhance=%d:voice=8", s.enhance)}
	side := coefficient(1 - s.centre)
	if target == 1 {
		half := coefficient((1 - s.centre) / 2)
		return append(chain, fmt.Sprintf("pan=mono|c0=%s*c0+%s*c1+%s*c2", half, half, coefficient(s.centre))), ""
	}
	return append(chain, fmt.Sprintf("pan=stereo|c0=%s*c0+%s*c2|c1=%s*c1+%s*c2", side, coefficient(s.centre), side, coefficient(s.centre))), ""
}

// coefficient writes a gain with at most three decimals.
func coefficient(g float64) string {
	return strconv.FormatFloat(float64(int64(g*1000+0.5))/1000, 'f', -1, 64)
}
