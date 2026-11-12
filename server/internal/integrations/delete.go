package integrations

import (
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/integrations/arr"
	"watchmymedia/server/internal/parser"
)

// Deleting (PLAN-ARR.md §8.1): the app that owns the files deletes them. The
// server first says what will go, then asks each app, closes the item's open
// requests and scans what changed. It never deletes a file itself.

// deleteWait bounds each call of a delete or replace: the app answers once it
// has moved the folder to its recycling bin, which across disks is a copy.
const deleteWait = 2 * time.Minute

// unlistedLimit bounds the walk of a folder for files the app doesn't list.
const unlistedLimit = 10000

// KeyDeleteAccess says who may delete episodes and movies: "admins" (the
// default) or "everyone" (PLAN-EPISODE-PAGE.md §4). Shows, whole seasons and
// replacing a file stay with admins either way.
const KeyDeleteAccess = "delete.access"

// DeleteAccess is who may delete episodes and movies: "admins" or "everyone".
func (s *Service) DeleteAccess(ctx context.Context) string {
	v, _ := s.settings().Get(ctx, KeyDeleteAccess, "admins")
	if v != "everyone" {
		return "admins"
	}
	return v
}

func (s *Service) SetDeleteAccess(ctx context.Context, v string) error {
	if v != "admins" && v != "everyone" {
		return invalid("access is admins or everyone")
	}
	return s.settings().Set(ctx, KeyDeleteAccess, v)
}

// Radarr and Sonarr answer a delete before they have removed the files, so
// the server scans each path once it is gone, looking every goneEvery for up
// to goneWait (a move to a recycling bin on another disk is a copy).
var goneWait, goneEvery = 2 * time.Minute, time.Second

// DeleteTarget is a library movie, show, season or episode, as the API layer
// finds it.
type DeleteTarget struct {
	Type        string // movie, show, season or episode
	Title       string // for the log
	LibraryID   int64
	LibraryPath string
	TMDBID      int
	// Files are the item's present files in the library: for a season or an
	// episode, only its own.
	Files []string
	// Trailers are a movie's trailer files in the library, which go with
	// it once none of its files stays (PLAN-TRAILERS.md §5.2).
	Trailers []string
	Season   int
	Episode  int
	// Airing is a season with episodes still to come.
	Airing bool
}

func (t DeleteTarget) kind() arr.Kind {
	if t.Type == "movie" {
		return arr.Radarr
	}
	return arr.Sonarr
}

func (t DeleteTarget) whole() bool { return t.Type == "movie" || t.Type == "show" }

// DeletePreview says what deleting an item will do, read from the apps now.
type DeletePreview struct {
	Recording bool        `json:"recording,omitempty" required:"false" doc:"The selection includes files made by WatchMyMedia recording; deleting removes those owned files directly."`
	Managed   bool        `json:"managed" doc:"Some Radarr or Sonarr manages the item. When false there is no delete: it is deleted where it was downloaded, or on disk."`
	Files     int         `json:"files" doc:"Files the apps list, across every app."`
	Size      int64       `json:"size" doc:"Their size in bytes."`
	Apps      []DeleteApp `json:"apps" nullable:"false" doc:"Each app that holds the item; several Radarrs (1080p and 4K) can hold one movie."`
	Kept      int         `json:"kept" doc:"The item's files in the library that no app holds. They stay."`
	Trailers  int         `json:"trailers,omitempty" required:"false" doc:"Movies: the movie's trailer files in the library. They go with the movie when none of its files stays: with its folder when the app removes the movie, else the server deletes them (not to a recycling bin), also when the movie stays in Radarr."`
	// Choices are the defaults the dialog starts with.
	Choices DeleteChoices `json:"choices"`
	Seerr   string        `json:"seerr,omitempty" required:"false" doc:"The Seerr connection that is told after the delete: it clears its record of the movie or show, or for a season or an episode, the show's, and rebuilds it from Sonarr."`
}

type DeleteApp struct {
	ConnectionID int64  `json:"connectionId"`
	Connection   string `json:"connection" doc:"The connection's name: Radarr 4K."`
	Kind         string `json:"kind" enum:"radarr,sonarr"`
	Folder       string `json:"folder" doc:"The movie's or series' folder as the app sees it; for a season or an episode, the folder of its files."`
	LocalFolder  string `json:"localFolder" doc:"The same folder as this server sees it."`
	Files        int    `json:"files" doc:"Files the app lists and deletes."`
	Size         int64  `json:"size"`
	Quality      string `json:"quality,omitempty" doc:"A movie's best version in this app's folder: 4K Dolby Vision."`
	Extra        int    `json:"extra" doc:"Movies and shows: files in the folder the app doesn't list. They go too when the item is removed from the app."`
	// OtherEpisodes are episode numbers whose file is the one being deleted:
	// a file can hold two episodes.
	OtherEpisodes []int  `json:"otherEpisodes,omitempty" doc:"An episode's file also holds these episodes of the season, which go too."`
	RecycleBin    string `json:"recycleBin,omitempty" doc:"Where the app moves what it deletes; empty when it deletes for good."`
	RecycleDays   int    `json:"recycleDays,omitempty" doc:"Days the recycling bin keeps files; 0 when it is never emptied."`
	Error         string `json:"error,omitempty" doc:"Why the app could not be asked. It can't delete until it answers."`
}

// DeleteChoices are the options a delete offers, at their defaults (§8.1).
type DeleteChoices struct {
	Keep          bool `json:"keep" doc:"Movies and shows: keep it in the app unmonitored rather than remove it. Off."`
	Exclude       bool `json:"exclude" doc:"Movies and shows: don't add it again from import lists. Off."`
	KeepMonitored bool `json:"keepMonitored" doc:"Seasons: keep getting new episodes of the season. On for a season still airing."`
	Airing        bool `json:"airing" doc:"The season is still airing."`
}

// DeleteOptions are what the admin chose.
type DeleteOptions struct {
	RecordAgain   bool    `json:"recordAgain,omitempty" required:"false" doc:"Allow deleted DVR episodes to be recorded again. Defaults to false."`
	Keep          bool    `json:"keep,omitempty" doc:"Movies and shows: keep it in the app, unmonitored, and delete only the files it lists."`
	Exclude       bool    `json:"exclude,omitempty" doc:"Movies and shows removed from the app: don't add them again from import lists."`
	KeepMonitored *bool   `json:"keepMonitored,omitempty" doc:"Seasons: keep getting new episodes of the season. Defaults to on for a season still airing."`
	Connections   []int64 `json:"connections,omitempty" maxItems:"32" doc:"The connections to delete from; every one that holds the item when empty."`
}

// DeleteResult is what a delete did.
type DeleteResult struct {
	Apps     []DeleteDone `json:"apps" nullable:"false"`
	Requests int          `json:"requests" doc:"Open requests for the item that were closed, so it can be asked for again."`
	Trailers int          `json:"trailers,omitempty" required:"false" doc:"Movies: the trailer files that went with the movie, with its folder or deleted by the server."`
	Seerr    *SeerrDone   `json:"seerr,omitempty" required:"false" doc:"What Seerr was told, when it is connected."`
}

// SeerrDone is Seerr's part in a delete.
type SeerrDone struct {
	Connection string `json:"connection"`
	Error      string `json:"error,omitempty" doc:"Why Seerr couldn't be told. The delete went ahead; Seerr may still show the item as available."`
}

type DeleteDone struct {
	ConnectionID int64  `json:"connectionId"`
	Connection   string `json:"connection"`
	Files        int    `json:"files"`
	Size         int64  `json:"size"`
	Error        string `json:"error,omitempty" doc:"Why this app's delete failed; the others went ahead."`
}

type hit struct {
	c  *conn
	id int
}

// findAll returns every app item that holds a library movie or show: each
// connection's item with its TMDB ID whose folder is inside the library, and
// any whose folder holds one of its files.
func (s *Service) findAll(kind arr.Kind, it Item) []hit {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var out []hit
	add := func(c *conn, id int) {
		if !slices.ContainsFunc(out, func(h hit) bool { return h.c == c && h.id == id }) {
			out = append(out, hit{c, id})
		}
	}
	root := localKey(it.LibraryPath)
	for _, c := range s.sorted() {
		if c.arr == nil || c.arr.Kind != kind || c.idx == nil {
			continue
		}
		for _, id := range c.idx.byTMDB[it.TMDBID] {
			if folder := c.idx.folder(c.maps, id); folder != "" && inside(it.LibraryPath, folder) {
				add(c, id)
			}
		}
		for _, f := range it.Files {
			for k := localKey(filepath.Dir(f)); under(root, k) && k != root; k = path.Dir(k) {
				if id, ok := c.idx.byFolder[k]; ok {
					add(c, id)
					break
				}
			}
		}
	}
	return out
}

// plan is what deleting the item does in one app, read from the app now.
type plan struct {
	hit
	app DeleteApp
	// fileIDs are the files the app deletes one by one: the movie's file or
	// the episodes' files.
	fileIDs []int
	// episodeIDs are the episodes whose files go.
	episodeIDs []int
	// scan are the server paths to scan afterwards: the folder of a movie or
	// show, the files of a season or episode.
	scan []string
	// files are the server paths of the files the app lists.
	files []string
	// covers reports whether a library file goes with the delete.
	covers func(local string) bool
}

func (s *Service) plan(ctx context.Context, cl *arr.Client, t DeleteTarget, h hit) (plan, error) {
	s.mu.RLock()
	maps := h.c.maps
	s.mu.RUnlock()
	p := plan{hit: h, app: DeleteApp{ConnectionID: h.c.row.ID, Connection: h.c.row.Name, Kind: string(cl.Kind)}}
	mm, err := cl.MediaManagement(ctx)
	if err != nil {
		return p, err
	}
	p.app.RecycleBin, p.app.RecycleDays = mm.RecycleBin, mm.RecycleBinCleanupDays
	if mm.RecycleBin == "" {
		p.app.RecycleDays = 0
	}
	listed := map[string]bool{}
	switch t.Type {
	case "movie":
		m, err := cl.Movie(ctx, h.id)
		if err != nil {
			return p, err
		}
		p.app.Folder = m.Path
		if f := m.MovieFile; f != nil {
			p.app.Files, p.app.Size = 1, f.Size
			p.fileIDs = []int{f.ID}
			p.files = []string{maps.ToLocal(f.Path)}
			listed[relKey(m.Path, f.Path, f.RelativePath)] = true
		}
	case "show":
		sr, err := cl.SeriesByID(ctx, h.id)
		if err != nil {
			return p, err
		}
		files, err := cl.EpisodeFiles(ctx, h.id)
		if err != nil {
			return p, err
		}
		p.app.Folder = sr.Path
		for _, f := range files {
			p.app.Files++
			p.app.Size += f.Size
			p.fileIDs = append(p.fileIDs, f.ID)
			p.files = append(p.files, maps.ToLocal(f.Path))
			listed[relKey(sr.Path, f.Path, f.RelativePath)] = true
		}
	case "season", "episode":
		files, err := cl.EpisodeFiles(ctx, h.id)
		if err != nil {
			return p, err
		}
		eps, err := cl.Episodes(ctx, h.id)
		if err != nil {
			return p, err
		}
		var picked []arr.EpisodeFile
		if t.Type == "season" {
			for _, f := range files {
				if f.SeasonNumber == t.Season {
					picked = append(picked, f)
				}
			}
		} else {
			picked = episodeFiles(t, maps, files, eps)
		}
		var dirs []string
		for _, f := range picked {
			p.app.Files++
			p.app.Size += f.Size
			p.fileIDs = append(p.fileIDs, f.ID)
			p.files = append(p.files, maps.ToLocal(f.Path))
			p.scan = p.files
			dirs = append(dirs, path.Dir(slashed(f.Path)))
		}
		for _, e := range eps {
			if e.EpisodeFileID > 0 && slices.Contains(p.fileIDs, e.EpisodeFileID) {
				p.episodeIDs = append(p.episodeIDs, e.ID)
				if t.Type == "episode" && (e.SeasonNumber != t.Season || e.EpisodeNumber != t.Episode) {
					p.app.OtherEpisodes = append(p.app.OtherEpisodes, e.EpisodeNumber)
				}
			}
		}
		if len(p.episodeIDs) == 0 && t.Type == "episode" {
			// Nothing to delete, but the episode is still unmonitored.
			for _, e := range eps {
				if e.SeasonNumber == t.Season && e.EpisodeNumber == t.Episode {
					p.episodeIDs = []int{e.ID}
				}
			}
		}
		p.app.Folder = commonDir(dirs)
		if p.app.Folder != "" {
			p.app.LocalFolder = maps.ToLocal(p.app.Folder)
		}
		gone := map[string]bool{}
		for _, sp := range p.scan {
			gone[localKey(sp)] = true
		}
		p.covers = func(local string) bool { return gone[localKey(local)] }
		return p, nil
	}
	p.app.LocalFolder = maps.ToLocal(p.app.Folder)
	p.scan = []string{p.app.LocalFolder}
	p.covers = func(local string) bool { return inside(p.app.LocalFolder, local) }
	extras, err := cl.ExtraFiles(ctx, h.id)
	// Sonarr 4 has no list of its extra files: without one, only videos
	// count, not the subtitles and metadata it writes beside them.
	videosOnly := arr.IsNotFound(err)
	if err != nil && !videosOnly {
		return p, err
	}
	for _, e := range extras {
		listed[relKey("", "", e.RelativePath)] = true
	}
	// The server reads only folders inside its libraries.
	if inside(t.LibraryPath, p.app.LocalFolder) && localKey(p.app.LocalFolder) != localKey(t.LibraryPath) {
		p.app.Extra = unlisted(p.app.LocalFolder, listed, videosOnly)
	}
	return p, nil
}

// episodeFiles are the app's files for a library episode: those at the
// library episode's paths, else the file of the app's episode with its
// numbers (Sonarr numbers a few shows differently from TMDB).
func episodeFiles(t DeleteTarget, maps PathMap, files []arr.EpisodeFile, eps []arr.Episode) []arr.EpisodeFile {
	want := map[string]bool{}
	for _, f := range t.Files {
		want[localKey(f)] = true
	}
	var out []arr.EpisodeFile
	for _, f := range files {
		if want[localKey(maps.ToLocal(f.Path))] {
			out = append(out, f)
		}
	}
	if len(out) > 0 {
		return out
	}
	for _, e := range eps {
		if e.SeasonNumber == t.Season && e.EpisodeNumber == t.Episode && e.EpisodeFileID > 0 {
			for _, f := range files {
				if f.ID == e.EpisodeFileID {
					out = append(out, f)
				}
			}
		}
	}
	return out
}

// relKey is a file's path relative to its item's folder, in the form
// compared: from the app's relativePath, else cut from its full path.
func relKey(folder, full, rel string) string {
	if rel == "" && folder != "" {
		rel = strings.TrimPrefix(slashed(full), slashed(folder)+"/")
	}
	return strings.TrimPrefix(slashed(rel), "./")
}

func commonDir(dirs []string) string {
	if len(dirs) == 0 {
		return ""
	}
	out := dirs[0]
	for _, d := range dirs[1:] {
		for !under(out, d) {
			out = path.Dir(out)
		}
	}
	return out
}

// skipped are names a walk passes over: hidden files and what NAS systems
// keep in every folder.
func skipped(name string) bool {
	return strings.HasPrefix(name, ".") || name == "@eaDir" || name == "#recycle" || name == "#snapshot" ||
		strings.EqualFold(name, "Thumbs.db") || strings.EqualFold(name, "desktop.ini")
}

// unlisted counts the files under dir the app doesn't list (listed holds
// paths relative to dir), or with videosOnly, the videos.
func unlisted(dir string, listed map[string]bool, videosOnly bool) int {
	n, seen := 0, 0
	_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			// What can't be read is left out of the count, which is only
			// what a person is told before deleting, and the walk goes on.
			return nil //nolint:nilerr // deliberate, as above
		}
		if seen++; seen > unlistedLimit {
			return fs.SkipAll
		}
		if p != dir && skipped(d.Name()) {
			if d.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if d.IsDir() || !d.Type().IsRegular() || (videosOnly && !parser.IsVideo(d.Name())) {
			return nil
		}
		rel, err := filepath.Rel(dir, p)
		if err == nil && !listed[slashed(filepath.ToSlash(rel))] {
			n++
		}
		return nil
	})
	return n
}

// listedIn tests whether a library file is one of files.
func listedIn(files []string) func(string) bool {
	keys := make(map[string]bool, len(files))
	for _, f := range files {
		keys[localKey(f)] = true
	}
	return func(p string) bool { return keys[localKey(p)] }
}

// anyTakes reports whether any app's delete took the library file p.
func anyTakes(gone []func(string) bool, p string) bool {
	return slices.ContainsFunc(gone, func(takes func(string) bool) bool { return takes != nil && takes(p) })
}

// deleteTrailers deletes a movie's trailer files once none of its files
// stays (decided 2026-11-05, PLAN-TRAILERS.md §11): those in a folder an app
// removed go with it; the server deletes the rest itself, as when the movie
// stays in Radarr and only its files go. A file the server can't delete (a
// library mounted read-only) is logged and stays. It returns how many went.
func (s *Service) deleteTrailers(t DeleteTarget, gone []func(string) bool) int {
	n := 0
	for _, p := range t.Trailers {
		if anyTakes(gone, p) {
			n++
			continue
		}
		if !inside(t.LibraryPath, p) {
			continue
		}
		if err := os.Remove(p); err != nil && !errors.Is(err, fs.ErrNotExist) {
			s.log.Warn("could not delete a trailer with its movie", "movie", t.Title, "file", p, "err", err)
			continue
		}
		n++
		s.log.Info("deleted a trailer with its movie", "movie", t.Title, "file", p)
	}
	return n
}

// PreviewDelete works out what deleting the item would do.
func (s *Service) PreviewDelete(ctx context.Context, t DeleteTarget) DeletePreview {
	out := DeletePreview{Apps: []DeleteApp{}, Choices: DeleteChoices{KeepMonitored: t.Type == "season" && t.Airing, Airing: t.Type == "season" && t.Airing}}
	hits := s.findAll(t.kind(), Item{LibraryPath: t.LibraryPath, TMDBID: t.TMDBID, Files: t.Files})
	var covers []func(string) bool
	for _, h := range hits {
		ctx, cancel := context.WithTimeout(ctx, arr.Timeout)
		p, err := s.plan(ctx, h.c.arr, t, h)
		cancel()
		if err != nil {
			p.app.Error = err.Error()
			s.log.Debug("delete preview", "integration", h.c.row.Name, "err", err)
		} else {
			out.Files += p.app.Files
			out.Size += p.app.Size
			covers = append(covers, p.covers)
		}
		out.Apps = append(out.Apps, p.app)
	}
	out.Managed = len(hits) > 0
	if c := s.seerrConn(); c != nil && out.Managed {
		out.Seerr = c.row.Name
	}
	for _, f := range t.Files {
		if !slices.ContainsFunc(covers, func(c func(string) bool) bool { return c(f) }) {
			out.Kept++
		}
	}
	return out
}

// DeleteMedia has each chosen app that holds the item delete it: unmonitor
// first, so an RSS grab can't land in between, then delete. Then the item's
// open requests close, Seerr is told, and the server scans what went. An app
// that fails leaves the others to go ahead; when all fail, the first error is
// returned. Seerr failing never fails the delete.
func (s *Service) DeleteMedia(ctx context.Context, by Person, t DeleteTarget, o DeleteOptions) (DeleteResult, error) {
	hits := s.findAll(t.kind(), Item{LibraryPath: t.LibraryPath, TMDBID: t.TMDBID, Files: t.Files})
	if len(hits) == 0 {
		return DeleteResult{}, errNotManaged
	}
	if len(o.Connections) > 0 {
		hits = slices.DeleteFunc(hits, func(h hit) bool { return !slices.Contains(o.Connections, h.c.row.ID) })
		if len(hits) == 0 {
			return DeleteResult{}, invalid("none of those apps holds this")
		}
	}
	keepMonitored := t.Airing
	if o.KeepMonitored != nil {
		keepMonitored = *o.KeepMonitored
	}
	res := DeleteResult{Apps: []DeleteDone{}}
	var scan []string
	// gone has what each app that deleted took, as a test of a library file.
	var gone []func(string) bool
	var first error
	ok := 0
	for _, h := range hits {
		cl := h.c.arr.Patient(deleteWait)
		p, err := s.plan(ctx, cl, t, h)
		if err == nil {
			err = s.deleteIn(ctx, cl, t, o, keepMonitored, p)
		}
		done := DeleteDone{ConnectionID: h.c.row.ID, Connection: h.c.row.Name, Files: p.app.Files, Size: p.app.Size}
		if err != nil {
			if first == nil {
				first = err
			}
			done.Error = err.Error()
			s.log.Warn("delete failed", "by", by.Name, "item", t.Title, "integration", h.c.row.Name, "err", err)
		} else {
			ok++
			if t.whole() && o.Keep {
				// The folder stays; its files go.
				scan = append(scan, p.files...)
				gone = append(gone, listedIn(p.files))
			} else {
				scan = append(scan, p.scan...)
				gone = append(gone, p.covers)
			}
			s.log.Info("deleted", "by", by.Name, "item", t.Title, "integration", h.c.row.Name, "folder", p.app.Folder,
				"files", p.app.Files, "bytes", p.app.Size, "removed", t.whole() && !o.Keep, "exclusion", t.whole() && !o.Keep && o.Exclude,
				"recycle_bin", p.app.RecycleBin)
		}
		s.forget(h.c, h.id)
		s.RequestRefresh(h.c.row.ID)
		res.Apps = append(res.Apps, done)
	}
	if ok == 0 {
		return res, first
	}
	if t.Type == "movie" && ok == len(hits) && !slices.ContainsFunc(t.Files, func(f string) bool { return !anyTakes(gone, f) }) {
		res.Trailers = s.deleteTrailers(t, gone)
		scan = append(scan, t.Trailers...)
	}
	res.Requests = s.closeRequests(ctx, by, t)
	res.Seerr = s.tellSeerr(ctx, by, t)
	s.rescanWhenGone(t.LibraryID, t.LibraryPath, scan)
	return res, nil
}

// deleteIn carries out the delete in one app (§8.1's table).
func (s *Service) deleteIn(ctx context.Context, cl *arr.Client, t DeleteTarget, o DeleteOptions, keepMonitored bool, p plan) error {
	switch t.Type {
	case "movie":
		if err := cl.SetMoviesMonitored(ctx, []int{p.id}, false); err != nil {
			return err
		}
		if o.Keep {
			for _, id := range p.fileIDs {
				if err := cl.DeleteMovieFile(ctx, id); err != nil && !arr.IsNotFound(err) {
					return err
				}
			}
			return nil
		}
		if err := cl.DeleteMovie(ctx, p.id, true, o.Exclude); err != nil {
			return err
		}
		s.unindex(p.c, p.id)
	case "show":
		if err := cl.SetSeriesMonitored(ctx, p.id, false); err != nil {
			return err
		}
		if o.Keep {
			return cl.DeleteEpisodeFiles(ctx, p.fileIDs)
		}
		if err := cl.DeleteSeries(ctx, p.id, true, o.Exclude); err != nil {
			return err
		}
		s.unindex(p.c, p.id)
	case "season":
		var err error
		if keepMonitored {
			err = cl.SetEpisodesMonitored(ctx, p.episodeIDs, false)
		} else {
			err = cl.MonitorSeasons(ctx, p.id, []int{t.Season}, false)
		}
		if err != nil {
			return err
		}
		return cl.DeleteEpisodeFiles(ctx, p.fileIDs)
	case "episode":
		if err := cl.SetEpisodesMonitored(ctx, p.episodeIDs, false); err != nil {
			return err
		}
		return cl.DeleteEpisodeFiles(ctx, p.fileIDs)
	}
	return nil
}

// tellSeerr clears Seerr's record of what was deleted, so it stops calling it
// available and it can be asked for again (§8.1): the movie's or show's, and
// for a season or an episode, the show's, with Seerr's Sonarr sync rerun to
// rebuild it. Seerr down or refusing is reported, not returned.
func (s *Service) tellSeerr(ctx context.Context, by Person, t DeleteTarget) *SeerrDone {
	c := s.seerrConn()
	if c == nil {
		return nil
	}
	mediaType, seasons := "tv", []int{t.Season}
	if t.Type == "movie" {
		mediaType = "movie"
	}
	if t.whole() {
		seasons = nil
	}
	done := &SeerrDone{Connection: c.row.Name}
	if err := s.SeerrForget(ctx, mediaType, t.TMDBID, seasons); err != nil {
		// SeerrForget says "seerr: …"; the connection's name says it here.
		if inner := errors.Unwrap(err); inner != nil {
			err = inner
		}
		done.Error = err.Error()
		s.log.Warn("delete: seerr not told", "by", by.Name, "item", t.Title, "integration", c.row.Name, "err", err)
	}
	return done
}

// unindex drops an item the server removed from an app from the index, so
// pages stop showing it as managed before the next read of the whole list.
func (s *Service) unindex(c *conn, id int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if c.idx == nil {
		return
	}
	delete(c.idx.movies, id)
	delete(c.idx.series, id)
	for k, v := range c.idx.byFolder {
		if v == id {
			delete(c.idx.byFolder, k)
		}
	}
	for tmdb, ids := range c.idx.byTMDB {
		c.idx.byTMDB[tmdb] = slices.DeleteFunc(ids, func(x int) bool { return x == id })
	}
}

// rescanWhenGone scans what a delete or replace removed, inside the library
// only, rather than waiting for the app's webhook: what is gone already at
// once, the rest as it goes, and after goneWait whatever is left.
func (s *Service) rescanWhenGone(libraryID int64, libraryPath string, paths []string) {
	var left []string
	for _, p := range paths {
		if p != "" && inside(libraryPath, p) && !slices.Contains(left, p) {
			left = append(left, p)
		}
	}
	deadline := time.Now().Add(goneWait)
	step := func() bool {
		var gone, still []string
		for _, p := range left {
			if _, err := os.Lstat(p); errors.Is(err, fs.ErrNotExist) {
				gone = append(gone, p)
			} else {
				still = append(still, p)
			}
		}
		if len(still) > 0 && !time.Now().Before(deadline) {
			gone, still = append(gone, still...), nil
		}
		if len(gone) > 0 {
			s.scan.RequestPaths(libraryID, gone)
		}
		left = still
		return len(left) == 0
	}
	if step() {
		return
	}
	ctx := s.context()
	go func() {
		t := time.NewTicker(goneEvery)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return // shutting down: the next scan finds what is gone
			case <-t.C:
				if step() {
					return
				}
			}
		}
	}()
}

// closeRequests closes the open requests a delete leaves behind, so the item
// can be asked for again: all of a movie's or a show's, and for a season or
// an episode, that season of each request (a request left with no season is
// closed). A closed request is cancelled with a note.
func (s *Service) closeRequests(ctx context.Context, by Person, t DeleteTarget) int {
	mediaType := "tv"
	if t.Type == "movie" {
		mediaType = "movie"
	}
	rows, err := s.q.ListRequestsForMedia(ctx, db.ListRequestsForMediaParams{MediaType: mediaType, TmdbID: int64(t.TMDBID)})
	if err != nil {
		s.log.Warn("delete: requests", "err", err)
		return 0
	}
	note := "Deleted from the library"
	if by.Name != "" {
		note += " by " + by.Name
	}
	n := 0
	for _, r := range rows {
		if r.Status != "pending" && r.Status != "approved" && r.Status != "failed" {
			continue
		}
		if !t.whole() {
			seasons := seasonList(r.Seasons)
			if !slices.Contains(seasons, t.Season) {
				continue
			}
			if rest := slices.DeleteFunc(seasons, func(n int) bool { return n == t.Season }); len(rest) > 0 {
				sj, _ := json.Marshal(rest)
				if err := s.q.SetRequestSeasons(ctx, db.SetRequestSeasonsParams{Seasons: string(sj), ID: r.ID}); err != nil {
					s.log.Warn("delete: request seasons", "request", r.ID, "err", err)
					continue
				}
				s.announce(r.ID, r.UserID, r.Status)
				n++
				continue
			}
		}
		if err := s.q.SetRequestStatus(ctx, db.SetRequestStatusParams{Status: "cancelled", Note: &note, ID: r.ID}); err != nil {
			s.log.Warn("delete: request status", "request", r.ID, "err", err)
			continue
		}
		s.announce(r.ID, r.UserID, "cancelled")
		n++
	}
	return n
}

// ReplaceTarget is a library file to replace (§9).
type ReplaceTarget struct {
	Type        string // movie or episode
	Title       string // for the log
	LibraryID   int64
	LibraryPath string
	TMDBID      int
	Path        string
}

// ReplaceResult says what replacing a file did.
type ReplaceResult struct {
	Connection  string `json:"connection"`
	Blocklisted bool   `json:"blocklisted" doc:"The release that brought the file in is marked failed, so the app won't grab it again."`
	Note        string `json:"note,omitempty" doc:"Why the release could not be blocklisted. The file was replaced all the same."`
}

// ReplaceFile has the app that manages a file mark the grab that brought it
// in failed (so it blocklists that release), delete the file, and search for
// another. A file the app imported by hand has no grab to blocklist: it is
// replaced all the same, and the result says so.
func (s *Service) ReplaceFile(ctx context.Context, by Person, t ReplaceTarget) (ReplaceResult, error) {
	kind := arr.Radarr
	if t.Type == "episode" {
		kind = arr.Sonarr
	}
	var h hit
	for _, x := range s.findAll(kind, Item{LibraryPath: t.LibraryPath, TMDBID: t.TMDBID, Files: []string{t.Path}}) {
		s.mu.RLock()
		folder := ""
		if x.c.idx != nil {
			folder = x.c.idx.folder(x.c.maps, x.id)
		}
		s.mu.RUnlock()
		if folder != "" && inside(folder, t.Path) {
			h = x
			break
		}
	}
	if h.c == nil {
		return ReplaceResult{}, errNotManaged
	}
	s.mu.RLock()
	maps := h.c.maps
	s.mu.RUnlock()
	cl := h.c.arr.Patient(deleteWait)
	app := map[arr.Kind]string{arr.Radarr: "Radarr", arr.Sonarr: "Sonarr"}[kind]
	res := ReplaceResult{Connection: h.c.row.Name}
	var history []arr.HistoryRecord
	var historyErr error
	var appPath string
	var del, search func() error
	if kind == arr.Radarr {
		m, err := cl.Movie(ctx, h.id)
		if err != nil {
			return res, err
		}
		f := m.MovieFile
		if f == nil || localKey(maps.ToLocal(f.Path)) != localKey(t.Path) {
			return res, invalid("%s doesn't list this file as the movie's, so it can't replace it", app)
		}
		appPath = f.Path
		history, historyErr = cl.MovieHistory(ctx, h.id)
		del = func() error { return cl.DeleteMovieFile(ctx, f.ID) }
		search = func() error {
			if !m.Monitored {
				if err := cl.SetMoviesMonitored(ctx, []int{h.id}, true); err != nil {
					return err
				}
			}
			return cl.SearchMovies(ctx, []int{h.id})
		}
	} else {
		files, err := cl.EpisodeFiles(ctx, h.id)
		if err != nil {
			return res, err
		}
		i := slices.IndexFunc(files, func(f arr.EpisodeFile) bool { return localKey(maps.ToLocal(f.Path)) == localKey(t.Path) })
		if i < 0 {
			return res, invalid("%s doesn't list this file, so it can't replace it", app)
		}
		f := files[i]
		eps, err := cl.Episodes(ctx, h.id)
		if err != nil {
			return res, err
		}
		var ids []int
		for _, e := range eps {
			if e.EpisodeFileID == f.ID {
				ids = append(ids, e.ID)
			}
		}
		if len(ids) == 0 {
			return res, invalid("%s lists no episode for this file", app)
		}
		appPath = f.Path
		history, historyErr = cl.EpisodeHistory(ctx, ids[0])
		del = func() error { return cl.DeleteEpisodeFiles(ctx, []int{f.ID}) }
		search = func() error {
			if err := cl.SetEpisodesMonitored(ctx, ids, true); err != nil {
				return err
			}
			return cl.SearchEpisodes(ctx, ids)
		}
	}
	switch grab := arr.FailedGrab(history, appPath); {
	case historyErr != nil:
		res.Note = app + "'s history couldn't be read (" + historyErr.Error() + "), so the release isn't blocked."
	case grab == 0:
		res.Note = app + " has no record of downloading this file (it may have been imported by hand), so no release is blocked."
	default:
		if err := cl.MarkFailed(ctx, grab); err != nil {
			res.Note = app + " couldn't mark the release failed (" + err.Error() + "), so it isn't blocked."
		} else {
			res.Blocklisted = true
		}
	}
	if err := del(); err != nil {
		return res, err
	}
	s.forget(h.c, h.id)
	s.RequestRefresh(h.c.row.ID)
	s.rescanWhenGone(t.LibraryID, t.LibraryPath, []string{t.Path})
	s.log.Info("replaced file", "by", by.Name, "item", t.Title, "integration", h.c.row.Name, "file", t.Path, "blocklisted", res.Blocklisted)
	if err := search(); err != nil {
		res.Note = strings.TrimSpace(res.Note + " The file is deleted, but the search for another failed (" + err.Error() + "): search in " + app + ".")
	}
	return res, nil
}
