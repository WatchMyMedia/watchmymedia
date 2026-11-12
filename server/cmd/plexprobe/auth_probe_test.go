package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type authProbeFixture struct {
	clients, accounts, pms *httptest.Server
	clientsCalls           atomic.Int32
	clientIdentifier       atomic.Value
	polls                  atomic.Int32
	accountCalls           atomic.Int32
	resourceCalls          atomic.Int32
	deviceCalls            atomic.Int32
	pmsCalls               atomic.Int32
	identityCalls          atomic.Int32
	resourcePMSCalls       atomic.Int32
	devicePMSCalls         atomic.Int32
	initialAccountID       int64
	renewedAccountID       int64
	initialRestricted      bool
	renewedRestricted      bool
	resourceToken          string
	resourceOwned          bool
	resourceMachine        string
	resourceDuplicate      bool
	resourcePMSStatus      int
	resourcePMSMalformed   bool
	deviceToken            string
	devicesBody            string
	devicePMSStatus        int
	identityMismatchAt     int32
	neverClaim             bool
	expectedHistorySince   string
}

func newAuthProbeFixture(t *testing.T) *authProbeFixture {
	t.Helper()
	f := &authProbeFixture{initialAccountID: 701, renewedAccountID: 701, resourceToken: "synthetic-server-secret", resourceOwned: true,
		resourceMachine: "synthetic-machine-private", deviceToken: "synthetic-device-secret"}
	f.clients = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.clientsCalls.Add(1)
		w.Header().Set("Content-Type", "application/json")
		if r.Header.Get("X-Plex-Client-Identifier") == "" || r.Header.Get("X-Plex-Token") == "synthetic-unused-existing-token" {
			t.Error("missing fresh identifier or supplied token was used")
		}
		if r.URL.Path != "/api/v2/resources" && r.URL.Path != "/api/v2/devices" && r.Header.Get("X-Plex-Token") != "" {
			t.Error("new-device auth endpoint received existing credential")
		}
		switch r.Method + " " + r.URL.Path {
		case "POST /api/v2/pins":
			f.clientIdentifier.Store(r.Header.Get("X-Plex-Client-Identifier"))
			var body struct {
				JWK    map[string]string `json:"jwk"`
				Strong bool              `json:"strong"`
			}
			if json.NewDecoder(r.Body).Decode(&body) != nil || !body.Strong || body.JWK["x"] == "" || body.JWK["d"] != "" {
				t.Error("new-device request did not carry only the public key")
			}
			_, _ = io.WriteString(w, `{"id":420,"code":"synthetic-transient-link-code","expiresIn":300}`)
		case "GET /api/v2/pins/420":
			if len(strings.Split(r.URL.Query().Get("deviceJWT"), ".")) != 3 {
				t.Error("PIN exchange did not carry signed device proof")
			}
			if f.polls.Add(1) == 1 || f.neverClaim {
				_, _ = io.WriteString(w, `{"id":420,"authToken":null}`)
			} else {
				_, _ = io.WriteString(w, `{"id":420,"authToken":"synthetic-pin-secret"}`)
			}
		case "GET /api/v2/auth/nonce":
			_, _ = io.WriteString(w, `{"nonce":"synthetic-nonce"}`)
		case "POST /api/v2/auth/token":
			var body struct {
				JWT string `json:"jwt"`
			}
			if json.NewDecoder(r.Body).Decode(&body) != nil || len(strings.Split(body.JWT, ".")) != 3 {
				t.Error("renewal did not carry device proof")
			}
			_, _ = io.WriteString(w, `{"auth_token":"synthetic-renewed-secret"}`)
		case "GET /api/v2/resources":
			f.resourceCalls.Add(1)
			if r.Header.Get("X-Plex-Token") != "synthetic-renewed-secret" {
				t.Error("resources did not use verified renewed grant")
			}
			resource := map[string]any{
				"clientIdentifier": f.resourceMachine, "provides": "server", "owned": f.resourceOwned,
				"accessToken": f.resourceToken, "connections": []any{map[string]any{"uri": "https://never-follow.example.invalid"}},
			}
			resources := []any{resource}
			if f.resourceDuplicate {
				resources = append(resources, resource)
			}
			_ = json.NewEncoder(w).Encode(resources)
		case "GET /api/v2/devices":
			f.deviceCalls.Add(1)
			if r.Header.Get("X-Plex-Token") != "synthetic-renewed-secret" || r.URL.RawQuery != "" || f.accountCalls.Load() != 2 || f.resourcePMSCalls.Load() != 1 || f.resourcePMSStatus != http.StatusUnauthorized {
				t.Error("owner bridge lacked the same verified renewed credential or prior resource HTTP 401")
			}
			if f.devicesBody != "" {
				_, _ = io.WriteString(w, f.devicesBody)
			} else {
				_ = json.NewEncoder(w).Encode([]any{map[string]any{
					"clientIdentifier": "synthetic-machine-private", "product": "Plex Media Server", "token": f.deviceToken,
				}})
			}
		default:
			t.Error("unexpected auth/discovery endpoint")
			w.WriteHeader(404)
		}
	}))
	f.accounts = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.accountCalls.Add(1)
		if r.Method != http.MethodGet || r.URL.Path != "/api/v2/user" {
			t.Error("wrong account verification endpoint")
		}
		id := f.initialAccountID
		restricted := f.initialRestricted
		switch r.Header.Get("X-Plex-Token") {
		case "synthetic-pin-secret":
		case "synthetic-renewed-secret":
			id = f.renewedAccountID
			restricted = f.renewedRestricted
		default:
			t.Error("account check used wrong credential")
		}
		_, _ = fmt.Fprintf(w, `{"id":%d,"restricted":%t,"username":"Synthetic private name"}`, id, restricted)
	}))
	f.pms = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.pmsCalls.Add(1)
		if r.Header.Get("X-Plex-Client-Identifier") != f.clientIdentifier.Load() {
			t.Error("PMS reads lost the authenticated device's client identifier")
		}
		if r.Method != http.MethodGet {
			t.Error("PMS probe attempted a mutation")
		}
		if r.URL.Path == "/identity" {
			f.identityCalls.Add(1)
			if r.Header.Get("X-Plex-Token") != "" {
				t.Error("PMS credential used before identity check")
			}
		} else {
			if f.identityCalls.Load() == 0 {
				t.Error("PMS credential sent before anonymous machine preflight")
			}
			switch r.Header.Get("X-Plex-Token") {
			case f.resourceToken:
				if r.URL.Path == "/library/sections" {
					f.resourcePMSCalls.Add(1)
					if f.resourcePMSStatus != 0 {
						w.WriteHeader(f.resourcePMSStatus)
						_, _ = io.WriteString(w, `{"error":"synthetic-server-secret"}`)
						return
					}
					if f.resourcePMSMalformed {
						_, _ = io.WriteString(w, `{"synthetic-server-secret":`)
						return
					}
				}
			case f.deviceToken:
				f.devicePMSCalls.Add(1)
				if f.identityCalls.Load() < 3 || f.deviceCalls.Load() != 1 {
					t.Error("owner device credential sent before repeated anonymous machine verification")
				}
				if f.devicePMSStatus != 0 {
					w.WriteHeader(f.devicePMSStatus)
					return
				}
			default:
				t.Error("PMS received an unexpected or legacy credential")
			}
		}
		switch r.URL.Path {
		case "/identity":
			machine := "synthetic-machine-private"
			if f.identityMismatchAt > 0 && f.identityCalls.Load() >= f.identityMismatchAt {
				machine = "different-synthetic-machine"
			}
			_, _ = fmt.Fprintf(w, `{"MediaContainer":{"machineIdentifier":%q,"version":"1.43.4-fixture","claimed":true}}`, machine)
		case "/library/sections":
			_, _ = io.WriteString(w, `{"MediaContainer":{"size":1,"Directory":[{"key":"9","type":"movie","title":"Synthetic private library"}]}}`)
		case "/library/sections/9/all":
			_, _ = io.WriteString(w, `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"Metadata":[{"ratingKey":"1001","type":"movie","librarySectionID":"9","title":"Synthetic private film","viewCount":1,"Guid":[{"id":"tmdb://700001"}]}]}}`)
		case "/status/sessions/history/all":
			if r.URL.Query().Get("accountID") != "72" {
				t.Error("history substituted the cloud account ID for a PMS account ID")
			}
			if r.URL.Query().Get("viewedAt>") != f.expectedHistorySince {
				t.Error("authentication journey lost the history lower bound")
			}
			_, _ = io.WriteString(w, `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"Metadata":[{"historyKey":"/status/sessions/history/5","ratingKey":"1001","accountID":72,"type":"movie","librarySectionID":"9","viewedAt":1700000000}]}}`)
		default:
			t.Error("unexpected PMS endpoint")
			w.WriteHeader(404)
		}
	}))
	t.Cleanup(f.clients.Close)
	t.Cleanup(f.accounts.Close)
	t.Cleanup(f.pms.Close)
	return f
}

func (f *authProbeFixture) deps() authProbeDeps {
	return authProbeDeps{clientsOrigin: f.clients.URL, accountOrigin: f.accounts.URL,
		wait: func(ctx context.Context, _ time.Duration) error { return ctx.Err() },
	}
}

func (f *authProbeFixture) env(t *testing.T, overrides map[string]string) func(string) string {
	t.Helper()
	values := map[string]string{
		"WMM_PLEX_PROBE_URL": f.pms.URL, "WMM_PLEX_PROBE_MACHINE_ID": "synthetic-machine-private",
		"WMM_PLEX_PROBE_SECTION_ID": "9", "WMM_PLEX_PROBE_CLOUD_ACCOUNT_ID": "701",
	}
	maps.Copy(values, overrides)
	return func(name string) string {
		if name == "WMM_PLEX_PROBE_TOKEN" {
			t.Error("fresh authentication read the existing-token environment variable")
			return "synthetic-unused-existing-token"
		}
		return values[name]
	}
}

func TestAuthProbeFreshGrantJourney(t *testing.T) {
	f := newAuthProbeFixture(t)
	var stdout, stderr bytes.Buffer
	code := runAuthWithDeps(t.Context(), f.env(t, nil), &stdout, &stderr, f.deps())
	if code != 0 {
		t.Fatalf("fresh-grant journey exit %d", code)
	}
	var report authProbeReport
	decoder := json.NewDecoder(&stdout)
	if err := decoder.Decode(&report); err != nil {
		t.Fatal(err)
	}
	if decoder.Decode(new(any)) != io.EOF {
		t.Error("authentication journey emitted more than one report")
	}
	if report.Authentication.Status != "account_verified" || report.CredentialRenewal.Status != "account_verified" || report.OwnerResource.Status != "matched" || report.PMS == nil || report.PMS.Watched != 1 || report.PMS.SubjectBinding != "owner_cloud_account_and_server_resource_verified" {
		t.Fatalf("incomplete auth verdict: %+v", report)
	}
	if report.PMSIdentity.Status != "matched" || report.ResourcePMSAccess.Status != "libraries_read" || report.ResourcePMSAccess.HTTPStatus != 0 || report.OwnerDeviceBridge.Status != "not_run" || report.PMSCredentialSource != "owner_resource" {
		t.Error("successful resource credential was not reported distinctly from the bridge")
	}
	if f.accountCalls.Load() != 2 || f.resourceCalls.Load() != 1 || f.pmsCalls.Load() != 5 || f.polls.Load() != 2 || f.deviceCalls.Load() != 0 {
		t.Error("journey skipped verification or repeated unexpected requests")
	}
	if !strings.Contains(stderr.String(), "https://app.plex.tv/auth#?") || !strings.Contains(stderr.String(), "synthetic-transient-link-code") {
		t.Error("transient sign-in URL not shown on diagnostics")
	}
	b, _ := json.Marshal(report)
	for _, forbidden := range []string{
		"synthetic-transient-link-code", "synthetic-pin-secret", "synthetic-renewed-secret", "synthetic-server-secret",
		"synthetic-unused-existing-token", "synthetic-machine-private", "Synthetic private", f.pms.URL, "700001", "701",
	} {
		if strings.Contains(string(b), forbidden) {
			t.Error("JSON report contains source/private interaction data")
		}
	}
	for _, forbidden := range []string{"synthetic-pin-secret", "synthetic-renewed-secret", "synthetic-server-secret", "synthetic-unused-existing-token", f.pms.URL, "Synthetic private"} {
		if strings.Contains(stderr.String(), forbidden) {
			t.Error("diagnostics contain credential or source data")
		}
	}
}

func TestAuthProbeWrongCloudAccountStopsBeforeResources(t *testing.T) {
	for _, stage := range []string{"initial", "renewed"} {
		t.Run(stage, func(t *testing.T) {
			f := newAuthProbeFixture(t)
			if stage == "initial" {
				f.initialAccountID = 999
			} else {
				f.renewedAccountID = 999
			}
			var stdout, stderr bytes.Buffer
			code := runAuthWithDeps(t.Context(), f.env(t, nil), &stdout, &stderr, f.deps())
			if code != 1 || f.resourceCalls.Load() != 0 || f.pmsCalls.Load() != 0 || !strings.Contains(stdout.String(), "identity_mismatch") {
				t.Error("mismatched cloud account reached resources/PMS or was not reported")
			}
		})
	}
}

func TestAuthProbeMissingOwnedServerCredentialDoesNotFallBack(t *testing.T) {
	for _, missing := range []string{"token", "ownership"} {
		t.Run(missing, func(t *testing.T) {
			f := newAuthProbeFixture(t)
			if missing == "token" {
				f.resourceToken = ""
			} else {
				f.resourceOwned = false
			}
			var stdout, stderr bytes.Buffer
			code := runAuthWithDeps(t.Context(), f.env(t, nil), &stdout, &stderr, f.deps())
			if code != 1 || f.pmsCalls.Load() != 0 || f.deviceCalls.Load() != 0 || !strings.Contains(stdout.String(), "owned_server_credential_unavailable") {
				t.Error("unavailable owned-server credential fell back to owner grant")
			}
		})
	}
}

func TestAuthProbeValidationPrecedesRequests(t *testing.T) {
	f := newAuthProbeFixture(t)
	for _, overrides := range []map[string]string{
		{"WMM_PLEX_PROBE_URL": ""},
		{"WMM_PLEX_PROBE_URL": "https://synthetic-user:synthetic-secret@example.invalid"}, //nolint:gosec // G101: a made-up credential the probe must refuse
		{"WMM_PLEX_PROBE_MACHINE_ID": ""},
		{"WMM_PLEX_PROBE_MACHINE_ID": "../unexpected"},
		{"WMM_PLEX_PROBE_SECTION_ID": "not-a-number"},
		{"WMM_PLEX_PROBE_CLOUD_ACCOUNT_ID": ""},
		{"WMM_PLEX_PROBE_CLOUD_ACCOUNT_ID": "0"},
		{"WMM_PLEX_PROBE_ACCOUNT_ID": "invalid"},
		{"WMM_PLEX_PROBE_ACCOUNT_ID": "72", "WMM_PLEX_PROBE_HISTORY_SINCE": "synthetic-secret"},
		{"WMM_PLEX_PROBE_ACCOUNT_ID": "72", "WMM_PLEX_PROBE_HISTORY_SINCE": "1969-12-31T23:59:59Z"},
		{"WMM_PLEX_PROBE_HISTORY_SINCE": "2023-11-14T22:13:20Z"},
	} {
		var stdout, stderr bytes.Buffer
		code := runAuthWithDeps(t.Context(), f.env(t, overrides), &stdout, &stderr, f.deps())
		if code != 2 || stdout.Len() != 0 || strings.Contains(stderr.String(), "synthetic-secret") {
			t.Error("bad preflight configuration was accepted or echoed")
		}
	}
	if f.clientsCalls.Load() != 0 || f.accountCalls.Load() != 0 || f.pmsCalls.Load() != 0 {
		t.Error("invalid configuration caused requests")
	}
}

func TestAuthProbeCancellationWhileWaiting(t *testing.T) {
	f := newAuthProbeFixture(t)
	f.neverClaim = true
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	deps := f.deps()
	deps.wait = func(ctx context.Context, _ time.Duration) error {
		cancel()
		return authPollWait(ctx, time.Hour)
	}
	var stdout, stderr bytes.Buffer
	code := runAuthWithDeps(ctx, f.env(t, nil), &stdout, &stderr, deps)
	if code != 1 || f.polls.Load() != 1 || f.accountCalls.Load() != 0 || f.resourceCalls.Load() != 0 || f.pmsCalls.Load() != 0 || !strings.Contains(stdout.String(), "canceled") {
		t.Error("canceled pending sign-in continued or was not reported")
	}
}

func TestAuthProbeHistoryIdentityRemainsCallerSupplied(t *testing.T) {
	f := newAuthProbeFixture(t)
	var stdout, stderr bytes.Buffer
	code := runAuthWithDeps(t.Context(), f.env(t, map[string]string{"WMM_PLEX_PROBE_ACCOUNT_ID": "72"}), &stdout, &stderr, f.deps())
	var report authProbeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if code != 0 || report.Authentication.Status != "account_verified" || report.HistorySubject != "caller_supplied_pms_account_id_not_verified" || report.PMS == nil || report.PMS.HistoryEvents != 1 {
		t.Error("verified cloud owner was conflated with caller-supplied PMS history identity")
	}
}

func TestAuthProbeHistorySincePropagates(t *testing.T) {
	f := newAuthProbeFixture(t)
	f.expectedHistorySince = "1699999999"
	var stdout, stderr bytes.Buffer
	code := runAuthWithDeps(t.Context(), f.env(t, map[string]string{
		"WMM_PLEX_PROBE_ACCOUNT_ID": "72", "WMM_PLEX_PROBE_HISTORY_SINCE": "2023-11-14T17:13:20.999-05:00",
	}), &stdout, &stderr, f.deps())
	var report authProbeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if code != 0 || report.Authentication.Status != "account_verified" || report.PMS == nil || report.PMS.HistoryEvents != 1 || report.PMS.HistoryCoverage != "since" || report.PMS.HistorySince != "2023-11-14T22:13:20Z" {
		t.Fatalf("bounded authentication journey: code=%d report=%+v", code, report)
	}
	if f.pmsCalls.Load() != 6 {
		t.Fatal("bounded authentication journey skipped or repeated PMS reads")
	}
}

func TestAuthProbeOwnerDeviceBridgePreservesRejectedResourceEvidence(t *testing.T) {
	f := newAuthProbeFixture(t)
	f.resourcePMSStatus = http.StatusUnauthorized
	var stdout, stderr bytes.Buffer
	code := runAuthWithDeps(t.Context(), f.env(t, map[string]string{"WMM_PLEX_PROBE_ACCOUNT_ID": "72"}), &stdout, &stderr, f.deps())
	var report authProbeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if code != 0 || report.ResourcePMSAccess.Status != "failed" || report.ResourcePMSAccess.Reason != "authorization" || report.ResourcePMSAccess.HTTPStatus != 401 || report.OwnerDeviceBridge.Status != "credential_selected" || report.PMSCredentialSource != "owner_device" || report.PMS == nil || report.PMS.Watched != 1 || report.PMS.HistoryEvents != 1 {
		t.Fatalf("bridge lost the rejected resource attempt or successful PMS result: %+v", report)
	}
	if report.HistorySubject != "caller_supplied_pms_account_id_not_verified" || f.accountCalls.Load() != 2 || f.resourceCalls.Load() != 1 || f.deviceCalls.Load() != 1 || f.identityCalls.Load() != 3 || f.resourcePMSCalls.Load() != 1 || f.devicePMSCalls.Load() != 3 || f.pmsCalls.Load() != 7 {
		t.Error("bridge changed history binding or skipped/repeated credential verification")
	}
	for _, forbidden := range []string{"synthetic-device-secret", "synthetic-server-secret", "synthetic-renewed-secret", "synthetic-pin-secret", "synthetic-unused-existing-token", f.pms.URL, "Synthetic private"} {
		if strings.Contains(stdout.String()+stderr.String(), forbidden) {
			t.Error("bridge report or diagnostics exposed a credential or private source")
		}
	}
}

func TestAuthProbeOnlyHTTP401PermitsOwnerBridge(t *testing.T) {
	for _, tc := range []struct {
		name      string
		status    int
		malformed bool
		kind      string
	}{
		{"forbidden", 403, false, "authorization"},
		{"expired token status", 498, false, "authorization"},
		{"not found", 404, false, "unavailable"},
		{"rate limited", 429, false, "rate_limit"},
		{"server error", 500, false, "http"},
		{"redirect", 302, false, "redirect"},
		{"malformed response", 0, true, "invalid_response"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newAuthProbeFixture(t)
			f.resourcePMSStatus, f.resourcePMSMalformed = tc.status, tc.malformed
			var stdout, stderr bytes.Buffer
			code := runAuthWithDeps(t.Context(), f.env(t, nil), &stdout, &stderr, f.deps())
			var report authProbeReport
			if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
				t.Fatal(err)
			}
			if code != 1 || report.ResourcePMSAccess.Status != "failed" || report.ResourcePMSAccess.Reason != tc.kind || report.ResourcePMSAccess.HTTPStatus != tc.status || report.OwnerDeviceBridge.Status != "not_run" || report.PMSCredentialSource != "not_selected" || report.PMS != nil || f.deviceCalls.Load() != 0 || f.pmsCalls.Load() != 2 {
				t.Fatalf("non-401 failure attempted credential bridge: %+v", report)
			}
		})
	}
}

type authProbeRoundTripper func(*http.Request) (*http.Response, error)

func (fn authProbeRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return fn(r) }

func TestAuthProbeTransportErrorNeverTriggersOwnerBridge(t *testing.T) {
	f := newAuthProbeFixture(t)
	deps := f.deps()
	deps.httpClient = &http.Client{Transport: authProbeRoundTripper(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/library/sections" {
			return nil, errors.New("synthetic-secret unauthorized 401")
		}
		return http.DefaultTransport.RoundTrip(r)
	})}
	var stdout, stderr bytes.Buffer
	code := runAuthWithDeps(t.Context(), f.env(t, nil), &stdout, &stderr, deps)
	var report authProbeReport
	if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
		t.Fatal(err)
	}
	if code != 1 || report.ResourcePMSAccess.Reason != "transport" || report.ResourcePMSAccess.HTTPStatus != 0 || f.deviceCalls.Load() != 0 || f.pmsCalls.Load() != 1 || strings.Contains(stdout.String()+stderr.String(), "synthetic-secret") {
		t.Error("transport failure was mistaken for HTTP 401 or leaked its text")
	}
}

func TestAuthProbeMachineMismatchPreventsCredentialUse(t *testing.T) {
	for _, mismatchAt := range []int32{1, 2, 3} {
		t.Run(fmt.Sprint(mismatchAt), func(t *testing.T) {
			f := newAuthProbeFixture(t)
			f.resourcePMSStatus, f.identityMismatchAt = http.StatusUnauthorized, mismatchAt
			var stdout, stderr bytes.Buffer
			code := runAuthWithDeps(t.Context(), f.env(t, nil), &stdout, &stderr, f.deps())
			if code != 1 || f.devicePMSCalls.Load() != 0 || f.identityCalls.Load() != mismatchAt || !strings.Contains(stdout.String(), "identity_mismatch") {
				t.Error("server identity change received an owner device credential")
			}
			if mismatchAt == 1 && (f.resourcePMSCalls.Load() != 0 || f.deviceCalls.Load() != 0) {
				t.Error("initial identity mismatch received resource credential or triggered bridge")
			}
		})
	}
}

func TestAuthProbeOwnerBridgeFailureNeverFallsBack(t *testing.T) {
	for _, tc := range []struct {
		name   string
		body   string
		status int
	}{
		{"device unavailable", `[]`, 0},
		{"device token rejected", "", http.StatusUnauthorized},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newAuthProbeFixture(t)
			f.resourcePMSStatus = http.StatusUnauthorized
			f.devicesBody, f.devicePMSStatus = tc.body, tc.status
			var stdout, stderr bytes.Buffer
			code := runAuthWithDeps(t.Context(), f.env(t, nil), &stdout, &stderr, f.deps())
			var report authProbeReport
			if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
				t.Fatal(err)
			}
			if code != 1 || f.deviceCalls.Load() != 1 || f.resourcePMSCalls.Load() != 1 || report.ResourcePMSAccess.HTTPStatus != http.StatusUnauthorized {
				t.Error("failed owner bridge tried another credential or lost the resource refusal")
			}
			if tc.body != "" && (report.OwnerDeviceBridge.Status != "failed" || report.PMS != nil || f.devicePMSCalls.Load() != 0) {
				t.Error("unavailable device credential reached PMS")
			}
			if tc.status != 0 && (report.PMS == nil || report.PMS.Library.Status != "failed" || report.PMS.Library.Reason != "authorization" || f.devicePMSCalls.Load() != 1) {
				t.Error("device rejection was not preserved as a failed PMS read")
			}
		})
	}
}

func TestAuthProbeRejectsRestrictedOwnerAndAmbiguousResources(t *testing.T) {
	for _, tc := range []string{"initial restricted", "renewed restricted", "wrong machine", "duplicate resource"} {
		t.Run(tc, func(t *testing.T) {
			f := newAuthProbeFixture(t)
			switch tc {
			case "initial restricted":
				f.initialRestricted = true
			case "renewed restricted":
				f.renewedRestricted = true
			case "wrong machine":
				f.resourceMachine = "other-synthetic-machine"
			case "duplicate resource":
				f.resourceDuplicate = true
			}
			var stdout, stderr bytes.Buffer
			code := runAuthWithDeps(t.Context(), f.env(t, nil), &stdout, &stderr, f.deps())
			if code != 1 || f.pmsCalls.Load() != 0 || f.deviceCalls.Load() != 0 {
				t.Error("unverified ownership reached PMS or owner bridge")
			}
		})
	}
}
