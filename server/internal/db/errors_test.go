package db

import (
	"errors"
	"fmt"
	"testing"
)

func TestIsUniqueViolation(t *testing.T) {
	conn, q := openTest(t)
	ctx := t.Context()
	if _, err := q.CreateUser(ctx, CreateUserParams{Name: "ann", PasswordHash: "x"}); err != nil {
		t.Fatal(err)
	}
	_, dup := q.CreateUser(ctx, CreateUserParams{Name: "ANN", PasswordHash: "x"})
	if !IsUniqueViolation(dup) || !IsUniqueViolation(fmt.Errorf("create: %w", dup)) {
		t.Errorf("duplicate name: %v", dup)
	}
	_, pk := conn.ExecContext(ctx, `INSERT INTO settings (key, value) VALUES ('k', 'v'), ('k', 'w')`)
	if !IsUniqueViolation(pk) {
		t.Errorf("duplicate primary key: %v", pk)
	}
	_, fk := conn.ExecContext(ctx, `INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ('t', 999, '')`)
	if fk == nil || IsUniqueViolation(fk) {
		t.Errorf("foreign key failure counted as unique: %v", fk)
	}
	if IsUniqueViolation(nil) || IsUniqueViolation(errors.New("UNIQUE constraint failed")) {
		t.Error("not an SQLite error")
	}
}
