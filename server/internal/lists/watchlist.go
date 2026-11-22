package lists

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"

	"watchmymedia/server/internal/db"
)

// Watchlists are a person's own lists of the movies and shows they mean
// to watch (KindWatchlist). They are lists like the others, so rows,
// layouts and the list page carry them; what is particular to them is
// here: how many a person has and how many titles each holds, their
// names, where a new title goes, what is kept for a title the library
// lacks, and when a finished title leaves.

// Watchlist limits. They are apart from the lists' own: watchlists don't
// count against a person's 25 lists or the server's 200.
const (
	MaxWatchlists      = 25
	MaxWatchlistTitles = 500
	// MaxWatchlistName is the longest name, in characters.
	MaxWatchlistName = 60
	// FirstWatchlist is the name of the watchlist a person's first save
	// makes.
	FirstWatchlist = "Watchlist"
)

// ChangedEvent is the lists.changed event, to a list's audience when it is
// made, changed or deleted, or its titles change.
const ChangedEvent = "lists.changed"

// ListsChanged is the lists.changed event's data.
type ListsChanged struct {
	ListID int64 `json:"listId"`
}

// Title is a movie or show to save to a watchlist, by its TMDB id, with
// what an app knows of it. The server keeps its own record where it has
// one; the app's words are used only for a title it has none of.
type Title struct {
	MediaType  string // movie or tv
	TMDBID     int64
	Title      string
	Year       int64
	PosterPath string
}

// SeveralError is a one-press save for a person with two or more
// watchlists, who has to choose one.
type SeveralError struct{ Watchlists []db.MediaList }

func (e *SeveralError) Error() string {
	return fmt.Sprintf("You have %d watchlists. Choose one to save to.", len(e.Watchlists))
}

// IsWatchlist is whether l is a watchlist.
func IsWatchlist(l db.MediaList) bool { return l.SourceKind == KindWatchlist }

// now is the clock watchlists are changed by.
func (s *Service) now() time.Time {
	if s.Now != nil {
		return s.Now()
	}
	return time.Now()
}

// CreateWatchlist makes a watchlist for the person, refused at 25 or for
// a name they already use.
func (s *Service) CreateWatchlist(ctx context.Context, ownerID int64, name string, removeWatched bool) (db.MediaList, error) {
	var l db.MediaList
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		var err error
		l, err = createWatchlist(ctx, q, ownerID, name, removeWatched, s.now())
		return err
	})
	return l, err
}

func createWatchlist(ctx context.Context, q *db.Queries, ownerID int64, name string, removeWatched bool, now time.Time) (db.MediaList, error) {
	have, err := q.ListWatchlists(ctx, &ownerID)
	if err != nil {
		return db.MediaList{}, err
	}
	if len(have) >= MaxWatchlists {
		return db.MediaList{}, invalid("You have %d watchlists, the most you can make.", MaxWatchlists)
	}
	if name, err = watchlistName(name, have, 0); err != nil {
		return db.MediaList{}, err
	}
	return q.CreateWatchlist(ctx, db.CreateWatchlistParams{OwnerID: &ownerID, Name: name, EntryLimit: MaxWatchlistTitles,
		RemoveWatched: db.BoolInt(removeWatched), Now: new(db.FormatTime(now))})
}

// watchlistName checks a name: one to 60 characters, and none of the
// person's other watchlists (have, but not the one with id self) called
// the same, whatever the case.
func watchlistName(name string, have []db.MediaList, self int64) (string, error) {
	name = norm.NFC.String(strings.TrimSpace(name))
	if name == "" {
		return "", invalid("Give the watchlist a name.")
	}
	if utf8.RuneCountInString(name) > MaxWatchlistName {
		return "", invalid("A watchlist's name has at most %d characters.", MaxWatchlistName)
	}
	for _, l := range have {
		if l.ID != self && strings.EqualFold(norm.NFC.String(l.Name), name) {
			return "", invalid("You already have a watchlist called %s.", l.Name)
		}
	}
	return name, nil
}

// UpdateWatchlist renames a watchlist or turns its switch on or off; nil
// leaves either as it is.
func (s *Service) UpdateWatchlist(ctx context.Context, l db.MediaList, name *string, removeWatched *bool) (db.MediaList, error) {
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		up := db.UpdateWatchlistParams{ID: l.ID, Name: l.Name, RemoveWatched: l.RemoveWatched, Now: db.FormatTime(s.now())}
		if name != nil {
			have, err := q.ListWatchlists(ctx, l.OwnerID)
			if err != nil {
				return err
			}
			if up.Name, err = watchlistName(*name, have, l.ID); err != nil {
				return err
			}
		}
		if removeWatched != nil {
			up.RemoveWatched = db.BoolInt(*removeWatched)
		}
		var err error
		l, err = q.UpdateWatchlist(ctx, up)
		return err
	})
	return l, err
}

// SaveToWatchlist saves a title to a watchlist, below its lowest rank, so
// no other entry moves. A title already there keeps its place, and saved
// is false. Refused when the watchlist is full, or when the server knows
// nothing of the title and t has no name for it.
func (s *Service) SaveToWatchlist(ctx context.Context, l db.MediaList, t Title) (saved bool, err error) {
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		saved, err = save(ctx, q, l, t, s.now())
		return err
	})
	return saved, err
}

func save(ctx context.Context, q *db.Queries, l db.MediaList, t Title, now time.Time) (bool, error) {
	_, err := q.GetWatchlistEntry(ctx, db.GetWatchlistEntryParams{ListID: l.ID, MediaType: t.MediaType, TmdbID: &t.TMDBID})
	if err == nil {
		return false, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return false, err
	}
	room, err := q.WatchlistRoom(ctx, l.ID)
	if err != nil {
		return false, err
	}
	if room.Entries >= MaxWatchlistTitles {
		return false, invalid("A watchlist holds up to %d titles.", MaxWatchlistTitles)
	}
	kept, err := describe(ctx, q, t)
	if err != nil {
		return false, err
	}
	stamp := db.FormatTime(now)
	p := db.InsertWatchlistEntryParams{ListID: l.ID, Rank: room.Lowest - 1, MediaType: t.MediaType, TmdbID: &t.TMDBID,
		Title: kept.Title, AddedAt: &stamp}
	if kept.IMDbID != "" {
		p.ImdbID = &kept.IMDbID
	}
	if kept.Year > 0 {
		p.Year = &kept.Year
	}
	if kept.PosterPath != "" {
		p.PosterPath = &kept.PosterPath
	}
	if err := q.InsertWatchlistEntry(ctx, p); err != nil {
		return false, err
	}
	return true, q.TouchMediaList(ctx, db.TouchMediaListParams{ID: l.ID, Now: stamp})
}

// tmdbPosterPath is the shape of TMDB's poster paths, which apps build an
// image address from.
var tmdbPosterPath = regexp.MustCompile(`^/[A-Za-z0-9_-]+\.(jpg|png)$`)

// describe is what a watchlist keeps for a title, for its page to show it
// while the library lacks it: the library's own record, else another
// list's (which TMDB, MDBList or an import gave), else what the app sent,
// checked.
func describe(ctx context.Context, q *db.Queries, t Title) (Entry, error) {
	e := Entry{MediaType: t.MediaType, TMDBID: t.TMDBID}
	if t.MediaType == "movie" {
		m, err := q.SavedMovieDetails(ctx, t.TMDBID)
		if err == nil {
			e.Title, e.Year, e.PosterPath, e.IMDbID = m.Title, deref(m.Year), deref(m.PosterPath), deref(m.ImdbID)
			return e, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return e, err
		}
	} else {
		sh, err := q.SavedShowDetails(ctx, t.TMDBID)
		if err == nil {
			e.Title, e.Year, e.PosterPath = sh.Title, deref(sh.Year), deref(sh.PosterPath)
			return e, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return e, err
		}
	}
	listed, err := q.ListedTitleDetails(ctx, db.ListedTitleDetailsParams{MediaType: t.MediaType, TmdbID: &t.TMDBID})
	if err == nil {
		e.Title, e.Year, e.PosterPath, e.IMDbID = listed.Title, deref(listed.Year), deref(listed.PosterPath), deref(listed.ImdbID)
		return e, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return e, err
	}
	e.Title = strings.TrimSpace(t.Title)
	if e.Title == "" || utf8.RuneCountInString(e.Title) > 300 {
		return e, invalid("Say which title this is: the server has no record of it.")
	}
	if t.Year >= 1870 && t.Year <= 2100 {
		e.Year = t.Year
	}
	if tmdbPosterPath.MatchString(t.PosterPath) {
		e.PosterPath = t.PosterPath
	}
	return e, nil
}

// SaveToOnlyWatchlist is the Watchlist button pressed once: it saves to
// the person's watchlist when they have one, making Watchlist when they
// have none (created). saved is false for a title already there. With two
// or more watchlists it saves nothing and returns a *SeveralError with
// them.
func (s *Service) SaveToOnlyWatchlist(ctx context.Context, ownerID int64, t Title) (l db.MediaList, created, saved bool, err error) {
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		have, err := q.ListWatchlists(ctx, &ownerID)
		if err != nil {
			return err
		}
		now := s.now()
		switch len(have) {
		case 0:
			if l, err = createWatchlist(ctx, q, ownerID, FirstWatchlist, true, now); err != nil {
				return err
			}
			created = true
		case 1:
			l = have[0]
		default:
			return &SeveralError{Watchlists: have}
		}
		saved, err = save(ctx, q, l, t, now)
		return err
	})
	return l, created, saved, err
}

// RemoveFromWatchlist takes a title off a watchlist; removed is false
// when it wasn't there.
func (s *Service) RemoveFromWatchlist(ctx context.Context, l db.MediaList, mediaType string, tmdbID int64) (removed bool, err error) {
	err = s.q.Transaction(ctx, func(q *db.Queries) error {
		n, err := q.DeleteWatchlistEntry(ctx, db.DeleteWatchlistEntryParams{ListID: l.ID, MediaType: mediaType, TmdbID: &tmdbID})
		if err != nil || n == 0 {
			return err
		}
		removed = true
		return q.TouchMediaList(ctx, db.TouchMediaListParams{ID: l.ID, Now: db.FormatTime(s.now())})
	})
	return removed, err
}

// RemoveFromWatchlists takes a title off every one of the person's
// watchlists, and returns the ones it was on.
func (s *Service) RemoveFromWatchlists(ctx context.Context, ownerID int64, mediaType string, tmdbID int64) ([]db.MediaList, error) {
	var left []db.MediaList
	err := s.q.Transaction(ctx, func(q *db.Queries) error {
		left = left[:0]
		have, err := q.ListWatchlists(ctx, &ownerID)
		if err != nil {
			return err
		}
		stamp := db.FormatTime(s.now())
		for _, l := range have {
			n, err := q.DeleteWatchlistEntry(ctx, db.DeleteWatchlistEntryParams{ListID: l.ID, MediaType: mediaType, TmdbID: &tmdbID})
			if err != nil {
				return err
			}
			if n == 0 {
				continue
			}
			if err := q.TouchMediaList(ctx, db.TouchMediaListParams{ID: l.ID, Now: stamp}); err != nil {
				return err
			}
			left = append(left, l)
		}
		return nil
	})
	return left, err
}

// Leave takes finished titles off the person's watchlists that remove
// what they watch. tmdbIDs are the titles (of mediaType) a change to their
// watch state touched; finishedAt says which of them the person has now
// finished and when, by the watch's own time. A title leaves a watchlist
// only when it was finished after it was saved there, so one saved to
// watch again stays until it is watched again. finishedAt is asked only
// about titles on such a watchlist, so a change with nothing saved costs
// one read. It returns the watchlists that changed.
func Leave(ctx context.Context, q *db.Queries, ownerID int64, mediaType string, tmdbIDs []int64, now time.Time,
	finishedAt func(tmdbIDs []int64) (map[int64]string, error)) ([]db.MediaList, error) {
	if len(tmdbIDs) == 0 {
		return nil, nil
	}
	ids := make([]*int64, len(tmdbIDs))
	for i := range tmdbIDs {
		ids[i] = &tmdbIDs[i]
	}
	saved, err := q.ListLeavingWatchlistEntries(ctx, db.ListLeavingWatchlistEntriesParams{MediaType: mediaType, OwnerID: &ownerID, TmdbIds: ids})
	if err != nil || len(saved) == 0 {
		return nil, err
	}
	var asked []int64
	for _, e := range saved {
		asked = append(asked, e.TmdbID)
	}
	finished, err := finishedAt(asked)
	if err != nil || len(finished) == 0 {
		return nil, err
	}
	var changed []db.MediaList
	err = q.Transaction(ctx, func(q *db.Queries) error {
		changed = changed[:0]
		touched := map[int64]bool{}
		for _, e := range saved {
			at, ok := finished[e.TmdbID]
			if !ok || !db.ParseTime(e.AddedAt).Before(db.ParseTime(at)) {
				continue
			}
			if err := q.DeleteMediaListEntry(ctx, db.DeleteMediaListEntryParams{ListID: e.ListID, Rank: e.Rank}); err != nil {
				return err
			}
			touched[e.ListID] = true
		}
		stamp := db.FormatTime(now)
		for id := range touched {
			if err := q.TouchMediaList(ctx, db.TouchMediaListParams{ID: id, Now: stamp}); err != nil {
				return err
			}
			l, err := q.GetMediaList(ctx, id)
			if err != nil {
				return err
			}
			changed = append(changed, l)
		}
		return nil
	})
	return changed, err
}
