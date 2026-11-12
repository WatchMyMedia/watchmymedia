package dvr

import (
	"slices"
	"testing"
	"time"
)

func TestPlanPreservesRunningAndSharesSameChannel(t *testing.T) {
	at := time.Date(2026, 11, 4, 20, 0, 0, 0, time.UTC)
	makeInterval := func(id, ch string, from, to int, priority int64, running bool) Interval {
		return Interval{ID: id, Source: "tuner", Channel: ch, Start: at.Add(time.Duration(from) * time.Minute), End: at.Add(time.Duration(to) * time.Minute), Priority: priority, Running: running}
	}
	cases := []struct {
		name     string
		rows     []Interval
		capacity int
		want     []string
	}{
		{"two simultaneous", []Interval{makeInterval("a", "1", 0, 60, 1, false), makeInterval("b", "2", 0, 60, 2, false)}, 2, []string{"a", "b"}},
		{"latest conflicts", []Interval{makeInterval("a", "1", 0, 60, 1, false), makeInterval("b", "2", 0, 60, 2, false), makeInterval("c", "3", 0, 60, 3, false)}, 2, []string{"a", "b"}},
		{"same channel shares padding", []Interval{makeInterval("a", "1", 0, 61, 1, false), makeInterval("b", "1", 59, 120, 2, false), makeInterval("c", "2", 0, 120, 3, false)}, 2, []string{"a", "b", "c"}},
		{"running beats preferred", []Interval{makeInterval("a", "1", 0, 60, 10, true), makeInterval("b", "2", 0, 60, -10, false)}, 1, []string{"a"}},
		{"preferred replaces pending", []Interval{makeInterval("a", "1", 0, 60, 1, false), makeInterval("b", "2", 0, 60, -1, false)}, 1, []string{"b"}},
		{"half open", []Interval{makeInterval("a", "1", 0, 60, 1, false), makeInterval("b", "2", 60, 120, 2, false)}, 1, []string{"a", "b"}},
		{"no tuners", []Interval{makeInterval("a", "1", 0, 60, 1, false)}, 0, []string{}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := Plan(tc.rows, map[string]int{"tuner": tc.capacity})
			for _, r := range tc.rows {
				if got[r.ID] != slices.Contains(tc.want, r.ID) {
					t.Fatalf("plan=%v, wanted %v", got, tc.want)
				}
			}
		})
	}
}
func TestManualTimesAcrossDaylightSaving(t *testing.T) {
	loc, err := time.LoadLocation("America/New_York")
	if err != nil {
		t.Fatal(err)
	}
	start := time.Date(2026, 10, 30, 6, 0, 0, 0, loc)
	end := start.AddDate(0, 0, 7)
	got := ManualTimes(start, 360, 62, "America/New_York", end)
	if len(got) != 6 {
		t.Fatalf("weekdays=%v", got)
	}
	for _, at := range got {
		local := at.In(loc)
		if local.Hour() != 6 || local.Minute() != 0 || local.Weekday() == time.Saturday || local.Weekday() == time.Sunday {
			t.Fatal(local)
		}
	}
	if got[1].Sub(got[0]) != 73*time.Hour {
		t.Fatalf("fall back moved the clock: %v", got)
	}
	// Spring-forward's missing 02:30 is omitted, not moved to 01:30.
	spring := ManualTimes(time.Date(2027, 3, 13, 0, 0, 0, 0, loc), 150, 127, "America/New_York", time.Date(2027, 3, 15, 23, 0, 0, 0, loc))
	if len(spring) != 2 || spring[0].In(loc).Day() != 13 || spring[1].In(loc).Day() != 15 {
		t.Fatalf("spring=%v", spring)
	}
}
