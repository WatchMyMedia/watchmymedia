package api

import (
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/arrtest"
	"watchmymedia/server/internal/livetv"
	"watchmymedia/server/internal/livetv/dvr"
)

// Deleting a recording from the library, with the integrations service the
// server always runs: a recording no app holds is deleted (not refused
// after its file went), Sonarr is left alone when every file is a
// recording, and a season with a Sonarr file and a recording deletes both.
func TestDeleteRecordingsFromTheLibrary(t *testing.T) {
	t.Parallel()
	var svc *integrations.Service
	env := newTestServerWith(t, func(d *Deps) {
		svc = integrations.New(d.Queries, &pathRecorder{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
		d.Integrations = svc
		d.LiveTV = livetv.New(d.Queries, d.Settings, nil)
		recorder := dvr.New(d.Queries, d.Settings, d.LiveTV, nil, dvr.Options{})
		d.Recordings = recorder
		t.Cleanup(recorder.Close)
	})
	ctx := t.Context()
	q := env.q
	root := t.TempDir()
	lib, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Television", Path: root})
	if err != nil {
		t.Fatal(err)
	}
	show, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: lib.ID, TmdbID: 800001, Title: "Harbor Lights", Genres: "[]"})
	s1, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 1, Name: "Season 1", EpisodeCount: 2})
	s2, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: 2, Name: "Season 2", EpisodeCount: 2})
	now := db.FormatTime(time.Now())
	// file puts an episode's file on disk and in the library; owned makes
	// it a finished recording's.
	file := func(season db.Season, number int64, name string, owned bool) (db.Episode, string) {
		t.Helper()
		ep, err := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: number, Title: name})
		if err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(root, "Harbor Lights", fmt.Sprintf("Season %02d", season.Number), name+".ts")
		body := "fictional broadcast " + name
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: path, Size: int64(len(body)), Mtime: now, AddedAt: now, LastSeenAt: now, Parsed: "{}"})
		if err != nil {
			t.Fatal(err)
		}
		_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: ep.ID, MediaFileID: f.ID})
		_ = q.SetMediaFileMatch(ctx, db.SetMediaFileMatchParams{MatchStatus: "matched", ID: f.ID})
		if owned {
			id := "rec-" + name
			data := fmt.Sprintf(`{"id":%q,"title":"Harbor Lights","bytes":%d,"Path":%q,"LibraryID":%d,"gaps":[]}`, id, len(body), path, lib.ID)
			if err := q.PutDVRRecording(ctx, db.PutDVRRecordingParams{ID: id, SourceID: "source", ChannelID: "channel", Number: "7.1",
				StartAt: time.Now().Add(-time.Hour).Unix(), EndAt: time.Now().Unix(), Priority: 1, State: "done", Section: "recorded",
				Path: &path, MediaFileID: &f.ID, Data: data, CreatedAt: time.Now().Unix(), UpdatedAt: time.Now().Unix()}); err != nil {
				t.Fatal(err)
			}
		}
		return ep, path
	}
	_, s1e1 := file(s1, 1, "S01E01", true)
	_, s1e2 := file(s1, 2, "S01E02", false)
	e3, s2e1 := file(s2, 1, "S02E01", true)
	e4, s2e2 := file(s2, 2, "S02E02", true)
	gone := func(path string) bool {
		_, err := os.Stat(path)
		return os.IsNotExist(err)
	}

	// No app is connected: a recording-only episode is the server's to delete.
	var p integrations.DeletePreview
	doJSON(t, env.client, http.MethodGet, env.URL+fmt.Sprintf("/api/delete/preview?type=episode&id=%d", e3.ID), "", &p)
	if !p.Managed || !p.Recording || p.Files != 1 || len(p.Apps) != 0 || p.Kept != 0 {
		t.Errorf("recording preview = %+v", p)
	}
	var out integrations.DeleteResult
	if r := doJSON(t, env.client, http.MethodPost, env.URL+fmt.Sprintf("/api/episodes/%d/delete", e3.ID), `{}`, &out); r.StatusCode != http.StatusOK {
		t.Fatalf("recording delete -> %d %s", r.StatusCode, r.Body)
	}
	if len(out.Apps) != 1 || out.Apps[0].Connection != "WatchMyMedia recordings" || out.Apps[0].Files != 1 || !gone(s2e1) {
		t.Fatalf("recording delete = %+v, file gone %v", out, gone(s2e1))
	}

	// Sonarr holds the series and its own S01E02.
	sonarr := arrtest.New(t, "sonarr")
	sonarr.Series = []map[string]any{{"id": 7, "title": "Harbor Lights", "path": "/tv/Harbor Lights", "tmdbId": 800001, "monitored": true,
		"seasons": []any{map[string]any{"seasonNumber": 1, "monitored": true}, map[string]any{"seasonNumber": 2, "monitored": true}}}}
	sonarr.EpisodeFiles = []map[string]any{
		{"id": 702, "seriesId": 7, "seasonNumber": 1, "path": "/tv/Harbor Lights/Season 01/S01E02.ts", "relativePath": "Season 01/S01E02.ts", "size": 30},
	}
	sonarr.Episodes = []map[string]any{
		{"id": 71, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 1, "hasFile": false, "monitored": true},
		{"id": 72, "seriesId": 7, "seasonNumber": 1, "episodeNumber": 2, "episodeFileId": 702, "hasFile": true, "monitored": true},
		{"id": 81, "seriesId": 7, "seasonNumber": 2, "episodeNumber": 1, "hasFile": false, "monitored": true},
		{"id": 82, "seriesId": 7, "seasonNumber": 2, "episodeNumber": 2, "hasFile": false, "monitored": true},
	}
	if _, err := svc.Create(ctx, integrations.Input{Kind: "sonarr", Name: new("Sonarr"), URL: new(sonarr.URL), APIKey: new(sonarr.Key),
		PathMap: &integrations.PathMap{{From: "/tv", To: root}}}); err != nil {
		t.Fatal(err)
	}

	// Every file a recording: Sonarr isn't asked, or told to unmonitor.
	p = integrations.DeletePreview{}
	doJSON(t, env.client, http.MethodGet, env.URL+fmt.Sprintf("/api/delete/preview?type=episode&id=%d", e4.ID), "", &p)
	if !p.Managed || !p.Recording || p.Files != 1 || len(p.Apps) != 0 {
		t.Errorf("recording preview with Sonarr = %+v", p)
	}
	out = integrations.DeleteResult{}
	if r := doJSON(t, env.client, http.MethodPost, env.URL+fmt.Sprintf("/api/episodes/%d/delete", e4.ID), `{}`, &out); r.StatusCode != http.StatusOK {
		t.Fatalf("recording delete with Sonarr -> %d %s", r.StatusCode, r.Body)
	}
	if len(out.Apps) != 1 || out.Apps[0].Connection != "WatchMyMedia recordings" || !gone(s2e2) {
		t.Fatalf("recording delete with Sonarr = %+v", out)
	}
	if n := len(sonarr.CallsTo(http.MethodPut, "/episode/monitor")) + len(sonarr.CallsTo(http.MethodDelete, "/episodefile/bulk")); n != 0 {
		t.Fatalf("Sonarr was changed for a recording (%d calls)", n)
	}

	// A season with both: Sonarr deletes its file, the server the recording.
	p = integrations.DeletePreview{}
	doJSON(t, env.client, http.MethodGet, env.URL+fmt.Sprintf("/api/delete/preview?type=season&id=%d&season=1", show.ID), "", &p)
	if !p.Recording || len(p.Apps) != 1 || p.Files != 2 || p.Kept != 0 {
		t.Errorf("mixed preview = %+v", p)
	}
	out = integrations.DeleteResult{}
	if r := doJSON(t, env.client, http.MethodPost, env.URL+fmt.Sprintf("/api/shows/%d/seasons/1/delete", show.ID), `{}`, &out); r.StatusCode != http.StatusOK {
		t.Fatalf("mixed delete -> %d %s", r.StatusCode, r.Body)
	}
	if len(out.Apps) != 2 || out.Apps[0].Connection != "Sonarr" || out.Apps[0].Error != "" || out.Apps[1].Connection != "WatchMyMedia recordings" {
		t.Fatalf("mixed delete = %+v", out)
	}
	if del := sonarr.CallsTo(http.MethodDelete, "/episodefile/bulk"); len(del) != 1 {
		t.Errorf("Sonarr file deletes = %+v", del)
	}
	if !gone(s1e1) {
		t.Error("the season's recording is still on disk")
	}
	if gone(s1e2) {
		// The fake Sonarr deletes nothing on disk; the server must not either.
		t.Error("the server deleted Sonarr's file itself")
	}

	// A file that is no recording, in a show no app holds, is still refused.
	other, _ := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: lib.ID, TmdbID: 800002, Title: "Quiet Coast", Genres: "[]"})
	os1, _ := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: other.ID, Number: 1, Name: "Season 1", EpisodeCount: 1})
	plain, _ := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: os1.ID, ShowID: other.ID, Number: 1, Title: "Pilot"})
	path := filepath.Join(root, "Quiet Coast", "S01E01.mkv")
	pf, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: path, Size: 1, Mtime: now, AddedAt: now, LastSeenAt: now, Parsed: "{}"})
	if err != nil {
		t.Fatal(err)
	}
	_ = q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: plain.ID, MediaFileID: pf.ID})
	if r := doJSON(t, env.client, http.MethodPost, env.URL+fmt.Sprintf("/api/episodes/%d/delete", plain.ID), `{}`, nil); r.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("unmanaged delete -> %d, want 422", r.StatusCode)
	}
}
