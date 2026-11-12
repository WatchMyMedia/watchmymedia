// Package db owns the SQLite connection, embedded goose migrations and the
// sqlc-generated query layer (models.go, querier.go, *.sql.go).
package db

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"io/fs"
	"net/url"

	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/database"
	_ "modernc.org/sqlite" // registers the "sqlite" driver
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// IdleConns is how many connections the pool keeps open between requests,
// rather than the two database/sql keeps by default: a new connection costs
// about 0.7 ms against 4 µs for one reused, and a burst of page requests
// would otherwise open and close several each time. Open connections are
// not capped: code that holds rows or a transaction while it runs another
// query would deadlock a capped pool once a burst took every connection.
const IdleConns = 8

// Open opens (creating if needed) the SQLite database at path with the
// pragmas this server relies on: WAL for concurrent readers, a busy timeout
// so writers wait instead of failing, and foreign keys enforced.
//
// Transactions begin IMMEDIATE (_txlock): one that reads and then writes
// takes the write lock up front and waits out the busy timeout like any
// writer. Under a deferred BEGIN its upgrade from reader to writer fails at
// once with SQLITE_BUSY when another connection has written meanwhile; the
// busy timeout does not apply there. Read-only transactions
// (sql.TxOptions{ReadOnly: true}) still begin deferred.
func Open(path string) (*sql.DB, error) {
	dsn := "file:" + url.PathEscape(path) +
		"?_pragma=busy_timeout(5000)" +
		"&_pragma=journal_mode(WAL)" +
		"&_pragma=foreign_keys(1)" +
		"&_pragma=synchronous(NORMAL)" +
		"&_txlock=immediate"
	conn, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	conn.SetMaxIdleConns(IdleConns)
	if err := conn.PingContext(context.Background()); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("ping sqlite: %w", err)
	}
	return conn, nil
}

// Migrate applies all pending embedded migrations.
func Migrate(ctx context.Context, conn *sql.DB) error {
	p, err := migrations(conn)
	if err != nil {
		return err
	}
	if _, err := p.Up(ctx); err != nil {
		return fmt.Errorf("apply migrations: %w", err)
	}
	return nil
}

func migrations(conn *sql.DB) (*goose.Provider, error) {
	sub, err := fs.Sub(migrationsFS, "migrations")
	if err != nil {
		return nil, err
	}
	p, err := goose.NewProvider(database.DialectSQLite3, conn, sub)
	if err != nil {
		return nil, fmt.Errorf("migration provider: %w", err)
	}
	return p, nil
}
