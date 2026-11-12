package metadata

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strconv"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/library"
	"watchmymedia/server/internal/memo"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/tmdb"
)

// RelPath is a file's path relative to its library root, with forward slashes.
func RelPath(libraryRoot, path string) string {
	rel, err := filepath.Rel(libraryRoot, path)
	if err != nil {
		return filepath.ToSlash(path)
	}
	return filepath.ToSlash(rel)
}

// Folder is the folder that names a file's show or movie, or "" (see
// parser.MatchFolder).
func Folder(lib db.Library, f db.MediaFile) string {
	return parser.MatchFolder(lib.Type, RelPath(lib.Path, f.Path))
}

// FolderRange is the range of paths under a library folder: every path in
// it sorts between "folder/" and "folder0" ('0' follows '/'; ']' follows '\').
func FolderRange(lib db.Library, folder string) (prefix, end string) {
	prefix = filepath.Join(lib.Path, filepath.FromSlash(folder)) + string(filepath.Separator)
	return prefix, prefix[:len(prefix)-1] + string(rune(filepath.Separator+1))
}

// folderShow returns the TMDB show other episodes in the file's folder are
// linked to, if any.
func (m *Matcher) folderShow(ctx context.Context, lib db.Library, f db.MediaFile) (int, bool) {
	folder := Folder(lib, f)
	if folder == "" {
		return 0, false
	}
	prefix, end := FolderRange(lib, folder)
	show, err := m.q.GetShowForFolder(ctx, db.GetShowForFolderParams{LibraryID: lib.ID, Prefix: prefix, PrefixEnd: end})
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			m.log.Warn("read show for folder", "folder", folder, "err", err)
		}
		return 0, false
	}
	return int(show.TmdbID), true
}

// folderOverride returns the TMDB ID a person chose for the file's folder.
func (m *Matcher) folderOverride(ctx context.Context, lib db.Library, f db.MediaFile) *int {
	folder := Folder(lib, f)
	if folder == "" {
		return nil
	}
	id, err := m.q.GetMatchOverride(ctx, db.GetMatchOverrideParams{LibraryID: lib.ID, Folder: folder})
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			m.log.Warn("read match override", "folder", folder, "err", err)
		}
		return nil
	}
	n := int(id)
	return &n
}

// LineUpSeason says how many of a group's files in one season land on an
// episode TMDB lists.
type LineUpSeason struct {
	Season int `json:"season"`
	Files  int `json:"files"`
	Found  int `json:"found"`
	OnTMDB int `json:"onTmdb" doc:"Episodes TMDB lists for this season; 0 when the show has no such season."`
}

// LineUp previews a group match without saving anything.
type LineUp struct {
	Total      int            `json:"total"`
	Found      int            `json:"found" doc:"Files that would land on an episode (TV) or the movie."`
	Unnumbered int            `json:"unnumbered" doc:"TV files whose names have no season and episode numbers."`
	Dated      int            `json:"dated" doc:"TV files named by air date, as daily shows are."`
	DatedFound int            `json:"datedFound" doc:"Of those, files dated within the show's run on TMDB."`
	Seasons    []LineUpSeason `json:"seasons" nullable:"false"`
}

// getTV returns a show's details from TMDB, asking once for every caller
// that wants them at the same time and reusing them for ten minutes.
func (m *Matcher) getTV(ctx context.Context, id int) (*tmdb.TVShow, error) {
	return memo.Load(ctx, m.tvs, &m.flight, id, func(ctx context.Context) (*tmdb.TVShow, error) {
		return m.tmdb.GetTV(ctx, id)
	})
}

// LineUp checks how a group's files would line up with a TMDB show, using
// each season's episode count, so a person can tell same-name shows apart
// before choosing. Movies always line up.
func (m *Matcher) LineUp(ctx context.Context, lib db.Library, files []db.MediaFile, tmdbID int) (LineUp, error) {
	out := LineUp{Total: len(files), Seasons: []LineUpSeason{}}
	if lib.Type != library.TypeTV {
		out.Found = len(files)
		return out, nil
	}
	show, err := m.getTV(ctx, tmdbID)
	if err != nil {
		return out, err
	}
	counts := map[int]int{}
	byYear := map[string]int{} // season that started airing in a year, for year seasons
	for _, s := range show.Seasons {
		counts[s.SeasonNumber] = s.EpisodeCount
		if s.SeasonNumber > 0 && len(s.AirDate) >= 4 {
			if _, ok := byYear[s.AirDate[:4]]; !ok {
				byYear[s.AirDate[:4]] = s.SeasonNumber
			}
		}
	}
	ended := show.Status == "Ended" || show.Status == "Canceled"
	bySeason := map[int]*LineUpSeason{}
	for _, f := range files {
		var g parser.Guess
		_ = json.Unmarshal([]byte(f.Parsed), &g)
		if g.AirDate != "" {
			out.Dated++
			inRun := func(d string) bool { return d != "" && d >= show.FirstAirDate && (!ended || d <= show.LastAirDate) }
			if inRun(g.AirDate) || inRun(g.AirDateAlt) {
				out.DatedFound++
				out.Found++
			}
			continue
		}
		if g.Season == nil || len(g.Episodes) == 0 {
			out.Unnumbered++
			continue
		}
		s := bySeason[*g.Season]
		if s == nil {
			onTMDB := counts[*g.Season]
			if *g.Season >= 1900 {
				if n, ok := byYear[strconv.Itoa(*g.Season)]; ok {
					onTMDB = counts[n]
				}
			}
			s = &LineUpSeason{Season: *g.Season, OnTMDB: onTMDB}
			bySeason[*g.Season] = s
		}
		s.Files++
		ok := true
		for _, e := range g.Episodes {
			if e < 1 || e > s.OnTMDB {
				ok = false
			}
		}
		if ok {
			s.Found++
			out.Found++
		}
	}
	for _, s := range bySeason {
		out.Seasons = append(out.Seasons, *s)
	}
	sort.Slice(out.Seasons, func(i, j int) bool { return out.Seasons[i].Season < out.Seasons[j].Season })
	return out, nil
}

// GroupResult reports a group match.
type GroupResult struct {
	Matched      int    `json:"matched"`
	NeedEpisodes int    `json:"needEpisodes" doc:"TV files whose episode is not on TMDB; they stay unmatched under the chosen show."`
	Folder       string `json:"folder,omitempty" doc:"The folder remembered for new files, when remember was set."`
	ShowID       *int64 `json:"showId,omitempty"`
	MovieID      *int64 `json:"movieId,omitempty"`
}

// ApplyGroup matches every file to one TMDB movie or show chosen by a
// person, each episode by the numbers in its own name, and locks what
// matched. With remember, files that share one folder record the choice
// for it, so files added there later match without a search.
func (m *Matcher) ApplyGroup(ctx context.Context, lib db.Library, files []db.MediaFile, tmdbID int, remember bool) (GroupResult, error) {
	var res GroupResult
	if remember {
		if folder := sharedFolder(lib, files); folder != "" {
			if err := m.q.UpsertMatchOverride(ctx, db.UpsertMatchOverrideParams{LibraryID: lib.ID, Folder: folder, TmdbID: int64(tmdbID)}); err != nil {
				return res, err
			}
			res.Folder = folder
		}
	}
	switch lib.Type {
	case library.TypeMovie:
		id, err := m.storeMovie(ctx, lib, tmdbID)
		if err != nil {
			return res, err
		}
		for _, f := range files {
			if err := m.record(ctx, f.ID, StatusMatched, &id, nil, nil, nil, true); err != nil {
				return res, err
			}
			res.Matched++
		}
		res.MovieID = &id
		m.linkTrailers(ctx, lib)
	case library.TypeTV:
		for _, f := range files {
			err := m.Apply(ctx, lib, f, tmdbID, nil, nil)
			var nm *noMatchError
			switch {
			case err == nil:
				res.Matched++
			case errors.As(err, &nm):
				// The show is right but this episode is not on TMDB: leave the
				// file unmatched with the reason, for the episode picker.
				if err := m.record(ctx, f.ID, StatusUnmatched, nil, nil, nil, new(err.Error()), false); err != nil {
					return res, err
				}
				res.NeedEpisodes++
			default:
				return res, err
			}
		}
		if show, err := m.q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: lib.ID, TmdbID: int64(tmdbID)}); err == nil {
			res.ShowID = &show.ID
		}
	default:
		return res, fmt.Errorf("unsupported library type %q", lib.Type)
	}
	return res, nil
}

// sharedFolder is the one folder every file is in, or "".
func sharedFolder(lib db.Library, files []db.MediaFile) string {
	folder := ""
	for i, f := range files {
		fo := Folder(lib, f)
		if fo == "" || (i > 0 && fo != folder) {
			return ""
		}
		folder = fo
	}
	return folder
}
