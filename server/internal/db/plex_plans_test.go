package db

import (
	"path/filepath"
	"strings"
	"testing"
)

// Preview matching looks up each Plex item by its exact IDs, and deleting
// an account or a library finds its Plex links, through indexes rather
// than reading whole tables, on a database that has never been analyzed.
func TestPlexQueriesUseIndexes(t *testing.T) {
	conn, err := Open(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = conn.Close() }()
	if err := Migrate(t.Context(), conn); err != nil {
		t.Fatal(err)
	}
	for name, q := range map[string]string{
		"MatchMovieByTMDB":     matchMovieByTMDB,
		"MatchMoviesByIMDb":    matchMoviesByIMDb,
		"MatchEpisode":         matchEpisode,
		"ListPlexUserLinks":    listPlexUserLinks,
		"ListPlexLibraryLinks": listPlexLibraryLinks,
		"user_delete_links":    `DELETE FROM plex_user_links WHERE user_id = ?`,
		"library_delete_links": `DELETE FROM plex_library_links WHERE library_id = ?`,
	} {
		plan := queryPlan(t, conn, name, q)
		for _, step := range plan {
			if strings.HasPrefix(step, "SCAN ") {
				t.Errorf("%s reads a whole table:\n  %s", name, strings.Join(plan, "\n  "))
				break
			}
		}
	}
}
