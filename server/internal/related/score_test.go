package related

import (
	"slices"
	"testing"
)

// signals is a library of 100 tagged titles where the page's title (1,
// TMDB 1001, library 1, 2010) has a rare keyword (k-rare, 2 titles), a
// common one (k-all, every title), Drama (60 titles), Heist (5 titles) and
// Korean (3 titles).
func signals(refs ...Ref) Signals {
	s := Signals{
		Titles: 100,
		Counts: map[TagKey]int64{
			{KindKeyword, "k-rare"}: 2, {KindKeyword, "k-all"}: 100,
			{KindGenre, "Drama"}: 60, {KindGenre, "Heist"}: 5, {KindLanguage, "ko"}: 3,
		},
		Recommended: map[int64]int64{},
		Tags:        map[int64][]TagKey{},
		Refs:        map[int64]Ref{},
		Exclude:     map[int64]bool{},
	}
	for _, r := range refs {
		s.Refs[r.ID] = r
	}
	return s
}

var target = Ref{ID: 1, TMDBID: 1001, LibraryID: 1, Title: "Target", Year: 2010}

func ref(id int64) Ref {
	return Ref{ID: id, TMDBID: 1000 + id, LibraryID: 1, Title: string(rune('A' + id)), Year: 2010}
}

func refIDs(refs []Ref) []int64 {
	out := make([]int64, len(refs))
	for i, r := range refs {
		out[i] = r.ID
	}
	return out
}

func TestSimilarQualifies(t *testing.T) {
	cases := []struct {
		name  string
		setup func(*Signals)
		want  []int64
	}{
		{"a TMDB recommendation alone", func(s *Signals) { s.Recommended[2] = 0 }, []int64{2}},
		{"a title whose recommendations name it", func(s *Signals) { s.Recommending = []int64{2} }, []int64{2}},
		{"a rare keyword", func(s *Signals) {
			s.Matches = []Match{{OtherID: 2, Kind: KindKeyword, Value: "k-rare"}}
		}, []int64{2}},
		{"a keyword every title has never counts", func(s *Signals) {
			s.Matches = []Match{{OtherID: 2, Kind: KindKeyword, Value: "k-all"}}
			s.Tags[2] = []TagKey{{KindGenre, "Heist"}, {KindGenre, "Drama"}, {KindLanguage, "ko"}}
		}, nil},
		{"the same director", func(s *Signals) {
			s.Matches = []Match{{OtherID: 2, Kind: KindDirector, Value: "77"}}
		}, []int64{2}},
		{"two leads", func(s *Signals) {
			s.Matches = []Match{{OtherID: 2, Kind: KindPerson, Value: "5", RankA: 0, RankB: 1}, {OtherID: 2, Kind: KindPerson, Value: "6", RankA: 2, RankB: 4}}
		}, []int64{2}},
		{"one minor cast member is too little", func(s *Signals) {
			s.Matches = []Match{{OtherID: 2, Kind: KindPerson, Value: "5", RankA: 8, RankB: 9}}
			s.Tags[2] = []TagKey{{KindGenre, "Drama"}}
		}, nil},
		{"a minor cast member with a rare genre and language", func(s *Signals) {
			s.Matches = []Match{{OtherID: 2, Kind: KindPerson, Value: "5", RankA: 8, RankB: 9}}
			s.Tags[2] = []TagKey{{KindGenre, "Heist"}, {KindLanguage, "ko"}}
		}, []int64{2}},
		{"genres, language and era alone never qualify", func(s *Signals) {
			// Candidates come only from strong signals; here one is found
			// by a keyword everyone has, which adds nothing.
			s.Matches = []Match{{OtherID: 2, Kind: KindKeyword, Value: "k-all"}}
			s.Tags[2] = []TagKey{{KindGenre, "Heist"}, {KindGenre, "Drama"}, {KindLanguage, "ko"}}
		}, nil},
		{"the collection's films are left to their row", func(s *Signals) {
			s.Recommended[2] = 0
			s.Exclude[2] = true
		}, nil},
		{"the page's own title in another library is never listed", func(s *Signals) {
			s.Refs[2] = Ref{ID: 2, TMDBID: target.TMDBID, LibraryID: 2, Title: "Target"}
			s.Recommending = []int64{2}
		}, nil},
		{"a title the library lacks is never listed", func(s *Signals) {
			s.Recommended[99] = 0
		}, nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s := signals(ref(2))
			c.setup(&s)
			if got := refIDs(Similar(target, s, SimilarMax)); !slices.Equal(got, c.want) && (len(got) != 0 || len(c.want) != 0) {
				t.Errorf("Similar = %v, want %v", got, c.want)
			}
		})
	}
}

func TestSimilarOrder(t *testing.T) {
	s := signals(ref(2), ref(3), ref(4), ref(5), ref(6))
	// TMDB's order among its recommendations; they come before titles
	// found by keyword; a recommendation both ways comes first.
	s.Recommended[3] = 1
	s.Recommended[4] = 0
	s.Recommended[2] = 5
	s.Recommending = []int64{2}
	s.Matches = []Match{{OtherID: 5, Kind: KindKeyword, Value: "k-rare"}, {OtherID: 6, Kind: KindDirector, Value: "7"}}
	got := refIDs(Similar(target, s, SimilarMax))
	if want := []int64{2, 4, 3, 5, 6}; !slices.Equal(got, want) {
		t.Errorf("order = %v, want %v", got, want)
	}
	if got := refIDs(Similar(target, s, 2)); !slices.Equal(got, []int64{2, 4}) {
		t.Errorf("limit 2 = %v", got)
	}
}

func TestSimilarTies(t *testing.T) {
	a, b, c := ref(2), ref(3), ref(4)
	a.Rating, b.Rating, c.Rating = 6, 8, 8
	a.Title, b.Title, c.Title = "Alpha", "bravo", "Charlie"
	s := signals(a, b, c)
	for _, id := range []int64{2, 3, 4} {
		s.Matches = append(s.Matches, Match{OtherID: id, Kind: KindDirector, Value: "7"})
	}
	// Equal scores: higher rating first, then by title.
	if got := refIDs(Similar(target, s, SimilarMax)); !slices.Equal(got, []int64{3, 4, 2}) {
		t.Errorf("ties = %v", got)
	}
}

func TestSimilarOneCardPerTitle(t *testing.T) {
	// The same TMDB title in library 2 (id 2) and in the page's library
	// (id 3): one card, the page's library's copy.
	other := Ref{ID: 2, TMDBID: 5000, LibraryID: 2, Title: "Copy"}
	same := Ref{ID: 3, TMDBID: 5000, LibraryID: 1, Title: "Copy"}
	s := signals(other, same)
	s.Recommended[2], s.Recommended[3] = 0, 0
	if got := refIDs(Similar(target, s, SimilarMax)); !slices.Equal(got, []int64{3}) {
		t.Errorf("Similar = %v, want [3]", got)
	}
	// Neither in the page's library: the first.
	s.Refs[3] = Ref{ID: 3, TMDBID: 5000, LibraryID: 3, Title: "Copy"}
	if got := refIDs(Similar(target, s, SimilarMax)); !slices.Equal(got, []int64{2}) {
		t.Errorf("Similar = %v, want [2]", got)
	}
}

func TestSimilarCaps(t *testing.T) {
	s := signals(ref(2))
	// Ten rare keywords add at most keywordMax.
	for i := range 10 {
		k := TagKey{KindKeyword, string(rune('a' + i))}
		s.Counts[k] = 2
		s.Matches = append(s.Matches, Match{OtherID: 2, Kind: k.Kind, Value: k.Value})
	}
	got := Similar(target, s, SimilarMax)
	if len(got) != 1 {
		t.Fatalf("Similar = %v", got)
	}
	// Against a title with only the director (3) and the language (1),
	// the capped keywords (6) still win, but not by the uncapped 39.
	s2 := signals(ref(2), ref(3))
	for _, m := range s.Matches {
		s2.Counts[TagKey{m.Kind, m.Value}] = 2
	}
	s2.Matches = slices.Clone(s.Matches)
	s2.Recommended[3] = 0
	if got := refIDs(Similar(target, s2, SimilarMax)); !slices.Equal(got, []int64{3, 2}) {
		t.Errorf("a recommendation (8+) should beat capped keywords (6): %v", got)
	}
}

func TestOnePerTitle(t *testing.T) {
	refs := []Ref{
		{ID: 1, TMDBID: 10, LibraryID: 2},
		{ID: 2, TMDBID: 11, LibraryID: 2},
		{ID: 3, TMDBID: 10, LibraryID: 1},
		{ID: 4, TMDBID: 12, LibraryID: 3},
	}
	if got := refIDs(OnePerTitle(refs, 1)); !slices.Equal(got, []int64{3, 2, 4}) {
		t.Errorf("OnePerTitle = %v, want [3 2 4]", got)
	}
}
