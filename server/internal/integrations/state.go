package integrations

import (
	"context"
	"errors"
	"fmt"
	"path"
	"path/filepath"
	"sync"
	"time"

	"watchmymedia/server/internal/integrations/arr"
	"watchmymedia/server/internal/memo"
)

const (
	// stateFresh is how long a queue or a series' episodes are kept: pages
	// fetch them often, and a download's progress moves slowly.
	stateFresh = 15 * time.Second
	// stateWait bounds how long a page waits for an app; past it the page
	// comes without the app's fields.
	stateWait = 2 * time.Second
)

// Download is a movie's or episode's download in Radarr's or Sonarr's queue.
type Download struct {
	State      string     `json:"state" enum:"queued,downloading,importing,failed"`
	Progress   float64    `json:"progress" doc:"How much is downloaded, 0 to 1."`
	FinishesAt *time.Time `json:"finishesAt,omitempty" doc:"When the download client expects it to finish."`
	Message    string     `json:"message,omitempty" doc:"Why it failed or is stuck, in the app's words."`
}

func downloadFrom(q arr.QueueItem) *Download {
	d := &Download{State: "downloading", FinishesAt: q.EstimatedCompletion, Message: q.ErrorMessage}
	if q.Size > 0 {
		d.Progress = max(0, min(1, (q.Size-q.SizeLeft)/q.Size))
	}
	switch {
	case q.TrackedDownloadState == "failed" || q.TrackedDownloadState == "failedPending" || q.Status == "failed":
		d.State = "failed"
	case q.TrackedDownloadState == "importPending" || q.TrackedDownloadState == "importing" || q.TrackedDownloadState == "importBlocked":
		d.State = "importing"
	case q.Status == "queued" || q.Status == "paused" || q.Status == "delay":
		d.State = "queued"
	}
	return d
}

// cached is an app's answer, or its failure, and when it came. A failure is
// kept as long as an answer, so pages don't each wait on an app that
// doesn't answer; v then still holds the last answer that came.
type cached[T any] struct {
	at  time.Time
	v   T
	err error
}

func (c cached[T]) fresh() bool { return !c.at.IsZero() && time.Since(c.at) < stateFresh }

// reachable says whether a page should ask the app now: not when the last
// check of it failed (a page would wait stateWait for nothing). Then it has
// the app checked again soon, at most once per stateFresh, so pages notice
// when it is back. The error is the last check's.
func (s *Service) reachable(c *conn) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if c.status.CheckedAt == nil || c.status.OK {
		return nil
	}
	if time.Since(c.nudged) >= stateFresh {
		c.nudged = time.Now()
		s.RequestRefresh(c.row.ID)
	}
	if c.down != nil {
		return c.down
	}
	return errors.New(c.status.Error)
}

// queue returns an app's download queue, from the cache when fresh. Pages
// asking at once share one call.
func (s *Service) queue(ctx context.Context, c *conn) []arr.QueueItem {
	s.mu.RLock()
	q := c.queue
	s.mu.RUnlock()
	if q.fresh() || s.reachable(c) != nil {
		return q.v
	}
	items, err := memo.Do(ctx, &s.flight, fmt.Sprintf("%d/queue", c.row.ID), func(ctx context.Context) ([]arr.QueueItem, error) {
		ctx, cancel := context.WithTimeout(ctx, stateWait)
		defer cancel()
		items, err := c.arr.Queue(ctx)
		s.mu.Lock()
		defer s.mu.Unlock()
		if err != nil {
			s.log.Debug("integration queue", "integration", c.row.Name, "err", err)
			c.queue = cached[[]arr.QueueItem]{at: time.Now(), v: c.queue.v, err: err}
			return c.queue.v, nil
		}
		c.queue = cached[[]arr.QueueItem]{at: time.Now(), v: items}
		return items, nil
	})
	if err != nil {
		return q.v // this page's own context ended
	}
	return items
}

// episodes returns a series' episodes, from the cache when fresh. Pages
// asking at once share one call.
func (s *Service) episodes(ctx context.Context, c *conn, seriesID int) []arr.Episode {
	s.mu.RLock()
	e := c.episodes[seriesID]
	s.mu.RUnlock()
	if e.fresh() || s.reachable(c) != nil {
		return e.v
	}
	eps, err := memo.Do(ctx, &s.flight, fmt.Sprintf("%d/episodes/%d", c.row.ID, seriesID), func(ctx context.Context) ([]arr.Episode, error) {
		ctx, cancel := context.WithTimeout(ctx, stateWait)
		defer cancel()
		eps, err := c.arr.Episodes(ctx, seriesID)
		s.mu.Lock()
		defer s.mu.Unlock()
		if c.episodes == nil {
			c.episodes = map[int]cached[[]arr.Episode]{}
		}
		if err != nil {
			s.log.Debug("integration episodes", "integration", c.row.Name, "err", err)
			old := c.episodes[seriesID].v
			c.episodes[seriesID] = cached[[]arr.Episode]{at: time.Now(), v: old, err: err}
			return old, nil
		}
		c.episodes[seriesID] = cached[[]arr.Episode]{at: time.Now(), v: eps}
		return eps, nil
	})
	if err != nil {
		return e.v
	}
	return eps
}

// forget drops what is cached about an app's queue and a series, after the
// server changed them.
func (s *Service) forget(c *conn, seriesID int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c.queue = cached[[]arr.QueueItem]{}
	delete(c.episodes, seriesID)
}

// Item names a library movie or show for the lookups below: its library's
// folder, its TMDB ID and some of its files.
type Item struct {
	LibraryPath string
	TMDBID      int
	Files       []string
}

// find returns the app and item ID that manage a library movie or show: the
// app's item with its TMDB ID whose folder is inside the library, else the
// one whose folder holds one of its files.
func (s *Service) find(kind arr.Kind, it Item) (*conn, int) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, c := range s.sorted() {
		if c.arr == nil || c.arr.Kind != kind || c.idx == nil {
			continue
		}
		for _, id := range c.idx.byTMDB[it.TMDBID] {
			if folder := c.idx.folder(c.maps, id); folder != "" && inside(it.LibraryPath, folder) {
				return c, id
			}
		}
	}
	root := localKey(it.LibraryPath)
	for _, f := range it.Files {
		for _, c := range s.sorted() {
			if c.arr == nil || c.arr.Kind != kind || c.idx == nil {
				continue
			}
			for k := localKey(filepath.Dir(f)); under(root, k) && k != root; k = path.Dir(k) {
				if id, ok := c.idx.byFolder[k]; ok {
					return c, id
				}
			}
		}
	}
	return nil, 0
}

// sorted is the connections by ID, so lookups are the same each time. The
// caller holds s.mu.
func (s *Service) sorted() []*conn {
	out := make([]*conn, 0, len(s.conns))
	for _, c := range s.conns {
		out = append(out, c)
	}
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j].row.ID < out[j-1].row.ID; j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

func (idx *index) folder(pm PathMap, id int) string {
	if m, ok := idx.movies[id]; ok {
		return pm.ToLocal(m.Path)
	}
	if sr, ok := idx.series[id]; ok {
		return pm.ToLocal(sr.Path)
	}
	return ""
}

// MovieState is what Radarr says about a library movie.
type MovieState struct {
	Connection string
	Monitored  bool
	Download   *Download
}

// Movie returns Radarr's state for a library movie, or nil when no Radarr
// has it.
func (s *Service) Movie(ctx context.Context, it Item) *MovieState {
	c, id := s.find(arr.Radarr, it)
	if c == nil {
		return nil
	}
	s.mu.RLock()
	m := c.idx.movies[id]
	s.mu.RUnlock()
	out := &MovieState{Connection: c.row.Name, Monitored: m.Monitored}
	for _, q := range s.queue(ctx, c) {
		if q.MovieID == id {
			out.Download = downloadFrom(q)
			break
		}
	}
	return out
}

// SeriesState is what Sonarr says about a library show, by season and
// episode number. Sonarr numbers episodes as TheTVDB does, which for a few
// shows differs from TMDB's numbers.
type SeriesState struct {
	Connection string
	Monitored  bool
	Seasons    map[int]bool
	Episodes   map[[2]int]EpisodeState
}

type EpisodeState struct {
	Monitored bool
	Download  *Download
}

// Series returns Sonarr's state for a library show, or nil when no Sonarr
// has it.
func (s *Service) Series(ctx context.Context, it Item) *SeriesState {
	c, id := s.find(arr.Sonarr, it)
	if c == nil {
		return nil
	}
	s.mu.RLock()
	sr := c.idx.series[id]
	s.mu.RUnlock()
	out := &SeriesState{Connection: c.row.Name, Monitored: sr.Monitored, Seasons: map[int]bool{}, Episodes: map[[2]int]EpisodeState{}}
	for _, se := range sr.Seasons {
		out.Seasons[se.SeasonNumber] = se.Monitored
	}
	// Both calls at once: an app that is slow to answer costs a page one
	// wait, not two.
	var eps []arr.Episode
	var queue []arr.QueueItem
	var wg sync.WaitGroup
	wg.Go(func() { eps = s.episodes(ctx, c, id) })
	wg.Go(func() { queue = s.queue(ctx, c) })
	wg.Wait()
	byID := map[int][2]int{}
	for _, e := range eps {
		key := [2]int{e.SeasonNumber, e.EpisodeNumber}
		byID[e.ID] = key
		out.Episodes[key] = EpisodeState{Monitored: e.Monitored}
	}
	for _, q := range queue {
		key, ok := byID[q.EpisodeID]
		if q.SeriesID != id || !ok {
			continue
		}
		st := out.Episodes[key]
		st.Download = downloadFrom(q)
		out.Episodes[key] = st
	}
	return out
}

// SeriesSeasons is which seasons of a library show Sonarr monitors, from the
// index alone, for the show page; nil when no Sonarr has the show.
func (s *Service) SeriesSeasons(it Item) (connection string, seasons map[int]bool) {
	c, id := s.find(arr.Sonarr, it)
	if c == nil {
		return "", nil
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	seasons = map[int]bool{}
	for _, se := range c.idx.series[id].Seasons {
		seasons[se.SeasonNumber] = se.Monitored
	}
	return c.row.Name, seasons
}

// Managed reports whether an app has a library movie or show.
func (s *Service) Managed(libraryType string, it Item) bool {
	c, _ := s.find(kindFor(libraryType), it)
	return c != nil
}

// SearchMissing has Sonarr look for episodes of a library show now: the
// listed episode numbers of a season, or all the season's missing ones. It
// monitors them first, since Sonarr only grabs monitored episodes.
func (s *Service) SearchMissing(ctx context.Context, it Item, season int, episodes []int) error {
	c, id := s.find(arr.Sonarr, it)
	if c == nil {
		return errNotManaged
	}
	defer s.forget(c, id)
	if len(episodes) == 0 {
		if err := c.arr.MonitorSeasons(ctx, id, []int{season}, true); err != nil {
			return err
		}
		return c.arr.SearchSeason(ctx, id, season)
	}
	ids := s.episodeIDs(ctx, c, id, season, episodes)
	if len(ids) == 0 {
		return invalid("Sonarr doesn't list those episodes")
	}
	if err := c.arr.SetEpisodesMonitored(ctx, ids, true); err != nil {
		return err
	}
	return c.arr.SearchEpisodes(ctx, ids)
}

// SetMonitored monitors or unmonitors episodes of a library show in Sonarr,
// or the whole season when episodes is empty.
func (s *Service) SetMonitored(ctx context.Context, it Item, season int, episodes []int, monitored bool) error {
	c, id := s.find(arr.Sonarr, it)
	if c == nil {
		return errNotManaged
	}
	defer s.forget(c, id)
	defer s.RequestRefresh(c.row.ID)
	if len(episodes) == 0 {
		return c.arr.MonitorSeasons(ctx, id, []int{season}, monitored)
	}
	ids := s.episodeIDs(ctx, c, id, season, episodes)
	if len(ids) == 0 {
		return invalid("Sonarr doesn't list those episodes")
	}
	return c.arr.SetEpisodesMonitored(ctx, ids, monitored)
}

func (s *Service) episodeIDs(ctx context.Context, c *conn, seriesID, season int, numbers []int) []int {
	want := map[int]bool{}
	for _, n := range numbers {
		want[n] = true
	}
	var ids []int
	for _, e := range s.episodes(ctx, c, seriesID) {
		if e.SeasonNumber == season && want[e.EpisodeNumber] {
			ids = append(ids, e.ID)
		}
	}
	return ids
}

var errNotManaged = &ErrInvalid{Reason: "no Radarr or Sonarr manages this"}

// IsNotManaged reports whether err says no Radarr or Sonarr holds the item.
func IsNotManaged(err error) bool { return errors.Is(err, errNotManaged) }
