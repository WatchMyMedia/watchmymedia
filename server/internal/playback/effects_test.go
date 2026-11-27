package playback

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

const limiter = "alimiter=limit=-1dB:level=false"

// The chain for each source layout, target and strength.
func TestEffectsFilter(t *testing.T) {
	t.Parallel()
	medium, high := AudioEffects{Dialogue: EffectMedium}, AudioEffects{Dialogue: EffectHigh}
	loudM, loudH := AudioEffects{Loudness: EffectMedium}, AudioEffects{Loudness: EffectHigh}
	compM := "acompressor=threshold=-28dB:ratio=4:attack=5:release=250:makeup=8dB:link=maximum"
	compH := "acompressor=threshold=-32dB:ratio=6:attack=5:release=250:makeup=10dB:link=maximum"
	for _, c := range []struct {
		name    string
		effects AudioEffects
		layout  string
		n, to   int
		want    string
	}{
		// A source with a centre, down to stereo: more of the centre.
		{"5.1 to stereo, medium", medium, "5.1", 6, 2, "pan=stereo|c0=0.25*c0+0.55*c2+0.2*c4|c1=0.25*c1+0.55*c2+0.2*c5," + limiter},
		{"5.1 to stereo, high", high, "5.1", 6, 2, "pan=stereo|c0=0.2*c0+0.65*c2+0.15*c4|c1=0.2*c1+0.65*c2+0.15*c5," + limiter},
		{"5.1(side) to stereo takes the side channels", medium, "5.1(side)", 6, 2, "pan=stereo|c0=0.25*c0+0.55*c2+0.2*c4|c1=0.25*c1+0.55*c2+0.2*c5," + limiter},
		{"7.1 to stereo shares the surrounds", medium, "7.1", 8, 2, "pan=stereo|c0=0.25*c0+0.55*c2+0.1*c4+0.1*c6|c1=0.25*c1+0.55*c2+0.1*c5+0.1*c7," + limiter},
		{"3.0 gives the fronts the surrounds' share", medium, "3.0", 3, 2, "pan=stereo|c0=0.45*c0+0.55*c2|c1=0.45*c1+0.55*c2," + limiter},
		{"4.0's back centre feeds both sides", medium, "4.0", 4, 2, "pan=stereo|c0=0.25*c0+0.55*c2+0.2*c3|c1=0.25*c1+0.55*c2+0.2*c3," + limiter},
		{"5.1 to mono", medium, "5.1", 6, 1, "pan=stereo|c0=0.25*c0+0.55*c2+0.2*c4|c1=0.25*c1+0.55*c2+0.2*c5,pan=mono|c0=0.5*c0+0.5*c1," + limiter},
		{"an unnamed 6-channel layout is read as 5.1", medium, "", 6, 2, "pan=stereo|c0=0.25*c0+0.55*c2+0.2*c4|c1=0.25*c1+0.55*c2+0.2*c5," + limiter},
		{"ffmpeg's own listing of channels", medium, "FL+FR+FC", 3, 2, "pan=stereo|c0=0.45*c0+0.55*c2|c1=0.45*c1+0.55*c2," + limiter},
		// Kept in surround: the centre raised against the rest.
		{"5.1 kept, medium", medium, "5.1", 6, 6, "pan=5.1|c0=0.79*c0|c1=0.79*c1|c2=1.26*c2|c3=0.79*c3|c4=0.79*c4|c5=0.79*c5," + limiter},
		{"5.1 kept, high", high, "5.1", 6, 6, "pan=5.1|c0=0.63*c0|c1=0.63*c1|c2=1.41*c2|c3=0.63*c3|c4=0.63*c4|c5=0.63*c5," + limiter},
		{"5.1(side) keeps its layout", medium, "5.1(side)", 6, 6, "pan=5.1(side)|c0=0.79*c0|c1=0.79*c1|c2=1.26*c2|c3=0.79*c3|c4=0.79*c4|c5=0.79*c5," + limiter},
		{"7.1 to 5.1 lifts, then downmixes", medium, "7.1", 8, 6, "pan=7.1|c0=0.79*c0|c1=0.79*c1|c2=1.26*c2|c3=0.79*c3|c4=0.79*c4|c5=0.79*c5|c6=0.79*c6|c7=0.79*c7,aformat=channel_layouts=5.1," + limiter},
		// Stereo: dialoguenhance finds the centre, folded back with more of it.
		{"stereo, medium", medium, "stereo", 2, 2, "dialoguenhance=original=1:enhance=2:voice=8,pan=stereo|c0=0.4*c0+0.6*c2|c1=0.4*c1+0.6*c2," + limiter},
		{"stereo, high", high, "stereo", 2, 2, "dialoguenhance=original=1:enhance=3:voice=8,pan=stereo|c0=0.35*c0+0.65*c2|c1=0.35*c1+0.65*c2," + limiter},
		{"stereo to mono", medium, "stereo", 2, 1, "dialoguenhance=original=1:enhance=2:voice=8,pan=mono|c0=0.2*c0+0.2*c1+0.6*c2," + limiter},
		{"quad has no centre: down to stereo first", medium, "quad", 4, 2, "aformat=channel_layouts=stereo,dialoguenhance=original=1:enhance=2:voice=8,pan=stereo|c0=0.4*c0+0.6*c2|c1=0.4*c1+0.6*c2," + limiter},
		{"2.1's third channel is no centre", medium, "2.1", 3, 2, "aformat=channel_layouts=stereo,dialoguenhance=original=1:enhance=2:voice=8,pan=stereo|c0=0.4*c0+0.6*c2|c1=0.4*c1+0.6*c2," + limiter},
		{"an unnamed 5-channel layout assumes no centre", medium, "", 5, 5, "highpass=f=100,equalizer=f=2500:t=o:w=2:g=6," + limiter},
		// Mono: a lift in the speech band.
		{"mono, medium", medium, "mono", 1, 1, "highpass=f=100,equalizer=f=2500:t=o:w=2:g=6," + limiter},
		{"mono, high", high, "mono", 1, 1, "highpass=f=120,equalizer=f=2500:t=o:w=2:g=9," + limiter},
		// Reduce loud sounds: the compressor on what is sent, then the limiter.
		{"loud sounds, stereo", loudM, "stereo", 2, 2, compM + "," + limiter},
		{"loud sounds, high", loudH, "stereo", 2, 2, compH + "," + limiter},
		{"loud sounds on 5.1 sent as stereo", loudM, "5.1", 6, 2, "aformat=channel_layouts=stereo," + compM + "," + limiter},
		{"loud sounds on 5.1 kept", loudM, "5.1", 6, 6, compM + "," + limiter},
		{"both", AudioEffects{Dialogue: EffectHigh, Loudness: EffectMedium}, "5.1", 6, 2,
			"pan=stereo|c0=0.2*c0+0.65*c2+0.15*c4|c1=0.2*c1+0.65*c2+0.15*c5," + compM + "," + limiter},
		{"both off", AudioEffects{}, "5.1", 6, 2, ""},
	} {
		got, notes := EffectsFilter(c.effects, c.layout, c.n, c.to, AudioFilters{})
		if got != c.want || len(notes) != 0 {
			t.Errorf("%s:\n got %q %q\nwant %q", c.name, got, notes, c.want)
		}
	}
}

// An ffmpeg without a filter gets a chain it can run, and the notes say what
// was left out.
func TestEffectsFilterFallbacks(t *testing.T) {
	t.Parallel()
	both := AudioEffects{Dialogue: EffectMedium, Loudness: EffectMedium}
	got, notes := EffectsFilter(both, "stereo", 2, 2, AudioFilters{NoDialogueEnhance: true})
	want := "pan=stereo|c0=0.7*c0+0.3*c1|c1=0.3*c0+0.7*c1,highpass=f=100,equalizer=f=2500:t=o:w=2:g=6,acompressor=threshold=-28dB:ratio=4:attack=5:release=250:makeup=8dB:link=maximum," + limiter
	if got != want || !slices.Equal(notes, []string{"dialogue boosted without dialoguenhance, which the server's ffmpeg lacks"}) {
		t.Errorf("no dialoguenhance: %q %q", got, notes)
	}
	// A source with a centre doesn't need dialoguenhance.
	if _, notes := EffectsFilter(both, "5.1", 6, 2, AudioFilters{NoDialogueEnhance: true}); len(notes) != 0 {
		t.Errorf("5.1 without dialoguenhance: %q", notes)
	}
	applied, why := both.Within(AudioFilters{NoCompressor: true})
	if applied != (AudioEffects{Dialogue: EffectMedium}) || len(why) != 1 || ReasonKind(why[0]) != "" {
		t.Errorf("no compressor: %+v %q", applied, why)
	}
	applied, why = both.Within(AudioFilters{NoLimiter: true})
	if applied.On() || len(why) != 1 || ReasonKind(why[0]) != "" {
		t.Errorf("no limiter: %+v %q", applied, why)
	}
	if got, _ := EffectsFilter(both, "stereo", 2, 2, AudioFilters{NoLimiter: true}); got != "" {
		t.Errorf("no limiter: %q", got)
	}
}

func TestEffectsFromPreferences(t *testing.T) {
	t.Parallel()
	if e := Effects("off", "high"); e != (AudioEffects{Loudness: EffectHigh}) {
		t.Errorf("off, high: %+v", e)
	}
	if e := Effects("", "loud"); e.On() {
		t.Errorf("nothing known: %+v", e)
	}
	if got := (AudioEffects{Dialogue: EffectHigh, Loudness: EffectMedium}).Reasons(); !slices.Equal(got, []string{"dialogue boosted (high)", "loud sounds reduced (medium)"}) {
		t.Errorf("reasons %q", got)
	}
}

// With an effect on, sound that could be copied is converted, the picture
// still copied; never to FLAC, and never for a download.
func TestDecideWithEffects(t *testing.T) {
	t.Parallel()
	boost := AudioEffects{Dialogue: EffectMedium}
	aac := mkInfo("mov", "h264", 8, "", "aac", 2)
	aac.Audio[0].ChannelLayout = "stereo"
	d := Decide(aac, ".mp4", Request{Profile: webProfile(), Effects: boost}, nil, Capabilities{})
	if d.Mode != ModeAudio || !d.Video.Copy || d.Audio.Copy || d.Audio.Codec != "aac" || d.Audio.Effects != boost {
		t.Errorf("direct play with Boost dialogue: %s %+v", d.Mode, d.Audio)
	}
	if !slices.Contains(d.Reasons, "dialogue boosted (medium)") {
		t.Errorf("reasons %q", d.Reasons)
	}
	for _, r := range d.Reasons {
		if k := ReasonKind(r); k != "" {
			t.Errorf("%q counts as %s, not a note", r, k)
		}
	}
	if args := strings.Join(audioEncodeArgs(d.Audio), " "); !strings.HasPrefix(args, "-af dialoguenhance=") || !strings.Contains(args, "-c:a aac -b:a 192k -ac 2") {
		t.Errorf("args %q", args)
	}
	off := Decide(aac, ".mp4", Request{Profile: webProfile()}, nil, Capabilities{})
	if off.Mode != ModeDirect || off.Audio.Effects.On() {
		t.Errorf("both off: %s %+v", off.Mode, off.Audio)
	}
	download := Decide(aac, ".mp4", Request{Profile: webProfile(), Effects: boost, Download: true}, nil, Capabilities{})
	if download.Mode != ModeDirect || download.Audio.Effects.On() {
		t.Errorf("download: %s %+v", download.Mode, download.Audio)
	}

	// TrueHD that would have been FLAC is E-AC-3 5.1 instead: surround stays.
	truehd := mkInfo("matroska", "h264", 8, "", "truehd", 8)
	truehd.Audio[0].ChannelLayout = "7.1"
	loud := AudioEffects{Loudness: EffectHigh}
	d = Decide(truehd, ".mkv", Request{Profile: appleTVReceiver, Effects: loud}, nil, Capabilities{})
	if d.Mode != ModeAudio || d.Audio.Codec != "eac3" || d.Audio.Channels != 6 || d.Audio.Effects != loud {
		t.Errorf("TrueHD with Reduce loud sounds: %s %+v", d.Mode, d.Audio)
	}
	if args := strings.Join(audioEncodeArgs(d.Audio), " "); !strings.Contains(args, "aformat=channel_layouts=5.1,acompressor") {
		t.Errorf("args %q", args)
	}
	// Copied E-AC-3 on a receiver is converted to E-AC-3 too.
	eac3 := mkInfo("matroska", "h264", 8, "", "eac3", 6)
	eac3.Audio[0].ChannelLayout = "5.1(side)"
	d = Decide(eac3, ".mkv", Request{Profile: androidTVTrueHD, Effects: boost}, nil, Capabilities{})
	if d.Mode != ModeAudio || d.Audio.Codec != "eac3" || d.Audio.Channels != 6 {
		t.Errorf("E-AC-3 with Boost dialogue: %s %+v", d.Mode, d.Audio)
	}
	// A transcode converts the sound it would have copied.
	small := webProfile()
	small.MaxHeight = 720
	d = Decide(aac, ".mp4", Request{Profile: small, Effects: boost}, nil, Capabilities{Encoder: "libx264"})
	if d.Mode != ModeTranscode || d.Audio.Copy || d.Audio.Effects != boost {
		t.Errorf("transcode: %s %+v", d.Mode, d.Audio)
	}
	// No audio, nothing to do.
	silent := mkInfo("mov", "h264", 8, "", "aac", 2)
	silent.Audio = nil
	if d := Decide(silent, ".mp4", Request{Profile: webProfile(), Effects: boost}, nil, Capabilities{}); d.Mode != ModeDirect {
		t.Errorf("no audio: %s", d.Mode)
	}
	// Without a limiter nothing is applied, and the play stays as it was.
	d = Decide(aac, ".mp4", Request{Profile: webProfile(), Effects: boost}, nil, Capabilities{audio: AudioFilters{NoLimiter: true}})
	if d.Mode != ModeDirect || d.Audio.Effects.On() || !slices.Contains(d.Reasons, "sound settings not applied: the server's ffmpeg has no alimiter filter") {
		t.Errorf("no limiter: %s %+v %q", d.Mode, d.Audio, d.Reasons)
	}
}

// Effects split cached renditions: a play with them never reuses bytes
// made without them, or at another strength.
func TestRenditionContractSplitsByEffects(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "film.mkv")
	if err := os.WriteFile(path, []byte("fictional source bytes"), 0o600); err != nil {
		t.Fatal(err)
	}
	spec := Spec{FileID: 1, Path: path, Mode: ModeAudio, Video: VideoPlan{Copy: true, Codec: "h264"},
		Audio: AudioPlan{Index: 1, Codec: "aac", Channels: 2, Bitrate: 192_000}, BurnIn: -1, VOD: &VODOptions{DurationMs: 1000}}
	plain, err := renditionContract(spec)
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]string{plain: "off"}
	for _, e := range []AudioEffects{{Dialogue: EffectMedium}, {Dialogue: EffectHigh}, {Loudness: EffectMedium}, {Dialogue: EffectMedium, Loudness: EffectMedium}} {
		s := spec
		s.Audio.Effects, s.Audio.sourceLayout, s.Audio.sourceChannels = e, "5.1", 6
		key, err := renditionContract(s)
		if err != nil {
			t.Fatal(err)
		}
		if other, ok := seen[key]; ok {
			t.Errorf("%+v shares a rendition with %s", e, other)
		}
		seen[key] = e.Dialogue + "/" + e.Loudness
		again, _ := renditionContract(s)
		if again != key {
			t.Errorf("%+v: the same effects give another rendition", e)
		}
	}
}

// An adaptive family's shared audio keeps the play's effects, and its two
// rungs still match.
func TestAsAACKeepsEffects(t *testing.T) {
	t.Parallel()
	info := mkInfo("matroska", "h264", 8, "", "dts", 6)
	info.Audio[0].ChannelLayout = "5.1(side)"
	d := Decide(info, ".mkv", Request{Profile: webProfile(), Effects: AudioEffects{Dialogue: EffectHigh}}, nil, Capabilities{})
	a := d.Audio.AsAAC(2)
	if a.Effects != d.Audio.Effects || a.Codec != "aac" || a.Channels != 2 || a.effectsFilter() == "" || a != d.Audio.AsAAC(2) {
		t.Errorf("as AAC: %+v from %+v", a, d.Audio)
	}
	if !strings.HasPrefix(a.effectsFilter(), "pan=stereo|c0=0.2*c0+0.65*c2+0.15*c4") {
		t.Errorf("stereo from 5.1(side): %q", a.effectsFilter())
	}
}

func TestAudioFiltersFromListing(t *testing.T) {
	t.Parallel()
	listing := " T. alimiter          A->A       Audio lookahead limiter.\n .. acompressor       A->A       Audio compressor.\n"
	if f := audioFiltersFrom(listing); f != (AudioFilters{NoDialogueEnhance: true}) {
		t.Errorf("listing: %+v", f)
	}
	if f := audioFiltersFrom(""); f != (AudioFilters{NoDialogueEnhance: true, NoCompressor: true, NoLimiter: true}) {
		t.Errorf("empty listing: %+v", f)
	}
}
