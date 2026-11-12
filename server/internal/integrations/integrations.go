// Package integrations connects the server to Radarr, Sonarr and Seerr
// (PLAN-ARR.md): the connections and their path mappings, an index of what
// each app has, and the apps' webhooks, which make the server scan what they
// changed at once.
package integrations

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"maps"
	"net/http"
	"path"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/integrations/apiclient"
	"watchmymedia/server/internal/integrations/arr"
	"watchmymedia/server/internal/integrations/seerr"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/memo"
	"watchmymedia/server/internal/tmdb"
)

// RefreshEvery is how often each app's whole list is read again; webhooks and
// the server's own actions refresh it sooner.
const RefreshEvery = 15 * time.Minute

// Scanner is the part of the scanner the webhooks use.
type Scanner interface {
	RequestPaths(libraryID int64, paths []string)
}

// Target is where requests for a library go in an app (§7.2).
type Target struct {
	LibraryID        int64  `json:"libraryId"`
	RootFolder       string `json:"rootFolder" maxLength:"1024" doc:"One of the app's root folders, as the app sees it."`
	QualityProfileID int    `json:"qualityProfileId"`
}

// Connection is a connection as the settings page shows it. The API key is
// never sent back.
type Connection struct {
	ID         int64    `json:"id"`
	Kind       string   `json:"kind" enum:"radarr,sonarr,seerr"`
	Name       string   `json:"name"`
	URL        string   `json:"url"`
	APIKeyHint string   `json:"apiKeyHint" doc:"Last four characters of the API key."`
	PathMap    PathMap  `json:"pathMap" nullable:"false"`
	Targets    []Target `json:"targets" nullable:"false"`
	Status     Status   `json:"status"`
}

type Status struct {
	OK        bool       `json:"ok" doc:"The last call to the app worked."`
	AppName   string     `json:"appName,omitempty"`
	Version   string     `json:"version,omitempty"`
	Error     string     `json:"error,omitempty" doc:"Why the last call failed."`
	CheckedAt *time.Time `json:"checkedAt,omitempty"`
	Items     int        `json:"items" doc:"Movies or series the app has; for Seerr, its users."`
	Webhook   Webhook    `json:"webhook"`
}

// Webhook is what has arrived from the app's webhook since the server
// started.
type Webhook struct {
	LastEventAt *time.Time `json:"lastEventAt,omitempty"`
	LastTestAt  *time.Time `json:"lastTestAt,omitempty"`
	Outside     int        `json:"outside" doc:"Events for folders outside every library: the path mappings need a look."`
}

type Service struct {
	q    *db.Queries
	log  *slog.Logger
	scan Scanner
	hc   *http.Client

	mu    sync.RWMutex
	conns map[int64]*conn

	refresh chan int64
	ctx     context.Context

	// tmdb and bus come from Configure.
	tmdb *tmdb.Client
	bus  *events.Bus

	// flight shares one call to an app among the pages asking the same at
	// once.
	flight memo.Group

	// titles are what Seerr's requests show of items, by seerrKey.
	titles *memo.Map[string, itemInfo]
}

type conn struct {
	row     db.Integration
	maps    PathMap
	targets []Target
	arr     *arr.Client
	limit   *limiter
	status  Status
	idx     *index

	queue    cached[[]arr.QueueItem]
	episodes map[int]cached[[]arr.Episode]
	// down is why the last check of the app failed, and nudged when a page
	// last asked for it to be checked again (see reachable).
	down   error
	nudged time.Time

	// seerr is set for the Seerr connection instead of arr, with its users,
	// the address people open it at, and its records of items by seerrKey.
	seerr      *seerr.Client
	seerrUsers []seerr.User
	seerrURL   string
	seerrMedia *memo.Map[string, cached[seerr.Media]]
}

// index is what one Radarr or Sonarr has, keyed for the lookups the server
// makes: by the item's folder as the server sees it, and by TMDB ID.
type index struct {
	at       time.Time
	movies   map[int]arr.Movie
	series   map[int]arr.Series
	byFolder map[string]int
	byTMDB   map[int][]int
}

func New(q *db.Queries, scan Scanner, log *slog.Logger) *Service {
	// The apps' keys travel in headers that Go keeps on a redirect to
	// another host: the client follows redirects on the same host only.
	return &Service{q: q, scan: scan, log: log, hc: apiclient.HTTPClient(apiclient.Timeout),
		conns: map[int64]*conn{}, refresh: make(chan int64, 16), ctx: context.Background(),
		titles: memo.NewMap[string, itemInfo](0, 5000)}
}

// Load reads the connections from the database.
func (s *Service) Load(ctx context.Context) error {
	rows, err := s.q.ListIntegrations(ctx)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range rows {
		c, err := s.newConn(r)
		if err != nil {
			s.log.Warn("integration not loaded", "integration", r.Name, "err", err)
			continue
		}
		s.conns[r.ID] = c
	}
	return nil
}

func (s *Service) newConn(r db.Integration) (*conn, error) {
	c := &conn{row: r, limit: newLimiter()}
	if err := json.Unmarshal([]byte(r.PathMap), &c.maps); err != nil {
		return nil, fmt.Errorf("path map: %w", err)
	}
	if err := json.Unmarshal([]byte(r.Targets), &c.targets); err != nil {
		return nil, fmt.Errorf("targets: %w", err)
	}
	switch arr.Kind(r.Kind) {
	case arr.Radarr, arr.Sonarr:
		cl, err := arr.New(arr.Kind(r.Kind), r.Url, r.ApiKey, s.hc)
		if err != nil {
			return nil, err
		}
		c.arr = cl
	case "seerr":
		c.seerr = seerr.New(r.Url, r.ApiKey, s.hc)
		c.seerrMedia = memo.NewMap[string, cached[seerr.Media]](seerrFresh, 2000)
	}
	return c, nil
}

// Run refreshes every app's index now, every RefreshEvery, and when asked,
// until ctx ends.
func (s *Service) Run(ctx context.Context) {
	s.mu.Lock()
	s.ctx = ctx
	s.mu.Unlock()
	s.refreshAll(ctx)
	t := time.NewTicker(RefreshEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.refreshAll(ctx)
		case id := <-s.refresh:
			// Events come in bursts (a season imported); read the list once.
			ids := map[int64]bool{id: true}
			wait := time.After(3 * time.Second)
		gather:
			for {
				select {
				case id := <-s.refresh:
					ids[id] = true
				case <-wait:
					break gather
				case <-ctx.Done():
					return
				}
			}
			s.refreshEach(ctx, slices.Collect(maps.Keys(ids)))
		}
	}
}

// RequestRefresh reads an app's list again soon.
func (s *Service) RequestRefresh(id int64) {
	select {
	case s.refresh <- id:
	default:
	}
}

func (s *Service) refreshAll(ctx context.Context) {
	s.mu.RLock()
	ids := slices.Collect(maps.Keys(s.conns))
	s.mu.RUnlock()
	s.refreshEach(ctx, ids)
}

// refreshEach refreshes the apps at once, so one that doesn't answer holds
// up none of the others.
func (s *Service) refreshEach(ctx context.Context, ids []int64) {
	var wg sync.WaitGroup
	for _, id := range ids {
		wg.Go(func() { s.Refresh(ctx, id) })
	}
	wg.Wait()
}

// context is the service's: Run's, once it runs.
func (s *Service) context() context.Context {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.ctx
}

// Refresh checks an app and reads its whole movie or series list.
func (s *Service) Refresh(ctx context.Context, id int64) {
	s.mu.RLock()
	c := s.conns[id]
	s.mu.RUnlock()
	if c != nil && c.seerr != nil {
		s.refreshSeerr(ctx, c)
		return
	}
	if c == nil || c.arr == nil {
		return
	}
	st, err := c.arr.Status(ctx)
	var idx *index
	if err == nil {
		idx, err = s.read(ctx, c)
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	c.status.CheckedAt = &now
	c.status.OK = err == nil
	c.down = err
	if err != nil {
		c.status.Error = err.Error()
		s.log.Warn("integration unreachable", "integration", c.row.Name, "err", err)
		return
	}
	c.status.Error = ""
	c.status.AppName, c.status.Version = st.AppName, st.Version
	c.idx = idx
	c.status.Items = len(idx.movies) + len(idx.series)
}

func (s *Service) read(ctx context.Context, c *conn) (*index, error) {
	idx := &index{at: time.Now(), movies: map[int]arr.Movie{}, series: map[int]arr.Series{},
		byFolder: map[string]int{}, byTMDB: map[int][]int{}}
	switch c.arr.Kind {
	case arr.Radarr:
		movies, err := c.arr.Movies(ctx)
		if err != nil {
			return nil, err
		}
		for _, m := range movies {
			idx.addMovie(c.maps, m)
		}
	case arr.Sonarr:
		series, err := c.arr.Series(ctx)
		if err != nil {
			return nil, err
		}
		for _, sr := range series {
			idx.addSeries(c.maps, sr)
		}
	}
	return idx, nil
}

func (idx *index) addMovie(pm PathMap, m arr.Movie) {
	idx.movies[m.ID] = m
	idx.link(pm, m.ID, m.Path, m.TMDBID)
}

func (idx *index) addSeries(pm PathMap, sr arr.Series) {
	idx.series[sr.ID] = sr
	idx.link(pm, sr.ID, sr.Path, sr.TMDBID)
}

func (idx *index) link(pm PathMap, id int, appPath string, tmdbID int) {
	if appPath != "" {
		idx.byFolder[localKey(pm.ToLocal(appPath))] = id
	}
	if tmdbID > 0 && !slices.Contains(idx.byTMDB[tmdbID], id) {
		idx.byTMDB[tmdbID] = append(idx.byTMDB[tmdbID], id)
	}
}

// kindFor is the app that manages a library type.
func kindFor(libraryType string) arr.Kind {
	if libraryType == library.TypeMovie {
		return arr.Radarr
	}
	return arr.Sonarr
}

// Hint returns the TMDB (or, for a Sonarr series without one, the TVDB) ID of
// the movie or series whose folder holds the file, for the matcher. Zero
// when no app has the folder.
func (s *Service) Hint(lib db.Library, filePath string) (tmdbID, tvdbID int) {
	kind := kindFor(lib.Type)
	root := localKey(lib.Path)
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.conns {
		if c.arr == nil || c.arr.Kind != kind || c.idx == nil {
			continue
		}
		for k := localKey(filepath.Dir(filePath)); under(root, k) && k != root; k = path.Dir(k) {
			id, ok := c.idx.byFolder[k]
			if !ok {
				continue
			}
			if m, ok := c.idx.movies[id]; ok {
				return m.TMDBID, 0
			}
			if sr, ok := c.idx.series[id]; ok {
				return sr.TMDBID, sr.TVDBID
			}
		}
	}
	return 0, 0
}

// List returns the connections, in the order the settings page shows them.
func (s *Service) List() []Connection {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Connection, 0, len(s.conns))
	for _, c := range s.conns {
		out = append(out, c.view())
	}
	slices.SortFunc(out, func(a, b Connection) int {
		if a.Kind != b.Kind {
			return kindOrder(a.Kind) - kindOrder(b.Kind)
		}
		if a.Name != b.Name {
			if a.Name < b.Name {
				return -1
			}
			return 1
		}
		return int(a.ID - b.ID)
	})
	return out
}

func kindOrder(k string) int {
	return slices.Index([]string{"radarr", "sonarr", "seerr"}, k)
}

// Get returns one connection.
func (s *Service) Get(id int64) (Connection, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	c, ok := s.conns[id]
	if !ok {
		return Connection{}, false
	}
	return c.view(), true
}

func (c *conn) view() Connection {
	// Seerr's keys are base64 and end in padding, which tells nothing apart.
	hint := strings.TrimRight(c.row.ApiKey, "=")
	if len(hint) > 4 {
		hint = hint[len(hint)-4:]
	}
	maps, targets := c.maps, c.targets
	if maps == nil {
		maps = PathMap{}
	}
	if targets == nil {
		targets = []Target{}
	}
	return Connection{ID: c.row.ID, Kind: c.row.Kind, Name: c.row.Name, URL: c.row.Url, APIKeyHint: hint,
		PathMap: maps, Targets: targets, Status: c.status}
}

// Input is a new connection, or the changes to one (nil fields keep theirs).
type Input struct {
	Kind    string
	Name    *string
	URL     *string
	APIKey  *string
	PathMap *PathMap
	Targets *[]Target
}

// ErrInvalid wraps what is wrong with an input, for a 422.
type ErrInvalid struct{ Reason string }

func (e *ErrInvalid) Error() string { return e.Reason }

func invalid(format string, args ...any) error {
	return &ErrInvalid{Reason: fmt.Sprintf(format, args...)}
}

// Check asks the app at address for its status with key.
func (s *Service) Check(ctx context.Context, kind, address, key string) (arr.SystemStatus, error) {
	if kind == "seerr" {
		return s.checkSeerr(ctx, address, key)
	}
	cl, err := arr.New(arr.Kind(kind), address, key, s.hc)
	if err != nil {
		return arr.SystemStatus{}, invalid("%s", err.Error())
	}
	st, err := cl.Status(ctx)
	if err != nil {
		return st, err
	}
	if err := supported(arr.Kind(kind), st.Version); err != nil {
		return st, err
	}
	return st, nil
}

// Create checks the app, then saves the connection.
func (s *Service) Create(ctx context.Context, in Input) (Connection, error) {
	if in.Name == nil || in.URL == nil || in.APIKey == nil {
		return Connection{}, invalid("a name, address and API key are needed")
	}
	base, err := arr.BaseURL(*in.URL)
	if err != nil {
		return Connection{}, invalid("%s", err.Error())
	}
	if in.Kind == "seerr" {
		if s.Seerr() {
			return Connection{}, invalid("Seerr is connected already; change that connection instead")
		}
		// Seerr sees no files and takes requests itself.
		in.PathMap, in.Targets = nil, nil
	}
	st, err := s.Check(ctx, in.Kind, base, *in.APIKey)
	if err != nil {
		return Connection{}, err
	}
	maps, targets := PathMap{}, []Target{}
	if in.PathMap != nil {
		maps = *in.PathMap
	}
	if in.Targets != nil {
		targets = *in.Targets
	}
	if err := validate(maps, targets); err != nil {
		return Connection{}, err
	}
	mj, _ := json.Marshal(maps)
	tj, _ := json.Marshal(targets)
	row, err := s.q.CreateIntegration(ctx, db.CreateIntegrationParams{Kind: in.Kind, Name: *in.Name, Url: base,
		ApiKey: *in.APIKey, WebhookToken: newToken(), PathMap: string(mj), Targets: string(tj)})
	if err != nil {
		return Connection{}, err
	}
	c, err := s.newConn(row)
	if err != nil {
		return Connection{}, err
	}
	now := time.Now()
	c.status = Status{OK: true, AppName: st.AppName, Version: st.Version, CheckedAt: &now}
	s.mu.Lock()
	s.conns[row.ID] = c
	s.mu.Unlock()
	s.Refresh(ctx, row.ID)
	v, _ := s.Get(row.ID)
	return v, nil
}

// Update changes a connection. A new address or key is checked first.
func (s *Service) Update(ctx context.Context, id int64, in Input) (Connection, error) {
	row, err := s.q.GetIntegration(ctx, id)
	if err != nil {
		return Connection{}, err
	}
	p := db.UpdateIntegrationParams{ID: id, Name: row.Name, Url: row.Url, ApiKey: row.ApiKey, PathMap: row.PathMap, Targets: row.Targets}
	if in.Name != nil {
		p.Name = *in.Name
	}
	if in.URL != nil {
		if p.Url, err = arr.BaseURL(*in.URL); err != nil {
			return Connection{}, invalid("%s", err.Error())
		}
	}
	if in.APIKey != nil && *in.APIKey != "" {
		p.ApiKey = *in.APIKey
	}
	if p.Url != row.Url || p.ApiKey != row.ApiKey {
		if _, err := s.Check(ctx, row.Kind, p.Url, p.ApiKey); err != nil {
			return Connection{}, err
		}
	}
	var maps PathMap
	var targets []Target
	_ = json.Unmarshal([]byte(row.PathMap), &maps)
	_ = json.Unmarshal([]byte(row.Targets), &targets)
	if in.PathMap != nil {
		maps = *in.PathMap
	}
	if in.Targets != nil {
		targets = *in.Targets
	}
	if err := validate(maps, targets); err != nil {
		return Connection{}, err
	}
	mj, _ := json.Marshal(maps)
	tj, _ := json.Marshal(targets)
	p.PathMap, p.Targets = string(mj), string(tj)
	updated, err := s.q.UpdateIntegration(ctx, p)
	if err != nil {
		return Connection{}, err
	}
	c, err := s.newConn(updated)
	if err != nil {
		return Connection{}, err
	}
	s.mu.Lock()
	if old := s.conns[id]; old != nil {
		c.status, c.limit, c.down = old.status, old.limit, old.down
	}
	s.conns[id] = c
	s.mu.Unlock()
	s.Refresh(ctx, id)
	v, _ := s.Get(id)
	return v, nil
}

func validate(maps PathMap, targets []Target) error {
	for _, m := range maps {
		if m.From == "" || m.To == "" {
			return invalid("each path mapping needs both folders")
		}
		if !filepath.IsAbs(m.To) {
			return invalid("%q is not a full path on this server", m.To)
		}
	}
	seen := map[int64]bool{}
	for _, t := range targets {
		if seen[t.LibraryID] {
			return invalid("a library can have only one target per connection")
		}
		seen[t.LibraryID] = true
		if t.RootFolder == "" || t.QualityProfileID <= 0 {
			return invalid("each target needs a root folder and a quality profile")
		}
	}
	return nil
}

// Delete forgets a connection. The webhook it made in the app stays and is
// refused from now on.
func (s *Service) Delete(ctx context.Context, id int64) error {
	if err := s.q.DeleteIntegration(ctx, id); err != nil {
		return err
	}
	s.mu.Lock()
	delete(s.conns, id)
	s.mu.Unlock()
	return nil
}

// supported refuses versions older than PLAN-ARR.md §1 supports.
func supported(kind arr.Kind, version string) error {
	major, minor, patch := versionParts(version)
	switch kind {
	case arr.Radarr:
		if major < 5 {
			return invalid("Radarr %s is too old: WatchMyMedia needs Radarr 5 or later", version)
		}
	case arr.Sonarr:
		if major < 4 || (major == 4 && minor == 0 && patch < 6) {
			return invalid("Sonarr %s is too old: WatchMyMedia needs Sonarr 4.0.6 or later", version)
		}
	}
	return nil
}

func versionParts(v string) (major, minor, patch int) {
	_, _ = fmt.Sscanf(v, "%d.%d.%d", &major, &minor, &patch)
	return major, minor, patch
}

func newToken() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// IsInvalid reports whether err is an input the caller can fix.
func IsInvalid(err error) (string, bool) {
	if e, ok := errors.AsType[*ErrInvalid](err); ok {
		return e.Reason, true
	}
	return "", false
}
