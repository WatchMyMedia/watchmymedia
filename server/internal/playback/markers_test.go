package playback

import (
	"testing"

	"watchmymedia/server/internal/probe"
)

func TestEndMarks(t *testing.T) {
	t.Parallel()
	const dur = 45 * 60 * 1000
	ch := func(startMin float64, title string) probe.Chapter {
		return probe.Chapter{StartMs: int64(startMin * 60000), Title: title}
	}
	cases := []struct {
		name     string
		chapters []probe.Chapter
		want     int64
		durMin   int64 // 0 means 45
	}{
		{"none", nil, 0, 0},
		{"plain chapters", []probe.Chapter{ch(0, "Chapter 1"), ch(20, "Chapter 2"), ch(40, "Chapter 3")}, 0, 0},
		{"credits", []probe.Chapter{ch(0, "Intro"), ch(2, "Episode"), ch(43, "Credits")}, 43 * 60000, 0},
		{"end credits then preview", []probe.Chapter{ch(0, "Prologue"), ch(42.5, "End Credits"), ch(44, "Preview")}, 42.5 * 60000, 0},
		{"anime ED", []probe.Chapter{ch(0, "OP"), ch(1.5, "Part A"), ch(22, "ED"), ch(23.5, "Preview")}, 22 * 60000, 24},
		{"mid-credits scene keeps the first credits from counting", []probe.Chapter{ch(0, "Episode"), ch(40, "Credits"), ch(41, "Mid-credits scene"), ch(43, "Credits")}, 43 * 60000, 0},
		{"short sting after the credits", []probe.Chapter{ch(0, "Episode"), ch(42, "Credits"), ch(44.8, "Logo")}, 42 * 60000, 0},
		{"post-credits scene, however short", []probe.Chapter{ch(0, "Episode"), ch(40, "Credits"), ch(44.5, "Post-Credits Scene")}, 0, 0},
		{"story after the credits", []probe.Chapter{ch(0, "Episode"), ch(30, "Credits"), ch(31, "Epilogue")}, 0, 0},
		{"too early", []probe.Chapter{ch(5, "Credits")}, 0, 0},
		{"at the very end", []probe.Chapter{ch(44.95, "Credits")}, 0, 0},
		{"word inside a title", []probe.Chapter{ch(30, "The Ending of Days")}, 0, 0},
	}
	for _, c := range cases {
		d := int64(dur)
		if c.durMin > 0 {
			d = c.durMin * 60000
		}
		if got, _ := EndMarks(c.chapters, d); got != c.want {
			t.Errorf("%s: got %d, want %d", c.name, got, c.want)
		}
	}
}

func TestUpNextAt(t *testing.T) {
	t.Parallel()
	const min = int64(60_000)
	cases := []struct{ credits, storyEnd, dur, want int64 }{
		{43 * min, 43 * min, 45 * min, 43 * min},      // credits mark
		{0, 0, 45 * min, 45*min - 35_000},             // 40+ min
		{0, 0, 55 * min, 55*min - 40_000},             // 50+ min
		{0, 0, 22 * min, 22*min - 30_000},             // shorter
		{0, 0, 8 * min, 0},                            // short with no mark
		{7 * min, 7 * min, 8 * min, 7 * min},          // short with a mark
		{45*min - 2000, 0, 45 * min, 45*min - 35_000}, // mark too close to the end
		{0, 45 * min, 45 * min, 45 * min},             // post-credits scene runs to the end
	}
	for _, c := range cases {
		if got := UpNextAt(c.credits, c.storyEnd, c.dur); got != c.want {
			t.Errorf("UpNextAt(%d, %d, %d) = %d, want %d", c.credits, c.storyEnd, c.dur, got, c.want)
		}
	}
	// End to end: a post-credits scene holds the card until it is over.
	chapters := []probe.Chapter{{StartMs: 0, Title: "Episode"}, {StartMs: 40 * min, Title: "Credits"}, {StartMs: 44*min + 30_000, Title: "Post-Credits Scene"}}
	credits, storyEnd := EndMarks(chapters, 45*min)
	if got := UpNextAt(credits, storyEnd, 45*min); got != 45*min {
		t.Errorf("with a post-credits scene, up next at %d", got)
	}
}
