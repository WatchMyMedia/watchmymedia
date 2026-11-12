package segments

import (
	"encoding/json"
	"fmt"
	"math"
	"math/rand"
	"testing"
)

// showEpisode builds an episode of total seconds: the intro theme at
// introAt (skipped if negative), its own audio elsewhere, and the credits
// theme in the last creditsLen seconds.
func showEpisode(seed int64, total, introAt, creditsLen float64, intro, credits []float64) []int16 {
	body := tune(seed, total)
	r := rand.New(rand.NewSource(seed * 13)) //nolint:gosec // G404: a fixed seed gives the same test audio every run
	out := make([]int16, len(body))
	at, cr := int(introAt*SampleRate), len(body)-int(creditsLen*SampleRate)
	for i := range body {
		v := body[i] * 0.6
		if introAt >= 0 && i >= at && i-at < len(intro) {
			v = intro[i-at]
		}
		if i >= cr && i-cr < len(credits) {
			v = credits[i-cr] * 0.8
		}
		v += 0.02 * r.NormFloat64()
		out[i] = int16(math.Max(-1, math.Min(1, v*0.5)) * 32767)
	}
	return out
}

func regionOf(samples []int16, startMs, lengthMs int64) Region {
	from := int(startMs * SampleRate / 1000)
	to := min(len(samples), from+int(lengthMs*SampleRate/1000))
	return Region{StartMs: startMs, Points: Fingerprint(samples[from:to]), Silences: Silences(samples[from:to])}
}

func TestDetectSeason(t *testing.T) {
	t.Parallel()
	intro, credits := tune(100, 40), tune(200, 50)
	const total = 8 * 60.0
	// Episode 1 is a pilot with no intro; the others have it at different points.
	introAt := []float64{-1, 0, 62, 30.5, 2}
	var eps []Episode
	for i, at := range introAt {
		s := showEpisode(int64(i+1), total, at, 50, intro, credits)
		dur := int64(total * 1000)
		is, il := IntroWindow(dur)
		cs, cl := CreditsWindow(dur)
		eps = append(eps, Episode{FileID: int64(i + 1), DurationMs: dur, Intro: regionOf(s, is, il), Credits: regionOf(s, cs, cl)})
	}
	found := DetectSeason(eps)
	got := map[string]Found{}
	for _, f := range found {
		got[fmt.Sprintf("%d %s", f.FileID, f.Kind)] = f
	}
	if _, ok := got["1 intro"]; ok {
		t.Errorf("pilot got an intro: %+v", got["1 intro"])
	}
	for i, at := range introAt[1:] {
		f, ok := got[fmt.Sprintf("%d intro", i+2)]
		if !ok {
			t.Errorf("episode %d: no intro", i+2)
			continue
		}
		wantStart := int64(at * 1000)
		if at*1000 <= snapToStartMs {
			wantStart = 0
		}
		if abs(f.StartMs-wantStart) > 1000 || abs(f.EndMs-int64(at*1000+40_000)) > 1500 || f.Confidence < 0.5 {
			t.Errorf("episode %d intro = %d-%d (conf %.2f), want %d-%d", i+2, f.StartMs, f.EndMs, f.Confidence, wantStart, int64(at*1000+40_000))
		}
	}
	for i := range introAt {
		f, ok := got[fmt.Sprintf("%d credits", i+1)]
		if !ok {
			t.Errorf("episode %d: no credits", i+1)
			continue
		}
		if abs(f.StartMs-int64((total-50)*1000)) > 1000 || f.EndMs != 0 || !f.Final {
			t.Errorf("episode %d credits = %+v, want from %d to the end, final", i+1, f, int64((total-50)*1000))
		}
	}
}

func TestSnapToSilence(t *testing.T) {
	t.Parallel()
	r := &Region{StartMs: 0, Silences: []int64{30_000, 41_500, 47_000}}
	if got := snapToSilence(42_000, r); got != 41_500 {
		t.Errorf("snap = %d, want 41500", got)
	}
	if got := snapToSilence(60_000, r); got != 60_000 {
		t.Errorf("no silence nearby: %d", got)
	}
}

func mustJSON(t *testing.T, v any) string {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}
