// plexprobe is an opt-in, read-only compatibility probe. It is not wired into
// the WatchMyMedia server and never writes its database or Plex watch state.
package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"regexp"
	"strconv"
	"time"

	"watchmymedia/server/internal/plex"
)

const usage = `Plex compatibility probe (explicitly authorized test servers only)

Usage: go run ./cmd/plexprobe -live
       go run ./cmd/plexprobe -live -auth

Required environment variables:
  WMM_PLEX_PROBE_URL          Explicit server origin, including port
  WMM_PLEX_PROBE_TOKEN        User-scoped server token (never a command argument)
  WMM_PLEX_PROBE_MACHINE_ID   Expected Plex machine identifier
  WMM_PLEX_PROBE_SECTION_ID   One movie or TV library section to read
Optional:
  WMM_PLEX_PROBE_ACCOUNT_ID   Verified PMS-local history account ID
  WMM_PLEX_PROBE_HISTORY_SINCE RFC3339 lower bound; requires ACCOUNT_ID
  WMM_PLEX_PROBE_CLIENT_ID    Identifier associated with an existing grant

With -auth, use WMM_PLEX_PROBE_CLOUD_ACCOUNT_ID (expected Plex owner account)
instead of WMM_PLEX_PROBE_TOKEN. A new, in-memory sign-in grant is created;
complete the transient authorization URL shown in the terminal. Existing tokens
are neither used nor migrated. This mode also verifies JWT renewal.

The probe reads one selected library. Output contains counts and reason codes,
not tokens, addresses, account IDs, titles or paths. The caller must establish
which user owns the token and which PMS account ID represents that user.
History coverage is all retained records in that library, or records since the
specified timestamp (rounded down to a UTC second). Neither proves lifetime history.
See internal/plex/COMPATIBILITY.md for setup, limits and the live test matrix.
`

type capability struct {
	Status string `json:"status"`
	Reason string `json:"reason,omitempty"`
}

type probeReport struct {
	Schema          int        `json:"schema"`
	ServerVersion   string     `json:"serverVersion,omitempty"`
	Identity        capability `json:"serverIdentity"`
	Library         capability `json:"selectedLibrary"`
	State           capability `json:"currentState"`
	History         capability `json:"history"`
	HistoryCoverage string     `json:"historyCoverage"`
	HistorySince    string     `json:"historySince,omitempty"`
	SubjectBinding  string     `json:"subjectBinding"`
	Items           int        `json:"items"`
	Watched         int        `json:"watched"`
	UnknownState    int        `json:"unknownState"`
	EditionItems    int        `json:"editionItems"`
	WithExternalIDs int        `json:"itemsWithExternalIDs"`
	StatePages      int        `json:"statePages"`
	HistoryEvents   int        `json:"historyEvents"`
	HistoryPages    int        `json:"historyPages"`
	SkippedHistory  int        `json:"unsupportedHistoryEvents"`
}

var safeVersion = regexp.MustCompile(`^[0-9]+(?:\.[0-9]+){1,3}(?:-[A-Za-z0-9]+)?$`)

func main() {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	os.Exit(run(ctx, os.Args[1:], os.Getenv, os.Stdout, os.Stderr))
}

func run(ctx context.Context, args []string, getenv func(string) string, out, diagnostics io.Writer) int {
	flags := flag.NewFlagSet("plexprobe", flag.ContinueOnError)
	// Flag parsing can echo an unknown argument. A user might mistakenly pass
	// a token as an argument, so never print that parser error.
	flags.SetOutput(io.Discard)
	live := flags.Bool("live", false, "permit network reads against the explicitly configured server")
	auth := flags.Bool("auth", false, "authorize a new Plex owner grant and verify renewal before reading")
	flags.Usage = func() {}
	if err := flags.Parse(args); err != nil || !*live || flags.NArg() != 0 {
		_, _ = io.WriteString(diagnostics, usage)
		if errors.Is(err, flag.ErrHelp) || (err == nil && !*live && flags.NArg() == 0) {
			return 0
		}
		return 2
	}
	if *auth {
		return runAuth(ctx, getenv, out, diagnostics)
	}
	origin, token := getenv("WMM_PLEX_PROBE_URL"), getenv("WMM_PLEX_PROBE_TOKEN")
	machine, sectionID := getenv("WMM_PLEX_PROBE_MACHINE_ID"), getenv("WMM_PLEX_PROBE_SECTION_ID")
	if origin == "" || token == "" || machine == "" || sectionID == "" {
		_, _ = io.WriteString(diagnostics, "Missing required probe configuration; see -help. No requests made.\n")
		return 2
	}
	sectionNumber, err := strconv.ParseInt(sectionID, 10, 64)
	if err != nil || sectionNumber <= 0 {
		_, _ = io.WriteString(diagnostics, "Invalid section ID. No requests made.\n")
		return 2
	}
	accountID, historySince, err := parseHistoryConfig(getenv)
	if err != nil {
		_, _ = fmt.Fprintln(diagnostics, err)
		return 2
	}
	clientID := getenv("WMM_PLEX_PROBE_CLIENT_ID")
	if clientID == "" {
		var randomID [16]byte
		if _, err := rand.Read(randomID[:]); err != nil {
			_, _ = io.WriteString(diagnostics, "Could not create a probe identifier. No requests made.\n")
			return 1
		}
		clientID = "wmm-plex-probe-" + hex.EncodeToString(randomID[:])
	}
	opts := plex.Options{ClientIdentifier: clientID}
	identityClient, err := plex.NewClient(origin, "", opts)
	if err != nil {
		_, _ = fmt.Fprintln(diagnostics, "Invalid server origin:", plex.ErrorKind(err))
		return 2
	}
	client, err := plex.NewClient(origin, token, opts)
	if err != nil {
		_, _ = fmt.Fprintln(diagnostics, "Invalid credential configuration:", plex.ErrorKind(err))
		return 2
	}
	report := probeReport{Schema: 1,
		Identity: capability{Status: "not_run"}, Library: capability{Status: "not_run"},
		State: capability{Status: "not_run"}, History: capability{Status: "not_requested"},
		HistoryCoverage: "not_requested",
		SubjectBinding:  "caller_supplied_not_verified_by_probe",
	}
	if accountID != 0 {
		report.HistoryCoverage = "all_retained"
		if !historySince.IsZero() {
			report.HistoryCoverage = "since"
			report.HistorySince = historySince.Format(time.RFC3339)
		}
	}
	write := func(exitCode int) int {
		encoder := json.NewEncoder(out)
		encoder.SetIndent("", "  ")
		if encoder.Encode(report) != nil {
			_, _ = io.WriteString(diagnostics, "Could not write probe report.\n")
			return 1
		}
		return exitCode
	}
	identity, err := identityClient.VerifyIdentity(ctx, machine)
	if err != nil {
		report.Identity = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		return write(1)
	}
	report.Identity = capability{Status: "matched"}
	if len(identity.Version) <= 64 && safeVersion.MatchString(identity.Version) {
		report.ServerVersion = identity.Version
	}
	sections, err := client.Libraries(ctx)
	if err != nil {
		report.Library = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		return write(1)
	}
	var section *plex.LibrarySection
	for i := range sections {
		if sections[i].ID == sectionID {
			section = &sections[i]
			break
		}
	}
	if section == nil {
		report.Library = capability{Status: "failed", Reason: "section_not_visible"}
		return write(1)
	}
	if section.Type != "movie" && section.Type != "show" {
		report.Library = capability{Status: "failed", Reason: "unsupported_library_type"}
		return write(1)
	}
	report.Library = capability{Status: "visible"}
	status := 0
	snapshot, err := client.Snapshot(ctx, *section, plex.Paging{})
	if err != nil {
		report.State = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		status = 1
	} else {
		report.State = capability{Status: "read_complete"}
		report.Items, report.StatePages = len(snapshot.Items), snapshot.Pages
		for _, item := range snapshot.Items {
			watched, known := item.Watched()
			if watched {
				report.Watched++
			}
			if !known {
				report.UnknownState++
			}
			if item.EditionTitle != "" {
				report.EditionItems++
			}
			if item.ExternalIDs.TMDB != "" || item.ExternalIDs.TVDB != "" || item.ExternalIDs.IMDb != "" {
				report.WithExternalIDs++
			}
		}
	}
	if accountID != 0 {
		history, err := client.History(ctx, plex.HistoryQuery{AccountID: accountID, SectionID: sectionID, Since: historySince})
		if err != nil {
			report.History = capability{Status: "failed", Reason: plex.ErrorKind(err)}
			status = 1
		} else {
			report.History = capability{Status: "read_complete_ids_checked"}
			report.HistoryEvents, report.HistoryPages = len(history.Events), history.Pages
			report.SkippedHistory = history.SkippedUnsupported
		}
	}
	return write(status)
}

// parseHistoryConfig is shared by both entry points so invalid history options
// cannot create a new authentication grant before failing. Errors never include
// environment values: a mistakenly pasted credential must not be echoed.
func parseHistoryConfig(getenv func(string) string) (int64, time.Time, error) {
	var accountID int64
	if value := getenv("WMM_PLEX_PROBE_ACCOUNT_ID"); value != "" {
		id, err := strconv.ParseInt(value, 10, 64)
		if err != nil || id <= 0 {
			return 0, time.Time{}, fmt.Errorf("invalid history account ID; no requests made")
		}
		accountID = id
	}
	value := getenv("WMM_PLEX_PROBE_HISTORY_SINCE")
	if value == "" {
		return accountID, time.Time{}, nil
	}
	if accountID == 0 {
		return 0, time.Time{}, fmt.Errorf("history since requires a history account ID; no requests made")
	}
	since, err := time.Parse(time.RFC3339, value)
	if err != nil || since.Unix() < 0 {
		return 0, time.Time{}, fmt.Errorf("invalid history since; use an RFC3339 timestamp from 1970 onward; no requests made")
	}
	// Plex history filters and viewedAt values have whole-second precision.
	return accountID, since.UTC().Truncate(time.Second), nil
}
