package integrations

import (
	"cmp"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"golang.org/x/time/rate"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations/arr"
)

// WebhookPath is where an app's webhook calls, {id} the connection's.
const WebhookPath = "/api/integrations/{id}/webhook"

// WebhookUser is the basic-auth user name the webhook sends; the password is
// the connection's token.
const WebhookUser = "watchmymedia"

type limiter = rate.Limiter

// newLimiter allows a burst of events (a season imported at once) and a few a
// second after it.
func newLimiter() *limiter { return rate.NewLimiter(rate.Limit(5), 50) }

// ServeWebhook takes an event from an app's webhook: it scans the paths the
// event changed and keeps the index current. The connection's token is the
// basic-auth password.
func (s *Service) ServeWebhook(w http.ResponseWriter, r *http.Request, id int64) {
	s.mu.RLock()
	c := s.conns[id]
	s.mu.RUnlock()
	_, pass, ok := r.BasicAuth()
	if c == nil || c.arr == nil || !ok || subtle.ConstantTimeCompare([]byte(pass), []byte(c.row.WebhookToken)) != 1 {
		w.Header().Set("WWW-Authenticate", `Basic realm="WatchMyMedia"`)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if !c.limit.Allow() {
		w.Header().Set("Retry-After", "1")
		http.Error(w, "too many events", http.StatusTooManyRequests)
		return
	}
	var e arr.Event
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&e); err != nil {
		http.Error(w, "unreadable event", http.StatusBadRequest)
		return
	}
	s.handle(r.Context(), c, e)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Service) handle(ctx context.Context, c *conn, e arr.Event) {
	now := time.Now()
	s.mu.Lock()
	c.status.Webhook.LastEventAt = &now
	if e.EventType == "Test" {
		c.status.Webhook.LastTestAt = &now
	}
	// The event says where the item is; the matcher can use it before the
	// next read of the whole list.
	if c.idx != nil {
		if e.Movie != nil && e.Movie.ID > 0 && e.Movie.FolderPath != "" {
			m := c.idx.movies[e.Movie.ID]
			m.ID, m.Path = e.Movie.ID, e.Movie.FolderPath
			m.Title = cmp.Or(e.Movie.Title, m.Title)
			m.TMDBID = cmp.Or(e.Movie.TMDBID, m.TMDBID)
			c.idx.addMovie(c.maps, m)
		}
		if e.Series != nil && e.Series.ID > 0 && e.Series.Path != "" {
			sr := c.idx.series[e.Series.ID]
			sr.ID, sr.Path = e.Series.ID, e.Series.Path
			sr.Title = cmp.Or(e.Series.Title, sr.Title)
			sr.TMDBID = cmp.Or(e.Series.TMDBID, sr.TMDBID)
			sr.TVDBID = cmp.Or(e.Series.TVDBID, sr.TVDBID)
			c.idx.addSeries(c.maps, sr)
		}
	}
	maps, kind := c.maps, c.arr.Kind
	s.mu.Unlock()
	// What Seerr said about the item may have changed with it.
	if e.Movie != nil && e.Movie.TMDBID > 0 {
		s.dropSeerr("movie", e.Movie.TMDBID)
	}
	if e.Series != nil && e.Series.TMDBID > 0 {
		s.dropSeerr("tv", e.Series.TMDBID)
	}

	paths := e.Paths()
	if len(paths) > 0 {
		libs, err := s.q.ListLibraries(ctx)
		if err != nil {
			s.log.Warn("integration webhook: libraries", "err", err)
			return
		}
		byLib, outside := scanPaths(libs, kind, maps, paths)
		for libID, ps := range byLib {
			s.scan.RequestPaths(libID, ps)
		}
		if outside > 0 {
			s.mu.Lock()
			c.status.Webhook.Outside += outside
			s.mu.Unlock()
		}
		s.log.Info("integration webhook", "integration", c.row.Name, "event", e.EventType, "paths", len(paths), "outside", outside)
	} else {
		s.log.Debug("integration webhook", "integration", c.row.Name, "event", e.EventType)
	}
	switch e.EventType {
	case "Download", "Rename", "MovieAdded", "SeriesAdd", "MovieDelete", "SeriesDelete", "MovieFileDelete", "EpisodeFileDelete":
		s.RequestRefresh(c.row.ID)
	}
}

// scanPaths maps an event's paths to the server's and groups them by the
// library of the app's kind that holds them. Paths outside every such
// library are only counted: a webhook never makes the server read a path
// outside its libraries.
func scanPaths(libs []db.Library, kind arr.Kind, maps PathMap, paths []string) (map[int64][]string, int) {
	out := map[int64][]string{}
	outside := 0
	for _, p := range paths {
		local := maps.ToLocal(p)
		found := false
		for _, l := range libs {
			if kindFor(l.Type) == kind && inside(l.Path, local) {
				out[l.ID] = append(out[l.ID], local)
				found = true
				break
			}
		}
		if !found {
			outside++
		}
	}
	return out, outside
}

// HookURL is the webhook address for a connection, given the address the app
// reaches this server at.
func HookURL(serverAddress string, id int64) (string, error) {
	base, err := arr.BaseURL(serverAddress)
	if err != nil {
		return "", invalid("%s", err.Error())
	}
	return base + strings.Replace(WebhookPath, "{id}", strconv.FormatInt(id, 10), 1), nil
}

// ConnectWebhook creates (or updates) the webhook in the app, pointed at this
// server's address as the app reaches it. The app tests it before saving.
func (s *Service) ConnectWebhook(ctx context.Context, id int64, serverAddress string) error {
	s.mu.RLock()
	c := s.conns[id]
	s.mu.RUnlock()
	if c != nil && c.seerr != nil {
		return invalid("Seerr doesn't need a webhook")
	}
	if c == nil || c.arr == nil {
		return errNotFound
	}
	hook, err := HookURL(serverAddress, id)
	if err != nil {
		return err
	}
	return c.arr.ConnectWebhook(ctx, hook, WebhookUser, c.row.WebhookToken)
}

var errNotFound = errors.New("integration not found")

// IsNotFound reports whether err means there is no such connection.
func IsNotFound(err error) bool { return errors.Is(err, errNotFound) }

// RootFolder is one of an app's root folders, as the settings page shows it.
type RootFolder struct {
	Path      string `json:"path"`
	LocalPath string `json:"localPath" doc:"The folder as this server sees it, after the path mappings."`
	LibraryID int64  `json:"libraryId,omitempty" doc:"The library it is inside; absent when none."`
	Items     int    `json:"items" doc:"The app's movies or series in it."`
}

// Details is what the settings page needs to edit a connection, read from
// the app now.
type Details struct {
	RootFolders     []RootFolder         `json:"rootFolders" nullable:"false"`
	QualityProfiles []arr.QualityProfile `json:"qualityProfiles" nullable:"false"`
	Suggestions     []Mapping            `json:"suggestions" nullable:"false" doc:"Mappings that would put root folders outside every library inside one."`
	RecycleBin      string               `json:"recycleBin,omitempty" doc:"Where the app moves deleted files; empty when it deletes them for good."`
}

// Details reads an app's root folders, quality profiles and recycling bin,
// and suggests path mappings.
func (s *Service) Details(ctx context.Context, id int64) (Details, error) {
	s.mu.RLock()
	c := s.conns[id]
	var items []string
	if c != nil && c.idx != nil {
		for _, m := range c.idx.movies {
			items = append(items, m.Path)
		}
		for _, sr := range c.idx.series {
			items = append(items, sr.Path)
		}
	}
	s.mu.RUnlock()
	if c != nil && c.seerr != nil {
		// Seerr has no folders or profiles of its own to edit here.
		return Details{RootFolders: []RootFolder{}, QualityProfiles: []arr.QualityProfile{}, Suggestions: []Mapping{}}, nil
	}
	if c == nil || c.arr == nil {
		return Details{}, errNotFound
	}
	roots, err := c.arr.RootFolders(ctx)
	if err != nil {
		return Details{}, err
	}
	profiles, err := c.arr.QualityProfiles(ctx)
	if err != nil {
		return Details{}, err
	}
	mm, err := c.arr.MediaManagement(ctx)
	if err != nil {
		return Details{}, err
	}
	libs, err := s.q.ListLibraries(ctx)
	if err != nil {
		return Details{}, err
	}
	var libPaths []string
	for _, l := range libs {
		if kindFor(l.Type) == c.arr.Kind {
			libPaths = append(libPaths, l.Path)
		}
	}
	out := Details{RootFolders: []RootFolder{}, QualityProfiles: profiles, RecycleBin: mm.RecycleBin}
	if out.QualityProfiles == nil {
		out.QualityProfiles = []arr.QualityProfile{}
	}
	rootPaths := make([]string, 0, len(roots))
	for _, r := range roots {
		rf := RootFolder{Path: r.Path, LocalPath: c.maps.ToLocal(r.Path)}
		for _, l := range libs {
			if kindFor(l.Type) == c.arr.Kind && inside(l.Path, rf.LocalPath) {
				rf.LibraryID = l.ID
				break
			}
		}
		for _, it := range items {
			if under(slashed(r.Path), slashed(it)) {
				rf.Items++
			}
		}
		out.RootFolders = append(out.RootFolders, rf)
		rootPaths = append(rootPaths, r.Path)
	}
	out.Suggestions = suggest(rootPaths, libPaths, items, c.maps)
	if out.Suggestions == nil {
		out.Suggestions = []Mapping{}
	}
	return out, nil
}
