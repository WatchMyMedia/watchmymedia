package api

import (
	"fmt"
	"net/http"
	"strings"
	"testing"

	"watchmymedia/server/internal/integrations"
	"watchmymedia/server/internal/integrations/arrtest"
)

// The connection routes: details, change, test, webhook, disconnect.
func TestIntegrationRoutes(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, withIntegrations)
	app := arrtest.New(t, "sonarr")
	var c integrations.Connection
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/integrations", `{"kind":"sonarr","name":"Sonarr","url":"`+app.URL+`","apiKey":"`+app.Key+`"}`, &c).StatusCode; code != http.StatusCreated {
		t.Fatalf("create -> %d", code)
	}
	one := fmt.Sprintf("%s/api/integrations/%d", srv.URL, c.ID)

	var list IntegrationList
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/integrations", "", &list).StatusCode; code != 200 || len(list.Items) != 1 || len(list.ServerAddresses) == 0 {
		t.Errorf("list -> %d %+v", code, list)
	}
	var details IntegrationDetail
	if code := doJSON(t, srv.client, http.MethodGet, one, "", &details).StatusCode; code != 200 || details.Name != "Sonarr" || (details.Details == nil && details.DetailsError == "") {
		t.Errorf("get -> %d %+v", code, details)
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/integrations/999", "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("missing -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodPatch, one, `{"name":"Sonarr 4K"}`, &c).StatusCode; code != 200 || c.Name != "Sonarr 4K" {
		t.Errorf("rename -> %d %+v", code, c)
	}
	if code := doJSON(t, srv.client, http.MethodPatch, one, `{"apiKey":"wrong"}`, nil).StatusCode; code != http.StatusBadGateway {
		t.Errorf("refused key -> %d, want 502", code)
	}

	var res TestResult
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/integrations/test", `{"kind":"sonarr","url":"`+app.URL+`","apiKey":"`+app.Key+`"}`, &res).StatusCode; code != 200 || !res.OK || !strings.Contains(res.Message, app.Version) {
		t.Errorf("test -> %d %+v", code, res)
	}
	// The saved key, by the connection's ID.
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/integrations/test", fmt.Sprintf(`{"kind":"sonarr","url":%q,"id":%d}`, app.URL, c.ID), &res).StatusCode; code != 200 || !res.OK {
		t.Errorf("test with the saved key -> %d %+v", code, res)
	}
	// A refusal is a result, not a failed request.
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/integrations/test", `{"kind":"sonarr","url":"`+app.URL+`","apiKey":"wrong"}`, &res).StatusCode; code != 200 || res.OK || res.Message == "" {
		t.Errorf("test with a wrong key -> %d %+v", code, res)
	}
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/integrations/test", fmt.Sprintf(`{"kind":"sonarr","url":%q,"id":999}`, app.URL), nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("test with a missing connection -> %d", code)
	}

	if code := doJSON(t, srv.client, http.MethodPost, one+"/webhook/connect", `{"address":"http://192.0.2.10:27587"}`, &c).StatusCode; code != 200 {
		t.Errorf("connect webhook -> %d %+v", code, c)
	}
	if len(app.Notifications) == 0 {
		t.Error("the app has no webhook")
	}
	if code := doJSON(t, srv.client, http.MethodGet, srv.URL+"/api/integrations/seerr/users", "", nil).StatusCode; code < 400 {
		t.Errorf("Seerr users without Seerr -> %d, want an error", code)
	}

	if code := doJSON(t, srv.client, http.MethodDelete, one, "", nil).StatusCode; code != http.StatusNoContent {
		t.Errorf("delete -> %d", code)
	}
	if code := doJSON(t, srv.client, http.MethodDelete, one, "", nil).StatusCode; code != http.StatusNotFound {
		t.Errorf("delete again -> %d", code)
	}
}
