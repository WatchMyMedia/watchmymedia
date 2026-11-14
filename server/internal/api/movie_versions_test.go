package api

import (
	"fmt"
	"net/http"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

// A movie's version whose file was deleted is marked missing by the next
// scan; the movie page lists only the versions still on disk, as the season
// page does for episodes. The Android and Apple apps list every file the
// server sends as a version, so a missing one showed as a second version
// that would not play.
func TestMovieLeavesOutMissingVersions(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	lib, _ := q.GetLibrary(ctx, 1)
	movie, err := q.UpsertMovie(ctx, db.UpsertMovieParams{LibraryID: lib.ID, TmdbID: 949, Title: "Heat", Genres: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	kept, _ := q.GetMediaFileByPath(ctx, "/m/Heat (1995).mkv")
	now := db.FormatTime(time.Now())
	gone, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: "/m/Heat (1995) 4K.mkv", Size: 40, Mtime: now, AddedAt: now, LastSeenAt: now,
		Parsed: `{"kind":"movie","title":"Heat","year":1995}`, SeenInScan: 1})
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range []db.MediaFile{kept, gone} {
		if err := q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", MovieID: &movie.ID, ID: f.ID}); err != nil {
			t.Fatal(err)
		}
	}
	moviePath := fmt.Sprintf("%s/api/movies/%d", srv.URL, movie.ID)

	var before MovieDetail
	doJSON(t, srv.client, http.MethodGet, moviePath, "", &before)
	if len(before.Files) != 2 || before.FileCount != 2 {
		t.Fatalf("with both files present: %d files, fileCount %d; want 2", len(before.Files), before.FileCount)
	}

	if _, err := q.MarkMediaFilesMissing(ctx, []int64{gone.ID}); err != nil {
		t.Fatal(err)
	}
	var after MovieDetail
	if code := doJSON(t, srv.client, http.MethodGet, moviePath, "", &after).StatusCode; code != http.StatusOK {
		t.Fatalf("movie -> %d", code)
	}
	if len(after.Files) != 1 || after.Files[0].ID != kept.ID || after.Files[0].Status != "present" {
		ids := make([]int64, 0, len(after.Files))
		for _, f := range after.Files {
			ids = append(ids, f.ID)
		}
		t.Errorf("files after one went missing = %v; want only %d, the one on disk", ids, kept.ID)
	}
	if after.FileCount != 1 || after.TotalSize != kept.Size {
		t.Errorf("fileCount %d, totalSize %d; want 1 and %d", after.FileCount, after.TotalSize, kept.Size)
	}
	var list MovieList
	doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/libraries/%d/movies", srv.URL, lib.ID), "", &list)
	if list.Total != 1 || list.Items[0].FileCount != 1 {
		t.Errorf("library list = %+v; want the movie with one file", list)
	}

	// With every file gone the movie stays reachable by its link, with no
	// versions to play, until it is matched again or removed.
	if _, err := q.MarkMediaFilesMissing(ctx, []int64{kept.ID}); err != nil {
		t.Fatal(err)
	}
	var none MovieDetail
	if code := doJSON(t, srv.client, http.MethodGet, moviePath, "", &none).StatusCode; code != http.StatusOK {
		t.Fatalf("movie with no files on disk -> %d", code)
	}
	if len(none.Files) != 0 || none.FileCount != 0 || none.Files == nil {
		t.Errorf("movie with no files on disk: files %v, fileCount %d; want an empty list", none.Files, none.FileCount)
	}
}
