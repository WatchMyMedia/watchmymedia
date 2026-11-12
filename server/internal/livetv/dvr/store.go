package dvr

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv"
)

const settingsKey = "livetv.recording.settings"

func (s *Service) Settings(ctx context.Context) (Settings, error) {
	out := Settings{Access: "admins", PadBefore: 1, PadAfter: 2, MinFreeBytes: 50_000_000_000, WarningSeconds: 120}
	raw, err := s.store.Get(ctx, settingsKey, "")
	if err != nil {
		return out, err
	}
	if raw != "" {
		err = json.Unmarshal([]byte(raw), &out)
	}
	return out, err
}
func (s *Service) SetSettings(ctx context.Context, v Settings) error {
	if v.Access != "none" && v.Access != "admins" && v.Access != "everyone" || !validPadding(v.PadBefore, v.PadAfter) || v.MinFreeBytes < 0 || v.WarningSeconds < 0 || v.WarningSeconds > 600 || v.TunersReserved < 0 || v.TunersReserved > 16 {
		return ErrInvalid
	}
	if v.Enabled {
		if err := s.validateFolder(ctx, v.TVLibraryID, v.TVPath, "tv"); err != nil {
			return err
		}
		if v.MoviePath != "" {
			if err := s.validateFolder(ctx, v.MovieLibraryID, v.MoviePath, "movie"); err != nil {
				return err
			}
		}
	}
	v.WakeSupported = false
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if err = s.store.Set(ctx, settingsKey, string(b)); err != nil {
		return err
	}
	if !v.Enabled {
		for _, p := range s.pipes {
			p.cancel()
		}
	}
	s.signal()
	return nil
}
func (s *Service) validateFolder(ctx context.Context, id int64, path, kind string) error {
	lib, err := s.q.GetLibrary(ctx, id)
	if err != nil {
		return ErrInvalid
	}
	if lib.Type != kind || filepath.Clean(path) != filepath.Clean(lib.Path) || !filepath.IsAbs(path) {
		return ErrInvalid
	}
	st, err := os.Stat(path)
	if err != nil || !st.IsDir() {
		return fmt.Errorf("%w: folder unavailable", ErrInvalid)
	}
	// Resolving the configured root once is safe; descendants are checked again
	// before writing and deleting, since a writable library can change later.
	return nil
}
func (s *Service) Available(ctx context.Context) bool {
	v, err := s.Settings(ctx)
	if err != nil || !v.Enabled || v.TVLibraryID == 0 {
		return false
	}
	lib, err := s.q.GetLibrary(ctx, v.TVLibraryID)
	if err != nil || lib.Type != "tv" || lib.Path != v.TVPath {
		return false
	}
	sources, err := s.live.Sources(ctx)
	if err != nil {
		return false
	}
	for _, src := range sources {
		if src.Enabled {
			return true
		}
	}
	return false
}
func (s *Service) Allowed(ctx context.Context, a Actor) bool {
	v, err := s.Settings(ctx)
	return err == nil && v.Enabled && s.live.Allowed(ctx, a.Admin) && (v.Access == "everyone" || v.Access == "admins" && a.Admin)
}
func readRecord(row db.LiveTvRecording) (record, error) {
	var r record
	err := json.Unmarshal([]byte(row.Data), &r)
	r.ID = row.ID
	r.RuleID = ""
	if row.RuleID != nil {
		r.RuleID = *row.RuleID
	}
	r.UserID = row.UserID
	r.State = row.State
	r.Start = unix(row.StartAt)
	r.End = unix(row.EndAt)
	r.Priority = row.Priority
	r.MediaFileID = row.MediaFileID
	if row.Path != nil {
		r.Path = *row.Path
	}
	return r, err
}
func (s *Service) get(ctx context.Context, id string) (record, error) {
	row, err := s.q.GetDVRRecording(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return record{}, ErrMissing
	}
	if err != nil {
		return record{}, err
	}
	return readRecord(row)
}
func put(ctx context.Context, q *db.Queries, r record, now int64) error {
	if r.Gaps == nil {
		r.Gaps = []Gap{}
	}
	data, err := json.Marshal(r)
	if err != nil {
		return err
	}
	var path, ruleID *string
	if r.Path != "" {
		path = &r.Path
	}
	if r.RuleID != "" {
		ruleID = &r.RuleID
	}
	return q.PutDVRRecording(ctx, db.PutDVRRecordingParams{ID: r.ID, RuleID: ruleID, UserID: r.UserID, SourceID: r.SourceID, ChannelID: r.ChannelID, Number: r.Number, ProgrammeID: r.ProgrammeID, SeriesKey: r.SeriesKey, StartAt: r.Start.Unix(), EndAt: r.End.Unix(), Priority: r.Priority, State: r.State, Section: section(r.State), Path: path, MediaFileID: r.MediaFileID, Data: string(data), CreatedAt: r.CreatedAt, UpdatedAt: now})
}
func readRule(row db.LiveTvRecordingRule) (rule, error) {
	var r rule
	err := json.Unmarshal([]byte(row.Data), &r)
	r.UserID = row.UserID
	return r, err
}
func putRule(ctx context.Context, q *db.Queries, r rule) error {
	data, err := json.Marshal(r)
	if err != nil {
		return err
	}
	return q.PutDVRRule(ctx, db.PutDVRRuleParams{ID: r.ID, UserID: r.UserID, Kind: r.Kind, SeriesKey: r.SeriesKey, Data: string(data), CreatedAt: r.CreatedAt})
}
func (s *Service) publish() {
	if s.opts.Bus != nil {
		s.opts.Bus.Publish(events.Event{Type: ChangedEvent, Data: Changed{}})
	}
}
func (s *Service) view(r record, a Actor) Recording {
	v := r.Recording
	v.CanCancel = owner(r.UserID, a)
	return v
}
func (s *Service) Get(ctx context.Context, id string, a Actor) (Recording, error) {
	r, err := s.get(ctx, id)
	return s.view(r, a), err
}
func (s *Service) List(ctx context.Context, a Actor, sect, cursor string) (Recordings, error) {
	out := Recordings{Items: []Recording{}}
	if sect == "series" {
		rows, err := s.q.ListDVRRules(ctx)
		if err != nil {
			return out, err
		}
		out.Rules = []RecordingRule{}
		for _, row := range rows {
			r, err := readRule(row)
			if err != nil {
				return out, err
			}
			r.CanCancel = owner(r.UserID, a)
			out.Rules = append(out.Rules, r.RecordingRule)
		}
		return out, nil
	}
	if sect != "now" && sect != "conflicts" && sect != "upcoming" && sect != "recorded" {
		return out, ErrInvalid
	}
	afterAt := int64(-1)
	afterID := ""
	if cursor != "" {
		b, err := base64.RawURLEncoding.DecodeString(cursor)
		if err != nil {
			return out, ErrInvalid
		}
		at, id, ok := strings.Cut(string(b), ":")
		if !ok {
			return out, ErrInvalid
		}
		afterAt, err = strconv.ParseInt(at, 10, 64)
		if err != nil {
			return out, ErrInvalid
		}
		afterID = id
	}
	var rows []db.LiveTvRecording
	var err error
	if sect == "recorded" {
		// Recorded is newest first; the cursor is the last row shown.
		beforeAt, beforeID := int64(1<<62), "\uffff"
		if cursor != "" {
			beforeAt, beforeID = afterAt, afterID
		}
		rows, err = s.q.ListDVRRecordedSection(ctx, db.ListDVRRecordedSectionParams{BeforeAt: beforeAt, BeforeID: beforeID, RowLimit: 21})
	} else {
		rows, err = s.q.ListDVRRecordingSection(ctx, db.ListDVRRecordingSectionParams{Section: sect, AfterAt: afterAt, AfterID: afterID, RowLimit: 21})
	}
	if err != nil {
		return out, err
	}
	if len(rows) > 20 {
		last := rows[19]
		out.NextCursor = base64.RawURLEncoding.EncodeToString([]byte(strconv.FormatInt(last.StartAt, 10) + ":" + last.ID))
		rows = rows[:20]
	}
	for _, row := range rows {
		r, err := readRecord(row)
		if err != nil {
			return out, err
		}
		out.Items = append(out.Items, s.view(r, a))
	}
	if cursor == "" {
		sources, _ := s.live.Sources(ctx)
		for _, src := range sources {
			if src.Enabled {
				out.Tuners += int(src.Device.Tuners)
			}
		}
		v, _ := s.Settings(ctx)
		if s.opts.FreeBytes != nil && v.TVPath != "" {
			free, e := s.opts.FreeBytes(v.TVPath)
			if e == nil {
				out.FreeBytes = int64(min(free, uint64(1<<63-1)))
			}
		}
	}
	return out, nil
}

// markRank picks which of a programme's rows its guide mark shows: one
// that will record wins over a clash, and both over an explanation.
func markRank(state string) int {
	switch state {
	case "recording":
		return 3
	case "scheduled":
		return 2
	case "conflict":
		return 1
	default:
		return 0
	}
}
func (s *Service) Marks(ctx context.Context, ids []string, a Actor) (map[string]*livetv.RecordingMark, error) {
	out := map[string]*livetv.RecordingMark{}
	if len(ids) == 0 || !s.Allowed(ctx, a) {
		return out, nil
	}
	rows, err := s.q.ListDVRProgrammeMarks(ctx, ids)
	if err != nil {
		return nil, err
	}
	for _, row := range rows {
		r, err := readRecord(row)
		if err != nil {
			return nil, err
		}
		if r.State == "cancelled" || r.State == "deleted" {
			continue
		}
		prev := out[r.ProgrammeID]
		if prev == nil || markRank(r.State) > markRank(prev.State) {
			out[r.ProgrammeID] = &livetv.RecordingMark{ID: r.ID, State: r.State, Series: r.Series, CanCancel: owner(r.UserID, a), Reason: r.Reason}
		}
	}
	return out, nil
}
