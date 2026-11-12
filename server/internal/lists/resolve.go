package lists

import (
	"context"
	"errors"
	"time"

	"watchmymedia/server/internal/memo"
	"watchmymedia/server/internal/tmdb"
)

// resolver turns IMDb and TheTVDB IDs into TMDB's, through TMDB's /find.
// MDBList lists and imports name the same titles again and again (the Top
// 250 every day), so answers are kept for a month.
type resolver struct {
	tmdb  *tmdb.Client
	cache *memo.Map[string, *tmdb.FindResults]
	group memo.Group
}

func newResolver(client *tmdb.Client) *resolver {
	return &resolver{tmdb: client, cache: memo.NewMap[string, *tmdb.FindResults](30*24*time.Hour, 20000), group: memo.Group{Timeout: 30 * time.Second}}
}

// find is the TMDB title an outside ID names, preferring mediaType's kind
// (movie, tv; all tries movies first). ok is false when TMDB has none.
func (r *resolver) find(ctx context.Context, source, id, mediaType string) (tmdb.ListItem, bool, error) {
	if id == "" || !r.tmdb.Configured() {
		return tmdb.ListItem{}, false, nil
	}
	res, err := memo.Load(ctx, r.cache, &r.group, source+":"+id, func(ctx context.Context) (*tmdb.FindResults, error) {
		res, err := r.tmdb.Find(ctx, id, source)
		if errors.Is(err, tmdb.ErrNotFound) {
			return &tmdb.FindResults{}, nil
		}
		return res, err
	})
	if err != nil {
		return tmdb.ListItem{}, false, err
	}
	for _, kind := range mediaTypes(mediaType) {
		switch {
		case kind == "movie" && len(res.MovieResults) > 0:
			m := res.MovieResults[0]
			return tmdb.ListItem{MediaType: "movie", ID: m.ID, Title: m.Title, Date: m.ReleaseDate, PosterPath: m.PosterPath}, true, nil
		case kind == "tv" && len(res.TVResults) > 0:
			s := res.TVResults[0]
			return tmdb.ListItem{MediaType: "tv", ID: s.ID, Title: s.Name, Date: s.FirstAirDate, PosterPath: s.PosterPath}, true, nil
		}
	}
	return tmdb.ListItem{}, false, nil
}
