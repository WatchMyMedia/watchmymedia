package playback

import (
	"regexp"
	"sort"

	"watchmymedia/server/internal/probe"
)

// Segment is a skippable part of a file, as the player receives it.
type Segment struct {
	ID      int64  `json:"id"`
	Kind    string `json:"kind" enum:"intro,recap,credits,preview,commercial"`
	StartMs int64  `json:"startMs"`
	// EndMs is 0 when the segment runs to the end of the file.
	EndMs      int64   `json:"endMs,omitempty"`
	Final      bool    `json:"final" doc:"On credits: the last credits block, after which nothing but previews follows."`
	Source     string  `json:"source" enum:"manual,chapter,fingerprint,blackframe,subtitle,external"`
	Confidence float64 `json:"confidence"`
}

// ChapterAlgoVersion changes whenever ChapterSegments does, so files are
// analysed again.
const ChapterAlgoVersion = 1

var (
	introTitle      = regexp.MustCompile(`(?i)\b(intro|opening|op|theme|title sequence|main titles?)\b`)
	recapTitle      = regexp.MustCompile(`(?i)\b(recap|previously)\b`)
	commercialTitle = regexp.MustCompile(`(?i)\b(commercials?|adverts?|ad break)\b`)
)

// Longest intro and recap a chapter may mark, and how far into the file
// they may start; past that a chapter called "Intro" is probably misnamed.
const (
	maxIntroMs = 4 * 60_000
	maxRecapMs = 5 * 60_000
)

// ChapterSegments turns chapter names into segments: intros and recaps in
// the first third, credits in the second half (the final block marked, see
// EndMarks), previews after the middle, and commercial breaks anywhere.
// Adjacent chapters of the same kind become one segment.
func ChapterSegments(chapters []probe.Chapter, durationMs int64) []Segment {
	finalCredits, _ := EndMarks(chapters, durationMs)
	var out []Segment
	for i, c := range chapters {
		end := c.EndMs
		if i+1 < len(chapters) {
			end = chapters[i+1].StartMs
		}
		if end <= 0 || end > durationMs {
			end = durationMs
		}
		if end <= c.StartMs {
			continue
		}
		kind := ""
		switch {
		case commercialTitle.MatchString(c.Title):
			kind = "commercial"
		case sceneTitle.MatchString(c.Title):
		case introTitle.MatchString(c.Title):
			if c.StartMs < durationMs/3 && end-c.StartMs <= maxIntroMs {
				kind = "intro"
			}
		case recapTitle.MatchString(c.Title):
			if c.StartMs < durationMs/3 && end-c.StartMs <= maxRecapMs {
				kind = "recap"
			}
		case creditsTitle.MatchString(c.Title):
			if c.StartMs >= durationMs/2 {
				kind = "credits"
			}
		case previewTitle.MatchString(c.Title):
			if c.StartMs >= durationMs/2 {
				kind = "preview"
			}
		}
		if kind == "" {
			continue
		}
		if n := len(out); n > 0 && out[n-1].Kind == kind && out[n-1].EndMs == c.StartMs {
			out[n-1].EndMs = end
			continue
		}
		out = append(out, Segment{Kind: kind, StartMs: c.StartMs, EndMs: end, Source: "chapter", Confidence: 1})
	}
	for i := range out {
		if out[i].EndMs >= durationMs {
			out[i].EndMs = 0 // to the end
		}
		if out[i].Kind == "credits" && finalCredits > 0 && out[i].StartMs <= finalCredits && (out[i].EndMs == 0 || finalCredits < out[i].EndMs) {
			out[i].Final = true
		}
	}
	return out
}

// sourceRank orders sources by trust: a person's edit beats a chapter mark,
// which beats detection.
var sourceRank = map[string]int{"manual": 0, "chapter": 1, "fingerprint": 2, "subtitle": 3, "blackframe": 4, "external": 5}

// PickSegments chooses which segments to play from everything stored: for
// each kind, a segment is dropped when one from a more trusted source
// overlaps it. The result is sorted by start.
func PickSegments(all []Segment, durationMs int64) []Segment {
	end := func(s Segment) int64 {
		if s.EndMs == 0 {
			return durationMs
		}
		return s.EndMs
	}
	sorted := append([]Segment(nil), all...)
	sort.SliceStable(sorted, func(i, j int) bool { return sourceRank[sorted[i].Source] < sourceRank[sorted[j].Source] })
	var out []Segment
	for _, s := range sorted {
		overlaps := false
		for _, o := range out {
			if o.Kind == s.Kind && s.StartMs < end(o) && o.StartMs < end(s) {
				overlaps = true
				break
			}
		}
		if !overlaps {
			out = append(out, s)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].StartMs < out[j].StartMs })
	return out
}

// CreditsAndStoryEnd reads the end of a file from its segments, as
// EndMarks does from chapters: where the final credits start (0 if none),
// and where the story after any earlier credits block ends, so a
// post-credits scene is not cut short.
func CreditsAndStoryEnd(segments []Segment, durationMs int64) (credits, storyEnd int64) {
	var lastCreditsEnd int64 = -1
	for _, s := range segments {
		if s.Kind != "credits" {
			continue
		}
		if s.Final {
			return s.StartMs, s.StartMs
		}
		lastCreditsEnd = s.EndMs
		if lastCreditsEnd == 0 {
			lastCreditsEnd = durationMs
		}
	}
	if lastCreditsEnd < 0 {
		return 0, 0
	}
	storyEnd = durationMs
	for _, s := range segments {
		if s.Kind == "preview" && s.StartMs >= lastCreditsEnd && s.StartMs < storyEnd {
			storyEnd = s.StartMs
		}
	}
	return 0, storyEnd
}

// WatchedAt is where an item counts as watched: the start of the final
// credits, or 92% of the way through without them.
func WatchedAt(segments []Segment, durationMs int64) int64 {
	if credits, _ := CreditsAndStoryEnd(segments, durationMs); credits > 0 {
		return min(credits, durationMs*92/100)
	}
	return durationMs * 92 / 100
}
