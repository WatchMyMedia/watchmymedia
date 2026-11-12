package db

import (
	"context"
	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/database"
	"io/fs"
	"path/filepath"
	"testing"
)

func TestAccountIdentityMigration(t *testing.T) {
	ctx := context.Background()
	conn, err := Open(filepath.Join(t.TempDir(), "identity.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	sub, _ := fs.Sub(migrationsFS, "migrations")
	p, err := goose.NewProvider(database.DialectSQLite3, conn, sub)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.UpTo(ctx, 18); err != nil {
		t.Fatal(err)
	}
	for _, sql := range []string{
		`INSERT INTO users(id, name, password_hash) VALUES (5, 'retained', 'hash')`,
		`INSERT INTO sessions(token_hash, user_id, expires_at) VALUES ('session', 5, '2099-01-01')`,
		`INSERT INTO activity_daily(local_day, user_id, item_type, mode, network, plays, played_ms) VALUES ('2026-10-31',40,'movie','direct','local',2,1000)`,
		`INSERT INTO activity_monthly(local_month, user_id, item_type, mode, network, plays, played_ms) VALUES ('2026-10',42,'movie','direct','local',3,2000)`,
	} {
		if _, err := conn.ExecContext(ctx, sql); err != nil {
			t.Fatal(err)
		}
	}
	// Migrate on to the latest: the queries below are today's, and later
	// migrations add columns they read.
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
	q := New(conn)
	u, err := q.CreateUser(ctx, CreateUserParams{Name: "new", PasswordHash: "hash"})
	if err != nil || u.ID != 43 {
		t.Fatalf("allocated: %+v %v", u, err)
	}
	for _, table := range []string{"activity_daily", "activity_monthly"} {
		period := "local_day"
		if table == "activity_monthly" {
			period = "local_month"
		}
		for _, user := range []int64{5, u.ID} {
			if _, err := conn.ExecContext(ctx, "INSERT INTO "+table+"("+period+",user_id,item_type,mode,network,plays,played_ms) VALUES ('2026-10-31',?,'movie','direct','local',1,1000)", user); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err := q.DeleteUser(ctx, u.ID); err != nil {
		t.Fatal(err)
	}
	for _, table := range []string{"activity_daily", "activity_monthly"} {
		var deleted, retained int
		if err := conn.QueryRowContext(ctx, "SELECT count(*) FROM "+table+" WHERE user_id=?", u.ID).Scan(&deleted); err != nil {
			t.Fatal(err)
		}
		if err := conn.QueryRowContext(ctx, "SELECT count(*) FROM "+table+" WHERE user_id=5").Scan(&retained); err != nil {
			t.Fatal(err)
		}
		if deleted != 0 || retained != 1 {
			t.Fatalf("%s: deleted viewer rows=%d, retained viewer rows=%d", table, deleted, retained)
		}
	}
	// Even a writer bypassing the application allocator cannot reuse an ID.
	if _, err := conn.ExecContext(ctx, `INSERT INTO users(id,name,password_hash) VALUES(43,'reuse','hash')`); err == nil {
		t.Fatal("reused identity accepted")
	}
	v, err := q.CreateUser(ctx, CreateUserParams{Name: "next", PasswordHash: "hash"})
	if err != nil || v.ID != 44 {
		t.Fatalf("next: %+v %v", v, err)
	}
	var n int
	if err := conn.QueryRowContext(ctx, `SELECT count(*) FROM sessions WHERE user_id=5`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("migration lost session: %d %v", n, err)
	}
	if _, err := p.DownTo(ctx, 18); err != nil {
		t.Fatal(err)
	}
	if _, err := p.Up(ctx); err != nil {
		t.Fatal(err)
	}
	w, err := q.CreateUser(ctx, CreateUserParams{Name: "after-roundtrip", PasswordHash: "hash"})
	if err != nil || w.ID != 45 {
		t.Fatalf("roundtrip: %+v %v", w, err)
	}
	rows, err := conn.QueryContext(ctx, `PRAGMA foreign_key_check`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	if rows.Next() {
		t.Fatal("migration damaged foreign keys")
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
}
