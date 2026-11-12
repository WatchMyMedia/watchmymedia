package trickplay

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"image/jpeg"
	"log/slog"
	"math"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"watchmymedia/server/internal/bgwork"
	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
	"watchmymedia/server/internal/playback"
)

const (
	// MinDurationMs is the shortest file that gets thumbnails.
	MinDurationMs = 60_000
	// MinFreeBytes is the free space below which no sheet is started on
	// the data folder's disk.
	MinFreeBytes = 5 << 30
	// missingFor is how long a missing file keeps its thumbnails: missing
	// files often come back (a share that was not mounted).
	missingFor = 30 * 24 * time.Hour
	// pageSize is how many files the work list reads at a time.
	pageSize = 50
	// sheetTimeout bounds one sheet's ffmpeg run, which reads 1,000 s of
	// the file: minutes for a large remux on a slow share.
	sheetTimeout = 30 * time.Minute
)

// Why the job is waiting rather than making thumbnails (Status.Paused).
const (
	PausedPlaying = "playing"
	PausedLowDisk = "lowDisk"
)

// ErrNoDecoder is ffmpeg failing to start at all: no binary, or the system
// out of open files or processes. It says nothing about the file.
var ErrNoDecoder = errors.New("could not run ffmpeg")

// errOff ends a run when seek thumbnails are turned off.
var errOff = errors.New("seek thumbnails are off")

// Options set up a Generator.
type Options struct {
	FFmpeg string
	// Dir holds the thumbnails: <data>/trickplay.
	Dir string
	// Enabled is the setting's value at start (SetEnabled changes it).
	Enabled bool
	// ToneMapping is what ffmpeg can do with HDR (from
	// playback.Capabilities); nil means nothing.
	ToneMapping func() ToneMapping
	// Busy reports whether anything is playing; nil means never.
	Busy func() bool
	// Layout is DefaultLayout when zero.
	Layout Layout
}

// ToneMapping is what the server's ffmpeg can do with HDR on the CPU.
type ToneMapping struct {
	// CPU: zscale, the transcoder's chain (Capabilities.CPUToneMap).
	CPU bool
	// Dovi: tonemapx, which reshapes Dolby Vision 5 through its RPU
	// (Capabilities.DoviToneMap).
	Dovi bool
}

// toneFor is how a file's HDR is mapped with what ffmpeg has, as the
// transcoder does it: Dolby Vision through tonemapx where there is one
// (profile 5 needs it; for the others it reshapes as well as zscale maps
// their HDR10 or HLG base layer), else with zscale, profile 5 (or an
// unknown profile) read as its base layer.
func toneFor(hdr string, dvProfile *int64, tm ToneMapping) string {
	dv5 := hdr == "dolbyvision" && (dvProfile == nil || *dvProfile == 5)
	switch {
	case hdr == "":
		return ToneSDR
	case hdr == "dolbyvision" && tm.Dovi:
		return ToneTonemapx
	case !tm.CPU:
		return TonePlain
	case dv5:
		return ToneDVBase
	}
	return ToneZscale
}

// Generator makes seek thumbnails in the background, newest files first,
// at low priority, one sheet at a time, waiting while anything plays.
type Generator struct {
	q      *db.Queries
	conn   *sql.DB
	bus    *events.Bus
	log    *slog.Logger
	dir    string
	ffmpeg string
	layout Layout

	toneMapping func() ToneMapping
	busy        func() bool
	free        func(dir string) (uint64, error)
	now         func() time.Time

	// Delay is how long after a library change to look, so a scan's
	// matching can finish first; Every is how often to look regardless.
	Delay, Every time.Duration
	// Resume is how long after the last play the job goes on; Poll is how
	// often it looks while paused for a play, DiskPoll while the disk is
	// nearly full.
	Resume, Poll, DiskPoll time.Duration
	// SweepEvery is how often folders that no longer match a file go.
	SweepEvery time.Duration

	enabled atomic.Bool
	wake    chan struct{}
	// work is held while a file's thumbnails are made or put in place, so
	// DeleteAll does not delete under them.
	work sync.Mutex

	mu        sync.Mutex
	cancelRun context.CancelFunc // ends the run in progress
	running   bool
	paused    string
	free0     uint64 // free space when paused for it
	current   *progress
	lastBusy  time.Time // when something was last seen playing
	wasBusy   bool

	// iframeMu is held while an I-frame track is found or made
	// (iframes.go); iframeCache keeps the layouts of the last few.
	iframeMu    sync.Mutex
	iframeCache map[string]*playback.IFrameTrack

	// runOnce and makeSheet are RunOnce and the ffmpeg run, which tests
	// replace.
	runOnce   func(context.Context) error
	makeSheet func(ctx context.Context, s sheetJob) error
}

// progress is the sheet being made.
type progress struct {
	fileID        int64
	sheet, sheets int
}

// New returns a generator; Run starts it.
func New(q *db.Queries, conn *sql.DB, bus *events.Bus, log *slog.Logger, o Options) *Generator {
	if o.Layout == (Layout{}) {
		o.Layout = DefaultLayout
	}
	if o.FFmpeg == "" {
		o.FFmpeg = "ffmpeg"
	}
	g := &Generator{q: q, conn: conn, bus: bus, log: log, dir: o.Dir, ffmpeg: o.FFmpeg, layout: o.Layout,
		toneMapping: o.ToneMapping, busy: o.Busy, free: freeBytes, now: time.Now,
		Delay: 2 * time.Minute, Every: 6 * time.Hour, Resume: 2 * time.Minute, Poll: 15 * time.Second, DiskPoll: time.Minute,
		SweepEvery: 24 * time.Hour, wake: make(chan struct{}, 1)}
	g.enabled.Store(o.Enabled)
	g.runOnce = g.RunOnce
	g.makeSheet = g.ffmpegSheet
	return g
}

// Dir is the folder that holds the thumbnails.
func (g *Generator) Dir() string { return g.dir }

// Enabled reports whether seek thumbnails are made.
func (g *Generator) Enabled() bool { return g.enabled.Load() }

// SetEnabled turns making thumbnails on, which starts a run now, or off,
// which stops the run in progress. Thumbnails already made are kept.
func (g *Generator) SetEnabled(on bool) {
	if was := g.enabled.Swap(on); on && !was {
		g.Wake()
	}
	if !on {
		g.stopRun()
	}
}

// Wake starts a run now, or after the one in progress.
func (g *Generator) Wake() {
	select {
	case g.wake <- struct{}{}:
	default:
	}
}

func (g *Generator) stopRun() {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.cancelRun != nil {
		g.cancelRun()
	}
}

// Played records that something is playing or just stopped: the job waits
// Resume after it before it starts another sheet.
func (g *Generator) Played() {
	g.mu.Lock()
	g.lastBusy = g.now()
	g.mu.Unlock()
}

// Run makes thumbnails Delay after start, after library changes, every
// Every and when turned on, until ctx ends; it returns once the run in
// progress has stopped. Once a day, and before the first run, it sweeps
// away folders that no longer match a file. Plays seen on the bus count as
// something playing (Played).
func (g *Generator) Run(ctx context.Context) {
	sub, stop := g.bus.Subscribe()
	defer stop()
	timer := time.NewTimer(g.Delay)
	defer timer.Stop()
	ticker := time.NewTicker(g.Every)
	defer ticker.Stop()
	sweeps := time.NewTicker(g.SweepEvery)
	defer sweeps.Stop()
	var running chan error // a run in progress, else nil
	dirty := false         // something changed while it ran
	sweepDue := true       // sweep before the first run
	// later starts a run soon: now when idle, else after this one.
	later := func(d time.Duration) {
		if running != nil {
			dirty = true
		} else {
			timer.Reset(d)
		}
	}
	for {
		select {
		case <-ctx.Done():
			if running != nil {
				if err := <-running; err != nil && !errors.Is(err, context.Canceled) {
					g.log.Debug("seek thumbnails stopped", "err", err)
				}
			}
			return
		case e := <-sub:
			switch e.Type {
			case "match.finished", "library.changed":
				later(g.Delay)
			case "activity.started", "activity.updated", "activity.stopped":
				g.Played()
			}
		case <-ticker.C:
			later(0)
		case <-g.wake:
			later(0)
		case <-sweeps.C:
			sweepDue = true
			if running == nil {
				sweepDue = false
				g.sweepLogged(ctx)
			}
		case <-timer.C:
			if running != nil {
				dirty = true
				continue
			}
			if sweepDue {
				sweepDue = false
				g.sweepLogged(ctx)
			}
			if !g.enabled.Load() {
				continue
			}
			runCtx, cancel := context.WithCancel(ctx)
			g.mu.Lock()
			g.cancelRun = cancel
			g.mu.Unlock()
			running = make(chan error, 1)
			go func(done chan<- error) {
				defer cancel()
				done <- g.runOnce(runCtx)
			}(running)
		case err := <-running:
			running = nil
			g.mu.Lock()
			g.cancelRun = nil
			g.mu.Unlock()
			if err != nil && ctx.Err() == nil && !errors.Is(err, context.Canceled) && !errors.Is(err, errOff) {
				g.log.Warn("seek thumbnails stopped", "err", err)
			}
			if sweepDue {
				sweepDue = false
				g.sweepLogged(ctx)
			}
			if dirty {
				dirty = false
				timer.Reset(g.Delay)
			}
		}
	}
}

// RunOnce makes thumbnails for every file that needs them, newest first,
// until there are none left, ctx ends or the setting is turned off. When
// ffmpeg cannot be run at all it stops, and the files left wait for the
// next run.
func (g *Generator) RunOnce(ctx context.Context) error {
	if !g.enabled.Load() {
		return nil
	}
	g.mu.Lock()
	g.running = true
	g.mu.Unlock()
	defer func() {
		g.mu.Lock()
		g.running, g.paused, g.current = false, "", nil
		g.mu.Unlock()
	}()
	start := time.Now()
	made := 0
	tm := g.tones()
	// The newest first_seen_at sorts below this, whatever its form.
	seen, id := "\uffff", int64(math.MaxInt64)
	for {
		page, err := g.q.ListFilesNeedingTrickplay(ctx, db.ListFilesNeedingTrickplayParams{
			AlgoVersion: AlgoVersion, Cpu: tm.CPU, Dovi: tm.Dovi, BeforeSeen: seen, BeforeID: id, Page: pageSize})
		if err != nil {
			return err
		}
		if len(page) == 0 {
			break
		}
		for _, f := range page {
			if err := g.file(ctx, f, tm); err != nil {
				if errors.Is(err, ErrNoDecoder) || ctx.Err() != nil || errors.Is(err, errOff) {
					return err
				}
				g.log.Warn("could not make seek thumbnails", "file", f.ID, "err", err)
				continue
			}
			made++
		}
		last := page[len(page)-1]
		seen, id = last.FirstSeenAt, last.ID
	}
	if made > 0 {
		g.log.Info("seek thumbnails made", "files", made, "took", time.Since(start).Round(time.Second))
	}
	return nil
}

// tones is what ffmpeg can do with HDR now.
func (g *Generator) tones() ToneMapping {
	if g.toneMapping == nil {
		return ToneMapping{}
	}
	return g.toneMapping()
}

// sheetJob is one sheet to make.
type sheetJob struct {
	path  string
	sheet int
	tiles int    // tiles on this sheet
	tone  string // how HDR is mapped
	out   string
}

// file makes one file's thumbnails: each sheet not yet made, into a
// temporary folder that a stopped run resumes; then the manifest, and the
// folder moved into place and recorded with how HDR was mapped, so they are
// made again if ffmpeg can later do better. A file ffmpeg cannot read is
// recorded as failed, and not tried again until the file or AlgoVersion
// changes.
func (g *Generator) file(ctx context.Context, f db.ListFilesNeedingTrickplayRow, tm ToneMapping) error {
	g.work.Lock()
	defer g.work.Unlock()
	defer g.setProgress(nil)
	tone := toneFor(f.Hdr, f.DvProfile, tm)
	version := Version(f.Size, f.Mtime, tone)
	rec := db.UpsertTrickplayParams{MediaFileID: f.ID, Version: version, AlgoVersion: AlgoVersion, FileSize: f.Size, FileMtime: f.Mtime, ToneMap: tone,
		IntervalMs: g.layout.IntervalMs, Columns: int64(g.layout.Columns), Rows: int64(g.layout.Rows)}
	var duration int64
	if f.DurationMs != nil {
		duration = *f.DurationMs
	}
	tiles := g.layout.Tiles(duration)
	sheets := g.layout.Sheets(tiles)
	tmp := tmpDir(g.dir, f.ID, version)
	if err := os.MkdirAll(tmp, 0o755); err != nil {
		return err
	}
	for s := range sheets {
		out := filepath.Join(tmp, SheetName(s))
		if st, err := os.Stat(out); err == nil && st.Size() > 0 {
			continue // made by an earlier run
		}
		if err := g.waitTurn(ctx); err != nil {
			return err
		}
		g.setProgress(&progress{fileID: f.ID, sheet: s + 1, sheets: sheets})
		err := g.makeSheet(ctx, sheetJob{path: f.Path, sheet: s, tiles: g.layout.TilesIn(s, tiles), tone: tone, out: out})
		if errors.Is(err, ErrNoDecoder) || ctx.Err() != nil {
			return err
		}
		if err != nil {
			_ = os.RemoveAll(tmp)
			rec.Status, rec.Error = "failed", err.Error()
			if rerr := g.q.UpsertTrickplay(ctx, rec); rerr != nil {
				return rerr
			}
			g.log.Warn("could not make seek thumbnails", "file", f.ID, "sheet", s, "err", err)
			return nil
		}
	}
	width, height, size, err := g.finish(tmp, sheets)
	if err != nil {
		_ = os.RemoveAll(tmp)
		rec.Status, rec.Error = "failed", err.Error()
		return g.q.UpsertTrickplay(ctx, rec)
	}
	rec.Status, rec.Width, rec.Height, rec.Tiles, rec.Sheets, rec.Bytes = "done", int64(width), int64(height), int64(tiles), int64(sheets), size
	// The manifest on disk marks the folder complete; it has no URL, which
	// the API adds.
	manifest, _ := json.Marshal(struct {
		playback.Trickplay
		URL string `json:"url,omitempty"`
	}{Trickplay: Manifest(db.Trickplay{IntervalMs: rec.IntervalMs, Width: rec.Width, Height: rec.Height,
		Columns: rec.Columns, Rows: rec.Rows, Tiles: rec.Tiles, Sheets: rec.Sheets}, "")})
	if err := os.WriteFile(filepath.Join(tmp, manifestName), manifest, 0o644); err != nil { //nolint:gosec // G306: thumbnails are not secret
		return err
	}
	final := FileDir(g.dir, f.ID, version)
	if err := os.MkdirAll(filepath.Dir(final), 0o755); err != nil {
		return err
	}
	_ = os.RemoveAll(final) // a folder an interrupted earlier run left behind
	if err := os.Rename(tmp, final); err != nil {
		return err
	}
	if err := g.q.UpsertTrickplay(ctx, rec); err != nil {
		_ = os.RemoveAll(final)
		return err
	}
	g.removeOtherVersions(f.ID, version)
	if g.bus != nil {
		// To admins only: Settings shows how far the job has got.
		g.bus.Publish(events.Event{Type: "trickplay.ready", Data: Ready{FileID: f.ID}, To: &events.Audience{}})
	}
	return nil
}

// Ready is the trickplay.ready event: a file's seek thumbnails are made.
type Ready struct {
	FileID int64 `json:"fileId"`
}

// finish reads the tile size from the first sheet and adds up the sheets'
// sizes.
func (g *Generator) finish(dir string, sheets int) (width, height int, bytes int64, err error) {
	f, err := os.Open(filepath.Join(dir, SheetName(0)))
	if err != nil {
		return 0, 0, 0, err
	}
	cfg, err := jpeg.DecodeConfig(f)
	_ = f.Close()
	if err != nil {
		return 0, 0, 0, fmt.Errorf("read the first sheet: %w", err)
	}
	width, height = cfg.Width/g.layout.Columns, cfg.Height/g.layout.Rows
	if width == 0 || height == 0 {
		return 0, 0, 0, fmt.Errorf("first sheet is %d × %d", cfg.Width, cfg.Height)
	}
	for s := range sheets {
		st, err := os.Stat(filepath.Join(dir, SheetName(s)))
		if err != nil {
			return 0, 0, 0, err
		}
		bytes += st.Size()
	}
	return width, height, bytes, nil
}

// removeOtherVersions deletes a file's thumbnails for versions other than
// the one just made.
func (g *Generator) removeOtherVersions(fileID int64, version string) {
	parent := filepath.Dir(FileDir(g.dir, fileID, version))
	entries, _ := os.ReadDir(parent)
	for _, e := range entries {
		if id, v, ok := parseFolder(e.Name()); ok && id == fileID && v != version {
			_ = os.RemoveAll(filepath.Join(parent, e.Name()))
		}
	}
}

// waitTurn returns when a sheet may start: nothing has played for Resume
// and the disk has MinFreeBytes free. Meanwhile the status says why it
// waits.
func (g *Generator) waitTurn(ctx context.Context) error {
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		if !g.enabled.Load() {
			return errOff
		}
		now := g.now()
		busy := g.busy != nil && g.busy()
		g.mu.Lock()
		// The first look that finds nothing playing is when the play ended,
		// as far as the job knows.
		if busy || g.wasBusy {
			g.lastBusy = now
		}
		g.wasBusy = busy
		last := g.lastBusy
		g.mu.Unlock()
		wait := last.Add(g.Resume).Sub(now)
		if busy || (!last.IsZero() && wait > 0) {
			g.setPaused(PausedPlaying, 0)
			d := g.Poll
			if !busy {
				d = min(d, wait)
			}
			if err := sleep(ctx, d); err != nil {
				return err
			}
			continue
		}
		if free, err := g.free(g.dir); err == nil && free < MinFreeBytes {
			g.setPaused(PausedLowDisk, free)
			if err := sleep(ctx, g.DiskPoll); err != nil {
				return err
			}
			continue
		}
		g.setPaused("", 0)
		return nil
	}
}

func sleep(ctx context.Context, d time.Duration) error { return bgwork.Sleep(ctx, d) }

func (g *Generator) setPaused(why string, free uint64) {
	g.mu.Lock()
	g.paused, g.free0 = why, free
	g.mu.Unlock()
}

func (g *Generator) setProgress(p *progress) {
	g.mu.Lock()
	g.current = p
	g.mu.Unlock()
}

// Manifest is the manifest for a file's done thumbnails; url is the sheet
// URL, with {sheet} in it.
func Manifest(t db.Trickplay, url string) playback.Trickplay {
	return playback.Trickplay{IntervalMs: t.IntervalMs, Width: int(t.Width), Height: int(t.Height), Columns: int(t.Columns), Rows: int(t.Rows),
		Count: int(t.Tiles), Sheets: int(t.Sheets), URL: url}
}

// Done returns a file's thumbnails when they are made for this version of
// it and are on disk.
func (g *Generator) Done(ctx context.Context, fileID, size int64, mtime string) (db.Trickplay, bool) {
	t, err := g.q.GetTrickplay(ctx, fileID)
	if err != nil || t.Status != "done" || t.AlgoVersion != AlgoVersion || t.FileSize != size || t.FileMtime != mtime {
		return t, false
	}
	if _, err := os.Stat(filepath.Join(FileDir(g.dir, fileID, t.Version), manifestName)); err != nil {
		return t, false // restored from a backup, or deleted by hand: the sweep has them made again
	}
	return t, true
}

// SheetPath is where a sheet of a file's done thumbnails is, if version is
// the one recorded and the sheet is one of them.
func (g *Generator) SheetPath(ctx context.Context, fileID int64, version string, sheet int) (string, bool) {
	if !ValidVersion(version) || sheet < 0 {
		return "", false
	}
	t, err := g.q.GetTrickplay(ctx, fileID)
	if err != nil || t.Status != "done" || t.Version != version || int64(sheet) >= t.Sheets {
		return "", false
	}
	return filepath.Join(FileDir(g.dir, fileID, version), SheetName(sheet)), true
}

// DeleteAll removes every file's thumbnails. The run in progress stops;
// with the setting on, they are made again, newest first.
func (g *Generator) DeleteAll(ctx context.Context) error {
	g.stopRun()
	g.work.Lock()
	defer g.work.Unlock()
	if err := g.q.DeleteAllTrickplay(ctx); err != nil {
		return err
	}
	entries, err := os.ReadDir(g.dir)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	for _, e := range entries {
		if err := os.RemoveAll(filepath.Join(g.dir, e.Name())); err != nil {
			return err
		}
	}
	if g.enabled.Load() {
		g.Wake()
	}
	return nil
}

// Status is how far seek thumbnails have got, for Settings.
type Status struct {
	Enabled   bool   `json:"enabled" doc:"Seek thumbnails are made for new and changed files."`
	Files     int64  `json:"files" doc:"Files that get seek thumbnails: present, with video, a minute long or more."`
	Done      int64  `json:"done" doc:"Of those, files whose thumbnails are made."`
	Failed    int64  `json:"failed" doc:"Of those, files whose thumbnails could not be made; tried again when the file changes."`
	Pending   int64  `json:"pending" doc:"Of those, files still to do."`
	Bytes     int64  `json:"bytes" doc:"What the thumbnails take on disk."`
	Running   bool   `json:"running" doc:"The job is working through files now, or paused in the middle."`
	Paused    string `json:"paused,omitempty" enum:"playing,lowDisk" doc:"Why the job is waiting: something is playing (it goes on 2 minutes after the last play stops), or the data folder's disk has under 5 GB free."`
	FreeBytes int64  `json:"freeBytes,omitempty" doc:"Free space on the data folder's disk, while paused for it."`
	// Current is the file being made.
	Current *Current `json:"current,omitempty"`
}

// Current is the file whose thumbnails are being made.
type Current struct {
	FileID        int64  `json:"fileId"`
	Title         string `json:"title" doc:"The movie's or episode's title, else the file's name."`
	ShowTitle     string `json:"showTitle,omitempty"`
	SeasonNumber  int64  `json:"seasonNumber,omitempty"`
	EpisodeNumber int64  `json:"episodeNumber,omitempty"`
	Sheet         int    `json:"sheet" doc:"The sheet being made, from 1."`
	Sheets        int    `json:"sheets"`
}

// Status is how far the job has got and what it costs.
func (g *Generator) Status(ctx context.Context) (Status, error) {
	s := Status{Enabled: g.enabled.Load()}
	tm := g.tones()
	c, err := g.q.CountTrickplay(ctx, db.CountTrickplayParams{AlgoVersion: AlgoVersion, Cpu: tm.CPU, Dovi: tm.Dovi})
	if err != nil {
		return s, err
	}
	s.Files, s.Done, s.Failed = c.Files, c.Done, c.Failed
	s.Pending = max(0, s.Files-s.Done-s.Failed)
	if s.Bytes, err = g.q.TrickplayBytes(ctx); err != nil {
		return s, err
	}
	g.mu.Lock()
	s.Running, s.Paused = g.running, g.paused
	if s.Paused == PausedLowDisk {
		s.FreeBytes = int64(min(g.free0, math.MaxInt64)) //nolint:gosec // G115: capped
	}
	cur := g.current
	if cur != nil {
		c := *cur
		cur = &c
	}
	g.mu.Unlock()
	if cur != nil && s.Paused == "" {
		s.Current = &Current{FileID: cur.fileID, Sheet: cur.sheet, Sheets: cur.sheets}
		if it, err := g.q.GetFileItem(ctx, cur.fileID); err == nil {
			s.Current.Title = filepath.Base(it.Path)
			switch {
			case it.MovieTitle != nil:
				s.Current.Title = *it.MovieTitle
			case it.ShowTitle != nil:
				s.Current.ShowTitle = *it.ShowTitle
				s.Current.Title = deref(it.EpisodeTitle)
				s.Current.SeasonNumber, s.Current.EpisodeNumber = deref(it.SeasonNumber), deref(it.EpisodeNumber)
			}
		}
	}
	return s, nil
}

func deref[T any](p *T) T {
	var zero T
	if p == nil {
		return zero
	}
	return *p
}

// firstLine is the first line of s.
func firstLine(s string) string {
	line, _, _ := strings.Cut(strings.TrimSpace(s), "\n")
	return line
}
