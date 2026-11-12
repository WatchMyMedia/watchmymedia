package seerr

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"slices"
	"strconv"
	"testing"
)

// TestLiveSeerr checks the client against a real, throwaway Seerr, such as
// deploy/arr/seerr.compose.yaml's:
//
//	WATCHMYMEDIA_TEST_SEERR=http://127.0.0.1:15055 WATCHMYMEDIA_TEST_SEERR_KEY=… \
//	  go test ./internal/integrations/seerr -run Live
//
// It adds two users, asks for a movie and a season nobody has asked for,
// reports an issue, and removes all of it again. WATCHMYMEDIA_TEST_SEERR_QUERY
// picks what to search for (default "the wire"). Never point it at a Seerr
// people use.
func TestLiveSeerr(t *testing.T) {
	base, key := os.Getenv("WATCHMYMEDIA_TEST_SEERR"), os.Getenv("WATCHMYMEDIA_TEST_SEERR_KEY")
	if base == "" || key == "" {
		t.Skip("set WATCHMYMEDIA_TEST_SEERR and WATCHMYMEDIA_TEST_SEERR_KEY to run against a throwaway Seerr")
	}
	query := os.Getenv("WATCHMYMEDIA_TEST_SEERR_QUERY")
	if query == "" {
		query = "the wire"
	}
	ctx := context.Background()
	c := New(base, key, nil)

	st, err := c.Status(ctx)
	if err != nil || st.Version == "" {
		t.Fatalf("status: %+v %v", st, err)
	}
	if _, err := New(base, "wrong", nil).Status(ctx); StatusOf(err) != http.StatusForbidden {
		t.Errorf("a wrong key: %v", err)
	}
	if _, err := c.PublicSettings(ctx); err != nil {
		t.Error(err)
	}

	asker := addUser(t, base, key, PermRequest|PermCreateIssues)
	nobody := addUser(t, base, key, 0)
	users, err := c.Users(ctx)
	if err != nil || !slices.ContainsFunc(users, func(u User) bool { return u.ID == asker && u.CanRequest("tv") }) {
		t.Fatalf("users: %+v %v", users, err)
	}

	found, err := c.Search(ctx, query)
	if err != nil {
		t.Fatal(err)
	}
	var movie, show Media
	for _, m := range found {
		if m.MediaInfo != nil {
			continue
		}
		if m.MediaType == "movie" && movie.ID == 0 {
			movie = m
		}
		if m.MediaType == "tv" && show.ID == 0 {
			show = m
		}
	}
	if movie.ID == 0 || show.ID == 0 {
		t.Fatalf("no movie and show nobody asked for in %q's results", query)
	}
	t.Cleanup(func() {
		for _, m := range []Media{movie, show} {
			if got, err := c.Lookup(ctx, m.MediaType, m.ID); err == nil && got.MediaInfo != nil {
				_ = c.DeleteMedia(ctx, got.MediaInfo.ID)
			}
		}
	})

	if _, err := c.Request(ctx, nobody, "movie", movie.ID, nil); StatusOf(err) != http.StatusForbidden {
		t.Errorf("without permission: %v", err)
	}
	r, err := c.Request(ctx, asker, "movie", movie.ID, nil)
	if err != nil || r.ID == 0 || r.Status != RequestPending || r.RequestedBy == nil || r.RequestedBy.ID != asker {
		t.Fatalf("request: %+v %v", r, err)
	}
	if _, err := c.Request(ctx, asker, "movie", movie.ID, nil); StatusOf(err) != http.StatusConflict {
		t.Errorf("asked again: %v", err)
	}
	tv, err := c.Request(ctx, asker, "tv", show.ID, []int{1})
	if err != nil || !slices.Equal(tv.SeasonNumbers(), []int{1}) {
		t.Fatalf("season request: %+v %v", tv, err)
	}
	if _, err := c.Request(ctx, asker, "tv", show.ID, []int{1}); StatusOf(err) != http.StatusAccepted {
		t.Errorf("nothing left: %v", err)
	}

	m, err := c.Movie(ctx, movie.ID)
	if err != nil || m.MediaInfo == nil || m.MediaInfo.ID == 0 || m.MediaInfo.Status != StatusPending ||
		!slices.ContainsFunc(m.MediaInfo.Requests, func(x MediaRequest) bool { return x.ID == r.ID && x.RequestedBy != nil }) {
		t.Fatalf("lookup: %+v %v", m.MediaInfo, err)
	}
	mine, err := c.UserRequests(ctx, asker, 20)
	if err != nil || len(mine) != 2 || mine[0].Media == nil || mine[0].Media.TMDBID == 0 {
		t.Errorf("user's requests: %+v %v", mine, err)
	}
	if n, err := c.RequestCounts(ctx); err != nil || n.Pending < 2 {
		t.Errorf("counts: %+v %v", n, err)
	}
	if is, err := c.CreateIssue(ctx, asker, m.MediaInfo.ID, IssueAudio, "Out of sync (WatchMyMedia live test)", 0, 0); err != nil || is.ID == 0 {
		t.Errorf("issue: %+v %v", is, err)
	}
	if err := c.DeleteRequest(ctx, nobody, r.ID); StatusOf(err) != http.StatusUnauthorized {
		t.Errorf("someone else withdrew it: %v", err)
	}
	if err := c.DeleteRequest(ctx, asker, r.ID); err != nil {
		t.Errorf("withdraw: %v", err)
	}
	if err := c.DeleteMedia(ctx, m.MediaInfo.ID); err != nil {
		t.Errorf("clear: %v", err)
	}
	if m, err := c.Movie(ctx, movie.ID); err != nil || m.MediaInfo != nil {
		t.Errorf("after clearing: %+v %v", m.MediaInfo, err)
	}
	if err := c.RunJob(ctx, JobSonarrScan); err != nil {
		t.Errorf("job: %v", err)
	}
}

// addUser makes a local Seerr user with the permissions and removes it when
// the test ends; the client itself never makes users.
func addUser(t *testing.T, base, key string, perms int) int {
	t.Helper()
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	name := "wmm-test-" + hex.EncodeToString(b)
	var u User
	call := func(method, path string, body, out any) {
		t.Helper()
		var data []byte
		if body != nil {
			data, _ = json.Marshal(body)
		}
		req, _ := http.NewRequestWithContext(t.Context(), method, base+"/api/v1"+path, bytes.NewReader(data)) //nolint:gosec // G704: the Seerr the opt-in live test is pointed at
		req.Header.Set("X-Api-Key", key)
		req.Header.Set("Content-Type", "application/json")
		res, err := http.DefaultClient.Do(req) //nolint:gosec // G704: as above
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = res.Body.Close() }()
		if res.StatusCode >= 300 {
			t.Fatalf("%s %s -> %d", method, path, res.StatusCode)
		}
		if out != nil {
			_ = json.NewDecoder(res.Body).Decode(out)
		}
	}
	call(http.MethodPost, "/user", map[string]any{"username": name, "email": name + "@example.test", "password": hex.EncodeToString(b) + "-pass"}, &u)
	call(http.MethodPost, "/user/"+strconv.Itoa(u.ID)+"/settings/permissions", map[string]any{"permissions": perms}, nil)
	t.Cleanup(func() { call(http.MethodDelete, "/user/"+strconv.Itoa(u.ID), nil, nil) })
	return u.ID
}
