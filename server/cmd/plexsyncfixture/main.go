// plexsyncfixture serves a disposable WatchMyMedia server and fictional Plex
// services for browser/client acceptance. It never contacts plex.tv or a real
// media server. Its media rows exercise browsing and watch state, not playback.
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"slices"
	"strconv"
	"sync"
	"syscall"
	"time"

	"watchmymedia/server/internal/activity"
	"watchmymedia/server/internal/api"
	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/plex/plextest"
	"watchmymedia/server/internal/plexsync"
	"watchmymedia/server/internal/settings"
	"watchmymedia/server/internal/tmdb"
	"watchmymedia/server/internal/watchstate"
	"watchmymedia/server/internal/webui"
)

const fixturePassword = "fixture-password"

func main() {
	port := flag.Int("port", 28374, "Loopback HTTP port; 0 chooses an unused port")
	flag.Parse()
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if err := run(ctx, *port); err != nil {
		slog.Error("Plex sync fixture", "error", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, port int) error {
	f, err := newFixture(ctx)
	if err != nil {
		return err
	}
	defer f.close()
	listener, err := (&net.ListenConfig{}).Listen(ctx, "tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)))
	if err != nil {
		return err
	}
	server := &http.Server{Handler: f.handler, ReadHeaderTimeout: 5 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }}
	done := make(chan error, 1)
	go func() { done <- server.Serve(listener) }()
	slog.Info("fictional Plex sync fixture ready", "url", "http://"+listener.Addr().String(),
		"admin", "Fixture Admin", "viewer", "Fixture Viewer", "password", fixturePassword,
		"connection", f.connection.ID, "data", f.dir)
	select {
	case err := <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		shutdown, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		return server.Shutdown(shutdown)
	}
}

type fixture struct {
	dir        string
	conn       *sql.DB
	q          *db.Queries
	auth       *auth.Service
	service    *plexsync.Service
	connection plexsync.PlexConnection
	serverID   string
	fake       *plextest.Fake
	reporter   *reporter
	handler    http.Handler
	bus        *events.Bus
	admin      int64
	viewer     int64
	movies     []int64
	show       int64
	episodes   []int64
	cancel     context.CancelFunc
	workers    sync.WaitGroup
}

func newFixture(parent context.Context) (_ *fixture, err error) {
	ctx, cancel := context.WithCancel(parent)
	f := &fixture{cancel: cancel, bus: events.NewBus(), reporter: &reporter{}}
	defer func() {
		if err != nil {
			f.close()
		}
	}()
	f.dir, err = os.MkdirTemp("", "wmm-plex-sync-fixture-")
	if err != nil {
		return nil, err
	}
	f.conn, err = db.Open(filepath.Join(f.dir, "fixture.db"))
	if err != nil {
		return nil, err
	}
	if err = db.Migrate(ctx, f.conn); err != nil {
		return nil, err
	}
	f.q = db.New(f.conn)
	store := settings.New(f.q)
	if err = store.Set(ctx, settings.KeyServerName, "Meadow Plex import fixture"); err != nil {
		return nil, err
	}
	f.serverID, err = store.ServerID(ctx)
	if err != nil {
		return nil, err
	}
	f.auth = auth.New(f.q, time.Hour, false)
	if err = f.auth.SetMediaKey(auth.NewMediaKey()); err != nil {
		return nil, err
	}
	admin, err := f.auth.CreateUser(ctx, "Fixture Admin", fixturePassword, true)
	if err != nil {
		return nil, err
	}
	viewer, err := f.auth.CreateUser(ctx, "Fixture Viewer", fixturePassword, false)
	if err != nil {
		return nil, err
	}
	f.admin, f.viewer = admin.ID, viewer.ID
	watch := watchstate.New(f.q, f.bus)
	f.fake = plextest.New(f.reporter)
	if err = f.seed(ctx, watch); err != nil {
		return nil, err
	}
	f.service, err = plexsync.New(ctx, f.q, watch, f.dir, plexsync.Options{
		ClientsOrigin: f.fake.Cloud.URL, AccountOrigin: f.fake.Cloud.URL,
		CloudHTTP: f.fake.Cloud.Client(), ServerHTTP: f.fake.PMS.Client(), Log: slog.Default(), Bus: f.bus,
	})
	if err != nil {
		return nil, err
	}
	who := plexsync.Requester{UserID: f.admin, Session: "fictional-initial-connection"}
	signIn, err := f.service.StartSignIn(ctx, who)
	if err != nil {
		return nil, err
	}
	f.fake.Set(func(p *plextest.Fake) { p.SignedIn = true })
	if _, err = f.service.SignInStatus(ctx, who, signIn.ID); err != nil {
		return nil, err
	}
	f.connection, err = f.service.Connect(ctx, who, plexsync.PlexConnect{SignInID: signIn.ID, MachineIdentifier: f.fake.Machine, Origin: f.fake.PMS.URL})
	if err != nil {
		return nil, err
	}
	libs, err := f.q.ListLibraries(ctx)
	if err != nil {
		return nil, err
	}
	mappings := plexsync.PlexLinks{Generation: f.connection.Generation,
		Users: []plexsync.PlexUserLink{{PlexUserID: 701, UserID: f.admin}, {PlexUserID: 706, UserID: f.viewer}}}
	for _, library := range libs {
		section := int64(1)
		if library.Type == "tv" {
			section = 2
		}
		mappings.Libraries = append(mappings.Libraries, plexsync.PlexLibraryLink{SectionID: section, LibraryID: library.ID})
	}
	f.connection, err = f.service.SaveLinks(ctx, f.connection.ID, mappings)
	if err != nil {
		return nil, err
	}
	tracker := activity.New(f.conn, f.bus, slog.Default(), activity.Options{Config: api.ActivityConfig(ctx, store)})
	router, _ := api.New(api.Deps{Version: "plex-sync-fixture", Queries: f.q, Conn: f.conn, DataDir: f.dir,
		Log: slog.Default(), Bus: f.bus, Auth: f.auth, Settings: store, TMDB: tmdb.New("", ""),
		Activity: tracker, Watch: watch, Plex: f.service, DefaultHWAccel: "none"})
	// Fixture identity and exact IDs let native opt-in probes prove which
	// disposable server they reached. This route exists only in this command.
	router.Get("/_fixture/status", func(w http.ResponseWriter, r *http.Request) {
		runs, err := f.service.Runs(r.Context(), f.connection.ID)
		if err != nil {
			http.Error(w, "could not read fixture runs", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"fixture": "plex-sync-fixture-v1", "version": "plex-sync-fixture", "serverName": "Meadow Plex import fixture", "serverId": f.serverID,
			"admin":        map[string]any{"name": "Fixture Admin", "userId": f.admin},
			"viewer":       map[string]any{"name": "Fixture Viewer", "userId": f.viewer},
			"connectionId": f.connection.ID, "movieIds": f.movies, "showId": f.show, "episodeIds": f.episodes, "runs": runs,
		})
	})
	router.Handle("/*", webui.Handler())
	f.handler = router
	f.workers.Go(func() { f.service.Run(ctx) })
	return f, nil
}

func (f *fixture) close() {
	f.cancel()
	f.workers.Wait()
	for _, cleanup := range slices.Backward(f.reporter.cleanups) {
		cleanup()
	}
	if f.conn != nil {
		_ = f.conn.Close()
	}
	if f.dir != "" {
		_ = os.RemoveAll(f.dir)
	}
}

func (f *fixture) seed(ctx context.Context, watch *watchstate.Service) error {
	movieDir, tvDir := filepath.Join(f.dir, "Movies"), filepath.Join(f.dir, "TV")
	movies, err := f.q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: movieDir})
	if err != nil {
		return err
	}
	tv, err := f.q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV Shows", Path: tvDir})
	if err != nil {
		return err
	}
	for index, title := range []string{"Harbor Lights", "Copper Sky", "Quiet Harbor"} {
		movie, err := f.q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: movies.ID, TmdbID: 810001 + int64(index), Title: title, Overview: "Fictional fixture film.", Genres: "[]", Credits: "[]", Trailers: "[]"})
		if err != nil {
			return err
		}
		f.movies = append(f.movies, movie.ID)
		if _, err := f.mediaFile(ctx, movies.ID, filepath.Join(movieDir, title+".mp4"), &movie.ID); err != nil {
			return err
		}
	}
	show, err := f.q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 820001, Title: "Signal Keepers", Overview: "Fictional fixture series.", Genres: "[]", Credits: "[]"})
	if err != nil {
		return err
	}
	f.show = show.ID
	season, err := f.q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, EpisodeCount: 3})
	if err != nil {
		return err
	}
	for index, title := range []string{"Beacon", "Crossing", "Homeward"} {
		ep, err := f.q.UpsertEpisode(ctx, db.UpsertEpisodeParams{ShowID: show.ID, SeasonID: season.ID, Number: int64(index + 1), Title: title})
		if err != nil {
			return err
		}
		f.episodes = append(f.episodes, ep.ID)
		file, err := f.mediaFile(ctx, tv.ID, filepath.Join(tvDir, "Signal Keepers", "Season 1", fmt.Sprintf("S01E%02d.mp4", index+1)), nil)
		if err != nil {
			return err
		}
		if err := f.q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: file}); err != nil {
			return err
		}
	}
	// Local unwatch is deliberately stronger than Plex's watched mark.
	if err := watch.MarkUnwatched(ctx, f.admin, watchstate.Movie, f.movies[2]); err != nil {
		return err
	}
	f.fake.SetItems("owner", "1", plextest.MovieJSON("101", "Harbor Lights", "810001", "", 1), plextest.MovieJSON("103", "Quiet Harbor", "810003", "", 1))
	f.fake.SetItems("synthetic-server-706", "1", plextest.MovieJSON("102", "Copper Sky", "810002", "", 1))
	f.fake.SetItems("owner", "2", plextest.EpisodeJSON("201", "Beacon", "200", 1, 1, 1))
	f.fake.SetItems("synthetic-server-706", "2", plextest.EpisodeJSON("202", "Crossing", "200", 1, 2, 1))
	f.fake.Set(func(p *plextest.Fake) {
		p.Shows["200"] = `{"ratingKey":"200","type":"show","title":"Signal Keepers","episodeOrdering":"tmdbAiring","Guid":[{"id":"tmdb://820001"}]}`
		at := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
		owner := plex.HistoryEvent{HistoryKey: "/status/sessions/history/1001", AccountID: 1, ViewedAt: at,
			Item: plex.Item{RatingKey: "101", Type: "movie", LibrarySectionID: "1", Title: "Harbor Lights", ExternalIDs: plex.ExternalIDs{TMDB: "810001"}}}
		viewer := plex.HistoryEvent{HistoryKey: "/status/sessions/history/1002", AccountID: 706, ViewedAt: at.Add(time.Minute),
			Item: plex.Item{RatingKey: "102", Type: "movie", LibrarySectionID: "1", Title: "Copper Sky", ExternalIDs: plex.ExternalIDs{TMDB: "810002"}}}
		p.HistoryEvents["owner"] = map[string][]plex.HistoryEvent{"1": {owner, viewer}}
		p.HistoryEvents["synthetic-server-706"] = map[string][]plex.HistoryEvent{"1": {viewer}}
	})
	return nil
}

func (f *fixture) mediaFile(ctx context.Context, library int64, path string, movie *int64) (int64, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return 0, err
	}
	data := []byte("Fictional metadata fixture; not a playback sample.\n")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return 0, err
	}
	probe := `{"container":"mp4","durationMs":10000,"video":[{"codec":"h264","width":1280,"height":720}],"audio":[],"subtitles":[]}`
	file, err := f.q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: library, Path: path, Size: int64(len(data)),
		Mtime: db.FormatTime(time.Now()), AddedAt: db.FormatTime(time.Now()), LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1, Parsed: "{}", Probe: &probe})
	if err != nil {
		return 0, err
	}
	if err := f.q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{ID: file.ID, MatchStatus: "matched", MovieID: movie}); err != nil {
		return 0, err
	}
	return file.ID, nil
}

type reporter struct {
	cleanups []func()
	mu       sync.Mutex
	errors   []string
}

func (*reporter) Helper()                        {}
func (r *reporter) Cleanup(f func())             { r.cleanups = append(r.cleanups, f) }
func (r *reporter) Error(args ...any)            { r.record(fmt.Sprint(args...)) }
func (r *reporter) Errorf(s string, args ...any) { r.record(fmt.Sprintf(s, args...)) }
func (r *reporter) record(message string) {
	r.mu.Lock()
	r.errors = append(r.errors, message)
	r.mu.Unlock()
	slog.Error("fictional Plex protocol mismatch", "reason", message)
}
