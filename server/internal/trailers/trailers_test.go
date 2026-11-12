package trailers

import (
	"reflect"
	"testing"

	"watchmymedia/server/internal/tmdb"
)

func yt(key, typ, lang string, official bool, size int, published string) tmdb.Video {
	return tmdb.Video{Key: key, Name: key + " name", Site: "YouTube", Type: typ, Language: lang, Official: official, Size: size, PublishedAt: published}
}

func keys(list []Trailer) []string {
	out := []string{}
	for _, t := range list {
		out = append(out, t.Key)
	}
	return out
}

// Official before unofficial, then trailers before teasers, English before
// no language, the larger size, and the newest; three at most.
func TestChooseOrder(t *testing.T) {
	t.Parallel()
	teaser := yt("teaserOfficial", "Teaser", "en", true, 1080, "2024-01-01T00:00:00.000Z")
	unofficial := yt("unofficialTrlr", "Trailer", "en", false, 2160, "2024-07-01T00:00:00.000Z")
	noLanguage := yt("noLanguageTrl", "Trailer", "", true, 2160, "2024-07-01T00:00:00.000Z")
	small := yt("smallTrailer1", "Trailer", "en", true, 720, "2024-07-01T00:00:00.000Z")
	older := yt("olderTrailer1", "Trailer", "en", true, 1080, "2024-03-01T00:00:00.000Z")
	newer := yt("newerTrailer1", "Trailer", "en", true, 1080, "2024-06-01T00:00:00.000Z")
	all := []tmdb.Video{teaser, unofficial, noLanguage, small, older, newer}
	if got, want := keys(Choose(all)), []string{"newerTrailer1", "olderTrailer1", "smallTrailer1"}; !reflect.DeepEqual(got, want) {
		t.Errorf("best three = %v, want %v", got, want)
	}
	// Without those, the rest of the order shows.
	if got, want := keys(Choose([]tmdb.Video{teaser, unofficial, noLanguage})), []string{"noLanguageTrl", "teaserOfficial", "unofficialTrlr"}; !reflect.DeepEqual(got, want) {
		t.Errorf("order = %v, want %v", got, want)
	}
}

// Only YouTube trailers and teasers in English or no language are kept.
func TestChooseLeavesOut(t *testing.T) {
	t.Parallel()
	vimeo := yt("vimeo1234567", "Trailer", "en", true, 1080, "")
	vimeo.Site = "Vimeo"
	for name, c := range map[string]struct {
		videos []tmdb.Video
		want   []string
	}{
		"none":            {nil, []string{}},
		"only Vimeo":      {[]tmdb.Video{vimeo}, []string{}},
		"other languages": {[]tmdb.Video{yt("frenchTrailer", "Trailer", "fr", true, 1080, ""), yt("germanTeaser", "Teaser", "de", true, 1080, "")}, []string{}},
		"not trailers": {[]tmdb.Video{yt("clip12345678", "Clip", "en", true, 1080, ""), yt("featurette12", "Featurette", "en", true, 1080, ""),
			yt("behindScenes", "Behind the Scenes", "en", true, 1080, "")}, []string{}},
		"odd keys":   {[]tmdb.Video{yt("bad key", "Trailer", "en", true, 1080, ""), yt("", "Trailer", "en", true, 1080, ""), yt("goodKey_-12", "Trailer", "en", true, 1080, "")}, []string{"goodKey_-12"}},
		"site case":  {[]tmdb.Video{{Key: "lowerSite123", Site: "youtube", Type: "Trailer", Language: "en"}}, []string{"lowerSite123"}},
		"same twice": {[]tmdb.Video{yt("sameKey12345", "Trailer", "en", true, 1080, ""), yt("sameKey12345", "Teaser", "en", true, 1080, ""), yt("otherKey1234", "Teaser", "en", true, 1080, "")}, []string{"sameKey12345", "otherKey1234"}},
	} {
		if got := keys(Choose(c.videos)); !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s: %v, want %v", name, got, c.want)
		}
	}
}

func TestChooseNames(t *testing.T) {
	t.Parallel()
	got := Choose([]tmdb.Video{{Key: "unnamed12345", Site: "YouTube", Type: "Teaser", Name: "  "}, {Key: "named1234567", Site: "YouTube", Type: "Trailer", Name: " Official Trailer "}})
	want := []Trailer{{Key: "named1234567", Name: "Official Trailer", Type: "Trailer"}, {Key: "unnamed12345", Name: "Teaser", Type: "Teaser"}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("Choose = %+v, want %+v", got, want)
	}
}

func TestEncodeDecode(t *testing.T) {
	t.Parallel()
	if got := Encode(nil); got != "[]" {
		t.Errorf("Encode(nil) = %q", got)
	}
	list := []Trailer{{Key: "abcdefghijk", Name: "Official Trailer", Type: "Trailer"}}
	if got := Decode(Encode(list)); !reflect.DeepEqual(got, list) {
		t.Errorf("round trip = %+v", got)
	}
	for _, s := range []string{"", "[]", "{", `{"key":"x"}`, `[{"key":"bad key","name":"x"}]`} {
		if got := Decode(s); len(got) != 0 {
			t.Errorf("Decode(%q) = %+v", s, got)
		}
	}
}
