package plexsync

import (
	"bytes"
	"database/sql"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/plex/plextest"
	"watchmymedia/server/internal/watchstate"
)

// env is a service on a migrated test database, a fake Plex, and a log
// that tests search for secrets.
type env struct {
	t       *testing.T
	conn    *sql.DB
	f       *plextest.Fake
	svc     *Service
	q       *db.Queries
	watch   *watchstate.Service
	dataDir string
	logs    *bytes.Buffer
	admin   int64
	viewer  int64
	who     Requester
	movies  int64
	others  int64
	tv      int64
	ids     map[string]int64
}

func newEnv(t *testing.T) *env {
	t.Helper()
	conn, q := dbtest.Open(t)
	e := &env{t: t, conn: conn, f: plextest.New(t), q: q, watch: watchstate.New(q, events.NewBus()), dataDir: t.TempDir(), logs: &bytes.Buffer{}, ids: map[string]int64{}}
	ctx := t.Context()
	admin, err := q.CreateUser(ctx, db.CreateUserParams{Name: "admin", PasswordHash: "x", IsAdmin: 1})
	if err != nil {
		t.Fatal(err)
	}
	// Named like Plex's 702 and 704, to show names link no one.
	viewer, err := q.CreateUser(ctx, db.CreateUserParams{Name: "Fictional Viewer", PasswordHash: "x"})
	if err != nil {
		t.Fatal(err)
	}
	e.admin, e.viewer = admin.ID, viewer.ID
	e.who = Requester{UserID: admin.ID, Session: "session-a"}
	lib := func(typ, name string) int64 {
		l, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: typ, Name: name, Path: "/" + name})
		if err != nil {
			t.Fatal(err)
		}
		return l.ID
	}
	e.movies, e.others, e.tv = lib("movie", "movies"), lib("movie", "others"), lib("tv", "tv")
	movie := func(name string, library, tmdb int64, imdb string) {
		var imdbID *string
		if imdb != "" {
			imdbID = &imdb
		}
		m, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: library, TmdbID: tmdb, ImdbID: imdbID, Title: name, Genres: "[]", Credits: "[]"})
		if err != nil {
			t.Fatal(err)
		}
		e.ids[name] = m.ID
	}
	movie("beacon", e.movies, 810001, "tt0810001")
	movie("copper", e.movies, 810003, "")
	movie("dunes", e.movies, 810004, "tt0810004")
	movie("harbor", e.movies, 810005, "tt0810005")
	// Only in the other library: a Plex item with this TMDB ID in the
	// linked section must not match it.
	movie("elsewhere", e.others, 810002, "")
	sh, err := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: e.tv, TmdbID: 820001, Title: "Signal Keepers", Genres: "[]", Credits: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range []int64{0, 1} {
		se, err := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: sh.ID, Number: s})
		if err != nil {
			t.Fatal(err)
		}
		for _, n := range []int64{1, 2} {
			ep, err := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: se.ID, ShowID: sh.ID, Number: n})
			if err != nil {
				t.Fatal(err)
			}
			e.ids[fmt.Sprintf("s%de%d", s, n)] = ep.ID
		}
	}
	e.svc = e.open()
	return e
}

// open starts a service on the env's database and data folder, as a
// server start does.
func (e *env) open() *Service {
	e.t.Helper()
	svc, err := New(e.t.Context(), e.q, e.watch, e.dataDir, Options{ClientsOrigin: e.f.Cloud.URL, AccountOrigin: e.f.Cloud.URL,
		CloudHTTP: e.f.Cloud.Client(), ServerHTTP: e.f.PMS.Client(), Log: slog.New(slog.NewTextHandler(e.logs, &slog.HandlerOptions{Level: slog.LevelDebug}))})
	if err != nil {
		e.t.Fatal(err)
	}
	return svc
}

// connect signs in and connects the fake server.
func (e *env) connect() PlexConnection {
	e.t.Helper()
	ctx := e.t.Context()
	in, err := e.svc.StartSignIn(ctx, e.who)
	if err != nil {
		e.t.Fatal(err)
	}
	e.f.Set(func(f *plextest.Fake) { f.SignedIn = true })
	if _, err := e.svc.SignInStatus(ctx, e.who, in.ID); err != nil {
		e.t.Fatal(err)
	}
	c, err := e.svc.Connect(ctx, e.who, PlexConnect{SignInID: in.ID, MachineIdentifier: e.f.Machine, Origin: e.f.PMS.URL})
	if err != nil {
		e.t.Fatal(err)
	}
	return c
}

func (e *env) link(c PlexConnection, users []PlexUserLink, libs []PlexLibraryLink) PlexConnection {
	e.t.Helper()
	got, err := e.svc.SaveLinks(e.t.Context(), c.ID, PlexLinks{Generation: c.Generation, Users: users, Libraries: libs})
	if err != nil {
		e.t.Fatal(err)
	}
	return got
}

// waitPreview waits for the connection's preview to finish and reads it.
func (e *env) waitPreview(c PlexConnection, id string) PlexPreview {
	e.t.Helper()
	e.svc.mu.Lock()
	p := e.svc.previews[c.ID]
	e.svc.mu.Unlock()
	if p == nil {
		e.t.Fatal("no preview")
	}
	select {
	case <-p.done:
	case <-time.After(10 * time.Second):
		e.t.Fatal("preview did not finish")
	}
	got, err := e.svc.GetPreview(e.t.Context(), c.ID, id)
	if err != nil {
		e.t.Fatal(err)
	}
	return got
}

// grantFiles lists the files in the grant folder.
func (e *env) grantFiles() []string {
	e.t.Helper()
	entries, err := os.ReadDir(filepath.Join(e.dataDir, CredentialsDir))
	if err != nil {
		e.t.Fatal(err)
	}
	var out []string
	for _, x := range entries {
		out = append(out, x.Name())
	}
	return out
}

// grantOnDisk reads the one grant in the store, before anything removes it.
func (e *env) grantOnDisk() grant {
	e.t.Helper()
	refs, err := e.q.ListPlexCredentialRefs(e.t.Context())
	if err != nil || len(refs) != 1 || refs[0] == nil {
		e.t.Fatalf("grant references = %v, %v", refs, err)
	}
	g, err := e.svc.store.get(*refs[0])
	if err != nil {
		e.t.Fatal(err)
	}
	return g
}
