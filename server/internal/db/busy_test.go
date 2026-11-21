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

// busyDB is a database, a connection to hold its write lock with, and a
// way to open others that wait as long as a test says for that lock, where
// the server's wait 5 s.
func busyDB(t *testing.T) (holder *sql.DB, open func(busy time.Duration) *sql.DB) {
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
	return holder, func(busy time.Duration) *sql.DB {
		t.Helper()
		conn, err := OpenBusy(path, busy)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = conn.Close() })
		return conn
	}
}

func TestIsBusy(t *testing.T) {
	t.Parallel()
	holder, open := busyDB(t)
	quick := open(10 * time.Millisecond)
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
	holder, open := busyDB(t)
	quick := open(10 * time.Millisecond)
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

	// It never finishes. The wait counts from the first refusal, so
	// nothing here depends on how long a try takes, which is the
	// connection's own wait and the machine's doing: the write is tried
	// once more at least, and is not given up before the wait has passed
	// since it was first refused.
	hold()
	stays := func(conn *sql.DB, wait time.Duration) (tries int, first, after time.Duration, err error) {
		var refused time.Time
		begin := time.Now()
		err = RetryBusy(ctx, wait, func() error {
			_, err := conn.ExecContext(ctx, `INSERT INTO notes VALUES ('a')`)
			if tries++; tries == 1 {
				refused = time.Now()
			}
			return err
		})
		return tries, refused.Sub(begin), time.Since(refused), err
	}
	const wait = 40 * time.Millisecond
	tries, first, after, err := stays(quick, wait)
	if !IsBusy(err) || tries < 2 || after < wait {
		t.Errorf("a lock that stays: %v after %d tries, the first of %v, and %v more; want busy after a second try at least and %v more", err, tries, first, after, wait)
	}
	// A try that outlasts the whole wait, as one did on CI's Windows runner
	// (61 ms against a wait of 40 ms), when the wait still counted from the
	// start and the write was given up after that one try. Here the
	// connection itself waits three times as long for the lock as the
	// write is to be retried for, so the first try is the longer on any
	// machine.
	const (
		slowTry  = 60 * time.Millisecond
		slowWait = slowTry / 3
	)
	tries, first, after, err = stays(open(slowTry), slowWait)
	if first <= slowWait {
		t.Errorf("the first try took %v, no longer than the wait of %v: this is not the case it means to be", first, slowWait)
	}
	if !IsBusy(err) || tries < 2 || after < slowWait {
		t.Errorf("a lock that stays past a slow try: %v after %d tries, the first of %v, and %v more; want busy after a second try at least and %v more", err, tries, first, after, slowWait)
	}
	// No wait at all is one try.
	if tries, _, _, err = stays(quick, 0); !IsBusy(err) || tries != 1 {
		t.Errorf("no wait: %v after %d tries, want busy after one", err, tries)
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
