package settings

import (
	"regexp"
	"sync"
	"testing"

	"watchmymedia/server/internal/db/dbtest"
)

func TestGetSetIsSet(t *testing.T) {
	_, q := dbtest.Open(t)
	s := New(q)
	ctx := t.Context()
	if v, err := s.Get(ctx, KeyBackupKeep, "7"); err != nil || v != "7" || s.IsSet(ctx, KeyBackupKeep) {
		t.Errorf("unset: %q %v, set %v", v, err, s.IsSet(ctx, KeyBackupKeep))
	}
	if err := s.Set(ctx, KeyBackupKeep, "3"); err != nil {
		t.Fatal(err)
	}
	if v, err := s.Get(ctx, KeyBackupKeep, "7"); err != nil || v != "3" || !s.IsSet(ctx, KeyBackupKeep) {
		t.Errorf("set: %q %v", v, err)
	}
	if err := s.Set(ctx, KeyBackupKeep, "4"); err != nil {
		t.Fatal(err)
	}
	if v, _ := s.Get(ctx, KeyBackupKeep, ""); v != "4" {
		t.Errorf("changed: %q", v)
	}
	// An empty value removes the setting, so the default applies again.
	if err := s.Set(ctx, KeyBackupKeep, ""); err != nil {
		t.Fatal(err)
	}
	if v, _ := s.Get(ctx, KeyBackupKeep, "7"); v != "7" || s.IsSet(ctx, KeyBackupKeep) {
		t.Errorf("cleared: %q", v)
	}
}

func TestUploadPreferenceOverridesConfigAtomically(t *testing.T) {
	_, q := dbtest.Open(t)
	s := New(q)
	def := UploadPreference{Bps: 40_000_000}
	if got, err := s.Upload(t.Context(), def); err != nil || got != def {
		t.Fatalf("default = %+v, %v", got, err)
	}
	want := UploadPreference{Bps: 12_000_000, Enforce: true}
	if err := s.SetUpload(t.Context(), want); err != nil {
		t.Fatal(err)
	}
	if got, err := New(q).Upload(t.Context(), UploadPreference{Bps: 80_000_000}); err != nil || got != want {
		t.Fatalf("stored value = %+v, %v", got, err)
	}
	if err := s.SetUpload(t.Context(), UploadPreference{Bps: -1}); err == nil {
		t.Fatal("negative setting persisted")
	}
	if err := s.Set(t.Context(), KeyUpload, ""); err != nil {
		t.Fatal(err)
	}
	if got, err := s.Upload(t.Context(), def); err != nil || got != def {
		t.Fatalf("cleared = %+v, %v", got, err)
	}
}

// The first calls to ServerID, even at once, make one ID that stays.
func TestServerID(t *testing.T) {
	_, q := dbtest.Open(t)
	s := New(q)
	ctx := t.Context()
	ids := make([]string, 8)
	errs := make([]error, len(ids))
	var wg sync.WaitGroup
	for i := range ids {
		wg.Go(func() { ids[i], errs[i] = s.ServerID(ctx) })
	}
	wg.Wait()
	for i := range ids {
		if errs[i] != nil || ids[i] != ids[0] {
			t.Fatalf("call %d: %q %v; call 0: %q", i, ids[i], errs[i], ids[0])
		}
	}
	if !regexp.MustCompile(`^[0-9a-f]{32}$`).MatchString(ids[0]) {
		t.Errorf("id %q", ids[0])
	}
	if again, err := New(q).ServerID(ctx); err != nil || again != ids[0] {
		t.Errorf("later call: %q %v", again, err)
	}
	if v, _ := s.Get(ctx, KeyServerID, ""); v != ids[0] {
		t.Errorf("stored %q", v)
	}
}

func TestServerName(t *testing.T) {
	_, q := dbtest.Open(t)
	s := New(q)
	ctx := t.Context()
	if got := s.ServerName(ctx); got != DefaultServerName() || got == "" {
		t.Errorf("default name %q", got)
	}
	if err := s.Set(ctx, KeyServerName, "Living room"); err != nil {
		t.Fatal(err)
	}
	if got := s.ServerName(ctx); got != "Living room" {
		t.Errorf("name %q", got)
	}
	if containerID.MatchString("Mac-mini") || !containerID.MatchString("3f2a9c1b7d4e") {
		t.Error("container ID pattern")
	}
}

func TestHardwarePreferenceKeepsBackendAndIndexTogether(t *testing.T) {
	conn, q := dbtest.Open(t)
	s := New(q)
	n := 2
	hw, device, err := s.HardwarePreference(t.Context(), "nvenc", &n)
	if err != nil || hw != "nvenc" || device == nil || *device != 2 {
		t.Fatalf("defaults: %s %v %v", hw, device, err)
	}
	if err := s.SetHardwarePreference(t.Context(), conn, "nvenc", nil); err != nil {
		t.Fatal(err)
	}
	hw, device, err = s.HardwarePreference(t.Context(), "nvenc", &n)
	if err != nil || hw != "nvenc" || device != nil {
		t.Fatalf("reset revived config device: %s %v %v", hw, device, err)
	}
	if err := s.Set(t.Context(), KeyHWAccel, "software"); err != nil {
		t.Fatal(err)
	}
	// A pre-device settings database can override the backend without inheriting config's index.
	if err := s.Set(t.Context(), KeyHWDevice, ""); err != nil {
		t.Fatal(err)
	}
	hw, device, err = s.HardwarePreference(t.Context(), "nvenc", &n)
	if err != nil || hw != "software" || device != nil {
		t.Fatalf("backend override inherited a foreign index: %s %v %v", hw, device, err)
	}
	if err := s.SetHardwarePreference(t.Context(), conn, "nvenc", &n); err != nil {
		t.Fatal(err)
	}
	// Force the second write to fail; the first must roll back too.
	if _, err := conn.ExecContext(t.Context(), `CREATE TRIGGER reject_device BEFORE UPDATE ON settings WHEN NEW.key='transcode.device' BEGIN SELECT RAISE(ABORT,'device write failed'); END`); err != nil {
		t.Fatal(err)
	}
	if err := s.SetHardwarePreference(t.Context(), conn, "software", nil); err == nil {
		t.Fatal("failed second write was accepted")
	}
	hw, device, err = s.HardwarePreference(t.Context(), "auto", nil)
	if err != nil || hw != "nvenc" || device == nil || *device != 2 {
		t.Fatalf("partial hardware preference committed: %s %v %v", hw, device, err)
	}
}
