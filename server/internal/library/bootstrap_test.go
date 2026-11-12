package library

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"

	"watchmymedia/server/internal/config"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/db/dbtest"
	"watchmymedia/server/internal/settings"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

// folder makes dir with one file in it, or empty when file is "".
func folder(t *testing.T, dir, file string) string {
	t.Helper()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if file != "" {
		if err := os.WriteFile(filepath.Join(dir, file), nil, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func paths(t *testing.T, q *db.Queries) []string {
	t.Helper()
	libs, err := q.ListLibraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, l := range libs {
		out = append(out, l.Type+" "+l.Name+" "+l.Path)
	}
	return out
}

func seeded(t *testing.T, q *db.Queries) bool {
	t.Helper()
	v, _ := q.GetSetting(t.Context(), settings.KeyLibrariesSeeded)
	return v == "1"
}

func start(ctx context.Context, t *testing.T, q *db.Queries, cfg config.LibrariesConfig) {
	t.Helper()
	if err := EnsureFromConfig(ctx, q, cfg, quiet); err != nil {
		t.Fatal(err)
	}
}

// A new server gets the configured folders that hold something, once.
// Later starts leave the libraries alone: a folder configured later, or a
// library removed in Settings, is not added again.
func TestEnsureFromConfigSeedsOnce(t *testing.T) {
	_, q := dbtest.Open(t)
	ctx := t.Context()
	root := t.TempDir()
	movies := folder(t, filepath.Join(root, "Movies"), "Brass Lantern (2019).mkv")
	tv := filepath.Join(root, "TV")

	start(ctx, t, q, config.LibrariesConfig{Movies: movies, TV: tv})
	if got := paths(t, q); len(got) != 1 || got[0] != "movie Movies "+movies {
		t.Fatalf("first start: %q", got)
	}
	if !seeded(t, q) {
		t.Fatal("not marked seeded after adding a library")
	}

	folder(t, filepath.Join(tv, "Harbor Lights"), "")
	start(ctx, t, q, config.LibrariesConfig{Movies: movies, TV: tv})
	if got := paths(t, q); len(got) != 1 {
		t.Fatalf("a TV folder that appeared later was added: %q", got)
	}

	libs, _ := q.ListLibraries(ctx)
	if err := q.DeleteLibrary(ctx, libs[0].ID); err != nil {
		t.Fatal(err)
	}
	start(ctx, t, q, config.LibrariesConfig{Movies: movies, TV: tv})
	if got := paths(t, q); len(got) != 0 {
		t.Fatalf("a removed library came back: %q", got)
	}
}

// Missing, empty and dot-only folders become no library, and the server
// tries again at the next start, so a drive mounted later is still added.
func TestEnsureFromConfigWaitsForFolders(t *testing.T) {
	_, q := dbtest.Open(t)
	ctx := t.Context()
	root := t.TempDir()
	movies := filepath.Join(root, "Movies")
	tv := folder(t, filepath.Join(root, "TV"), ".DS_Store")

	start(ctx, t, q, config.LibrariesConfig{Movies: movies, TV: tv})
	if got := paths(t, q); len(got) != 0 {
		t.Fatalf("missing or empty folders added: %q", got)
	}
	if seeded(t, q) {
		t.Fatal("marked seeded with no library")
	}

	folder(t, movies, "Cold Orchard (2021).mkv")
	start(ctx, t, q, config.LibrariesConfig{Movies: movies, TV: tv})
	if got := paths(t, q); len(got) != 1 || got[0] != "movie Movies "+movies {
		t.Fatalf("after the drive was mounted: %q", got)
	}
}

// A server that already has libraries, such as one upgraded from a release
// that added the configured folders at every start, is marked seeded and
// gets nothing new.
func TestEnsureFromConfigLeavesExistingLibraries(t *testing.T) {
	_, q := dbtest.Open(t)
	ctx := t.Context()
	root := t.TempDir()
	if _, err := q.CreateLibrary(ctx, db.CreateLibraryParams{Type: TypeMovie, Name: "Films", Path: "/srv/films"}); err != nil {
		t.Fatal(err)
	}
	tv := folder(t, filepath.Join(root, "TV"), "Harbor Lights - S01E01.mkv")

	start(ctx, t, q, config.LibrariesConfig{TV: tv})
	if got := paths(t, q); len(got) != 1 || got[0] != "movie Films /srv/films" {
		t.Fatalf("libraries: %q", got)
	}
	if !seeded(t, q) {
		t.Error("not marked seeded")
	}
}
