package db

import (
	"fmt"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// TestSQLiteToolWritesTitles checks that the sqlite3 tool people have can
// still write to the database, and that what it writes is searchable: the
// fixture scripts add shows and episodes with it, and so do checks on a
// scratch database. A trigger the tool cannot read fails every statement on
// the database, not only writes to its table. A draft of the search index
// had fifty replace() calls inside one another in a trigger; the server
// read that, and sqlite3 3.45 answered "parser stack overflow" to
// everything.
//
// It needs sqlite3 on the PATH, at 3.45 or later: the index's tokenizer
// option (trigram remove_diacritics, which history search also uses) is
// from that release.
func TestSQLiteToolWritesTitles(t *testing.T) {
	t.Parallel()
	ctx := t.Context()
	tool, err := exec.LookPath("sqlite3")
	if err != nil {
		t.Skip("no sqlite3 on the PATH")
	}
	version, err := exec.CommandContext(ctx, tool, "--version").Output() //nolint:gosec // G204: the sqlite3 found on the PATH
	if err != nil {
		t.Skipf("sqlite3 --version: %v", err)
	}
	var major, minor int
	if _, err := fmt.Sscanf(string(version), "%d.%d", &major, &minor); err != nil || major != 3 || minor < 45 {
		t.Skipf("sqlite3 is %s; the index needs 3.45", strings.TrimSpace(string(version)))
	}

	path := filepath.Join(t.TempDir(), "t.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := Migrate(ctx, conn); err != nil {
		t.Fatal(err)
	}
	// The server has the database open meanwhile, as it may.
	script := `
		INSERT INTO libraries (id, type, name, path) VALUES (1, 'movie', 'Movies', '/nonexistent/m'), (2, 'tv', 'TV', '/nonexistent/tv');
		INSERT INTO movies (id, library_id, tmdb_id, title, original_title) VALUES
			(7, 1, 949, 'Harbor Lights', NULL), (8, 1, 950, 'Lantern-Man', 'Der Laternen-Mann'), (9, 1, 951, 'Quiet Field', NULL);
		INSERT INTO shows (id, library_id, tmdb_id, title) VALUES (3, 2, 77, 'Night Shift Diner');
		INSERT INTO seasons (id, show_id, number) VALUES (4, 3, 1);
		INSERT INTO episodes (id, season_id, show_id, number, title) VALUES (11, 4, 3, 1, 'Pilot'), (12, 4, 3, 2, 'Graveyard Special');
		UPDATE movies SET title = 'Harbour Lights' WHERE id = 7;
		DELETE FROM movies WHERE id = 9;
		SELECT 'scratch ' || COUNT(*) FROM search_scratch;
	`
	cmd := exec.CommandContext(ctx, tool, "-bail", path) //nolint:gosec // G204: the sqlite3 found on the PATH
	cmd.Stdin = strings.NewReader(script)
	out, err := cmd.CombinedOutput()
	if err != nil || strings.TrimSpace(string(out)) != "scratch 0" {
		t.Fatalf("sqlite3 %s: %v\n%s", strings.TrimSpace(string(version)), err, out)
	}

	for _, c := range []struct {
		table, words string
		want         []int64
	}{
		{"movies", `"harbour"`, []int64{7}},
		{"movies", `"harbor"`, nil},
		{"movies", `"lanternman"`, []int64{8}},
		{"movies", `"laternen"`, []int64{8}},
		{"movies", `"quiet"`, nil},
		{"shows", `"diner"`, []int64{3}},
		{"episodes", `"graveyard" AND "special"`, []int64{12}},
		// The first letter of a word, after the index's two marks.
		{"episodes", "\"\x1f\x1fp\"", []int64{11}},
	} {
		var got []int64
		func() {
			rows, err := conn.QueryContext(ctx, fmt.Sprintf(`SELECT rowid FROM %s_search(?) ORDER BY rowid`, c.table), c.words)
			if err != nil {
				t.Fatal(err)
			}
			defer func() { _ = rows.Close() }()
			for rows.Next() {
				var id int64
				if err := rows.Scan(&id); err != nil {
					t.Fatal(err)
				}
				got = append(got, id)
			}
			if err := rows.Err(); err != nil {
				t.Fatal(err)
			}
		}()
		if !slices.Equal(got, c.want) {
			t.Errorf("%s %q found %v, want %v", c.table, c.words, got, c.want)
		}
	}
}
