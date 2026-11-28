package playback

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"math"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// effectsFilm writes a made-up 5.1 film of 30 s: speech-like sound in the
// centre (a voice's harmonics, in syllables), a low bed in every channel,
// and a two-second burst of noise from 5 s in every ten in the fronts, the
// surrounds and the LFE, each channel its own noise, so a burst is never
// taken for centred speech. Downmixed by ffmpeg, it is also the stereo and
// mono films.
func effectsFilm(t *testing.T, ffmpeg string, channels int) string {
	t.Helper()
	const speech = "0.12*(sin(2*PI*140*t)+0.7*sin(2*PI*280*t)+0.9*sin(2*PI*560*t)+0.6*sin(2*PI*1120*t)+0.35*sin(2*PI*2240*t)+0.2*sin(2*PI*3100*t))*max(0,sin(2*PI*2.7*t))*(0.6+0.4*sin(2*PI*0.37*t))"
	const gate = `if(between(mod(t\,10)\,5\,7)\,1\,0)`
	args := []string{"-v", "error", "-y", "-f", "lavfi", "-i", "aevalsrc=exprs='" + speech + "':s=48000:d=30"}
	for seed, colour := range []string{"pink", "pink", "pink", "pink", "brown"} {
		args = append(args, "-f", "lavfi", "-i", fmt.Sprintf("anoisesrc=seed=%d:color=%s:amplitude=0.9:d=30:r=48000", seed+1, colour))
	}
	args = append(args, "-f", "lavfi", "-i", "aevalsrc=exprs='0.01*sin(2*PI*220*t)+0.01*sin(2*PI*330*t)':s=48000:d=30")
	var g strings.Builder
	for i := 1; i <= 5; i++ {
		fmt.Fprintf(&g, "[%d]volume='%s':eval=frame[n%d];", i, gate, i)
	}
	g.WriteString("[6]asplit=6[b1][b2][b3][b4][b5][b6];")
	g.WriteString("[n1][b1]amix=inputs=2:normalize=0[fl];[n2][b2]amix=inputs=2:normalize=0[fr];[0][b3]amix=inputs=2:normalize=0[fc];")
	g.WriteString("[n5][b4]amix=inputs=2:normalize=0[lfe];[n3][b5]amix=inputs=2:normalize=0[bl];[n4][b6]amix=inputs=2:normalize=0[br];")
	g.WriteString("[fl][fr][fc][lfe][bl][br]join=inputs=6:channel_layout=5.1:map=0.0-FL|1.0-FR|2.0-FC|3.0-LFE|4.0-BL|5.0-BR[a]")
	out := filepath.Join(t.TempDir(), fmt.Sprintf("film%d.wav", channels))
	args = append(args, "-filter_complex", g.String(), "-map", "[a]", "-ac", fmt.Sprint(channels), "-c:a", "pcm_s16le", out)
	if b, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg film: %v\n%s", err, b)
	}
	return out
}

// soundLevels converts src with args as a session does, and measures the
// result: its codec and layout, its peak in dBFS, and how much louder the
// bursts are than the dialogue (the median of 100 ms blocks in the bursts
// against the 75th percentile outside them, every channel counted).
func soundLevels(t *testing.T, ffmpeg, src string, args []string) (codec, layout string, peak, gap float64) {
	t.Helper()
	out := filepath.Join(t.TempDir(), "out.mp4")
	cmd := append([]string{"-v", "error", "-y", "-i", src, "-map", "0:a:0"}, args...)
	if b, err := exec.Command(ffmpeg, append(cmd, "-f", "mp4", out)...).CombinedOutput(); err != nil {
		t.Fatalf("ffmpeg %q: %v\n%s", args, err, b)
	}
	ffprobe := filepath.Join(filepath.Dir(ffmpeg), "ffprobe")
	info, err := exec.Command(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,channels,channel_layout", "-of", "csv=p=0", out).Output()
	if err != nil {
		t.Fatalf("ffprobe: %v", err)
	}
	fields := strings.Split(strings.TrimSpace(string(info)), ",")
	if len(fields) < 3 {
		t.Fatalf("ffprobe: %q", info)
	}
	var channels int
	_, _ = fmt.Sscan(fields[1], &channels)
	raw, err := exec.Command(ffmpeg, "-v", "error", "-i", out, "-f", "f32le", "-").Output()
	if err != nil || channels == 0 {
		t.Fatalf("decode: %v", err)
	}
	samples := make([]float32, len(raw)/4)
	if err := binary.Read(bytes.NewReader(raw), binary.LittleEndian, samples); err != nil {
		t.Fatal(err)
	}
	block := 4800 * channels
	var dialogue, bursts []float64
	var top float64
	for i := 0; i+block <= len(samples); i += block {
		sum := 0.0
		for _, s := range samples[i : i+block] {
			sum += float64(s) * float64(s)
			top = max(top, math.Abs(float64(s)))
		}
		level := 10 * math.Log10(sum/float64(block)+1e-12)
		at := math.Mod(float64(i/block)/10+0.05, 10)
		switch {
		case at >= 5.25 && at <= 6.75:
			bursts = append(bursts, level)
		case (at < 4.75 || at > 7.25) && float64(i/block)/10 > 0.5:
			dialogue = append(dialogue, level)
		}
	}
	slices.Sort(dialogue)
	slices.Sort(bursts)
	return fields[0], fields[2], 20 * math.Log10(top), bursts[len(bursts)/2] - dialogue[len(dialogue)*3/4]
}

// Each chain, run by the ffmpeg on PATH on the made-up film: the converted
// track has the codec and layout asked for, no sample above −1 dBFS (with
// a little room for the encoder's own overshoot), and the bursts come
// closer to the dialogue at Medium and closer still at High.
func TestEffectsWithFFmpeg(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	caps := detectedCaps(ffmpeg, "software")
	if f := caps.AudioFilters(); f.NoCompressor || f.NoLimiter {
		t.Skipf("ffmpeg lacks the sound filters: %+v", f)
	}
	films := map[int]string{6: effectsFilm(t, ffmpeg, 6), 2: "", 1: ""}
	for _, n := range []int{2, 1} {
		out := filepath.Join(t.TempDir(), fmt.Sprintf("film%d.wav", n))
		if b, err := exec.Command(ffmpeg, "-v", "error", "-y", "-i", films[6], "-ac", fmt.Sprint(n), "-c:a", "pcm_s16le", out).CombinedOutput(); err != nil {
			t.Fatalf("downmix: %v\n%s", err, b)
		}
		films[n] = out
	}
	stereoAAC := Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxAudioChannels: 2}
	surroundAAC := Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac"}, MaxAudioChannels: 6}
	surroundEAC3 := Profile{Containers: []string{"mp4"}, VideoCodecs: []string{"h264"}, AudioCodecs: []string{"aac", "eac3"}, MaxAudioChannels: 6}
	layouts := map[int]string{6: "5.1", 2: "stereo", 1: "mono"}
	for _, c := range []struct {
		name     string
		channels int
		profile  Profile
		effect   func(string) AudioEffects
		codec    string
		layout   string
	}{
		{"5.1 to stereo AAC, Boost dialogue", 6, stereoAAC, func(s string) AudioEffects { return AudioEffects{Dialogue: s} }, "aac", "stereo"},
		{"5.1 to AAC 5.1, Boost dialogue", 6, surroundAAC, func(s string) AudioEffects { return AudioEffects{Dialogue: s} }, "aac", "5.1"},
		{"5.1 to E-AC-3 5.1, Boost dialogue", 6, surroundEAC3, func(s string) AudioEffects { return AudioEffects{Dialogue: s} }, "eac3", "5.1"},
		{"5.1 to stereo AAC, Reduce loud sounds", 6, stereoAAC, func(s string) AudioEffects { return AudioEffects{Loudness: s} }, "aac", "stereo"},
		{"5.1 to E-AC-3 5.1, Reduce loud sounds", 6, surroundEAC3, func(s string) AudioEffects { return AudioEffects{Loudness: s} }, "eac3", "5.1"},
		{"5.1 to stereo AAC, both", 6, stereoAAC, func(s string) AudioEffects { return AudioEffects{Dialogue: s, Loudness: s} }, "aac", "stereo"},
		{"5.1 to E-AC-3 5.1, both", 6, surroundEAC3, func(s string) AudioEffects { return AudioEffects{Dialogue: s, Loudness: s} }, "eac3", "5.1"},
		{"stereo, Boost dialogue", 2, stereoAAC, func(s string) AudioEffects { return AudioEffects{Dialogue: s} }, "aac", "stereo"},
		{"stereo, Reduce loud sounds", 2, stereoAAC, func(s string) AudioEffects { return AudioEffects{Loudness: s} }, "aac", "stereo"},
		{"mono, Reduce loud sounds", 1, stereoAAC, func(s string) AudioEffects { return AudioEffects{Loudness: s} }, "aac", "mono"},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			if c.channels == 2 && caps.AudioFilters().NoDialogueEnhance && c.effect("x").Dialogue != "" {
				t.Skip("ffmpeg has no dialoguenhance")
			}
			info := mkInfo("matroska", "h264", 8, "", "pcm_s16le", c.channels)
			info.Audio[0].Index, info.Audio[0].ChannelLayout = 0, layouts[c.channels]
			gaps := map[string]float64{}
			for _, strength := range []string{EffectOff, EffectMedium, EffectHigh} {
				var effects AudioEffects
				if strength != EffectOff {
					effects = c.effect(strength)
				}
				d := Decide(info, ".mkv", Request{Profile: c.profile, Effects: effects}, nil, caps)
				if d.Audio.Copy || d.Audio.Effects != effects {
					t.Fatalf("%s: %+v", strength, d.Audio)
				}
				codec, layout, peak, gap := soundLevels(t, ffmpeg, films[c.channels], audioEncodeArgs(d.Audio))
				t.Logf("%-6s gap %5.1f dB, peak %5.1f dBFS", strength, gap, peak)
				// E-AC-3 reads back as 5.1(side), which is the same six channels.
				if codec != c.codec || layout != c.layout && layout != c.layout+"(side)" {
					t.Errorf("%s: %s %s, want %s %s", strength, codec, layout, c.codec, c.layout)
				}
				if strength != EffectOff && peak > -0.7 {
					t.Errorf("%s: peak %.2f dBFS, over the limiter's −1 dBFS", strength, peak)
				}
				gaps[strength] = gap
			}
			// Mono's bursts and speech share a band: only the compressor
			// moves it, and it is checked like the rest.
			if gaps[EffectMedium] > gaps[EffectOff]-2 {
				t.Errorf("Medium narrows the gap from %.1f to %.1f dB only", gaps[EffectOff], gaps[EffectMedium])
			}
			if gaps[EffectHigh] > gaps[EffectMedium]-1 {
				t.Errorf("High (%.1f dB) is not narrower than Medium (%.1f dB)", gaps[EffectHigh], gaps[EffectMedium])
			}
		})
	}
}

// A session's ffmpeg runs the chain: the copied picture and the converted
// sound, with both effects on.
func TestEffectsSession(t *testing.T) {
	t.Parallel()
	ffmpeg := needFFmpeg(t)
	caps := detectedCaps(ffmpeg, "software")
	if f := caps.AudioFilters(); f != (AudioFilters{}) {
		t.Skipf("ffmpeg lacks the sound filters: %+v", f)
	}
	clip := sample(t, ffmpeg)
	m := testManager(t, ffmpeg, "software")
	plan := AudioPlan{Index: 1, Codec: "aac", Channels: 2, Bitrate: 192_000, Effects: AudioEffects{Dialogue: EffectHigh, Loudness: EffectMedium},
		sourceLayout: "stereo", sourceChannels: 2, filters: caps.AudioFilters()}
	s, err := m.Start(t.Context(), Spec{FileID: 1, PlayID: "p1", UserID: 1, Path: clip, Mode: ModeAudio, Video: VideoPlan{Codec: "mpeg4", Copy: true}, Audio: plan, BurnIn: -1})
	if err != nil {
		t.Fatal(err)
	}
	<-s.done
	if s.Err() != nil {
		t.Fatalf("%v\n%s", s.Err(), s.cmd.Tail())
	}
	if args := strings.Join(m.args(Spec{Path: clip, Mode: ModeAudio, Video: VideoPlan{Codec: "mpeg4", Copy: true}, Audio: plan, BurnIn: -1}, s.Dir), " "); !strings.Contains(args, "-af dialoguenhance=original=1:enhance=3") {
		t.Errorf("args %q", args)
	}
	ffprobe := filepath.Join(filepath.Dir(ffmpeg), "ffprobe")
	out, err := exec.Command(ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,channels", "-of", "csv=p=0", filepath.Join(s.Dir, "init.mp4")).CombinedOutput()
	if err != nil || strings.TrimSpace(string(out)) != "aac,2" {
		t.Errorf("init: %q %v", out, err)
	}
}
