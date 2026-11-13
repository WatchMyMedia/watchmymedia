package dvr

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/growing"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/hdhomerun"
	"watchmymedia/server/internal/livetv/hdhomerun/hdhrtest"
	"watchmymedia/server/internal/settings"
)

type fixture struct {
	s        *Service
	conn     *sql.DB
	q        *db.Queries
	live     *livetv.Service
	fake     *hdhrtest.Tuner
	actor    Actor
	channels []livetv.Channel
	lib      db.Library
	clock    *atomic.Int64
}

func setup(t testing.TB, stream http.HandlerFunc) fixture {
	return setupServer(t, stream, httptest.NewServer)
}
func setupServer(t testing.TB, stream http.HandlerFunc, start func(http.Handler) *httptest.Server) fixture {
	t.Helper()
	conn, q := dbtest.Open(t)
	fake := hdhrtest.New(stream)
	server := start(fake)
	t.Cleanup(server.Close)
	store := settings.New(q)
	live := livetv.New(q, store, func(a string) (*hdhomerun.Client, error) {
		return hdhomerun.New(a, hdhrtest.Transport(server.URL, server.Client().Transport))
	})
	_, err := live.Add(t.Context(), "tuner.test", "")
	if err != nil {
		t.Fatal(err)
	}
	channels, err := live.Channels(t.Context(), false)
	if err != nil {
		t.Fatal(err)
	}
	user, err := q.CreateUser(t.Context(), db.CreateUserParams{Name: "Casey", PasswordHash: "unused", IsAdmin: 1})
	if err != nil {
		t.Fatal(err)
	}
	lib, err := q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "tv", Name: "Television", Path: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	clock := &atomic.Int64{}
	clock.Store(time.Date(2026, 11, 4, 20, 0, 0, 0, time.UTC).Unix())
	s := New(q, store, live, nil, Options{Now: func() time.Time { return unix(clock.Load()) }, FreeBytes: func(string) (uint64, error) { return 1_000_000_000_000, nil }, Bus: events.NewBus()})
	t.Cleanup(s.Close)
	if err = s.SetSettings(t.Context(), Settings{Enabled: true, Access: "admins", TVLibraryID: lib.ID, TVPath: lib.Path, WarningSeconds: 120}); err != nil {
		t.Fatal(err)
	}
	return fixture{s, conn, q, live, fake, Actor{UserID: user.ID, Name: user.Name, Admin: true}, channels, lib, clock}
}

func TestRunTimerFinishesRecordingWithoutPollingOrAnotherMutation(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		f := setupServer(t, func(w http.ResponseWriter, r *http.Request) {
			for {
				_, _ = w.Write(bytes.Repeat([]byte{0x47}, 188))
				_ = http.NewResponseController(w).Flush()
				select {
				case <-r.Context().Done():
					return
				case <-time.After(time.Second):
				}
			}
		}, func(h http.Handler) *httptest.Server { return httptest.NewTestServer(t, h) })
		f.s.opts.Now = time.Now
		start := time.Now()
		r, err := f.s.Manual(t.Context(), f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(30 * time.Second), Repeat: "once", Zone: "UTC", Name: "Timer report"})
		if err != nil {
			t.Fatal(err)
		}
		ctx, cancel := context.WithCancel(t.Context())
		done := make(chan struct{})
		go func() { defer close(done); f.s.Run(ctx) }()
		synctest.Wait()
		time.Sleep(35 * time.Second)
		synctest.Wait()
		got, err := f.s.Get(t.Context(), r.ID, f.actor)
		if err != nil || got.State != "done" || got.Bytes == 0 {
			t.Fatalf("timer failed to finalize: %+v %v", got, err)
		}
		cancel()
		<-done
	})
}

func TestRestartAppendsSamePartialAndKeepsTheGap(t *testing.T) {
	t.Parallel()
	tuner := newFeed()
	chunks := tuner.chunks
	f := setup(t, tuner.serve)
	ctx, cancel := context.WithTimeout(t.Context(), 15*time.Second)
	defer cancel()
	start := unix(f.clock.Load())
	r, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(3 * time.Minute), Repeat: "once", Zone: "UTC", Name: "Restart report"})
	if err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, start)
	source, err := f.s.Source(ctx, r.ID)
	if err != nil {
		t.Fatal(err)
	}
	first, err := growing.Open(ctx, source.Path, 0, source.Done)
	if err != nil {
		t.Fatal(err)
	}
	packet := bytes.Repeat([]byte{0x47}, 188)
	select {
	case chunks <- packet:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	got := make([]byte, len(packet))
	if _, err = io.ReadFull(first, got); err != nil {
		t.Fatal(err)
	}
	_ = first.Close()
	f.s.Close()
	f.clock.Add(30)
	resumed := New(f.q, f.s.store, f.live, nil, f.s.opts)
	t.Cleanup(resumed.Close)
	resumed.tick(ctx, unix(f.clock.Load()))
	active, err := resumed.Get(ctx, r.ID, f.actor)
	if err != nil || active.State != "recording" || active.EndedAt != nil {
		t.Fatalf("resumed timeline must still grow: %+v %v", active, err)
	}
	src, err := resumed.Source(ctx, r.ID)
	if err != nil || src.Path != source.Path {
		t.Fatal(src, err)
	}
	reader, err := growing.Open(ctx, src.Path, 0, src.Done)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	if _, err = io.ReadFull(reader, got); err != nil {
		t.Fatal(err)
	}
	// Send only once the resumed recorder has opened its own stream.
	for range 2 {
		select {
		case <-tuner.started:
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		}
	}
	select {
	case chunks <- packet:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	if _, err = io.ReadFull(reader, got); err != nil {
		t.Fatal(err)
	}
	f.clock.Store(start.Add(4 * time.Minute).Unix())
	resumed.tick(ctx, unix(f.clock.Load()))
	result, err := resumed.get(ctx, r.ID)
	if err != nil || result.State != "partial" || result.Bytes != int64(2*len(packet)) {
		t.Fatalf("restart=%+v %v", result, err)
	}
	if len(result.Gaps) == 0 || result.Gaps[0].Reason != "server_restarted" {
		t.Fatal(result.Gaps)
	}
	bytesOnDisk, err := os.ReadFile(result.Path)
	if err != nil || len(bytesOnDisk) != 2*len(packet) {
		t.Fatal(len(bytesOnDisk), err)
	}
}
func (f fixture) programme(t testing.TB, id string, channel int, offset int64, series string, isNew bool) {
	t.Helper()
	row := db.InsertProgrammeParams{ID: id, ChannelID: f.channels[channel].ID, StartAt: f.clock.Load() + offset, EndAt: f.clock.Load() + offset + 3600, Title: "Inspector Vane", EpisodeTitle: id, SeriesKey: series, Season: new(int64(1)), Episode: new(offset/3600 + 1), Categories: "[]", Kind: "episode"}
	if isNew {
		row.IsNew = 1
	}
	if err := f.q.InsertProgramme(t.Context(), row); err != nil {
		t.Fatal(err)
	}
}
func TestSettingsPermissionsSchedulingAndKeyset(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	if _, err := f.s.Manual(ctx, Actor{UserID: f.actor.UserID}, Manual{}); !errors.Is(err, ErrDenied) {
		t.Fatal(err)
	}
	f.programme(t, "first", 0, 300, "series", true)
	r, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "first", Scope: "once"})
	if err != nil {
		t.Fatal(err)
	}
	again, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "first", Scope: "once"})
	if err != nil || again.ID != r.ID {
		t.Fatalf("idempotent=%v %v", again, err)
	}
	if err = f.s.Cancel(ctx, r.ID, Actor{UserID: 999}); !errors.Is(err, ErrDenied) {
		t.Fatal(err)
	}
	if _, err = f.s.List(ctx, f.actor, "upcoming", "broken"); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	for i := range 25 {
		start := unix(f.clock.Load()).Add(time.Duration(i+2) * time.Hour)
		_, err = f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(time.Minute), Repeat: "once", Zone: "UTC", Name: "Evening update"})
		if err != nil {
			t.Fatal(err)
		}
	}
	page, err := f.s.List(ctx, f.actor, "upcoming", "")
	if err != nil || len(page.Items) != 20 || page.NextCursor == "" {
		t.Fatalf("page %d %q %v", len(page.Items), page.NextCursor, err)
	}
	next, err := f.s.List(ctx, f.actor, "upcoming", page.NextCursor)
	if err != nil || len(next.Items) != 6 {
		t.Fatalf("next=%d %v", len(next.Items), err)
	}
	for _, r := range next.Items {
		for _, first := range page.Items {
			if r.ID == first.ID {
				t.Fatal("duplicate keyset row")
			}
		}
	}
	if err = f.q.DeleteUser(ctx, f.actor.UserID); err != nil {
		t.Fatal(err)
	}
	got, err := f.s.Get(ctx, r.ID, Actor{Admin: true})
	if err != nil || got.UserID != nil {
		t.Fatalf("deleted actor=%+v %v", got, err)
	}
}
func TestSeriesExpansionFollowsRefreshAndKeepsCancellation(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	f.programme(t, "pilot", 0, 300, "vane", true)
	f.programme(t, "repeat", 0, 3900, "vane", false)
	f.programme(t, "other", 1, 7500, "vane", true)
	r, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "pilot", Scope: "series"})
	if err != nil {
		t.Fatal(err)
	}
	rules, err := f.s.List(ctx, f.actor, "series", "")
	if err != nil || len(rules.Rules) != 1 {
		t.Fatal(rules, err)
	}
	list, err := f.s.List(ctx, f.actor, "upcoming", "")
	if err != nil || len(list.Items) != 1 {
		t.Fatal(list, err)
	}
	if err = f.s.Cancel(ctx, r.ID, f.actor); err != nil {
		t.Fatal(err)
	}
	any := true
	if _, err = f.s.UpdateRule(ctx, r.RuleID, f.actor, UpdateRule{AnyChannel: &any}); err != nil {
		t.Fatal(err)
	}
	list, err = f.s.List(ctx, f.actor, "upcoming", "")
	if err != nil || len(list.Items) != 1 || list.Items[0].ProgrammeID != "other" {
		t.Fatal(list, err)
	}
	if err = f.s.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	cancelled, err := f.s.Get(ctx, r.ID, f.actor)
	if err != nil || cancelled.State != "cancelled" {
		t.Fatal(cancelled, err)
	}
	// Listing identity is the episode, not merely the old channel/start id.
	if _, err = f.conn.ExecContext(ctx, "UPDATE live_tv_programmes SET start_at=start_at+600,end_at=end_at+600,id='moved' WHERE id='other'"); err != nil {
		t.Fatal(err)
	}
	if err = f.s.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	list, err = f.s.List(ctx, f.actor, "upcoming", "")
	if err != nil || len(list.Items) != 1 || list.Items[0].ProgrammeID != "moved" {
		t.Fatal(list, err)
	}
	if err = f.s.DeleteRule(ctx, r.RuleID, f.actor); err != nil {
		t.Fatal(err)
	}
	list, err = f.s.List(ctx, f.actor, "upcoming", "")
	if err != nil || len(list.Items) != 0 {
		t.Fatal(list, err)
	}
}

func TestNewOnlyNeverOverridesSkipForTheSelectedRepeat(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	f.programme(t, "repeat", 0, 300, "vane", false)
	f.programme(t, "new-episode", 0, 3900, "vane", true)
	r, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "repeat", Scope: "series"})
	if err != nil || r.State != "skipped" || r.Reason != "not_new" {
		t.Fatalf("selected repeat=%+v %v", r, err)
	}
	page, err := f.s.List(ctx, f.actor, "upcoming", "")
	if err != nil || len(page.Items) != 1 || page.Items[0].ProgrammeID != "new-episode" {
		t.Fatal(page, err)
	}
	// Explicitly recording just this episode is a separate user choice.
	once, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "repeat", Scope: "once"})
	if err != nil || once.State != "scheduled" {
		t.Fatal(once, err)
	}
	// Changing the rule to every episode revives its filtered row; narrowing
	// it again must remove that repeat from the pending schedule.
	_, err = f.s.UpdateRule(ctx, r.RuleID, f.actor, UpdateRule{NewOnly: new(false)})
	if err != nil {
		t.Fatal(err)
	}
	_, err = f.s.UpdateRule(ctx, r.RuleID, f.actor, UpdateRule{NewOnly: new(true)})
	if err != nil {
		t.Fatal(err)
	}
	row, err := f.s.get(ctx, r.ID)
	if err != nil || row.State != "skipped" {
		t.Fatal(row, err)
	}
}

func TestManualRetriesReuseOnlyTheSameActiveIntent(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	start := unix(f.clock.Load()).Add(time.Hour)
	in := Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(time.Hour), Name: "Evening update", Repeat: "once", Zone: "UTC"}
	first, err := f.s.Manual(ctx, f.actor, in)
	if err != nil {
		t.Fatal(err)
	}
	again, err := f.s.Manual(ctx, f.actor, in)
	if err != nil || again.ID != first.ID {
		t.Fatal(first, again, err)
	}
	if err = f.s.Cancel(ctx, first.ID, f.actor); err != nil {
		t.Fatal(err)
	}
	deliberate, err := f.s.Manual(ctx, f.actor, in)
	if err != nil || deliberate.ID == first.ID {
		t.Fatal(deliberate, err)
	}
	in.Repeat = "daily"
	rule, err := f.s.Manual(ctx, f.actor, in)
	if err != nil || rule.RuleID == "" {
		t.Fatal(rule, err)
	}
	again, err = f.s.Manual(ctx, f.actor, in)
	if err != nil || again.ID != rule.ID {
		t.Fatal(rule, again, err)
	}
	in.Name = "A different programme"
	different, err := f.s.Manual(ctx, f.actor, in)
	if err != nil || different.ID == rule.ID {
		t.Fatal(different, err)
	}
}
func TestRecorderSharesUpstreamKeepsLiveViewerAndOwnsDeletion(t *testing.T) {
	t.Parallel()
	chunks := make(chan []byte)
	f := setup(t, func(w http.ResponseWriter, r *http.Request) {
		for {
			select {
			case <-r.Context().Done():
				return
			case b := <-chunks:
				_, _ = w.Write(b)
				_ = http.NewResponseController(w).Flush()
			}
		}
	})
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	start := unix(f.clock.Load())
	r1, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(time.Minute), Repeat: "once", Zone: "UTC", Name: "Morning report"})
	if err != nil {
		t.Fatal(err)
	}
	r2, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(2 * time.Minute), Repeat: "once", Zone: "UTC", Name: "Weather"})
	if err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, start)
	source, err := f.s.Source(ctx, r1.ID)
	if err != nil {
		t.Fatal(err)
	}
	reader, err := growing.Open(ctx, source.Path, 0, source.Done)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	data := bytes.Repeat([]byte{0x47, 0, 0, 0}, 188)
	select {
	case chunks <- data:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	got := make([]byte, len(data))
	if _, err = io.ReadFull(reader, got); err != nil || !bytes.Equal(data, got) {
		t.Fatalf("broadcast copy=%d %v", len(got), err)
	}
	live, err := f.s.LiveInput(ctx, f.channels[0].ID)
	if err != nil {
		t.Fatal(err)
	}
	defer live.Close()
	f.clock.Store(start.Add(3 * time.Minute).Unix())
	f.s.tick(ctx, unix(f.clock.Load()))
	for _, r := range []Recording{r1, r2} {
		got, err := f.s.get(ctx, r.ID)
		if err != nil || got.State != "done" || got.Bytes != int64(len(data)) {
			t.Fatalf("finished=%+v %v", got, err)
		}
		if finalInfo, statErr := os.Stat(got.Path); statErr != nil || got.FileMtimeNano != finalInfo.ModTime().UnixNano() {
			t.Fatalf("persisted fingerprint differs from post-close recording: got=%d stat=%v err=%v", got.FileMtimeNano, finalInfo, statErr)
		}
		if _, err = os.Stat(got.PartialPath); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("partial survived", err)
		}
	}
	// A joined source lease is dormant until it reads, and drops the bytes
	// that come before. Activate it before the chunk is sent: on a loaded
	// runner the chunk reached the capture before this test's Read began,
	// passed the viewer by, and the Read waited out the deadline.
	live.(*liveReader).active.Store(true)
	select {
	case chunks <- data:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	if _, err = io.ReadFull(live, got); err != nil {
		t.Fatal(err)
	}
	_, peak, opens := f.fake.Counts()
	if peak != 1 || opens != 1 {
		t.Fatalf("tuner peak=%d opens=%d", peak, opens)
	}
	_ = live.Close()
	row, err := f.s.get(ctx, r1.ID)
	if err != nil {
		t.Fatal(err)
	}
	// A symlink substituted after recording is never removed by ownership.
	original := row.Path + ".original"
	if err = os.Rename(row.Path, original); err != nil {
		t.Fatal(err)
	}
	if err = os.Symlink(original, row.Path); err != nil {
		t.Fatal(err)
	}
	if _, err = f.s.Source(ctx, row.ID); !errors.Is(err, ErrUnavailable) {
		t.Fatal("served a substituted symlink", err)
	}
	if err = f.s.deleteOwned(ctx, row, false); !errors.Is(err, ErrInvalid) {
		t.Fatal("deleted replaced symlink", err)
	}
	if err = os.Remove(row.Path); err != nil {
		t.Fatal(err)
	}
	if err = os.Rename(original, row.Path); err != nil {
		t.Fatal(err)
	}
	if err = f.s.deleteOwned(ctx, row, false); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(row.Path); !errors.Is(err, os.ErrNotExist) {
		t.Fatal(err)
	}
}

func TestCancelRacingStartCannotBeResurrected(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	now := unix(f.clock.Load())
	for i := range 8 {
		r, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: now, End: now.Add(time.Minute), Repeat: "once", Zone: "UTC", Name: fmt.Sprintf("Race %d", i)})
		if err != nil {
			t.Fatal(err)
		}
		raw, err := f.s.get(ctx, r.ID)
		if err != nil {
			t.Fatal(err)
		}
		var wg sync.WaitGroup
		errs := make(chan error, 1)
		wg.Go(func() { f.s.begin(ctx, raw, false) })
		wg.Go(func() { errs <- f.s.Cancel(ctx, r.ID, f.actor) })
		wg.Wait()
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
		f.s.tick(ctx, now)
		result, err := f.s.Get(ctx, r.ID, f.actor)
		if err != nil || result.State == "recording" || result.State == "scheduled" {
			t.Fatal(result, err)
		}
	}
}
func TestNamesContainmentAndExpectedDiskBudget(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	r := record{Title: "../CON: Orchard?", EpisodeTitle: "A/B", Start: unix(f.clock.Load()), Season: new(1), Episode: new(2)}
	path, err := f.s.recordingPath(ctx, f.lib, r)
	if err != nil {
		t.Fatal(err)
	}
	if !filepath.IsLocal(mustRel(t, f.lib.Path, path)) {
		t.Fatal(path)
	}
	escape := filepath.Join(f.lib.Path, "escape")
	if err = os.Symlink(t.TempDir(), escape); err != nil {
		t.Fatal(err)
	}
	if err = contained(f.lib.Path, filepath.Join(escape, "file.ts")); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	f.s.opts.FreeBytes = func(string) (uint64, error) { return 10, nil }
	start := unix(f.clock.Load())
	rec, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(time.Minute), Repeat: "once", Zone: "UTC", Name: "News"})
	if err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, start)
	got, err := f.s.Get(ctx, rec.ID, f.actor)
	if err != nil || got.State != "failed" || got.Reason != "disk_full" {
		t.Fatal(got, err)
	}
}
func mustRel(t testing.TB, a, b string) string {
	t.Helper()
	r, err := filepath.Rel(a, b)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestMovieRecordingRequiresItsOwnLibraryKind(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	f.programme(t, "film", 0, 300, "", true)
	if _, err := f.conn.ExecContext(t.Context(), "UPDATE live_tv_programmes SET kind='movie' WHERE id='film'"); err != nil {
		t.Fatal(err)
	}
	if _, err := f.s.Create(t.Context(), f.actor, Create{ProgrammeID: "film", Scope: "once"}); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("film without Movies library: %v", err)
	}
	movies, err := f.q.UpsertLibrary(t.Context(), db.UpsertLibraryParams{Type: "movie", Name: "Movies", Path: t.TempDir()})
	if err != nil {
		t.Fatal(err)
	}
	settings, err := f.s.Settings(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	settings.MovieLibraryID, settings.MoviePath = movies.ID, movies.Path
	if err = f.s.SetSettings(t.Context(), settings); err != nil {
		t.Fatal(err)
	}
	if _, err = f.s.Create(t.Context(), f.actor, Create{ProgrammeID: "film", Scope: "once"}); err != nil {
		t.Fatal(err)
	}
}
