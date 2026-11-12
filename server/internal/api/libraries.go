package api

import (
	"context"
	"net/http"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/metadata"
	"watchmymedia/server/internal/scanner"
)

type Library struct {
	ID             int64      `json:"id" doc:"Library ID."`
	Type           string     `json:"type" enum:"movie,tv" doc:"Kind of content in the library."`
	Name           string     `json:"name" example:"Movies"`
	Path           string     `json:"path" doc:"Root folder of the library on the server."`
	FileCount      int64      `json:"fileCount" doc:"Video files currently present."`
	MissingCount   int64      `json:"missingCount" doc:"Files seen before that are gone now."`
	PendingCount   int64      `json:"pendingCount" doc:"Present files not yet matched to TMDB."`
	UnmatchedCount int64      `json:"unmatchedCount" doc:"Present files with no confident TMDB match, or whose lookup failed."`
	LastScannedAt  *time.Time `json:"lastScannedAt,omitempty"`
	// LastScanError says why the last scan failed (the folder is missing or
	// can't be read) until a full scan succeeds.
	LastScanError   *string    `json:"lastScanError,omitempty" required:"false" doc:"Why the last scan failed, such as an unmounted or unreadable folder; gone once a full scan succeeds."`
	LastScanErrorAt *time.Time `json:"lastScanErrorAt,omitempty" required:"false" doc:"When the last scan failed."`
	// Scan is set while a scan of this library is running.
	Scan *scanner.ScanProgress `json:"scan,omitempty"`
	// Match is set while metadata matching for this library is running.
	Match *metadata.MatchProgress `json:"match,omitempty"`
	// Watch is how much of the library's folder the watcher covers: changes
	// it sees show up within seconds, the rest at the next scheduled scan.
	// Always sent; optional in the schema so apps still read older servers.
	Watch     scanner.WatchStatus `json:"watch,omitzero"`
	CreatedAt time.Time           `json:"createdAt"`
	UpdatedAt time.Time           `json:"updatedAt"`
}

type LibraryList struct {
	Items []Library `json:"items" nullable:"false"`
}

type ListLibrariesOutput struct {
	Body LibraryList
}

type LibraryIDInput struct {
	ID int64 `path:"id" doc:"Library ID."`
}

type GetLibraryOutput struct {
	Body Library
}

type ScanRequested struct {
	Queued bool `json:"queued"`
}

type ScanLibraryOutput struct {
	Status int
	Body   ScanRequested
}

type MatchLibraryInput struct {
	ID      int64 `path:"id" doc:"Library ID."`
	Rematch bool  `query:"rematch" doc:"Also retry files that were unmatched or failed before."`
}

func registerLibraries(h huma.API, deps Deps) {
	huma.Register(h, huma.Operation{
		OperationID: "listLibraries",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries",
		Summary:     "List libraries",
		Tags:        []string{"libraries"},
	}, func(ctx context.Context, _ *struct{}) (*ListLibrariesOutput, error) {
		rows, err := deps.Queries.ListLibraries(ctx)
		if err != nil {
			return nil, err
		}
		counts, err := fileCounts(ctx, deps)
		if err != nil {
			return nil, err
		}
		out := &ListLibrariesOutput{}
		out.Body.Items = make([]Library, 0, len(rows))
		for _, r := range rows {
			out.Body.Items = append(out.Body.Items, libraryFromDB(deps, r, counts[r.ID]))
		}
		return out, nil
	})

	huma.Register(h, huma.Operation{
		OperationID: "getLibrary",
		Method:      http.MethodGet,
		Path:        Prefix + "/libraries/{id}",
		Summary:     "Get a library",
		Tags:        []string{"libraries"},
	}, func(ctx context.Context, in *LibraryIDInput) (*GetLibraryOutput, error) {
		row, err := getLibrary(ctx, deps, in.ID)
		if err != nil {
			return nil, err
		}
		counts, err := fileCounts(ctx, deps)
		if err != nil {
			return nil, err
		}
		return &GetLibraryOutput{Body: libraryFromDB(deps, row, counts[row.ID])}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "scanLibrary",
		Method:        http.MethodPost,
		Path:          Prefix + "/libraries/{id}/scan",
		Summary:       "Scan a library now",
		Description:   "Queues a scan of the library folder. Progress is reported on the events stream.",
		Tags:          []string{"libraries"},
		DefaultStatus: http.StatusAccepted,
	}), func(ctx context.Context, in *LibraryIDInput) (*ScanLibraryOutput, error) {
		if _, err := getLibrary(ctx, deps, in.ID); err != nil {
			return nil, err
		}
		if deps.Scanner == nil {
			return nil, huma.Error503ServiceUnavailable("scanner not running")
		}
		deps.Scanner.Request(in.ID)
		return &ScanLibraryOutput{Status: http.StatusAccepted, Body: ScanRequested{Queued: true}}, nil
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID:   "matchLibrary",
		Method:        http.MethodPost,
		Path:          Prefix + "/libraries/{id}/match",
		Summary:       "Match the library's files to TMDB now",
		Description:   "Queues metadata matching for pending files; with rematch=true, unmatched and failed files are retried too. Progress is reported on the events stream.",
		Tags:          []string{"libraries"},
		DefaultStatus: http.StatusAccepted,
	}), func(ctx context.Context, in *MatchLibraryInput) (*ScanLibraryOutput, error) {
		if _, err := getLibrary(ctx, deps, in.ID); err != nil {
			return nil, err
		}
		if err := tmdbReady(deps); err != nil {
			return nil, err
		}
		deps.Matcher.Request(in.ID, in.Rematch)
		return &ScanLibraryOutput{Status: http.StatusAccepted, Body: ScanRequested{Queued: true}}, nil
	})
}

type statusCounts struct{ present, missing, pending, unmatched int64 }

// fileStatusCounts counts each library's present and missing files.
func fileStatusCounts(ctx context.Context, deps Deps) (map[int64]statusCounts, error) {
	rows, err := deps.Queries.CountMediaFilesByStatus(ctx)
	if err != nil {
		return nil, err
	}
	out := map[int64]statusCounts{}
	for _, r := range rows {
		c := out[r.LibraryID]
		switch r.Status {
		case "present":
			c.present = r.Count
		case "missing":
			c.missing = r.Count
		}
		out[r.LibraryID] = c
	}
	return out, nil
}

// fileCounts adds the files still to match to fileStatusCounts.
func fileCounts(ctx context.Context, deps Deps) (map[int64]statusCounts, error) {
	out, err := fileStatusCounts(ctx, deps)
	if err != nil {
		return nil, err
	}
	matches, err := deps.Queries.CountMediaFilesByMatch(ctx)
	if err != nil {
		return nil, err
	}
	for _, r := range matches {
		c := out[r.LibraryID]
		switch r.MatchStatus {
		case metadata.StatusPending:
			c.pending = r.Count
		case metadata.StatusUnmatched, metadata.StatusFailed:
			c.unmatched += r.Count
		}
		out[r.LibraryID] = c
	}
	return out, nil
}

func libraryFromDB(deps Deps, r db.Library, c statusCounts) Library {
	lib := Library{
		ID:             r.ID,
		Type:           r.Type,
		Name:           r.Name,
		Path:           r.Path,
		FileCount:      c.present,
		MissingCount:   c.missing,
		PendingCount:   c.pending,
		UnmatchedCount: c.unmatched,
		LastScannedAt:  db.ParseTimePtr(r.LastScannedAt),
		LastScanError:  r.LastScanError,
		CreatedAt:      db.ParseTime(r.CreatedAt),
		UpdatedAt:      db.ParseTime(r.UpdatedAt),
	}
	if r.LastScanError != nil {
		lib.LastScanErrorAt = db.ParseTimePtr(r.LastScanErrorAt)
	}
	lib.Watch = scanner.WatchStatus{State: "off"}
	if deps.Scanner != nil {
		if p, ok := deps.Scanner.Progress(r.ID); ok {
			lib.Scan = &p
		}
		lib.Watch = deps.Scanner.WatchStatus(r.ID)
	}
	if deps.Matcher != nil {
		if p, ok := deps.Matcher.Progress(r.ID); ok {
			lib.Match = &p
		}
	}
	return lib
}
