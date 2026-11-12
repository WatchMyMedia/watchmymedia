package httpx

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestRedactKeepsTheCauseAndDropsTheQuery(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	addr := srv.URL
	srv.Close() // nothing listens there now

	req, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, addr+"/3/movie/1?api_key=secret123", nil)
	_, err := http.DefaultClient.Do(req) //nolint:bodyclose // the request fails
	if err == nil {
		t.Fatal("no error from a closed server")
	}
	red := Redact(err)
	if msg := red.Error(); strings.Contains(msg, "secret123") || strings.Contains(msg, "api_key") || !strings.Contains(msg, "/3/movie/1") {
		t.Errorf("redacted = %q", msg)
	}
	if _, ok := errors.AsType[*net.OpError](red); !ok {
		t.Errorf("the dial error is gone: %v", red)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Millisecond)
	defer cancel()
	slow := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	defer slow.Close()
	req, _ = http.NewRequestWithContext(ctx, http.MethodGet, slow.URL+"/x?api_key=secret123", nil)
	_, err = http.DefaultClient.Do(req) //nolint:bodyclose // the request fails
	if red := Redact(err); !errors.Is(red, context.DeadlineExceeded) || strings.Contains(red.Error(), "secret123") {
		t.Errorf("deadline = %v", red)
	}
	if other := errors.New("plain"); Redact(other) != other { //nolint:errorlint // the very same error, not one like it
		t.Error("an error without a URL changed")
	}
}
