package segments

import "math/bits"

// Matching thresholds. Measured on the same music in two encodes with the
// frames out of step, about 80% of points differ in 11 bits or fewer;
// unrelated audio, about 5%.
const (
	maxBitErrors = 11
	// densityMs is the window over which at least half the points must
	// match for audio to count as shared; it keeps runs of chance matches
	// out and edges tight.
	densityMs = 2000
	// gapMs bridges short stretches that differ inside shared audio (a
	// sound effect over the theme).
	gapMs = 3500
)

// Match is a stretch of audio two fingerprints share: points [AStart, AEnd)
// of a line up with [BStart, BEnd) of b.
type Match struct {
	AStart, AEnd, BStart, BEnd int
	// Matched is how many points in the stretch agree.
	Matched int
}

// Len is the match's length in points.
func (m Match) Len() int { return m.AEnd - m.AStart }

// LongestMatch finds the longest stretch of at least minLen points that a
// and b share, trying every alignment of one against the other.
func LongestMatch(a, b []uint32, minLen int) (Match, bool) {
	win, gap := max(Points(densityMs), 1), Points(gapMs)
	var best Match
	sum := make([]int32, min(len(a), len(b))+1) // prefix counts of matching points
	for shift := -(len(a) - 1); shift < len(b); shift++ {
		// Point i of a lines up with point i+shift of b.
		lo, hi := max(0, -shift), min(len(a), len(b)-shift)
		n := hi - lo
		if n < minLen || n <= best.Len() {
			continue
		}
		for k := range n {
			v := int32(0)
			if bits.OnesCount32(a[lo+k]^b[lo+k+shift]) <= maxBitErrors {
				v = 1
			}
			sum[k+1] = sum[k] + v
		}
		if int(sum[n])*2 < minLen { // too few matches for any run
			continue
		}
		// k is inside shared audio when half the window around it matches.
		inside := func(k int) bool {
			from, to := max(0, k-win/2), min(n, k+win/2+1)
			return 2*(sum[to]-sum[from]) >= int32(to-from)
		}
		start, last := -1, -1
		flush := func() {
			if start < 0 {
				return
			}
			// Trim to the first and last matching point.
			for start < last && sum[start+1] == sum[start] {
				start++
			}
			for last > start && sum[last+1] == sum[last] {
				last--
			}
			if l := last - start + 1; l >= minLen && l > best.Len() {
				best = Match{AStart: lo + start, AEnd: lo + last + 1, BStart: lo + start + shift, BEnd: lo + last + 1 + shift, Matched: int(sum[last+1] - sum[start])}
			}
			start = -1
		}
		for k := range n {
			if !inside(k) {
				if start >= 0 && k-last > gap {
					flush()
				}
				continue
			}
			if start < 0 {
				start = k
			}
			last = k
		}
		flush()
	}
	return best, best.Len() > 0
}
