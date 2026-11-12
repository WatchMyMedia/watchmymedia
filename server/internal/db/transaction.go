package db

import (
	"context"
	"database/sql"
	"errors"
)

// Transaction runs related mutations atomically on the connection owned by q.
func (q *Queries) Transaction(ctx context.Context, fn func(*Queries) error) error {
	conn, ok := q.db.(interface {
		BeginTx(context.Context, *sql.TxOptions) (*sql.Tx, error)
	})
	if !ok {
		return errors.New("queries do not own a transaction-capable connection")
	}
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if err := fn(q.WithTx(tx)); err != nil {
		return err
	}
	return tx.Commit()
}
