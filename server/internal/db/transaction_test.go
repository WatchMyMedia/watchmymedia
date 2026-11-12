package db

import (
	"database/sql"
	"errors"
	"testing"
)

func TestTransactionRollsBack(t *testing.T) {
	_, q := openTest(t)
	ctx := t.Context()
	boom := errors.New("boom")
	err := q.Transaction(ctx, func(q *Queries) error {
		if err := q.SetSetting(ctx, SetSettingParams{Key: "a", Value: "1"}); err != nil {
			return err
		}
		return boom
	})
	if !errors.Is(err, boom) {
		t.Fatalf("Transaction returned %v", err)
	}
	if _, err := q.GetSetting(ctx, "a"); !errors.Is(err, sql.ErrNoRows) {
		t.Errorf("a failed transaction's write was kept: %v", err)
	}
	if err := q.Transaction(ctx, func(q *Queries) error { return q.SetSetting(ctx, SetSettingParams{Key: "a", Value: "2"}) }); err != nil {
		t.Fatal(err)
	}
	if v, err := q.GetSetting(ctx, "a"); err != nil || v != "2" {
		t.Errorf("committed value %q, %v", v, err)
	}
	// Queries already in a transaction cannot start another.
	err = q.Transaction(ctx, func(inner *Queries) error {
		return inner.Transaction(ctx, func(*Queries) error { return nil })
	})
	if err == nil {
		t.Error("nested Transaction did not fail")
	}
}
