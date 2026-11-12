package api

import (
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"testing"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/livetv/dvr"
	"watchmymedia/server/internal/playback"
)

// A recording that finishes while its playback opens is a moment's wait,
// not a server error: 409 with Retry-After, as when it is just starting.
func TestRecordingPlaybackOpenedAsItFinishesIsRetried(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name   string
		err    error
		status int
		retry  bool
	}{
		{"finishing", fmt.Errorf("open recording: %w", dvr.ErrUnavailable), http.StatusConflict, true},
		{"renamed into the library", fmt.Errorf("open recording: %w", &fs.PathError{Op: "stat", Path: "x.partial", Err: fs.ErrNotExist}), http.StatusConflict, true},
		{"too many sessions", playback.ErrTooManySessions, http.StatusServiceUnavailable, true},
		{"replaced", playback.ErrStopped, http.StatusConflict, false},
		{"ffmpeg failed", errors.New("start ffmpeg: no such file"), http.StatusInternalServerError, false},
	} {
		err := recordingStartError(c.err)
		se, ok := errors.AsType[huma.StatusError](err)
		if !ok || se.GetStatus() != c.status {
			t.Errorf("%s: %v, want %d", c.name, err, c.status)
		}
		he, ok := errors.AsType[huma.HeadersError](err)
		if retry := ok && he.GetHeaders().Get("Retry-After") != ""; retry != c.retry {
			t.Errorf("%s: Retry-After %v, want %v", c.name, retry, c.retry)
		}
	}
}
