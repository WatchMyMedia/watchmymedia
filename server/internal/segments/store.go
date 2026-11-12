package segments

import (
	"context"
	"database/sql"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
)

// FileVersion identifies what a detector looked at.
type FileVersion struct {
	ID    int64
	Mtime string
	Size  int64
}

// tombstoneMs is how close a detected segment may come to one a person
// deleted before it counts as the same one and is not stored.
const tombstoneMs = 3000

// Replace stores what one detector (source) found in a file: its earlier
// active segments go, the new ones are added unless a person deleted one of
// the same kind near the same place, and the analysis is recorded so the
// file is not looked at again until it or the algorithm changes.
func Replace(ctx context.Context, conn *sql.DB, q *db.Queries, f FileVersion, source string, algoVersion int64, found []playback.Segment) error {
	return InTx(ctx, conn, q, func(q *db.Queries) error {
		existing, err := q.ListFileSegments(ctx, f.ID)
		if err != nil {
			return err
		}
		if err := q.DeleteActiveSegmentsFromSource(ctx, db.DeleteActiveSegmentsFromSourceParams{MediaFileID: f.ID, Source: source}); err != nil {
			return err
		}
	next:
		for _, s := range found {
			for _, e := range existing {
				if e.State == "suppressed" && e.Kind == s.Kind && abs(e.StartMs-s.StartMs) <= tombstoneMs {
					continue next
				}
			}
			var end *int64
			if s.EndMs > 0 {
				end = &s.EndMs
			}
			final := int64(0)
			if s.Final {
				final = 1
			}
			if err := q.InsertSegment(ctx, db.InsertSegmentParams{MediaFileID: f.ID, Kind: s.Kind, StartMs: s.StartMs, EndMs: end,
				Final: final, Source: source, Confidence: s.Confidence}); err != nil {
				return err
			}
		}
		return q.UpsertSegmentAnalysis(ctx, db.UpsertSegmentAnalysisParams{MediaFileID: f.ID, Source: source,
			AlgoVersion: algoVersion, FileMtime: f.Mtime, FileSize: f.Size})
	})
}

// Analysed reports whether source has looked at this version of the file
// with algoVersion.
func Analysed(ctx context.Context, q *db.Queries, f FileVersion, source string, algoVersion int64) bool {
	a, err := q.GetSegmentAnalysis(ctx, db.GetSegmentAnalysisParams{MediaFileID: f.ID, Source: source})
	return err == nil && a.AlgoVersion == algoVersion && a.FileMtime == f.Mtime && a.FileSize == f.Size
}

// InTx runs fn in a transaction on conn (db.Queries.Transaction) when the
// connection is known, else on q as it is.
func InTx(ctx context.Context, conn *sql.DB, q *db.Queries, fn func(q *db.Queries) error) error {
	if conn == nil {
		return fn(q)
	}
	return db.New(conn).Transaction(ctx, fn)
}
