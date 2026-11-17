package related

import (
	"cmp"
	"math"
	"slices"
	"strings"
)

// The weights of More like this (PLAN-RELATED.md §5). TMDB's
// recommendations count most; then what titles share, rare things more than
// common ones.
const (
	// recommended is what a title gets when the page's title's TMDB
	// recommendations name it, plus up to recommendedOrder for TMDB's order.
	recommended      = 8.0
	recommendedOrder = 2.0
	// recommending is what a title gets when its own recommendations name
	// the page's title.
	recommending = 5.0
	// keywordMax caps what shared keywords add; each adds its rarity.
	keywordMax = 6.0
	// sameMaker is each shared director (movies) or creator (shows).
	sameMaker = 3.0
	// castLead is each cast member both titles bill in their top 5;
	// castOther each other shared one; castMax caps them.
	castLead  = 1.5
	castOther = 0.5
	castMax   = 4.0
	// genreWeight times its rarity is what each shared genre adds;
	// genreMax caps them.
	genreWeight = 0.5
	genreMax    = 2.0
	// language is a shared original language that fewer than half the
	// titles have.
	language = 1.0
	// era is a release within eraYears of the page's title.
	era      = 0.5
	eraYears = 10
	// Qualify is the least score a title needs, and a title qualifies only
	// with a strong signal too: a recommendation either way, a keyword, a
	// director or creator, or a cast member. Genres, language and era only
	// add to those.
	Qualify = 3.0
	// SimilarMin is the fewest titles More like this shows; SimilarMax the
	// most.
	SimilarMin = 3
	SimilarMax = 20
)

// Ref is a title as ordering and one card per title need it.
type Ref struct {
	ID        int64
	TMDBID    int64
	LibraryID int64
	Title     string
	Year      int64
	Rating    float64
}

// Match is a strong tag the page's title shares with another: a keyword, a
// cast member, a director or a creator, with both billing ranks.
type Match struct {
	OtherID      int64
	Kind, Value  string
	RankA, RankB int64
}

// TagKey names a tag without its rank.
type TagKey struct{ Kind, Value string }

// Signals are what the queries found for a title.
type Signals struct {
	// Titles is how many titles of the kind have tags.
	Titles int64
	// Counts are how many titles share each of the page's title's keywords,
	// genres and language, the title included.
	Counts map[TagKey]int64
	// Matches are the strong tags other titles share.
	Matches []Match
	// Recommended are the titles its TMDB recommendations name, with
	// TMDB's rank (from 0); every copy.
	Recommended map[int64]int64
	// Recommending are the titles whose recommendations name it.
	Recommending []int64
	// Tags are the candidates' genres and languages.
	Tags map[int64][]TagKey
	// Refs are the candidates.
	Refs map[int64]Ref
	// Exclude are titles not to list: its collection's.
	Exclude map[int64]bool
}

// Candidates are the titles Signals names, for reading their tags and refs.
func (s Signals) Candidates() []int64 {
	seen := map[int64]bool{}
	for _, m := range s.Matches {
		seen[m.OtherID] = true
	}
	for id := range s.Recommended {
		seen[id] = true
	}
	for _, id := range s.Recommending {
		seen[id] = true
	}
	return slices.Sorted(func(yield func(int64) bool) {
		for id := range seen {
			if !yield(id) {
				return
			}
		}
	})
}

// rarity is ln(titles ÷ titles with it): 0 for a tag every title has, more
// the rarer.
func (s Signals) rarity(k TagKey) float64 {
	n := s.Counts[k]
	if n <= 0 || s.Titles <= 0 || n >= s.Titles {
		return 0
	}
	return math.Log(float64(s.Titles) / float64(n))
}

type scored struct {
	ref    Ref
	score  float64
	strong bool
}

// Similar orders the titles that qualify for More like this, best first,
// one per TMDB title (the copy in target's library when there is one), and
// none of target's own TMDB title. It returns at most limit.
func Similar(target Ref, s Signals, limit int) []Ref {
	by := map[int64]*scored{}
	get := func(id int64) *scored {
		if c, ok := by[id]; ok {
			return c
		}
		ref, ok := s.Refs[id]
		if !ok || ref.TMDBID == target.TMDBID || s.Exclude[id] {
			return nil
		}
		c := &scored{ref: ref}
		by[id] = c
		return c
	}
	for id, rank := range s.Recommended {
		if c := get(id); c != nil {
			c.score += recommended + recommendedOrder*max(0, 1-float64(rank)/SimilarMax)
			c.strong = true
		}
	}
	for _, id := range s.Recommending {
		if c := get(id); c != nil {
			c.score += recommending
			c.strong = true
		}
	}
	keywords, cast := map[int64]float64{}, map[int64]float64{}
	for _, m := range s.Matches {
		c := get(m.OtherID)
		if c == nil {
			continue
		}
		switch m.Kind {
		case KindKeyword:
			if r := s.rarity(TagKey{m.Kind, m.Value}); r > 0 {
				keywords[m.OtherID] += r
				c.strong = true
			}
		case KindDirector, KindCreator:
			c.score += sameMaker
			c.strong = true
		case KindPerson:
			if m.RankA < 5 && m.RankB < 5 {
				cast[m.OtherID] += castLead
			} else {
				cast[m.OtherID] += castOther
			}
			c.strong = true
		}
	}
	for id, v := range keywords {
		by[id].score += min(v, keywordMax)
	}
	for id, v := range cast {
		by[id].score += min(v, castMax)
	}
	for id, c := range by {
		genres := 0.0
		for _, t := range s.Tags[id] {
			if _, ok := s.Counts[t]; !ok {
				continue
			}
			switch t.Kind {
			case KindGenre:
				genres += genreWeight * s.rarity(t)
			case KindLanguage:
				if s.Titles > 0 && s.Counts[t]*2 < s.Titles {
					c.score += language
				}
			}
		}
		c.score += min(genres, genreMax)
		if target.Year > 0 && c.ref.Year > 0 && abs(target.Year-c.ref.Year) <= eraYears {
			c.score += era
		}
	}
	// One per TMDB title: the copy in target's library, else the first.
	best := map[int64]*scored{}
	for _, c := range by {
		if !c.strong || c.score < Qualify {
			continue
		}
		b, ok := best[c.ref.TMDBID]
		switch {
		case !ok:
			best[c.ref.TMDBID] = c
		case (c.ref.LibraryID == target.LibraryID) != (b.ref.LibraryID == target.LibraryID):
			if c.ref.LibraryID == target.LibraryID {
				best[c.ref.TMDBID] = c
			}
		case c.ref.ID < b.ref.ID:
			best[c.ref.TMDBID] = c
		}
	}
	list := make([]*scored, 0, len(best))
	for _, c := range best {
		list = append(list, c)
	}
	slices.SortFunc(list, func(a, b *scored) int {
		return cmp.Or(
			cmp.Compare(b.score, a.score),
			cmp.Compare(b.ref.Rating, a.ref.Rating),
			cmp.Compare(strings.ToLower(a.ref.Title), strings.ToLower(b.ref.Title)),
			cmp.Compare(a.ref.ID, b.ref.ID),
		)
	})
	out := make([]Ref, 0, min(len(list), limit))
	for _, c := range list[:min(len(list), limit)] {
		out = append(out, c.ref)
	}
	return out
}

// OnePerTitle keeps the first copy of each TMDB title in order, preferring
// the copy in library.
func OnePerTitle(refs []Ref, library int64) []Ref {
	at := map[int64]int{}
	var out []Ref
	for _, r := range refs {
		i, ok := at[r.TMDBID]
		switch {
		case !ok:
			at[r.TMDBID] = len(out)
			out = append(out, r)
		case r.LibraryID == library && out[i].LibraryID != library:
			out[i] = r
		}
	}
	return out
}

func abs(n int64) int64 {
	if n < 0 {
		return -n
	}
	return n
}
