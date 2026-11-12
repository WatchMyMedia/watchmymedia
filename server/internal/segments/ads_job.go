package segments

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"time"

	"watchmymedia/server/internal/bgwork"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/playback"
)

type AdOptions struct {
	FFmpeg string
	// Candidates returns at most 50 finalized, linked files without a current
	// analysis, using the recording and segment-analysis indexes.
	Candidates func(context.Context) ([]db.MediaFile, error)
	Enabled    func(context.Context) bool
	Gate       bgwork.Gate
}
type AdDetector struct {
	q    *db.Queries
	conn *sql.DB
	bus  *events.Bus
	log  *slog.Logger
	opts AdOptions
}

func NewAdDetector(q *db.Queries, conn *sql.DB, bus *events.Bus, log *slog.Logger, opts AdOptions) *AdDetector {
	return &AdDetector{q: q, conn: conn, bus: bus, log: log, opts: opts}
}

// Run owns and awaits one worker. Events coalesce instead of queueing a
// worker per recording; a restart recovers work from durable analyses.
func (d *AdDetector) Run(ctx context.Context) {
	sub, stop := d.bus.Subscribe()
	defer stop()
	timer := time.NewTimer(30 * time.Second)
	defer timer.Stop()
	var running chan error
	dirty := false
	for {
		select {
		case <-ctx.Done():
			if running != nil {
				if err := <-running; err != nil {
					d.log.Debug("ad analysis stopped", "err", err)
				}
			}
			return
		case e := <-sub:
			if e.Type != "live.recordings.changed" && e.Type != "library.changed" && e.Type != "match.finished" {
				continue
			}
			if running != nil {
				dirty = true
			} else {
				timer.Reset(30 * time.Second)
			}
		case <-timer.C:
			if running != nil {
				dirty = true
				continue
			}
			running = make(chan error, 1)
			go func(done chan<- error) { done <- d.RunOnce(ctx) }(running)
		case err := <-running:
			running = nil
			if err != nil && ctx.Err() == nil {
				d.log.Warn("ad analysis stopped", "err", err)
			}
			if dirty {
				dirty = false
				timer.Reset(30 * time.Second)
			}
		}
	}
}
func (d *AdDetector) RunOnce(ctx context.Context) error {
	if d.opts.Candidates == nil {
		return nil
	}
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		if d.opts.Enabled == nil || !d.opts.Enabled(ctx) {
			return nil
		}
		rows, err := d.opts.Candidates(ctx)
		if err != nil {
			return err
		}
		if len(rows) == 0 {
			return nil
		}
		// The provider contract is bounded; do not accept an accidental library
		// materialization from a future implementation.
		if len(rows) > 50 {
			rows = rows[:50]
		}
		completed := 0
		for _, row := range rows {
			if !d.opts.Enabled(ctx) {
				return nil
			}
			version := FileVersion{ID: row.ID, Mtime: row.Mtime, Size: row.Size}
			if Analysed(ctx, d.q, version, "blackframe", AdBreakAlgoVersion) {
				continue
			}
			found, err := d.detect(ctx, row)
			switch {
			case err == nil:
			case errors.Is(err, errAdUnreadable):
				// Recorded as looked at, with nothing found, so one bad
				// recording neither stops the ones after it nor runs again
				// on every event; a changed file or detector tries again.
				d.log.Warn("could not look for ad breaks in a recording", "file", row.ID, "err", err)
				found = nil
			default:
				// ffmpeg didn't start, the gate failed, or the server is
				// stopping: nothing was learned, and the next run tries again.
				return err
			}
			current, err := d.q.GetMediaFile(ctx, row.ID)
			if err != nil {
				return err
			}
			if current.Mtime != row.Mtime || current.Size != row.Size || current.Status != "present" {
				continue
			}
			if !d.opts.Enabled(ctx) {
				return nil
			}
			if err = Replace(ctx, d.conn, d.q, version, "blackframe", AdBreakAlgoVersion, found); err != nil {
				return err
			}
			completed++
			d.bus.Publish(events.Event{Type: "live.recordings.changed", Data: struct{}{}})
		}
		if completed == 0 {
			return nil
		}
	}
}

// detect looks for ad breaks in one recording. A recording with no video (a
// radio channel) has no pictures to go black, so it has none.
func (d *AdDetector) detect(ctx context.Context, row db.MediaFile) ([]playback.Segment, error) {
	info := parseProbe(row.Probe)
	if info != nil && len(info.Video) == 0 {
		return nil, nil
	}
	duration := int64(0)
	if row.DurationMs != nil {
		duration = *row.DurationMs
	} else if info != nil {
		duration = info.DurationMs
	}
	return DetectAdBreaks(ctx, d.opts.FFmpeg, row.Path, adTimeout(duration, row.Size), d.opts.Gate)
}
