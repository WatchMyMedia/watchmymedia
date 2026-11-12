package metadata

import (
	"context"
	"errors"
	"sort"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/watchstate"
)

// RematchPlan previews moving every file of a show or movie to another
// TMDB item. Nothing is saved.
type RematchPlan struct {
	Files       int `json:"files"`
	Found       int `json:"found" doc:"Files that land on an episode of the new show; for movies, all of them."`
	Watched     int `json:"watched" doc:"TV: episodes anyone has watch state on. Movies: people with watch state on it."`
	WatchedKept int `json:"watchedKept" doc:"Of those, how many keep their state: episodes with the same season and number in the new show."`
}

// RematchResult reports a move.
type RematchResult struct {
	Moved          int    `json:"moved"`
	NeedEpisodes   int    `json:"needEpisodes" doc:"TV files whose episode is not in the new show; they are left unmatched."`
	WatchedKept    int    `json:"watchedKept"`
	WatchedCleared int    `json:"watchedCleared"`
	Folder         string `json:"folder,omitempty" doc:"The folder remembered for new files, when remember was set."`
	ShowID         *int64 `json:"showId,omitempty"`
	MovieID        *int64 `json:"movieId,omitempty"`
}

// showFile is one file of a show with the episodes it is linked to, by season.
type showFile struct {
	id       int64
	episodes map[int][]int
}

func (m *Matcher) showFiles(ctx context.Context, showID int64) ([]showFile, error) {
	rows, err := m.q.ListShowFileEpisodes(ctx, showID)
	if err != nil {
		return nil, err
	}
	var out []showFile
	for _, r := range rows {
		if len(out) == 0 || out[len(out)-1].id != r.MediaFileID {
			out = append(out, showFile{id: r.MediaFileID, episodes: map[int][]int{}})
		}
		f := &out[len(out)-1]
		f.episodes[int(r.SeasonNumber)] = append(f.episodes[int(r.SeasonNumber)], int(r.EpisodeNumber))
	}
	return out, nil
}

// PreviewShowRematch says how a show's files and watch history would carry
// over to another TMDB show, by season and episode number.
func (m *Matcher) PreviewShowRematch(ctx context.Context, show db.Show, tmdbID int) (RematchPlan, error) {
	var plan RematchPlan
	files, err := m.showFiles(ctx, show.ID)
	if err != nil {
		return plan, err
	}
	tv, err := m.getTV(ctx, tmdbID)
	if err != nil {
		return plan, err
	}
	counts := map[int]int{}
	for _, s := range tv.Seasons {
		counts[s.SeasonNumber] = s.EpisodeCount
	}
	has := func(season, episode int) bool { return episode >= 1 && episode <= counts[season] }
	plan.Files = len(files)
	for _, f := range files {
		ok := true
		for season, eps := range f.episodes {
			for _, e := range eps {
				ok = ok && has(season, e)
			}
		}
		if ok {
			plan.Found++
		}
	}
	watched, err := m.q.ListShowWatchedEpisodes(ctx, show.ID)
	if err != nil {
		return plan, err
	}
	plan.Watched = len(watched)
	for _, w := range watched {
		if has(int(w.SeasonNumber), int(w.EpisodeNumber)) {
			plan.WatchedKept++
		}
	}
	return plan, nil
}

// RematchShow moves every file of a show to another TMDB show chosen by a
// person, keeping each file's season and episode numbers, and locks them.
// Watch state, and unwatches that left none, move to the episode with the
// same numbers (see watchstate.Service.Move), or are cleared when there is
// none. The old show is then removed, since nothing points at it. With
// remember, the files' shared folder records the new show.
func (m *Matcher) RematchShow(ctx context.Context, lib db.Library, show db.Show, tmdbID int, remember bool) (RematchResult, error) {
	var res RematchResult
	files, err := m.showFiles(ctx, show.ID)
	if err != nil {
		return res, err
	}
	if len(files) == 0 {
		return res, noMatch("%q has no files to move", show.Title)
	}
	// Read the watched episodes before the old episodes lose their files.
	watched, err := m.q.ListShowWatchedEpisodes(ctx, show.ID)
	if err != nil {
		return res, err
	}
	guarded, err := m.q.ListShowGuardedEpisodes(ctx, show.ID)
	if err != nil {
		return res, err
	}
	rows := make([]db.MediaFile, 0, len(files))
	for _, f := range files {
		row, err := m.q.GetMediaFile(ctx, f.id)
		if err != nil {
			return res, err
		}
		rows = append(rows, row)
	}
	if remember {
		if folder := sharedFolder(lib, rows); folder != "" {
			if err := m.q.UpsertMatchOverride(ctx, db.UpsertMatchOverrideParams{LibraryID: lib.ID, Folder: folder, TmdbID: int64(tmdbID)}); err != nil {
				return res, err
			}
			res.Folder = folder
		}
	}
	for i, f := range files {
		seasons := make([]int, 0, len(f.episodes))
		for s := range f.episodes {
			seasons = append(seasons, s)
		}
		sort.Ints(seasons)
		var ids []int64
		var missing error
		for _, s := range seasons {
			got, err := m.linkEpisodes(ctx, lib, tmdbID, s, f.episodes[s])
			var nm *noMatchError
			switch {
			case err == nil:
				ids = append(ids, got...)
			case errors.As(err, &nm):
				missing = err
			default:
				return res, err
			}
		}
		if len(ids) == 0 {
			if err := m.record(ctx, rows[i].ID, StatusUnmatched, nil, nil, nil, new(missing.Error()), false); err != nil {
				return res, err
			}
			res.NeedEpisodes++
			continue
		}
		if err := m.record(ctx, rows[i].ID, StatusMatched, nil, ids, nil, nil, true); err != nil {
			return res, err
		}
		res.Moved++
	}
	next, err := m.q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: lib.ID, TmdbID: int64(tmdbID)})
	if err != nil {
		return res, err
	}
	res.ShowID = &next.ID
	var moves []watchstate.Move
	moved := map[int64]bool{}
	carry := func(id, season, episode int64) bool {
		to, ok := m.episodeByNumber(ctx, next.ID, season, episode)
		if !moved[id] {
			moved[id] = true
			moves = append(moves, watchstate.Move{From: id, To: to})
		}
		return ok
	}
	for _, w := range watched {
		if carry(w.ID, w.SeasonNumber, w.EpisodeNumber) {
			res.WatchedKept++
		} else {
			res.WatchedCleared++
		}
	}
	for _, g := range guarded {
		carry(g.ID, g.SeasonNumber, g.EpisodeNumber)
	}
	if err := m.opts.Watch.Move(ctx, watchstate.Episode, moves); err != nil {
		return res, err
	}
	if next.ID != show.ID {
		if err := m.q.DeleteShow(ctx, show.ID); err != nil {
			return res, err
		}
	}
	return res, nil
}

func (m *Matcher) episodeByNumber(ctx context.Context, showID, season, episode int64) (int64, bool) {
	s, err := m.q.GetSeasonByNumber(ctx, db.GetSeasonByNumberParams{ShowID: showID, Number: season})
	if err != nil {
		return 0, false
	}
	e, err := m.q.GetEpisodeByNumber(ctx, db.GetEpisodeByNumberParams{SeasonID: s.ID, Number: episode})
	if err != nil {
		return 0, false
	}
	return e.ID, true
}

// PreviewMovieRematch counts what moving a movie's files would carry over.
func (m *Matcher) PreviewMovieRematch(ctx context.Context, movie db.Movie) (RematchPlan, error) {
	files, err := m.q.ListMovieFiles(ctx, &movie.ID)
	if err != nil {
		return RematchPlan{}, err
	}
	n, err := m.q.CountMovieWatchStates(ctx, movie.ID)
	if err != nil {
		return RematchPlan{}, err
	}
	return RematchPlan{Files: len(files), Found: len(files), Watched: int(n), WatchedKept: int(n)}, nil
}

// RematchMovie moves every file of a movie to another TMDB movie chosen by
// a person, with its watch state (see watchstate.Service.Move), and removes
// the old movie.
func (m *Matcher) RematchMovie(ctx context.Context, lib db.Library, movie db.Movie, tmdbID int, remember bool) (RematchResult, error) {
	var res RematchResult
	files, err := m.q.ListMovieFiles(ctx, &movie.ID)
	if err != nil {
		return res, err
	}
	if len(files) == 0 {
		return res, noMatch("%q has no files to move", movie.Title)
	}
	id, err := m.storeMovie(ctx, lib, tmdbID)
	if err != nil {
		return res, err
	}
	res.MovieID = &id
	if remember {
		if folder := sharedFolder(lib, files); folder != "" {
			if err := m.q.UpsertMatchOverride(ctx, db.UpsertMatchOverrideParams{LibraryID: lib.ID, Folder: folder, TmdbID: int64(tmdbID)}); err != nil {
				return res, err
			}
			res.Folder = folder
		}
	}
	for _, f := range files {
		if err := m.record(ctx, f.ID, StatusMatched, &id, nil, nil, nil, true); err != nil {
			return res, err
		}
		res.Moved++
	}
	if id == movie.ID {
		return res, nil
	}
	n, err := m.q.CountMovieWatchStates(ctx, movie.ID)
	if err != nil {
		return res, err
	}
	if err := m.opts.Watch.Move(ctx, watchstate.Movie, []watchstate.Move{{From: movie.ID, To: id}}); err != nil {
		return res, err
	}
	res.WatchedKept = int(n)
	if err := m.q.DeleteMovie(ctx, movie.ID); err != nil {
		return res, err
	}
	// The old movie's trailers were let go with it; they follow the files.
	m.linkTrailers(ctx, lib)
	return res, nil
}
