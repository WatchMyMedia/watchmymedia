package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"strings"
	"testing"

	"watchmymedia/server/internal/netguard"
)

// adminOp answers before reading the request: a member gets "admin only"
// and an admin away from the admin networks the network message, even for
// a body that would not pass validation.
func TestAdminOp(t *testing.T) {
	t.Parallel()
	srv := newTestServerWith(t, func(d *Deps) {
		trusted, _ := netguard.Parse([]string{"127.0.0.1", "::1"})
		admin, _ := netguard.Parse([]string{"local"})
		d.Net = netguard.New(trusted, admin)
	})
	if code := doJSON(t, srv.client, http.MethodPost, srv.URL+"/api/users", `{"name":"kid","password":"kidpass1"}`, nil).StatusCode; code != http.StatusCreated {
		t.Fatalf("create user -> %d", code)
	}
	jar, _ := cookiejar.New(nil)
	kid := &http.Client{Jar: jar}
	if code := doJSON(t, kid, http.MethodPost, srv.URL+"/api/auth/login", `{"name":"kid","password":"kidpass1"}`, nil).StatusCode; code != http.StatusOK {
		t.Fatalf("kid login -> %d", code)
	}
	call := func(c *http.Client, from, body string) (int, string) {
		t.Helper()
		req, _ := http.NewRequestWithContext(t.Context(), http.MethodPut, srv.URL+"/api/settings", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		if from != "" {
			req.Header.Set("X-Forwarded-For", from)
		}
		res, err := c.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		var problem struct {
			Status int    `json:"status"`
			Detail string `json:"detail"`
		}
		_ = json.Unmarshal(b, &problem)
		if res.StatusCode >= 400 && problem.Status != res.StatusCode {
			t.Errorf("problem status %d, response %d: %s", problem.Status, res.StatusCode, b)
		}
		return res.StatusCode, problem.Detail
	}
	if code, detail := call(kid, "", `{"backupKeep":"not a number"}`); code != http.StatusForbidden || detail != "admin only" {
		t.Errorf("member -> %d %q, want 403 admin only", code, detail)
	}
	if code, detail := call(srv.client, "203.0.113.9", `{"backupKeep":"not a number"}`); code != http.StatusForbidden || !strings.Contains(detail, "home network") {
		t.Errorf("admin from the internet -> %d %q, want 403 for the network", code, detail)
	}
	if code, _ := call(srv.client, "", `{"backupKeep":"not a number"}`); code != http.StatusUnprocessableEntity {
		t.Errorf("admin with a bad body -> %d, want 422", code)
	}
	if code, _ := call(srv.client, "", `{"backupKeep":5}`); code != http.StatusOK {
		t.Errorf("admin -> %d", code)
	}
}
