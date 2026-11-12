// Package trailers keeps movies' trailers from TMDB (PLAN-TRAILERS.md
// §5.1): it chooses the best of a movie's TMDB videos, encodes them for
// movies.trailers, refreshes them in the background (Refresher), and holds
// whether YouTube trailers are on.
package trailers

import (
	"cmp"
	"encoding/json"
	"regexp"
	"slices"
	"strings"

	"watchmymedia/server/internal/tmdb"
)

// Max is how many trailers a movie keeps.
const Max = 3

// Trailer is a stored trailer: a YouTube video from TMDB.
type Trailer struct {
	// Key is the YouTube video id.
	Key string `json:"key"`
	// Name is TMDB's name for it ("Official Trailer").
	Name string `json:"name"`
	// Type is "Trailer" or "Teaser".
	Type string `json:"type"`
}

// youTubeKey is what a YouTube video id looks like (11 characters today).
// Apps put the key in a URL, so anything else is left out.
var youTubeKey = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// Choose picks a movie's trailers from its TMDB videos, best first, at most
// Max. It keeps YouTube trailers and teasers in English or no language, and
// orders them official first, then trailers before teasers, English before
// no language, the larger size and the newest.
func Choose(videos []tmdb.Video) []Trailer {
	kept := make([]tmdb.Video, 0, len(videos))
	for _, v := range videos {
		if !strings.EqualFold(v.Site, "YouTube") || !youTubeKey.MatchString(v.Key) {
			continue
		}
		if v.Type != "Trailer" && v.Type != "Teaser" {
			continue
		}
		if v.Language != "" && v.Language != "en" {
			continue
		}
		kept = append(kept, v)
	}
	slices.SortStableFunc(kept, func(a, b tmdb.Video) int {
		return cmp.Or(
			preferTrue(a.Official, b.Official),
			preferTrue(a.Type == "Trailer", b.Type == "Trailer"),
			preferTrue(a.Language == "en", b.Language == "en"),
			cmp.Compare(b.Size, a.Size),
			strings.Compare(b.PublishedAt, a.PublishedAt),
		)
	})
	out := make([]Trailer, 0, Max)
	seen := map[string]bool{}
	for _, v := range kept {
		if len(out) == Max {
			break
		}
		if seen[v.Key] {
			continue
		}
		seen[v.Key] = true
		name := strings.TrimSpace(v.Name)
		if name == "" {
			name = v.Type
		}
		out = append(out, Trailer{Key: v.Key, Name: name, Type: v.Type})
	}
	return out
}

// preferTrue orders true before false.
func preferTrue(a, b bool) int {
	switch {
	case a == b:
		return 0
	case a:
		return -1
	default:
		return 1
	}
}

// Encode is trailers as movies.trailers keeps them.
func Encode(list []Trailer) string {
	if len(list) == 0 {
		return "[]"
	}
	b, _ := json.Marshal(list)
	return string(b)
}

// Decode reads movies.trailers. Anything it can't read is no trailers.
func Decode(s string) []Trailer {
	var list []Trailer
	if err := json.Unmarshal([]byte(s), &list); err != nil {
		return nil
	}
	return slices.DeleteFunc(list, func(t Trailer) bool { return !youTubeKey.MatchString(t.Key) })
}
