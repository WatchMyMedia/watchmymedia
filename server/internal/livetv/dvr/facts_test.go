package dvr

import (
	"path/filepath"
	"slices"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/watchstate"
)

// listing adds a programme of a series' episode with what recordings copy.
func (f fixture) listing(t *testing.T, id string, channel int, offset int64, series string, episode int64, repeat bool) {
	t.Helper()
	row := db.InsertProgrammeParams{ID: id, ChannelID: f.channels[channel].ID, StartAt: f.clock.Load() + offset, EndAt: f.clock.Load() + offset + 3600,
		Title: "Inspector Vane", EpisodeTitle: "The orchard", SeriesKey: series, Season: new(int64(2)), Episode: new(episode),
		AirDate: "2019-05-01", Categories: `["Documentary","Drama"]`, Kind: "episode"}
	if repeat {
		row.IsRepeat = 1
	} else {
		row.IsNew = 1
	}
	if err := f.q.InsertProgramme(t.Context(), row); err != nil {
		t.Fatal(err)
	}
}

// A recording says what its listing said (new or a repeat, its kind, year
// and categories), a clash says when a repeat airs again, and the first
// page counts each section and says how much room there is.
func TestRecordingsCarryTheirListingAndTheClashesNextAiring(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	config, err := f.s.Settings(ctx)
	if err != nil {
		t.Fatal(err)
	}
	config.TunersReserved, config.MinFreeBytes = 1, 50_000_000_000
	if err = f.s.SetSettings(ctx, config); err != nil {
		t.Fatal(err)
	}
	f.listing(t, "holder", 0, 3600, "vane", 3, true)
	f.listing(t, "holder-again", 1, 3*86400, "vane", 3, true)
	f.listing(t, "other-episode", 1, 4*86400, "vane", 4, true)
	f.listing(t, "clash", 1, 3600, "orchard", 7, true)
	f.listing(t, "clash-again", 0, 2*86400, "orchard", 7, true)
	holder, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "holder", Scope: "once"})
	if err != nil || holder.State != "scheduled" {
		t.Fatal(holder, err)
	}
	f.clock.Add(1)
	clash, err := f.s.Create(ctx, f.actor, Create{ProgrammeID: "clash", Scope: "once"})
	if err != nil || clash.State != "conflict" {
		t.Fatal(clash, err)
	}
	page, err := f.s.List(ctx, f.actor, "conflicts", "")
	if err != nil || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	got := page.Items[0]
	if !got.Repeat || got.New || got.ProgrammeKind != "episode" || got.Year != 2019 || !slices.Equal(got.Categories, []string{"Documentary", "Drama"}) {
		t.Fatalf("listing facts: %+v", got)
	}
	if got.NextAiring == nil || got.NextAiring.ProgrammeID != "clash-again" || got.NextAiring.ChannelID != f.channels[0].ID {
		t.Fatalf("the clash's next airing: %+v", got.NextAiring)
	}
	if len(got.ConflictsWith) != 1 || !got.ConflictsWith[0].Repeat || got.ConflictsWith[0].NextAiring == nil || got.ConflictsWith[0].NextAiring.ProgrammeID != "holder-again" {
		t.Fatalf("the holder's next airing: %+v", got.ConflictsWith)
	}
	if page.Counts == nil || page.Counts.Conflicts.Items != 1 || page.Counts.Upcoming.Items != 1 || page.Counts.Recorded.Items != 0 {
		t.Fatalf("counts: %+v", page.Counts)
	}
	if page.ReserveBytes != 50_000_000_000 || page.TotalBytes <= 0 || page.FreeBytes != 1_000_000_000_000 {
		t.Fatalf("room: free %d reserve %d total %d", page.FreeBytes, page.ReserveBytes, page.TotalBytes)
	}
	// What is worked out for a reply is never stored.
	row, err := f.q.GetDVRRecording(ctx, clash.ID)
	if err != nil {
		t.Fatal(err)
	}
	stored, err := readRecord(row)
	if err != nil || stored.NextAiring != nil || stored.ProgrammeKind != "" || len(stored.ConflictsWith) != 1 || stored.ConflictsWith[0].NextAiring != nil || !stored.ConflictsWith[0].Repeat {
		t.Fatalf("stored: %+v %v", stored, err)
	}
	// A later page carries no counts.
	if next, err := f.s.List(ctx, f.actor, "conflicts", "MTow"); err != nil || next.Counts != nil {
		t.Fatalf("a later page: %+v %v", next.Counts, err)
	}
}

// Each person sees their own place in a recording: its own progress while
// it isn't in the library, then the watch state of what it became.
func TestRecordingsCarryTheViewersPlace(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	now := unix(f.clock.Load())
	other, err := f.q.CreateUser(ctx, db.CreateUserParams{Name: "Robin", PasswordHash: "unused"})
	if err != nil {
		t.Fatal(err)
	}
	growing := record{ID: "growing", UserID: &f.actor.UserID, State: "done", Start: now.Add(-2 * time.Hour), End: now.Add(-time.Hour), Gaps: []Gap{}, CreatedAt: now.Unix(), Bytes: 2048}
	if err = put(ctx, f.q, growing, now.Unix()); err != nil {
		t.Fatal(err)
	}
	if err = f.q.PutDVRProgress(ctx, db.PutDVRProgressParams{RecordingID: growing.ID, UserID: f.actor.UserID, PositionMs: 60_000, DurationMs: 3_600_000, UpdatedAt: now.UnixMilli()}); err != nil {
		t.Fatal(err)
	}
	res, err := f.conn.ExecContext(ctx, "INSERT INTO movies(library_id,tmdb_id,title) VALUES (?,321,'Night Ferry')", f.lib.ID)
	if err != nil {
		t.Fatal(err)
	}
	movieID, _ := res.LastInsertId()
	path := filepath.Join(f.lib.Path, "Night Ferry.ts")
	res, err = f.conn.ExecContext(ctx, "INSERT INTO media_files(library_id,path,size,mtime,status,parsed,movie_id) VALUES (?,?,188,?,'present','{}',?)", f.lib.ID, path, db.FormatTime(now), movieID)
	if err != nil {
		t.Fatal(err)
	}
	fileID, _ := res.LastInsertId()
	linked := record{ID: "linked", UserID: &f.actor.UserID, State: "done", Start: now.Add(-4 * time.Hour), End: now.Add(-3 * time.Hour), Gaps: []Gap{}, CreatedAt: now.Unix(), MediaFileID: &fileID, Path: path, Bytes: 1024}
	if err = put(ctx, f.q, linked, now.Unix()); err != nil {
		t.Fatal(err)
	}
	watch := watchstate.New(f.q, nil)
	if _, err = watch.Report(ctx, watchstate.Progress{UserID: f.actor.UserID, ItemType: watchstate.Movie, ItemIDs: []int64{movieID}, FileID: fileID, PositionMs: 3_500_000, DurationMs: 3_600_000}); err != nil {
		t.Fatal(err)
	}
	if err = watch.MarkWatched(ctx, f.actor.UserID, watchstate.Movie, movieID); err != nil {
		t.Fatal(err)
	}
	page, err := f.s.List(ctx, f.actor, "recorded", "")
	if err != nil || len(page.Items) != 2 {
		t.Fatal(page, err)
	}
	byID := map[string]Recording{}
	for _, r := range page.Items {
		byID[r.ID] = r
	}
	if p := byID["growing"].Progress; p == nil || p.PositionMs != 60_000 || p.DurationMs != 3_600_000 || byID["growing"].Watched {
		t.Fatalf("the recording's own progress: %+v", byID["growing"])
	}
	if !byID["linked"].Watched {
		t.Fatalf("the library's watch state: %+v", byID["linked"])
	}
	if page.Counts == nil || page.Counts.Recorded.Items != 2 || page.Counts.Recorded.Bytes != 3072 {
		t.Fatalf("recorded counts: %+v", page.Counts)
	}
	// Someone else has watched neither.
	page, err = f.s.List(ctx, Actor{UserID: other.ID, Name: other.Name}, "recorded", "")
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range page.Items {
		if r.Progress != nil || r.Watched {
			t.Fatalf("another person's place leaked: %+v", r)
		}
	}
	one, err := f.s.Get(ctx, "growing", f.actor)
	if err == nil {
		one, err = f.s.WithFacts(ctx, one, f.actor)
	}
	if err != nil || one.Progress == nil || one.Progress.PositionMs != 60_000 {
		t.Fatalf("one recording: %+v %v", one, err)
	}
}
