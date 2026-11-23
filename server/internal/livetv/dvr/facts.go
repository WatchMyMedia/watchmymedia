package dvr

import (
	"context"
	"encoding/json"
	"slices"
	"strconv"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/metadata"
)

// listingCategories reads a listing's categories, kept as a JSON array.
func listingCategories(raw string) []string {
	var out []string
	if json.Unmarshal([]byte(raw), &out) != nil || len(out) == 0 {
		return nil
	}
	return out
}

// airYear is the year of a listing's air date: a movie's year, or the
// year an episode first aired. 0 when it has none.
func airYear(date string) int {
	if len(date) < 4 {
		return 0
	}
	y, err := strconv.Atoi(date[:4])
	if err != nil || y < 1800 {
		return 0
	}
	return y
}

// unstored clears what a reply works out for itself, so put never keeps
// it in a recording's data.
func unstored(r *Recording) {
	r.ProgrammeKind, r.Year, r.Progress, r.Watched, r.NextAiring = "", 0, nil, false, nil
	if len(r.ConflictsWith) > 0 {
		refs := slices.Clone(r.ConflictsWith)
		for i := range refs {
			refs[i].NextAiring = nil
		}
		r.ConflictsWith = refs
	}
}

// sectionCounts counts each section's recordings and bytes, and the rules.
func (s *Service) sectionCounts(ctx context.Context) (*SectionCounts, error) {
	rows, err := s.q.CountDVRSections(ctx)
	if err != nil {
		return nil, err
	}
	out := &SectionCounts{}
	for _, row := range rows {
		c := SectionCount{Items: row.Items, Bytes: row.Bytes}
		switch row.Section {
		case "now":
			out.Now = c
		case "conflicts":
			out.Conflicts = c
		case "upcoming":
			out.Upcoming = c
		case "recorded":
			out.Recorded = c
		}
	}
	if out.Series.Items, err = s.q.CountDVRRules(ctx); err != nil {
		return nil, err
	}
	return out, nil
}

// viewerFacts puts on a page of recordings where userID is in each, and
// for repeats that won't record, and repeats holding a tuner they need,
// when the same episode airs again.
func (s *Service) viewerFacts(ctx context.Context, userID int64, items []Recording) error {
	if len(items) == 0 {
		return nil
	}
	ids := make([]string, 0, len(items))
	for _, r := range items {
		ids = append(ids, r.ID)
	}
	if userID != 0 {
		rows, err := s.q.ListDVRViewerProgress(ctx, db.ListDVRViewerProgressParams{UserID: userID, Ids: ids})
		if err != nil {
			return err
		}
		byID := make(map[string]db.ListDVRViewerProgressRow, len(rows))
		for _, row := range rows {
			byID[row.RecordingID] = row
		}
		for i := range items {
			row, ok := byID[items[i].ID]
			if !ok {
				continue
			}
			switch {
			case row.RecordingPositionMs != nil:
				items[i].Progress = &ViewerProgress{PositionMs: *row.RecordingPositionMs, DurationMs: deref(row.RecordingDurationMs)}
			case row.WatchPositionMs != nil:
				items[i].Watched = deref(row.WatchCompleted) == 1
				if !items[i].Watched || *row.WatchPositionMs > 0 {
					items[i].Progress = &ViewerProgress{PositionMs: *row.WatchPositionMs, DurationMs: deref(row.WatchDurationMs)}
				}
			}
		}
	}
	return s.nextAirings(ctx, items)
}

// nextAirings finds, for each repeat in conflict on the page and each
// repeat in a clash's holders, the next time the listings show the same
// episode on a channel that can be watched.
func (s *Service) nextAirings(ctx context.Context, items []Recording) error {
	// The repeats asked about: the page's own, then the holders, which
	// need their recordings read for their series and episode.
	var want []string
	for _, r := range items {
		if r.State == "conflict" && r.Repeat {
			want = append(want, r.ID)
		}
		for _, ref := range r.ConflictsWith {
			if ref.Repeat {
				want = append(want, ref.ID)
			}
		}
	}
	if len(want) == 0 {
		return nil
	}
	slices.Sort(want)
	want = slices.Compact(want)
	rows, err := s.q.ListDVRRecordingsByIDs(ctx, want)
	if err != nil {
		return err
	}
	records := map[string]record{}
	for _, row := range rows {
		r, err := readRecord(row)
		if err != nil {
			return err
		}
		records[r.ID] = r
	}
	channels := map[string]db.LiveTvChannel{}
	series := map[string][]db.LiveTvProgramme{}
	now := s.opts.Now().Unix()
	find := func(id string) (*Airing, error) {
		r, ok := records[id]
		if !ok || r.SeriesKey == "" {
			return nil, nil
		}
		if _, ok := series[r.SeriesKey]; !ok {
			ps, err := s.q.ListDVRSeriesProgrammes(ctx, db.ListDVRSeriesProgrammesParams{SeriesKey: r.SeriesKey, EndAt: now})
			if err != nil {
				return nil, err
			}
			series[r.SeriesKey] = ps
		}
		for _, p := range series[r.SeriesKey] {
			if p.ID == r.ProgrammeID || p.StartAt <= r.Start.Unix() || !sameListing(r, p) {
				continue
			}
			ch, ok := channels[p.ChannelID]
			if !ok {
				c, err := s.q.GetLiveChannel(ctx, p.ChannelID)
				if err != nil {
					continue
				}
				channels[p.ChannelID], ch = c, c
			}
			if ch.Enabled == 0 || ch.Protected != 0 {
				continue
			}
			return &Airing{ChannelID: ch.ID, Number: ch.Number, ChannelName: ch.Name, ProgrammeID: p.ID, Start: unix(p.StartAt)}, nil
		}
		return nil, nil
	}
	for i := range items {
		r := &items[i]
		if r.State == "conflict" && r.Repeat {
			if r.NextAiring, err = find(r.ID); err != nil {
				return err
			}
		}
		if len(r.ConflictsWith) == 0 {
			continue
		}
		refs := slices.Clone(r.ConflictsWith)
		for j := range refs {
			if refs[j].Repeat {
				if refs[j].NextAiring, err = find(refs[j].ID); err != nil {
					return err
				}
			}
		}
		r.ConflictsWith = refs
	}
	return nil
}

// sameListing reports whether p shows the episode r recorded, as series
// rules tell episodes apart: by season and episode, else by episode title.
func sameListing(r record, p db.LiveTvProgramme) bool {
	if r.Season != nil && r.Episode != nil && p.Season != nil && p.Episode != nil {
		return int64(*r.Season) == *p.Season && int64(*r.Episode) == *p.Episode
	}
	return r.EpisodeTitle != "" && metadata.Normalize(r.EpisodeTitle) == metadata.Normalize(p.EpisodeTitle)
}

func deref[T any](p *T) T {
	var zero T
	if p == nil {
		return zero
	}
	return *p
}
