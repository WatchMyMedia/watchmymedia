package dvr

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/text/unicode/norm"
	"watchmymedia/server/internal/growing"
	"watchmymedia/server/internal/parser"
)

// streaming is a fixture whose tuner sends what the test puts on chunks.
func streaming(t *testing.T) (fixture, chan []byte) {
	t.Helper()
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
	return f, chunks
}

// feed is a fake tuner stream that hands each chunk to the newest request
// only. A stream the recorder closed can linger until the server notices;
// without this it could take a chunk meant for the stream that replaced it.
type feed struct {
	chunks  chan []byte
	pending chan []byte
	started chan struct{}
	gen     atomic.Int64
}

func newFeed() *feed {
	return &feed{chunks: make(chan []byte), pending: make(chan []byte, 8), started: make(chan struct{}, 8)}
}
func (f *feed) serve(w http.ResponseWriter, r *http.Request) {
	mine := f.gen.Add(1)
	f.started <- struct{}{}
	for {
		var b []byte
		select {
		case <-r.Context().Done():
			return
		case b = <-f.pending:
		case b = <-f.chunks:
		}
		if f.gen.Load() != mine {
			f.pending <- b
			return
		}
		_, _ = w.Write(b)
		_ = http.NewResponseController(w).Flush()
	}
}

var packet = bytes.Repeat([]byte{0x47}, 188)

// recordOnePacket starts r and waits until a packet is on disk.
func recordOnePacket(t *testing.T, ctx context.Context, f fixture, chunks chan []byte, id string) {
	t.Helper()
	f.s.tick(ctx, unix(f.clock.Load()))
	src, err := f.s.Source(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	reader, err := growing.Open(ctx, src.Path, 0, src.Done)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	select {
	case chunks <- packet:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	if _, err = io.ReadFull(reader, make([]byte, len(packet))); err != nil {
		t.Fatal(err)
	}
}

// Deleting a rule (ON DELETE SET NULL) while one of its recordings runs
// used to fail every later write of that recording's stale rule id: Stop
// answered 500 and the final state was never written.
func TestDeletingARuleWhileItRecordsKeepsTheRecordingStoppable(t *testing.T) {
	t.Parallel()
	f, chunks := streaming(t)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	start := unix(f.clock.Load())
	rec, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(3 * time.Minute), Repeat: "daily", Zone: "UTC", Name: "Morning report"})
	if err != nil || rec.RuleID == "" {
		t.Fatal(rec, err)
	}
	recordOnePacket(t, ctx, f, chunks, rec.ID)
	if err = f.s.DeleteRule(ctx, rec.RuleID, f.actor); err != nil {
		t.Fatal(err)
	}
	if err = f.s.Cancel(ctx, rec.ID, f.actor); err != nil {
		t.Fatal("stop after deleting the rule:", err)
	}
	f.s.tick(ctx, unix(f.clock.Load()))
	done, err := f.s.get(ctx, rec.ID)
	if err != nil || done.State != "partial" || done.Reason != "stopped" || done.RuleID != "" {
		t.Fatalf("%+v %v", done, err)
	}
	for _, g := range done.Gaps {
		if g.Reason == "server_restarted" {
			t.Fatal("finished as if the server had restarted", done.Gaps)
		}
	}
}

// A resume after a restart only needs the running floor, not room for the
// rest of the programme, and a resume that can't carry on publishes what
// was recorded instead of failing with the bytes hidden.
func TestResumeKeepsWhatWasRecorded(t *testing.T) {
	t.Parallel()
	for _, tc := range []struct {
		name string
		free func(string) (uint64, error)
		want string
	}{
		{"too little room for the rest", func(string) (uint64, error) { return 1_000_000, nil }, "recording"},
		{"disk unreadable", func(string) (uint64, error) { return 0, errors.New("unreadable") }, "partial"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			f, chunks := streaming(t)
			ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
			defer cancel()
			start := unix(f.clock.Load())
			rec, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(time.Hour), Repeat: "once", Zone: "UTC", Name: "Long report"})
			if err != nil {
				t.Fatal(err)
			}
			recordOnePacket(t, ctx, f, chunks, rec.ID)
			f.s.Close()
			f.clock.Add(60)
			opts := f.s.opts
			opts.FreeBytes = tc.free
			resumed := New(f.q, f.s.store, f.live, nil, opts)
			t.Cleanup(resumed.Close)
			resumed.tick(ctx, unix(f.clock.Load()))
			got, err := resumed.get(ctx, rec.ID)
			if err != nil || got.State != tc.want {
				t.Fatalf("%+v %v", got, err)
			}
			if tc.want == "partial" {
				if b, err := os.ReadFile(got.Path); err != nil || !bytes.Equal(b, packet) {
					t.Fatal("recorded bytes not published", err)
				}
			}
		})
	}
}

// Who may watch Live TV is the viewers' policy: changing it leaves a
// running recording recording.
func TestAccessChangeLeavesRecordingsRunning(t *testing.T) {
	t.Parallel()
	f, chunks := streaming(t)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	start := unix(f.clock.Load())
	rec, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(time.Hour), Repeat: "once", Zone: "UTC", Name: "Evening report"})
	if err != nil {
		t.Fatal(err)
	}
	recordOnePacket(t, ctx, f, chunks, rec.ID)
	if err = f.live.SetAccess(ctx, "everyone"); err != nil {
		t.Fatal(err)
	}
	f.s.tick(ctx, unix(f.clock.Load()))
	got, err := f.s.get(ctx, rec.ID)
	if err != nil || got.State != "recording" || f.s.Active() != 1 {
		t.Fatalf("%+v %v", got, err)
	}
}

// A gap runs from the last bytes written to the next, not from when the
// tuner was opened, and short hiccups aren't named.
func TestGapsRunFromTheLastBytes(t *testing.T) {
	t.Parallel()
	opened := time.Date(2026, 11, 4, 21, 0, 0, 0, time.UTC)
	out := &output{lastData: opened}
	out.lastData = opened.Add(45 * time.Minute)
	out.lose(opened.Add(46*time.Minute), "signal_lost")
	out.lose(opened.Add(47*time.Minute), "tuners_busy") // already open: unchanged
	out.closeGap(opened.Add(48 * time.Minute))
	if len(out.r.Gaps) != 1 || !out.r.Gaps[0].Start.Equal(opened.Add(45*time.Minute)) || !out.r.Gaps[0].End.Equal(opened.Add(48*time.Minute)) || out.r.Gaps[0].Reason != "signal_lost" {
		t.Fatal(out.r.Gaps)
	}
	out.lastData = opened.Add(50 * time.Minute)
	out.lose(opened.Add(50*time.Minute), "signal_lost")
	out.closeGap(opened.Add(50*time.Minute + time.Second))
	if len(out.r.Gaps) != 1 {
		t.Fatal("a one-second hiccup was named", out.r.Gaps)
	}
}

// While finish seals and renames, a tick must not start a second writer on
// the same file and a cancel must not overwrite the final state.
func TestAFinishingRecordingIsLeftToFinish(t *testing.T) {
	t.Parallel()
	f, chunks := streaming(t)
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	start := unix(f.clock.Load())
	rec, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: start, End: start.Add(time.Hour), Repeat: "once", Zone: "UTC", Name: "Late report"})
	if err != nil {
		t.Fatal(err)
	}
	recordOnePacket(t, ctx, f, chunks, rec.ID)
	f.s.mu.Lock()
	out := f.s.running[rec.ID]
	pipes := len(f.s.pipes)
	f.s.mu.Unlock()
	out.mu.Lock()
	out.finishing = true
	out.mu.Unlock()
	f.s.tick(ctx, unix(f.clock.Load()))
	if err = f.s.Cancel(ctx, rec.ID, f.actor); err != nil {
		t.Fatal(err)
	}
	f.s.mu.Lock()
	same := f.s.running[rec.ID] == out && len(f.s.pipes) == pipes
	f.s.mu.Unlock()
	if !same {
		t.Fatal("a second writer started while the first was finishing")
	}
	got, err := f.s.get(ctx, rec.ID)
	if err != nil || got.Reason == "stop_requested" {
		t.Fatalf("a cancel overwrote the finishing state: %+v %v", got, err)
	}
	out.mu.Lock()
	out.finishing = false
	out.mu.Unlock()
}

// Rows that can't record leave Upcoming once their time has passed, and a
// series drops an episode the listings drop.
func TestPastClashesAndDroppedEpisodesLeaveUpcoming(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	config, err := f.s.Settings(ctx)
	if err != nil {
		t.Fatal(err)
	}
	config.TunersReserved = 2
	if err = f.s.SetSettings(ctx, config); err != nil {
		t.Fatal(err)
	}
	f.programme(t, "lonely", 0, 300, "", true)
	once, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "lonely", Scope: "once"})
	if err != nil || once.State != "conflict" || once.Reason != "no_tuners" {
		t.Fatalf("no tuner left for recordings: %+v %v", once, err)
	}
	config.TunersReserved = 0
	if err = f.s.SetSettings(ctx, config); err != nil {
		t.Fatal(err)
	}
	f.programme(t, "pilot", 1, 7200, "vane", true)
	series, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "pilot", Scope: "series"})
	if err != nil || series.State != "scheduled" {
		t.Fatal(series, err)
	}
	if _, err = f.conn.ExecContext(ctx, "DELETE FROM live_tv_programmes WHERE id='pilot'"); err != nil {
		t.Fatal(err)
	}
	if err = f.s.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	dropped, err := f.s.get(ctx, series.ID)
	if err != nil || dropped.State != "skipped" || dropped.Reason != "listing_gone" {
		t.Fatalf("series episode the listings dropped: %+v %v", dropped, err)
	}
	// The once row is still conflict (planned before the change): once its
	// time passes it moves to Recorded as failed.
	if _, err = f.conn.ExecContext(ctx, "UPDATE live_tv_recordings SET state='conflict',section='conflicts' WHERE id=?", once.ID); err != nil {
		t.Fatal(err)
	}
	f.clock.Add(3 * 3600)
	f.s.tick(ctx, unix(f.clock.Load()))
	past, err := f.s.get(ctx, once.ID)
	if err != nil || past.State != "failed" {
		t.Fatalf("a past clash stayed in Upcoming: %+v %v", past, err)
	}
	pending, err := f.q.ListDVRPending(ctx)
	if err != nil || len(pending) != 0 {
		t.Fatal(len(pending), err)
	}
}

// A clash names the recordings holding the tuners, and choosing one to
// give way swaps only those two.
func TestAClashNamesItsHoldersAndOneGivesWay(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	config, err := f.s.Settings(ctx)
	if err != nil {
		t.Fatal(err)
	}
	config.TunersReserved = 1
	if err = f.s.SetSettings(ctx, config); err != nil {
		t.Fatal(err)
	}
	f.programme(t, "news", 0, 3600, "", true)
	f.programme(t, "arts", 1, 3600, "", true)
	news, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "news", Scope: "once"})
	if err != nil || news.State != "scheduled" {
		t.Fatal(news, err)
	}
	f.clock.Add(1) // set later, so it ranks after the news
	arts, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "arts", Scope: "once"})
	if err != nil || arts.State != "conflict" || len(arts.ConflictsWith) != 1 || arts.ConflictsWith[0].ID != news.ID || arts.ConflictsWith[0].Running {
		t.Fatalf("the clash should name the news: %+v %v", arts, err)
	}
	marks, err := f.s.Marks(ctx, []string{"arts"}, f.actor)
	if err != nil || marks["arts"].State != "conflict" || marks["arts"].Reason != "tuners_busy" {
		t.Fatal(marks["arts"], err)
	}
	if _, err = f.s.Update(ctx, arts.ID, f.actor, Update{Prefer: true, Over: arts.ID}); !errors.Is(err, ErrInvalid) {
		t.Fatal("a recording can't give way to itself", err)
	}
	arts, err = f.s.Update(ctx, arts.ID, f.actor, Update{Prefer: true, Over: news.ID})
	if err != nil || arts.State != "scheduled" {
		t.Fatal(arts, err)
	}
	news, err = f.s.Get(ctx, news.ID, f.actor)
	if err != nil || news.State != "conflict" || len(news.ConflictsWith) != 1 || news.ConflictsWith[0].ID != arts.ID {
		t.Fatal(news, err)
	}
}

// Following a programme moves its channel too: after the tuner is added
// again under new ids, the recording records from where the programme is.
func TestFollowingAProgrammeMovesItsChannel(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	f.programme(t, "bulletin", 0, 3600, "", true)
	rec, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "bulletin", Scope: "once"})
	if err != nil {
		t.Fatal(err)
	}
	moved := f.channels[1]
	if _, err = f.conn.ExecContext(ctx, "UPDATE live_tv_channels SET number=? WHERE id=?", f.channels[0].Number, moved.ID); err != nil {
		t.Fatal(err)
	}
	if _, err = f.conn.ExecContext(ctx, "UPDATE live_tv_programmes SET channel_id=?,id='bulletin-new' WHERE id='bulletin'", moved.ID); err != nil {
		t.Fatal(err)
	}
	if err = f.s.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	got, err := f.s.get(ctx, rec.ID)
	if err != nil || got.ProgrammeID != "bulletin-new" || got.ChannelID != moved.ID || got.State != "scheduled" {
		t.Fatalf("%+v %v", got, err)
	}
}

// A repeat that starts more than 14 days ahead is saved once, not refused
// after saving; a repeat set during today's slot records the rest of it.
func TestManualRepeatsFarAheadAndDuringTheirSlot(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	now := unix(f.clock.Load())
	far := Manual{ChannelID: f.channels[0].ID, Start: now.AddDate(0, 0, 20), End: now.AddDate(0, 0, 20).Add(time.Hour), Repeat: "weekly", Zone: "UTC", Name: "Garden hour"}
	first, err := f.s.Manual(ctx, f.actor, far)
	if err != nil || !first.Start.Equal(far.Start) {
		t.Fatal(first, err)
	}
	again, err := f.s.Manual(ctx, f.actor, far)
	if err != nil || again.ID != first.ID {
		t.Fatal("a retry made a second rule", again, err)
	}
	rules, err := f.q.ListDVRRules(ctx)
	if err != nil || len(rules) != 1 {
		t.Fatal(len(rules), err)
	}
	during := Manual{ChannelID: f.channels[1].ID, Start: now.Add(-5 * time.Minute), End: now.Add(55 * time.Minute), Repeat: "daily", Zone: "UTC", Name: "Breakfast"}
	today, err := f.s.Manual(ctx, f.actor, during)
	if err != nil || !today.Start.Equal(during.Start) || today.State != "scheduled" {
		t.Fatalf("today's slot was skipped: %+v %v", today, err)
	}
}

// Cancelling a recording, or deleting one person's series rule, frees the
// series episodes it was blocking at once.
func TestCancellingFreesTheSeriesEpisodeItBlocked(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	f.programme(t, "pilot", 0, 3600, "vane", true)
	once, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "pilot", Scope: "once"})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = f.s.Create(ctx, f.actor, Create{ProgrammeID: "pilot", Scope: "series"}); err != nil {
		t.Fatal(err)
	}
	seriesRow := func() record {
		t.Helper()
		rows, err := f.q.ListDVRProgrammeMarks(ctx, []string{"pilot"})
		if err != nil {
			t.Fatal(err)
		}
		for _, row := range rows {
			if row.RuleID != nil {
				r, err := readRecord(row)
				if err != nil {
					t.Fatal(err)
				}
				return r
			}
		}
		t.Fatal("no series row")
		return record{}
	}
	if r := seriesRow(); r.State != "skipped" || r.Reason != "already_scheduled" {
		t.Fatal(r.State, r.Reason)
	}
	marks, err := f.s.Marks(ctx, []string{"pilot"}, f.actor)
	if err != nil || marks["pilot"].ID != once.ID || marks["pilot"].State != "scheduled" {
		t.Fatal("the mark should show the recording that will run", marks["pilot"], err)
	}
	if err = f.s.Cancel(ctx, once.ID, f.actor); err != nil {
		t.Fatal(err)
	}
	if r := seriesRow(); r.State != "scheduled" {
		t.Fatalf("the series episode stayed skipped until the next refresh: %s %s", r.State, r.Reason)
	}
}

// Padding changed on one recording of a rule survives the next refresh.
func TestPaddingOfOneRecordingSurvivesRefresh(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	f.programme(t, "pilot", 0, 3600, "vane", true)
	rec, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "pilot", Scope: "series"})
	if err != nil {
		t.Fatal(err)
	}
	thirty := 30
	if _, err = f.s.Update(ctx, rec.ID, f.actor, Update{PadAfter: &thirty}); err != nil {
		t.Fatal(err)
	}
	if err = f.s.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	got, err := f.s.get(ctx, rec.ID)
	if err != nil || got.PadAfter != 30 {
		t.Fatal(got.PadAfter, err)
	}
}

// Every name the recorder writes is one the scanner reads, under 255 bytes,
// in a folder spelled as the disk already spells it.
func TestRecordingNamesAreOnesTheScannerReads(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	season, episode := 3, 5
	long := strings.Repeat("長", 120)
	cases := []record{
		{Title: "Extras", Season: &season, Episode: &episode, EpisodeTitle: "The Trailer"},
		{Title: "Other", Season: &season, Episode: &episode, TMDBID: 1234},
		{Title: "_Hidden show", Start: unix(f.clock.Load())},
		{Title: "CON", Start: unix(f.clock.Load())},
		{Title: long, Season: &season, Episode: &episode, EpisodeTitle: long},
		{Title: "Sample", Kind: "movie", AirDate: "2024-01-01"},
	}
	for _, r := range cases {
		path, err := f.s.recordingPath(ctx, f.lib, r)
		if err != nil {
			t.Fatal(r.Title, err)
		}
		rel, err := filepath.Rel(f.lib.Path, path)
		if err != nil {
			t.Fatal(err)
		}
		parts := strings.Split(rel, string(filepath.Separator))
		for _, dir := range parts[:len(parts)-1] {
			if parser.IgnoreDir(dir) {
				t.Errorf("%q: the scanner skips the folder %q", r.Title, dir)
			}
		}
		base := parts[len(parts)-1]
		if parser.IgnoreFile(base) {
			t.Errorf("%q: the scanner skips the file %q", r.Title, base)
		}
		for _, part := range append(parts, "."+base+".partial") {
			if len(part) > 255 {
				t.Errorf("%q: %d bytes in %q", r.Title, len(part), part)
			}
		}
	}
	if path, _ := f.s.recordingPath(ctx, f.lib, cases[1]); !strings.Contains(path, "[tmdbid-1234]") {
		t.Errorf("a renamed folder should keep the show's TMDB id: %s", path)
	}
	// An existing folder spelled NFD (as macOS keeps it) or with another
	// casing is reused as the disk spells it.
	nfd := norm.NFD.String("Pokémon")
	if err := os.Mkdir(filepath.Join(f.lib.Path, nfd), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(f.lib.Path, "Masterchef"), 0o755); err != nil {
		t.Fatal(err)
	}
	for title, folder := range map[string]string{norm.NFC.String("Pokémon"): nfd, "MasterChef": "Masterchef"} {
		path, err := f.s.recordingPath(ctx, f.lib, record{Title: title, Season: &season, Episode: &episode})
		if err != nil {
			t.Fatal(err)
		}
		if got, _, _ := strings.Cut(strings.TrimPrefix(path, f.lib.Path+string(filepath.Separator)), string(filepath.Separator)); got != folder {
			t.Errorf("%q went to %q, not the existing %q", title, got, folder)
		}
	}
}

// Recorded lists the newest first, across pages.
func TestRecordedListsTheNewestFirst(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	base := unix(f.clock.Load()).Add(-48 * time.Hour)
	for i := range 25 {
		r := record{ID: "done-" + string(rune('a'+i)), Title: "Bulletin", Start: base.Add(time.Duration(i) * time.Hour), End: base.Add(time.Duration(i)*time.Hour + 30*time.Minute), State: "done", Gaps: []Gap{}, CreatedAt: base.Unix()}
		if err := put(ctx, f.q, r, base.Unix()); err != nil {
			t.Fatal(err)
		}
	}
	page, err := f.s.List(ctx, f.actor, "recorded", "")
	if err != nil || len(page.Items) != 20 || page.Items[0].ID != "done-y" || page.NextCursor == "" {
		t.Fatalf("first %v %v", page.Items[0].ID, err)
	}
	next, err := f.s.List(ctx, f.actor, "recorded", page.NextCursor)
	if err != nil || len(next.Items) != 5 || next.Items[0].ID != "done-e" || next.Items[4].ID != "done-a" {
		t.Fatal(len(next.Items), err)
	}
}

// When sealing through the writer failed, the closed file is measured by
// its path, but only at exactly the length the recording wrote.
func TestMeasuringAClosedPartial(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	partial := filepath.Join(f.lib.Path, ".Bulletin.ts.partial")
	if err := os.WriteFile(partial, packet, 0o600); err != nil {
		t.Fatal(err)
	}
	r := record{ID: "measured", Bytes: int64(len(packet)), LibraryID: f.lib.ID, PartialPath: partial}
	if err := f.s.measureClosed(ctx, &r); err != nil || r.FileMtimeNano == 0 {
		t.Fatal(r.FileMtimeNano, err)
	}
	r.Bytes++
	r.FileMtimeNano = 0
	if err := f.s.measureClosed(ctx, &r); !errors.Is(err, ErrInvalid) || r.FileMtimeNano != 0 {
		t.Fatal("measured a file of another length", err)
	}
}

// A missing file counts as deleted only while its library folder is there;
// an unmounted drive's recordings stay owned for its return.
func TestDeletingWithTheLibraryFolderMissingKeepsOwnership(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	path := filepath.Join(f.lib.Path, "Bulletin", "Bulletin - 2026-11-04.ts")
	now := unix(f.clock.Load())
	r := record{ID: "unmounted", State: "done", Title: "Bulletin", Start: now.Add(-time.Hour), End: now, Bytes: 188, Gaps: []Gap{}, LibraryID: f.lib.ID, Path: path, CreatedAt: now.Unix()}
	if err := put(ctx, f.q, r, now.Unix()); err != nil {
		t.Fatal(err)
	}
	gone := f.lib.Path + ".unmounted"
	if err := os.Rename(f.lib.Path, gone); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Rename(gone, f.lib.Path) })
	if err := f.s.DeleteRecording(ctx, r.ID, false); !errors.Is(err, ErrUnavailable) {
		t.Fatal(err)
	}
	if got, err := f.s.get(ctx, r.ID); err != nil || got.State != "done" {
		t.Fatal(got.State, err)
	}
	if _, err := os.Stat(filepath.Join(f.lib.Path, "Bulletin")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("a delete recreated the folder", err)
	}
}
