package httpx

import (
	"net/http"
	"strconv"
	"strings"
	"time"
)

// RetryAfter reads a Retry-After header, given in seconds or as an HTTP date
// (RFC 9110 §10.2.3), as a wait from now of at most limit. ok is false when
// the header is empty or in neither form.
func RetryAfter(value string, now time.Time, limit time.Duration) (wait time.Duration, ok bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0, false
	}
	if seconds, err := strconv.ParseInt(value, 10, 64); err == nil {
		if seconds < 0 {
			return 0, false
		}
		if seconds > int64(limit/time.Second) {
			return limit, true
		}
		return time.Duration(seconds) * time.Second, true
	}
	if when, err := http.ParseTime(value); err == nil {
		return max(0, min(when.Sub(now), limit)), true
	}
	return 0, false
}
