package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestProbeRequiresExplicitOptInAndDoesNotEchoArguments(t *testing.T) {
	for _, args := range [][]string{nil, {"-help"}, {"-auth"}, {"-token=synthetic-secret"}, {"-live", "synthetic-secret"}} {
		var stdout, stderr bytes.Buffer
		envRead := false
		run(t.Context(), args, func(string) string { envRead = true; return "" }, &stdout, &stderr)
		if envRead || stdout.Len() != 0 || strings.Contains(stderr.String(), "synthetic-secret") {
			t.Fatal("probe read environment without explicit valid opt-in, or echoed secret argument")
		}
	}
}

func TestProbeReadJourneyAndRedactedReport(t *testing.T) {
	for _, historyStatus := range []int{200, 403} {
		t.Run(fmt.Sprint(historyStatus), func(t *testing.T) {
			var paths []string
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				paths = append(paths, r.URL.Path)
				if r.Method != http.MethodGet {
					t.Errorf("probe attempted mutation %s", r.Method)
				}
				if r.URL.Path == "/identity" {
					if r.Header.Get("X-Plex-Token") != "" {
						t.Error("sent credential before server identity check")
					}
				} else if r.Header.Get("X-Plex-Token") != "synthetic-secret" {
					t.Error("selected credential missing")
				}
				w.Header().Set("Content-Type", "application/json")
				switch r.URL.Path {
				case "/identity":
					_, _ = io.WriteString(w, `{"MediaContainer":{"machineIdentifier":"fixture-machine-private","version":"1.43.4-fixture","claimed":true}}`)
				case "/library/sections":
					_, _ = io.WriteString(w, `{"MediaContainer":{"size":1,"Directory":[{"key":"9","type":"movie","title":"Fictional private library"}]}}`)
				case "/library/sections/9/all":
					if r.URL.Query().Get("type") != "1" || r.URL.Query().Get("includeGuids") != "1" {
						t.Error("missing movie/GUID query")
					}
					_, _ = io.WriteString(w, `{"MediaContainer":{"size":2,"totalSize":2,"offset":0,"Metadata":[{"ratingKey":"101","type":"movie","librarySectionID":"9","title":"Fictional private film","viewCount":1,"Guid":[{"id":"tmdb://700001"}]},{"ratingKey":"102","type":"movie","librarySectionID":"9","editionTitle":"Different cut"}]}}`)
				case "/status/sessions/history/all":
					if r.URL.Query().Get("accountID") != "72" || r.URL.Query().Get("librarySectionID") != "9" {
						t.Error("history not filtered to explicit subject and library")
					}
					if historyStatus != 200 {
						w.WriteHeader(historyStatus)
						_, _ = io.WriteString(w, "synthetic-secret provider error")
						return
					}
					_, _ = io.WriteString(w, `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"Metadata":[{"historyKey":"/status/sessions/history/5","ratingKey":"101","accountID":72,"type":"movie","librarySectionID":"9","viewedAt":1700000000,"title":"Fictional private film"}]}}`)
				default:
					t.Errorf("unexpected endpoint %s", r.URL.Path)
					w.WriteHeader(404)
				}
			}))
			defer srv.Close()
			var stdout, stderr bytes.Buffer
			code := run(t.Context(), []string{"-live"}, probeEnv(srv.URL, "fixture-machine-private"), &stdout, &stderr)
			wantCode := 0
			if historyStatus != 200 {
				wantCode = 1
			}
			if code != wantCode || stderr.Len() != 0 {
				t.Fatalf("exit=%d stderr=%s", code, stderr.String())
			}
			var report probeReport
			if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
				t.Fatal(err)
			}
			if report.Items != 2 || report.Watched != 1 || report.UnknownState != 1 || report.WithExternalIDs != 1 || report.State.Status != "read_complete" || report.SubjectBinding != "caller_supplied_not_verified_by_probe" {
				t.Fatalf("wrong report: %s", stdout.String())
			}
			if historyStatus == 200 && report.HistoryEvents != 1 {
				t.Fatal("missing retained history count")
			}
			if report.HistoryCoverage != "all_retained" || report.HistorySince != "" {
				t.Fatal("unbounded history request did not describe retained-history coverage")
			}
			if historyStatus == 403 && report.History.Reason != "authorization" {
				t.Fatal("history refusal was not reported independently of state")
			}
			for _, secret := range []string{"synthetic-secret", "Fictional private", "fixture-machine-private", srv.URL, "700001"} {
				if strings.Contains(stdout.String()+stderr.String(), secret) {
					t.Fatal("report contains source-private data")
				}
			}
			if len(paths) != 4 {
				t.Fatalf("unexpected number of requests: %d", len(paths))
			}
		})
	}
}

func TestIdentityMismatchStopsBeforeCredentialUse(t *testing.T) {
	requests := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		if r.URL.Path != "/identity" || r.Header.Get("X-Plex-Token") != "" {
			t.Error("contacted authenticated endpoint on mismatched server")
		}
		_, _ = io.WriteString(w, `{"MediaContainer":{"machineIdentifier":"different","version":"1.43.4-fixture"}}`)
	}))
	defer srv.Close()
	var stdout, stderr bytes.Buffer
	code := run(t.Context(), []string{"-live"}, probeEnv(srv.URL, "fixture-machine-private"), &stdout, &stderr)
	if code != 1 || requests != 1 || !strings.Contains(stdout.String(), "identity_mismatch") {
		t.Fatalf("unsafe mismatch behavior: code=%d requests=%d report=%s", code, requests, stdout.String())
	}
}

func TestProbeHistorySinceCoverage(t *testing.T) {
	for _, tc := range []struct {
		name        string
		since       string
		query       string
		coverage    string
		canonical   string
		historyCode int
	}{
		{"all retained", "", "", "all_retained", "", 200},
		{"offset normalized", "2023-11-14T17:13:20-05:00", "1699999999", "since", "2023-11-14T22:13:20Z", 200},
		{"fraction normalized", "2023-11-14T22:13:20.999Z", "1699999999", "since", "2023-11-14T22:13:20Z", 200},
		{"failed bounded read", "2023-11-14T22:13:20Z", "1699999999", "since", "2023-11-14T22:13:20Z", 403},
	} {
		t.Run(tc.name, func(t *testing.T) {
			requests, historyRequests := 0, 0
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests++
				switch r.URL.Path {
				case "/identity":
					_, _ = io.WriteString(w, `{"MediaContainer":{"machineIdentifier":"fixture-machine","version":"1.43.4-fixture"}}`)
				case "/library/sections":
					_, _ = io.WriteString(w, `{"MediaContainer":{"size":1,"Directory":[{"key":"9","type":"movie"}]}}`)
				case "/library/sections/9/all":
					_, _ = io.WriteString(w, `{"MediaContainer":{"size":0,"totalSize":0,"offset":0}}`)
				case "/status/sessions/history/all":
					historyRequests++
					if r.URL.Query().Get("viewedAt>") != tc.query || r.URL.Query().Get("accountID") != "72" || r.URL.Query().Get("librarySectionID") != "9" {
						t.Error("history lower bound or subject scope lost")
					}
					w.WriteHeader(tc.historyCode)
					if tc.historyCode == 200 {
						_, _ = io.WriteString(w, `{"MediaContainer":{"size":1,"totalSize":1,"offset":0,"Metadata":[{"historyKey":"/status/sessions/history/5","ratingKey":"101","accountID":72,"type":"movie","librarySectionID":"9","viewedAt":1700000000}]}}`)
					}
				default:
					t.Error("unexpected endpoint")
					w.WriteHeader(404)
				}
			}))
			defer srv.Close()
			baseEnv := probeEnv(srv.URL, "fixture-machine")
			getenv := func(name string) string {
				if name == "WMM_PLEX_PROBE_HISTORY_SINCE" {
					return tc.since
				}
				return baseEnv(name)
			}
			var stdout, stderr bytes.Buffer
			code := run(t.Context(), []string{"-live"}, getenv, &stdout, &stderr)
			wantCode := 0
			if tc.historyCode != 200 {
				wantCode = 1
			}
			var report probeReport
			if err := json.Unmarshal(stdout.Bytes(), &report); err != nil {
				t.Fatal(err)
			}
			if code != wantCode || stderr.Len() != 0 || report.HistoryCoverage != tc.coverage || report.HistorySince != tc.canonical || requests != 4 || historyRequests != 1 {
				t.Fatalf("unexpected bounded-history result: code=%d report=%+v requests=%d/%d", code, report, requests, historyRequests)
			}
			if tc.historyCode == 200 && (report.HistoryEvents != 1 || report.History.Status != "read_complete_ids_checked") {
				t.Fatal("bounded history did not complete")
			}
		})
	}
}

func TestProbeInvalidHistorySinceStopsBeforeRequests(t *testing.T) {
	requests := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		requests++
		w.WriteHeader(500)
	}))
	defer srv.Close()
	for _, tc := range []struct {
		name    string
		since   string
		account string
	}{
		{"malformed", "synthetic-secret", "72"},
		{"date only", "2023-11-14", "72"},
		{"before epoch", "1969-12-31T23:59:59Z", "72"},
		{"missing account", "2023-11-14T22:13:20Z", ""},
		{"invalid account", "2023-11-14T22:13:20Z", "not-an-id"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			baseEnv := probeEnv(srv.URL, "fixture-machine")
			getenv := func(name string) string {
				switch name {
				case "WMM_PLEX_PROBE_HISTORY_SINCE":
					return tc.since
				case "WMM_PLEX_PROBE_ACCOUNT_ID":
					return tc.account
				default:
					return baseEnv(name)
				}
			}
			var stdout, stderr bytes.Buffer
			code := run(t.Context(), []string{"-live"}, getenv, &stdout, &stderr)
			if code != 2 || requests != 0 || stdout.Len() != 0 || strings.Contains(stderr.String(), tc.since) {
				t.Fatal("invalid history configuration reached network or was echoed")
			}
		})
	}
}

func probeEnv(origin, machine string) func(string) string {
	values := map[string]string{
		"WMM_PLEX_PROBE_URL": origin, "WMM_PLEX_PROBE_TOKEN": "synthetic-secret",
		"WMM_PLEX_PROBE_MACHINE_ID": machine, "WMM_PLEX_PROBE_SECTION_ID": "9",
		"WMM_PLEX_PROBE_ACCOUNT_ID": "72",
	}
	return func(key string) string { return values[key] }
}
