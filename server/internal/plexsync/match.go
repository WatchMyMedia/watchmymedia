package plexsync

import (
	"context"
	"fmt"
	"strconv"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/plex"
	"watchmymedia/server/internal/watchstate"
)

// found is one watched Plex item and what matching made of it: matched to
// a local target, unmatched, or ambiguous.
type found struct {
	category       string
	target         watchstate.Target
	example        PlexExample
	sourceIdentity string
}

// showIDs is what a Plex show's own metadata says, looked up once per show
// per preview. reason is set when its episodes can't be matched.
type showIDs struct {
	tmdb   int64
	title  string
	reason string
	order  string
}

// matcher matches Plex items to local ones by exact external IDs within one
// linked library (PLAN-PLEX-SYNC.md, "Identity and media matching"). Titles
// are never compared. Anything that isn't one certain match is unmatched
// or ambiguous, and an import leaves it alone.
type matcher struct {
	q     *db.Queries
	owner *plex.Client
	shows map[string]*showIDs
}

func (m *matcher) match(ctx context.Context, libraryID int64, item plex.Item) (found, error) {
	if item.Type == "episode" {
		return m.episode(ctx, libraryID, item)
	}
	return m.movie(ctx, libraryID, item)
}

// movie matches the library's movie with the item's TMDB ID, or with its
// IMDb ID when the local movie has that IMDb ID. IDs that disagree with
// each other, or with the local movie's, and Plex editions (a cut is not
// the title as a whole) are ambiguous.
func (m *matcher) movie(ctx context.Context, libraryID int64, item plex.Item) (found, error) {
	f := found{example: PlexExample{ItemType: watchstate.Movie, Title: item.Title}}
	ids := item.ExternalIDs
	switch {
	case ids.Conflict:
		return f.ambiguous("conflicting_ids"), nil
	case item.EditionTitle != "":
		return f.ambiguous("edition"), nil
	}
	candidates := map[int64]int64{} // local movie ID → its TMDB ID
	// NormalizeGUIDs keeps only positive decimal IDs.
	if tmdb, perr := strconv.ParseInt(ids.TMDB, 10, 64); perr == nil {
		rows, err := m.q.MatchMovieByTMDB(ctx, db.MatchMovieByTMDBParams{LibraryID: libraryID, TmdbID: tmdb})
		if err != nil {
			return found{}, err
		}
		for _, id := range rows {
			candidates[id] = tmdb
		}
	}
	if ids.IMDb != "" {
		rows, err := m.q.MatchMoviesByIMDb(ctx, db.MatchMoviesByIMDbParams{LibraryID: libraryID, ImdbID: &ids.IMDb})
		if err != nil {
			return found{}, err
		}
		for _, r := range rows {
			if ids.TMDB != "" && strconv.FormatInt(r.TmdbID, 10) != ids.TMDB {
				return f.ambiguous("conflicting_ids"), nil
			}
			candidates[r.ID] = r.TmdbID
		}
	}
	switch len(candidates) {
	case 0:
		if ids.TMDB == "" && ids.IMDb == "" {
			return f.unmatched("no_tmdb_or_imdb_id"), nil
		}
		return f.unmatched("not_in_library"), nil
	case 1:
		for id, tmdb := range candidates {
			return f.matched(watchstate.Target{ItemType: watchstate.Movie, ItemID: id,
				Identity: watchstate.Identity{LibraryID: libraryID, TMDBID: tmdb}}), nil
		}
	}
	return f.ambiguous("several_matches"), nil
}

// episode matches the library's episode with the show's TMDB ID and the
// same season and episode numbers. The episode's own IDs say nothing about
// the show (a TVDB ID is not a TMDB ID); the show's come from its own
// metadata. DVD and absolute orders number episodes differently from TMDB,
// and specials are numbered differently from one source to another, so
// those are ambiguous.
func (m *matcher) episode(ctx context.Context, libraryID int64, item plex.Item) (found, error) {
	f := found{example: PlexExample{ItemType: watchstate.Episode, Title: item.Title}}
	if item.SeasonNumber == nil || item.EpisodeNumber == nil {
		return f.unmatched("no_episode_number"), nil
	}
	season, number := *item.SeasonNumber, *item.EpisodeNumber
	f.example.Season, f.example.Episode = season, number
	show, err := m.show(ctx, item.GrandparentRatingKey)
	if err != nil {
		return found{}, err
	}
	f.example.Show = show.title
	f.sourceIdentity = fmt.Sprintf("%d:%s:%s", show.tmdb, show.order, show.reason)
	switch {
	case item.ExternalIDs.Conflict:
		return f.ambiguous("conflicting_ids"), nil
	case show.reason == "conflicting_ids" || show.reason == "alternate_order":
		return f.ambiguous(show.reason), nil
	case show.reason != "":
		return f.unmatched(show.reason), nil
	case !tmdbCompatibleOrder(item.EpisodeOrdering) && (item.EpisodeOrdering != "" || !tmdbCompatibleOrder(show.order)):
		return f.ambiguous("alternate_order"), nil
	case season == 0:
		return f.ambiguous("special"), nil
	}
	rows, err := m.q.MatchEpisode(ctx, db.MatchEpisodeParams{LibraryID: libraryID, TmdbID: show.tmdb, Season: season, Episode: number})
	if err != nil {
		return found{}, err
	}
	switch len(rows) {
	case 0:
		return f.unmatched("not_in_library"), nil
	case 1:
		return f.matched(watchstate.Target{ItemType: watchstate.Episode, ItemID: rows[0],
			Identity: watchstate.Identity{LibraryID: libraryID, TMDBID: show.tmdb, Season: season, Episode: number}}), nil
	}
	return f.ambiguous("several_matches"), nil
}

// show looks up a show's IDs with the owner's server credential: they are
// the show's metadata, the same for everyone, not anyone's watch state.
// A show Plex no longer has leaves its episodes unmatched; a server that
// stops answering stops the preview.
func (m *matcher) show(ctx context.Context, ratingKey string) (*showIDs, error) {
	if s, ok := m.shows[ratingKey]; ok {
		return s, nil
	}
	if len(m.shows) >= maxShows {
		return &showIDs{reason: "too_many_shows"}, nil
	}
	s := &showIDs{}
	item, err := m.owner.Metadata(ctx, ratingKey)
	switch kind := plex.ErrorKind(err); {
	case err == nil && item.Type == "show":
		s.title = item.Title
		s.order = item.EpisodeOrdering
		ids := item.ExternalIDs
		switch {
		case ids.Conflict:
			s.reason = "conflicting_ids"
		case item.EpisodeOrdering != "" && !tmdbCompatibleOrder(item.EpisodeOrdering):
			s.reason = "alternate_order"
		case ids.TMDB == "":
			s.reason = "no_show_tmdb_id"
		default:
			if s.tmdb, err = strconv.ParseInt(ids.TMDB, 10, 64); err != nil {
				s.reason = "no_show_tmdb_id"
			}
		}
	case err == nil, kind == "unavailable", kind == "invalid_input", kind == "invalid_response":
		s.reason = "show_unreadable"
	default:
		return nil, err
	}
	m.shows[ratingKey] = s
	return s, nil
}

// tmdbCompatibleOrder requires explicit TMDB aired-order evidence before
// applying episode marks. Unstated and TVDB/DVD numbering stay unresolved.
func tmdbCompatibleOrder(order string) bool {
	switch order {
	case "tmdbAiring":
		return true
	}
	return false
}

func (f found) matched(t watchstate.Target) found {
	f.category, f.target = "matched", t
	return f
}

func (f found) unmatched(reason string) found {
	f.category, f.example.Reason = "unmatched", reason
	return f
}

func (f found) ambiguous(reason string) found {
	f.category, f.example.Reason = "ambiguous", reason
	return f
}
