package activity

import (
	"context"
	"database/sql"
	"time"

	"watchmymedia/server/internal/db"
)

// UserSummary is a row of the Users tab.
type UserSummary struct {
	User          UserRef     `json:"user"`
	IsAdmin       bool        `json:"isAdmin"`
	RecordHistory bool        `json:"recordHistory"`
	Playing       int         `json:"playing" doc:"Plays running now."`
	LastSeenAt    *time.Time  `json:"lastSeenAt,omitempty"`
	LastPlayed    *HistoryRow `json:"lastPlayed,omitempty"`
	Plays         int64       `json:"plays" doc:"All time."`
	PlayedMs      int64       `json:"playedMs" doc:"All time."`
	Devices       int64       `json:"devices"`
}

// Window is watch time over a recent period.
type Window struct {
	Label    string `json:"label"`
	Days     int    `json:"days" doc:"0 is all time."`
	Plays    int64  `json:"plays"`
	PlayedMs int64  `json:"playedMs"`
}

// DeviceSummary is a device a user has played on.
type DeviceSummary struct {
	Device      Device    `json:"device"`
	LastIP      string    `json:"lastIp"`
	FirstSeenAt time.Time `json:"firstSeenAt"`
	LastSeenAt  time.Time `json:"lastSeenAt"`
	Plays       int64     `json:"plays"`
}

// UserDetail is a user's page.
type UserDetail struct {
	UserSummary
	Windows   []Window        `json:"windows" nullable:"false"`
	DeviceSet []DeviceSummary `json:"deviceList" nullable:"false"`
	TopShows  []StatItem      `json:"topShows" nullable:"false"`
	TopMovies []StatItem      `json:"topMovies" nullable:"false"`
}

// Users summarises every account's activity.
func (t *Tracker) Users(ctx context.Context) ([]UserSummary, error) {
	out := []UserSummary{}
	if t.db == nil {
		return out, nil
	}
	rows, err := readAll(ctx, t.db, func(rows *sql.Rows, s *UserSummary) error {
		var admin, record int64
		var seen sql.NullString
		if err := rows.Scan(&s.User.ID, &s.User.Name, &admin, &record, &s.Plays, &s.PlayedMs, &s.Devices, &seen); err != nil {
			return err
		}
		s.IsAdmin, s.RecordHistory = admin == 1, record == 1
		if seen.Valid {
			s.LastSeenAt = db.ParseTimePtr(&seen.String)
		}
		return nil
	}, `
		SELECT u.id, u.name, u.is_admin, u.record_history, COALESCE(a.plays, 0), COALESCE(a.ms, 0), COALESCE(d.n, 0), d.seen
		FROM users u
		LEFT JOIN (SELECT user_id, SUM(plays) AS plays, SUM(played_ms) AS ms FROM activity_monthly GROUP BY user_id) a ON a.user_id = u.id
		LEFT JOIN (SELECT user_id, COUNT(*) AS n, MAX(last_seen_at) AS seen FROM devices GROUP BY user_id) d ON d.user_id = u.id
		ORDER BY u.name COLLATE NOCASE`)
	if err != nil {
		return nil, err
	}
	out = append(out, rows...)
	live := map[int64]int{}
	t.mu.Lock()
	for _, p := range t.plays {
		live[p.userID]++
	}
	t.mu.Unlock()
	now := t.now()
	for i := range out {
		s := &out[i]
		s.Playing = live[s.User.ID]
		if s.Playing > 0 {
			s.LastSeenAt = &now
		}
		last, err := t.History(ctx, Filter{UserID: s.User.ID, Limit: 1, noTotal: true})
		if err != nil {
			return nil, err
		}
		if len(last.Items) > 0 {
			s.LastPlayed = &last.Items[0]
			if s.LastSeenAt == nil || last.Items[0].StoppedAt.After(*s.LastSeenAt) {
				at := last.Items[0].StoppedAt
				s.LastSeenAt = &at
			}
		}
	}
	return out, nil
}

// User returns one account's activity, or sql.ErrNoRows.
func (t *Tracker) User(ctx context.Context, id int64) (UserDetail, error) {
	var d UserDetail
	all, err := t.Users(ctx)
	if err != nil {
		return d, err
	}
	found := false
	for _, s := range all {
		if s.User.ID == id {
			d.UserSummary, found = s, true
		}
	}
	if !found {
		return d, sql.ErrNoRows
	}
	d.Windows, d.DeviceSet, d.TopShows, d.TopMovies = []Window{}, []DeviceSummary{}, []StatItem{}, []StatItem{}
	if t.db == nil {
		return d, nil
	}
	now := t.now()
	for _, w := range []struct {
		label string
		days  int
		since time.Time
	}{{"Last 24 hours", 1, now.Add(-24 * time.Hour)}, {"Last 7 days", 7, now.AddDate(0, 0, -7)}, {"Last 30 days", 30, now.AddDate(0, 0, -30)}} {
		win := Window{Label: w.label, Days: w.days}
		if err := t.db.QueryRowContext(ctx, `
			SELECT COALESCE(SUM(group_latest), 0), COALESCE(SUM(played_ms), 0) FROM plays WHERE user_id = ? AND started_at >= ?`,
			id, db.FormatTime(w.since)).Scan(&win.Plays, &win.PlayedMs); err != nil {
			return d, err
		}
		d.Windows = append(d.Windows, win)
	}
	d.Windows = append(d.Windows, Window{Label: "All time", Plays: d.Plays, PlayedMs: d.PlayedMs})

	devices, err := readAll(ctx, t.db, func(rows *sql.Rows, s *DeviceSummary) error {
		var first, last string
		if err := rows.Scan(&s.Device.ID, &s.Device.Name, &s.Device.Product, &s.Device.Platform, &s.Device.Version, &s.LastIP, &first, &last, &s.Plays); err != nil {
			return err
		}
		s.FirstSeenAt, s.LastSeenAt = db.ParseTime(first), db.ParseTime(last)
		return nil
	}, `
		SELECT d.id, d.name, d.product, d.platform, d.version, d.last_ip, d.first_seen_at, d.last_seen_at,
			(SELECT COUNT(*) FROM plays p WHERE p.device_id = d.id AND p.group_latest = 1)
		FROM devices d WHERE d.user_id = ? ORDER BY d.last_seen_at DESC`, id)
	if err != nil {
		return d, err
	}
	d.DeviceSet = append(d.DeviceSet, devices...)
	cards, err := t.Cards(ctx, StatsQuery{UserID: id})
	if err != nil {
		return d, err
	}
	d.TopShows, d.TopMovies = cards.TopShows, cards.TopMovies
	return d, nil
}
