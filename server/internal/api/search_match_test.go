package api

import (
	"slices"
	"testing"
)

func TestParseSearch(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		typed  string
		legacy string
		words  []string
		year   int64
		index  []string
		scan   []string
	}{
		{typed: "  Lantern-Man ", legacy: "%Lantern-Man%", words: []string{"lantern", "man"}, index: []string{"Lantern", "Man"}},
		{typed: "bolt e", legacy: "%bolt e%", words: []string{"bolt", "e"}, index: []string{"bolt"}},
		{typed: "ox", legacy: "%ox%", words: []string{"ox"}, scan: []string{"ox"}},
		{typed: "dr io", legacy: "%dr io%", words: []string{"dr", "io"}, scan: []string{"dr", "io"}},
		// A year after the words is not asked of the title.
		{typed: "dusk 2021", legacy: "%dusk 2021%", words: []string{"dusk", "2021"}, year: 2021, index: []string{"dusk"}},
		{typed: "ox (2009)", legacy: "%ox (2009)%", words: []string{"ox", "2009"}, year: 2009, scan: []string{"ox"}},
		// A year alone, a number that is no year and a year before the
		// words are words like any other.
		{typed: "2021", legacy: "%2021%", words: []string{"2021"}, index: []string{"2021"}},
		{typed: "dusk 3021", legacy: "%dusk 3021%", words: []string{"dusk", "3021"}, index: []string{"dusk", "3021"}},
		{typed: "2021 dusk", legacy: "%2021 dusk%", words: []string{"2021", "dusk"}, index: []string{"2021", "dusk"}},
		// The wildcards are taken out of the old pattern, as they were.
		{typed: "100%_x", legacy: "%100x%", words: []string{"100", "x"}, index: []string{"100"}},
		// Accents go from the words, and stay in what the index is asked:
		// it folds them its own way. A letter and its accent typed apart
		// are one letter.
		{typed: "CAFÉ Soleil", legacy: "%CAFÉ Soleil%", words: []string{"cafe", "soleil"}, index: []string{"CAFÉ", "Soleil"}},
		{typed: "ne\u0301", legacy: "%ne\u0301%", words: []string{"ne"}, scan: []string{"ne\u0301"}},
		{typed: "Тихая гавань", legacy: "%Тихая гавань%", words: []string{"тихая", "гавань"}, index: []string{"Тихая", "гавань"}},
		// No letters or digits: only what was typed, as before.
		{typed: "!?", legacy: "%!?%"},
		{typed: "a b c d", legacy: "%a b c d%", words: []string{"a", "b", "c", "d"}, scan: []string{"a", "b", "c", "d"}},
	} {
		s := parseSearch(c.typed)
		if s.legacy != c.legacy || !slices.Equal(s.words, c.words) || s.year != c.year || !slices.Equal(s.index, c.index) || !slices.Equal(s.scan, c.scan) {
			t.Errorf("parseSearch(%q) = legacy %q words %q year %d index %q scan %q", c.typed, s.legacy, s.words, s.year, s.index, s.scan)
		}
	}

	if got := new(parseSearch(`one "two" three`)).indexMatch(); got != `"one" AND "two" AND "three"` {
		t.Errorf("indexMatch = %s", got)
	}
	if got := new(parseSearch("dr io")).startsMatch(); got != `"`+searchMark+`dr" AND "`+searchMark+`io"` {
		t.Errorf("startsMatch = %q", got)
	}
	for typed, want := range map[string][4]string{
		"ox":      {"%ox%", "%", "%", ""},
		"dr io":   {"%dr%", "%io%", "%", ""},
		"a b c d": {"%a%", "%b%", "%c%", ""},
		"!?":      {"", "", "", "%!?%"},
	} {
		s := parseSearch(typed)
		one, two, three, marks := s.scanPatterns()
		if [4]string{one, two, three, deref(marks)} != want {
			t.Errorf("scanPatterns(%q) = %q %q %q %q", typed, one, two, three, deref(marks))
		}
	}
}

func TestSearchFold(t *testing.T) {
	t.Parallel()
	for _, c := range []struct{ in, words, joined string }{
		{"Lantern-Man: No Way Back", "lantern man no way back", "lanternman no way back"},
		{"BOLT·E", "bolt e", "bolte"},
		{"Harbor's Dozen", "harbor s dozen", "harbors dozen"},
		{"Harbor\u2019s Dozen", "harbor s dozen", "harbors dozen"},
		{"M*I*S*T", "m i s t", "mist"},
		{"E.V. the Extra-Venturesome", "e v the extra venturesome", "ev the extraventuresome"},
		{"Fore/Aft", "fore aft", "foreaft"},
		{"Café Soleil", "cafe soleil", "cafe soleil"},
		{"Cafe\u0301 Soleil", "cafe soleil", "cafe soleil"},
		{"ÉTÉ  Été", "ete ete", "ete ete"},
		{"Тихая ГАВАНЬ", "тихая гавань", "тихая гавань"},
		{"ΟΔΟΣ", "οδοσ", "οδοσ"},
		{"οδος", "οδοσ", "οδοσ"},
		{"  --  ", "", ""},
		{"Dusk, Dawn & After!", "dusk dawn after", "dusk dawn after"},
	} {
		if got := searchFold(c.in, false); got != c.words {
			t.Errorf("searchFold(%q) = %q, want %q", c.in, got, c.words)
		}
		if got := searchFold(c.in, true); got != c.joined {
			t.Errorf("searchFold(%q, joined) = %q, want %q", c.in, got, c.joined)
		}
	}
}

func TestSearchRank(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		typed    string
		title    string
		original string
		year     int64
		legacy   bool
		want     int
	}{
		// The four steps of the order, and the two between.
		{typed: "star", title: "Star", want: searchExact},
		{typed: "star", title: "Star Harbor", want: searchStarts},
		{typed: "star", title: "Starlight", want: searchStarts},
		{typed: "star", title: "Lone Star", want: searchPhrase},
		{typed: "star", title: "Lone Starlight", want: searchPhrase},
		{typed: "star", title: "Rockstar", want: searchAnywhere},
		{typed: "star", title: "Harbor", want: searchNoMatch},
		{typed: "lone star", title: "Lone Star", want: searchExact},
		{typed: "lone st", title: "Lone Star", want: searchStarts},
		{typed: "lord tides", title: "The Lord of the Tides", want: searchWordsHit},
		{typed: "tides lord", title: "The Lord of the Tides", want: searchWordsHit},
		{typed: "lord of", title: "The Lord of the Tides", want: searchStarts},
		{typed: "of the", title: "The Lord of the Tides", want: searchPhrase},
		{typed: "ord ide", title: "The Lord of the Tides", want: searchAnywhere},
		{typed: "lord rings", title: "The Lord of the Tides", want: searchNoMatch},
		// A title is listed under its first word after the article.
		{typed: "lantern", title: "The Lantern", want: searchExact},
		{typed: "lantern", title: "A Lantern in the Dark", want: searchStarts},
		{typed: "the lantern", title: "The Lantern", want: searchExact},
		// Case, accents and punctuation.
		{typed: "CAFE soleil", title: "Café Soleil", want: searchExact},
		{typed: "café", title: "Cafe Soleil", want: searchStarts},
		{typed: "lanternman", title: "Lantern-Man", want: searchExact},
		{typed: "lantern man", title: "Lantern-Man", want: searchExact},
		{typed: "lantern-man", title: "Lantern-Man", want: searchExact},
		{typed: "lanternm", title: "The Lantern-Man Returns", want: searchStarts},
		{typed: "bolt e", title: "BOLT·E", want: searchExact},
		{typed: "bolte", title: "BOLT·E", want: searchExact},
		{typed: "harbors dozen", title: "Harbor's Dozen", want: searchExact},
		{typed: "harbor's dozen", title: "Harbor\u2019s Dozen", want: searchExact},
		{typed: "mist", title: "M*I*S*T", want: searchExact},
		{typed: "ev", title: "E.V.", want: searchExact},
		{typed: "тихая", title: "Тихая гавань", want: searchStarts},
		// The original title counts as the title does.
		{typed: "kumo", title: "The Cloud Castle", original: "Kumo no Shiro", want: searchStarts},
		{typed: "cloud shiro", title: "The Cloud Castle", original: "Kumo no Shiro", want: searchNoMatch},
		// A year after the words is the title's year, or part of the title.
		{typed: "dusk 2021", title: "Dusk", year: 2021, want: searchExact},
		{typed: "dusk 2021", title: "Dusk", year: 1984, want: searchNoMatch},
		{typed: "dusk 2021", title: "Dusk 2021", year: 2019, want: searchExact},
		{typed: "dusk 2021", title: "Dusk Falls", year: 2021, want: searchStarts},
		{typed: "dusk (2021)", title: "Dusk", year: 2021, want: searchExact},
		{typed: "2021", title: "Dusk", year: 2021, want: searchNoMatch},
		// What matched before is kept, however the words are read.
		{typed: "!?", title: "Wait!?", legacy: true, want: searchAnywhere},
		{typed: "!?", title: "!?", legacy: true, want: searchExact},
		{typed: "a_b", title: "Cab Ride", legacy: true, want: searchAnywhere},
	} {
		s := parseSearch(c.typed)
		rank, ok := s.rank(c.title, c.original, c.year, c.legacy)
		if rank != c.want || ok != (c.want != searchNoMatch) {
			t.Errorf("%q in %q (%q, %d): rank %d matched %v, want rank %d", c.typed, c.title, c.original, c.year, rank, ok, c.want)
		}
	}
}

func TestCompareNoCase(t *testing.T) {
	t.Parallel()
	titles := []string{"star wars", "Stardust", "a Star", "Star", "STAR b", "Zeta", "Émile", "eagle"}
	slices.SortFunc(titles, compareNoCase)
	// As ORDER BY title COLLATE NOCASE: A to Z fold, other bytes don't, and
	// a title sorts before a longer one that starts with it.
	want := []string{"a Star", "eagle", "Star", "STAR b", "star wars", "Stardust", "Zeta", "Émile"}
	if !slices.Equal(titles, want) {
		t.Errorf("order = %q, want %q", titles, want)
	}
}

func TestBestHits(t *testing.T) {
	t.Parallel()
	hits := []searchHit{
		{id: 1, rank: searchAnywhere, title: "Alpha"},
		{id: 2, rank: searchExact, title: "Zeta"},
		{id: 3, rank: searchStarts, title: "beta"},
		{id: 4, rank: searchStarts, title: "Beta", season: 2, number: 1},
		{id: 5, rank: searchStarts, title: "Beta", season: 1, number: 9},
		{id: 6, rank: searchStarts, title: "Beta", season: 1, number: 9},
		{id: 7, rank: searchExact, title: "alpha"},
	}
	if got, want := bestHits(slices.Clone(hits), 50), []int64{7, 2, 3, 5, 6, 4, 1}; !slices.Equal(got, want) {
		t.Errorf("bestHits = %v, want %v", got, want)
	}
	if got, want := bestHits(slices.Clone(hits), 3), []int64{7, 2, 3}; !slices.Equal(got, want) {
		t.Errorf("bestHits of 3 = %v, want %v", got, want)
	}
	if got := bestHits(nil, 20); len(got) != 0 {
		t.Errorf("bestHits of none = %v", got)
	}
}
