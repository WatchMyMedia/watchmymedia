// Package httpx holds what the server's clients for outside services share:
// keeping request URLs, and the keys some services take in them, out of
// errors, and reading replies with a size limit that fails rather than
// cuts.
package httpx

import (
	"errors"
	"net/url"
)

// Redact returns err, as http.Client.Do returns it, with the request URL cut
// down to its scheme, host and path. TMDB takes its key in the query, and an
// *url.Error prints the whole URL, so the key would reach the log, the
// database and API replies. The result is still an *url.Error around the same
// cause, so errors.Is (context.Canceled, context.DeadlineExceeded) and
// net.Error's Timeout keep working.
func Redact(err error) error {
	var ue *url.Error
	if !errors.As(err, &ue) {
		return err
	}
	return &url.Error{Op: ue.Op, URL: redactURL(ue.URL), Err: ue.Err}
}

func redactURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return "(address hidden)"
	}
	u.User, u.RawQuery, u.ForceQuery, u.Fragment, u.RawFragment = nil, "", false, "", ""
	return u.String()
}
