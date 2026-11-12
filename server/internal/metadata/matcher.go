// Package metadata matches scanned files to TMDB movies and episodes and
// stores the results. Matching runs per library after scans add or change
// files, on demand from the API, and once at startup for anything pending.
package metadata

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sync/errgroup"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/memo"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/scanner"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/trailers"
	"watchmymedia/server/internal/watchstate"
)

const (
	StatusPending   = "pending"
	StatusMatched   = "matched"
	StatusUnmatched = "unmatched"
	StatusFailed    = "failed"
)

type MatchStarted struct {
	LibraryID int64 `json:"libraryId"`
	Total     int   `json:"total"`
}

type MatchProgress struct {
	LibraryID int64  `json:"libraryId"`
	Done      int    `json:"done"`
	Total     int    `json:"total"`
	Current   string `json:"current,omitempty"`
}

type MatchFinished struct {
	LibraryID  int64 `json:"libraryId"`
	Matched    int   `json:"matched"`
	Unmatched  int   `json:"unmatched"`
	Failed     int   `json:"failed"`
	DurationMs int64 `json:"durationMs"`
}

type Options struct {
	Workers int
	// StartupDelay is how long Run waits before matching what is pending,
	// so matching doesn't slow the server's start; zero means 2 s.
	StartupDelay time.Duration
	// Watch moves watch state in rematches; nil makes one over the same
	// queries and bus.
	Watch *watchstate.Service
}

type Matcher struct {
	q    *db.Queries
	tmdb *tmdb.Client
	bus  *events.Bus
	log  *slog.Logger
	opts Options

	mu     sync.Mutex
	ctx    context.Context
	states map[int64]*state

	// shows serialises storing one show of a library, so the episode
	// workers of a show don't all fetch it from TMDB, while other shows go
	// on.
	shows showLocks

	// flight shares one TMDB lookup among the workers asking the same at
	// once; the maps keep the answers a while, as every episode in a show
	// folder asks the same.
	flight memo.Group
	// tvs are recent show details: line-up previews, the check that a show
	// has a file's season, and storing a show all ask for them.
	tvs *memo.Map[int, *tmdb.TVShow]
	// ids are what IDs written in names resolved to: a TMDB ID, or 0 when
	// TMDB doesn't know the ID for the library's type.
	ids *memo.Map[string, int]
	// searches are recent TMDB search answers.
	searches *memo.Map[string, []scoreInput]
	// refetched are the stored seasons fetched again for an episode they
	// lacked (see refetchSeason).
	refetched *memo.Map[string, struct{}]
	// seasonLists and orders are what season line-ups read to suggest and
	// preview: a season's episodes with their names, and a show's other
	// episode orders (see seasonmap.go).
	seasonLists *memo.Map[string, *tmdb.Season]
	orders      *memo.Map[int, []*tmdb.EpisodeGroup]

	// hints says which movie or show Radarr or Sonarr has a file's folder
	// as; nil without them.
	hints atomic.Pointer[Hints]
}

// Hints returns the TMDB ID (or, failing that, the TVDB ID) of the movie or
// show an outside app has a file's folder as, or zeros.
type Hints func(lib db.Library, path string) (tmdbID, tvdbID int)

// SetHints gives the matcher the IDs Radarr and Sonarr know files by. They
// come after a match a person chose and IDs in names, before a title search.
func (m *Matcher) SetHints(h Hints) { m.hints.Store(&h) }

// ChainHints composes independent providers in priority order.
func ChainHints(hints ...Hints) Hints {
	return func(lib db.Library, path string) (int, int) {
		for _, h := range hints {
			if h != nil {
				a, b := h(lib, path)
				if a != 0 || b != 0 {
					return a, b
				}
			}
		}
		return 0, 0
	}
}

type state struct {
	running, pending, rematch bool
	// announce is set when a scan added or changed files: the run sends
	// library.changed when done, which the scan left to it.
	announce bool
	progress MatchProgress
}

// announceEvery is how often a long run lets apps show what it has matched
// so far.
const announceEvery = 5 * time.Second

// retryEvery is how often files without a match are tried again: TMDB adds
// new seasons, episodes and shows within days.
const retryEvery = 24 * time.Hour

func New(q *db.Queries, client *tmdb.Client, bus *events.Bus, log *slog.Logger, opts Options) *Matcher {
	if opts.Workers < 1 {
		opts.Workers = 4
	}
	if opts.StartupDelay <= 0 {
		opts.StartupDelay = 2 * time.Second
	}
	if opts.Watch == nil {
		opts.Watch = watchstate.New(q, bus)
	}
	return &Matcher{q: q, tmdb: client, bus: bus, log: log, opts: opts, ctx: context.Background(), states: map[int64]*state{},
		// A shared lookup gives up after a minute, retries included.
		flight:    memo.Group{Timeout: time.Minute},
		tvs:       memo.NewMap[int, *tmdb.TVShow](10*time.Minute, 500),
		ids:       memo.NewMap[string, int](time.Hour, 5000),
		searches:  memo.NewMap[string, []scoreInput](searchTTL, 2000),
		refetched: memo.NewMap[string, struct{}](seasonRefetchEvery, 10000),

		seasonLists: memo.NewMap[string, *tmdb.Season](10*time.Minute, 500),
		orders:      memo.NewMap[int, []*tmdb.EpisodeGroup](10*time.Minute, 200),
	}
}

// showKey names a show of a library.
type showKey struct {
	library int64
	tmdbID  int
}

// showLocks are a lock per show, made when first wanted and dropped when
// nobody holds or waits for them.
type showLocks struct {
	mu    sync.Mutex
	locks map[showKey]*showLock
}

type showLock struct {
	sync.Mutex
	users int
}

// lock locks the show and returns the function that unlocks it.
func (l *showLocks) lock(k showKey) (unlock func()) {
	l.mu.Lock()
	if l.locks == nil {
		l.locks = map[showKey]*showLock{}
	}
	sl := l.locks[k]
	if sl == nil {
		sl = &showLock{}
		l.locks[k] = sl
	}
	sl.users++
	l.mu.Unlock()
	sl.Lock()
	return func() {
		sl.Unlock()
		l.mu.Lock()
		if sl.users--; sl.users == 0 {
			delete(l.locks, k)
		}
		l.mu.Unlock()
	}
}

func (m *Matcher) Configured() bool { return m.tmdb.Configured() }

// Run matches pending files at startup and after every scan that changed
// something, and once a day tries unmatched ones again, until ctx is done.
// A scan that changed something leaves announcing the change to matching
// (see scanner.Options.MatchesAfter), so the run announces it.
func (m *Matcher) Run(ctx context.Context) {
	m.mu.Lock()
	m.ctx = ctx
	m.mu.Unlock()
	if !m.Configured() {
		m.log.Warn("TMDB API key not configured; metadata matching is disabled")
	}
	ch, cancel := m.bus.Subscribe()
	defer cancel()
	startup := time.After(m.opts.StartupDelay)
	retry := time.NewTicker(retryEvery)
	defer retry.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-startup:
			m.RequestAll(false)
		case <-retry.C:
			m.RequestAll(true)
		case e := <-ch:
			if f, ok := e.Data.(scanner.ScanFinished); ok && f.Added+f.Updated+f.Reparsed > 0 {
				m.request(f.LibraryID, false, !f.Announced)
			}
		}
	}
}

// RequestAll queues matching for every library.
func (m *Matcher) RequestAll(rematch bool) {
	libs, err := m.q.ListLibraries(m.context())
	if err != nil {
		m.log.Error("list libraries", "err", err)
		return
	}
	for _, lib := range libs {
		m.Request(lib.ID, rematch)
	}
}

// Request queues matching of one library's pending files. With rematch,
// unmatched and failed files are tried again too. Requests coalesce.
func (m *Matcher) Request(libraryID int64, rematch bool) { m.request(libraryID, rematch, false) }

func (m *Matcher) request(libraryID int64, rematch, announce bool) {
	if !m.Configured() {
		if announce {
			// The key was removed since the scan asked: say it here.
			m.bus.Publish(events.Event{Type: "library.changed", Data: scanner.LibraryChanged{LibraryID: libraryID}})
		}
		return
	}
	m.mu.Lock()
	st := m.stateLocked(libraryID)
	st.rematch = st.rematch || rematch
	st.announce = st.announce || announce
	if st.running {
		st.pending = true
		m.mu.Unlock()
		return
	}
	st.running = true
	m.mu.Unlock()
	go m.runLoop(libraryID)
}

func (m *Matcher) runLoop(libraryID int64) {
	for {
		ctx := m.context()
		m.mu.Lock()
		st := m.stateLocked(libraryID)
		rematch, announce := st.rematch, st.announce
		st.rematch, st.announce = false, false
		m.mu.Unlock()
		lib, err := m.q.GetLibrary(ctx, libraryID)
		if err != nil {
			m.log.Error("match: load library", "library", libraryID, "err", err)
		} else if _, err := m.matchLibrary(ctx, lib, rematch, announce); err != nil && !errors.Is(err, context.Canceled) {
			m.log.Error("match failed", "library", libraryID, "err", err)
			if announce {
				m.bus.Publish(events.Event{Type: "library.changed", Data: scanner.LibraryChanged{LibraryID: libraryID}})
			}
		}
		m.mu.Lock()
		st = m.stateLocked(libraryID)
		if !st.pending || ctx.Err() != nil {
			st.running, st.pending, st.rematch, st.announce = false, false, false, false
			m.mu.Unlock()
			return
		}
		st.pending = false
		m.mu.Unlock()
	}
}

// Progress returns the in-flight match run of a library, if any.
func (m *Matcher) Progress(libraryID int64) (MatchProgress, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	st, ok := m.states[libraryID]
	if !ok || !st.running {
		return MatchProgress{}, false
	}
	return st.progress, true
}

func (m *Matcher) context() context.Context {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.ctx
}

func (m *Matcher) stateLocked(id int64) *state {
	st, ok := m.states[id]
	if !ok {
		st = &state{}
		m.states[id] = st
	}
	return st
}

func (m *Matcher) setProgress(p MatchProgress) {
	m.mu.Lock()
	m.stateLocked(p.LibraryID).progress = p
	m.mu.Unlock()
	m.bus.Publish(events.Event{Type: "match.progress", Data: p})
}

// MatchLibrary matches the library's pending files (or, with rematch, every
// file that is not matched and not locked) and returns a summary.
func (m *Matcher) MatchLibrary(ctx context.Context, lib db.Library, rematch bool) (MatchFinished, error) {
	return m.matchLibrary(ctx, lib, rematch, false)
}

// matchLibrary is MatchLibrary; with announce it sends library.changed when
// done even if no file changed state, for the scan that asked.
func (m *Matcher) matchLibrary(ctx context.Context, lib db.Library, rematch, announce bool) (MatchFinished, error) {
	changed := func() {
		m.bus.Publish(events.Event{Type: "library.changed", Data: scanner.LibraryChanged{LibraryID: lib.ID}})
	}
	if !m.Configured() {
		return MatchFinished{}, tmdb.ErrNoAPIKey
	}
	start := time.Now()
	var files []db.MediaFile
	var err error
	if rematch {
		files, err = m.q.ListFilesToRematch(ctx, lib.ID)
	} else {
		files, err = m.q.ListPendingMatches(ctx, lib.ID)
	}
	if err != nil {
		return MatchFinished{}, err
	}
	total := len(files)
	summary := MatchFinished{LibraryID: lib.ID}
	if total == 0 {
		// Still report completion so a "Match" click in the UI gets an answer.
		m.bus.Publish(events.Event{Type: "match.finished", Data: summary})
		rated := m.fillContentRatings(ctx, lib)
		if m.linkTrailers(ctx, lib)+rated > 0 || announce {
			changed()
		}
		return summary, nil
	}
	log := m.log.With("library", lib.ID)
	log.Info("matching started", "files", total)
	m.setProgress(MatchProgress{LibraryID: lib.ID, Total: total})
	m.bus.Publish(events.Event{Type: "match.started", Data: MatchStarted{LibraryID: lib.ID, Total: total}})

	type outcome struct {
		file   db.MediaFile
		status string
		err    error
	}
	jobs := make(chan db.MediaFile)
	results := make(chan outcome)
	var wg sync.WaitGroup
	for i := 0; i < m.opts.Workers; i++ {
		wg.Go(func() {
			for f := range jobs {
				status, err := m.matchFile(ctx, lib, f)
				results <- outcome{file: f, status: status, err: err}
			}
		})
	}
	go func() {
		defer close(results)
		defer wg.Wait()
		defer close(jobs)
		for _, f := range files {
			select {
			case jobs <- f:
			case <-ctx.Done():
				return
			}
		}
	}()
	done := 0
	lastProgress := time.Time{}
	lastAnnounce, announced := start, 0
	var noShows []db.MediaFile
	for r := range results {
		done++
		switch r.status {
		case StatusMatched:
			summary.Matched++
		case StatusUnmatched:
			summary.Unmatched++
			var nm *noMatchError
			if errors.As(r.err, &nm) && nm.noShow {
				noShows = append(noShows, r.file)
			}
		default:
			summary.Failed++
			if r.err != nil {
				log.Warn("match error", "file", r.file.Path, "err", r.err)
			}
		}
		if now := time.Now(); now.Sub(lastProgress) > 200*time.Millisecond || done == total {
			lastProgress = now
			m.setProgress(MatchProgress{LibraryID: lib.ID, Done: done, Total: total, Current: r.file.Path})
		}
		// A long run (a new library, a season pack of a new show) shows what
		// it has matched so far rather than nothing until the end.
		if now := time.Now(); summary.Matched > announced && now.Sub(lastAnnounce) >= announceEvery && done < total {
			lastAnnounce, announced = now, summary.Matched
			changed()
		}
	}
	if ctx.Err() != nil {
		return summary, ctx.Err()
	}
	// An episode whose own names found no show follows the show its
	// folder's other episodes are linked to (see matchEpisode), and they
	// may have matched in this run, after it.
	for _, f := range noShows {
		if _, ok := m.folderShow(ctx, lib, f); !ok {
			continue
		}
		status, err := m.matchFile(ctx, lib, f)
		if ctx.Err() != nil {
			return summary, ctx.Err()
		}
		switch status {
		case StatusMatched:
			summary.Matched++
			summary.Unmatched--
		case StatusFailed:
			summary.Failed++
			summary.Unmatched--
			log.Warn("match error", "file", f.Path, "err", err)
		}
	}
	summary.DurationMs = time.Since(start).Milliseconds()
	log.Info("matching finished", "matched", summary.Matched, "unmatched", summary.Unmatched, "failed", summary.Failed,
		"dur", time.Since(start).Round(time.Millisecond))
	m.bus.Publish(events.Event{Type: "match.finished", Data: summary})
	// Unmatched files change what lists show too: they appear by file name.
	rated := m.fillContentRatings(ctx, lib)
	linked := m.linkTrailers(ctx, lib)
	if announce || summary.Matched+summary.Unmatched+summary.Failed > 0 || rated+linked > 0 {
		changed()
	}
	return summary, nil
}

// matchFile matches one file and records the result. It returns the
// resulting match status.
func (m *Matcher) matchFile(ctx context.Context, lib db.Library, f db.MediaFile) (string, error) {
	var guess parser.Guess
	_ = json.Unmarshal([]byte(f.Parsed), &guess)

	var (
		movieID    *int64
		episodeIDs []int64
		candidates []Candidate
		err        error
	)
	// A match a person chose for the folder comes first, then an ID written
	// in the names or known to Radarr or Sonarr, then a search by title.
	override := m.folderOverride(ctx, lib, f)
	if override == nil {
		if h := m.hints.Load(); h != nil && guess.TMDBID == 0 && guess.TVDBID == 0 && guess.IMDBID == "" {
			guess.TMDBID, guess.TVDBID = (*h)(lib, f.Path)
		}
		override, err = m.nameID(ctx, lib, guess)
	}
	switch {
	case err != nil:
	case lib.Type == library.TypeMovie:
		movieID, candidates, err = m.matchMovie(ctx, lib, guess, override)
	case lib.Type == library.TypeTV:
		episodeIDs, candidates, err = m.matchEpisode(ctx, lib, f, guess, override)
	default:
		err = fmt.Errorf("unsupported library type %q", lib.Type)
	}

	status := StatusMatched
	var errMsg *string
	switch {
	case err != nil && isLookupFailure(err):
		status = StatusFailed
		errMsg = new(err.Error())
	case err != nil:
		status = StatusUnmatched
		errMsg = new(err.Error())
	case movieID == nil && len(episodeIDs) == 0:
		status = StatusUnmatched
	}
	if setErr := m.record(ctx, f.ID, status, movieID, episodeIDs, candidates, errMsg, false); setErr != nil {
		return StatusFailed, setErr
	}
	return status, err
}

// isLookupFailure separates "TMDB could not be reached" from "no good match".
func isLookupFailure(err error) bool {
	if errors.Is(err, tmdb.ErrNotFound) {
		return false
	}
	var nm *noMatchError
	return !errors.As(err, &nm)
}

type noMatchError struct {
	msg string
	// noShow is set when no show could be found for an episode, rather
	// than the show lacking it.
	noShow bool
}

func (e *noMatchError) Error() string { return e.msg }

func noMatch(format string, args ...any) error {
	return &noMatchError{msg: fmt.Sprintf(format, args...)}
}

func noShow(format string, args ...any) error {
	return &noMatchError{msg: fmt.Sprintf(format, args...), noShow: true}
}

func (m *Matcher) record(ctx context.Context, fileID int64, status string, movieID *int64, episodeIDs []int64, candidates []Candidate, errMsg *string, locked bool) error {
	var cand *string
	if len(candidates) > 0 {
		if len(candidates) > 5 {
			candidates = candidates[:5]
		}
		b, _ := json.Marshal(candidates)
		cand = new(string(b))
	}
	var matchedAt *string
	if status == StatusMatched {
		matchedAt = new(db.FormatTime(time.Now()))
	}
	lockedInt := int64(0)
	if locked {
		lockedInt = 1
	}
	// One transaction: a failure part way must not leave a file matched with
	// its old episodes gone and only some of the new ones linked.
	return m.q.Transaction(ctx, func(q *db.Queries) error {
		if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{
			MatchStatus: status, MovieID: movieID, MatchCandidates: cand, MatchError: errMsg,
			MatchedAt: matchedAt, MatchLocked: lockedInt, ID: fileID,
		}); err != nil {
			return err
		}
		if locked {
			// A person matching an ignored file by hand wants it in the library.
			if err := q.SetMediaFileIgnored(ctx, db.SetMediaFileIgnoredParams{MatchIgnored: 0, ID: fileID}); err != nil {
				return err
			}
		}
		if err := q.DeleteEpisodeFilesForFile(ctx, fileID); err != nil {
			return err
		}
		for _, id := range episodeIDs {
			if err := q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: id, MediaFileID: fileID}); err != nil {
				return err
			}
		}
		return nil
	})
}

// --- movies ---------------------------------------------------------------

// searchOne searches TMDB for one name, as typed in the fix-match dialog,
// and again without the year when that finds nothing.
func (m *Matcher) searchOne(ctx context.Context, kind, name string, year *int, country string) ([]Candidate, error) {
	in, err := m.search(ctx, kind, name, year)
	if err != nil {
		return nil, err
	}
	if len(in) == 0 && year != nil {
		if in, err = m.search(ctx, kind, name, nil); err != nil {
			return nil, err
		}
	}
	return rank([]reading{{name, year}}, country, in), nil
}

func (m *Matcher) searchMovies(ctx context.Context, title string, year *int) ([]Candidate, error) {
	if strings.TrimSpace(title) == "" {
		return nil, noMatch("no title could be read from the file name")
	}
	return m.searchOne(ctx, library.TypeMovie, title, year, "")
}

func (m *Matcher) matchMovie(ctx context.Context, lib db.Library, g parser.Guess, override *int) (*int64, []Candidate, error) {
	if override != nil {
		id, err := m.storeMovie(ctx, lib, *override)
		if err != nil {
			return nil, nil, err
		}
		return &id, nil, nil
	}
	if strings.TrimSpace(g.Title) == "" {
		return nil, nil, noMatch("no title could be read from the file name")
	}
	best, candidates, err := m.find(ctx, library.TypeMovie, g, nil)
	if err != nil {
		return nil, candidates, err
	}
	if len(candidates) == 0 {
		return nil, nil, noMatch("no results on TMDB for %q", g.Title)
	}
	if best == nil {
		return nil, candidates, noMatch("no confident match for %q; best was %q (%d)", g.Title, candidates[0].Title, candidates[0].Year)
	}
	id, err := m.storeMovie(ctx, lib, best.TMDBID)
	if err != nil {
		return nil, candidates, err
	}
	return &id, candidates, nil
}

func (m *Matcher) storeMovie(ctx context.Context, lib db.Library, tmdbID int) (int64, error) {
	mv, err := m.tmdb.GetMovie(ctx, tmdbID)
	if err != nil {
		return 0, err
	}
	genres, _ := json.Marshal(genreNames(mv.Genres))
	row, err := m.q.UpsertMovie(ctx, db.UpsertMovieParams{
		LibraryID:     lib.ID,
		TmdbID:        int64(mv.ID),
		ImdbID:        nilIfEmpty(mv.IMDBID),
		Title:         mv.Title,
		OriginalTitle: nilIfEmpty(mv.OriginalTitle),
		Year:          yearPtr(mv.ReleaseDate),
		ReleaseDate:   nilIfEmpty(mv.ReleaseDate),
		Overview:      mv.Overview,
		Tagline:       nilIfEmpty(mv.Tagline),
		RuntimeMin:    intPtrNonZero(mv.Runtime),
		Genres:        string(genres),
		Rating:        floatPtrNonZero(mv.VoteAverage),
		PosterPath:    nilIfEmpty(mv.PosterPath),
		BackdropPath:  nilIfEmpty(mv.BackdropPath),
		Credits:       creditsJSON(mv.Credits),
		ContentRating: new(mv.ReleaseDates.Certification(RatingCountry)),
		// The videos came with the details, so the trailers are checked
		// now too (PLAN-TRAILERS.md §5.1).
		Trailers:          trailers.Encode(trailers.Choose(mv.Videos.Results)),
		TrailersCheckedAt: new(db.FormatTime(time.Now())),
	})
	if err != nil {
		return 0, err
	}
	return row.ID, nil
}

// Credit is a top-billed cast member kept with a movie or show.
type Credit struct {
	Name        string `json:"name"`
	Character   string `json:"character,omitempty"`
	ProfilePath string `json:"profilePath,omitempty"`
}

func creditsJSON(c tmdb.Credits) string {
	out := make([]Credit, 0, 12)
	for _, m := range c.Cast {
		if len(out) == 12 {
			break
		}
		out = append(out, Credit{Name: m.Name, Character: m.Character, ProfilePath: m.ProfilePath})
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// --- shows and episodes ---------------------------------------------------

func (m *Matcher) searchShows(ctx context.Context, title string, year *int) ([]Candidate, error) {
	name, country := splitCountry(title)
	if strings.TrimSpace(name) == "" {
		return nil, noMatch("no show name could be read from the folder or file name")
	}
	return m.searchOne(ctx, library.TypeTV, name, year, country)
}

func (m *Matcher) matchEpisode(ctx context.Context, lib db.Library, f db.MediaFile, g parser.Guess, override *int) ([]int64, []Candidate, error) {
	if g.Kind != parser.KindEpisode || (g.AirDate == "" && (g.Season == nil || len(g.Episodes) == 0)) {
		return nil, nil, noMatch("could not read a season and episode number from the file name")
	}
	// A season a person lined up for the folder goes where they said, on the
	// folder's show or another, before anything is searched.
	if rule, ok := m.seasonRule(ctx, lib, f, g); ok {
		ids, err := m.linkByRule(ctx, lib, rule, g)
		return ids, nil, err
	}
	if override != nil {
		ids, err := m.linkGuess(ctx, lib, *override, g)
		return ids, nil, err
	}
	if name, _ := splitCountry(g.Title); strings.TrimSpace(name) == "" {
		return nil, nil, noMatch("no show name could be read from the folder or file name")
	}
	// Among same-name shows, a confident one that has the file's season
	// and episodes wins over one that does not have the season at all.
	best, candidates, err := m.find(ctx, library.TypeTV, g, func(c Candidate, top bool) (bool, error) {
		return m.fitsShow(ctx, c.TMDBID, g, !top)
	})
	if err != nil {
		return nil, candidates, err
	}
	if best == nil {
		// The file's own names find nothing TMDB has for sure, but the
		// folder's other episodes are linked to a show: it is that one
		// ("American Sports Story/American.Sports.Story.S01E03", whose
		// sibling "…Aaron.Hernandez.S01E01" names the show in full).
		if id, ok := m.folderShow(ctx, lib, f); ok {
			ids, err := m.linkGuess(ctx, lib, id, g)
			return ids, candidates, err
		}
		if len(candidates) == 0 {
			return nil, nil, noShow("no results on TMDB for show %q", g.Title)
		}
		return nil, candidates, noShow("no confident match for show %q; best was %q (%d)", g.Title, candidates[0].Title, candidates[0].Year)
	}
	ids, err := m.linkGuess(ctx, lib, best.TMDBID, g)
	return ids, candidates, err
}

// linkEpisodes makes sure the show and season exist locally and returns the
// local episode IDs for the given numbers.
func (m *Matcher) linkEpisodes(ctx context.Context, lib db.Library, tmdbShowID, season int, episodes []int) ([]int64, error) {
	defer m.shows.lock(showKey{lib.ID, tmdbShowID})()

	show, err := m.q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: lib.ID, TmdbID: int64(tmdbShowID)})
	if err != nil {
		if show, err = m.storeShow(ctx, lib, tmdbShowID); err != nil {
			return nil, err
		}
	}
	seasonRow, err := m.q.GetSeasonByNumber(ctx, db.GetSeasonByNumberParams{ShowID: show.ID, Number: int64(season)})
	fetch := err != nil
	if !fetch {
		// A season row with no episodes yet still needs its episode list.
		eps, _ := m.q.ListSeasonEpisodes(ctx, seasonRow.ID)
		fetch = len(eps) == 0
	}
	if fetch {
		if seasonRow, err = m.storeSeason(ctx, show, season); err != nil {
			return nil, err
		}
	}
	lookup := func() (ids []int64, missing []int) {
		for _, n := range episodes {
			ep, err := m.q.GetEpisodeByNumber(ctx, db.GetEpisodeByNumberParams{SeasonID: seasonRow.ID, Number: int64(n)})
			if err != nil {
				missing = append(missing, n)
				continue
			}
			ids = append(ids, ep.ID)
		}
		return ids, missing
	}
	ids, missing := lookup()
	if len(missing) > 0 && !fetch && m.refetchSeason(show.TmdbID, season) {
		// TMDB may have listed the episode since the season was stored.
		if seasonRow, err = m.storeSeason(ctx, show, season); err != nil {
			return nil, err
		}
		ids, missing = lookup()
	}
	if len(ids) == 0 {
		return nil, noMatch("season %d of %q has no episode %v on TMDB", season, show.Title, missing)
	}
	return ids, nil
}

// seasonRefetchEvery is how often a stored season may be fetched again for
// an episode it lacks: a season of files past what TMDB lists would
// otherwise ask once per file, and on every retry.
const seasonRefetchEvery = 6 * time.Hour

// refetchSeason says whether a stored season may be fetched again now, and
// if so notes that it is.
func (m *Matcher) refetchSeason(tmdbID int64, season int) bool {
	return m.refetched.Add(fmt.Sprintf("%d/%d", tmdbID, season), struct{}{})
}

// seasonFetches is how many of a show's seasons are fetched at once when it
// is stored; the client's limiter still paces them.
const seasonFetches = 4

func (m *Matcher) storeShow(ctx context.Context, lib db.Library, tmdbID int) (db.Show, error) {
	tv, err := m.getTV(ctx, tmdbID)
	if err != nil {
		return db.Show{}, err
	}
	// Every season's episode list is stored, with or without files, so the
	// UI can show aired-but-missing and upcoming episodes. They are all
	// fetched first, a few at once, and written in one transaction, so a
	// failure leaves no show stored with only some of its seasons.
	seasons := make([]*tmdb.Season, len(tv.Seasons))
	g, gctx := errgroup.WithContext(ctx)
	g.SetLimit(seasonFetches)
	for i, s := range tv.Seasons {
		g.Go(func() error {
			got, err := m.tmdb.GetSeason(gctx, tv.ID, s.SeasonNumber)
			switch {
			case errors.Is(err, tmdb.ErrNotFound):
				// Listed without a page of its own: its summary is kept.
			case err != nil:
				return err
			default:
				seasons[i] = got
			}
			return nil
		})
	}
	if err := g.Wait(); err != nil {
		return db.Show{}, err
	}
	genres, _ := json.Marshal(genreNames(tv.Genres))
	var show db.Show
	err = m.q.Transaction(ctx, func(q *db.Queries) error {
		var err error
		show, err = q.UpsertShow(ctx, db.UpsertShowParams{
			LibraryID:     lib.ID,
			TmdbID:        int64(tv.ID),
			Title:         tv.Name,
			OriginalTitle: nilIfEmpty(tv.OriginalName),
			Year:          yearPtr(tv.FirstAirDate),
			FirstAirDate:  nilIfEmpty(tv.FirstAirDate),
			Overview:      tv.Overview,
			Status:        nilIfEmpty(tv.Status),
			Genres:        string(genres),
			Rating:        floatPtrNonZero(tv.VoteAverage),
			PosterPath:    nilIfEmpty(tv.PosterPath),
			BackdropPath:  nilIfEmpty(tv.BackdropPath),
			LastAirDate:   nilIfEmpty(tv.LastAirDate),
			NextAirDate:   nextAirDate(tv.NextEpisode),
			Credits:       creditsJSON(tv.Credits),
			ContentRating: new(tv.ContentRatings.Certification(RatingCountry)),
		})
		if err != nil {
			return err
		}
		for i, s := range tv.Seasons {
			if _, err := q.UpsertSeason(ctx, db.UpsertSeasonParams{
				ShowID: show.ID, Number: int64(s.SeasonNumber), Name: s.Name, Overview: s.Overview,
				AirDate: nilIfEmpty(s.AirDate), PosterPath: nilIfEmpty(s.PosterPath), EpisodeCount: int64(s.EpisodeCount),
			}); err != nil {
				return err
			}
			if seasons[i] != nil {
				if _, err := writeSeason(ctx, q, show, seasons[i]); err != nil {
					return err
				}
			}
		}
		return nil
	})
	if err != nil {
		return db.Show{}, err
	}
	return show, nil
}

func nextAirDate(e *tmdb.EpisodeRef) *string {
	if e == nil || e.AirDate == "" {
		return nil
	}
	return &e.AirDate
}

// storeSeason fetches a season's episodes and stores the season with them,
// in one transaction.
func (m *Matcher) storeSeason(ctx context.Context, show db.Show, number int) (db.Season, error) {
	s, err := m.tmdb.GetSeason(ctx, int(show.TmdbID), number)
	if err != nil {
		if errors.Is(err, tmdb.ErrNotFound) {
			return db.Season{}, noMatch("%q has no season %d on TMDB", show.Title, number)
		}
		return db.Season{}, err
	}
	var row db.Season
	err = m.q.Transaction(ctx, func(q *db.Queries) error {
		var err error
		row, err = writeSeason(ctx, q, show, s)
		return err
	})
	return row, err
}

// writeSeason stores a season TMDB gave and its episodes, with q (a
// transaction).
func writeSeason(ctx context.Context, q *db.Queries, show db.Show, s *tmdb.Season) (db.Season, error) {
	row, err := q.UpsertSeason(ctx, db.UpsertSeasonParams{
		ShowID: show.ID, Number: int64(s.SeasonNumber), Name: s.Name, Overview: s.Overview,
		AirDate: nilIfEmpty(s.AirDate), PosterPath: nilIfEmpty(s.PosterPath), EpisodeCount: int64(len(s.Episodes)),
	})
	if err != nil {
		return db.Season{}, err
	}
	for _, e := range s.Episodes {
		if _, err := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{
			SeasonID: row.ID, ShowID: show.ID, Number: int64(e.EpisodeNumber), Title: e.Name, Overview: e.Overview,
			AirDate: nilIfEmpty(e.AirDate), StillPath: nilIfEmpty(e.StillPath),
			RuntimeMin: intPtrNonZero(e.Runtime), Rating: floatPtrNonZero(e.VoteAverage),
		}); err != nil {
			return db.Season{}, err
		}
	}
	return row, nil
}

// --- manual matching (fix-match UI) ---------------------------------------

// Search returns ranked candidates for a query, for the fix-match dialog.
func (m *Matcher) Search(ctx context.Context, libraryType, query string, year *int) ([]Candidate, error) {
	if r, ok, err := parseRef(query); err != nil {
		return nil, err
	} else if ok {
		return m.searchByRef(ctx, libraryType, r)
	}
	switch libraryType {
	case library.TypeMovie:
		return m.searchMovies(ctx, query, year)
	case library.TypeTV:
		return m.searchShows(ctx, query, year)
	}
	return nil, fmt.Errorf("unsupported library type %q", libraryType)
}

// Apply links a file to a TMDB movie or show chosen by a person and locks
// the match so automatic runs leave it alone. For shows, season and
// episodes default to what the file name says.
func (m *Matcher) Apply(ctx context.Context, lib db.Library, f db.MediaFile, tmdbID int, season *int, episodes []int) error {
	switch lib.Type {
	case library.TypeMovie:
		id, err := m.storeMovie(ctx, lib, tmdbID)
		if err != nil {
			return err
		}
		if err := m.record(ctx, f.ID, StatusMatched, &id, nil, nil, nil, true); err != nil {
			return err
		}
		m.linkTrailers(ctx, lib)
		return nil
	case library.TypeTV:
		var g parser.Guess
		_ = json.Unmarshal([]byte(f.Parsed), &g)
		if season == nil && len(episodes) == 0 {
			// Nothing chosen: go by the name, which may be an air date.
			if g.AirDate == "" && (g.Season == nil || len(g.Episodes) == 0) {
				return noMatch("season and episode numbers are required for this file")
			}
			ids, err := m.linkGuess(ctx, lib, tmdbID, g)
			if err != nil {
				return err
			}
			return m.record(ctx, f.ID, StatusMatched, nil, ids, nil, nil, true)
		}
		if season == nil {
			season = g.Season
		}
		if len(episodes) == 0 {
			episodes = g.Episodes
		}
		if season == nil || len(episodes) == 0 {
			return noMatch("season and episode numbers are required for this file")
		}
		ids, err := m.linkGuess(ctx, lib, tmdbID, parser.Guess{Season: season, Episodes: episodes})
		if err != nil {
			return err
		}
		return m.record(ctx, f.ID, StatusMatched, nil, ids, nil, nil, true)
	}
	return fmt.Errorf("unsupported library type %q", lib.Type)
}

// linkTrailers links the library's trailers to their movies again
// (scanner.LinkTrailers), after matching changed which movie files belong
// to, and returns how many links changed. A failure is logged: the next
// scan or match tries again.
func (m *Matcher) linkTrailers(ctx context.Context, lib db.Library) int {
	n, err := scanner.LinkTrailers(ctx, m.q, lib)
	if err != nil && ctx.Err() == nil {
		m.log.Error("link trailers", "library", lib.ID, "err", err)
	}
	return n
}

// --- helpers --------------------------------------------------------------

func genreNames(gs []tmdb.Genre) []string {
	out := make([]string, 0, len(gs))
	for _, g := range gs {
		out = append(out, g.Name)
	}
	return out
}

func nilIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func yearPtr(date string) *int64 {
	if y := tmdb.Year(date); y > 0 {
		v := int64(y)
		return &v
	}
	return nil
}

func intPtrNonZero(n int) *int64 {
	if n == 0 {
		return nil
	}
	v := int64(n)
	return &v
}

func floatPtrNonZero(f float64) *float64 {
	if f == 0 {
		return nil
	}
	return &f
}
