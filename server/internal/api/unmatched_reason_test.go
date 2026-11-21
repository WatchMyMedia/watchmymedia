package api

import (
	"testing"

	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/metadata"
)

// A file still waiting for its match says "not looked up yet", unless a try
// at it left a note of why it has no result: the database was too busy to
// take one. A file TMDB did not answer for comes first, as before.
func TestUnmatchedReasonOfAWaitingFile(t *testing.T) {
	t.Parallel()
	g := &UnmatchedGroup{Kind: GroupLookup, Files: []UnmatchedFile{{MatchStatus: metadata.StatusPending}}}
	if got := groupReason(library.TypeTV, g); got != "Not looked up on TMDB yet." {
		t.Errorf("a waiting file: %q", got)
	}
	g.Files[0].MatchError = metadata.BusyNote
	if got := groupReason(library.TypeTV, g); got != metadata.BusyNote {
		t.Errorf("a waiting file with a note: %q", got)
	}
	g.Files = append(g.Files, UnmatchedFile{MatchStatus: metadata.StatusFailed, MatchError: "timeout"})
	if got := groupReason(library.TypeTV, g); got != "TMDB did not answer: timeout" {
		t.Errorf("beside a file TMDB did not answer for: %q", got)
	}
}
