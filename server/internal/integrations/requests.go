package integrations

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/integrations/arr"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/tmdb"
)

// KeyRequestApproval says whose requests go straight through: "admins" (the
// default: everyone else's wait for an admin) or "everyone".
const KeyRequestApproval = "requests.approval"

// Person is who asks, decides, reports or deletes.
type Person struct {
	ID    int64
	Name  string
	Admin bool
}

// RequestState is a request as an item's page shows it.
type RequestState struct {
	ID          int64     `json:"id"`
	Status      string    `json:"status" enum:"pending,approved,declined,failed,available"`
	RequestedBy string    `json:"requestedBy"`
	Mine        bool      `json:"mine" doc:"The signed-in user asked for it."`
	Seasons     []int     `json:"seasons" nullable:"false" doc:"Season numbers asked for; empty for a movie."`
	Note        string    `json:"note,omitempty" doc:"Why it was declined, or why carrying it out failed."`
	CreatedAt   time.Time `json:"createdAt"`
}

// Request is a request in a list: Your requests, the Inbox.
type Request struct {
	RequestState
	MediaType  string    `json:"mediaType" enum:"movie,tv"`
	TMDBID     int64     `json:"tmdbId"`
	Title      string    `json:"title"`
	Year       int64     `json:"year,omitempty"`
	PosterPath string    `json:"posterPath,omitempty"`
	Download   *Download `json:"download,omitempty"`
	// MovieID or ShowID is the library item once something of it is there.
	MovieID int64 `json:"movieId,omitempty"`
	ShowID  int64 `json:"showId,omitempty"`
}

// RequestsChanged is sent to the person who asked and to admins when a
// request is made or changes.
type RequestsChanged struct {
	RequestID int64  `json:"requestId"`
	Status    string `json:"status"`
}

// Configure gives the service what requests need: TMDB, for titles and TVDB
// IDs, and the event bus.
func (s *Service) Configure(t *tmdb.Client, bus *events.Bus) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tmdb, s.bus = t, bus
}

func (s *Service) settings() *settings.Store { return settings.New(s.q) }

// Approval is who may request without an admin approving: "admins" or
// "everyone".
func (s *Service) Approval(ctx context.Context) string {
	v, _ := s.settings().Get(ctx, KeyRequestApproval, "admins")
	if v != "everyone" {
		return "admins"
	}
	return v
}

func (s *Service) SetApproval(ctx context.Context, v string) error {
	if v != "admins" && v != "everyone" {
		return invalid("approval is admins or everyone")
	}
	return s.settings().Set(ctx, KeyRequestApproval, v)
}

// Requests reports whether requests can be carried out: some Radarr or
// Sonarr has a target for a library.
func (s *Service) Requests() (movies, shows bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.conns {
		if c.arr != nil && len(c.targets) > 0 {
			movies = movies || c.arr.Kind == arr.Radarr
			shows = shows || c.arr.Kind == arr.Sonarr
		}
	}
	return movies, shows
}

// Ask makes a request. Asking for what is already asked for and open adds
// the seasons to the person's own request, or returns someone else's with
// created false. An admin's request, or anyone's when approval is
// "everyone", is carried out at once. With Seerr connected the request goes
// to Seerr instead, as p's linked Seerr user.
func (s *Service) Ask(ctx context.Context, p Person, mediaType string, tmdbID int, seasons []int) (Request, bool, error) {
	if c := s.seerrConn(); c != nil {
		return s.askSeerr(ctx, c, p, mediaType, tmdbID, seasons)
	}
	kind := arr.Radarr
	if mediaType == "tv" {
		kind = arr.Sonarr
	}
	movies, shows := s.Requests()
	if (kind == arr.Radarr && !movies) || (kind == arr.Sonarr && !shows) {
		return Request{}, false, invalid("requests for %s aren't set up on this server", map[arr.Kind]string{arr.Radarr: "movies", arr.Sonarr: "shows"}[kind])
	}
	title, year, poster, err := s.lookup(ctx, mediaType, tmdbID, seasons)
	if err != nil {
		return Request{}, false, err
	}
	if mediaType == "movie" {
		seasons = nil
	} else if len(seasons) == 0 {
		return Request{}, false, invalid("pick at least one season")
	}
	slices.Sort(seasons)
	seasons = slices.Compact(seasons)
	if done, err := s.inLibrary(ctx, mediaType, int64(tmdbID), seasons); err != nil {
		return Request{}, false, err
	} else if done {
		return Request{}, false, invalid("it's in your library already")
	}

	existing, err := s.q.ListRequestsForMedia(ctx, db.ListRequestsForMediaParams{MediaType: mediaType, TmdbID: int64(tmdbID)})
	if err != nil {
		return Request{}, false, err
	}
	for _, r := range existing {
		if r.Status != "pending" && r.Status != "approved" {
			continue
		}
		have := seasonList(r.Seasons)
		missing := slices.DeleteFunc(slices.Clone(seasons), func(n int) bool { return slices.Contains(have, n) })
		if r.UserID != p.ID && len(missing) == 0 || mediaType == "movie" {
			v, err := s.request(ctx, p, r.ID)
			return v, false, err
		}
		// Seasons join the person's own request while it waits, or an approved
		// one when they need no approval; a member's approved request takes no
		// more seasons, or they would download without an admin.
		if r.UserID == p.ID && (r.Status == "pending" || p.Admin || s.Approval(ctx) == "everyone") {
			merged := append(have, missing...)
			slices.Sort(merged)
			mj, _ := json.Marshal(merged)
			if err := s.q.SetRequestSeasons(ctx, db.SetRequestSeasonsParams{Seasons: string(mj), ID: r.ID}); err != nil {
				return Request{}, false, err
			}
			if r.Status == "approved" {
				s.carryOut(ctx, r.ID)
			}
			v, err := s.request(ctx, p, r.ID)
			return v, false, err
		}
		// Someone else asked for some of these seasons, or the person's own
		// approved request has them: this request is for the rest.
		seasons = missing
	}

	sj, _ := json.Marshal(nonNilInts(seasons))
	row, err := s.q.CreateRequest(ctx, db.CreateRequestParams{UserID: p.ID, MediaType: mediaType, TmdbID: int64(tmdbID),
		Title: title, Year: year, PosterPath: poster, Seasons: string(sj)})
	if err != nil {
		return Request{}, false, err
	}
	if p.Admin || s.Approval(ctx) == "everyone" {
		_ = s.q.DecideRequest(ctx, db.DecideRequestParams{DecidedBy: &p.ID, ID: row.ID})
		s.carryOut(ctx, row.ID)
	} else {
		s.announce(row.ID, row.UserID, row.Status)
	}
	v, err := s.request(ctx, p, row.ID)
	return v, true, err
}

// lookup checks the item on TMDB and returns what a request shows of it.
func (s *Service) lookup(ctx context.Context, mediaType string, tmdbID int, seasons []int) (string, *int64, *string, error) {
	s.mu.RLock()
	t := s.tmdb
	s.mu.RUnlock()
	if t == nil || !t.Configured() {
		return "", nil, nil, invalid("this server has no TMDB key")
	}
	switch mediaType {
	case "movie":
		m, err := t.GetMovie(ctx, tmdbID)
		if err != nil {
			return "", nil, nil, lookupError(err)
		}
		return m.Title, yearOf(m.ReleaseDate), nilIfEmpty(m.PosterPath), nil
	case "tv":
		tv, err := t.GetTV(ctx, tmdbID)
		if err != nil {
			return "", nil, nil, lookupError(err)
		}
		for _, n := range seasons {
			if !slices.ContainsFunc(tv.Seasons, func(se tmdb.SeasonSummary) bool { return se.SeasonNumber == n }) {
				return "", nil, nil, invalid("%s has no season %d", tv.Name, n)
			}
		}
		return tv.Name, yearOf(tv.FirstAirDate), nilIfEmpty(tv.PosterPath), nil
	}
	return "", nil, nil, invalid("mediaType is movie or tv")
}

func lookupError(err error) error {
	if errors.Is(err, tmdb.ErrNotFound) {
		return invalid("TMDB has no such item")
	}
	return err
}

// Decide approves a pending or failed request (carrying it out) or declines
// it with a note for the person who asked.
func (s *Service) Decide(ctx context.Context, admin Person, id int64, approve bool, note string) (Request, error) {
	r, err := s.q.GetRequest(ctx, id)
	if errors.Is(err, sql.ErrNoRows) {
		return Request{}, errNotFound
	}
	if err != nil {
		return Request{}, err
	}
	if r.Status != "pending" && r.Status != "failed" {
		return Request{}, invalid("this request is %s already", r.Status)
	}
	if err := s.q.DecideRequest(ctx, db.DecideRequestParams{DecidedBy: &admin.ID, ID: id}); err != nil {
		return Request{}, err
	}
	if approve {
		s.carryOut(ctx, id)
	} else {
		if err := s.q.SetRequestStatus(ctx, db.SetRequestStatusParams{Status: "declined", Note: nilIfEmpty(strings.TrimSpace(note)), ID: id}); err != nil {
			return Request{}, err
		}
		s.announce(id, r.UserID, "declined")
	}
	return s.request(ctx, admin, id)
}

// Cancel withdraws a person's own request while it waits for an admin, in
// Seerr when it is connected.
func (s *Service) Cancel(ctx context.Context, p Person, id int64) error {
	if c := s.seerrConn(); c != nil {
		return s.cancelSeerr(ctx, c, p, id)
	}
	r, err := s.q.GetRequest(ctx, id)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && r.UserID != p.ID && !p.Admin) {
		return errNotFound
	}
	if err != nil {
		return err
	}
	if r.Status != "pending" && r.Status != "failed" {
		return invalid("only a request that is waiting can be cancelled")
	}
	if err := s.q.SetRequestStatus(ctx, db.SetRequestStatusParams{Status: "cancelled", ID: id}); err != nil {
		return err
	}
	s.announce(id, r.UserID, "cancelled")
	return nil
}

// carryOut sends an approved request to Radarr or Sonarr: monitors and
// searches for what an app already has, else adds it to the library's
// target. A failure leaves the request failed with the app's message.
func (s *Service) carryOut(ctx context.Context, id int64) {
	r, err := s.q.GetRequest(ctx, id)
	if err != nil {
		return
	}
	connID, err := s.send(ctx, r)
	status, note := "approved", (*string)(nil)
	if err != nil {
		status, note = "failed", nilIfEmpty(err.Error())
		s.log.Warn("request failed", "request", id, "title", r.Title, "err", err)
	}
	var cid *int64
	if connID > 0 {
		cid = &connID
	}
	if err := s.q.SetRequestStatus(ctx, db.SetRequestStatusParams{Status: status, Note: note, IntegrationID: cid, ID: id}); err != nil {
		s.log.Warn("request status", "request", id, "err", err)
	}
	s.announce(id, r.UserID, status)
}

func (s *Service) send(ctx context.Context, r db.GetRequestRow) (int64, error) {
	kind := arr.Radarr
	if r.MediaType == "tv" {
		kind = arr.Sonarr
	}
	seasons := seasonList(r.Seasons)
	c, itemID := s.byTMDB(kind, int(r.TmdbID))
	if c != nil {
		defer s.RequestRefresh(c.row.ID)
		defer s.forget(c, itemID)
		if kind == arr.Radarr {
			if err := c.arr.SetMoviesMonitored(ctx, []int{itemID}, true); err != nil {
				return c.row.ID, err
			}
			return c.row.ID, c.arr.SearchMovies(ctx, []int{itemID})
		}
		if err := c.arr.MonitorSeasons(ctx, itemID, seasons, true); err != nil {
			return c.row.ID, err
		}
		for _, n := range seasons {
			if err := c.arr.SearchSeason(ctx, itemID, n); err != nil {
				return c.row.ID, err
			}
		}
		return c.row.ID, nil
	}
	c, t := s.target(kind)
	if c == nil {
		return 0, errors.New("no Radarr or Sonarr is set to take requests for this library")
	}
	defer s.RequestRefresh(c.row.ID)
	if kind == arr.Radarr {
		_, err := c.arr.AddMovie(ctx, int(r.TmdbID), t.RootFolder, t.QualityProfileID)
		return c.row.ID, err
	}
	s.mu.RLock()
	tc := s.tmdb
	s.mu.RUnlock()
	tvdb, err := tc.TVDBID(ctx, int(r.TmdbID))
	if err != nil {
		return c.row.ID, err
	}
	if tvdb == 0 {
		return c.row.ID, errors.New("TMDB has no TheTVDB ID for this show, which Sonarr needs")
	}
	_, err = c.arr.AddSeries(ctx, tvdb, t.RootFolder, t.QualityProfileID, seasons)
	return c.row.ID, err
}

// byTMDB finds an app's item for a TMDB ID in any connection of the kind.
func (s *Service) byTMDB(kind arr.Kind, tmdbID int) (*conn, int) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.sorted() {
		if c.arr != nil && c.arr.Kind == kind && c.idx != nil && len(c.idx.byTMDB[tmdbID]) > 0 {
			return c, c.idx.byTMDB[tmdbID][0]
		}
	}
	return nil, 0
}

// target is where new requests of a kind go: the first target of the first
// connection that has one.
func (s *Service) target(kind arr.Kind) (*conn, Target) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.sorted() {
		if c.arr != nil && c.arr.Kind == kind && len(c.targets) > 0 {
			return c, c.targets[0]
		}
	}
	return nil, Target{}
}

func (s *Service) announce(id, userID int64, status string) {
	s.mu.RLock()
	bus := s.bus
	s.mu.RUnlock()
	if bus != nil {
		bus.Publish(events.Event{Type: "requests.changed", Data: RequestsChanged{RequestID: id, Status: status}, To: &events.Audience{UserID: userID}})
	}
}

// request is one request as p sees it, with availability brought up to date.
func (s *Service) request(ctx context.Context, p Person, id int64) (Request, error) {
	r, err := s.q.GetRequest(ctx, id)
	if err != nil {
		return Request{}, err
	}
	out := s.view(ctx, p, r)
	return out, nil
}

func (s *Service) view(ctx context.Context, p Person, r db.GetRequestRow) Request {
	r.Status = s.settle(ctx, r)
	out := Request{RequestState: stateOf(p, r), MediaType: r.MediaType, TMDBID: r.TmdbID, Title: r.Title}
	if r.Year != nil {
		out.Year = *r.Year
	}
	if r.PosterPath != nil {
		out.PosterPath = *r.PosterPath
	}
	if r.MediaType == "movie" {
		if m, err := s.q.GetLibraryMovieByTMDB(ctx, r.TmdbID); err == nil {
			out.MovieID = m.ID
		}
	} else if sh, err := s.q.GetLibraryShowByTMDB(ctx, r.TmdbID); err == nil {
		out.ShowID = sh.ID
	}
	if r.Status == "approved" {
		out.Download = s.requestDownload(ctx, r)
	}
	return out
}

func stateOf(p Person, r db.GetRequestRow) RequestState {
	st := RequestState{ID: r.ID, Status: r.Status, RequestedBy: r.UserName, Mine: r.UserID == p.ID, Seasons: nonNilInts(seasonList(r.Seasons))}
	if r.Note != nil {
		st.Note = *r.Note
	}
	st.CreatedAt, _ = time.Parse(time.RFC3339Nano, r.CreatedAt)
	return st
}

// inLibrary reports whether the library has the movie, or every listed
// season with each of its aired episodes.
func (s *Service) inLibrary(ctx context.Context, mediaType string, tmdbID int64, seasons []int) (bool, error) {
	if mediaType == "movie" {
		n, err := s.q.CountPresentMovieFilesByTMDB(ctx, tmdbID)
		return n > 0, err
	}
	today := time.Now().UTC().Format("2006-01-02")
	rows, err := s.q.ListSeasonAvailabilityByTMDB(ctx, db.ListSeasonAvailabilityByTMDBParams{Today: &today, TmdbID: tmdbID})
	if err != nil {
		return false, err
	}
	best := map[int64]db.ListSeasonAvailabilityByTMDBRow{}
	for _, row := range rows {
		if b, ok := best[row.Number]; !ok || row.AvailableCount > b.AvailableCount {
			best[row.Number] = row
		}
	}
	for _, n := range seasons {
		b := best[int64(n)]
		if b.AvailableCount == 0 || b.AvailableCount < b.AiredCount {
			return false, nil
		}
	}
	return len(seasons) > 0, nil
}

// settle marks an approved (or pending) request available once the library
// has it.
func (s *Service) settle(ctx context.Context, r db.GetRequestRow) string {
	if r.Status != "approved" && r.Status != "pending" {
		return r.Status
	}
	seasons := seasonList(r.Seasons)
	if r.MediaType == "movie" {
		seasons = []int{0}
	}
	if done, err := s.inLibrary(ctx, r.MediaType, r.TmdbID, seasons); err != nil || !done {
		return r.Status
	}
	if err := s.q.SetRequestStatus(ctx, db.SetRequestStatusParams{Status: "available", Note: r.Note, ID: r.ID}); err == nil {
		s.announce(r.ID, r.UserID, "available")
	}
	return "available"
}

// requestDownload is the first of a request's downloads in the queue.
func (s *Service) requestDownload(ctx context.Context, r db.GetRequestRow) *Download {
	kind := arr.Radarr
	if r.MediaType == "tv" {
		kind = arr.Sonarr
	}
	c, id := s.byTMDB(kind, int(r.TmdbID))
	if c == nil {
		return nil
	}
	seasons := seasonList(r.Seasons)
	for _, q := range s.queue(ctx, c) {
		if (kind == arr.Radarr && q.MovieID == id) || (kind == arr.Sonarr && q.SeriesID == id && slices.Contains(seasons, q.SeasonNumber)) {
			return downloadFrom(q)
		}
	}
	return nil
}

// Mine lists a person's requests, newest first: their requests in Seerr
// when it is connected.
func (s *Service) Mine(ctx context.Context, p Person) ([]Request, error) {
	if c := s.seerrConn(); c != nil {
		return s.mineSeerr(ctx, c, p)
	}
	rows, err := s.q.ListUserRequests(ctx, db.ListUserRequestsParams{UserID: p.ID, Limit: 200})
	if err != nil {
		return nil, err
	}
	out := make([]Request, 0, len(rows))
	for _, r := range rows {
		out = append(out, s.view(ctx, p, db.GetRequestRow(r)))
	}
	return out, nil
}

// Open lists requests waiting on an admin (pending, failed) and, after them,
// those approved and still downloading.
func (s *Service) Open(ctx context.Context, p Person) ([]Request, error) {
	rows, err := s.q.ListOpenRequests(ctx)
	if err != nil {
		return nil, err
	}
	out := []Request{}
	for _, r := range rows {
		if v := s.view(ctx, p, db.GetRequestRow(r)); v.Status != "available" {
			out = append(out, v)
		}
	}
	slices.SortStableFunc(out, func(a, b Request) int {
		return waitOrder(a.Status) - waitOrder(b.Status)
	})
	return out, nil
}

func waitOrder(status string) int {
	return map[string]int{"pending": 0, "failed": 1, "approved": 2}[status]
}

// ForMedia lists an item's requests as p sees them, newest first: Seerr's
// when it is connected.
func (s *Service) ForMedia(ctx context.Context, p Person, mediaType string, tmdbID int64) []RequestState {
	if c := s.seerrConn(); c != nil {
		return s.seerrForMedia(ctx, c, p, mediaType, tmdbID)
	}
	rows, err := s.q.ListRequestsForMedia(ctx, db.ListRequestsForMediaParams{MediaType: mediaType, TmdbID: tmdbID})
	if err != nil {
		return nil
	}
	out := make([]RequestState, 0, len(rows))
	for _, r := range rows {
		gr := db.GetRequestRow(r)
		gr.Status = s.settle(ctx, gr)
		out = append(out, stateOf(p, gr))
	}
	return out
}

// PendingCount is how many requests wait for an admin.
func (s *Service) PendingCount(ctx context.Context) int64 {
	n, _ := s.q.CountPendingRequests(ctx)
	return n
}

func seasonList(raw string) []int {
	var out []int
	_ = json.Unmarshal([]byte(raw), &out)
	return out
}

func nonNilInts(v []int) []int {
	if v == nil {
		return []int{}
	}
	return v
}

func yearOf(date string) *int64 {
	if y := tmdb.Year(date); y > 0 {
		v := int64(y)
		return &v
	}
	return nil
}

func nilIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// IsRequestNotFound reports whether err means there is no such request.
func IsRequestNotFound(err error) bool {
	return errors.Is(err, errNotFound) || errors.Is(err, sql.ErrNoRows)
}
