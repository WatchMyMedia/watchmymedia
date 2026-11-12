package metadata

import (
	"testing"

	"watchmymedia/server/internal/db"
)

// A file Radarr or Sonarr has under an item matches as that item, whatever
// its name says; IDs in the name and a person's choice still come first.
func TestHintsMatchFilesTheAppsKnow(t *testing.T) {
	e := setup(t)
	e.m.SetHints(func(lib db.Library, path string) (int, int) {
		if path == "/m/rls.grp.x264/a1b2c3.mkv" || path == "/m/The Matrix {tmdb-603}/m.mkv" {
			return 949, 0
		}
		return 0, 0
	})
	e.file(e.mov, "/m/rls.grp.x264/a1b2c3.mkv", `{"kind":"movie","title":"a1b2c3"}`)
	e.file(e.mov, "/m/The Matrix {tmdb-603}/m.mkv", `{"kind":"movie","title":"The Matrix","tmdbId":603}`)
	if _, err := e.m.MatchLibrary(e.ctx, e.mov, false); err != nil {
		t.Fatal(err)
	}
	f, _ := e.q.GetMediaFileByPath(e.ctx, "/m/rls.grp.x264/a1b2c3.mkv")
	if f.MatchStatus != StatusMatched || f.MovieID == nil {
		t.Fatalf("hinted file = %+v", f)
	}
	heat, _ := e.q.GetMovie(e.ctx, *f.MovieID)
	if heat.TmdbID != 949 {
		t.Errorf("hinted file matched %d, want 949", heat.TmdbID)
	}
	f, _ = e.q.GetMediaFileByPath(e.ctx, "/m/The Matrix {tmdb-603}/m.mkv")
	if f.MovieID == nil {
		t.Fatalf("tagged file = %+v", f)
	}
	if m, _ := e.q.GetMovie(e.ctx, *f.MovieID); m.TmdbID != 603 {
		t.Errorf("the name's ID lost to the hint: matched %d", m.TmdbID)
	}
}
