package guide

import (
	"database/sql"
	"errors"
	"fmt"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/guide/guidetest"
)

// user adds a person, for favourites and reminders to belong to.
func (e *env) user(name string) int64 {
	e.t.Helper()
	u, err := e.q.CreateUser(e.ctx, db.CreateUserParams{Name: name, PasswordHash: "x", IsAdmin: 0})
	if err != nil {
		e.t.Fatal(err)
	}
	return u.ID
}

func (e *env) channelIDs() []string {
	e.t.Helper()
	channels, err := e.live.Channels(e.ctx, false)
	if err != nil {
		e.t.Fatal(err)
	}
	ids := make([]string, 0, len(channels))
	for _, c := range channels {
		ids = append(ids, c.ID)
	}
	return ids
}

// refresh fetches a feed that starts at start.
func (e *env) refresh(start time.Time) {
	e.t.Helper()
	e.sd.Feed = guidetest.Feed(start, guidetest.StandardChannels)
	e.g.Fetch(e.ctx)
	if st := e.status(); st.State != StateOK {
		e.t.Fatalf("fetch: %s", st.State)
	}
}

func TestSearchListings(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.refresh(e.start)
	ids := e.channelIDs()

	hits, err := e.g.Search(e.ctx, ids, "sleuth low", 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(hits) == 0 {
		t.Fatal("no hits for The Sleuth Files' The Low Tide")
	}
	for i, h := range hits {
		if h.Programme.Title != "The Sleuth Files" || !strings.Contains(h.Programme.EpisodeTitle, "Low") {
			t.Errorf("hit %d: %q %q", i, h.Programme.Title, h.Programme.EpisodeTitle)
		}
		if !h.Programme.End.After(time.Now()) {
			t.Errorf("hit %d already ended", i)
		}
		if i > 0 && h.Programme.Start.Before(hits[i-1].Programme.Start) {
			t.Errorf("hits out of order at %d", i)
		}
	}
	// Case and accents' forms don't matter, whichever way either is written.
	for _, q := range []string{"maiz", "MAÍZ", norm.NFD.String("maíz")} {
		hits, err := e.g.Search(e.ctx, ids, q, 50)
		if err != nil {
			t.Fatal(err)
		}
		if len(hits) == 0 || hits[0].Programme.EpisodeTitle != "Pan de maíz" {
			t.Errorf("%q: %+v", q, hits)
		}
	}
	if hits, _ := e.g.Search(e.ctx, ids, "sleuth", 2); len(hits) != 2 {
		t.Errorf("limit 2 gave %d", len(hits))
	}
	if hits, _ := e.g.Search(e.ctx, ids, "nothing listed like this", 50); len(hits) != 0 {
		t.Errorf("nonsense found %d", len(hits))
	}
	// Only the channels given, and only those whose listings show.
	sleuth := e.channel("7.1")
	if hits, _ := e.g.Search(e.ctx, []string{e.channel("12.2").ID}, "low tide", 50); len(hits) != 0 {
		t.Errorf("another channel's programme found: %+v", hits[0])
	}
	if err := e.g.SetChannelListings(e.ctx, sleuth.ID, false); err != nil {
		t.Fatal(err)
	}
	if hits, _ := e.g.Search(e.ctx, ids, "low tide", 50); len(hits) != 0 {
		t.Errorf("a channel with listings off searched: %+v", hits[0])
	}
}

func TestSearchBackfillsOlderListings(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.refresh(e.start)
	// As if listed before the search column existed.
	conn, err := db.Open(e.dbPath)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = conn.Close() }()
	if _, err := conn.ExecContext(e.ctx, `UPDATE live_tv_programmes SET search = ''`); err != nil {
		t.Fatal(err)
	}
	if hits, _ := e.g.Search(e.ctx, e.channelIDs(), "sleuth", 50); len(hits) != 0 {
		t.Fatal("found before the backfill")
	}
	if err := e.g.backfillSearch(e.ctx); err != nil {
		t.Fatal(err)
	}
	if hits, _ := e.g.Search(e.ctx, e.channelIDs(), "sleuth", 50); len(hits) == 0 {
		t.Fatal("not found after the backfill")
	}
}

func TestFavourites(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	ann, bo := e.user("Ann"), e.user("Bo")
	if err := e.g.SetFavourite(e.ctx, ann, "7.1", true); err != nil {
		t.Fatal(err)
	}
	if err := e.g.SetFavourite(e.ctx, ann, "7.1", true); err != nil {
		t.Fatal("starring twice:", err)
	}
	if err := e.g.SetFavourite(e.ctx, ann, "22.1", true); err != nil {
		t.Fatal(err)
	}
	if f, _ := e.g.Favourites(e.ctx, ann); len(f) != 2 || !f["7.1"] || !f["22.1"] {
		t.Errorf("Ann's: %v", f)
	}
	if f, _ := e.g.Favourites(e.ctx, bo); len(f) != 0 {
		t.Errorf("Bo's: %v", f)
	}
	if err := e.g.SetFavourite(e.ctx, ann, "7.1", false); err != nil {
		t.Fatal(err)
	}
	if f, _ := e.g.Favourites(e.ctx, ann); len(f) != 1 || !f["22.1"] {
		t.Errorf("after unstarring: %v", f)
	}
}

// laterEpisode is a numbered episode on 7.1 starting between 2 and 4 hours
// after start.
func (e *env) laterEpisode() livetv.Programme {
	e.t.Helper()
	for _, p := range e.programmes("7.1") {
		if p.Season != nil && p.Episode != nil && p.Start.After(e.start.Add(2*time.Hour)) && p.Start.Before(e.start.Add(4*time.Hour)) {
			return p
		}
	}
	e.t.Fatal("no numbered episode on 7.1 2 to 4 hours on")
	return livetv.Programme{}
}

func (e *env) reminder(userID int64) db.LiveTvReminder {
	e.t.Helper()
	rows, err := e.g.Reminders(e.ctx, userID)
	if err != nil || len(rows) != 1 {
		e.t.Fatalf("reminders: %+v %v", rows, err)
	}
	return rows[0]
}

func TestReminders(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.refresh(e.start)
	ann, bo := e.user("Ann"), e.user("Bo")
	p := e.laterEpisode()

	r, err := e.g.Remind(e.ctx, ann, p.ID)
	if err != nil {
		t.Fatal(err)
	}
	if r.Number != "7.1" || r.Title != p.Title || r.StartAt != p.Start.Unix() || r.State != ReminderSet || r.Season == nil {
		t.Fatalf("reminder: %+v", r)
	}
	if again, err := e.g.Remind(e.ctx, ann, p.ID); err != nil || again.ID != r.ID {
		t.Fatalf("again: %+v %v", again, err)
	}
	if rows, _ := e.g.Reminders(e.ctx, bo); len(rows) != 0 {
		t.Errorf("Bo has Ann's reminder")
	}
	if _, err := e.g.Remind(e.ctx, ann, "no-such-programme"); !errors.Is(err, ErrProgramme) {
		t.Errorf("unknown programme: %v", err)
	}
	var onNow livetv.Programme
	for _, q := range e.programmes("7.1") {
		if !q.Start.After(time.Now()) && q.End.After(time.Now()) {
			onNow = q
		}
	}
	if _, err := e.g.Remind(e.ctx, ann, onNow.ID); !errors.Is(err, ErrStarted) {
		t.Errorf("started programme: %v", err)
	}
	// Bo removing Ann's reminder does nothing; Ann removing it does.
	if err := e.g.Unremind(e.ctx, bo, r.ID); err != nil {
		t.Fatal(err)
	}
	e.reminder(ann)
	if err := e.g.Unremind(e.ctx, ann, r.ID); err != nil {
		t.Fatal(err)
	}
	if rows, _ := e.g.Reminders(e.ctx, ann); len(rows) != 0 {
		t.Errorf("removed reminder still listed")
	}
	if err := e.g.Unremind(e.ctx, ann, r.ID); err != nil {
		t.Errorf("removing it again: %v", err)
	}
}

func TestReminderLimit(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.refresh(e.start)
	ann := e.user("Ann")
	var later []livetv.Programme
	for _, number := range []string{"7.1", "12.2", "22.1", "42.3"} {
		for _, p := range e.programmes(number) {
			if p.Start.After(time.Now()) {
				later = append(later, p)
			}
		}
	}
	if len(later) <= MaxReminders {
		t.Skipf("the feed has only %d programmes to come", len(later))
	}
	for _, p := range later[:MaxReminders] {
		if _, err := e.g.Remind(e.ctx, ann, p.ID); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := e.g.Remind(e.ctx, ann, later[MaxReminders].ID); !errors.Is(err, ErrReminders) {
		t.Errorf("one more: %v", err)
	}
	if _, err := e.g.Remind(e.ctx, ann, later[0].ID); err != nil {
		t.Errorf("setting one again at the limit: %v", err)
	}
}

func TestRefreshFollowsReminders(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.refresh(e.start)
	ann := e.user("Ann")
	p := e.laterEpisode()
	if _, err := e.g.Remind(e.ctx, ann, p.ID); err != nil {
		t.Fatal(err)
	}
	e.g.bus = events.NewBus()
	ch, cancel := e.g.bus.Subscribe()
	defer cancel()

	// Everything runs half an hour later: the reminder follows its episode.
	e.refresh(e.start.Add(30 * time.Minute))
	r := e.reminder(ann)
	if r.State != ReminderMoved || r.StartAt != p.Start.Add(30*time.Minute).Unix() || r.ProgrammeID == p.ID {
		t.Fatalf("moved: %+v", r)
	}
	select {
	case ev := <-ch:
		if ev.Type != RemindersChangedEvent || ev.Data != (RemindersChanged{}) || ev.To == nil || ev.To.UserID != ann || !ev.To.Private {
			t.Errorf("event: %+v", ev)
		}
	default:
		t.Error("no event for the moved reminder")
	}

	// Four hours later: no such episode within three hours, so it is gone,
	// and it keeps its last time.
	e.refresh(e.start.Add(4 * time.Hour))
	if r := e.reminder(ann); r.State != ReminderGone || r.StartAt != p.Start.Add(30*time.Minute).Unix() {
		t.Fatalf("gone: %+v", r)
	}

	// Back where it was: found again.
	e.refresh(e.start)
	if r := e.reminder(ann); r.State != ReminderMoved || r.StartAt != p.Start.Unix() {
		t.Fatalf("back: %+v", r)
	}
	// The same listing again keeps it as it is.
	e.refresh(e.start)
	if r := e.reminder(ann); r.State != ReminderMoved || r.ProgrammeID != p.ID {
		t.Fatalf("unchanged: %+v", r)
	}
}

func TestRemindersOutliveTheTuner(t *testing.T) {
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.refresh(e.start)
	ann := e.user("Ann")
	if err := e.g.SetFavourite(e.ctx, ann, "7.1", true); err != nil {
		t.Fatal(err)
	}
	p := e.laterEpisode()
	if _, err := e.g.Remind(e.ctx, ann, p.ID); err != nil {
		t.Fatal(err)
	}
	sources, err := e.live.Sources(e.ctx)
	if err != nil || len(sources) != 1 {
		t.Fatal(sources, err)
	}
	if err := e.live.Remove(e.ctx, sources[0].ID); err != nil {
		t.Fatal(err)
	}
	// With the tuner gone a refresh has nothing to check them against.
	e.g.Fetch(e.ctx)
	if f, _ := e.g.Favourites(e.ctx, ann); !f["7.1"] {
		t.Error("favourite lost with the tuner")
	}
	if r := e.reminder(ann); r.State != ReminderSet {
		t.Errorf("reminder changed with no tuner: %+v", r)
	}
}

func queryPlan(t *testing.T, conn *sql.DB, query string, args ...any) string {
	t.Helper()
	rows, err := conn.QueryContext(t.Context(), "EXPLAIN QUERY PLAN "+query, args...)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rows.Close() }()
	var plan []string
	for rows.Next() {
		var id, parent, unused int
		var detail string
		if err := rows.Scan(&id, &parent, &unused, &detail); err != nil {
			t.Fatal(err)
		}
		plan = append(plan, detail)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return strings.Join(plan, "; ")
}

// The plans hold without statistics, which the server never gathers.
func TestPersonalQueriesUseTheirIndexes(t *testing.T) {
	t.Parallel()
	conn, _ := dbtest.Open(t)
	if p := queryPlan(t, conn, `SELECT * FROM live_tv_reminders INDEXED BY live_tv_reminder_user WHERE user_id = ? AND end_at > ? ORDER BY start_at, id`, 1, 0); !strings.Contains(p, "live_tv_reminder_user (user_id=?)") {
		t.Errorf("reminders: %s", p)
	}
	if p := queryPlan(t, conn, `SELECT * FROM live_tv_programmes INDEXED BY live_tv_programme_window WHERE start_at >= ? AND start_at <= ? AND channel_id IN (?, ?) ORDER BY start_at, channel_id`, 0, 10, "a", "b"); !strings.Contains(p, "live_tv_programme_window (channel_id=? AND start_at>? AND start_at<?)") {
		t.Errorf("near: %s", p)
	}
	if p := queryPlan(t, conn, `SELECT * FROM live_tv_reminders WHERE end_at > ? ORDER BY start_at, id`, 0); !strings.Contains(p, "live_tv_reminder_end") && !strings.Contains(p, "SCAN live_tv_reminders") {
		t.Errorf("upcoming: %s", p)
	}
}

// TestSearchSpeed searches 14 days of listings for 100 channels' worth of
// rows (about 70,000), which the LIKE over the folded column scans.
func TestSearchSpeed(t *testing.T) {
	if testing.Short() {
		t.Skip("speed budget")
	}
	t.Parallel()
	e := newEnv(t, httptest.NewServer)
	e.on()
	e.refresh(e.start)
	ids := e.channelIDs()
	const rows = 70000
	err := e.q.Transaction(e.ctx, func(q *db.Queries) error {
		at := e.start.Add(48 * time.Hour).Unix()
		for i := range rows {
			title := fmt.Sprintf("Filler Programme %d", i)
			if err := q.InsertProgramme(e.ctx, db.InsertProgrammeParams{
				ID: fmt.Sprintf("filler-%d", i), ChannelID: ids[i%len(ids)], StartAt: at + int64(i/len(ids))*1800, EndAt: at + int64(i/len(ids))*1800 + 1800,
				Title: title, Categories: "[]", Search: searchText(title, "An Episode"),
			}); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	began := time.Now()
	hits, err := e.g.Search(e.ctx, ids, "sleuth low", 50)
	took := time.Since(began)
	if err != nil || len(hits) == 0 {
		t.Fatalf("search: %d hits, %v", len(hits), err)
	}
	t.Logf("%d rows: %v", rows, took)
	if took > 500*time.Millisecond {
		t.Errorf("search took %v", took)
	}
}
