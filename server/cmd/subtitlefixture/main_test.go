package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestAutomaticObserverRestoresBodyAndCountsPendingCancellation(t *testing.T) {
	state := &providerState{}
	entered, done, release := make(chan string, 1), make(chan struct{}), make(chan struct{})
	var once sync.Once
	releaseSearch := func() { once.Do(func() { close(release) }) }
	t.Cleanup(releaseSearch)
	const automaticBody = " {\"playId\":\"fictional-play\", \"cancel\":false} \n"
	const cancelBody = "{\"playId\":\"fictional-play\",\"cancel\":true}"
	handler := state.instrumentAutomatic(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
		}
		switch string(body) {
		case automaticBody:
			entered <- string(body)
			<-release
		case cancelBody:
		case "": // Manual picker search is a different route.
		default:
			t.Errorf("observer changed request bytes: %q", body)
		}
		w.WriteHeader(http.StatusOK)
	}))
	go func() {
		defer close(done)
		handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/api/files/1/subtitles/automatic", strings.NewReader(automaticBody)))
	}()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("production handler did not receive the restored automatic body")
	}
	assertCounters := func(attempts, cancels, active int) {
		t.Helper()
		state.mu.Lock()
		defer state.mu.Unlock()
		if state.automaticAttempts != attempts || state.automaticCancels != cancels || state.automaticActive != active {
			t.Fatalf("automatic counts = %d/%d/%d; want %d/%d/%d", state.automaticAttempts, state.automaticCancels, state.automaticActive, attempts, cancels, active)
		}
	}
	assertCounters(1, 0, 1)
	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodPost, "/api/files/1/subtitles/automatic", strings.NewReader(cancelBody)))
	assertCounters(1, 1, 1)
	handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/api/files/1/subtitles/search", nil))
	assertCounters(1, 1, 1)
	releaseSearch()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("automatic handler did not finish after release")
	}
	assertCounters(1, 1, 0)
}

func TestAutomaticObserverRejectsOversizeInsteadOfTruncatingBody(t *testing.T) {
	state := &providerState{}
	handler := state.instrumentAutomatic(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		t.Fatal("a truncated body must not reach the production handler")
	}))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodPost, "/api/files/1/subtitles/automatic", strings.NewReader(strings.Repeat("x", (1<<20)+1))))
	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize status = %d", response.Code)
	}
}
