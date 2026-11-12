package playback

import (
	"strings"
	"testing"
)

func TestVTTSegments(t *testing.T) {
	t.Parallel()
	in := "WEBVTT\r\n\r\nNOTE made by hand\r\n\r\n00:00:01.000 --> 00:00:03.500\r\nFirst line\r\n\r\n2\r\n00:07.900 --> 00:00:09.200 line:90%\r\nAcross a\r\nboundary\r\n\r\n00:00:30.000 --> 00:00:33.000\r\nHalf way\r\n\r\nbroken --> nope\r\nx\r\n"
	cues := parseVTT([]byte(in))
	if len(cues) != 3 || cues[1].start != 7_900_000 || cues[1].settings != "line:90%" || cues[1].text != "Across a\nboundary" {
		t.Fatalf("cues = %+v", cues)
	}
	seg := string(vttSegment(cues, 4_000_000, 8_000_000))
	if !strings.HasPrefix(seg, "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n") || strings.Contains(seg, "First line") || !strings.Contains(seg, "00:00:07.900 --> 00:00:09.200 line:90%\nAcross a\nboundary\n") {
		t.Errorf("segment 4-8:\n%s", seg)
	}
	// The spanning cue repeats in the next segment; an empty one is still valid.
	if next := string(vttSegment(cues, 8_000_000, 12_000_000)); !strings.Contains(next, "Across a") {
		t.Errorf("segment 8-12:\n%s", next)
	}
	if empty := string(vttSegment(cues, 12_000_000, 16_000_000)); strings.Contains(empty, "-->") {
		t.Errorf("segment 12-16:\n%s", empty)
	}
	if formatVTTTime(3_723_004_000) != "01:02:03.004" {
		t.Error(formatVTTTime(3_723_004_000))
	}
}
