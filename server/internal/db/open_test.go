package db

import (
	"database/sql"

	"path/filepath"
	"testing"
	"time"
)

func openTest(t *testing.T) (*sql.DB, *Queries) {
	t.Helper()
	conn, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := Migrate(t.Context(), conn); err != nil {
		t.Fatal(err)
	}
	return conn, New(conn)
}

// Every connection the pool opens gets the pragmas the server relies on.
func TestOpenPragmas(t *testing.T) {
	conn, _ := openTest(t)
	ctx := t.Context()
	// Hold several connections at once so the pragmas are read on more
	// than the first.
	var conns []*sql.Conn
	for range 3 {
		c, err := conn.Conn(ctx)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, c)
	}
	for i, c := range conns {
		var journal string
		var foreignKeys, busy, synchronous int
		if err := c.QueryRowContext(ctx, `PRAGMA journal_mode`).Scan(&journal); err != nil {
			t.Fatal(err)
		}
		_ = c.QueryRowContext(ctx, `PRAGMA foreign_keys`).Scan(&foreignKeys)
		_ = c.QueryRowContext(ctx, `PRAGMA busy_timeout`).Scan(&busy)
		_ = c.QueryRowContext(ctx, `PRAGMA synchronous`).Scan(&synchronous)
		if journal != "wal" || foreignKeys != 1 || busy != 5000 || synchronous != 1 {
			t.Errorf("connection %d: journal_mode %s, foreign_keys %d, busy_timeout %d, synchronous %d; want wal 1 5000 1 (NORMAL)",
				i, journal, foreignKeys, busy, synchronous)
		}
	}
	for _, c := range conns {
		_ = c.Close()
	}
	// Connections freed after a burst stay open for the next one, rather
	// than the two database/sql keeps by default, and open ones are not
	// capped (a capped pool can deadlock code that nests queries).
	if st := conn.Stats(); st.MaxOpenConnections != 0 || st.Idle != 3 {
		t.Errorf("pool: max open %d, idle %d; want 0 (no cap) and 3", st.MaxOpenConnections, st.Idle)
	}
}

// A transaction that reads and then writes waits for another writer to
// finish, rather than failing with SQLITE_BUSY when it comes to write: it
// takes the write lock when it begins.
func TestReadThenWriteWaitsForWriter(t *testing.T) {
	conn, q := openTest(t)
	ctx := t.Context()
	if err := q.SetSetting(ctx, SetSettingParams{Key: "n", Value: "0"}); err != nil {
		t.Fatal(err)
	}
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	var n string
	if err := tx.QueryRowContext(ctx, `SELECT value FROM settings WHERE key = 'n'`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	// Another writer comes along between the read and the write.
	other := make(chan error, 1)
	go func() { other <- q.SetSetting(ctx, SetSettingParams{Key: "other", Value: "1"}) }()
	select {
	case err := <-other:
		// Under a deferred BEGIN the other write goes through here, and
		// the write below then fails at once.
		t.Errorf("another connection wrote while a transaction held the write lock (%v)", err)
	case <-time.After(100 * time.Millisecond):
	}
	if _, err := tx.ExecContext(ctx, `UPDATE settings SET value = ? WHERE key = 'n'`, n+"1"); err != nil {
		t.Fatalf("write after read: %v", err)
	}
	if err := tx.Commit(); err != nil {
		t.Fatal(err)
	}
	if err := <-other; err != nil {
		t.Errorf("the other writer: %v", err)
	}
	// Read-only transactions do not take the lock.
	ro, err := conn.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = ro.Rollback() }()
	if err := q.SetSetting(ctx, SetSettingParams{Key: "n", Value: "2"}); err != nil {
		t.Errorf("write beside a read-only transaction: %v", err)
	}
}
