package dbtest

import (
	"database/sql"
	"path/filepath"
	"slices"
	"testing"

	"watchmymedia/server/internal/db"
)

// The copied template must be the database a fresh migration makes: the
// same tables, indexes, triggers and views, and the same goose version, so
// that tests on a copy test the server's schema.
func TestOpenMatchesAFreshMigration(t *testing.T) {
	fresh, err := db.Open(filepath.Join(t.TempDir(), "fresh.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = fresh.Close() })
	if err := db.Migrate(t.Context(), fresh); err != nil {
		t.Fatal(err)
	}
	copied, _ := Open(t)
	if got, want := schema(t, copied), schema(t, fresh); !slices.Equal(got, want) {
		t.Fatalf("copied schema differs from a fresh migration:\n got %d objects\nwant %d objects", len(got), len(want))
	}
	if got, want := version(t, copied), version(t, fresh); got != want || got == 0 {
		t.Fatalf("goose version: copied %d, fresh %d", got, want)
	}
	var mode string
	if err := copied.QueryRow("PRAGMA journal_mode").Scan(&mode); err != nil || mode != "wal" {
		t.Fatalf("journal_mode = %q, %v; want wal", mode, err)
	}
	// Each test's copy is its own: a write to one is not in another.
	if _, err := copied.Exec("INSERT INTO settings (key, value) VALUES ('dbtest.marker', 'x')"); err != nil {
		t.Fatal(err)
	}
	other, _ := Open(t)
	var n int
	if err := other.QueryRow("SELECT count(*) FROM settings WHERE key = 'dbtest.marker'").Scan(&n); err != nil || n != 0 {
		t.Fatalf("second copy sees the first's row: n=%d, %v", n, err)
	}
}

func schema(t *testing.T, conn *sql.DB) []string {
	t.Helper()
	rows, err := conn.Query("SELECT type || ' ' || name || ' ' || coalesce(sql, '') FROM sqlite_master ORDER BY type, name")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			t.Fatal(err)
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return out
}

func version(t *testing.T, conn *sql.DB) int64 {
	t.Helper()
	var v int64
	if err := conn.QueryRow("SELECT max(version_id) FROM goose_db_version WHERE is_applied").Scan(&v); err != nil {
		t.Fatal(err)
	}
	return v
}
