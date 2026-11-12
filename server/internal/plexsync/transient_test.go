package plexsync

import (
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/plex/plextest"
)

type transientTransport func(*http.Request) (*http.Response, error)

func (f transientTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// failRequests changes only the selected fictional provider operation. The
// production adapter still decodes HTTP status and Retry-After itself.
func failRequests(client **http.Client, status int, match func(*http.Request) bool) func() {
	original := *client
	base := original.Transport
	if base == nil {
		base = http.DefaultTransport
	}
	copy := *original
	copy.Transport = transientTransport(func(r *http.Request) (*http.Response, error) {
		if match(r) {
			return &http.Response{StatusCode: status, Header: http.Header{"Retry-After": []string{"600"}}, Body: io.NopCloser(strings.NewReader("")), Request: r}, nil
		}
		return base.RoundTrip(r)
	})
	*client = &copy
	return func() { *client = original }
}

func TestTransientProviderReadsResumeTheSameRun(t *testing.T) {
	stages := []struct {
		name    string
		cloud   bool
		history bool
		match   func(*http.Request) bool
	}{
		{"owner renewal", true, false, func(r *http.Request) bool { return r.URL.Path == "/api/v2/auth/nonce" }},
		{"profile credential", true, false, func(r *http.Request) bool { return r.URL.Path == "/api/home/users/706/switch" }},
		{"person libraries", false, false, func(r *http.Request) bool {
			return r.URL.Path == "/library/sections" && r.Header.Get("X-Plex-Token") == "synthetic-server-706"
		}},
		{"watched paging", false, false, func(r *http.Request) bool {
			return strings.HasPrefix(r.URL.Path, "/library/sections/") && strings.HasSuffix(r.URL.Path, "/all")
		}},
		{"history accounts", false, true, func(r *http.Request) bool { return r.URL.Path == "/accounts" }},
		{"history paging", false, true, func(r *http.Request) bool { return r.URL.Path == "/status/sessions/history/all" }},
		{"history corroboration", false, true, func(r *http.Request) bool {
			return r.URL.Path == "/status/sessions/history/all" && r.URL.Query().Get("accountID") == "706"
		}},
	}
	for _, stage := range stages {
		for _, status := range []int{http.StatusTooManyRequests, http.StatusServiceUnavailable} {
			t.Run(fmt.Sprintf("%s/%d", stage.name, status), func(t *testing.T) {
				e, c := runEnv(t)
				e.seedHistory()
				member := historyEvent("902", 706, plex.Item{RatingKey: "108", Type: "movie", LibrarySectionID: "1", Title: "Copper", ExternalIDs: plex.ExternalIDs{TMDB: "810003"}})
				e.f.Set(func(f *plextest.Fake) {
					f.HistoryEvents["owner"]["1"] = append(f.HistoryEvents["owner"]["1"], member)
					f.HistoryEvents["synthetic-server-706"] = map[string][]plex.HistoryEvent{"1": {member}}
				})
				r := e.previewRun(c, false)
				client := &e.svc.server
				if stage.cloud {
					client = &e.svc.cloud
				}
				restore := failRequests(client, status, stage.match)
				now := time.Now().UTC().Truncate(time.Millisecond)
				e.svc.now = func() time.Time { return now }
				r = e.tickRun(c, r)
				if r.Status != "queued" {
					t.Fatalf("transient HTTP %d became terminal: %+v", status, r)
				}
				saved, err := e.q.GetPlexRun(t.Context(), r.ID)
				if err != nil || saved.Attempt != 1 || saved.RetryAt != stamp(now.Add(10*time.Minute)) {
					t.Fatalf("Retry-After lost: run=%+v error=%v", saved, err)
				}
				if stage.history && r.Counts.Marked != 2 || !stage.history && r.Counts.Marked != 0 {
					t.Fatalf("incomplete watched snapshot wrote marks, or history lost its checkpoint: %+v", r)
				}
				// Recover before the deadline; an early tick must not consume another
				// attempt or complete the waiting run.
				restore()
				if early := e.tickRun(c, r); early.Status != "queued" {
					t.Fatalf("retry ignored its deadline: %+v", early)
				}
				now = now.Add(10 * time.Minute)
				r = e.tickRun(c, r)
				saved, err = e.q.GetPlexRun(t.Context(), r.ID)
				if err != nil || saved.Attempt != 2 || r.Status != "complete" || r.Counts.Marked != 2 || r.Counts.HistoryImported != 2 {
					t.Fatalf("same-run recovery lost or duplicated effects: run=%+v saved=%+v error=%v", r, saved, err)
				}
				if e.count("plays") != 0 || e.count("watch_state") != 2 || e.count("plex_history_events") != 2 {
					t.Fatal("retry changed measured playback or duplicated imported facts")
				}
			})
		}
	}
}

func TestTransientRetriesStopAfterThreeAttempts(t *testing.T) {
	e, c := runEnv(t)
	r := e.previewRun(c, false)
	failRequests(&e.svc.cloud, http.StatusTooManyRequests, func(*http.Request) bool { return true })
	now := time.Now().UTC().Truncate(time.Millisecond)
	e.svc.now = func() time.Time { return now }
	for attempt := int64(1); attempt <= 3; attempt++ {
		r = e.tickRun(c, r)
		saved, err := e.q.GetPlexRun(t.Context(), r.ID)
		if err != nil || saved.Attempt != attempt || r.Reason != "rate_limit" {
			t.Fatalf("attempt %d: %+v %v", attempt, saved, err)
		}
		if attempt < 3 {
			if r.Status != "queued" || saved.RetryAt != stamp(now.Add(10*time.Minute)) {
				t.Fatalf("attempt did not wait for Retry-After: %+v", saved)
			}
		} else if r.Status != "failed" {
			t.Fatalf("retry limit was exceeded: %+v", saved)
		}
		now = now.Add(10 * time.Minute)
	}
	if next := e.tickRun(c, r); next.Status != "failed" {
		t.Fatalf("terminal attempt restarted automatically: %+v", next)
	}
}

func TestTransientHistoryFailureResumesCommittedPage(t *testing.T) {
	e, c := runEnv(t)
	events := make([]plex.HistoryEvent, 201)
	for i := range events {
		events[i] = historyEvent(strconv.Itoa(10000+i), 1, plex.Item{RatingKey: "101", Type: "movie", LibrarySectionID: "1", Title: "Beacon", ExternalIDs: plex.ExternalIDs{TMDB: "810001", IMDb: "tt0810001"}})
	}
	e.f.Set(func(f *plextest.Fake) { f.HistoryEvents["owner"] = map[string][]plex.HistoryEvent{"1": events} })
	r := e.previewRun(c, false)
	restore := failRequests(&e.svc.server, http.StatusTooManyRequests, func(r *http.Request) bool {
		return r.URL.Path == "/status/sessions/history/all" && r.URL.Query().Get("X-Plex-Container-Start") == "200"
	})
	now := time.Now().UTC().Truncate(time.Millisecond)
	e.svc.now = func() time.Time { return now }
	r = e.tickRun(c, r)
	if r.Status != "queued" || r.Counts.Marked != 2 || r.Counts.HistoryImported != 200 || e.count("plex_history_events") != 200 {
		t.Fatalf("retry lost the already committed page: %+v", r)
	}
	restore()
	now = now.Add(10 * time.Minute)
	r = e.tickRun(c, r)
	if r.Status != "complete" || r.Counts.Marked != 2 || r.Counts.HistoryImported != 201 || e.count("plex_history_events") != 201 {
		t.Fatalf("resumed page duplicated or skipped effects: %+v", r)
	}
}

func TestPermanentAndDisabledHistoryDoNotRetry(t *testing.T) {
	for _, disabled := range []bool{false, true} {
		t.Run(fmt.Sprintf("recording disabled=%t", disabled), func(t *testing.T) {
			e, c := runEnv(t)
			r := e.previewRun(c, false)
			if disabled {
				if _, err := e.conn.ExecContext(t.Context(), "UPDATE users SET record_history=0"); err != nil {
					t.Fatal(err)
				}
				failRequests(&e.svc.server, http.StatusTooManyRequests, func(r *http.Request) bool { return r.URL.Path == "/accounts" })
			} else {
				failRequests(&e.svc.server, http.StatusNotFound, func(r *http.Request) bool { return r.URL.Path == "/status/sessions/history/all" })
			}
			r = e.tickRun(c, r)
			if disabled && (r.Status != "complete" || r.History != "disabled") || !disabled && (r.Status != "partial" || r.History != "partial") {
				t.Fatalf("permanent/privacy outcome turned into a retry: %+v", r)
			}
			if r.Counts.Marked != 2 {
				t.Fatal("optional history failure blocked watched marks")
			}
		})
	}
}
