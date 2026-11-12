package segments

import (
	"bytes"
	"compress/flate"
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/playback"
	"watchmymedia/server/internal/probe"
)

// minEpisodeMs is the shortest file worth fingerprinting.
const minEpisodeMs = 3 * 60_000

// Detector finds intros and credits in the background: after scans and
// matching settle, each season with a new or changed episode is
// fingerprinted (cached per file) and compared episode to episode.
type Detector struct {
	q    *db.Queries
	conn *sql.DB
	bus  *events.Bus
	dec  Decoder
	log  *slog.Logger
	// Delay is how long to wait after a library change before looking, so
	// a scan's matching can finish first.
	Delay time.Duration
	// Every is how often to look regardless.
	Every time.Duration
	// runOnce is RunOnce, which tests replace.
	runOnce func(context.Context) error
}

func NewDetector(q *db.Queries, conn *sql.DB, bus *events.Bus, dec Decoder, log *slog.Logger) *Detector {
	d := &Detector{q: q, conn: conn, bus: bus, dec: dec, log: log, Delay: 2 * time.Minute, Every: 6 * time.Hour}
	d.runOnce = d.RunOnce
	return d
}

// Run looks for work once Delay after start, after library changes, and
// every Every, until ctx ends; it returns once a run in progress has
// stopped. It keeps reading events while a run is in progress (the bus
// drops events for a subscriber that falls behind), and a change seen
// meanwhile brings another run Delay after this one.
func (d *Detector) Run(ctx context.Context) {
	sub, stop := d.bus.Subscribe()
	defer stop()
	timer := time.NewTimer(d.Delay)
	defer timer.Stop()
	ticker := time.NewTicker(d.Every)
	defer ticker.Stop()
	var running chan error // a run in progress, else nil
	dirty := false         // something changed while it ran
	for {
		select {
		case <-ctx.Done():
			if running != nil {
				if err := <-running; err != nil {
					d.log.Debug("segment detection stopped", "err", err)
				}
			}
			return
		case e := <-sub:
			if e.Type != "match.finished" && e.Type != "library.changed" {
				continue
			}
			if running != nil {
				dirty = true
			} else {
				timer.Reset(d.Delay)
			}
		case <-ticker.C:
			if running != nil {
				dirty = true
			} else {
				timer.Reset(0)
			}
		case <-timer.C:
			if running != nil {
				dirty = true
				continue
			}
			running = make(chan error, 1)
			go func(done chan<- error) { done <- d.runOnce(ctx) }(running)
		case err := <-running:
			running = nil
			if err != nil && ctx.Err() == nil {
				d.log.Warn("segment detection failed", "err", err)
			}
			if dirty {
				dirty = false
				timer.Reset(d.Delay)
			}
		}
	}
}

// RunOnce analyses every season that has a new or changed episode. When
// ffmpeg cannot be run at all it stops, and the seasons left wait for the
// next run.
func (d *Detector) RunOnce(ctx context.Context) error {
	seasons, err := d.q.ListSeasonsToFingerprint(ctx, AlgoVersion)
	if err != nil {
		return err
	}
	for _, id := range seasons {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if err := d.Season(ctx, id); err != nil {
			if errors.Is(err, ErrNoDecoder) {
				return err
			}
			d.log.Warn("segment detection failed for a season", "season", id, "err", err)
		}
	}
	return nil
}

// Season fingerprints a season's episodes and stores the intros and
// credits found. Every file gets an analysis record, found or not, unless
// ffmpeg could not be run: then nothing is recorded, as nothing was
// learned, and the season is looked at again on the next run.
func (d *Detector) Season(ctx context.Context, seasonID int64) error {
	start := time.Now()
	rows, err := d.q.ListSeasonFilesForFingerprint(ctx, seasonID)
	if err != nil {
		return err
	}
	lang := seasonLanguage(rows)
	var eps []Episode
	versions := map[int64]FileVersion{}
	for _, r := range rows {
		v := FileVersion{ID: r.ID, Mtime: r.Mtime, Size: r.Size}
		versions[r.ID] = v
		info := parseProbe(r.Probe)
		if info == nil || info.DurationMs < minEpisodeMs || len(info.Audio) == 0 {
			continue // too short to share an intro and credits worth skipping
		}
		e := Episode{FileID: r.ID, DurationMs: info.DurationMs}
		index := audioIndex(info, lang)
		is, il := IntroWindow(info.DurationMs)
		cs, cl := CreditsWindow(info.DurationMs)
		if e.Intro, err = d.region(ctx, v, r.Path, index, "intro", is, il); err != nil {
			return err
		}
		if e.Credits, err = d.region(ctx, v, r.Path, index, "credits", cs, cl); err != nil {
			return err
		}
		eps = append(eps, e)
	}
	byFile := map[int64][]playback.Segment{}
	if len(eps) >= 2 {
		for _, f := range DetectSeason(eps) {
			byFile[f.FileID] = append(byFile[f.FileID], playback.Segment{Kind: f.Kind, StartMs: f.StartMs, EndMs: f.EndMs, Final: f.Final,
				Source: "fingerprint", Confidence: f.Confidence})
		}
	}
	found := 0
	for _, r := range rows {
		found += len(byFile[r.ID])
		if err := Replace(ctx, d.conn, d.q, versions[r.ID], "fingerprint", AlgoVersion, byFile[r.ID]); err != nil {
			return err
		}
	}
	d.log.Info("segments detected", "season", seasonID, "files", len(rows), "segments", found, "took", time.Since(start).Round(time.Millisecond))
	return nil
}

// region returns a window's fingerprint, from the cache when this version
// of the file was fingerprinted before with the same window. A file that
// cannot be decoded gives an empty one; ffmpeg not running at all is an
// error.
func (d *Detector) region(ctx context.Context, v FileVersion, path string, index int, name string, startMs, lengthMs int64) (Region, error) {
	r := Region{StartMs: startMs}
	if lengthMs <= 0 {
		return r, nil
	}
	c, err := d.q.GetFingerprint(ctx, db.GetFingerprintParams{MediaFileID: v.ID, Region: name})
	if err == nil && c.AlgoVersion == FingerprintAlgoVersion && c.FileMtime == v.Mtime && c.FileSize == v.Size && c.StartMs == startMs && c.LengthMs == lengthMs {
		r.Points = unpack(c.Data)
		_ = json.Unmarshal([]byte(c.Silences), &r.Silences)
		return r, nil
	}
	samples, err := d.dec.PCM(ctx, path, index, startMs, lengthMs)
	if errors.Is(err, ErrNoDecoder) {
		return r, err
	}
	if err != nil {
		d.log.Warn("could not decode audio for segment detection", "file", v.ID, "err", err)
		return r, nil
	}
	r.Points, r.Silences = Fingerprint(samples), Silences(samples)
	silences, _ := json.Marshal(r.Silences)
	if r.Silences == nil {
		silences = []byte("[]")
	}
	if err := d.q.UpsertFingerprint(ctx, db.UpsertFingerprintParams{MediaFileID: v.ID, Region: name, AlgoVersion: FingerprintAlgoVersion,
		FileMtime: v.Mtime, FileSize: v.Size, StartMs: startMs, LengthMs: lengthMs, Data: pack(r.Points), Silences: string(silences)}); err != nil {
		d.log.Warn("could not cache a fingerprint", "file", v.ID, "err", err)
	}
	return r, nil
}

func parseProbe(raw *string) *probe.MediaInfo {
	if raw == nil {
		return nil
	}
	info, err := probe.Parse(*raw)
	if err != nil {
		return nil
	}
	return info
}

// seasonLanguage is the language of the first episode's default audio, so
// every episode is compared in the same language.
func seasonLanguage(rows []db.ListSeasonFilesForFingerprintRow) string {
	for _, r := range rows {
		if info := parseProbe(r.Probe); info != nil {
			for _, a := range info.Audio {
				if a.Default && a.Language != "" {
					return a.Language
				}
			}
		}
	}
	return ""
}

// audioIndex picks the stream to fingerprint: the season's language if the
// file has it, else the default stream, else the first.
func audioIndex(info *probe.MediaInfo, lang string) int {
	if lang != "" {
		for _, a := range info.Audio {
			if a.Language == lang {
				return a.Index
			}
		}
	}
	for _, a := range info.Audio {
		if a.Default {
			return a.Index
		}
	}
	return info.Audio[0].Index
}

func pack(points []uint32) []byte {
	var buf bytes.Buffer
	w, _ := flate.NewWriter(&buf, flate.BestSpeed)
	_ = binary.Write(w, binary.LittleEndian, points)
	_ = w.Close()
	return buf.Bytes()
}

func unpack(data []byte) []uint32 {
	raw, err := io.ReadAll(flate.NewReader(bytes.NewReader(data)))
	if err != nil {
		return nil
	}
	points := make([]uint32, len(raw)/4)
	_ = binary.Read(bytes.NewReader(raw), binary.LittleEndian, points)
	return points
}
