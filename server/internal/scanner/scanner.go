// Package scanner keeps the media_files table in sync with the library
// folders. A scan reads a library, or the paths in it the watcher saw change,
// probes new or changed video files, marks files that disappeared as missing,
// and reports progress on the event bus. Scans are triggered by the
// scheduler, the filesystem watcher, and the API; at most one scan per
// library runs at a time and repeat requests coalesce.
package scanner

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/probe"
)

type Options struct {
	// Workers is how many files are probed at once, across every library
	// being scanned.
	Workers   int
	StableFor time.Duration
	// Interval between scheduled whole-library scans; SetInterval changes it.
	Interval time.Duration
	// Debounce is the quiet period after filesystem events before a rescan;
	// changes wait a minute at most (maxDebounce).
	Debounce time.Duration
	// MatchesAfter reports whether metadata matching runs after a scan that
	// adds or changes files and announces library.changed itself. The scan
	// then leaves the announcement to it, so apps refresh once, with the new
	// files already under their show or movie.
	MatchesAfter func() bool
}

const (
	// maxScopePaths is how many separate paths a scan of changes takes before
	// reading the whole library is simpler.
	maxScopePaths = 256
	// progressAbove is how many files a scan of changes needs before it shows
	// progress; a new episode should not flash a progress bar.
	progressAbove = 200
	// movedWithin is how long a missing file is remembered as the possible
	// old place of a new file with the same size and date.
	movedWithin = 30 * 24 * time.Hour
	// batchSize is how many of a scan's writes are committed together, and
	// batchWait how long they wait at most for more. One commit per file
	// cost 1.47 s for 18,700 files against 0.30 s in one transaction; the
	// wait keeps files found before a slow probe from waiting on it.
	batchSize = 500
	batchWait = time.Second
)

type Scanner struct {
	q     *db.Queries
	probe probe.Interface
	bus   *events.Bus
	log   *slog.Logger
	opts  Options
	// probes holds a slot for each ffprobe running, Workers at most.
	probes chan struct{}

	mu       sync.Mutex
	ctx      context.Context
	states   map[int64]*state
	watch    map[int64]WatchStatus
	interval time.Duration
	retimed  chan struct{}
	// watchLimit overrides the system's limit on watches, for tests.
	watchLimit int
	// watchChanged is signalled when a library's watch status changes, for
	// tests to wait on.
	watchChanged chan struct{}
	// now and after are the clock that decides whether a file is still
	// being written and when to look at it again; tests replace them.
	now   func() time.Time
	after func(time.Duration, func())
}

type state struct {
	running bool
	// queued runs after the current scan; requests meanwhile merge into it.
	queued   *scope
	progress ScanProgress
}

// scope is what one scan reads: a whole library, or some paths in it. A path
// is a file, or a folder to read everything below; a path that is gone marks
// what was there missing.
type scope struct {
	full  bool
	paths []string
}

func wholeLibrary() *scope { return &scope{full: true} }

func pathsScope(paths []string) *scope {
	sc := &scope{}
	sc.merge(&scope{paths: paths})
	return sc
}

// merge adds o to sc. Paths below another path are dropped, and too many
// paths become a whole-library scan.
func (sc *scope) merge(o *scope) {
	if sc.full || o.full {
		sc.full, sc.paths = true, nil
		return
	}
	sc.paths = collapse(append(sc.paths, o.paths...))
	if len(sc.paths) > maxScopePaths {
		sc.full, sc.paths = true, nil
	}
}

// collapse cleans paths, drops duplicates and any path below another one,
// and sorts what is left.
func collapse(paths []string) []string {
	set := make(map[string]bool, len(paths))
	for _, p := range paths {
		set[filepath.Clean(p)] = true
	}
	out := make([]string, 0, len(set))
	for p := range set {
		covered := false
		for d := filepath.Dir(p); !covered; d = filepath.Dir(d) {
			covered = set[d]
			if d == filepath.Dir(d) {
				break
			}
		}
		if !covered {
			out = append(out, p)
		}
	}
	sort.Strings(out)
	return out
}

// within reports whether p is strictly below dir.
func within(dir, p string) bool {
	if !strings.HasSuffix(dir, string(filepath.Separator)) {
		dir += string(filepath.Separator)
	}
	return len(p) > len(dir) && strings.HasPrefix(p, dir)
}

func New(q *db.Queries, p probe.Interface, bus *events.Bus, log *slog.Logger, opts Options) *Scanner {
	if opts.Workers < 1 {
		opts.Workers = 1
	}
	if opts.Debounce == 0 {
		opts.Debounce = 5 * time.Second
	}
	return &Scanner{q: q, probe: p, bus: bus, log: log, opts: opts, probes: make(chan struct{}, opts.Workers), ctx: context.Background(), states: map[int64]*state{},
		watch: map[int64]WatchStatus{}, interval: opts.Interval, retimed: make(chan struct{}, 1),
		watchChanged: make(chan struct{}, 1), now: time.Now, after: func(d time.Duration, f func()) { time.AfterFunc(d, f) }}
}

// Run starts the scheduler and the filesystem watcher and blocks until ctx is
// done. Every library is scanned shortly after start and every interval.
func (s *Scanner) Run(ctx context.Context) {
	s.mu.Lock()
	s.ctx = ctx
	s.mu.Unlock()

	go s.runWatcher(ctx)

	select {
	case <-time.After(time.Second):
		s.RequestAll()
	case <-ctx.Done():
		return
	}
	timer := time.NewTimer(time.Hour)
	defer timer.Stop()
	wait := func() {
		if d := s.Interval(); d > 0 {
			timer.Reset(d)
		} else {
			timer.Stop()
		}
	}
	wait()
	for {
		select {
		case <-ctx.Done():
			return
		case <-s.retimed:
			wait()
		case <-timer.C:
			s.RequestAll()
			wait()
		}
	}
}

// Interval is the time between scheduled whole-library scans.
func (s *Scanner) Interval() time.Duration {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.interval
}

// SetInterval changes the time between scheduled scans; the next one is
// that long from now.
func (s *Scanner) SetInterval(d time.Duration) {
	s.mu.Lock()
	s.interval = d
	s.mu.Unlock()
	select {
	case s.retimed <- struct{}{}:
	default:
	}
}

// RequestAll queues a scan of every library.
func (s *Scanner) RequestAll() {
	libs, err := s.q.ListLibraries(s.context())
	if err != nil {
		s.log.Error("list libraries", "err", err)
		return
	}
	for _, lib := range libs {
		s.Request(lib.ID)
	}
}

// Request queues a scan of a whole library. If one is already running,
// another runs after it; further requests meanwhile are merged into that one.
func (s *Scanner) Request(libraryID int64) { s.enqueue(libraryID, wholeLibrary()) }

// RequestPaths queues a scan of some paths in a library: files, or folders
// to read everything below them. A path that is gone marks the files that
// were there missing.
func (s *Scanner) RequestPaths(libraryID int64, paths []string) {
	if len(paths) > 0 {
		s.enqueue(libraryID, pathsScope(paths))
	}
}

func (s *Scanner) enqueue(libraryID int64, sc *scope) {
	s.mu.Lock()
	st := s.stateLocked(libraryID)
	if st.running {
		if st.queued == nil {
			st.queued = sc
		} else {
			st.queued.merge(sc)
		}
		s.mu.Unlock()
		return
	}
	st.running = true
	s.mu.Unlock()
	go s.runLoop(libraryID, sc)
}

func (s *Scanner) runLoop(libraryID int64, sc *scope) {
	for {
		ctx := s.context()
		lib, err := s.q.GetLibrary(ctx, libraryID)
		if errors.Is(err, sql.ErrNoRows) {
			s.log.Debug("scan: library no longer exists", "library", libraryID)
		} else if err != nil {
			s.log.Error("scan: read library", "library", libraryID, "err", err)
		} else if _, err := s.scan(ctx, lib, sc); err != nil && !errors.Is(err, context.Canceled) {
			s.log.Error("scan failed", "library", libraryID, "err", err)
		}
		s.mu.Lock()
		st := s.stateLocked(libraryID)
		if st.queued == nil || ctx.Err() != nil {
			st.running = false
			st.queued = nil
			s.mu.Unlock()
			return
		}
		sc, st.queued = st.queued, nil
		s.mu.Unlock()
	}
}

// Progress returns the in-flight scan of a library, if it shows progress.
func (s *Scanner) Progress(libraryID int64) (ScanProgress, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	st, ok := s.states[libraryID]
	if !ok || !st.running || st.progress.LibraryID == 0 {
		return ScanProgress{}, false
	}
	return st.progress, true
}

func (s *Scanner) context() context.Context {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.ctx
}

func (s *Scanner) stateLocked(id int64) *state {
	st, ok := s.states[id]
	if !ok {
		st = &state{}
		s.states[id] = st
	}
	return st
}

func (s *Scanner) setProgress(p ScanProgress) {
	s.mu.Lock()
	s.stateLocked(p.LibraryID).progress = p
	s.mu.Unlock()
	s.bus.Publish(events.Event{Type: "scan.progress", Data: p})
}

func (s *Scanner) clearProgress(libraryID int64) {
	s.mu.Lock()
	s.stateLocked(libraryID).progress = ScanProgress{}
	s.mu.Unlock()
}

func (s *Scanner) matchesAfter() bool { return s.opts.MatchesAfter != nil && s.opts.MatchesAfter() }

type entry struct {
	path  string
	rel   string
	size  int64
	mtime time.Time
	// extra is the file's media_files.extra_kind: "trailer" for a trailer
	// (parser.ParseExtra), empty for library content.
	extra string
}

func newEntry(path, rel string, size int64, mtime time.Time) entry {
	e := entry{path: path, rel: rel, size: size, mtime: mtime}
	if x, ok := parser.ParseExtra(rel); ok {
		e.extra = x.Kind
	}
	return e
}

// extraKind is an entry's extra_kind as stored.
func (e entry) extraKind() *string {
	if e.extra == "" {
		return nil
	}
	return &e.extra
}

// extraChanged reports whether a file's kind as read now differs from what
// the database has: a bare trailer.mp4 that an older server matched as a
// second version of its movie, the first scan after the upgrade.
func extraChanged(e entry, k *known) bool {
	return k != nil && e.extra != deref(k.ExtraKind)
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// known is what the database holds about a file, as much as a scan compares
// (ListScanFiles and ListScanFilesUnder return the same columns).
type known db.ListScanFilesRow

type job struct {
	entry
	existing *known
}

type resultKind int

const (
	kindUnchanged resultKind = iota
	kindSkipped
	kindUpsert
	kindCanceled // the scan ended before the file was probed
)

type result struct {
	entry
	kind     resultKind
	existing *known
	params   db.UpsertMediaFileParams
	probeErr error
	// parsed is an unchanged file's name as read now, when that differs
	// from what the database has.
	parsed string
	// extra is set when the file's kind as read now differs from what the
	// database has (extraChanged).
	extra bool
}

// Scan runs one scan of the whole of lib synchronously and returns its summary.
func (s *Scanner) Scan(ctx context.Context, lib db.Library) (ScanFinished, error) {
	return s.scan(ctx, lib, wholeLibrary())
}

// ScanPaths scans some paths of lib synchronously, as RequestPaths would.
func (s *Scanner) ScanPaths(ctx context.Context, lib db.Library, paths []string) (ScanFinished, error) {
	return s.scan(ctx, lib, pathsScope(paths))
}

func (s *Scanner) scan(ctx context.Context, lib db.Library, sc *scope) (ScanFinished, error) {
	start := time.Now()
	token := start.UnixNano() // identifies this scan in media_files.seen_in_scan
	root := filepath.Clean(lib.Path)
	full := sc.full
	var paths []string
	if !full {
		for _, p := range sc.paths {
			rel, ok := below(root, p)
			if !ok || inIgnoredDir(rel) {
				continue
			}
			if rel == "" {
				full = true
				break
			}
			paths = append(paths, p)
		}
		if !full && len(paths) == 0 {
			return ScanFinished{LibraryID: lib.ID, Partial: true}, nil
		}
	}
	log := s.log.With("library", lib.ID, "path", lib.Path)

	if fi, err := os.Stat(root); err != nil || !fi.IsDir() {
		msg := "library root is not accessible"
		if err != nil {
			msg = fmt.Sprintf("library root is not accessible: %v", err)
		}
		log.Warn("scan skipped", "reason", msg)
		s.bus.Publish(events.Event{Type: "scan.failed", Data: ScanFailed{LibraryID: lib.ID, Error: msg}})
		s.setScanError(ctx, lib.ID, msg)
		return ScanFinished{}, errors.New(msg)
	}

	// What is on disk, and what the database says about the same paths.
	var files []entry
	var unreadable []string
	var err error
	if full {
		files, unreadable, err = collect(ctx, root, root)
	} else {
		files, paths, err = collectPaths(ctx, root, s.spellPaths(ctx, lib.ID, root, paths))
	}
	if err != nil {
		s.bus.Publish(events.Event{Type: "scan.failed", Data: ScanFailed{LibraryID: lib.ID, Error: err.Error()}})
		if ctx.Err() == nil {
			s.setScanError(ctx, lib.ID, err.Error())
		}
		return ScanFinished{}, err
	}
	have, err := s.knownFiles(ctx, lib.ID, full, paths)
	if err != nil {
		return ScanFinished{}, err
	}
	onDisk := make(map[string]bool, len(files))
	for _, e := range files {
		onDisk[e.path] = true
	}

	total := len(files)
	showProgress := full || total > progressAbove
	if showProgress {
		log.Info("scan started", "files", total, "partial", !full)
		s.setProgress(ScanProgress{LibraryID: lib.ID, Total: total})
		s.bus.Publish(events.Event{Type: "scan.started", Data: ScanStarted{LibraryID: lib.ID, Total: total}})
		defer s.clearProgress(lib.ID)
	}
	// After a library's first scan, a new file counts as added when found.
	firstScan := lib.LastScannedAt == nil

	jobs := make(chan job)
	results := make(chan result)
	var wg sync.WaitGroup
	for range s.opts.Workers {
		wg.Go(func() {
			for j := range jobs {
				results <- s.process(ctx, lib, j, token, firstScan)
			}
		})
	}
	go func() {
		defer close(results)
		defer wg.Wait()
		defer close(jobs)
		for _, e := range files {
			if ctx.Err() != nil {
				return
			}
			k, ok := have[e.path]
			switch {
			case ok && k.Size == e.size && k.Mtime == db.FormatTime(e.mtime) && !probeOutdated(k):
				results <- result{entry: e, kind: kindUnchanged, existing: &k}
			case ok:
				jobs <- job{entry: e, existing: &k}
			default:
				jobs <- job{entry: e}
			}
		}
	}()

	summary := ScanFinished{LibraryID: lib.ID, Partial: !full}
	scanned := 0
	lastProgress := time.Time{}
	var added []db.MediaFile
	var skipped []string
	var restored []int64
	var youngest time.Time
	// Writes wait in batch and are committed together (see batchSize).
	var batch []result
	var due <-chan time.Time
	flush := func() {
		s.write(ctx, log, batch, &summary, &added)
		batch, due = batch[:0], nil
	}
	for open := true; open; {
		var r result
		select {
		case r, open = <-results:
		case <-due:
			flush()
			continue
		}
		if !open {
			break
		}
		scanned++
		switch r.kind {
		case kindUnchanged:
			summary.Unchanged++
			// Present and unchanged files are not written to: a library of
			// tens of thousands costs one query, not a write per file.
			if r.existing.Status != "present" {
				restored = append(restored, r.existing.ID)
			}
			// Names are read again on every scan (it is cheap), so files
			// scanned before the parser learned a naming style pick it up.
			if parsed := parse(lib, r.rel); parsed != r.existing.Parsed {
				r.parsed = parsed
			}
			r.extra = extraChanged(r.entry, r.existing)
			if r.parsed != "" || r.extra {
				batch = append(batch, r)
			}
		case kindSkipped:
			summary.Skipped++
			skipped = append(skipped, r.path)
			if r.mtime.After(youngest) {
				youngest = r.mtime
			}
		case kindUpsert:
			if r.probeErr != nil {
				log.Warn("probe failed", "file", r.rel, "err", r.probeErr)
			}
			batch = append(batch, r)
		}
		switch {
		case len(batch) >= batchSize:
			flush()
		case len(batch) > 0 && due == nil:
			due = time.After(batchWait)
		}
		if now := time.Now(); showProgress && (now.Sub(lastProgress) > 200*time.Millisecond || scanned == total) {
			lastProgress = now
			s.setProgress(ScanProgress{LibraryID: lib.ID, Scanned: scanned, Total: total, Current: r.rel})
		}
	}
	flush()
	if ctx.Err() != nil {
		return summary, ctx.Err()
	}
	seen := db.FormatTime(time.Now())
	for ids := range slices.Chunk(restored, batchSize) {
		n, err := s.q.MarkMediaFilesSeen(ctx, db.MarkMediaFilesSeenParams{LastSeenAt: seen, SeenInScan: token, Ids: ids})
		if err != nil {
			log.Error("mark present", "files", len(ids), "err", err)
		}
		summary.Restored += int(n)
	}

	// Anything the database had as present that is not on disk now is gone.
	var gone []int64
	for p, k := range have {
		if k.Status == "present" && !onDisk[p] {
			gone = append(gone, k.ID)
		}
	}
	for ids := range slices.Chunk(gone, batchSize) {
		missing, err := s.q.MarkMediaFilesMissing(ctx, ids)
		if err != nil {
			log.Error("mark missing", "err", err)
		}
		summary.Missing += int(missing)
	}
	if len(added) > 0 && !firstScan {
		s.keepMovedDates(ctx, lib.ID, added)
	}
	summary.DurationMs = time.Since(start).Milliseconds()
	if full {
		if err := s.q.SetLibraryScannedAt(ctx, db.SetLibraryScannedAtParams{LastScannedAt: new(db.FormatTime(time.Now())), ID: lib.ID}); err != nil {
			log.Error("set scanned_at", "err", err)
		}
		if len(unreadable) > 0 {
			s.setScanError(ctx, lib.ID, unreadableError(root, unreadable))
		}
	}

	// With matching to follow, a scan that added or changed files leaves
	// announcing it to matching, so apps do not show the new files by file
	// name first and then again under their movie or show.
	newFiles := summary.Added+summary.Updated+summary.Reparsed > 0
	changed := newFiles || summary.Missing+summary.Restored > 0
	// Trailers follow their movie's files: link them again after a scan
	// that changed something (matching does too, after it).
	if changed && lib.Type == library.TypeMovie {
		if _, err := LinkTrailers(ctx, s.q, lib); err != nil && ctx.Err() == nil {
			log.Error("link trailers", "err", err)
		}
	}
	summary.Announced = changed && (!newFiles || !s.matchesAfter())
	if full || changed || summary.Skipped+summary.Failed > 0 {
		log.Info("scan finished", "partial", !full, "added", summary.Added, "updated", summary.Updated, "unchanged", summary.Unchanged,
			"reparsed", summary.Reparsed, "restored", summary.Restored, "missing", summary.Missing, "skipped", summary.Skipped,
			"failed", summary.Failed, "dur", time.Since(start).Round(time.Millisecond))
		s.bus.Publish(events.Event{Type: "scan.finished", Data: summary})
	}
	if summary.Announced {
		s.bus.Publish(events.Event{Type: "library.changed", Data: LibraryChanged{LibraryID: lib.ID}})
	}
	if len(skipped) > 0 && s.opts.StableFor > 0 {
		// Files were still being written: look at just those again once the
		// newest of them should be stable.
		wait := min(max(s.opts.StableFor-s.now().Sub(youngest), 0), 2*s.opts.StableFor) + 250*time.Millisecond
		s.after(wait, func() { s.RequestPaths(lib.ID, skipped) })
	}
	return summary, nil
}

// write commits a batch of a scan's writes in one transaction and counts
// them in sum. A write that fails is logged and left out, as before. If the
// transaction fails, the batch is: the next scan finds those files again.
func (s *Scanner) write(ctx context.Context, log *slog.Logger, batch []result, sum *ScanFinished, added *[]db.MediaFile) {
	if len(batch) == 0 {
		return
	}
	var reparsed, updated, failed int
	var fresh []db.MediaFile
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		for _, r := range batch {
			if r.kind == kindUnchanged {
				if r.parsed != "" {
					if err := q.UpdateMediaFileParsed(ctx, db.UpdateMediaFileParsedParams{Parsed: r.parsed, ID: r.existing.ID}); err != nil {
						log.Error("update parsed name", "file", r.rel, "err", err)
						continue
					}
				}
				if r.extra {
					if err := setExtra(ctx, q, r.existing.ID, r.entry); err != nil {
						log.Error("set extra kind", "file", r.rel, "err", err)
						continue
					}
				}
				reparsed++
				continue
			}
			row, err := q.UpsertMediaFile(ctx, r.params)
			if err != nil {
				log.Error("upsert media file", "file", r.rel, "err", err)
				continue
			}
			if extraChanged(r.entry, r.existing) {
				if err := setExtra(ctx, q, row.ID, r.entry); err != nil {
					log.Error("set extra kind", "file", r.rel, "err", err)
					continue
				}
			}
			if r.probeErr != nil {
				failed++
			}
			if r.existing == nil {
				fresh = append(fresh, row)
			} else {
				updated++
			}
		}
		return nil
	})
	if err != nil {
		if ctx.Err() == nil {
			log.Error("save scanned files", "files", len(batch), "err", err)
		}
		return
	}
	sum.Reparsed += reparsed
	sum.Updated += updated
	sum.Failed += failed
	sum.Added += len(fresh)
	*added = append(*added, fresh...)
}

// setExtra records that a file became an extra or stopped being one: it
// leaves its movie or episodes, or goes back to matching.
func setExtra(ctx context.Context, q *db.Queries, id int64, e entry) error {
	if err := q.SetMediaFileExtra(ctx, db.SetMediaFileExtraParams{ExtraKind: e.extraKind(), ID: id}); err != nil {
		return err
	}
	return q.DeleteEpisodeFilesForFile(ctx, id)
}

// knownFiles loads what the database holds for the whole library, or for
// the given paths and everything below them.
func (s *Scanner) knownFiles(ctx context.Context, libraryID int64, full bool, paths []string) (map[string]known, error) {
	have := map[string]known{}
	if full {
		rows, err := s.q.ListScanFiles(ctx, libraryID)
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			have[r.Path] = known(r)
		}
		return have, nil
	}
	sep := filepath.Separator
	for _, p := range paths {
		rows, err := s.q.ListScanFilesUnder(ctx, db.ListScanFilesUnderParams{LibraryID: libraryID, Path: p, Lo: p + string(sep), Hi: p + string(sep+1)})
		if err != nil {
			return nil, err
		}
		for _, r := range rows {
			have[r.Path] = known(r)
		}
	}
	return have, nil
}

// keepMovedDates gives a new file the added date of a file of the same size
// and modified date that went missing lately: the same file, moved or
// renamed (a folder renamed, the library's folder changed), is not new.
func (s *Scanner) keepMovedDates(ctx context.Context, libraryID int64, added []db.MediaFile) {
	rows, err := s.q.ListRecentlyMissingFiles(ctx, db.ListRecentlyMissingFilesParams{LibraryID: libraryID, UpdatedAt: db.FormatTime(time.Now().Add(-movedWithin))})
	if err != nil {
		s.log.Error("list missing files", "library", libraryID, "err", err)
		return
	}
	if len(rows) == 0 {
		return
	}
	type key struct {
		size  int64
		mtime string
	}
	was := make(map[key][]string, len(rows))
	for _, r := range rows { // most recently missing first
		k := key{r.Size, r.Mtime}
		was[k] = append(was[k], r.AddedAt)
	}
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		for _, f := range added {
			k := key{f.Size, f.Mtime}
			dates := was[k]
			if len(dates) == 0 {
				continue
			}
			was[k] = dates[1:]
			if err := q.SetMediaFileAddedAt(ctx, db.SetMediaFileAddedAtParams{AddedAt: dates[0], ID: f.ID}); err != nil {
				s.log.Error("keep added date", "file", f.Path, "err", err)
			}
		}
		return nil
	})
	if err != nil {
		s.log.Error("keep added dates", "library", libraryID, "err", err)
	}
}

// probeOutdated reports whether an unchanged file's stored probe lacks
// something the prober now reads: Dolby Vision files probed before the
// profile, or the record's base layer and level (PLAN-HDR-PROFILE.md), were
// kept are probed again once. Playback treats them safely meanwhile.
func probeOutdated(k known) bool {
	return k.Hdr == "dolbyvision" && (k.DvProfile == nil || k.DvOutdated == 1)
}

// addedAt is when a newly found file counts as added. In a library's first
// scan it is the file's modified date, so importing a whole library keeps
// the order files arrived in; after that it is when the scan found it, since
// copies keep their old dates. A date in the future (a wrong clock) counts
// as now.
func addedAt(mtime, now time.Time, firstScan bool) time.Time {
	if !firstScan || mtime.IsZero() || mtime.After(now) {
		return now
	}
	return mtime
}

// parse reads what a file is from its path relative to the library root,
// as the JSON stored in media_files.parsed.
func parse(lib db.Library, rel string) string {
	var guess parser.Guess
	switch lib.Type {
	case library.TypeMovie:
		guess = parser.ParseMovie(rel)
	case library.TypeTV:
		guess = parser.ParseEpisode(rel)
	default:
		guess = parser.Guess{Kind: parser.KindUnknown, Title: rel}
	}
	b, _ := json.Marshal(guess)
	return string(b)
}

// process parses and probes one file. It runs on a worker goroutine and does
// not touch the database.
func (s *Scanner) process(ctx context.Context, lib db.Library, j job, token int64, firstScan bool) result {
	r := result{entry: j.entry, existing: j.existing}
	// A file modified moments ago may still be copying. A date further in
	// the future than that is a wrong clock, not a copy, and would otherwise
	// never look stable.
	if age := s.now().Sub(j.mtime); s.opts.StableFor > 0 && age < s.opts.StableFor && age > -s.opts.StableFor {
		r.kind = kindSkipped
		return r
	}
	r.kind = kindUpsert

	now := time.Now()
	r.params = db.UpsertMediaFileParams{
		LibraryID: lib.ID,
		Path:      j.path,
		Size:      j.size,
		Mtime:     db.FormatTime(j.mtime),
		// Kept for a file the database already has (see UpsertMediaFile).
		AddedAt:    db.FormatTime(addedAt(j.mtime, now, firstScan)),
		Parsed:     parse(lib, j.rel),
		LastSeenAt: db.FormatTime(now),
		SeenInScan: token,
		ExtraKind:  j.extraKind(),
	}

	// Scans of several libraries run at once; together they run no more
	// than Workers probes.
	select {
	case s.probes <- struct{}{}:
	case <-ctx.Done():
		r.kind = kindCanceled
		return r
	}
	info, err := s.probe.Probe(ctx, j.path)
	<-s.probes
	if err != nil {
		r.probeErr = err
		r.params.ProbeError = new(err.Error())
		return r
	}
	raw, _ := probe.Encode(info)
	r.params.Probe = new(string(raw))
	r.params.Container = new(info.Container)
	r.params.DurationMs = new(info.DurationMs)
	if len(info.Video) > 0 {
		v := info.Video[0]
		r.params.VideoCodec = new(v.Codec)
		r.params.Width = new(int64(v.Width))
		r.params.Height = new(int64(v.Height))
		r.params.Hdr = v.HDR
		if v.HDR == "dolbyvision" {
			r.params.DvProfile = new(int64(v.DVProfile))
		}
	}
	if len(info.Audio) > 0 {
		r.params.AudioCodec = new(info.Audio[0].Codec)
	}
	return r
}

// collect walks dir, which is root or a folder below it, and returns every
// video file worth scanning and the folders below dir it could not read.
func collect(ctx context.Context, root, dir string) ([]entry, []string, error) {
	var files []entry
	var unreadable []string
	err := filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if err != nil {
			if p == dir {
				return err
			}
			slog.Warn("scan: cannot read", "path", p, "err", err)
			if d != nil && d.IsDir() {
				unreadable = append(unreadable, p)
				return fs.SkipDir
			}
			return nil
		}
		if d.IsDir() {
			if p != dir && skipDir(root, p) {
				return fs.SkipDir
			}
			return nil
		}
		if d.Type()&fs.ModeSymlink != 0 || !parser.IsVideo(d.Name()) || parser.SkipFile(d.Name()) {
			return nil
		}
		// A file removed since its folder was read is left out, as files
		// that were never there are.
		if info, err := d.Info(); err == nil {
			rel, _ := filepath.Rel(root, p)
			files = append(files, newEntry(p, filepath.ToSlash(rel), info.Size(), info.ModTime()))
		}
		return nil
	})
	return files, unreadable, err
}

// setScanError keeps why a scan failed on its library, for the web and the
// apps to show after the scan.failed event is gone. The next full scan that
// finishes clears it.
func (s *Scanner) setScanError(ctx context.Context, libraryID int64, msg string) {
	err := s.q.SetLibraryScanError(ctx, db.SetLibraryScanErrorParams{LastScanError: &msg, LastScanErrorAt: new(db.FormatTime(s.now())), ID: libraryID})
	if err != nil {
		s.log.Error("set scan error", "library", libraryID, "err", err)
	}
}

// unreadableError words the folders a full scan had to skip, which is how a
// library whose files belong to another user (PUID in Docker) looks.
func unreadableError(root string, dirs []string) string {
	rel, err := filepath.Rel(root, dirs[0])
	if err != nil {
		rel = dirs[0]
	}
	if len(dirs) == 1 {
		return fmt.Sprintf("couldn't read the folder %s", filepath.ToSlash(rel))
	}
	return fmt.Sprintf("couldn't read %d folders, such as %s", len(dirs), filepath.ToSlash(rel))
}

// collectPaths reads the video files at or below each path. A path that is
// gone adds nothing, so what was there is marked missing; one that cannot be
// read is left out of the scan, so nothing there is. It returns the paths
// the scan covers.
func collectPaths(ctx context.Context, root string, paths []string) ([]entry, []string, error) {
	var files []entry
	covered := paths[:0:0]
	for _, p := range paths {
		if ctx.Err() != nil {
			return nil, nil, ctx.Err()
		}
		fi, err := os.Lstat(p)
		switch {
		case errors.Is(err, fs.ErrNotExist):
		case err != nil:
			slog.Warn("scan: cannot read", "path", p, "err", err)
			continue
		case fi.IsDir():
			if skipDir(root, p) {
				continue
			}
			found, _, err := collect(ctx, root, p)
			if err != nil {
				if errors.Is(err, fs.ErrNotExist) {
					break // removed while being read
				}
				slog.Warn("scan: cannot read", "path", p, "err", err)
				continue
			}
			files = append(files, found...)
		case fi.Mode()&fs.ModeSymlink == 0 && parser.IsVideo(fi.Name()) && !parser.SkipFile(fi.Name()):
			rel, _ := filepath.Rel(root, p)
			files = append(files, newEntry(p, filepath.ToSlash(rel), fi.Size(), fi.ModTime()))
		}
		covered = append(covered, p)
	}
	return files, covered, nil
}

// inIgnoredDir reports whether rel, a path relative to a library's root,
// is inside a folder scans skip.
func inIgnoredDir(rel string) bool {
	dir := filepath.Dir(rel)
	if dir == "." {
		return false
	}
	return parser.SkipDir(filepath.ToSlash(dir))
}

// skipDir reports whether scans leave out the folder p below root.
func skipDir(root, p string) bool {
	rel, err := filepath.Rel(root, p)
	if err != nil {
		return parser.IgnoreDir(filepath.Base(p))
	}
	return parser.SkipDir(filepath.ToSlash(rel))
}
