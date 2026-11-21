package db

import (
	"errors"

	"modernc.org/sqlite"
	sqlite3 "modernc.org/sqlite/lib"
)

// IsUniqueViolation reports whether err is a UNIQUE or PRIMARY KEY
// constraint failing, such as a second row with a name already in use.
func IsUniqueViolation(err error) bool {
	var e *sqlite.Error
	if !errors.As(err, &e) {
		return false
	}
	switch e.Code() {
	case sqlite3.SQLITE_CONSTRAINT_UNIQUE, sqlite3.SQLITE_CONSTRAINT_PRIMARYKEY:
		return true
	}
	return false
}

// IsDiskFull reports whether SQLite stopped because the disk is full, as
// VACUUM INTO does when a backup's copy of the database doesn't fit.
func IsDiskFull(err error) bool {
	e, ok := errors.AsType[*sqlite.Error](err)
	return ok && e.Code()&0xff == sqlite3.SQLITE_FULL
}

// IsBusy reports whether SQLite gave up waiting for the database: another
// connection held the write lock for longer than the busy timeout
// ("database is locked"). That says nothing about what was being written;
// the same write succeeds once the other writer is done.
func IsBusy(err error) bool {
	e, ok := errors.AsType[*sqlite.Error](err)
	return ok && e.Code()&0xff == sqlite3.SQLITE_BUSY
}
