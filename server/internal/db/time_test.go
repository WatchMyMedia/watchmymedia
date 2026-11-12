package db

import (
	"testing"
	"time"
)

func TestParseTime(t *testing.T) {
	at := time.Date(2026, 10, 29, 20, 0, 1, 234_000_000, time.UTC)
	for in, want := range map[string]time.Time{
		"":                              {},
		"garbage":                       {},
		"2026-10-29":                    {},
		FormatTime(at):                  at,
		"2026-10-29T20:00:01.234Z":      at,
		"2026-10-29T22:00:01.234+02:00": at,
		"2026-10-29T20:00:01Z":          at.Truncate(time.Second),
	} {
		if got := ParseTime(in); !got.Equal(want) {
			t.Errorf("ParseTime(%q) = %v, want %v", in, got, want)
		}
	}
	if FormatTime(at.In(time.FixedZone("x", 3600))) != "2026-10-29T20:00:01.234Z" {
		t.Errorf("FormatTime = %s", FormatTime(at))
	}
	s, bad := FormatTime(at), "nope"
	if p := ParseTimePtr(&s); p == nil || !p.Equal(at) {
		t.Errorf("ParseTimePtr = %v", p)
	}
	if ParseTimePtr(nil) != nil || ParseTimePtr(&bad) != nil {
		t.Error("ParseTimePtr of nothing")
	}
}
