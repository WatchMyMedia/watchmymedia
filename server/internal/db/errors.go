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
