package backup

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"errors"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
)

func TestWriteAndSave(t *testing.T) {
	dir := t.TempDir()
	conn, q := dbtest.Open(t)
	ctx := t.Context()
	if _, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "movie", Name: "M", Path: "/m"}); err != nil {
		t.Fatal(err)
	}
	_ = os.WriteFile(filepath.Join(dir, "config.yaml"), []byte("port: 1\n"), 0o600)
	// A picture an admin chose and a downloaded subtitle can't be fetched
	// again, so both go in (PLAN-POSTERS.md).
	for _, name := range []string{ArtworkDir + "/c-0123456789abcdef01234567.jpg", DownloadedSubtitlesDir + "/1/en.srt"} {
		path := filepath.Join(dir, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte("kept"), 0o600); err != nil {
			t.Fatal(err)
		}
	}

	for range 3 {
		if _, err := Save(ctx, conn, dir, 2); err != nil {
			t.Fatal(err)
		}
	}
	list := List(dir)
	if len(list) != 2 {
		t.Fatalf("kept %d backups, want 2", len(list))
	}
	f, _ := os.Open(filepath.Join(dir, "backups", list[0].Name))
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	tr := tar.NewReader(gz)
	names := map[string]int64{}
	for {
		h, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		names[h.Name] = h.Size
	}
	if names["watchmymedia.db"] == 0 || names["config.yaml"] != 8 ||
		names[ArtworkDir+"/c-0123456789abcdef01234567.jpg"] != 4 || names[DownloadedSubtitlesDir+"/1/en.srt"] != 4 {
		t.Errorf("archive members = %v", names)
	}
	// The snapshot is a usable database.
	tmp := filepath.Join(t.TempDir(), "restore.db")
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		t.Fatal(err)
	}
	gz2, _ := gzip.NewReader(f)
	tr = tar.NewReader(gz2)
	for {
		h, err := tr.Next()
		if err != nil {
			break
		}
		if h.Name == "watchmymedia.db" {
			out, _ := os.Create(tmp)
			_, _ = io.CopyN(out, tr, h.Size)
			_ = out.Close()
		}
	}
	rconn, err := db.Open(tmp)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rconn.Close() }()
	libs, err := db.New(rconn).ListLibraries(ctx)
	if err != nil || len(libs) != 1 || libs[0].Name != "M" {
		t.Errorf("restored libraries = %+v %v", libs, err)
	}
	if entries, _ := os.ReadDir(dir); len(entries) > 8 {
		t.Errorf("temp dirs left behind: %v", entries)
	}
}

// Archives hold password hashes and API keys: the folder and the files are
// the server's alone, and a folder made wider before is tightened.
func TestSaveIsPrivate(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows has no Unix permission bits")
	}
	dir := t.TempDir()
	conn, _ := dbtest.Open(t)
	if err := os.MkdirAll(filepath.Join(dir, "backups"), 0o755); err != nil {
		t.Fatal(err)
	}
	path, err := Save(t.Context(), conn, dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	for p, want := range map[string]os.FileMode{filepath.Dir(path): 0o700, path: 0o600} {
		fi, err := os.Stat(p)
		if err != nil {
			t.Fatal(err)
		}
		if got := fi.Mode().Perm(); got != want {
			t.Errorf("%s: mode %v, want %v", filepath.Base(p), got, want)
		}
	}
}

// Saving keeps the newest archives up to keep and deletes older ones,
// leaving other files in the folder alone.
func TestSavePrunes(t *testing.T) {
	dir := t.TempDir()
	conn, _ := dbtest.Open(t)
	backups := filepath.Join(dir, "backups")
	if err := os.MkdirAll(backups, 0o700); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-48 * time.Hour)
	for i, name := range []string{prefix + "a.tar.gz", prefix + "b.tar.gz", prefix + "c.tar.gz", "notes.txt", prefix + "d.zip"} {
		p := filepath.Join(backups, name)
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
		at := old.Add(time.Duration(i) * time.Hour)
		if err := os.Chtimes(p, at, at); err != nil {
			t.Fatal(err)
		}
	}
	path, err := Save(t.Context(), conn, dir, 2)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, b := range List(dir) {
		names = append(names, b.Name)
	}
	if want := []string{filepath.Base(path), prefix + "c.tar.gz"}; !slices.Equal(names, want) {
		t.Errorf("kept %v, want %v", names, want)
	}
	for _, other := range []string{"notes.txt", prefix + "d.zip"} {
		if _, err := os.Stat(filepath.Join(backups, other)); err != nil {
			t.Errorf("%s: %v", other, err)
		}
	}
}

// An archive holds no Plex grant and its database refers to none: the
// grant folder is left out, and the copy's connection has no credential
// reference and sync off, so a restore has to connect Plex again. The live
// database keeps its reference.
func TestArchiveHoldsNoPlexGrant(t *testing.T) {
	dir := t.TempDir()
	conn, q := dbtest.Open(t)
	ctx := t.Context()
	ref := "0123456789abcdef0123456789abcdef"
	if _, err := q.InsertPlexConnection(ctx, db.InsertPlexConnectionParams{MachineIdentifier: "fictional-machine", Origin: "http://192.0.2.10:32400",
		CredentialRef: &ref, OwnerPlexID: 701, HistoryCapability: "unknown"}); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.ExecContext(ctx, "UPDATE plex_connections SET sync_enabled = 1"); err != nil {
		t.Fatal(err)
	}
	const secret = "synthetic-device-seed-and-client"
	grants := filepath.Join(dir, "plex-credentials")
	if err := os.MkdirAll(grants, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(grants, ref+".json"), []byte(secret), 0o600); err != nil {
		t.Fatal(err)
	}
	path, err := Save(ctx, conn, dir, 0)
	if err != nil {
		t.Fatal(err)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	tr := tar.NewReader(gz)
	restored := filepath.Join(t.TempDir(), "restore.db")
	for {
		h, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(tr)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(h.Name, "plex-credentials") || bytes.Contains(data, []byte(secret)) || bytes.Contains(data, []byte(ref)) {
			t.Errorf("archive member %s holds the grant or its reference", h.Name)
		}
		if h.Name == "watchmymedia.db" {
			if err := os.WriteFile(restored, data, 0o600); err != nil {
				t.Fatal(err)
			}
		}
	}
	rconn, err := db.Open(restored)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = rconn.Close() }()
	got, err := db.New(rconn).GetPlexConnectionByMachine(ctx, "fictional-machine")
	if err != nil || got.CredentialRef != nil || got.SyncEnabled != 0 || got.Problem != "restored_from_backup" {
		t.Fatalf("restored connection = %+v %v", got, err)
	}
	live, _ := q.GetPlexConnectionByMachine(ctx, "fictional-machine")
	if live.CredentialRef == nil || *live.CredentialRef != ref || live.SyncEnabled != 1 || live.Problem != "" {
		t.Fatalf("the live connection changed: %+v", live)
	}
}
