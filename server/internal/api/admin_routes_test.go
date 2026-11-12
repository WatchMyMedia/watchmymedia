package api

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/settings"
)

// adminOperations lists the operations adminOp marks, from the API's own
// OpenAPI document, as method and path.
func adminOperations(t *testing.T, h huma.API) map[string][2]string {
	t.Helper()
	out := map[string][2]string{}
	for path, item := range h.OpenAPI().Paths {
		for _, op := range []*huma.Operation{item.Get, item.Put, item.Post, item.Delete, item.Options, item.Head, item.Patch, item.Trace} {
			if op != nil && op.Metadata[adminOnly] == true {
				out[op.OperationID] = [2]string{op.Method, path}
			}
		}
	}
	return out
}

var pathParam = regexp.MustCompile(`\{[^}]+\}`)

// Every operation adminOp marks refuses a signed-in member, before it reads
// its input. New admin routes are covered as soon as they use adminOp.
func TestAdminOperationsRefuseMembers(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	member, _ := securityTestUser(t, srv, "member")
	ops := adminOperations(t, srv.api)
	// Library maintenance (matching, rematching, hiding files, scans) and
	// posters must stay among them.
	for _, id := range []string{
		"searchFileCandidates", "applyFileMatch",
		"lineUpFiles", "matchFiles", "ignoreFiles",
		"searchLibraryCandidates", "previewShowRematch", "rematchShow", "previewMovieRematch", "rematchMovie",
		"scanLibrary", "matchLibrary",
		"suggestSeasonLineUps", "matchSeasons",
		"getMoviePosters", "setMoviePoster", "uploadMoviePoster", "resetMoviePoster",
		"uploadShowPoster", "uploadSeasonPoster",
		"updateSettings",
	} {
		if _, ok := ops[id]; !ok {
			t.Errorf("%s is not an admin operation", id)
		}
	}
	ids := make([]string, 0, len(ops))
	for id := range ops {
		ids = append(ids, id)
	}
	slices.Sort(ids)
	for _, id := range ids {
		method, path := ops[id][0], ops[id][1]
		got := doJSON(t, member, method, srv.URL+pathParam.ReplaceAllString(path, "1"), `{}`, nil)
		var problem struct {
			Detail string `json:"detail"`
		}
		_ = json.Unmarshal(got.Body, &problem)
		if got.StatusCode != http.StatusForbidden || problem.Detail != "admin only" {
			t.Errorf("%s (%s %s) as a member -> %d %s, want 403 admin only", id, method, path, got.StatusCode, got.Body)
		}
	}
	t.Logf("%d admin operations refuse members", len(ids))
}

// A member can't match, rematch, hide or scan; the admin still can.
func TestLibraryMaintenanceIsForAdmins(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	member, _ := securityTestUser(t, srv, "member")
	ctx := t.Context()
	libs, err := srv.q.ListLibraries(ctx)
	if err != nil || len(libs) == 0 {
		t.Fatal(libs, err)
	}
	lib := libs[0]
	var id int64
	if err := srv.conn.QueryRowContext(ctx, "SELECT id FROM media_files WHERE library_id = ?", lib.ID).Scan(&id); err != nil {
		t.Fatal(err)
	}
	file, err := srv.q.GetMediaFile(ctx, id)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct{ method, path, body string }{
		{"POST", fmt.Sprintf("/api/libraries/%d/scan", lib.ID), ""},
		{"POST", fmt.Sprintf("/api/libraries/%d/match?rematch=true", lib.ID), ""},
		{"POST", fmt.Sprintf("/api/libraries/%d/ignore-files", lib.ID), fmt.Sprintf(`{"fileIds":[%d],"ignored":true}`, file.ID)},
		{"POST", fmt.Sprintf("/api/libraries/%d/match-files", lib.ID), fmt.Sprintf(`{"fileIds":[%d],"tmdbId":949}`, file.ID)},
		{"POST", fmt.Sprintf("/api/files/%d/match", file.ID), `{"tmdbId":949}`},
		{"GET", fmt.Sprintf("/api/files/%d/candidates?q=Heat", file.ID), ""},
		{"GET", fmt.Sprintf("/api/libraries/%d/candidates?q=Heat", lib.ID), ""},
		{"POST", "/api/movies/1/rematch", `{"tmdbId":949}`},
		{"POST", "/api/shows/1/rematch", `{"tmdbId":1399}`},
	} {
		if got := doJSON(t, member, c.method, srv.URL+c.path, c.body, nil); got.StatusCode != http.StatusForbidden {
			t.Errorf("member %s %s -> %d %s, want 403", c.method, c.path, got.StatusCode, got.Body)
		}
	}
	after, err := srv.q.GetMediaFile(ctx, file.ID)
	if err != nil {
		t.Fatal(err)
	}
	if after.MatchStatus != file.MatchStatus {
		t.Errorf("a member's requests changed the file: %s -> %s", file.MatchStatus, after.MatchStatus)
	}
	// The admin's own request goes through.
	if got := doJSON(t, srv.client, "POST", fmt.Sprintf("%s/api/libraries/%d/ignore-files", srv.URL, lib.ID), fmt.Sprintf(`{"fileIds":[%d],"ignored":true}`, file.ID), nil); got.StatusCode >= 300 {
		t.Errorf("admin ignore-files -> %d %s", got.StatusCode, got.Body)
	}
}

// A media token fetches media; it can't stop a play or plan one.
func TestMediaTokenOnlyReads(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	var mt MediaToken
	if got := doJSON(t, srv.client, "POST", srv.URL+"/api/auth/media-token", "", &mt); got.StatusCode != http.StatusOK || mt.Token == "" {
		t.Fatalf("media token -> %d %s", got.StatusCode, got.Body)
	}
	anon := &http.Client{}
	tok := "?access_token=" + mt.Token
	for _, c := range []struct{ method, path, body string }{
		{"DELETE", "/api/playback/some-session", ""},
		{"POST", "/api/playback/plans", `{}`},
	} {
		if got := doJSON(t, anon, c.method, srv.URL+c.path+tok, c.body, nil); got.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s %s with a media token -> %d %s, want 401", c.method, c.path, got.StatusCode, got.Body)
		}
	}
	// It still opens media.
	for _, method := range []string{"GET", "HEAD"} {
		if got := doJSON(t, anon, method, srv.URL+"/api/files/1/stream"+tok, "", nil); got.StatusCode == http.StatusUnauthorized {
			t.Errorf("%s stream with a media token -> 401", method)
		}
	}
}

// Deleting a recording's file needs recording access, even when Who can
// delete is everyone.
func TestDeleteRecordingFileNeedsRecordingAccess(t *testing.T) {
	t.Parallel()
	var store *settings.Store
	srv := newTestServerWith(t, func(d *Deps) { store = d.Settings })
	member, _ := securityTestUser(t, srv, "member")
	if err := store.Set(t.Context(), integrations.KeyDeleteAccess, "everyone"); err != nil {
		t.Fatal(err)
	}
	// Recording is off here, so nobody has recording access.
	got := doJSON(t, member, "DELETE", srv.URL+"/api/live-tv/recordings/some-recording/file", "", nil)
	if got.StatusCode != http.StatusForbidden {
		t.Errorf("member without recording access -> %d %s, want 403", got.StatusCode, got.Body)
	}
}

// countingReader notes whether anything read the request body.
type countingReader struct {
	read atomic.Int64
	left int
}

func (r *countingReader) Read(p []byte) (int, error) {
	if r.left <= 0 {
		return 0, io.EOF
	}
	n := min(len(p), r.left)
	for i := range p[:n] {
		p[i] = 'x'
	}
	r.left -= n
	r.read.Add(int64(n))
	return n, nil
}

// A member's poster upload is refused before its body is read.
func TestPosterUploadRefusesMembersUnread(t *testing.T) {
	t.Parallel()
	srv := newTestServer(t)
	member, _ := securityTestUser(t, srv, "member")
	base, err := url.Parse(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	for _, kind := range []string{"movies", "shows", "seasons"} {
		body := &countingReader{left: 8 << 20}
		req := httptest.NewRequestWithContext(t.Context(), http.MethodPost, "/api/"+kind+"/1/poster/upload", body)
		req.Header.Set("Content-Type", "application/octet-stream")
		for _, c := range member.Jar.Cookies(base) {
			req.AddCookie(c)
		}
		req.ContentLength = 8 << 20
		rec := httptest.NewRecorder()
		srv.Config.Handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusForbidden || !strings.Contains(rec.Body.String(), "admin only") {
			t.Errorf("%s upload as a member -> %d %s, want 403 admin only", kind, rec.Code, rec.Body)
		}
		if n := body.read.Load(); n > 0 {
			t.Errorf("%s upload as a member read %d bytes of its body", kind, n)
		}
	}
}
