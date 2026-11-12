package segments

import (
	"slices"
	"strings"
	"testing"
)

func TestAdsRequireCoincidentBlackSilenceAndThreeRegularBoundaries(t *testing.T) {
	positive := adEvidence{black: []quietSpan{{120, 120.2}, {150, 150.2}, {180, 180.2}}, silence: []quietSpan{{119.9, 120.3}, {149.9, 150.3}, {179.9, 180.3}}}
	found := adBreaks(positive)
	if len(found) != 1 || found[0].StartMs != 120100 || found[0].EndMs != 180100 {
		t.Fatalf("%+v", found)
	}
	for name, e := range map[string]adEvidence{
		"black only": {black: positive.black}, "silence only": {silence: positive.silence},
		"scene fade":     {black: []quietSpan{{120, 130}, {150, 160}, {180, 190}}, silence: positive.silence},
		"two boundaries": {black: positive.black[:2], silence: positive.silence},
		"irregular gaps": {black: []quietSpan{{120, 120.2}, {141, 141.2}, {180, 180.2}}, silence: []quietSpan{{119.9, 120.3}, {140.9, 141.3}, {179.9, 180.3}}},
	} {
		t.Run(name, func(t *testing.T) {
			if got := adBreaks(e); len(got) != 0 {
				t.Fatalf("unsafe detection: %+v", got)
			}
		})
	}
}
func TestAdMetadataKeepsBoundariesAndRejectsNonFiniteValues(t *testing.T) {
	e, err := readAdEvidence(strings.NewReader("frame:3    pts:120     pts_time:0.12\nlavfi.black_start=NaN\nlavfi.black_start=10\ntimecode=00:00:10:02\nlavfi.silence_start=9.9\nlavfi.black_end=10.2\nlavfi.silence_duration=0.5\nlavfi.silence_end=10.4\nframe:4    pts:NOPTS   pts_time:NOPTS\n"))
	if err != nil || len(e.black) != 1 || len(e.silence) != 1 {
		t.Fatalf("%+v %v", e, err)
	}
}

// Lines torn between the video's and the audio's printers, as buffered
// printing wrote them, fail the pass rather than give a wrong time.
func TestAdMetadataRejectsGarbledLines(t *testing.T) {
	t.Parallel()
	for _, line := range []string{
		"17",
		"frame:12365 pts:44514480 pts_time:frame:1    pts:1536    pts_time:0.032",
		"frame:17814 pts:27362304 pts_time494.605333",
		"lavfi.black_start=10.2lavfi.silence_end=11",
		"lavfi.silence_end=1197.21frame:28453 pts:102431280 pts_time:1138.125333",
		"lavfi.black_end=4.5 6",
		"lavfi.black_start=",
		"timecode=00:18:58:04lavfi.black_start=12",
	} {
		if _, err := readAdEvidence(strings.NewReader("lavfi.black_start=1\n" + line + "\nlavfi.black_end=1.1\n")); err == nil {
			t.Errorf("accepted %q", line)
		}
	}
}

// evidenceAt is coincident black and silence around each boundary time.
func evidenceAt(times ...float64) adEvidence {
	var e adEvidence
	for _, t := range times {
		e.black = append(e.black, quietSpan{t - .1, t + .1})
		e.silence = append(e.silence, quietSpan{t - .15, t + .15})
	}
	return e
}

// every is n boundaries step seconds apart from start.
func every(start, step float64, n int) []float64 {
	out := make([]float64, n)
	for i := range out {
		out[i] = start + float64(i)*step
	}
	return out
}

func TestAdBreaksAreThirtySecondsToSixMinutes(t *testing.T) {
	t.Parallel()
	type span struct{ start, end int64 }
	for _, c := range []struct {
		name  string
		times []float64
		want  []span
	}{
		{"the shortest", every(100, 15, 3), []span{{100000, 130000}}},
		{"shorter than thirty seconds", []float64{100, 113, 126}, nil},
		{"six minutes", every(100, 15, 25), []span{{100000, 460000}}},
		{"longer than six minutes", every(100, 15, 26), nil},
		{"a long run is not cut into breaks", every(100, 15, 41), nil},
		{"a long run, then a break", append(every(100, 15, 30), every(1000, 30, 4)...), []span{{1000000, 1090000}}},
		{"two breaks", append(every(100, 15, 4), every(1000, 30, 3)...), []span{{100000, 145000}, {1000000, 1060000}}},
		{"a programme between", []float64{100, 115, 215, 230}, nil},
	} {
		t.Run(c.name, func(t *testing.T) {
			var got []span
			for _, s := range adBreaks(evidenceAt(c.times...)) {
				got = append(got, span{s.StartMs, s.EndMs})
			}
			if !slices.Equal(got, c.want) {
				t.Fatalf("breaks %v, want %v", got, c.want)
			}
		})
	}
}
