package api

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"testing"

	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/seerr"
	"watchmymedia/server/internal/integrations/seerrtest"
)

func TestSeerrAPI(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		d.Integrations = integrations.New(d.Queries, &pathRecorder{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	})
	app := seerrtest.New(t)
	app.Items = []*seerrtest.Item{{MediaType: "movie", TMDBID: 700001, Title: "Quiet Field", Date: "2019-04-02"}}
	app.Users = append(app.Users, seerr.User{ID: 2, DisplayName: "admin", Username: "admin", Permissions: seerr.PermRequest})
	do := func(method, path, body string) (int, string) {
		t.Helper()
		req, _ := http.NewRequestWithContext(t.Context(), method, srv.URL+path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		res, err := srv.client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, string(b)
	}

	if code, body := do(http.MethodPost, "/api/integrations", `{"kind":"seerr","name":"Seerr","url":"`+app.URL+`","apiKey":"`+app.Key+`"}`); code != http.StatusCreated ||
		!strings.Contains(body, `"kind":"seerr"`) || strings.Contains(body, app.Key) {
		t.Fatalf("create -> %d %s", code, body)
	}
	code, body := do(http.MethodGet, "/api/integrations/seerr/users", "")
	var users SeerrUserList
	_ = json.Unmarshal([]byte(body), &users)
	if code != http.StatusOK || len(users.Items) != 2 || users.Items[1].Name != "admin" || !users.Items[1].CanRequest {
		t.Fatalf("seerr users -> %d %s", code, body)
	}

	// Not linked yet: no requests.
	code, body = do(http.MethodGet, "/api/requests/options", "")
	var opts RequestOptions
	_ = json.Unmarshal([]byte(body), &opts)
	if code != http.StatusOK || opts.Backend != "seerr" || opts.Movies || opts.Manage {
		t.Errorf("options unlinked -> %d %s", code, body)
	}
	if code, body := do(http.MethodPost, "/api/requests", `{"mediaType":"movie","tmdbId":700001}`); code != http.StatusForbidden || !strings.Contains(body, "isn't linked") {
		t.Errorf("unlinked request -> %d %s", code, body)
	}

	admin, _ := srv.q.GetUserByName(t.Context(), "admin")
	userPath := "/api/users/" + strconv.FormatInt(admin.ID, 10)
	if code, body := do(http.MethodPatch, userPath, `{"seerrUserId":42}`); code != http.StatusUnprocessableEntity {
		t.Errorf("link to an unknown Seerr user -> %d %s", code, body)
	}
	if code, body := do(http.MethodPatch, userPath, `{"seerrUserId":2}`); code != http.StatusOK || !strings.Contains(body, `"seerrUserId":2`) {
		t.Fatalf("link -> %d %s", code, body)
	}
	if _, body := do(http.MethodGet, "/api/users", ""); !strings.Contains(body, `"seerrUserId":2`) {
		t.Errorf("users = %s", body)
	}
	_, body = do(http.MethodGet, "/api/requests/options", "")
	_ = json.Unmarshal([]byte(body), &opts)
	if !opts.Movies || !opts.Shows || !opts.NeedsApproval {
		t.Errorf("options linked = %s", body)
	}

	if code, body := do(http.MethodPost, "/api/requests", `{"mediaType":"movie","tmdbId":700001}`); code != http.StatusCreated || !strings.Contains(body, `"status":"pending"`) {
		t.Fatalf("request -> %d %s", code, body)
	}
	if code, body := do(http.MethodPost, "/api/requests", `{"mediaType":"movie","tmdbId":700001}`); code != http.StatusConflict || !strings.Contains(body, "requested in Seerr already") {
		t.Errorf("duplicate -> %d %s", code, body)
	}
	code, body = do(http.MethodGet, "/api/requests/lookup?mediaType=movie&tmdbId=700001", "")
	var r Requestable
	_ = json.Unmarshal([]byte(body), &r)
	if code != http.StatusOK || r.State != "requested" || r.Request == nil || !r.Request.Mine {
		t.Errorf("lookup -> %d %s", code, body)
	}
	if code, body := do(http.MethodGet, "/api/requests/search?q=quiet", ""); code != http.StatusOK || !strings.Contains(body, `"state":"requested"`) {
		t.Errorf("search -> %d %s", code, body)
	}
	if code, body := do(http.MethodGet, "/api/requests/mine", ""); code != http.StatusOK || !strings.Contains(body, `"title":"Quiet Field"`) {
		t.Errorf("mine -> %d %s", code, body)
	}
	code, body = do(http.MethodGet, "/api/requests/inbox", "")
	if code != http.StatusOK || !strings.Contains(body, `"requestsUrl":"`+app.URL+`/requests?filter=pending"`) || !strings.Contains(body, `"pending":1`) {
		t.Errorf("inbox -> %d %s", code, body)
	}
}
