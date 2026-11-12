package lists

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/tmdb"
)

// Options make a Service.
type Options struct {
	Queries *db.Queries
	Conn    *sql.DB
	TMDB    *tmdb.Client
	// MDBList is the client with the admin's key; nil makes one without a
	// key.
	MDBList *MDBList
	Bus     *events.Bus
	Log     *slog.Logger
	// Disabled turns every list from an outside service off (lists.enabled
	// false): TMDB and MDBList are not ready and nothing is fetched.
	// Library and import lists still work.
	Disabled bool
	// Changed is told when a list's entries change, to publish
	// lists.changed to its audience; nil tells no one.
	Changed func(db.MediaList)
}

// Service holds the sources, stores what they fetch, and fetches lists
// again in the background (Run).
type Service struct {
	q        *db.Queries
	conn     *sql.DB
	tmdb     *tmdb.Client
	mdb      *MDBList
	bus      *events.Bus
	log      *slog.Logger
	disabled bool
	changed  func(db.MediaList)
	sources  map[string]Source
	importer *Importer
	wake     chan struct{}

	mu       sync.Mutex
	fetching map[int64]bool
	wanted   map[int64]time.Time

	running atomic.Bool
	libGen  atomic.Uint64

	// Delay is how long after start the first run waits.
	Delay time.Duration
	// Every is how often the job looks for lists that are due.
	Every time.Duration
	// Batch is the most lists a run fetches, Parallel how many at once.
	Batch, Parallel int
	// WantedFor is how long after a screen last showed a list, or someone
	// opened it, the job keeps fetching it.
	WantedFor time.Duration
}

// New returns a service; it fetches in the background from Run.
func New(opts Options) *Service {
	if opts.Log == nil {
		opts.Log = slog.Default()
	}
	if opts.MDBList == nil {
		opts.MDBList = NewMDBList("", MDBListOptions{})
	}
	if opts.TMDB == nil {
		opts.TMDB = tmdb.New("", "")
	}
	find := newResolver(opts.TMDB)
	im := &Importer{q: opts.Queries, tmdb: opts.TMDB, find: find, Workers: 4}
	s := &Service{
		q: opts.Queries, conn: opts.Conn, tmdb: opts.TMDB, mdb: opts.MDBList, bus: opts.Bus, log: opts.Log,
		disabled: opts.Disabled, changed: opts.Changed, importer: im, wake: make(chan struct{}, 1),
		fetching: map[int64]bool{}, wanted: map[int64]time.Time{},
		Delay: time.Minute, Every: 15 * time.Minute, Batch: 20, Parallel: 3, WantedFor: 14 * 24 * time.Hour,
	}
	s.sources = map[string]Source{
		KindTMDB:    &TMDB{Client: opts.TMDB},
		KindMDBList: newMDBListSource(opts.MDBList, find),
		KindLibrary: Library{},
		KindImport:  im,
	}
	return s
}

// MDBList is the MDBList client, for the key in Settings.
func (s *Service) MDBList() *MDBList { return s.mdb }

// Enabled is lists.enabled: whether lists from outside services work.
func (s *Service) Enabled() bool { return !s.disabled }

// Source is the source of kind.
func (s *Service) Source(kind string) (Source, bool) {
	src, ok := s.sources[kind]
	return src, ok
}

// Name is what a list of kind with p says it is from.
func (s *Service) Name(kind string, p Params) string {
	if src, ok := s.sources[kind]; ok {
		return src.Name(p)
	}
	return kind
}

// Ready says whether lists of kind can be made now, and if not why, for
// the builder.
func (s *Service) Ready(kind string) (bool, string) {
	switch kind {
	case KindTMDB:
		if s.disabled {
			return false, "Lists from outside services are turned off on this server"
		}
		if !s.tmdb.Configured() {
			return false, "This server has no TMDB key; an admin can add one in Settings"
		}
	case KindMDBList:
		if s.disabled {
			return false, "Lists from outside services are turned off on this server"
		}
		if !s.mdb.Configured() {
			return false, ErrMDBListNoKey.Reason
		}
	case KindLibrary, KindImport:
	default:
		return false, "This server doesn't know that source"
	}
	return true, ""
}

// Collect checks a request and fetches it, for a new list or a preview.
// A library request is only checked: the api package reads it from the
// library.
func (s *Service) Collect(ctx context.Context, kind string, r Request) (Result, error) {
	src, ok := s.sources[kind]
	if !ok {
		return Result{}, invalid("Choose a source: tmdb, mdblist, library or import")
	}
	if ok, reason := s.Ready(kind); !ok {
		if Outside(kind) && s.disabled {
			return Result{}, ErrDisabled
		}
		return Result{}, invalid("%s", reason)
	}
	if err := src.Validate(r); err != nil {
		return Result{}, err
	}
	if kind == KindLibrary {
		return Result{}, nil
	}
	return src.Fetch(ctx, r)
}

// NextFetch is when a list of kind fetched at now is due again; nil for
// one never fetched.
func NextFetch(kind string, p Params, now time.Time) *string {
	every := Every(kind, p)
	if every == 0 {
		return nil
	}
	return new(db.FormatTime(now.Add(every)))
}

// Create stores a new list and what was fetched for it, in one
// transaction.
func (s *Service) Create(ctx context.Context, p db.CreateMediaListParams, entries []Entry) (db.MediaList, error) {
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return db.MediaList{}, err
	}
	defer func() { _ = tx.Rollback() }()
	q := s.q.WithTx(tx)
	l, err := q.CreateMediaList(ctx, p)
	if err != nil {
		return db.MediaList{}, err
	}
	if err := insertEntries(ctx, q, l.ID, entries); err != nil {
		return db.MediaList{}, err
	}
	return l, tx.Commit()
}

func insertEntries(ctx context.Context, q *db.Queries, listID int64, entries []Entry) error {
	for _, e := range entries {
		p := db.InsertMediaListEntryParams{ListID: listID, Rank: int64(e.Rank), MediaType: e.MediaType, Title: e.Title}
		if e.TMDBID > 0 {
			p.TmdbID = &e.TMDBID
		}
		if e.IMDbID != "" {
			p.ImdbID = &e.IMDbID
		}
		if e.Year > 0 {
			p.Year = &e.Year
		}
		if e.PosterPath != "" {
			p.PosterPath = &e.PosterPath
		}
		if err := q.InsertMediaListEntry(ctx, p); err != nil {
			return err
		}
	}
	return nil
}

// Entries are a list's stored entries in rank order.
func (s *Service) Entries(ctx context.Context, listID int64) ([]Entry, error) {
	rows, err := s.q.ListMediaListEntries(ctx, listID)
	if err != nil {
		return nil, err
	}
	return FromRows(rows), nil
}

// FromRows are stored entries as Entry values.
func FromRows(rows []db.MediaListEntry) []Entry {
	out := make([]Entry, 0, len(rows))
	for _, r := range rows {
		out = append(out, Entry{Rank: int(r.Rank), MediaType: r.MediaType, TMDBID: deref(r.TmdbID), IMDbID: deref(r.ImdbID),
			Title: r.Title, Year: deref(r.Year), PosterPath: deref(r.PosterPath)})
	}
	return out
}

// Replace stores entries as the list's, with when it was fetched and is
// due again, in one transaction, and reports whether they differ from
// what it had.
func (s *Service) Replace(ctx context.Context, l db.MediaList, res Result, now time.Time) (changed bool, err error) {
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer func() { _ = tx.Rollback() }()
	q := s.q.WithTx(tx)
	rows, err := q.ListMediaListEntries(ctx, l.ID)
	if err != nil {
		return false, err
	}
	old := FromRows(rows)
	changed = !sameEntries(old, res.Entries)
	if changed {
		if err := q.DeleteMediaListEntries(ctx, l.ID); err != nil {
			return false, err
		}
		if err := insertEntries(ctx, q, l.ID, res.Entries); err != nil {
			return false, err
		}
	}
	link := res.URL
	if link == "" {
		link = l.SourceUrl
	}
	if err := q.SetMediaListFetched(ctx, db.SetMediaListFetchedParams{
		FetchedAt: new(db.FormatTime(now)), NextFetchAt: NextFetch(l.SourceKind, DecodeParams(l.Params), now), SourceUrl: link, ID: l.ID,
	}); err != nil {
		return false, err
	}
	return changed, tx.Commit()
}

func sameEntries(a, b []Entry) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// Fetching reports whether a fetch of the list runs now.
func (s *Service) Fetching(id int64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.fetching[id]
}

func (s *Service) begin(id int64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.fetching[id] {
		return false
	}
	s.fetching[id] = true
	return true
}

func (s *Service) end(id int64) {
	s.mu.Lock()
	delete(s.fetching, id)
	s.mu.Unlock()
}

// Fetch fetches a list again now: its source again for TMDB and MDBList,
// its titles' TMDB details for an import. A failure keeps the entries it
// had, says why on the list, and backs off; it is ErrFetching while
// another fetch of it runs.
func (s *Service) Fetch(ctx context.Context, id int64) (db.MediaList, error) {
	if !s.begin(id) {
		return db.MediaList{}, ErrFetching
	}
	defer s.end(id)
	l, err := s.q.GetMediaList(ctx, id)
	if err != nil {
		return l, err
	}
	if !Fetched(l.SourceKind) {
		return l, ErrNotFetched
	}
	if Outside(l.SourceKind) && s.disabled {
		return l, ErrDisabled
	}
	p := DecodeParams(l.Params)
	var res Result
	if l.SourceKind == KindImport {
		var entries []Entry
		if entries, err = s.Entries(ctx, id); err == nil {
			var refreshed []Entry
			if refreshed, err = s.importer.Refresh(ctx, entries); err == nil {
				res = Result{Entries: refreshed, URL: l.SourceUrl, Format: p.Format}
			}
		}
	} else {
		res, err = s.sources[l.SourceKind].Fetch(ctx, Request{Params: p, MediaType: l.MediaType, Limit: int(l.EntryLimit)})
	}
	now := time.Now()
	if err != nil {
		if ctx.Err() != nil {
			return l, err
		}
		s.fail(ctx, l, err, now)
		if got, gerr := s.q.GetMediaList(ctx, id); gerr == nil {
			l = got
		}
		return l, err
	}
	changed, err := s.Replace(ctx, l, res, now)
	if err != nil {
		return l, err
	}
	if got, err := s.q.GetMediaList(ctx, id); err == nil {
		l = got
	}
	if changed && s.changed != nil {
		s.changed(l)
	}
	return l, nil
}

// fail notes a failed fetch: why, and when to try again, later after each
// failure in a row, or when MDBList's daily limit resets.
func (s *Service) fail(ctx context.Context, l db.MediaList, cause error, now time.Time) {
	failures := l.Failures + 1
	wait := Backoff(int(failures))
	if e, ok := errors.AsType[*MDBListLimitError](cause); ok && e.RetryAfter > wait {
		wait = e.RetryAfter
	}
	var next *string
	if Every(l.SourceKind, DecodeParams(l.Params)) > 0 {
		next = new(db.FormatTime(now.Add(wait)))
	}
	if err := s.q.SetMediaListFailed(ctx, db.SetMediaListFailedParams{
		Error: new(Describe(cause)), ErrorAt: new(db.FormatTime(now)), Failures: failures, NextFetchAt: next, ID: l.ID,
	}); err != nil {
		s.log.Warn("could not note a list's failed fetch", "list", l.ID, "err", err)
	}
	s.log.Info("list fetch failed", "list", l.ID, "source", l.SourceKind, "failures", failures, "err", cause)
}

// Want notes that a screen showed these lists' rows or someone opened
// them, so the job keeps them fresh. It writes at most once an hour a
// list, and wakes the job for one that fell due while nobody looked.
func (s *Service) Want(ctx context.Context, lists ...db.MediaList) {
	now := time.Now()
	var ids []int64
	wake := false
	s.mu.Lock()
	for _, l := range lists {
		if last, ok := s.wanted[l.ID]; ok && now.Sub(last) < time.Hour {
			continue
		}
		s.wanted[l.ID] = now
		ids = append(ids, l.ID)
		if l.NextFetchAt != nil && *l.NextFetchAt <= db.FormatTime(now) {
			wake = true
		}
	}
	if len(s.wanted) > 10000 {
		clear(s.wanted)
	}
	s.mu.Unlock()
	if len(ids) == 0 {
		return
	}
	if err := s.q.SetMediaListsWanted(ctx, db.SetMediaListsWantedParams{WantedAt: new(db.FormatTime(now)), Ids: ids}); err != nil {
		s.log.Warn("could not note lists shown", "err", err)
		return
	}
	if wake {
		select {
		case s.wake <- struct{}{}:
		default:
		}
	}
}

// LibraryGeneration counts changes to the libraries' items while the job
// runs, so caches of a library's items know to read it again; live is
// false when the job doesn't run, and nothing can be cached.
func (s *Service) LibraryGeneration() (gen uint64, live bool) {
	return s.libGen.Load(), s.running.Load()
}

// Run fetches due lists Delay after it starts and every Every after that,
// and counts library changes for LibraryGeneration, until ctx ends. It
// returns once its fetches have stopped.
func (s *Service) Run(ctx context.Context) {
	var changes <-chan events.Event
	if s.bus != nil {
		ch, cancel := s.bus.Subscribe()
		defer cancel()
		changes = ch
	}
	s.running.Store(true)
	defer s.running.Store(false)
	timer := time.NewTimer(s.Delay)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case e := <-changes:
			switch e.Type {
			case "library.changed", "match.finished", "scan.finished":
				s.libGen.Add(1)
			}
			continue
		case <-s.wake:
		case <-timer.C:
		}
		if _, err := s.RunOnce(ctx); err != nil && ctx.Err() == nil {
			s.log.Warn("could not refresh lists", "err", err)
		}
		timer.Reset(s.Every)
	}
}

// RunOnce fetches up to Batch lists that are due, Parallel at a time, and
// says how many it fetched. Lists of a kind that can't be fetched now (no
// key, lists.enabled off) wait.
func (s *Service) RunOnce(ctx context.Context) (int, error) {
	var kinds []string
	for _, k := range []string{KindTMDB, KindMDBList} {
		if ok, _ := s.Ready(k); ok {
			kinds = append(kinds, k)
		}
	}
	if s.tmdb.Configured() {
		kinds = append(kinds, KindImport)
	}
	if len(kinds) == 0 {
		return 0, nil
	}
	now := time.Now()
	due, err := s.q.ListDueMediaLists(ctx, db.ListDueMediaListsParams{
		Now: new(db.FormatTime(now)), WantedSince: new(db.FormatTime(now.Add(-s.WantedFor))), Kinds: kinds,
	})
	if err != nil {
		return 0, err
	}
	due = due[:min(len(due), max(s.Batch, 1))]
	var (
		wg      sync.WaitGroup
		fetched atomic.Int64
		sem     = make(chan struct{}, max(s.Parallel, 1))
	)
	for _, l := range due {
		select {
		case sem <- struct{}{}:
		case <-ctx.Done():
		}
		if ctx.Err() != nil {
			break
		}
		wg.Go(func() {
			defer func() { <-sem }()
			if _, err := s.Fetch(ctx, l.ID); err == nil {
				fetched.Add(1)
			}
		})
	}
	wg.Wait()
	if n := fetched.Load(); n > 0 {
		s.log.Info("lists refreshed", "lists", n, "due", len(due))
	}
	return int(fetched.Load()), ctx.Err()
}
