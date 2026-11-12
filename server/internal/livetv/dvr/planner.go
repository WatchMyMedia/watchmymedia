package dvr

import (
	"cmp"
	"slices"
	"time"
)

// Interval is the recorder's complete reservation, including padding. A
// channel's overlapping reservations consume one tuner and share one stream.
type Interval struct {
	ID, Source, Channel string
	Start, End          time.Time
	Priority            int64
	Running             bool
}

// Plan is deterministic and has no I/O. Running reservations are fixed;
// otherwise an earlier priority wins. A preferred recording is moved ahead of
// pending reservations, never ahead of one already recording.
func Plan(input []Interval, capacity map[string]int) map[string]bool {
	rows := slices.Clone(input)
	slices.SortFunc(rows, func(a, b Interval) int {
		if a.Running != b.Running {
			if a.Running {
				return -1
			}
			return 1
		}
		if c := cmp.Compare(a.Priority, b.Priority); c != 0 {
			return c
		}
		return cmp.Compare(a.ID, b.ID)
	})
	accepted := []Interval{}
	out := map[string]bool{}
	for _, candidate := range rows {
		fits := true
		points := []time.Time{candidate.Start}
		for _, a := range accepted {
			if a.Source == candidate.Source && a.Start.Before(candidate.End) && a.End.After(candidate.Start) {
				points = append(points, maxTime(candidate.Start, a.Start))
			}
		}
		for _, at := range points {
			channels := map[string]bool{candidate.Channel: true}
			for _, a := range accepted {
				if a.Source == candidate.Source && !a.Start.After(at) && a.End.After(at) {
					channels[a.Channel] = true
				}
			}
			if len(channels) > capacity[candidate.Source] {
				fits = false
				break
			}
		}
		out[candidate.ID] = fits || candidate.Running
		if out[candidate.ID] {
			accepted = append(accepted, candidate)
		}
	}
	return out
}
func maxTime(a, b time.Time) time.Time {
	if a.After(b) {
		return a
	}
	return b
}
func unix(v int64) time.Time { return time.Unix(v, 0).UTC() }

// ManualTimes expands no further than 14 days and never changes wall-clock
// time across DST. A nonexistent local time is skipped rather than moved into
// another hour. The repeated hour creates one occurrence, not two.
func ManualTimes(start time.Time, minute, days int, zone string, until time.Time) []time.Time {
	loc, err := time.LoadLocation(zone)
	if err != nil {
		return nil
	}
	day := start.In(loc)
	out := []time.Time{}
	for range 15 {
		y, m, d := day.Date()
		at := time.Date(y, m, d, minute/60, minute%60, 0, 0, loc)
		if at.After(until) {
			break
		}
		if !at.Before(start) && at.Hour() == minute/60 && at.Minute() == minute%60 && days&(1<<int(at.Weekday())) != 0 {
			out = append(out, at.UTC())
		}
		day = time.Date(y, m, d+1, 12, 0, 0, 0, loc)
	}
	return out
}
