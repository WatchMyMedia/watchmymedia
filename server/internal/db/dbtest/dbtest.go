// Package dbtest opens throwaway databases for tests.
package dbtest

import (
	"database/sql"
	"path/filepath"
	"testing"

	"watchmymedia/server/internal/db"
)

// Open creates a database in a temporary folder with every migration
// applied, as the server opens its own, and closes it when the test ends.
func Open(t testing.TB) (*sql.DB, *db.Queries) {
	t.Helper()
	conn, err := db.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := db.Migrate(t.Context(), conn); err != nil {
		t.Fatal(err)
	}
	return conn, db.New(conn)
}
