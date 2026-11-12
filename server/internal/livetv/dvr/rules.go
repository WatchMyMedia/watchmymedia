package dvr

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"strconv"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/metadata"
)

func occurrenceID(ruleID string, start int64) string {
	b := sha256.Sum256([]byte(ruleID + "\x00" + strconv.FormatInt(start, 10)))
	return hex.EncodeToString(b[:16])
}
func sameEpisode(a, b record) bool {
	if a.SeriesKey == "" || a.SeriesKey != b.SeriesKey {
		return false
	}
	if a.Season != nil && a.Episode != nil && b.Season != nil && b.Episode != nil {
		return *a.Season == *b.Season && *a.Episode == *b.Episode
	}
	return a.EpisodeTitle != "" && metadata.Normalize(a.EpisodeTitle) == metadata.Normalize(b.EpisodeTitle)
}
func (s *Service) expandRule(ctx context.Context, ru rule) error {
	existing, err := s.q.ListDVRByRule(ctx, &ru.ID)
	if err != nil {
		return err
	}
	byProgramme := map[string]record{}
	byID := map[string]record{}
	for _, row := range existing {
		r, e := readRecord(row)
		if e != nil {
			return e
		}
		byID[r.ID] = r
		if r.ProgrammeID != "" {
			byProgramme[r.ProgrammeID] = r
		}
	}
	var history []record
	if ru.Kind == "series" {
		rows, e := s.q.ListDVRBySeries(ctx, ru.SeriesKey)
		if e != nil {
			return e
		}
		for _, row := range rows {
			r, e := readRecord(row)
			if e != nil {
				return e
			}
			history = append(history, r)
		}
	}
	a := Actor{Name: ru.UserName}
	if ru.UserID != nil {
		a.UserID = *ru.UserID
	}
	now := s.opts.Now()
	wanted := map[string]bool{}
	add := func(r record) error {
		r.RuleID = ru.ID
		r.Series = ru.Kind == "series"
		r.Priority = ru.CreatedAt
		r.CreatedAt = now.Unix()
		if previous, ok := byID[r.ID]; ok {
			if previous.State != "scheduled" && previous.State != "conflict" && previous.State != "gone" && previous.State != "skipped" {
				return nil
			}
			r.State = previous.State
			if r.State == "gone" || r.State == "skipped" {
				r.State = "scheduled"
			}
			r.Priority = previous.Priority
			// Padding changed on this one recording stays changed.
			r.PadBefore, r.PadAfter = previous.PadBefore, previous.PadAfter
		}
		if previous, ok := byProgramme[r.ProgrammeID]; ok && r.ProgrammeID != "" {
			r.ID = previous.ID
			r.Priority = previous.Priority
			if previous.State != "scheduled" && previous.State != "conflict" && previous.State != "gone" && previous.State != "skipped" {
				return nil
			}
		}
		wanted[r.ID] = true
		if ru.Kind == "series" {
			for _, old := range history {
				if old.ID == r.ID || !sameEpisode(r, old) && (r.ProgrammeID == "" || old.ProgrammeID != r.ProgrammeID) {
					continue
				}
				switch old.State {
				case "done", "partial":
					r.State = "skipped"
					r.Reason = "already_recorded"
				case "deleted":
					if !old.RecordAgain {
						r.State = "skipped"
						r.Reason = "already_recorded"
					}
				case "scheduled", "recording":
					if old.Start.Before(r.Start) || old.ProgrammeID == r.ProgrammeID {
						r.State = "skipped"
						r.Reason = "already_scheduled"
					}
				}
			}
			if r.State != "skipped" && r.TMDBID > 0 && r.Season != nil && r.Episode != nil {
				found, e := s.q.DVRHasEpisode(ctx, db.DVRHasEpisodeParams{TmdbID: int64(r.TMDBID), SeasonNumber: int64(*r.Season), EpisodeNumber: int64(*r.Episode)})
				if e != nil {
					return e
				}
				if found {
					r.State = "skipped"
					r.Reason = "in_library"
				}
			}
		}
		if err := put(ctx, s.q, r, now.Unix()); err != nil {
			return err
		}
		history = append(history, r)
		return nil
	}
	if ru.Kind == "series" {
		programmes, e := s.q.ListDVRSeriesProgrammes(ctx, db.ListDVRSeriesProgrammesParams{SeriesKey: ru.SeriesKey, EndAt: now.Unix()})
		if e != nil {
			return e
		}
		for _, p := range programmes {
			if ru.NewOnly && p.IsNew == 0 {
				continue
			}
			ch, e := s.q.GetLiveChannel(ctx, p.ChannelID)
			if e != nil {
				continue
			}
			if ch.Enabled == 0 || ch.Protected != 0 || !ru.AnyChannel && ch.Number != ru.Number {
				continue
			}
			r := s.fromProgramme(p, ch, a, ru.PadBefore, ru.PadAfter)
			r.ID = occurrenceID(ru.ID, p.StartAt)
			if err := add(r); err != nil {
				return err
			}
		}
	} else {
		ch, e := s.q.GetLiveChannel(ctx, ru.ChannelID)
		if errors.Is(e, sql.ErrNoRows) {
			// The tuner was added again and its channels have new ids:
			// follow the channel by number, or wait for it to come back.
			channels, err := s.q.ListLiveChannels(ctx)
			if err != nil {
				return err
			}
			found, ok := channelByNumber(channels, ru.Number)
			if !ok {
				return nil
			}
			ch, ru.ChannelID = found, found.ID
			if err = putRule(ctx, s.q, ru); err != nil {
				return err
			}
		} else if e != nil {
			return e
		}
		start, until := ru.window(now)
		for _, at := range ManualTimes(start, ru.LocalMinute, ru.Days, ru.Zone, until) {
			p := db.LiveTvProgramme{Title: ru.Title, StartAt: at.Unix(), EndAt: at.Unix() + ru.DurationSeconds}
			r := s.fromProgramme(p, ch, a, ru.PadBefore, ru.PadAfter)
			r.ID = occurrenceID(ru.ID, p.StartAt)
			r.ProgrammeID = ""
			if err := add(r); err != nil {
				return err
			}
		}
	}
	for _, old := range byID {
		if !wanted[old.ID] && (old.State == "scheduled" || old.State == "conflict") {
			old.State = "skipped"
			old.Reason = "rule_filtered"
			if err := put(ctx, s.q, old, now.Unix()); err != nil {
				return err
			}
		}
	}
	return nil
}

// window is the span a manual rule expands over: from an occurrence that
// is on now (one set during today's slot records the rest of it) to 14 days
// ahead, or to the first occurrence when that is further off.
func (ru rule) window(now time.Time) (time.Time, time.Time) {
	start := now.Add(-time.Duration(ru.DurationSeconds) * time.Second)
	if ru.Start != nil && ru.Start.After(start) {
		start = *ru.Start
	}
	until := now.AddDate(0, 0, 14)
	if ru.Start != nil && ru.Start.After(until) {
		until = ru.Start.AddDate(0, 0, 1)
	}
	return start, until
}
func recordingDuration(start, end time.Time) int64 {
	return max(0, int64(end.Sub(start).Milliseconds()))
}
