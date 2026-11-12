package apiclient

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
)

func TestMessageReadsEveryShape(t *testing.T) {
	for body, want := range map[string]string{
		`{"message":"No seasons available to request"}`:                                                       "No seasons available to request",
		`{"status":403,"error":"You do not have permission to access this endpoint"}`:                         "You do not have permission to access this endpoint",
		`[{"propertyName":"Path","errorMessage":"Folder is not writable"},{"errorMessage":"Path is in use"}]`: "Folder is not writable; Path is in use",
		`<html>`: "Bad Gateway",
	} {
		if got := Message(http.StatusBadGateway, []byte(body)); got != want {
			t.Errorf("%s -> %q, want %q", body, got, want)
		}
	}
	if got := Message(http.StatusForbidden, nil); got != "the API key was refused" {
		t.Errorf("empty 403 -> %q", got)
	}
}

func TestDoSendsTheKeyAndReadsTheAnswer(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "k" || r.Header.Get("X-Api-User") != "7" || r.URL.RawQuery != "a=b%20c" {
			t.Errorf("request %s %v", r.URL, r.Header)
		}
		switch r.URL.Path {
		case "/api/v1/accepted":
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"message":"nothing left"}`))
		default:
			_, _ = w.Write([]byte(`{"id":5}`))
		}
	}))
	defer srv.Close()
	c := &Client{App: "Seerr", Base: srv.URL + "/api/v1", Key: "k"}
	call := Call{Method: http.MethodGet, Path: "/thing", Query: "a=b%20c", Header: http.Header{"X-Api-User": {"7"}}}
	var out struct{ ID int }
	if err := c.Do(context.Background(), call, &out); err != nil || out.ID != 5 {
		t.Fatalf("out = %+v, %v", out, err)
	}
	// Any 2xx is success unless the call lists what is.
	call.Path = "/accepted"
	if err := c.Do(context.Background(), call, nil); err != nil {
		t.Errorf("202 with no list = %v", err)
	}
	call.OK = []int{http.StatusOK}
	var e *Error
	if err := c.Do(context.Background(), call, nil); !errors.As(err, &e) || e.App != "Seerr" || e.Status != http.StatusAccepted || e.Message != "nothing left" {
		t.Errorf("202 refused = %v", err)
	}
	if StatusOf(errors.New("other")) != 0 || IsNotFound(nil) {
		t.Error("StatusOf a plain error")
	}
}

// The key headers are not Authorization, so Go would carry them along on a
// redirect to another host. The client follows a redirect only on the same
// host.
func TestRedirectsStayOnTheAppsHost(t *testing.T) {
	var leaked atomic.Value
	other := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		leaked.Store(r.Header.Get("X-Api-Key") + r.Header.Get("X-Api-User"))
	}))
	defer other.Close()
	app := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v3/away":
			http.Redirect(w, r, other.URL+"/steal", http.StatusFound)
		case "/api/v3/moved":
			http.Redirect(w, r, "/api/v3/here", http.StatusMovedPermanently)
		case "/api/v3/here":
			_, _ = w.Write([]byte(`{"id":1}`))
		}
	}))
	defer app.Close()
	c := &Client{App: "Radarr", Base: app.URL + "/api/v3", Key: "secret", HTTP: HTTPClient(Timeout)}
	call := Call{Method: http.MethodGet, Path: "/away", Header: http.Header{"X-Api-User": {"3"}}}
	err := c.Do(context.Background(), call, nil)
	if err == nil || !strings.Contains(err.Error(), "another address") {
		t.Errorf("redirect to another host = %v", err)
	}
	if v, _ := leaked.Load().(string); v != "" {
		t.Errorf("the other host got %q", v)
	}
	var out struct{ ID int }
	if err := c.Do(context.Background(), Call{Method: http.MethodGet, Path: "/moved"}, &out); err != nil || out.ID != 1 {
		t.Errorf("same-host redirect = %+v, %v", out, err)
	}

	down := &http.Request{URL: &url.URL{Scheme: "http", Host: "nas:7878"}}
	via := []*http.Request{{URL: &url.URL{Scheme: "https", Host: "nas:7878"}}}
	if err := SameHost(down, via); err == nil {
		t.Error("https to http was followed")
	}
	up := &http.Request{URL: &url.URL{Scheme: "https", Host: "nas"}}
	if err := SameHost(up, []*http.Request{{URL: &url.URL{Scheme: "http", Host: "NAS"}}}); err != nil {
		t.Errorf("http to https on the same host = %v", err)
	}
}
