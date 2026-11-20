// Package related works out the titles shown at the end of a movie's or a
// show's page: the movie's TMDB collection, and More like
// this, from TMDB's recommendations and what titles share. It keeps what a
// title is like as tags (movie_tags, show_tags), written when a title is
// matched and filled in for older titles by Refresher.
package related

import (
	"context"
	"strconv"
	"strings"
	"time"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/tmdb"
)

// The kinds of tag.
const (
	// KindRec is one of TMDB's recommendations: its TMDB ID, ranked in
	// TMDB's order.
	KindRec = "rec"
	// KindKeyword is a TMDB keyword's ID.
	KindKeyword = "keyword"
	// KindGenre is a genre's name, as titles keep their genres.
	KindGenre = "genre"
	// KindPerson is a cast member's TMDB person ID, ranked by billing.
	KindPerson = "person"
	// KindDirector is a movie's director's TMDB person ID.
	KindDirector = "director"
	// KindCreator is a show's creator's TMDB person ID.
	KindCreator = "creator"
	// KindLanguage is the original language's ISO 639-1 code.
	KindLanguage = "language"
)

// castKept is how many of the billed cast are kept: the cast row shows 12,
// and past the first 10 the parts are small.
const castKept = 10

// Tag is one thing a title is like.
type Tag struct {
	Kind  string
	Value string
	Rank  int64
}

// MovieTags are a movie's tags from its TMDB details.
func MovieTags(mv *tmdb.Movie) []Tag {
	var out []Tag
	out = append(out, recTags(mv.Recommendations)...)
	for _, k := range mv.Keywords.Keywords {
		out = append(out, Tag{Kind: KindKeyword, Value: strconv.Itoa(k.ID)})
	}
	out = append(out, genreTags(mv.Genres)...)
	out = append(out, castTags(mv.Credits)...)
	for _, c := range mv.Credits.Crew {
		if c.Job == "Director" && c.ID > 0 {
			out = append(out, Tag{Kind: KindDirector, Value: strconv.Itoa(c.ID)})
		}
	}
	return append(out, languageTags(mv.OriginalLanguage)...)
}

// ShowTags are a show's tags from its TMDB details.
func ShowTags(tv *tmdb.TVShow) []Tag {
	var out []Tag
	out = append(out, recTags(tv.Recommendations)...)
	for _, k := range tv.Keywords.Results {
		out = append(out, Tag{Kind: KindKeyword, Value: strconv.Itoa(k.ID)})
	}
	out = append(out, genreTags(tv.Genres)...)
	out = append(out, castTags(tv.Credits)...)
	for _, p := range tv.CreatedBy {
		if p.ID > 0 {
			out = append(out, Tag{Kind: KindCreator, Value: strconv.Itoa(p.ID)})
		}
	}
	return append(out, languageTags(tv.OriginalLanguage)...)
}

func recTags(r tmdb.Recommendations) []Tag {
	ids := r.IDs()
	out := make([]Tag, 0, len(ids))
	for i, id := range ids {
		out = append(out, Tag{Kind: KindRec, Value: strconv.Itoa(id), Rank: int64(i)})
	}
	return out
}

func genreTags(gs []tmdb.Genre) []Tag {
	out := make([]Tag, 0, len(gs))
	for _, g := range gs {
		if g.Name != "" {
			out = append(out, Tag{Kind: KindGenre, Value: g.Name})
		}
	}
	return out
}

func castTags(c tmdb.Credits) []Tag {
	var out []Tag
	for i, m := range c.Cast {
		if i == castKept {
			break
		}
		if m.ID > 0 {
			out = append(out, Tag{Kind: KindPerson, Value: strconv.Itoa(m.ID), Rank: int64(i)})
		}
	}
	return out
}

func languageTags(code string) []Tag {
	code = strings.ToLower(strings.TrimSpace(code))
	if code == "" || code == "xx" {
		return nil
	}
	return []Tag{{Kind: KindLanguage, Value: code}}
}

// StoreMovie replaces a movie's tags and collection with those of its TMDB
// details, and marks its related data fetched at now. mv nil (TMDB no
// longer has it) stores none. A movie deleted meanwhile is left alone. Call
// it inside a transaction.
func StoreMovie(ctx context.Context, q *db.Queries, id int64, mv *tmdb.Movie, now time.Time) error {
	var collectionID *int64
	var collectionName *string
	if mv != nil {
		if c := mv.Collection; c != nil && c.ID > 0 {
			collectionID, collectionName = new(int64(c.ID)), new(c.Name)
		}
	}
	n, err := q.SetMovieRelated(ctx, db.SetMovieRelatedParams{CollectionID: collectionID, CollectionName: collectionName,
		RelatedCheckedAt: new(db.FormatTime(now)), ID: id})
	if err != nil || n == 0 {
		return err
	}
	if err := q.DeleteMovieTags(ctx, id); err != nil {
		return err
	}
	if mv == nil {
		return nil
	}
	for _, t := range MovieTags(mv) {
		if err := q.InsertMovieTag(ctx, db.InsertMovieTagParams{MovieID: id, Kind: t.Kind, Value: t.Value, Rank: t.Rank}); err != nil {
			return err
		}
	}
	return nil
}

// StoreShow replaces a show's tags with those of its TMDB details, and
// marks its related data fetched at now. tv nil stores none. A show deleted
// meanwhile is left alone. Call it inside a transaction.
func StoreShow(ctx context.Context, q *db.Queries, id int64, tv *tmdb.TVShow, now time.Time) error {
	n, err := q.SetShowRelatedChecked(ctx, db.SetShowRelatedCheckedParams{RelatedCheckedAt: new(db.FormatTime(now)), ID: id})
	if err != nil || n == 0 {
		return err
	}
	if err := q.DeleteShowTags(ctx, id); err != nil {
		return err
	}
	if tv == nil {
		return nil
	}
	for _, t := range ShowTags(tv) {
		if err := q.InsertShowTag(ctx, db.InsertShowTagParams{ShowID: id, Kind: t.Kind, Value: t.Value, Rank: t.Rank}); err != nil {
			return err
		}
	}
	return nil
}
