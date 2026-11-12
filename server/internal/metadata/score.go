package metadata

import (
	"math"
	"regexp"
	"slices"
	"sort"
	"strings"
	"unicode"

	"golang.org/x/text/unicode/norm"
)

// Candidate is a possible TMDB match for a file, scored against the parsed
// name. The top few are stored on unmatched files so the UI can offer them.
type Candidate struct {
	TMDBID     int     `json:"tmdbId"`
	Title      string  `json:"title"`
	Year       int     `json:"year,omitempty"`
	Overview   string  `json:"overview,omitempty"`
	PosterPath string  `json:"posterPath,omitempty"`
	Score      float64 `json:"score"`
	// Country is the origin country for shows ("US"), used for "(US)" suffixes.
	Country string `json:"country,omitempty"`

	// How well the reading of the file's name that fits best matches, for accept.
	titleSim, yearSim float64
	yearKnown         bool
}

// A reading is one name a file may go by, its folder's or its own when
// that differs, with the year written beside it, if any.
type reading struct {
	title string
	year  *int
}

// scoreInput is what a search result contributes to scoring.
type scoreInput struct {
	Candidate
	OriginalTitle string
	Popularity    float64
	Countries     []string
}

var (
	countrySuffixRe = regexp.MustCompile(`\s*\(([A-Z]{2,3})\)\s*$`)
	nonAlnumRe      = regexp.MustCompile(`[^a-z0-9 ]+`)
	// Apostrophes are dropped, not turned into spaces, as names on disk
	// leave them out: "Coopers Bar" is "Cooper's Bar".
	apostropheRe = regexp.MustCompile("['’‘`]")
	// A dollar sign that ends a word stands for an s: "McMillion$".
	dollarRe = regexp.MustCompile(`([a-z])\$`)
)

// tmdbCountry is the code TMDB uses for a country written in a name: names
// say "UK" where TMDB says "GB".
func tmdbCountry(code string) string {
	if code == "UK" {
		return "GB"
	}
	return code
}

// splitCountry removes a trailing "(US)" from a parsed title.
func splitCountry(title string) (string, string) {
	if m := countrySuffixRe.FindStringSubmatch(title); m != nil {
		return strings.TrimSpace(title[:len(title)-len(m[0])]), m[1]
	}
	return title, ""
}

// Normalize is a name as matching compares it: lower case, without accents,
// punctuation or a leading "the", whether stored composed or decomposed.
// Live TV's guide matches listings to TMDB by it too.
func Normalize(s string) string { return normalize(s) }

func normalize(s string) string {
	s = strings.ToLower(s)
	s = strings.ReplaceAll(s, "&", " and ")
	s = dollarRe.ReplaceAllString(s, "${1}s")
	s = apostropheRe.ReplaceAllString(s, "")
	s = norm.NFD.String(s)
	s = strings.Map(func(r rune) rune {
		if unicode.Is(unicode.Mn, r) {
			return -1
		}
		return r
	}, s)
	s = nonAlnumRe.ReplaceAllString(s, " ")
	s = strings.Join(strings.Fields(s), " ")
	s = strings.TrimPrefix(s, "the ")
	return s
}

// titleSimilarity is 1 for an exact normalized match, or one that differs
// only in spaces ("MotherFatherSon"), and degrades with edit distance and
// token overlap; whichever is kinder wins. The same words in another order
// or repeated ("Rose Red" for "Red Rose", "The Way Way Down" for "The Way
// Down") are close but never the same.
func titleSimilarity(a, b string) float64 {
	na, nb := normalize(a), normalize(b)
	if na == "" || nb == "" {
		return 0
	}
	if na == nb || strings.ReplaceAll(na, " ", "") == strings.ReplaceAll(nb, " ", "") {
		return 1
	}
	lev := 1 - float64(levenshtein(na, nb))/float64(max(len(na), len(nb)))
	return math.Max(lev, math.Min(jaccard(strings.Fields(na), strings.Fields(nb)), 0.9))
}

func jaccard(a, b []string) float64 {
	set := map[string]bool{}
	for _, w := range a {
		set[w] = true
	}
	inter, union := 0, len(set)
	seen := map[string]bool{}
	for _, w := range b {
		if seen[w] {
			continue
		}
		seen[w] = true
		if set[w] {
			inter++
		} else {
			union++
		}
	}
	if union == 0 {
		return 0
	}
	return float64(inter) / float64(union)
}

func levenshtein(a, b string) int {
	ra, rb := []rune(a), []rune(b)
	prev := make([]int, len(rb)+1)
	cur := make([]int, len(rb)+1)
	for j := range prev {
		prev[j] = j
	}
	for i := 1; i <= len(ra); i++ {
		cur[0] = i
		for j := 1; j <= len(rb); j++ {
			cost := 1
			if ra[i-1] == rb[j-1] {
				cost = 0
			}
			cur[j] = min(prev[j]+1, cur[j-1]+1, prev[j-1]+cost)
		}
		prev, cur = cur, prev
	}
	return prev[len(rb)]
}

// yearSimilarity: exact 1, off by one 0.8, unknown on either side 0.6, else 0.
func yearSimilarity(guess *int, year int) float64 {
	if guess == nil || year == 0 {
		return 0.6
	}
	switch *guess - year {
	case 0:
		return 1
	case 1, -1:
		return 0.8
	}
	return 0
}

// rank scores and sorts candidates against every reading of a file's name
// and the country written in it ("UK" from "The Office (UK)"). Each
// candidate keeps the score of the reading it fits best.
func rank(readings []reading, country string, in []scoreInput) []Candidate {
	out := make([]Candidate, 0, len(in))
	for _, c := range in {
		score := 0.0
		for _, r := range readings {
			t := math.Max(titleSimilarity(r.title, c.Title), titleSimilarity(r.title, c.OriginalTitle))
			if country != "" {
				// "Married at First Sight (UK)" is TMDB's "Married at First Sight UK".
				t = math.Max(t, titleSimilarity(r.title+" "+country, c.Title))
			}
			y := yearSimilarity(r.year, c.Year)
			if s := 0.7*t + 0.3*y; s > score {
				score = s
				c.titleSim, c.yearSim, c.yearKnown = t, y, r.year != nil
			}
		}
		if country != "" && slices.Contains(c.Countries, tmdbCountry(country)) {
			score += 0.05
		}
		// Popularity only breaks ties.
		score += math.Min(math.Log1p(c.Popularity)/1000, 0.009)
		c.Score = math.Round(math.Min(score, 1)*1000) / 1000
		out = append(out, c.Candidate)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	return out
}

// accept decides whether a ranked candidate is a confident match: the same
// title, with a year that agrees when the name has one, or a high score.
// An entry TMDB has no date for never matches a name with a year: such
// entries are mostly stubs that share a title with the real one.
func accept(c Candidate) bool {
	if c.yearKnown && c.Year == 0 {
		return false
	}
	if c.titleSim >= 0.95 && (!c.yearKnown || c.yearSim >= 0.8) {
		return true
	}
	return c.Score >= 0.85
}
