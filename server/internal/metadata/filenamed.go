package metadata

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"slices"
	"strings"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/parser"
	"watchmymedia/server/internal/watchstate"
)

// Episodes named after files (migration 00044_file_named_episodes.sql).
//
// A file whose show is known but whose season or episode TMDB does not list
// is linked to an episode of that show named after the file, at the file's
// own numbers, so it plays, keeps its watch state and sits in order on the
// show's page. Cunk on Cinema is the case that asked for it: the files are
// the streaming release's six episodes and TMDB lists the BBC's three.
//
// The file stays unmatched, with the reason TMDB gave, so it is still
// listed for review and still tried on TMDB each day. When TMDB lists the
// episode, writing its season fills in this same row (UpsertEpisode sets
// file_named back to 0), so the file's link and everyone's watch state
// stay. When a person matches or lines the file up elsewhere, record hands
// the watch state on and removes the episode (retireFileNamed).
//
// Only numbered files get them (numbered: not by air date or year, not
// specials, which need a choice per file), and season line-ups never make
// them: a line-up says which TMDB episode a file is.

// unlisted is noMatch for a show TMDB has that lacks the file's season or
// episodes, where the file can play as episodes named after it.
func unlisted(format string, args ...any) error {
	return &noMatchError{msg: fmt.Sprintf(format, args...), unlisted: true}
}

func isUnlisted(err error) bool {
	var nm *noMatchError
	return errors.As(err, &nm) && nm.unlisted
}

// linkOrName is linkGuess, falling back to episodes named after the file
// where the TMDB show lacks the numbers its name gives. The error stays
// either way, so such a file is still unmatched.
func (m *Matcher) linkOrName(ctx context.Context, lib db.Library, f db.MediaFile, tmdbID int, g parser.Guess) ([]int64, error) {
	ids, err := m.linkGuess(ctx, lib, tmdbID, g)
	named, nerr := m.fileNamed(ctx, lib, f, tmdbID, g, err)
	switch {
	case nerr != nil:
		return nil, nerr
	case len(named) > 0:
		return named, err
	}
	return ids, err
}

// fileNamed returns the episodes of the TMDB show named after file f, made
// if need be, when err (from linking f there by its name g) says TMDB lists
// neither the season nor the episodes that name gives. Otherwise it returns
// nothing.
func (m *Matcher) fileNamed(ctx context.Context, lib db.Library, f db.MediaFile, tmdbID int, g parser.Guess, err error) ([]int64, error) {
	if !isUnlisted(err) || !numbered(g) {
		return nil, nil
	}
	return m.linkFileNamed(ctx, lib, tmdbID, map[int][]int{*g.Season: g.Episodes}, f.Path)
}

// linkFileNamed returns the episodes of a stored show at these season and
// episode numbers, making each one TMDB does not list as an episode named
// after the file at path, and the season too when TMDB has none with the
// number. Episodes that exist (TMDB's, or made for another copy of the
// file) are used as they are.
func (m *Matcher) linkFileNamed(ctx context.Context, lib db.Library, tmdbID int, numbers map[int][]int, path string) ([]int64, error) {
	seasons := make([]int, 0, len(numbers))
	for s, eps := range numbers {
		if s < 1 || len(eps) == 0 || slices.Min(eps) < 1 {
			return nil, nil
		}
		seasons = append(seasons, s)
	}
	slices.Sort(seasons)
	defer m.shows.lock(showKey{lib.ID, tmdbID})()
	show, err := m.q.GetShowByTMDB(ctx, db.GetShowByTMDBParams{LibraryID: lib.ID, TmdbID: int64(tmdbID)})
	if err != nil {
		return nil, err
	}
	title := fileTitle(path)
	var ids []int64
	// One transaction, so the sweep never finds a season made here without
	// its episodes.
	err = m.write(ctx, func(q *db.Queries) error {
		ids = nil
		for _, s := range seasons {
			if err := q.InsertFileNamedSeason(ctx, db.InsertFileNamedSeasonParams{ShowID: show.ID, Number: int64(s), Name: fmt.Sprintf("Season %d", s)}); err != nil {
				return err
			}
			season, err := q.GetSeasonByNumber(ctx, db.GetSeasonByNumberParams{ShowID: show.ID, Number: int64(s)})
			if err != nil {
				return err
			}
			for _, n := range numbers[s] {
				if err := q.InsertFileNamedEpisode(ctx, db.InsertFileNamedEpisodeParams{SeasonID: season.ID, ShowID: show.ID, Number: int64(n), Title: title}); err != nil {
					return err
				}
				ep, err := q.GetEpisodeByNumber(ctx, db.GetEpisodeByNumberParams{SeasonID: season.ID, Number: int64(n)})
				if err != nil {
					return err
				}
				ids = append(ids, ep.ID)
			}
		}
		return nil
	})
	return ids, err
}

// fileTitle is the title of an episode named after a file: the file's name
// without its extension, composed (NFC) as names on some servers are not.
func fileTitle(path string) string {
	base := filepath.Base(path)
	return norm.NFC.String(strings.TrimSuffix(base, filepath.Ext(base)))
}

// retireFileNamed follows record relinking a file that was linked to the
// episodes named after it in was (in order). Each of them no file is linked
// to now hands its watch state to the episode in the same place in now (or
// clears it when now has none there) and is removed, with its season when
// that was made for files and is left without episodes.
func (m *Matcher) retireFileNamed(ctx context.Context, was, now []int64) error {
	gone, err := m.q.ListFilelessFileNamedEpisodes(ctx, was)
	if err != nil || len(gone) == 0 {
		return err
	}
	moves := make([]watchstate.Move, 0, len(gone))
	ids := make([]int64, 0, len(gone))
	seasons := make([]int64, 0, len(gone))
	for _, g := range gone {
		var to int64
		if i := slices.Index(was, g.ID); i >= 0 && i < len(now) {
			to = now[i]
		}
		moves = append(moves, watchstate.Move{From: g.ID, To: to})
		ids = append(ids, g.ID)
		seasons = append(seasons, g.SeasonID)
	}
	if err := m.opts.Watch.Move(ctx, watchstate.Episode, moves); err != nil {
		return err
	}
	if err := m.q.DeleteFilelessFileNamedEpisodes(ctx, ids); err != nil {
		return err
	}
	return m.q.DeleteEmptyFileNamedSeasons(ctx, seasons)
}

// sweepFileNamed removes a library's episodes named after files that no
// file is linked to any more (a person deleted the file: scans only mark
// files missing, and those keep their links), then its file-named seasons
// left without episodes. It reports whether it removed any.
func (m *Matcher) sweepFileNamed(ctx context.Context, lib db.Library) bool {
	eps, err := m.q.SweepFileNamedEpisodes(ctx, lib.ID)
	if err != nil {
		if ctx.Err() == nil {
			m.log.Warn("remove file-named episodes without files", "library", lib.ID, "err", err)
		}
		return false
	}
	seasons, err := m.q.SweepFileNamedSeasons(ctx, lib.ID)
	if err != nil && ctx.Err() == nil {
		m.log.Warn("remove empty file-named seasons", "library", lib.ID, "err", err)
	}
	return eps+seasons > 0
}
