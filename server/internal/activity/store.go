package activity

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
)

// values is what a play row gets updated with while it runs.
type values struct {
	row         int64
	state       string
	lastSeen    time.Time
	playedMs    int64
	pausedMs    int64
	bufferingMs int64
	startPos    int64
	pos         int64
	maxPos      int64
	durationMs  int64
	completed   bool
	stream      Stream
	bytes       int64
	auto        string
}

func (p *live) values() values {
	return values{
		row: p.row, state: p.state, lastSeen: p.lastSeen, playedMs: p.playedMs, pausedMs: p.pausedMs, bufferingMs: p.bufferingMs,
		startPos: p.startPos, pos: p.pos, maxPos: p.maxPos, durationMs: p.durationMs, completed: p.completed, stream: p.stream, bytes: p.bytes,
		auto: autoText(p.auto.record()),
	}
}

// autoText is the plays.auto column: empty when there is nothing to keep.
func autoText(a *PlayAuto) string {
	if a == nil {
		return ""
	}
	return jsonText(a)
}

func jsonText(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "null"
	}
	return string(b)
}

// localTime is when t was in the server's time zone: the day, hour and
// weekday that charts use.
func (t *Tracker) localTime(at time.Time) (day string, hour, weekday int) {
	l := at.In(t.loc)
	return l.Format(time.DateOnly), l.Hour(), int(l.Weekday())
}

// insert writes a new play with its device and history item.
func (t *Tracker) insert(ctx context.Context, p *live) (row, device int64, err error) {
	tx, err := t.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, 0, err
	}
	defer func() { _ = tx.Rollback() }()
	now := db.FormatTime(p.startedAt)
	ip := ""
	if p.ip.IsValid() {
		ip = p.ip.String()
	}
	var deviceID sql.NullInt64
	if p.device.Key != "" {
		err = tx.QueryRowContext(ctx, `
			INSERT INTO devices (user_id, device_key, name, product, platform, version, last_ip, first_seen_at, last_seen_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (user_id, device_key) DO UPDATE SET
				name = excluded.name, product = excluded.product, platform = excluded.platform,
				version = excluded.version, last_ip = excluded.last_ip, last_seen_at = excluded.last_seen_at
			RETURNING id`,
			p.userID, p.device.Key, p.device.Name, p.device.Product, p.device.Platform, p.device.Version, ip, now, now).Scan(&deviceID)
		if err != nil {
			return 0, 0, fmt.Errorf("upsert device: %w", err)
		}
	}
	it := p.item
	var itemID int64
	err = tx.QueryRowContext(ctx, `
		INSERT INTO history_items (item_type, item_id, movie_id, show_id, library_id, title, show_title, season_number, episode_number, year, poster_path, art_path, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (item_type, item_id) DO UPDATE SET
			movie_id = excluded.movie_id, show_id = excluded.show_id, library_id = excluded.library_id,
			title = excluded.title, show_title = excluded.show_title, season_number = excluded.season_number,
			episode_number = excluded.episode_number, year = excluded.year, poster_path = excluded.poster_path,
			art_path = excluded.art_path, updated_at = excluded.updated_at
		RETURNING id`,
		it.Type, it.ItemID, it.MovieID, it.ShowID, it.LibraryID, it.Title, it.ShowTitle, it.SeasonNumber, it.EpisodeNumber, it.Year,
		it.PosterPath, it.ArtPath, now).Scan(&itemID)
	if err != nil {
		return 0, 0, fmt.Errorf("upsert history item: %w", err)
	}
	day, hour, weekday := t.localTime(p.startedAt)
	s := p.stream
	var fileID sql.NullInt64
	if p.fileID > 0 {
		fileID = sql.NullInt64{Int64: p.fileID, Valid: true}
	}
	err = tx.QueryRowContext(ctx, `
		INSERT INTO plays (public_id, user_id, user_name, device_id, history_item_id, media_file_id, library_id, item_type, show_id,
			state, started_at, last_seen_at, start_position_ms, end_position_ms, max_position_ms, duration_ms,
			mode, reasons, source, stream, encoder, quality, auto, ip, network, local_day, local_hour, local_weekday)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		RETURNING id`,
		p.id, p.userID, p.userName, deviceID, itemID, fileID, it.LibraryID, it.Type, it.ShowID,
		p.state, now, now, p.startPos, p.pos, p.maxPos, p.durationMs,
		s.Mode, jsonText(s.Reasons), jsonText(s.Source), jsonText(s.Stream), s.Encoder, s.Quality, autoText(p.auto.record()), ip, p.network, day, hour, weekday).Scan(&row)
	if err != nil {
		return 0, 0, fmt.Errorf("insert play: %w", err)
	}
	return row, deviceID.Int64, tx.Commit()
}

// write saves a running play's progress.
func (t *Tracker) write(ctx context.Context, v values) {
	if v.row == 0 || t.db == nil {
		return
	}
	if err := updateRow(ctx, t.db, v); err != nil {
		t.log.Warn("could not save play", "row", v.row, "err", err)
	}
}

type execer interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
}

type querier interface {
	QueryContext(ctx context.Context, query string, args ...any) (*sql.Rows, error)
}

// readAll runs a query on the database or a transaction and reads every
// row with scan.
func readAll[T any](ctx context.Context, q querier, scan func(*sql.Rows, *T) error, query string, args ...any) ([]T, error) {
	rows, err := q.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()
	var out []T
	for rows.Next() {
		var v T
		if err := scan(rows, &v); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// updateRow saves a running play's values. A play that has finished keeps
// what it finished with: Heartbeat writes its snapshot after letting go of
// the lock, so a late write could otherwise set a stopped play playing again.
func updateRow(ctx context.Context, x execer, v values) error {
	s := v.stream
	_, err := x.ExecContext(ctx, `
		UPDATE plays SET state = ?, last_seen_at = ?, played_ms = ?, paused_ms = ?, buffering_ms = ?,
			start_position_ms = ?, end_position_ms = ?, max_position_ms = ?, duration_ms = ?, completed = ?,
			mode = ?, reasons = ?, source = ?, stream = ?, encoder = ?, quality = ?, auto = ?, bytes_sent = ?
		WHERE id = ? AND stopped_at IS NULL`,
		v.state, db.FormatTime(v.lastSeen), v.playedMs, v.pausedMs, v.bufferingMs,
		v.startPos, v.pos, v.maxPos, v.durationMs, db.BoolInt(v.completed),
		s.Mode, jsonText(s.Reasons), jsonText(s.Source), jsonText(s.Stream), s.Encoder, s.Quality, v.auto, v.bytes, v.row)
	return err
}

// lastStream reads how an earlier play streamed, for a play that continues it.
func (t *Tracker) lastStream(ctx context.Context, publicID string, userID int64) Stream {
	var s Stream
	if t.db == nil {
		return s
	}
	var reasons, source, stream, auto string
	err := t.db.QueryRowContext(ctx, `SELECT mode, reasons, source, stream, encoder, quality, auto FROM plays WHERE public_id = ? AND user_id = ?`, publicID, userID).
		Scan(&s.Mode, &reasons, &source, &stream, &s.Encoder, &s.Quality, &auto)
	if err != nil {
		return Stream{}
	}
	_ = json.Unmarshal([]byte(reasons), &s.Reasons)
	_ = json.Unmarshal([]byte(source), &s.Source)
	_ = json.Unmarshal([]byte(stream), &s.Stream)
	// An Automatic play carries on at the step its player is still on.
	var a PlayAuto
	if auto != "" && json.Unmarshal([]byte(auto), &a) == nil && a.On {
		s.Step = a.Step
	}
	return s
}

// finish ends a play row: dropped if it was too short to count, otherwise
// stopped, grouped with the play it resumes, and added to the daily totals.
// v carries the final in-memory state; nil finishes the row as stored (for
// plays left open by a crash).
func (t *Tracker) finish(ctx context.Context, v *values, at time.Time, reason string) error {
	return t.finishRow(ctx, v.row, v, at, reason)
}

func (t *Tracker) finishRow(ctx context.Context, row int64, v *values, at time.Time, reason string) error {
	cfg := t.Config()
	tx, err := t.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if v != nil {
		if err := updateRow(ctx, tx, *v); err != nil {
			return err
		}
	}
	var played, completed int64
	if err := tx.QueryRowContext(ctx, `SELECT played_ms, completed FROM plays WHERE id = ?`, row).Scan(&played, &completed); err != nil {
		return err
	}
	if completed == 0 && played < cfg.MinPlay.Milliseconds() {
		_, err := tx.ExecContext(ctx, `DELETE FROM plays WHERE id = ?`, row)
		if err != nil {
			return err
		}
		return tx.Commit()
	}
	stopped := db.FormatTime(at)
	if _, err := tx.ExecContext(ctx, `UPDATE plays SET state = 'stopped', stopped_at = ?, end_reason = ? WHERE id = ?`, stopped, reason, row); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE devices SET last_seen_at = max(last_seen_at, ?) WHERE id = (SELECT device_id FROM plays WHERE id = ?)`, stopped, row); err != nil {
		return err
	}
	days, err := group(ctx, tx, row, cfg.Group)
	if err != nil {
		return err
	}
	if err := rebuildDays(ctx, tx, days); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	t.version.Add(1)
	return nil
}

// group joins a finished play to the group of the user's previous play of
// the same item when that one was not finished and this one starts at or
// after where it ended (less a little slack); otherwise the play starts its
// own group. It returns the days whose totals changed.
func group(ctx context.Context, tx *sql.Tx, row int64, enabled bool) ([]string, error) {
	var (
		userID              sql.NullInt64
		itemID, start, play int64
		startedAt, day      string
	)
	err := tx.QueryRowContext(ctx, `SELECT user_id, history_item_id, start_position_ms, played_ms, started_at, local_day FROM plays WHERE id = ?`, row).
		Scan(&userID, &itemID, &start, &play, &startedAt, &day)
	if err != nil {
		return nil, err
	}
	days := []string{day}
	if enabled && userID.Valid {
		var (
			prevID, groupID, count, groupPlayed, end, completed int64
			groupStarted, prevDay                               string
		)
		err := tx.QueryRowContext(ctx, `
			SELECT id, group_id, group_count, group_started_at, group_played_ms, end_position_ms, completed, local_day
			FROM plays
			WHERE history_item_id = ? AND user_id = ? AND group_latest = 1 AND id <> ? AND started_at <= ?
			ORDER BY started_at DESC, id DESC LIMIT 1`, itemID, userID.Int64, row, startedAt).
			Scan(&prevID, &groupID, &count, &groupStarted, &groupPlayed, &end, &completed, &prevDay)
		switch {
		case errors.Is(err, sql.ErrNoRows):
		case err != nil:
			return nil, err
		case completed == 0 && start >= end-groupSlack.Milliseconds():
			if _, err := tx.ExecContext(ctx, `UPDATE plays SET group_latest = 0 WHERE id = ?`, prevID); err != nil {
				return nil, err
			}
			_, err := tx.ExecContext(ctx, `
				UPDATE plays SET group_id = ?, group_latest = 1, group_started_at = ?, group_played_ms = ?, group_count = ? WHERE id = ?`,
				groupID, groupStarted, groupPlayed+play, count+1, row)
			return append(days, prevDay), err
		}
	}
	_, err = tx.ExecContext(ctx, `
		UPDATE plays SET group_id = id, group_latest = 1, group_started_at = started_at, group_played_ms = played_ms, group_count = 1 WHERE id = ?`, row)
	return days, err
}

// regroup recomputes a group after some of its rows were deleted: the
// first remaining row names it and the newest carries its totals. It
// returns the days of the remaining rows.
func regroup(ctx context.Context, tx *sql.Tx, groupID int64) ([]string, error) {
	type r struct {
		id      int64
		started string
		played  int64
		day     string
	}
	rs, err := readAll(ctx, tx, func(rows *sql.Rows, x *r) error { return rows.Scan(&x.id, &x.started, &x.played, &x.day) },
		`SELECT id, started_at, played_ms, local_day FROM plays WHERE group_id = ? AND stopped_at IS NOT NULL ORDER BY started_at, id`, groupID)
	if err != nil || len(rs) == 0 {
		return nil, err
	}
	first, last := rs[0], rs[len(rs)-1]
	var sum int64
	days := make([]string, 0, len(rs))
	for _, x := range rs {
		sum += x.played
		days = append(days, x.day)
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE plays SET group_id = ?, group_latest = (id = ?), group_started_at = ? WHERE group_id = ? AND stopped_at IS NOT NULL`,
		first.id, last.id, first.started, groupID); err != nil {
		return nil, err
	}
	_, err = tx.ExecContext(ctx, `UPDATE plays SET group_played_ms = ?, group_count = ? WHERE id = ?`, sum, len(rs), last.id)
	return days, err
}

// rebuildDays recomputes the daily totals of the given days from plays,
// and the monthly totals of their months from the daily ones. A group
// counts as one play, on the day of its newest row.
func rebuildDays(ctx context.Context, tx *sql.Tx, days []string) error {
	seen := map[string]bool{}
	months := map[string]bool{}
	for _, d := range days {
		if seen[d] {
			continue
		}
		seen[d] = true
		months[d[:7]] = true
		if _, err := tx.ExecContext(ctx, `DELETE FROM activity_daily WHERE local_day = ?`, d); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO activity_daily (local_day, user_id, item_type, mode, network, plays, played_ms)
			SELECT local_day, COALESCE(user_id, 0), item_type, mode, network, SUM(group_latest), SUM(played_ms)
			FROM plays WHERE local_day = ? AND stopped_at IS NOT NULL
			GROUP BY 1, 2, 3, 4, 5`, d); err != nil {
			return err
		}
	}
	for m := range months {
		if err := rebuildMonth(ctx, tx, m); err != nil {
			return err
		}
	}
	return nil
}

func rebuildMonth(ctx context.Context, tx *sql.Tx, month string) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM activity_monthly WHERE local_month = ?`, month); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `
		INSERT INTO activity_monthly (local_month, user_id, item_type, mode, network, plays, played_ms)
		SELECT ?, user_id, item_type, mode, network, SUM(plays), SUM(played_ms)
		FROM activity_daily WHERE local_day >= ? AND local_day < ?
		GROUP BY 2, 3, 4, 5`, month, month+"-01", month+"-99")
	return err
}

// Recover closes plays a previous run left open, at their last report.
func (t *Tracker) Recover(ctx context.Context) error {
	if t.db == nil {
		return nil
	}
	type open struct {
		id   int64
		seen string
	}
	opens, err := readAll(ctx, t.db, func(rows *sql.Rows, o *open) error { return rows.Scan(&o.id, &o.seen) },
		`SELECT id, last_seen_at FROM plays WHERE stopped_at IS NULL`)
	if err != nil {
		return err
	}
	for _, o := range opens {
		if err := t.finishRow(ctx, o.id, nil, db.ParseTime(o.seen), EndTimeout); err != nil {
			return err
		}
	}
	if len(opens) > 0 {
		t.log.Info("closed plays left open by the last run", "plays", len(opens))
	}
	return nil
}

// Delete removes plays from history: single rows by ID, and whole groups.
// Live plays are never deleted. Groups that lose rows are recomputed, and
// the daily totals of every day touched.
func (t *Tracker) Delete(ctx context.Context, ids, groups []int64) (int64, error) {
	if t.db == nil || len(ids)+len(groups) == 0 {
		return 0, nil
	}
	tx, err := t.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback() }()
	where := "stopped_at IS NOT NULL AND (id IN (" + placeholders(len(ids)) + ") OR group_id IN (" + placeholders(len(groups)) + "))"
	args := append(int64Args(ids), int64Args(groups)...)
	type hit struct {
		group sql.NullInt64
		day   string
	}
	hits, err := readAll(ctx, tx, func(rows *sql.Rows, h *hit) error { return rows.Scan(&h.group, &h.day) },
		`SELECT DISTINCT group_id, local_day FROM plays WHERE `+where, args...)
	if err != nil {
		return 0, err
	}
	var touched []int64
	var days []string
	for _, h := range hits {
		if h.group.Valid {
			touched = append(touched, h.group.Int64)
		}
		days = append(days, h.day)
	}
	//nolint:gosec // G202: the clauses joined in are fixed text; every value is a bound argument.
	res, err := tx.ExecContext(ctx, `DELETE FROM plays WHERE `+where, args...)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	for _, g := range touched {
		d, err := regroup(ctx, tx, g)
		if err != nil {
			return 0, err
		}
		days = append(days, d...)
	}
	if err := rebuildDays(ctx, tx, days); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	t.version.Add(1)
	return n, nil
}

// Trim deletes plays older than the retention setting, whole days at a
// time. The daily totals stay unless TrimStats is set.
func (t *Tracker) Trim(ctx context.Context) (int64, error) {
	cfg := t.Config()
	if t.db == nil || cfg.RetentionDays <= 0 {
		return 0, nil
	}
	cutoff, _, _ := t.localTime(t.now().AddDate(0, 0, -cfg.RetentionDays))
	tx, err := t.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer func() { _ = tx.Rollback() }()
	groups, err := readAll(ctx, tx, func(rows *sql.Rows, g *int64) error { return rows.Scan(g) },
		`SELECT DISTINCT group_id FROM plays WHERE local_day < ? AND group_id IS NOT NULL`, cutoff)
	if err != nil {
		return 0, err
	}
	res, err := tx.ExecContext(ctx, `DELETE FROM plays WHERE local_day < ? AND stopped_at IS NOT NULL`, cutoff)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	var days []string
	for _, g := range groups {
		d, err := regroup(ctx, tx, g)
		if err != nil {
			return 0, err
		}
		days = append(days, d...)
	}
	if err := rebuildDays(ctx, tx, days); err != nil {
		return 0, err
	}
	if cfg.TrimStats {
		if _, err := tx.ExecContext(ctx, `DELETE FROM activity_daily WHERE local_day < ?`, cutoff); err != nil {
			return 0, err
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM activity_monthly WHERE local_month < ?`, cutoff[:7]); err != nil {
			return 0, err
		}
		if err := rebuildMonth(ctx, tx, cutoff[:7]); err != nil {
			return 0, err
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	if n > 0 {
		t.version.Add(1)
	}
	return n, nil
}

func placeholders(n int) string {
	if n == 0 {
		return "NULL"
	}
	return strings.TrimSuffix(strings.Repeat("?,", n), ",")
}

func int64Args(xs []int64) []any {
	out := make([]any, len(xs))
	for i, x := range xs {
		out[i] = x
	}
	return out
}
