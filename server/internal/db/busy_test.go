package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

// busyPair is a database with two connections to it: one to hold the write
// lock with, and one that waits 10 ms for it where the server's waits 5 s.
func busyPair(t *testing.T) (holder, quick *sql.DB) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "t.db")
	holder, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = holder.Close() })
	if _, err := holder.ExecContext(t.Context(), `CREATE TABLE notes (text TEXT)`); err != nil {
		t.Fatal(err)
	}
	quick, err = OpenBusy(path, 10*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = quick.Close() })
	return holder, quick
}

func TestIsBusy(t *testing.T) {
	t.Parallel()
	holder, quick := busyPair(t)
	ctx := t.Context()
	tx, err := holder.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tx.Rollback() }()

	// A write, and the start of a transaction, while another holds the
	// database.
	_, write := quick.ExecContext(ctx, `INSERT INTO notes VALUES ('a')`)
	if !IsBusy(write) || !IsBusy(fmt.Errorf("store: %w", write)) {
		t.Errorf("a write to a locked database: %v", write)
	}
	begin := New(quick).Transaction(ctx, func(*Queries) error { return nil })
	if !IsBusy(begin) {
		t.Errorf("a transaction on a locked database: %v", begin)
	}
	// Reading goes on meanwhile, and other failures are not this one.
	var n int
	if err := quick.QueryRowContext(ctx, `SELECT COUNT(*) FROM notes`).Scan(&n); err != nil {
		t.Errorf("a read beside a writer: %v", err)
	}
	_, missing := quick.ExecContext(ctx, `INSERT INTO nowhere VALUES ('a')`)
	if missing == nil || IsBusy(missing) || IsBusy(nil) || IsBusy(errors.New("database is locked")) || IsBusy(context.DeadlineExceeded) {
		t.Errorf("counted as busy what is not: %v", missing)
	}
}

func TestRetryBusy(t *testing.T) {
	t.Parallel()
	holder, quick := busyPair(t)
	ctx := t.Context()
	hold := func() *sql.Tx {
		t.Helper()
		tx, err := holder.BeginTx(ctx, nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = tx.Rollback() })
		return tx
	}
	write := func() error {
		_, err := quick.ExecContext(ctx, `INSERT INTO notes VALUES ('a')`)
		return err
	}

	// The other writer finishes while it waits: the write is tried again
	// and goes through.
	tx := hold()
	tries := 0
	err := RetryBusy(ctx, time.Minute, func() error {
		if tries++; tries == 2 {
			_ = tx.Rollback()
		}
		return write()
	})
	if err != nil || tries != 2 {
		t.Errorf("a lock that clears: %v after %d tries, want it written at the second", err, tries)
	}

	// It never finishes: the write is given up once the wait has passed,
	// with the database's own error.
	hold()
	tries = 0
	begin := time.Now()
	err = RetryBusy(ctx, 40*time.Millisecond, func() error {
		tries++
		return write()
	})
	if !IsBusy(err) || tries < 2 || time.Since(begin) < 40*time.Millisecond {
		t.Errorf("a lock that stays: %v after %d tries in %v, want busy after more than one try and the whole wait", err, tries, time.Since(begin))
	}

	// What it was waiting for ends: its error, at once.
	cancelled, cancel := context.WithCancel(ctx)
	tries = 0
	err = RetryBusy(cancelled, time.Minute, func() error {
		tries++
		cancel()
		return write()
	})
	if !errors.Is(err, context.Canceled) || tries != 1 {
		t.Errorf("a context that ends: %v after %d tries", err, tries)
	}

	// Any other failure is not tried again, nor is success.
	tries = 0
	no := errors.New("no")
	if err := RetryBusy(ctx, time.Minute, func() error { tries++; return no }); !errors.Is(err, no) || tries != 1 {
		t.Errorf("another error: %v after %d tries", err, tries)
	}
	tries = 0
	if err := RetryBusy(ctx, time.Minute, func() error { tries++; return nil }); err != nil || tries != 1 {
		t.Errorf("no error: %v after %d tries", err, tries)
	}
}
