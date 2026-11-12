package playback

import (
	"regexp"
	"slices"

	"watchmymedia/server/internal/probe"
)

// Chapter names used for end credits ("Credits", "End Credits", "Ending",
// "ED", "Outro"), for scenes inside or after them ("Mid-credits scene",
// "Post-credits"), which are story, and for previews of the next episode.
var (
	creditsTitle = regexp.MustCompile(`(?i)\b(credits|outro)\b|^\s*(ed|ending|end)\s*$`)
	sceneTitle   = regexp.MustCompile(`(?i)\b(mid|post|after)[\s-]*credits?\b|\bscene\b|\bstinger\b`)
	previewTitle = regexp.MustCompile(`(?i)\b(preview|next time|next episode|coming up)\b`)
)

// shortChapter is how long a chapter after the credits may run and still
// count as part of them (a sting or a logo); anything longer is story, such
// as a mid-credits scene, and the credits before it are not the last ones.
const shortChapter = 15_000

// EndMarks reads the end of a file from its chapters. credits is where the
// final end credits begin, or 0 when no chapter looks like credits; storyEnd
// is where the last story chapter ends (a post-credits scene, say), or 0
// when the chapters say nothing about it. It walks back from the end over
// credits, previews and short chapters, so a mid-credits scene keeps the
// credits before it from counting. Only credits in the second half that
// start at least 5 seconds before the end count.
func EndMarks(chapters []probe.Chapter, durationMs int64) (credits, storyEnd int64) {
	for i, c := range slices.Backward(chapters) {
		end := c.EndMs
		if i+1 < len(chapters) {
			end = chapters[i+1].StartMs
		}
		if end <= 0 || end > durationMs {
			end = durationMs
		}
		switch {
		case sceneTitle.MatchString(c.Title):
			return credits, end
		case creditsTitle.MatchString(c.Title):
			if c.StartMs >= durationMs/2 && c.StartMs < durationMs-5000 {
				credits = c.StartMs
			}
		case previewTitle.MatchString(c.Title) || end-c.StartMs < shortChapter:
		default:
			if credits > 0 {
				return credits, credits
			}
			return 0, end
		}
	}
	return credits, credits
}

// UpNextAt is when the player should offer the next episode: at the final
// credits when chapters mark them, otherwise shortly before the end, longer
// for longer episodes (as Jellyfin does), but never before a post-credits
// scene ends. 0 means do not offer it, for files under 10 minutes without a
// credits mark.
func UpNextAt(creditsMs, storyEndMs, durationMs int64) int64 {
	if creditsMs > 0 && durationMs-creditsMs >= 5000 {
		return creditsMs
	}
	if durationMs < 10*60_000 {
		return 0
	}
	before := int64(30_000)
	switch {
	case durationMs >= 50*60_000:
		before = 40_000
	case durationMs >= 40*60_000:
		before = 35_000
	}
	return min(max(durationMs-before, storyEndMs), durationMs)
}
