package scanner

import (
	"context"
	"path/filepath"
	"testing"

	"watchmymedia/server/internal/db"
)

// movieOf matches the file at rel to a movie with the given TMDB id, as
// matching would, and returns the movie's id.
func movieOf(t *testing.T, q *db.Queries, lib db.Library, rel string, tmdbID int64) int64 {
	t.Helper()
	ctx := context.Background()
	m, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: lib.ID, TmdbID: tmdbID, Title: rel, Genres: "[]", Credits: "{}"})
	if err != nil {
		t.Fatal(err)
	}
	f := fileAt(t, q, lib, rel)
	if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &m.ID, ID: f.ID}); err != nil {
		t.Fatal(err)
	}
	return m.ID
}

func fileAt(t *testing.T, q *db.Queries, lib db.Library, rel string) db.MediaFile {
	t.Helper()
	f, err := q.GetMediaFileByPath(context.Background(), filepath.Join(lib.Path, filepath.FromSlash(rel)))
	if err != nil {
		t.Fatalf("%s: %v", rel, err)
	}
	return f
}

func kindOf(f db.MediaFile) string {
	if f.ExtraKind == nil {
		return ""
	}
	return *f.ExtraKind
}

func linkedTo(f db.MediaFile) int64 {
	if f.ExtraOf == nil {
		return 0
	}
	return *f.ExtraOf
}

// Each name Jellyfin, Plex, Emby and Kodi read as a trailer is recorded as
// one, outside the library: not in its files, not matched.
func TestScanRecordsTrailers(t *testing.T) {
	s, q, fp, root, lib := setup(t)
	ctx := context.Background()
	trailers := []string{
		"Heat (1995)/Heat (1995)-trailer.mkv",
		"Heat (1995)/Heat (1995).trailer.mp4",
		"Heat (1995)/Heat (1995)_trailer.mp4",
		"Heat (1995)/Heat (1995) trailer.mp4",
		"Heat (1995)/trailer.mp4",
		"Heat (1995)/movie-trailer.mkv",
		"Heat (1995)/Trailers/Official Trailer.mp4",
		"Ronin (1998)/trailers/Teaser.mkv", // its own folder: file systems may ignore case
	}
	content := []string{"Heat (1995)/Heat (1995).mkv", "Trailer Park Boys (2014).mkv"}
	skipped := []string{
		"Heat (1995)/Trailers/Old/Teaser.mp4", // only a Trailers folder's own files
		"Heat (1995)/Extras/Trailers/Teaser.mp4",
		"Heat (1995)/Featurettes/Making Of.mkv",
		"Heat (1995)/Heat (1995)-sample.mkv",
	}
	for _, rel := range append(append(append([]string{}, trailers...), content...), skipped...) {
		writeOld(t, filepath.Join(root, filepath.FromSlash(rel)), rel)
	}
	sum, err := s.Scan(ctx, lib)
	if err != nil {
		t.Fatal(err)
	}
	if want := len(trailers) + len(content); sum.Added != want || fp.calls() != want {
		t.Fatalf("added %d, probed %d, want %d", sum.Added, fp.calls(), want)
	}
	for _, rel := range trailers {
		if f := fileAt(t, q, lib, rel); kindOf(f) != "trailer" || f.Probe == nil {
			t.Errorf("%s: kind %q, probed %v", rel, kindOf(f), f.Probe != nil)
		}
	}
	for _, rel := range content {
		if f := fileAt(t, q, lib, rel); kindOf(f) != "" {
			t.Errorf("%s: kind %q", rel, kindOf(f))
		}
	}
	for _, rel := range skipped {
		if _, err := q.GetMediaFileByPath(ctx, filepath.Join(root, filepath.FromSlash(rel))); err == nil {
			t.Errorf("%s was read", rel)
		}
	}
	// Out of the library's files, its count and what matching takes up.
	files, err := q.ListMediaFiles(ctx, db.ListMediaFilesParams{LibraryID: lib.ID, Limit: 100})
	if err != nil {
		t.Fatal(err)
	}
	n, _ := q.CountMediaFiles(ctx, db.CountMediaFilesParams{LibraryID: lib.ID})
	pending, _ := q.ListPendingMatches(ctx, lib.ID)
	rematch, _ := q.ListFilesToRematch(ctx, lib.ID)
	needing, _ := q.ListFilesNeedingMatch(ctx, lib.ID)
	if len(files) != len(content) || n != int64(len(content)) || len(pending) != len(content) || len(rematch) != len(content) || len(needing) != len(content) {
		t.Errorf("files %d, count %d, pending %d, rematch %d, needing match %d; want %d each",
			len(files), n, len(pending), len(rematch), len(needing), len(content))
	}
	counts, _ := q.CountMediaFilesByMatch(ctx)
	for _, c := range counts {
		if c.Count != int64(len(content)) {
			t.Errorf("match counts = %+v", counts)
		}
	}
	byStatus, _ := q.CountMediaFilesByStatus(ctx)
	if len(byStatus) != 1 || byStatus[0].Count != int64(len(content)) {
		t.Errorf("file counts = %+v", byStatus)
	}

	// A change inside a Trailers folder, as the watcher reports it.
	writeOld(t, filepath.Join(root, "Heat (1995)", "Trailers", "Teaser 2.mp4"), "new")
	if _, err := s.ScanPaths(ctx, lib, []string{filepath.Join(root, "Heat (1995)", "Trailers", "Teaser 2.mp4")}); err != nil {
		t.Fatal(err)
	}
	if f := fileAt(t, q, lib, "Heat (1995)/Trailers/Teaser 2.mp4"); kindOf(f) != "trailer" {
		t.Errorf("watched trailer kind %q", kindOf(f))
	}
}

// A trailer links to the movie whose file it is named after, or to its
// folder's only movie; never to one of several, or to a folder whose files
// aren't all matched yet.
func TestLinkTrailers(t *testing.T) {
	s, q, _, root, lib := setup(t)
	ctx := context.Background()
	for _, rel := range []string{
		// A flat folder of many movies.
		"Heat (1995).mkv", "Heat (1995)-trailer.mkv", "Ronin (1998).mkv", "Ronin (1998).trailer.mp4", "trailer.mp4",
		// A movie's own folder.
		"Collateral (2004)/Collateral (2004).mkv", "Collateral (2004)/trailer.mp4",
		"Collateral (2004)/movie-trailer.mkv", "Collateral (2004)/Trailers/Teaser.mp4",
		// Two versions of one movie.
		"Thief (1981)/Thief (1981) - 1080p.mkv", "Thief (1981)/Thief (1981) - 2160p.mkv", "Thief (1981)/Trailer.mkv",
		// Two movies in one folder.
		"Double/Alpha (2001).mkv", "Double/Beta (2002).mkv", "Double/trailer.mp4", "Double/Alpha (2001)-trailer.mkv",
		// Its movie isn't matched yet.
		"Pending (2020)/Pending (2020).mkv", "Pending (2020)/trailer.mp4", "Pending (2020)/Pending (2020)-trailer.mkv",
		// Named after no file.
		"Collateral (2004)/Something Else-trailer.mkv",
	} {
		writeOld(t, filepath.Join(root, filepath.FromSlash(rel)), rel)
	}
	if _, err := s.Scan(ctx, lib); err != nil {
		t.Fatal(err)
	}
	heat := movieOf(t, q, lib, "Heat (1995).mkv", 949)
	ronin := movieOf(t, q, lib, "Ronin (1998).mkv", 8195)
	collateral := movieOf(t, q, lib, "Collateral (2004)/Collateral (2004).mkv", 1538)
	thief := movieOf(t, q, lib, "Thief (1981)/Thief (1981) - 1080p.mkv", 11524)
	if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &thief,
		ID: fileAt(t, q, lib, "Thief (1981)/Thief (1981) - 2160p.mkv").ID}); err != nil {
		t.Fatal(err)
	}
	alpha := movieOf(t, q, lib, "Double/Alpha (2001).mkv", 1)
	movieOf(t, q, lib, "Double/Beta (2002).mkv", 2)

	n, err := LinkTrailers(ctx, q, lib)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]int64{
		"Heat (1995)-trailer.mkv":                      heat,
		"Ronin (1998).trailer.mp4":                     ronin,
		"trailer.mp4":                                  0,
		"Collateral (2004)/trailer.mp4":                collateral,
		"Collateral (2004)/movie-trailer.mkv":          collateral,
		"Collateral (2004)/Trailers/Teaser.mp4":        collateral,
		"Collateral (2004)/Something Else-trailer.mkv": 0,
		"Thief (1981)/Trailer.mkv":                     thief,
		"Double/trailer.mp4":                           0,
		"Double/Alpha (2001)-trailer.mkv":              alpha,
		"Pending (2020)/trailer.mp4":                   0,
		"Pending (2020)/Pending (2020)-trailer.mkv":    0,
	}
	linked := 0
	for rel, movie := range want {
		if got := linkedTo(fileAt(t, q, lib, rel)); got != movie {
			t.Errorf("%s linked to %d, want %d", rel, got, movie)
		}
		if movie != 0 {
			linked++
		}
	}
	if n != linked {
		t.Errorf("LinkTrailers changed %d, want %d", n, linked)
	}
	if n, _ := LinkTrailers(ctx, q, lib); n != 0 {
		t.Errorf("linking again changed %d", n)
	}

	// The pending movie is matched: its trailers follow.
	pending := movieOf(t, q, lib, "Pending (2020)/Pending (2020).mkv", 3)
	if n, _ := LinkTrailers(ctx, q, lib); n != 2 {
		t.Errorf("after matching, linking changed %d, want 2", n)
	}
	if got := linkedTo(fileAt(t, q, lib, "Pending (2020)/trailer.mp4")); got != pending {
		t.Errorf("pending trailer linked to %d, want %d", got, pending)
	}

	// Heat's file goes: its trailer is left linked to nothing.
	if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "pending", ID: fileAt(t, q, lib, "Heat (1995).mkv").ID}); err != nil {
		t.Fatal(err)
	}
	if _, err := LinkTrailers(ctx, q, lib); err != nil {
		t.Fatal(err)
	}
	if got := linkedTo(fileAt(t, q, lib, "Heat (1995)-trailer.mkv")); got != 0 {
		t.Errorf("Heat's trailer still linked to %d", got)
	}

	// Deleting a movie lets its trailers go.
	if err := q.DeleteMovie(ctx, collateral); err != nil {
		t.Fatal(err)
	}
	if got := linkedTo(fileAt(t, q, lib, "Collateral (2004)/trailer.mp4")); got != 0 {
		t.Errorf("deleted movie's trailer linked to %d", got)
	}
}

// A library scanned before the server read trailers has a bare trailer.mp4
// matched as a second version of its movie. The first scan after the
// upgrade turns it into a trailer of that movie (PLAN-TRAILERS.md §1, §5.2).
func TestScanTurnsMatchedTrailerIntoTrailer(t *testing.T) {
	s, q, _, root, lib := setup(t)
	ctx := context.Background()
	writeOld(t, filepath.Join(root, "Heat (1995)", "Heat (1995).mkv"), "movie")
	writeOld(t, filepath.Join(root, "Heat (1995)", "trailer.mp4"), "trailer")
	if _, err := s.Scan(ctx, lib); err != nil {
		t.Fatal(err)
	}
	// How an older server left it: library content, matched with the movie.
	heat := movieOf(t, q, lib, "Heat (1995)/Heat (1995).mkv", 949)
	old := fileAt(t, q, lib, "Heat (1995)/trailer.mp4")
	if err := q.SetMediaFileExtra(ctx, db.SetMediaFileExtraParams{ID: old.ID}); err != nil {
		t.Fatal(err)
	}
	cands := `[{"tmdbId":949}]`
	if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &heat, MatchCandidates: &cands, MatchLocked: 1, ID: old.ID}); err != nil {
		t.Fatal(err)
	}
	if files, _ := q.ListMovieFiles(ctx, &heat); len(files) != 2 {
		t.Fatalf("before: the movie has %d files, want 2", len(files))
	}

	sum, err := s.Scan(ctx, lib)
	if err != nil {
		t.Fatal(err)
	}
	if sum.Reparsed != 1 || sum.Added+sum.Updated != 0 {
		t.Errorf("scan summary = %+v", sum)
	}
	f := fileAt(t, q, lib, "Heat (1995)/trailer.mp4")
	if kindOf(f) != "trailer" || f.MovieID != nil || f.MatchStatus != "pending" || f.MatchLocked != 0 || f.MatchCandidates != nil || f.ID != old.ID {
		t.Errorf("after: %+v", f)
	}
	if linkedTo(f) != heat {
		t.Errorf("linked to %d, want %d", linkedTo(f), heat)
	}
	files, _ := q.ListMovieFiles(ctx, &heat)
	if len(files) != 1 || filepath.Base(files[0].Path) != "Heat (1995).mkv" {
		t.Errorf("the movie's files after: %d", len(files))
	}

	// Scanning again changes nothing.
	if sum, _ := s.Scan(ctx, lib); sum.Reparsed != 0 {
		t.Errorf("second scan reparsed %d", sum.Reparsed)
	}
}

// Trailers are linked only in movie libraries; in a TV library they are
// recorded, and stay out of it.
func TestTVLibraryTrailersStayOut(t *testing.T) {
	s, q, _, root, _ := setup(t)
	ctx := context.Background()
	tv, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: filepath.Join(root, "tv")})
	if err != nil {
		t.Fatal(err)
	}
	writeOld(t, filepath.Join(tv.Path, "Harbor Lights", "Season 01", "Harbor Lights - S01E01.mkv"), "ep")
	writeOld(t, filepath.Join(tv.Path, "Harbor Lights", "Trailers", "Season 1 Trailer.mp4"), "trailer")
	if _, err := s.Scan(ctx, tv); err != nil {
		t.Fatal(err)
	}
	if f := fileAt(t, q, tv, "Harbor Lights/Trailers/Season 1 Trailer.mp4"); kindOf(f) != "trailer" || f.ExtraOf != nil {
		t.Errorf("TV trailer: %+v", f)
	}
	if n, err := LinkTrailers(ctx, q, tv); n != 0 || err != nil {
		t.Errorf("LinkTrailers in a TV library = %d, %v", n, err)
	}
	if pending, _ := q.ListPendingMatches(ctx, tv.ID); len(pending) != 1 {
		t.Errorf("pending = %d, want the episode only", len(pending))
	}
}
