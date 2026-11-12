package livetv

import "testing"

func TestLiveTargetDurationUsesPublishedOutput(t *testing.T) {
	for _, tc := range []struct {
		value string
		want  int64
	}{{"2", 2_000}, {"6", 6_000}, {"0", 0}, {"-1", 0}, {"NaN", 0}, {"2.5", 0}, {"61", 0}, {"999999999999999999999", 0}} {
		if got := liveTargetDurationMs([]byte("#EXTM3U\n#EXT-X-TARGETDURATION:" + tc.value + "\n")); got != tc.want {
			t.Errorf("target %q: got %d, want %d", tc.value, got, tc.want)
		}
	}
	if got := liveTargetDurationMs([]byte("#EXTM3U\n")); got != 0 {
		t.Fatalf("missing target duration: %d", got)
	}
}

func TestLiveStartupWaitsForCompleteBufferedMedia(t *testing.T) {
	const header = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n"
	const piece = "#EXTINF:2.002,\nsegment-000000001.ts\n"
	for _, tc := range []struct {
		name, playlist string
		ready          bool
	}{
		{"empty", header, false},
		{"first segment", header + piece, false},
		{"two segments", header + piece + piece, false},
		{"three segments", header + piece + piece + piece, true},
		{"long GOP", header + "#EXTINF:8.0,\nsegment-000000001.ts\n", true},
		{"unfinished segment", header + piece + piece + "#EXTINF:2.002,\n", false},
		{"missing URI", header + "#EXTINF:3.0,\n#EXTINF:3.0,\nsegment-000000001.ts\n", false},
		{"unknown duration", header + "#EXTINF:NaN,\nsegment-000000001.ts\n", false},
		{"infinite duration", header + "#EXTINF:Inf,\nsegment-000000001.ts\n", false},
		{"negative duration", header + "#EXTINF:-6,\nsegment-000000001.ts\n", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := liveStartupReady([]byte(tc.playlist)); got != tc.ready {
				t.Fatalf("startup ready = %v, want %v", got, tc.ready)
			}
		})
	}
}
