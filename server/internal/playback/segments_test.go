package playback

import (
	"fmt"
	"strings"
	"testing"

	"watchmymedia/server/internal/probe"
)

// chapters builds back-to-back chapters from "title@minutes" pairs.
func chapters(spec ...string) []probe.Chapter {
	var out []probe.Chapter
	for _, s := range spec {
		title, at, _ := strings.Cut(s, "@")
		var m float64
		_, _ = fmt.Sscanf(at, "%g", &m)
		out = append(out, probe.Chapter{StartMs: int64(m * 60000), Title: title})
	}
	return out
}

func describe(segs []Segment) string {
	var parts []string
	for _, s := range segs {
		p := fmt.Sprintf("%s %g-%g", s.Kind, float64(s.StartMs)/60000, float64(s.EndMs)/60000)
		if s.Final {
			p += " final"
		}
		parts = append(parts, p)
	}
	return strings.Join(parts, ", ")
}

func TestChapterSegments(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name     string
		chapters []probe.Chapter
		durMin   float64
		want     string
	}{
		{"recap, intro, credits, preview", chapters("Recap@0", "Intro@1.5", "Episode@2.5", "Credits@42", "Preview@44"), 45,
			"recap 0-1.5, intro 1.5-2.5, credits 42-44 final, preview 44-0"},
		{"anime", chapters("OP@0", "Part A@1.5", "Part B@12", "ED@21.5", "Next Time@23"), 24,
			"intro 0-1.5, credits 21.5-23 final, preview 23-0"},
		{"mid-credits scene", chapters("Episode@0", "Credits@40", "Mid-credits scene@41", "Credits@43"), 45,
			"credits 40-41, credits 43-0 final"},
		{"adjacent credits merge", chapters("Episode@0", "Credits@40", "End Credits@42"), 45, "credits 40-0 final"},
		{"opening credits are the intro", chapters("Opening Credits@0", "Episode@1"), 45, "intro 0-1"},
		{"misnamed long intro", chapters("Intro@0", "Episode@10"), 45, ""},
		{"intro late in the file", chapters("Episode@0", "Theme@30", "More@31"), 45, ""},
		{"credits in the first half", chapters("Episode@0", "Credits@10", "More@12"), 45, ""},
		{"plain chapters", chapters("Chapter 1@0", "Chapter 2@20"), 45, ""},
		{"commercial break", chapters("Part 1@0", "Commercial@15", "Part 2@18"), 45, "commercial 15-18"},
	}
	for _, c := range cases {
		got := describe(ChapterSegments(c.chapters, int64(c.durMin*60000)))
		if got != c.want {
			t.Errorf("%s:\n got %q\nwant %q", c.name, got, c.want)
		}
	}
}

func TestPickSegments(t *testing.T) {
	t.Parallel()
	all := []Segment{
		{ID: 1, Kind: "intro", StartMs: 60_000, EndMs: 150_000, Source: "chapter"},
		{ID: 2, Kind: "intro", StartMs: 62_000, EndMs: 148_000, Source: "manual"},
		{ID: 3, Kind: "credits", StartMs: 2_500_000, Source: "fingerprint"},
		{ID: 4, Kind: "recap", StartMs: 0, EndMs: 61_000, Source: "fingerprint"}, // overlaps the intro, but another kind
		{ID: 5, Kind: "credits", StartMs: 2_520_000, Source: "chapter"},
	}
	var ids []int64
	for _, s := range PickSegments(all, 2_700_000) {
		ids = append(ids, s.ID)
	}
	if fmt.Sprint(ids) != "[4 2 5]" {
		t.Errorf("picked %v, want [4 2 5]", ids)
	}
}

func TestCreditsStoryEndAndWatchedAt(t *testing.T) {
	t.Parallel()
	const dur = 45 * 60000
	final := ChapterSegments(chapters("Episode@0", "Credits@42", "Preview@44"), dur)
	if c, s := CreditsAndStoryEnd(final, dur); c != 42*60000 || s != 42*60000 {
		t.Errorf("final credits: %d %d", c, s)
	}
	// Credits then a post-credits scene and a preview: no final credits, the
	// story runs until the preview.
	post := ChapterSegments(chapters("Episode@0", "Credits@40", "Post-credits scene@43", "Preview@44"), dur)
	if c, s := CreditsAndStoryEnd(post, dur); c != 0 || s != 44*60000 {
		t.Errorf("post-credits: %d %d (%s)", c, s, describe(post))
	}
	if c, s := CreditsAndStoryEnd(nil, dur); c != 0 || s != 0 {
		t.Errorf("none: %d %d", c, s)
	}
	early := ChapterSegments(chapters("Episode@0", "Credits@38"), dur)
	if w := WatchedAt(early, dur); w != 38*60000 {
		t.Errorf("watched at %d with credits at 38 min", w)
	}
	if w := WatchedAt(nil, dur); w != dur*92/100 {
		t.Errorf("watched at %d without credits", w)
	}
	// Credits starting after 92% do not hold watched back.
	if w := WatchedAt(final, dur); w != dur*92/100 {
		t.Errorf("watched at %d with credits at 42 min", w)
	}
}
