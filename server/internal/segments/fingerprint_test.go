package segments

import (
	"math"
	"math/rand"
	"testing"
)

// tune renders seconds of a random melody: notes of 0.2-0.5 s between 300
// Hz and 1.8 kHz with two harmonics, the same for the same seed.
func tune(seed int64, seconds float64) []float64 {
	r := rand.New(rand.NewSource(seed)) //nolint:gosec // G404: a fixed seed gives the same test audio every run
	out := make([]float64, int(seconds*SampleRate))
	for i := 0; i < len(out); {
		n := int((0.2 + 0.3*r.Float64()) * SampleRate)
		f := 300 * math.Pow(2, float64(r.Intn(31))/12)
		for k := 0; k < n && i < len(out); k, i = k+1, i+1 {
			t := float64(k) / SampleRate
			env := math.Min(1, t*40) * math.Exp(-2*t)
			out[i] = env * (math.Sin(2*math.Pi*f*t) + 0.5*math.Sin(4*math.Pi*f*t) + 0.25*math.Sin(6*math.Pi*f*t))
		}
	}
	return out
}

// episode places the theme at offset seconds inside other audio, with its
// own noise and volume, as two episodes of a show would have it.
func episode(theme []float64, offset, total float64, seed int64, gain float64) []int16 {
	body := tune(seed, total)
	r := rand.New(rand.NewSource(seed * 7)) //nolint:gosec // G404: a fixed seed gives the same test audio every run
	at := int(offset * SampleRate)
	out := make([]int16, len(body))
	for i := range body {
		v := body[i] * 0.6
		if j := i - at; j >= 0 && j < len(theme) {
			v = theme[j] * gain
		}
		v += 0.02 * r.NormFloat64()
		out[i] = int16(math.Max(-1, math.Min(1, v*0.5)) * 32767)
	}
	return out
}

func secs(points int) float64 { return float64(points) * FrameMs / 1000 }

func TestLongestMatchFindsSharedTheme(t *testing.T) {
	t.Parallel()
	theme := tune(1, 30)
	a := Fingerprint(episode(theme, 20, 120, 2, 1.0))
	b := Fingerprint(episode(theme, 47.03, 150, 3, 0.7)) // off the frame grid, quieter
	m, ok := LongestMatch(a, b, Points(15000))
	if !ok {
		t.Fatal("no match")
	}
	if got := secs(m.AStart); math.Abs(got-20) > 1 {
		t.Errorf("a starts at %.2fs, want 20", got)
	}
	if got := secs(m.BStart); math.Abs(got-47.03) > 1 {
		t.Errorf("b starts at %.2fs, want 47.03", got)
	}
	if got := secs(m.Len()); math.Abs(got-30) > 2 {
		t.Errorf("length %.2fs, want 30", got)
	}
	if d := float64(m.Matched) / float64(m.Len()); d < 0.8 {
		t.Errorf("only %.0f%% of points match", d*100)
	}
}

func TestLongestMatchIgnoresUnrelatedAudio(t *testing.T) {
	t.Parallel()
	a := Fingerprint(episode(tune(10, 30), 20, 120, 4, 1))
	b := Fingerprint(episode(tune(11, 30), 20, 120, 5, 1))
	if m, ok := LongestMatch(a, b, Points(15000)); ok {
		t.Errorf("unrelated audio matched for %.1fs (%d of %d points)", secs(m.Len()), m.Matched, m.Len())
	}
}

func TestSilences(t *testing.T) {
	t.Parallel()
	s := make([]int16, 4*SampleRate)
	for i := range s {
		if i < SampleRate || i >= 2*SampleRate { // loud except 1-2 s
			s[i] = int16(10000 * math.Sin(float64(i)))
		}
	}
	got := Silences(s)
	if len(got) != 1 || got[0] < 950 || got[0] > 1050 {
		t.Errorf("silences = %v, want one near 1000 ms", got)
	}
}
