package dvr

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
	"unicode"

	"golang.org/x/text/unicode/norm"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/segments"
	"watchmymedia/server/internal/watchstate"
)

// Provenance is small library metadata; it never exposes server paths.
type Provenance struct {
	Channel   string    `json:"channel"`
	Number    string    `json:"number"`
	StartedAt time.Time `json:"startedAt"`
	Gaps      []Gap     `json:"gaps" nullable:"false"`
	RuleID    string    `json:"ruleId,omitempty" required:"false"`
	AdBreaks  string    `json:"adBreaks" doc:"finding, found or none."`
}

func (s *Service) Metadata(ctx context.Context, ids []int64) (map[int64]*Provenance, error) {
	out := map[int64]*Provenance{}
	if len(ids) == 0 {
		return out, nil
	}
	pointers := make([]*int64, 0, len(ids))
	for _, id := range ids {
		pointers = append(pointers, new(id))
	}
	rows, err := s.q.ListDVRFileMetadata(ctx, pointers)
	if err != nil {
		return nil, err
	}
	config, err := s.Settings(ctx)
	if err != nil {
		return nil, err
	}
	for _, row := range rows {
		r, err := readRecord(row.LiveTvRecording)
		if err != nil {
			return nil, err
		}
		if r.MediaFileID == nil || r.State == "deleted" {
			continue
		}
		ads := "none"
		if row.HasAds {
			ads = "found"
		} else if row.Analyzed == nil && config.FindAds {
			ads = "finding"
		}
		out[*r.MediaFileID] = &Provenance{Channel: r.ChannelName, Number: r.Number, StartedAt: valueTime(r.StartedAt, r.Start), Gaps: r.Gaps, RuleID: r.RuleID, AdBreaks: ads}
	}
	return out, nil
}

func (s *Service) AdCandidates(ctx context.Context) ([]db.MediaFile, error) {
	// The detector's own version: a file analysed by an older one is redone,
	// and one analysed by this one is never a candidate again.
	return s.q.ListDVRAdCandidates(ctx, db.ListDVRAdCandidatesParams{AfterID: "", AlgoVersion: segments.AdBreakAlgoVersion, RowLimit: 50})
}
func (s *Service) HasFiles(ctx context.Context) bool {
	found, err := s.q.HasDVRFiles(ctx)
	return err == nil && found
}

func (s *Service) OwnedPaths(ctx context.Context, paths []string) ([]Recording, error) {
	out := []Recording{}
	for _, path := range paths {
		row, err := s.q.GetDVRRecordingByPath(ctx, &path)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return nil, err
		}
		r, err := readRecord(row)
		if err != nil {
			return nil, err
		}
		if r.State == "done" || r.State == "partial" {
			out = append(out, r.Recording)
		}
	}
	return out, nil
}

func (s *Service) DeletePaths(ctx context.Context, paths []string, recordAgain bool) (int, int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	count := 0
	bytes := int64(0)
	for _, path := range paths {
		row, err := s.q.GetDVRRecordingByPath(ctx, &path)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return count, bytes, err
		}
		r, err := readRecord(row)
		if err != nil {
			return count, bytes, err
		}
		if r.State != "done" && r.State != "partial" {
			continue
		}
		if err = s.deleteOwned(ctx, r, recordAgain); err != nil {
			return count, bytes, err
		}
		count++
		bytes += r.Bytes
	}
	return count, bytes, nil
}

// maxStemBytes keeps a file name, with " (n)", ".ts" and the hidden
// ".<name>.ts.partial" around it, under the 255 bytes file systems allow.
const maxStemBytes = 230

// capBytes cuts s to at most n bytes at a character boundary.
func capBytes(s string, n int) string {
	if len(s) <= n {
		return s
	}
	cut := 0
	for i := range s {
		if i > n {
			break
		}
		cut = i
	}
	return strings.TrimRight(s[:cut], " .")
}

func cleanName(raw string) string {
	raw = norm.NFC.String(raw)
	raw = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || strings.ContainsRune(`<>:"/\|?*`, r) {
			return '-'
		}
		return r
	}, raw)
	// The scanner skips names that start with a dot or an underscore.
	raw = strings.TrimLeft(strings.Trim(raw, " ."), "_.")
	raw = capBytes(strings.TrimSpace(raw), 150)
	if raw == "" {
		return "Recording"
	}
	switch strings.ToUpper(strings.SplitN(raw, ".", 2)[0]) {
	case "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9":
		raw += " (TV)"
	}
	return raw
}

// readableFolder renames a folder the scanner would skip ("Extras",
// "Other", "Trailers"), keeping the show findable: by its TMDB id when the
// listing has one (the parser reads the tag and leaves it out of the
// title), else with " (TV)".
func readableFolder(name string, tmdbID int) string {
	if !parser.IgnoreDir(name) {
		return name
	}
	if tmdbID > 0 {
		return fmt.Sprintf("%s [tmdbid-%d]", name, tmdbID)
	}
	return name + " (TV)"
}

// onDisk spells name as an existing folder in parent does, when one reads
// the same apart from Unicode normalization or case: macOS keeps NFD
// folders and case-insensitive volumes keep the first casing, and the
// scanner stores paths as the disk spells them.
func onDisk(parent, name string) string {
	entries, err := os.ReadDir(parent)
	if err != nil {
		return name
	}
	want := norm.NFC.String(name)
	for _, e := range entries {
		if e.IsDir() && e.Name() != name && strings.EqualFold(norm.NFC.String(e.Name()), want) {
			return e.Name()
		}
	}
	return name
}

func (s *Service) recordingPath(ctx context.Context, lib db.Library, r record) (string, error) {
	title := cleanName(r.Title)
	show := readableFolder(title, r.TMDBID)
	folder := filepath.Join(lib.Path, onDisk(lib.Path, show))
	base := title
	if r.Kind == "movie" {
		year := r.AirDate
		if len(year) >= 4 {
			year = year[:4]
		}
		if _, err := strconv.Atoi(year); err == nil && len(year) == 4 {
			title += " (" + year + ")"
		}
		movie := readableFolder(title, r.TMDBID)
		folder = filepath.Join(lib.Path, onDisk(lib.Path, movie))
		base = title
	} else if r.Season != nil && r.Episode != nil {
		season := fmt.Sprintf("Season %02d", *r.Season)
		folder = filepath.Join(folder, onDisk(folder, season))
		base = fmt.Sprintf("%s - S%02dE%02d", title, *r.Season, *r.Episode)
		if r.EpisodeTitle != "" {
			base += " - " + cleanName(r.EpisodeTitle)
		}
	} else {
		date := r.AirDate
		if len(date) != 10 {
			date = r.Start.UTC().Format("2006-01-02")
		}
		base += " - " + date
		if r.ProgrammeID == "" {
			base += " " + r.Start.UTC().Format("15.04")
		}
		if r.EpisodeTitle != "" {
			base += " - " + cleanName(r.EpisodeTitle)
		}
	}
	base = capBytes(base, maxStemBytes)
	// The scanner skips files named like samples and trailers ("… - The
	// Trailer"); the episode code or date still names the file.
	if parser.IgnoreFile(base + ".ts") {
		base = capBytes(base, maxStemBytes-12) + " (Recording)"
	}
	if parser.IgnoreFile(base + ".ts") {
		// It starts like a sample ("Sample (2024)"): the folder names it.
		base = capBytes("Recording - "+base, maxStemBytes)
	}
	for n := 1; n <= 1000; n++ {
		name := base
		if n > 1 {
			name = capBytes(base, maxStemBytes-8) + fmt.Sprintf(" (%d)", n)
		}
		if path, ok, err := s.unused(ctx, folder, name); err != nil || ok {
			return path, err
		}
	}
	return "", ErrUnavailable
}

// makeParent and contained refuse traversal and symlinked descendants,
// before a capture and before any read or deletion. A root selected by an
// admin may itself be a mount or symlink; its resolved identity anchors
// the operation. Only makeParent creates folders, and only to record:
// reading or deleting never recreates a folder someone removed.
func makeParent(root, path string) error { return safeParent(root, path, true) }
func contained(root, path string) error  { return safeParent(root, path, false) }
func safeParent(root, path string, create bool) error {
	rel, err := filepath.Rel(root, path)
	if err != nil || rel == "." || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return ErrInvalid
	}
	rootReal, err := filepath.EvalSymlinks(root)
	if err != nil {
		return err
	}
	parent := filepath.Dir(path)
	current := root
	for part := range strings.SplitSeq(filepath.Dir(rel), string(filepath.Separator)) {
		if part == "." {
			continue
		}
		current = filepath.Join(current, part)
		st, e := os.Lstat(current)
		if errors.Is(e, os.ErrNotExist) && create {
			if e = os.Mkdir(current, 0o755); e != nil && !errors.Is(e, os.ErrExist) {
				return e
			}
			st, e = os.Lstat(current)
		}
		if e != nil {
			return e
		}
		if !st.IsDir() || st.Mode()&os.ModeSymlink != 0 {
			return ErrInvalid
		}
	}
	real, err := filepath.EvalSymlinks(parent)
	if err != nil {
		return err
	}
	r, err := filepath.Rel(rootReal, real)
	if err != nil || r == ".." || strings.HasPrefix(r, ".."+string(filepath.Separator)) {
		return ErrInvalid
	}
	return nil
}
func (s *Service) link(ctx context.Context) bool {
	finishing := false
	s.mu.Lock()
	defer s.mu.Unlock()
	rows, err := s.q.ListDVRUnlinked(ctx)
	if err != nil {
		return false
	}
	for _, row := range rows {
		r, e := readRecord(row)
		if e != nil {
			continue
		}
		if r.Reason == "finish_pending" {
			now := s.opts.Now()
			if wait, ok := s.retryAt[r.ID]; ok && now.Before(wait.at) {
				finishing = true
				continue
			}
			if r.FileMtimeNano == 0 {
				// Sealing failed when the writer closed: measure the closed
				// file now, at exactly the length recorded.
				_ = s.measureClosed(ctx, &r)
			}
			if e = s.publishSealed(ctx, &r); e == nil || errors.Is(e, os.ErrNotExist) && s.publishedMatches(ctx, r) {
				// Only a successful publish (or its durable crash reconciliation)
				// establishes final ownership; a collision never fingerprints it.
				if !s.publishedMatches(ctx, r) {
					finishing = true
					s.backOff(r.ID, now)
					continue
				}
				delete(s.retryAt, r.ID)
				r.State, r.Reason = "partial", ""
				if r.FinalState == "done" || r.FinalState == "partial" {
					r.State, r.Reason = r.FinalState, r.FinalReason
				}
				_ = put(ctx, s.q, r, now.Unix())
				if s.opts.Scanner != nil {
					s.opts.Scanner.RequestPaths(r.LibraryID, []string{r.Path})
				}
				s.publish()
				continue
			}
			finishing = true
			s.backOff(r.ID, now)
			continue
		}
		fileID, e := s.q.DVRFileAtPath(ctx, r.Path)
		if e != nil {
			continue
		}
		r.MediaFileID = &fileID
		if e = put(ctx, s.q, r, s.opts.Now().Unix()); e != nil {
			continue
		}
		_ = s.transferProgress(ctx, r)
		s.retention(ctx, r.RuleID)
		s.publish()
	}
	pending, err := s.q.ListDVRProgressRecordingIDs(ctx)
	if err == nil {
		for _, id := range pending {
			r, e := s.get(ctx, id)
			if e == nil {
				_ = s.transferProgress(ctx, r)
			}
		}
	}
	return finishing
}

// backOff spaces out publishing a recording that couldn't be renamed into
// place, from 10 seconds to 10 minutes. It runs under s.mu.
func (s *Service) backOff(id string, now time.Time) {
	delay := 10 * time.Second
	if prev, ok := s.retryAt[id]; ok {
		delay = min(prev.delay*2, 10*time.Minute)
	}
	s.retryAt[id] = retry{at: now.Add(delay), delay: delay}
}
func (s *Service) retention(ctx context.Context, ruleID string) {
	if ruleID == "" {
		return
	}
	row, err := s.q.GetDVRRule(ctx, ruleID)
	if err != nil {
		return
	}
	ru, err := readRule(row)
	if err != nil || ru.Keep <= 0 {
		return
	}
	rows, err := s.q.ListDVRByRule(ctx, &ruleID)
	if err != nil {
		return
	}
	retained := []record{}
	for _, row := range rows {
		if row.State == "done" || row.State == "partial" {
			r, e := readRecord(row)
			if e == nil {
				retained = append(retained, r)
			}
		}
	}
	for len(retained) > ru.Keep {
		r := retained[0]
		retained = retained[1:]
		_ = s.deleteOwned(ctx, r, false)
	}
}

// DeleteFile is deliberately narrow: a database ownership row, exact path,
// current library containment and a regular file must all agree. Other
// library files continue through the existing Radarr/Sonarr delete path.
func (s *Service) DeleteFile(ctx context.Context, fileID int64, recordAgain bool) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	row, err := s.q.GetDVRRecordingByFile(ctx, &fileID)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	r, err := readRecord(row)
	if err != nil {
		return true, err
	}
	return true, s.deleteOwned(ctx, r, recordAgain)
}
func (s *Service) DeleteRecording(ctx context.Context, id string, recordAgain bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, err := s.get(ctx, id)
	if err != nil {
		return err
	}
	if r.State == "deleted" {
		return nil
	}
	return s.deleteOwned(ctx, r, recordAgain)
}
func (s *Service) deleteOwned(ctx context.Context, r record, recordAgain bool) error {
	if r.Reason == "finish_pending" || r.State != "done" && r.State != "partial" {
		return ErrInvalid
	}
	err := s.validateOwned(ctx, r, r.Path, true)
	if errors.Is(err, os.ErrNotExist) {
		// Already gone counts as deleted only while the library folder is
		// there: an unmounted drive's files must stay owned for its return.
		lib, e := s.q.GetLibrary(ctx, r.LibraryID)
		if e != nil {
			return e
		}
		if _, e = os.Stat(lib.Path); e != nil {
			return ErrUnavailable
		}
	} else if err != nil {
		return err
	}
	if err = os.Remove(r.Path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	r.State = "deleted"
	r.RecordAgain = recordAgain
	if err = put(ctx, s.q, r, s.opts.Now().Unix()); err != nil {
		return err
	}
	if s.opts.Scanner != nil {
		s.opts.Scanner.RequestPaths(r.LibraryID, []string{r.Path})
	}
	s.signal()
	return nil
}
func (s *Service) Progress(ctx context.Context, userID int64, id string) (int64, int64, error) {
	row, err := s.q.GetDVRProgress(ctx, db.GetDVRProgressParams{RecordingID: id, UserID: userID})
	if err == nil {
		return row.PositionMs, row.DurationMs, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return 0, 0, err
	}
	r, err := s.get(ctx, id)
	if err != nil {
		return 0, 0, err
	}
	if r.MediaFileID == nil {
		return 0, 0, nil
	}
	item, err := s.q.GetFileItem(ctx, *r.MediaFileID)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, 0, nil
	}
	if err != nil {
		return 0, 0, err
	}
	typ := watchstate.Movie
	itemID := item.MovieID
	if itemID == nil {
		typ = watchstate.Episode
		itemID = item.EpisodeID
	}
	if itemID == nil {
		return 0, 0, nil
	}
	state, err := s.q.GetWatchState(ctx, db.GetWatchStateParams{UserID: userID, ItemType: typ, ItemID: *itemID})
	if errors.Is(err, sql.ErrNoRows) {
		return 0, 0, nil
	}
	return state.PositionMs, state.DurationMs, err
}
func (s *Service) ReportProgress(ctx context.Context, userID int64, id string, positionMS, durationMS int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if positionMS < 0 || durationMS < 0 {
		return ErrInvalid
	}
	r, err := s.get(ctx, id)
	if err != nil {
		return err
	}
	durationMS = min(durationMS, recordingDuration(valueTime(r.StartedAt, r.Start), minTime(s.opts.Now(), r.until())))
	positionMS = min(positionMS, durationMS)
	if err = s.q.PutDVRProgress(ctx, db.PutDVRProgressParams{RecordingID: id, UserID: userID, PositionMs: positionMS, DurationMs: durationMS, UpdatedAt: s.opts.Now().UnixMilli()}); err != nil {
		return err
	}
	if r.MediaFileID != nil {
		return s.transferProgress(ctx, r)
	}
	return nil
}
func (s *Service) transferProgress(ctx context.Context, r record) error {
	if r.MediaFileID == nil || s.opts.Watch == nil {
		return nil
	}
	item, err := s.q.GetFileItem(ctx, *r.MediaFileID)
	if err != nil {
		return err
	}
	typ := watchstate.Movie
	ids := []int64{}
	if item.MovieID != nil {
		ids = append(ids, *item.MovieID)
	} else {
		typ = watchstate.Episode
		episodes, e := s.q.ListEpisodeIDsForFile(ctx, *r.MediaFileID)
		if e != nil {
			return e
		}
		ids = append(ids, episodes...)
	}
	if len(ids) == 0 {
		return nil
	}
	rows, err := s.q.ListDVRProgress(ctx, r.ID)
	if err != nil {
		return err
	}
	// Progress reported while it recorded is against the length recorded
	// then; the library's progress is against the whole file, or someone
	// who stopped near "now" would read as having watched nearly all of it.
	length := recordingDuration(valueTime(r.StartedAt, r.Start), valueTime(r.EndedAt, r.until()))
	if file, e := s.q.GetMediaFile(ctx, *r.MediaFileID); e == nil && file.DurationMs != nil && *file.DurationMs > 0 {
		length = *file.DurationMs
	}
	for _, row := range rows {
		if _, err := s.opts.Watch.ReportAt(ctx, watchstate.Progress{UserID: row.UserID, ItemType: typ, ItemIDs: ids, FileID: *r.MediaFileID, PositionMs: row.PositionMs, DurationMs: max(row.DurationMs, length)}, time.UnixMilli(row.UpdatedAt).UTC()); err != nil {
			return err
		}
		if err := s.q.DeleteDVRProgressVersion(ctx, db.DeleteDVRProgressVersionParams{RecordingID: row.RecordingID, UserID: row.UserID, UpdatedAt: row.UpdatedAt, PositionMs: row.PositionMs, DurationMs: row.DurationMs}); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) validateOwned(ctx context.Context, r record, path string, finished bool) error {
	lib, err := s.q.GetLibrary(ctx, r.LibraryID)
	if err != nil {
		return err
	}
	if err = contained(lib.Path, path); err != nil {
		return err
	}
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return ErrInvalid
	}
	if finished && (info.Size() != r.Bytes || r.FileMtimeNano != 0 && info.ModTime().UnixNano() != r.FileMtimeNano) {
		return ErrInvalid
	}
	return nil
}

// publishSealed commits the writer's exact identity before the only external
// effect, an atomic no-replace rename. That checkpoint also reconciles a
// crash between publication and the final recording-state write. When
// something else took the final name while this recorded, the recording is
// published beside it under the next free name, as §1 says: the other file
// is never claimed, replaced or deleted.
func (s *Service) publishSealed(ctx context.Context, r *record) error {
	if r.Bytes <= 0 || r.FileMtimeNano == 0 {
		return ErrInvalid
	}
	if err := s.validateOwned(ctx, *r, r.PartialPath, true); err != nil {
		return err
	}
	if r.Reason != "finish_pending" {
		r.FinalState, r.FinalReason = r.State, r.Reason
	}
	for range 20 {
		checkpoint := *r
		checkpoint.State = "partial"
		checkpoint.Reason = "finish_pending"
		if err := put(ctx, s.q, checkpoint, s.opts.Now().Unix()); err != nil {
			return err
		}
		err := publishFile(r.PartialPath, r.Path)
		if !errors.Is(err, os.ErrExist) {
			return err
		}
		next, err := s.freeName(ctx, r.Path)
		if err != nil {
			return err
		}
		r.Path = next
	}
	return ErrUnavailable
}

// freeName is the first "<name> (n).ts" beside path that no file, hidden
// partial or recording row uses.
func (s *Service) freeName(ctx context.Context, path string) (string, error) {
	dir, stem := filepath.Dir(path), strings.TrimSuffix(filepath.Base(path), ".ts")
	for n := 2; n <= 1000; n++ {
		name := capBytes(stem, maxStemBytes-8) + fmt.Sprintf(" (%d)", n)
		if p, ok, err := s.unused(ctx, dir, name); err != nil || ok {
			return p, err
		}
	}
	return "", ErrUnavailable
}

// unused reports whether name.ts and its hidden partial are free in dir.
func (s *Service) unused(ctx context.Context, dir, name string) (string, bool, error) {
	path := filepath.Join(dir, name+".ts")
	for _, p := range []string{path, filepath.Join(dir, "."+name+".ts.partial")} {
		if _, err := os.Lstat(p); !errors.Is(err, os.ErrNotExist) {
			return "", false, err
		}
	}
	if _, err := s.q.GetDVRRecordingByPath(ctx, &path); !errors.Is(err, sql.ErrNoRows) {
		return "", false, err
	}
	return path, true, nil
}

// measureClosed fingerprints a closed partial by its path when sealing
// through the writer failed (a sync refused, a handle not reopened). It
// accepts the file only at exactly the length this recording wrote.
func (s *Service) measureClosed(ctx context.Context, r *record) error {
	if err := s.validateOwned(ctx, *r, r.PartialPath, false); err != nil {
		return err
	}
	info, err := os.Lstat(r.PartialPath)
	if err != nil {
		return err
	}
	if info.Size() != r.Bytes {
		return ErrInvalid
	}
	r.FileMtimeNano = info.ModTime().UnixNano()
	return nil
}
func (s *Service) publishedMatches(ctx context.Context, r record) bool {
	return r.Bytes > 0 && r.FileMtimeNano != 0 && s.validateOwned(ctx, r, r.Path, true) == nil
}
