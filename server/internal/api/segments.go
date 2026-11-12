package api

import (
	"context"
	"fmt"
	"net/http"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/segments"
)

// StoredSegment is one row of a file's segments, as the editor sees them:
// every source, including suppressed ones.
type StoredSegment struct {
	ID         int64   `json:"id"`
	Kind       string  `json:"kind" enum:"intro,recap,credits,preview,commercial"`
	StartMs    int64   `json:"startMs"`
	EndMs      int64   `json:"endMs,omitempty" doc:"0 means to the end of the file."`
	Final      bool    `json:"final"`
	Source     string  `json:"source" enum:"manual,chapter,fingerprint,blackframe,subtitle,external"`
	Confidence float64 `json:"confidence"`
	State      string  `json:"state" enum:"active,suppressed"`
}

type FileSegments struct {
	DurationMs int64              `json:"durationMs"`
	Segments   []StoredSegment    `json:"segments" nullable:"false" doc:"Every stored segment, by start."`
	Effective  []playback.Segment `json:"effective" nullable:"false" doc:"What the player gets: one source per kind, manual first."`
}

type FileSegmentsOutput struct {
	Body FileSegments
}

// ManualSegment is a segment a person draws.
type ManualSegment struct {
	Kind    string `json:"kind" enum:"intro,recap,credits,preview,commercial"`
	StartMs int64  `json:"startMs" minimum:"0"`
	EndMs   int64  `json:"endMs" minimum:"0" doc:"0 means to the end of the file."`
	Final   bool   `json:"final" required:"false" doc:"Credits only: the last block, after which the next episode may start."`
}

type PutSegmentsInput struct {
	ID   int64 `path:"id" doc:"Media file ID."`
	Body struct {
		Manual     []ManualSegment `json:"manual" nullable:"false" doc:"Replaces every manual segment of the file."`
		Suppressed []int64         `json:"suppressed" nullable:"false" doc:"IDs of detected segments to hide; detection will not bring them back."`
		Restored   []int64         `json:"restored" required:"false" doc:"IDs of suppressed segments to show again."`
	}
}

func registerSegments(h huma.API, deps Deps) {
	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "getFileSegments",
		Method:      http.MethodGet,
		Path:        Prefix + "/files/{id}/segments",
		Summary:     "List a file's skip segments, for editing",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *FileIDInput) (*FileSegmentsOutput, error) {
		row, err := deps.Queries.GetMediaFile(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "file not found")
		}
		return fileSegmentsOutput(ctx, deps, row)
	})

	huma.Register(h, adminOp(h, huma.Operation{
		OperationID: "putFileSegments",
		Method:      http.MethodPut,
		Path:        Prefix + "/files/{id}/segments",
		Summary:     "Replace a file's manual skip segments and hide or restore detected ones",
		Description: "Manual segments win over detected ones of the same kind. A hidden detected segment stays as a suppressed row, so re-detection does not bring it back.",
		Tags:        []string{"files"},
	}), func(ctx context.Context, in *PutSegmentsInput) (*FileSegmentsOutput, error) {
		row, err := deps.Queries.GetMediaFile(ctx, in.ID)
		if err != nil {
			return nil, notFound(err, "file not found")
		}
		duration := deref(row.DurationMs)
		for i, m := range in.Body.Manual {
			if m.EndMs != 0 && m.EndMs <= m.StartMs {
				return nil, huma.Error422UnprocessableEntity(fmt.Sprintf("segment %d ends before it starts", i+1))
			}
			if duration > 0 && (m.StartMs >= duration || m.EndMs > duration) {
				return nil, huma.Error422UnprocessableEntity(fmt.Sprintf("segment %d lies past the end of the file", i+1))
			}
		}
		err = segments.InTx(ctx, deps.Conn, deps.Queries, func(q *db.Queries) error {
			if err := q.DeleteManualSegments(ctx, row.ID); err != nil {
				return err
			}
			for _, m := range in.Body.Manual {
				var end *int64
				if m.EndMs > 0 {
					end = &m.EndMs
				}
				final := int64(0)
				if m.Final && m.Kind == "credits" {
					final = 1
				}
				if err := q.InsertSegment(ctx, db.InsertSegmentParams{MediaFileID: row.ID, Kind: m.Kind, StartMs: m.StartMs, EndMs: end, Final: final, Source: "manual", Confidence: 1}); err != nil {
					return err
				}
			}
			for _, id := range in.Body.Suppressed {
				if err := q.SetSegmentState(ctx, db.SetSegmentStateParams{State: "suppressed", ID: id, MediaFileID: row.ID}); err != nil {
					return err
				}
			}
			for _, id := range in.Body.Restored {
				if err := q.SetSegmentState(ctx, db.SetSegmentStateParams{State: "active", ID: id, MediaFileID: row.ID}); err != nil {
					return err
				}
			}
			return nil
		})
		if err != nil {
			return nil, err
		}
		return fileSegmentsOutput(ctx, deps, row)
	})
}

func fileSegmentsOutput(ctx context.Context, deps Deps, row db.MediaFile) (*FileSegmentsOutput, error) {
	duration := deref(row.DurationMs)
	rows, err := deps.Queries.ListFileSegments(ctx, row.ID)
	if err != nil {
		return nil, err
	}
	out := FileSegments{DurationMs: duration, Segments: make([]StoredSegment, 0, len(rows)), Effective: storedSegments(ctx, deps, row.ID, duration)}
	for _, r := range rows {
		out.Segments = append(out.Segments, StoredSegment{ID: r.ID, Kind: r.Kind, StartMs: r.StartMs, EndMs: deref(r.EndMs), Final: r.Final == 1,
			Source: r.Source, Confidence: r.Confidence, State: r.State})
	}
	return &FileSegmentsOutput{Body: out}, nil
}
