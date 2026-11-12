package api

import (
	"fmt"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"path/filepath"
	"testing"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/metadata"
)

func TestSeasonLineUpRoutes(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, withTMDB(t))
	ctx := t.Context()
	q := srv.q
	tv, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "Shows", Path: filepath.FromSlash("/s")})
	other, _ := q.UpsertLibrary(ctx, db.UpsertLibraryParams{Type: "tv", Name: "More", Path: filepath.FromSlash("/o")})
	file := func(lib db.Library, rel string, season, episode int) int64 {
		f, err := q.UpsertMediaFile(ctx, db.UpsertMediaFileParams{LibraryID: lib.ID, Path: filepath.Join(lib.Path, filepath.FromSlash(rel)), Size: 1, Mtime: db.FormatTime(time.Now()),
			AddedAt: db.FormatTime(time.Now()), Parsed: fmt.Sprintf(`{"kind":"episode","title":"Harbor Lights","season":%d,"episodes":[%d]}`, season, episode),
			LastSeenAt: db.FormatTime(time.Now()), SeenInScan: 1})
		if err != nil {
			t.Fatal(err)
		}
		return f.ID
	}
	// Season 1 has two episodes on TMDB, so E03 runs on into season 2.
	e3 := file(tv, "Harbor Lights/S01E03.mkv", 1, 3)
	elsewhere := file(other, "Harbor Lights/S01E03.mkv", 1, 3)
	base := srv.URL + fmt.Sprintf("/api/libraries/%d", tv.ID)

	var list SeasonLineUpList
	if code := doJSON(t, srv.client, http.MethodPost, base+"/season-lineups/suggest", fmt.Sprintf(`{"fileIds":[%d],"tmdbId":1396}`, e3), &list).StatusCode; code != http.StatusOK {
		t.Fatalf("suggest -> %d", code)
	}
	want := metadata.SeasonRule{FromSeason: 1, FromEpisode: 3, TMDBID: 1396, ToSeason: 2, ToEpisode: 1}
	if len(list.Seasons) != 1 || list.Seasons[0].Suggestion == nil || list.Seasons[0].Suggestion.Rule != want || list.Seasons[0].Suggestion.Source != metadata.SourceRunOn {
		t.Fatalf("suggestions = %+v", list.Seasons)
	}
	rule := `"rule":{"fromSeason":1,"fromEpisode":3,"tmdbId":1396,"toSeason":2,"toEpisode":1}`

	var pv metadata.SeasonPreview
	doJSON(t, srv.client, http.MethodPost, base+"/season-lineups/preview", fmt.Sprintf(`{"fileIds":[%d],%s}`, e3, rule), &pv)
	if pv.Found != 1 || len(pv.Files) != 1 || len(pv.Files[0].To) != 1 || pv.Files[0].To[0].Title != "Return" || len(pv.Seasons) != 2 || len(pv.Episodes) != 1 {
		t.Fatalf("preview = %+v", pv)
	}

	// A file of another library is refused, and nothing is stored.
	if code := doJSON(t, srv.client, http.MethodPost, base+"/match-seasons", fmt.Sprintf(`{"lineUps":[{"fileIds":[%d],%s}],"remember":true}`, elsewhere, rule), nil).StatusCode; code != http.StatusUnprocessableEntity {
		t.Errorf("another library's file -> %d", code)
	}

	// Members have no part in matching.
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"kid","password":"kidpass1"}`, nil).StatusCode; code != http.StatusCreated {
		t.Fatalf("create user -> %d", code)
	}
	jar, _ := cookiejar.New(nil)
	kid := &http.Client{Jar: jar}
	doJSON(t, kid, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"kid","password":"kidpass1"}`, nil)
	match := fmt.Sprintf(`{"lineUps":[{"fileIds":[%d],%s}],"remember":true}`, e3, rule)
	for _, c := range []struct{ method, path, body string }{
		{http.MethodPost, "/season-lineups/suggest", fmt.Sprintf(`{"fileIds":[%d],"tmdbId":1396}`, e3)},
		{http.MethodPost, "/season-lineups/preview", fmt.Sprintf(`{"fileIds":[%d],%s}`, e3, rule)},
		{http.MethodPost, "/match-seasons", match},
		{http.MethodGet, "/season-lineups", ""},
		{http.MethodDelete, "/season-lineups?folder=x&fromSeason=1&fromEpisode=1", ""},
	} {
		if code := doJSON(t, kid, c.method, base+c.path, c.body, nil).StatusCode; code != http.StatusForbidden {
			t.Errorf("member %s %s -> %d, want 403", c.method, c.path, code)
		}
	}

	var res metadata.GroupResult
	if code := doJSON(t, srv.client, http.MethodPost, base+"/match-seasons", match, &res).StatusCode; code != http.StatusOK {
		t.Fatalf("match -> %d", code)
	}
	if res.Matched != 1 || res.Folder != "Harbor Lights" || res.ShowID == nil {
		t.Fatalf("result = %+v", res)
	}
	if row, _ := q.GetMediaFile(ctx, e3); row.MatchStatus != metadata.StatusMatched || row.MatchLocked != 1 {
		t.Errorf("file = %s locked=%d", row.MatchStatus, row.MatchLocked)
	}

	var kept RememberedSeasonLineUpList
	doJSON(t, srv.client, http.MethodGet, base+"/season-lineups", "", &kept)
	if len(kept.Items) != 1 || kept.Items[0].Folder != "Harbor Lights" || kept.Items[0].Rule != want || kept.Items[0].ShowTitle != "Harbor Lights" {
		t.Fatalf("remembered = %+v", kept.Items)
	}
	forget := base + "/season-lineups?" + url.Values{"folder": {"Harbor Lights"}, "fromSeason": {"1"}, "fromEpisode": {"3"}}.Encode()
	if code := doJSON(t, srv.client, http.MethodDelete, forget, "", nil).StatusCode; code != http.StatusNoContent {
		t.Errorf("forget -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodDelete, forget, "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("forget again -> %d", code)
	}
	if row, _ := q.GetMediaFile(ctx, e3); row.MatchStatus != metadata.StatusMatched {
		t.Errorf("file after forgetting = %s", row.MatchStatus)
	}
}
