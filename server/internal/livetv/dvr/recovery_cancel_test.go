package dvr

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestCancelBeforeRecoveryRejectsAStaleResume(t *testing.T) {
	t.Parallel()
	f := setup(t, nil)
	ctx := t.Context()
	now := unix(f.clock.Load())
	rec, err := f.s.Manual(ctx, f.actor, Manual{ChannelID: f.channels[0].ID, Start: now, End: now.Add(3 * time.Minute), Repeat: "once", Zone: "UTC", Name: "Restart cancel"})
	if err != nil {
		t.Fatal(err)
	}
	interrupted, err := f.s.get(ctx, rec.ID)
	if err != nil {
		t.Fatal(err)
	}
	interrupted.State = "recording"
	interrupted.StartedAt = new(now)
	interrupted.LibraryID = f.lib.ID
	interrupted.Path = filepath.Join(f.lib.Path, "Restart cancel.ts")
	interrupted.PartialPath = filepath.Join(f.lib.Path, ".Restart cancel.ts.partial")
	captured := bytes.Repeat([]byte{0x47}, 188)
	if err = os.WriteFile(interrupted.PartialPath, captured, 0o600); err != nil {
		t.Fatal(err)
	}
	if err = put(ctx, f.q, interrupted, now.Unix()); err != nil {
		t.Fatal(err)
	}
	// The scheduler has read its startup row, but the account cancels before
	// begin has opened or registered a replacement writer.
	stale := interrupted
	if err = f.s.Cancel(ctx, rec.ID, f.actor); err != nil {
		t.Fatal(err)
	}
	requested, err := f.s.get(ctx, rec.ID)
	if err != nil || requested.Reason != "stop_requested" {
		t.Fatalf("cancel was not durable: %+v %v", requested, err)
	}
	f.s.begin(ctx, stale, true)
	requested, err = f.s.get(ctx, rec.ID)
	if err != nil || requested.Reason != "stop_requested" || f.s.Active() != 0 {
		t.Fatalf("stale resume undid cancellation: %+v active=%d err=%v", requested, f.s.Active(), err)
	}
	f.s.tick(ctx, now)
	finished, err := f.s.get(ctx, rec.ID)
	if err != nil || finished.State != "partial" {
		t.Fatalf("cancel did not retain its captured part: %+v %v", finished, err)
	}
	body, err := os.ReadFile(finished.Path)
	if err != nil || !bytes.Equal(body, captured) {
		t.Fatalf("captured part changed: %q %v", body, err)
	}
	if active, _, opens := f.fake.Counts(); active != 0 || opens != 0 {
		t.Fatalf("cancelled recovery opened a tuner: active=%d opens=%d", active, opens)
	}
}
