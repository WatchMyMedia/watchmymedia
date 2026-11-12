package playback

import (
	"fmt"
	"strconv"
	"strings"
)

// vttCue is one WebVTT cue with its times in microseconds.
type vttCue struct {
	start, end int64
	settings   string
	text       string
}

// parseVTT reads the cues of a WebVTT file (as ffmpeg writes them); header,
// NOTE, STYLE and REGION blocks are dropped.
func parseVTT(data []byte) []vttCue {
	text := strings.ReplaceAll(strings.ReplaceAll(string(data), "\r\n", "\n"), "\r", "\n")
	var cues []vttCue
	for block := range strings.SplitSeq(text, "\n\n") {
		lines := strings.Split(strings.Trim(block, "\n"), "\n")
		i := 0
		for i < len(lines) && !strings.Contains(lines[i], "-->") {
			i++
		}
		if i >= len(lines) || i > 1 { // no timing line, or not one where a cue's would be
			continue
		}
		timing := strings.SplitN(lines[i], "-->", 2)
		start, ok1 := parseVTTTime(strings.TrimSpace(timing[0]))
		rest := strings.Fields(timing[1])
		if len(rest) == 0 {
			continue
		}
		end, ok2 := parseVTTTime(rest[0])
		if !ok1 || !ok2 || end <= start {
			continue
		}
		cues = append(cues, vttCue{start: start, end: end, settings: strings.Join(rest[1:], " "), text: strings.Join(lines[i+1:], "\n")})
	}
	return cues
}

// parseVTTTime reads hh:mm:ss.ttt or mm:ss.ttt.
func parseVTTTime(s string) (int64, bool) {
	parts := strings.Split(s, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return 0, false
	}
	var total float64
	for _, p := range parts[:len(parts)-1] {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return 0, false
		}
		total = total*60 + float64(n)
	}
	sec, err := strconv.ParseFloat(parts[len(parts)-1], 64)
	if err != nil || sec < 0 {
		return 0, false
	}
	return int64((total*60+sec)*1e6 + 0.5), true
}

func formatVTTTime(us int64) string {
	ms := us / 1000
	return fmt.Sprintf("%02d:%02d:%02d.%03d", ms/3_600_000, ms/60_000%60, ms/1000%60, ms%1000)
}

// vttSegment is one HLS subtitle segment: the cues that overlap [from, to),
// in file time, with a timestamp map that puts file time zero where the
// video's fragments have it (vodTimeOffset). A cue that spans segments is
// repeated in each, as HLS expects.
func vttSegment(cues []vttCue, from, to int64) []byte {
	var b strings.Builder
	fmt.Fprintf(&b, "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:%d,LOCAL:00:00:00.000\n\n", vodTimeOffset*90000)
	for _, c := range cues {
		if c.end <= from || c.start >= to {
			continue
		}
		b.WriteString(formatVTTTime(c.start))
		b.WriteString(" --> ")
		b.WriteString(formatVTTTime(c.end))
		if c.settings != "" {
			b.WriteString(" " + c.settings)
		}
		b.WriteString("\n" + c.text + "\n\n")
	}
	return []byte(b.String())
}
