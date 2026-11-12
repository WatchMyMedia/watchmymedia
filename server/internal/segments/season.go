package segments

import (
	"slices"
)

// Where to look and what to accept, from Jellyfin's Intro Skipper defaults.
const (
	introWindowMs    = 10 * 60_000 // at most the first 10 minutes...
	introWindowShare = 0.25        // ...and at most the first quarter
	creditsWindowMs  = 450_000     // the last 7.5 minutes
	minSegmentMs     = 15_000
	maxIntroMs       = 120_000
	maxCreditsMs     = 450_000
	snapToStartMs    = 5000   // an intro starting this close to 0:00 starts at 0:00
	finalCreditsTail = 60_000 // credits ending this close to the end are the final block
	toEndMs          = 5000   // ...and this close, run to the end
	agreeOverlap     = 0.8    // two matches agree when they overlap this much
	singleConfidence = 0.6    // confidence when only one other episode was compared
	minConfidence    = 0.5
	neighbours       = 2 // each episode is compared with this many later ones
	snapBeforeMs     = 5000
	snapAfterMs      = 2000
)

// AlgoVersion changes whenever detection does, so seasons are analysed again.
const AlgoVersion = 1

// Region is one window of an episode's audio, fingerprinted.
type Region struct {
	StartMs  int64    // where the window starts in the file
	Points   []uint32 // fingerprint
	Silences []int64  // silence starts, ms from the window start
}

// Episode is one file of a season with its intro and credits windows.
type Episode struct {
	FileID     int64
	DurationMs int64
	Intro      Region
	Credits    Region
}

// Found is a segment detection settled on for one file.
type Found struct {
	FileID     int64
	Kind       string // intro or credits
	StartMs    int64
	EndMs      int64 // 0: to the end of the file
	Final      bool
	Confidence float64
}

// IntroWindow and CreditsWindow are the parts of a file to fingerprint.
func IntroWindow(durationMs int64) (start, length int64) {
	return 0, min(introWindowMs, int64(float64(durationMs)*introWindowShare))
}

func CreditsWindow(durationMs int64) (start, length int64) {
	start = max(durationMs-creditsWindowMs, durationMs/2)
	return start, durationMs - start
}

type span struct{ start, end int64 }

func (s span) overlap(o span) float64 {
	shared := min(s.end, o.end) - max(s.start, o.start)
	shorter := min(s.end-s.start, o.end-o.start)
	if shared <= 0 || shorter <= 0 {
		return 0
	}
	return float64(shared) / float64(shorter)
}

// DetectSeason compares each episode's intro and credits audio with its
// neighbours' and settles, per episode, on the stretch most comparisons
// agree on. Episodes must be in airing order.
func DetectSeason(eps []Episode) []Found {
	var out []Found
	out = append(out, detectRegion(eps, "intro", func(e *Episode) *Region { return &e.Intro }, maxIntroMs)...)
	out = append(out, detectRegion(eps, "credits", func(e *Episode) *Region { return &e.Credits }, maxCreditsMs)...)
	return out
}

func detectRegion(eps []Episode, kind string, region func(*Episode) *Region, maxMs int64) []Found {
	candidates := make([][]span, len(eps)) // matches found for each episode, ms in the file
	compared := make([]int, len(eps))
	minLen := Points(minSegmentMs)
	for i := range eps {
		for j := i + 1; j < len(eps) && j <= i+neighbours; j++ {
			ra, rb := region(&eps[i]), region(&eps[j])
			if len(ra.Points) == 0 || len(rb.Points) == 0 {
				continue
			}
			compared[i]++
			compared[j]++
			m, ok := LongestMatch(ra.Points, rb.Points, minLen)
			if !ok {
				continue
			}
			candidates[i] = append(candidates[i], span{ra.StartMs + FrameTime(m.AStart), ra.StartMs + FrameTime(m.AEnd-1)})
			candidates[j] = append(candidates[j], span{rb.StartMs + FrameTime(m.BStart), rb.StartMs + FrameTime(m.BEnd-1)})
		}
	}
	var out []Found
	for i, cands := range candidates {
		if len(cands) == 0 {
			continue
		}
		// The candidate most others agree with, and the median of those.
		var agreeing []span
		for _, c := range cands {
			var with []span
			for _, o := range cands {
				if c.overlap(o) >= agreeOverlap {
					with = append(with, o)
				}
			}
			if len(with) > len(agreeing) {
				agreeing = with
			}
		}
		conf := float64(len(agreeing)) / float64(compared[i])
		if compared[i] == 1 {
			conf = singleConfidence
		}
		if conf < minConfidence {
			continue
		}
		s := median(agreeing)
		if l := s.end - s.start; l < minSegmentMs || l > maxMs {
			continue
		}
		e := eps[i]
		f := Found{FileID: e.FileID, Kind: kind, StartMs: s.start, EndMs: s.end, Confidence: conf}
		switch kind {
		case "intro":
			if f.StartMs <= snapToStartMs {
				f.StartMs = 0
			}
			f.EndMs = snapToSilence(f.EndMs, region(&e))
		case "credits":
			f.Final = e.DurationMs-f.EndMs <= finalCreditsTail
			if e.DurationMs-f.EndMs <= toEndMs {
				f.EndMs = 0
			}
		}
		out = append(out, f)
	}
	return out
}

func median(spans []span) span {
	starts := make([]int64, len(spans))
	ends := make([]int64, len(spans))
	for i, s := range spans {
		starts[i], ends[i] = s.start, s.end
	}
	slices.Sort(starts)
	slices.Sort(ends)
	return span{starts[len(starts)/2], ends[len(ends)/2]}
}

// snapToSilence moves an intro's end onto the start of a silence just
// before it (up to 5 s) or just after (up to 2 s), the nearest one (ties go
// to the earlier),
// so the skip lands on the cut into the episode rather than mid-sound.
func snapToSilence(endMs int64, r *Region) int64 {
	best, found := endMs, false
	for _, s := range r.Silences {
		at := r.StartMs + s
		if at < endMs-snapBeforeMs || at > endMs+snapAfterMs {
			continue
		}
		if !found || abs(at-endMs) < abs(best-endMs) || (abs(at-endMs) == abs(best-endMs) && at < best) {
			best, found = at, true
		}
	}
	return best
}

func abs(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}
