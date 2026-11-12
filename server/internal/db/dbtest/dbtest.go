// Package dbtest opens throwaway databases for tests.
package dbtest

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"watchmymedia/server/internal/db"
)

// template is a database with every migration applied, made once per test
// binary and kept as bytes. Running the migrations takes about 30 ms, but
// about 0.9 s under the race detector, where the SQLite driver's every step
// is instrumented; with hundreds of test databases per package that was
// most of CI's race job. Copying the file gives each test the same schema
// for a few milliseconds.
var template = sync.OnceValues(func() ([]byte, error) {
	dir, err := os.MkdirTemp("", "dbtest-")
	if err != nil {
		return nil, err
	}
	defer func() { _ = os.RemoveAll(dir) }()
	path := filepath.Join(dir, "template.db")
	conn, err := db.Open(path)
	if err != nil {
		return nil, err
	}
	if err := db.Migrate(context.Background(), conn); err != nil {
		_ = conn.Close()
		return nil, err
	}
	// Fold the write-ahead log into the file, so the file alone holds it all.
	if _, err := conn.Exec("PRAGMA wal_checkpoint(TRUNCATE)"); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if err := conn.Close(); err != nil {
		return nil, err
	}
	return os.ReadFile(path)
})

// Open creates a database in a temporary folder with every migration
// applied, as the server opens its own, and closes it when the test ends.
func Open(t testing.TB) (*sql.DB, *db.Queries) {
	t.Helper()
	conn := OpenPath(t, filepath.Join(t.TempDir(), "test.db"))
	return conn, db.New(conn)
}

// OpenPath is Open at a path of the caller's choosing, for tests that need
// to know where the file is.
func OpenPath(t testing.TB, path string) *sql.DB {
	t.Helper()
	conn, err := openMigrated(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

func openMigrated(path string) (*sql.DB, error) {
	data, err := template()
	if err != nil {
		return nil, fmt.Errorf("migrate template database: %w", err)
	}
	if err := os.WriteFile(path, data, 0o600); err != nil {
		return nil, err
	}
	return db.Open(path)
}
