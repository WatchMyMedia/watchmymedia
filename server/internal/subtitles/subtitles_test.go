package subtitles

import (
	"strings"
	"testing"
)

func TestParseLanguages(t *testing.T) {
	for in, want := range map[string]string{
		"en":                   "en",
		" EN , es,pt-BR ":      "en,es,pt-br",
		"en,en,es":             "en,es",
		"english, e, zh-tw, !": "zh-tw",
		"":                     "",
	} {
		if got := strings.Join(ParseLanguages(in), ","); got != want {
			t.Errorf("ParseLanguages(%q) = %q, want %q", in, got, want)
		}
	}
}
