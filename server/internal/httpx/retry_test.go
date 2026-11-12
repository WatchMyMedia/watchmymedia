package httpx

import (
	"net/http"
	"testing"
	"time"
)

func TestRetryAfter(t *testing.T) {
	now := time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)
	for _, c := range []struct {
		in   string
		want time.Duration
		ok   bool
	}{
		{"", 0, false},
		{"soon", 0, false},
		{"-1", 0, false},
		{"0", 0, true},
		{"7", 7 * time.Second, true},
		{"86400", time.Minute, true}, // capped
		{now.Add(20 * time.Second).Format(http.TimeFormat), 20 * time.Second, true},
		{now.Add(-time.Hour).Format(http.TimeFormat), 0, true}, // already past
		{now.Add(time.Hour).Format(http.TimeFormat), time.Minute, true},
		{"Sunday, 01-Nov-26 12:00:05 GMT", 5 * time.Second, true}, // RFC 850, which HTTP dates may also be
	} {
		got, ok := RetryAfter(c.in, now, time.Minute)
		if got != c.want || ok != c.ok {
			t.Errorf("RetryAfter(%q) = %v %v, want %v %v", c.in, got, ok, c.want, c.ok)
		}
	}
}
