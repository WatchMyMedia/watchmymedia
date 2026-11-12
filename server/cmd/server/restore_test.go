package main

import (
	"archive/tar"
	"compress/gzip"
	"database/sql"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/auth"
	"watchmymedia/server/internal/backup"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/settings"
)

// Restoring an archive, as the README says (unpack it over the data
// directory and start), gives a server that starts cleanly with a new media
// token key: tokens made before the backup no longer work, and people sign
// in again with their passwords.
func TestRestoredServerSignsEveryoneInAgain(t *testing.T) {
	ctx := t.Context()
	live := t.TempDir()
	conn, q := openMigrated(t, filepath.Join(live, "watchmymedia.db"))
	a := auth.New(q, time.Hour, false)
	if err := loadMediaKey(ctx, settings.New(q), a); err != nil {
		t.Fatal(err)
	}
	if _, err := a.CreateUser(ctx, "Robin", "fictional-pass", true); err != nil {
		t.Fatal(err)
	}
	_, grant, err := a.Login(ctx, auth.Credentials{Name: "Robin", Password: "fictional-pass"}, auth.Client{Kind: auth.KindDevice, DeviceKey: "fictional-tv"})
	if err != nil {
		t.Fatal(err)
	}
	media, _, err := a.IssueMediaToken(ctx, grant.Token)
	if err != nil {
		t.Fatal(err)
	}
	cast, _, err := a.IssueCastToken(ctx, grant.Token)
	if err != nil {
		t.Fatal(err)
	}
	oldKey, _ := settings.New(q).Get(ctx, settings.KeyMediaTokenKey, "")
	path, err := backup.Save(ctx, conn, live, 0)
	if err != nil {
		t.Fatal(err)
	}

	// Restore the database into a fresh data directory and start as run does.
	dataDir := t.TempDir()
	unpack(t, path, dataDir)
	_, rq := openMigrated(t, filepath.Join(dataDir, "watchmymedia.db"))
	store := settings.New(rq)
	ra := auth.New(rq, time.Hour, false)
	if err := loadMediaKey(ctx, store, ra); err != nil {
		t.Fatal(err)
	}
	if key, _ := store.Get(ctx, settings.KeyMediaTokenKey, ""); key == "" || key == oldKey {
		t.Errorf("restored server's media key is empty or the old one")
	}
	if _, err := ra.Authenticate(ctx, grant.Token); !errors.Is(err, auth.ErrUnauthenticated) {
		t.Errorf("old session after restore: %v", err)
	}
	if _, err := ra.AuthenticateMedia(ctx, media); !errors.Is(err, auth.ErrBadMediaToken) {
		t.Errorf("old media token after restore: %v", err)
	}
	if _, err := ra.AuthenticateCast(ctx, cast); !errors.Is(err, auth.ErrBadCastToken) {
		t.Errorf("old cast token after restore: %v", err)
	}
	if _, _, err := ra.RenewCastToken(ctx, cast); !errors.Is(err, auth.ErrBadCastToken) {
		t.Errorf("renewing an old cast token after restore: %v", err)
	}
	if need, _ := ra.SetupRequired(ctx); need {
		t.Error("restored server asks for setup again")
	}
	_, fresh, err := ra.Login(ctx, auth.Credentials{Name: "Robin", Password: "fictional-pass"}, auth.Client{})
	if err != nil {
		t.Fatalf("sign in after restore: %v", err)
	}
	tok, _, err := ra.IssueMediaToken(ctx, fresh.Token)
	if err != nil {
		t.Fatal(err)
	}
	if u, err := ra.AuthenticateMedia(ctx, tok); err != nil || u.Name != "Robin" {
		t.Errorf("new media token after restore = %+v %v", u, err)
	}
	// A second start keeps the key it made.
	again := auth.New(rq, time.Hour, false)
	if err := loadMediaKey(ctx, store, again); err != nil {
		t.Fatal(err)
	}
	if _, err := again.AuthenticateMedia(ctx, tok); err != nil {
		t.Errorf("media token after a second start: %v", err)
	}
}

func openMigrated(t *testing.T, path string) (*sql.DB, *db.Queries) {
	t.Helper()
	conn, err := db.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := db.Migrate(t.Context(), conn); err != nil {
		t.Fatal(err)
	}
	return conn, db.New(conn)
}

func unpack(t *testing.T, archive, dir string) {
	t.Helper()
	f, err := os.Open(archive)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	tr := tar.NewReader(gz)
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			return
		}
		if err != nil {
			t.Fatal(err)
		}
		if h.Name != "watchmymedia.db" { // the rest is not needed here
			continue
		}
		data, err := io.ReadAll(tr)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "watchmymedia.db"), data, 0o600); err != nil {
			t.Fatal(err)
		}
	}
}
