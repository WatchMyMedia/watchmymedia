package api

import (
	"fmt"
	"net/http"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
)

// TestShowNextEpisodeOrder checks the show's up-next pick (PLAN-SHOW-PAGE.md,
// decision 10): the episode in progress played last, else the first unfinished
// one after the episode finished last, else the first unfinished one; never
// Specials.
func TestShowNextEpisodeOrder(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	ctx := t.Context()
	q := srv.q
	tv, err := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "TV", Path: "/t"})
	if err != nil {
		t.Fatal(err)
	}
	show, err := q.UpsertShow(ctx, db.UpsertShowParams{LibraryID: tv.ID, TmdbID: 7, Title: "Harbor Lights", Genres: "[]", Credits: "[]"})
	if err != nil {
		t.Fatal(err)
	}
	// Specials (1), Season 1 (4) and Season 2 (2), every episode on disk.
	ep := map[string]int64{}
	for _, s := range []struct{ number, count int64 }{{0, 1}, {1, 4}, {2, 2}} {
		season, err := q.UpsertSeason(ctx, db.UpsertSeasonParams{ShowID: show.ID, Number: s.number, Name: fmt.Sprintf("Season %d", s.number), EpisodeCount: s.count})
		if err != nil {
			t.Fatal(err)
		}
		for n := int64(1); n <= s.count; n++ {
			e, err := q.UpsertEpisode(ctx, db.UpsertEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: n, Title: fmt.Sprintf("S%dE%d", s.number, n)})
			if err != nil {
				t.Fatal(err)
			}
			now := db.FormatTime(time.Now())
			f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: tv.ID, Path: fmt.Sprintf("/t/Harbor Lights/S%02dE%02d.mkv", s.number, n), Size: 1,
				Mtime: now, AddedAt: now, Parsed: "{}", LastSeenAt: now, SeenInScan: 1})
			if err != nil {
				t.Fatal(err)
			}
			if err := q.InsertEpisodeFile(ctx, db.InsertEpisodeFileParams{EpisodeID: e.ID, MediaFileID: f.ID}); err != nil {
				t.Fatal(err)
			}
			ep[fmt.Sprintf("S%dE%d", s.number, n)] = e.ID
		}
	}
	var userID int64
	if err := srv.conn.QueryRowContext(ctx, `SELECT id FROM users ORDER BY id LIMIT 1`).Scan(&userID); err != nil {
		t.Fatal(err)
	}

	// A watch state: done, or this far in, at minute `at` of the test's clock.
	type mark struct {
		ep   string
		done bool
		at   int
	}
	for _, tc := range []struct {
		name  string
		marks []mark
		want  string // "" for none
	}{
		{"nothing watched", nil, "S1E1"},
		{"in progress beats an earlier unwatched episode", []mark{{"S1E3", false, 1}}, "S1E3"},
		{"the one in progress played last", []mark{{"S1E2", false, 1}, {"S2E1", false, 2}}, "S2E1"},
		{"after the last finished, not a skipped one", []mark{{"S1E1", true, 1}, {"S1E3", true, 2}}, "S1E4"},
		{"into the next season", []mark{{"S1E4", true, 1}}, "S2E1"},
		{"back to the first unfinished after the last episode", []mark{{"S1E1", true, 1}, {"S2E2", true, 2}}, "S1E2"},
		{"a season marked at once counts from its end", []mark{{"S1E1", true, 1}, {"S1E2", true, 1}, {"S1E3", true, 1}, {"S1E4", true, 1}}, "S2E1"},
		{"Specials in progress are left out", []mark{{"S0E1", false, 1}}, "S1E1"},
		{"everything finished", []mark{{"S1E1", true, 1}, {"S1E2", true, 1}, {"S1E3", true, 1}, {"S1E4", true, 1}, {"S2E1", true, 2}, {"S2E2", true, 3}}, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := srv.conn.ExecContext(ctx, `DELETE FROM watch_state WHERE user_id = ?`, userID); err != nil {
				t.Fatal(err)
			}
			base := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
			for _, m := range tc.marks {
				done, pos := 0, 600000
				if m.done {
					done, pos = 1, 0
				}
				at := base.Add(time.Duration(m.at) * time.Minute).Format("2006-01-02T15:04:05.000Z")
				if _, err := srv.conn.ExecContext(ctx,
					`INSERT INTO watch_state (user_id, item_type, item_id, position_ms, duration_ms, completed, updated_at) VALUES (?, 'episode', ?, ?, 2880000, ?, ?)`,
					userID, ep[m.ep], pos, done, at); err != nil {
					t.Fatal(err)
				}
			}
			var got ShowDetail
			if code := doJSON(t, srv.client, http.MethodGet, fmt.Sprintf("%s/api/shows/%d", srv.URL, show.ID), "", &got).StatusCode; code != http.StatusOK {
				t.Fatalf("show -> %d", code)
			}
			switch {
			case tc.want == "" && got.NextEpisode != nil:
				t.Errorf("next = S%dE%d, want none", got.NextEpisode.SeasonNumber, got.NextEpisode.EpisodeNumber)
			case tc.want != "" && got.NextEpisode == nil:
				t.Errorf("next = none, want %s", tc.want)
			case tc.want != "" && got.NextEpisode.EpisodeID != ep[tc.want]:
				t.Errorf("next = S%dE%d, want %s", got.NextEpisode.SeasonNumber, got.NextEpisode.EpisodeNumber, tc.want)
			}
		})
	}
}
