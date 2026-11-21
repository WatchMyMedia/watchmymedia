package api

import (
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

// The search index (migration 00045_search_index.sql) keeps each title as
// words to look up, and writes these marks out for each of its three
// tables: keep the two alike (TestSearchIndexWords checks, with the marks
// the index takes a word to start after).
const (
	// searchJoiners are the marks people leave out when they type and put
	// no gap for: "spiderman" for "Spider-Man", "oceans" for "Ocean's
	// Eleven", "walle" for "WALL·E". The index holds a title with any of
	// them a second time, without them. They are - ' . * / and the curled
	// apostrophe and middle dot.
	searchJoiners = "-'\u2019.\u00b7*/"
	// searchMark is what the index puts before every word, so that the
	// first letter of one is three characters, the least it can look up:
	// two unit separators, which no title has.
	searchMark = "\x1f\x1f"
)

// How well a title matches the words typed, best first. Results are in
// this order, then by title.
const (
	searchExact    = iota // the whole title is the words
	searchStarts          // the title starts with them
	searchPhrase          // they are in the title as typed, starting at a word
	searchWordsHit        // each starts a word of the title, in any order
	searchAnywhere        // each is somewhere in the title
	searchNoMatch
)

// searchIndexMin is the shortest word the trigram index can look up.
const searchIndexMin = 3

// searchQuery is what someone typed, read for matching.
type searchQuery struct {
	// raw is the search as typed, without the spaces around it.
	raw string
	// legacy is the LIKE pattern search used before there were words: what
	// was typed, anywhere in a title. A title it matches is always found.
	legacy string
	// words are what was typed as letters and digits: lower case, without
	// accents, split at everything else. "Spider-Man: 2" is spider, man, 2.
	words  []string
	phrase string // words, with a space between
	// year is the last word when it is a year and not the only word:
	// "dune 2021" also finds Dune from 2021. yearless is the phrase
	// without it.
	year     int64
	yearless string
	// index holds the words long enough for the trigram index, as typed,
	// and scan every word when none is: the year is in neither, since a
	// title need not contain it.
	index []string
	scan  []string
}

func parseSearch(q string) searchQuery {
	q = strings.TrimSpace(q)
	s := searchQuery{
		raw:    q,
		legacy: "%" + strings.ReplaceAll(strings.ReplaceAll(q, "%", ""), "_", "") + "%",
		words:  strings.Fields(searchFold(q, false)),
	}
	s.phrase = strings.Join(s.words, " ")
	// The words as typed, for the index and LIKE, which fold case and
	// accents their own way.
	typed := strings.FieldsFunc(q, func(r rune) bool { return !searchWordRune(r) })
	if n := len(s.words); n >= 2 && len(typed) == n {
		if y := searchYear(s.words[n-1]); y != 0 {
			s.year, s.yearless = y, strings.Join(s.words[:n-1], " ")
			typed = typed[:n-1]
		}
	}
	for _, w := range typed {
		if searchLetters(w) >= searchIndexMin {
			s.index = append(s.index, w)
		}
	}
	if len(s.index) == 0 {
		s.scan = typed
	}
	return s
}

// searchYear is w as a year a movie or show could be from, or 0.
func searchYear(w string) int64 {
	if len(w) != 4 || (w[:2] != "18" && w[:2] != "19" && w[:2] != "20") {
		return 0
	}
	y, err := strconv.ParseInt(w, 10, 64)
	if err != nil {
		return 0
	}
	return y
}

// searchWordRune reports whether r is part of a word: a letter, a digit or
// a mark on one.
func searchWordRune(r rune) bool {
	return unicode.IsLetter(r) || unicode.IsNumber(r) || unicode.IsMark(r)
}

// searchLetters counts w's letters and digits. The index counts a word's
// characters after dropping the accents it knows, so it never counts fewer
// (TestSearchIndexKeepsLetters), and a word it is asked for always has the
// three it needs: asked for a shorter one, it finds nothing at all.
func searchLetters(w string) int {
	n := 0
	for _, r := range w {
		if unicode.IsLetter(r) || unicode.IsNumber(r) {
			n++
		}
	}
	return n
}

// indexMatch is the trigram index's query for the search: every long word,
// each as a quoted string, which the index reads as "these letters, in
// this order, anywhere".
func (s *searchQuery) indexMatch() string {
	quoted := make([]string, len(s.index))
	for i, w := range s.index {
		quoted[i] = `"` + strings.ReplaceAll(w, `"`, `""`) + `"`
	}
	return strings.Join(quoted, " AND ")
}

// startsMatch is the index's query for a search of short words: every
// title where each of them starts a word. The index can look up one letter
// that way, after the two marks it puts before every word.
func (s *searchQuery) startsMatch() string {
	quoted := make([]string, len(s.scan))
	for i, w := range s.scan {
		quoted[i] = `"` + searchMark + w + `"`
	}
	return strings.Join(quoted, " AND ")
}

// scanPatterns are the LIKE patterns of a search too short for the index,
// to look for anywhere in every title: one for each of up to three words,
// and '%' for a word there isn't. A search with no letters or digits at
// all has no words; it gives marks, what was typed as it was matched
// before, and words that match nothing.
func (s *searchQuery) scanPatterns() (one, two, three string, marks *string) {
	if len(s.scan) == 0 {
		return "", "", "", &s.legacy
	}
	p := [3]string{"%", "%", "%"}
	for i, w := range s.scan[:min(len(s.scan), len(p))] {
		p[i] = "%" + w + "%"
	}
	return p[0], p[1], p[2], nil
}

// searchFold is s as people type it when they search: lower case, without
// accents, and with everything but letters and digits as one space. With
// joined, the marks in searchJoiners are left out without a space, so
// "Spider-Man" is "spiderman" where it is otherwise "spider man".
func searchFold(s string, joined bool) string {
	ascii := true
	for i := range len(s) {
		if s[i] >= utf8.RuneSelf {
			ascii = false
			break
		}
	}
	if !ascii {
		s = norm.NFD.String(s)
	}
	var b strings.Builder
	b.Grow(len(s))
	space := false
	for _, r := range s {
		switch {
		case r >= 0x300 && r <= 0x36f:
			// An accent, apart from its letter after NFD.
		case joined && strings.ContainsRune(searchJoiners, r):
		case searchWordRune(r):
			if space && b.Len() > 0 {
				b.WriteByte(' ')
			}
			space = false
			if r == 'ς' {
				r = 'σ'
			}
			b.WriteRune(unicode.ToLower(r))
		default:
			space = true
		}
	}
	return b.String()
}

// searchName is one of a title's names (the title, the original title)
// folded for matching: as its words and, where it has marks people leave
// out, without them; and each of those without the article it starts with
// ("The Matrix" is listed under M, and "matrix" is the whole of it).
type searchName struct {
	words, joined         string
	bareWords, bareJoined string
}

func foldName(name string) searchName {
	n := searchName{words: searchFold(name, false)}
	n.bareWords = withoutArticle(n.words)
	if strings.ContainsAny(name, searchJoiners) {
		n.joined = searchFold(name, true)
		n.bareJoined = withoutArticle(n.joined)
	}
	return n
}

// withoutArticle is a folded name after the article it starts with, or
// nothing when it starts with none.
func withoutArticle(form string) string {
	for _, a := range []string{"the ", "a ", "an "} {
		if rest, ok := strings.CutPrefix(form, a); ok {
			return rest
		}
	}
	return ""
}

// rank is how well the name matches the words, whose phrase is their text
// with a space between.
func (n searchName) rank(words []string, phrase string) int {
	best := searchNoMatch
	if phrase == "" {
		return best
	}
	for _, form := range []string{n.words, n.joined, n.bareWords, n.bareJoined} {
		if form != "" {
			best = min(best, rankForm(form, words, phrase))
		}
	}
	return best
}

func rankForm(form string, words []string, phrase string) int {
	switch {
	case form == phrase:
		return searchExact
	case strings.HasPrefix(form, phrase):
		return searchStarts
	case strings.Contains(form, " "+phrase):
		return searchPhrase
	}
	starts := true
	for _, w := range words {
		if !strings.Contains(form, w) {
			return searchNoMatch
		}
		if starts && !strings.HasPrefix(form, w) && !strings.Contains(form, " "+w) {
			starts = false
		}
	}
	if starts {
		return searchWordsHit
	}
	return searchAnywhere
}

// rank is how well a title matches the search, and whether it matches at
// all. A title that matched before the words were read this way (legacy)
// always does. The year typed after the words counts when it is the
// title's year (0 when it has none).
func (s *searchQuery) rank(title, original string, year int64, legacy bool) (int, bool) {
	best := searchNoMatch
	for _, name := range []string{title, original} {
		if name == "" {
			continue
		}
		n := foldName(name)
		best = min(best, n.rank(s.words, s.phrase))
		if s.year != 0 && s.year == year {
			best = min(best, n.rank(s.words[:len(s.words)-1], s.yearless))
		}
		if strings.EqualFold(name, s.raw) {
			best = searchExact
		}
	}
	if best == searchNoMatch && legacy {
		best = searchAnywhere
	}
	return best, best != searchNoMatch
}

// compareNoCase orders titles as SQLite's NOCASE collation does, which
// search results were in before they were ranked: byte by byte, with A to Z
// as a to z.
func compareNoCase(a, b string) int {
	for i := 0; i < len(a) && i < len(b); i++ {
		x, y := a[i], b[i]
		if x >= 'A' && x <= 'Z' {
			x += 'a' - 'A'
		}
		if y >= 'A' && y <= 'Z' {
			y += 'a' - 'A'
		}
		if x != y {
			return int(x) - int(y)
		}
	}
	return len(a) - len(b)
}
