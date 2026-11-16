package api

import (
	"cmp"
	"context"
	"fmt"
	"math"
	"net/http"
	"slices"
	"strconv"
	"time"

	"github.com/danielgtaylor/huma/v2"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/events"
)

// Following shows and the episodes that arrive for them
// (PLAN-NEW-EPISODES.md §5, API level 29). A person follows a show by its
// TMDB id, so one follow covers the show in every library and outlives the
// show's row; each device keeps its own cursor into the feed of arrivals.

// FollowsChangedEvent is the follows.changed event, to the person's own
// devices when they follow or stop following a show.
const FollowsChangedEvent = "follows.changed"

// FollowsChanged is the follows.changed event's data.
type FollowsChanged struct {
	TMDBID    int64 `json:"tmdbId" doc:"The show's TMDB id, as in listFollows."`
	Following bool  `json:"following" doc:"Whether the person follows it now."`
}

type ShowFollowInput struct {
	ID int64 `path:"id" doc:"The show's ID."`
}

type FollowIDInput struct {
	TMDBID int64 `path:"tmdbId" doc:"The followed show's TMDB id, as in listFollows."`
}

// FollowedShow is the show a follow is for, while it is in a library.
type FollowedShow struct {
	ID         int64  `json:"id"`
	Title      string `json:"title"`
	PosterPath string `json:"posterPath,omitempty" required:"false"`
}

// FollowLatest is the newest episode that arrived for a followed show.
type FollowLatest struct {
	Season    int64     `json:"season"`
	Number    int64     `json:"number"`
	Title     string    `json:"title,omitempty" required:"false"`
	ArrivedAt time.Time `json:"arrivedAt"`
}

// Follow is one show the person follows.
type Follow struct {
	TMDBID     int64         `json:"tmdbId" doc:"The show's TMDB id: what removeFollow takes."`
	Title      string        `json:"title" doc:"The show's title, kept for a show that has left the library."`
	FollowedAt time.Time     `json:"followedAt"`
	Show       *FollowedShow `json:"show,omitempty" required:"false" doc:"The show, while it is in a library. Absent once it has left every library."`
	Latest     *FollowLatest `json:"latest,omitempty" required:"false" doc:"The newest episode that arrived for it, announced or not. Absent when none has."`
}

// FollowList is the shows the person follows.
type FollowList struct {
	Follows []Follow `json:"follows" nullable:"false" doc:"Newest follow first."`
}

type FollowListOutput struct{ Body FollowList }

type NewEpisodesInput struct {
	After string `query:"after" required:"false" maxLength:"20" doc:"The cursor from the device's last reply. Without it, the reply has the current cursor and no shows: a device starts from now."`
}

// NewEpisodeShow is the show a group of new episodes belongs to.
type NewEpisodeShow struct {
	ID         int64  `json:"id"`
	TMDBID     int64  `json:"tmdbId"`
	Title      string `json:"title"`
	PosterPath string `json:"posterPath,omitempty" required:"false"`
}

// NewEpisode is an episode that arrived for a followed show.
type NewEpisode struct {
	ID        int64     `json:"id" doc:"The episode's ID."`
	Season    int64     `json:"season"`
	Number    int64     `json:"number"`
	Title     string    `json:"title,omitempty" required:"false"`
	StillPath string    `json:"stillPath,omitempty" required:"false"`
	ArrivedAt time.Time `json:"arrivedAt" doc:"When its first file was linked to it, in any library."`
}

// NewEpisodeOpen is the episode a notification opens: the earliest of the
// group.
type NewEpisodeOpen struct {
	EpisodeID int64 `json:"episodeId"`
	Season    int64 `json:"season"`
	Number    int64 `json:"number"`
}

// NewEpisodeGroup is one show's new episodes: one notification.
type NewEpisodeGroup struct {
	Show     NewEpisodeShow `json:"show"`
	Count    int            `json:"count" doc:"How many episodes arrived, which may be more than episodes lists."`
	Episodes []NewEpisode   `json:"episodes" nullable:"false" doc:"Earliest first (by season, specials last, then episode), at most 5."`
	Open     NewEpisodeOpen `json:"open" doc:"The earliest of them, for the notification to open."`
}

// NewEpisodes is what arrived for the person's followed shows since a
// device's cursor.
type NewEpisodes struct {
	Cursor string            `json:"cursor" doc:"Keep it and send it as after next time. An opaque string."`
	Shows  []NewEpisodeGroup `json:"shows" nullable:"false" doc:"One a show, the show with the newest arrival first; at most 50."`
}

type NewEpisodesOutput struct{ Body NewEpisodes }

// The limits in PLAN-NEW-EPISODES.md §5.
const (
	// maxFollows is how many shows one person may follow.
	maxFollows = 500
	// arrivalWait is how long an arrival waits before the feed lists it, so
	// episodes copied in together come as one group.
	arrivalWait = 2 * time.Minute
	// One reply of the feed lists at most this many shows and arrivals; the
	// cursor stops short of the rest, which the next check gets.
	feedMaxShows    = 50
	feedMaxArrivals = 200
	// feedListed is how many of a show's new episodes a group lists.
	feedListed = 5
	// followedRowLength is how many episodes Home's followed row shows.
	followedRowLength = 20
)

func registerFollows(h huma.API, deps Deps) {
	op := func(id, method, path, summary, description string) huma.Operation {
		return huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary, Description: description, Tags: []string{"follows"}}
	}
	huma.Register(h, op("followShow", http.MethodPut, "/shows/{id}/follow", "Follow a show for the signed-in person",
		"Following is kept by the show's TMDB id, so it covers the show in every library. Only episodes that arrive from now on count. At most 500 a person: past that the reply is 409."),
		func(ctx context.Context, in *ShowFollowInput) (*struct{}, error) {
			u, err := currentUser(ctx)
			if err != nil {
				return nil, err
			}
			show, err := deps.Queries.GetShow(ctx, in.ID)
			if err != nil {
				return nil, notFound(err, "show not found")
			}
			added := false
			err = deps.Queries.Transaction(ctx, func(q *db.Queries) error {
				if following, err := q.IsFollowingShow(ctx, db.IsFollowingShowParams{UserID: u.ID, TmdbID: show.TmdbID}); err != nil || following {
					return err
				}
				n, err := q.CountShowFollows(ctx, u.ID)
				if err != nil {
					return err
				}
				if n >= maxFollows {
					return huma.Error409Conflict(fmt.Sprintf("you follow %d shows, the most one person may; stop following one to follow another", maxFollows))
				}
				since, err := q.LatestArrivalID(ctx)
				if err != nil {
					return err
				}
				rows, err := q.InsertShowFollow(ctx, db.InsertShowFollowParams{UserID: u.ID, TmdbID: show.TmdbID, Title: show.Title,
					SinceArrival: since, CreatedAt: db.FormatTime(time.Now())})
				added = rows > 0
				return err
			})
			if err != nil {
				return nil, err
			}
			if added {
				publishFollowsChanged(deps, u.ID, show.TmdbID, true)
			}
			return nil, nil
		})
	huma.Register(h, op("unfollowShow", http.MethodDelete, "/shows/{id}/follow", "Stop following a show for the signed-in person", ""),
		func(ctx context.Context, in *ShowFollowInput) (*struct{}, error) {
			u, err := currentUser(ctx)
			if err != nil {
				return nil, err
			}
			show, err := deps.Queries.GetShow(ctx, in.ID)
			if err != nil {
				return nil, notFound(err, "show not found")
			}
			return nil, unfollow(ctx, deps, u.ID, show.TmdbID)
		})
	huma.Register(h, op("listFollows", http.MethodGet, "/me/follows", "The shows the signed-in person follows", ""),
		func(ctx context.Context, _ *struct{}) (*FollowListOutput, error) {
			u, err := currentUser(ctx)
			if err != nil {
				return nil, err
			}
			rows, err := deps.Queries.ListShowFollows(ctx, u.ID)
			if err != nil {
				return nil, err
			}
			out := &FollowListOutput{Body: FollowList{Follows: make([]Follow, 0, len(rows))}}
			for _, r := range rows {
				f := Follow{TMDBID: r.TmdbID, Title: r.Title, FollowedAt: db.ParseTime(r.CreatedAt)}
				if r.ShowID != nil {
					f.Show = &FollowedShow{ID: *r.ShowID, Title: deref(r.ShowTitle), PosterPath: deref(r.ShowPosterPath)}
				}
				if r.LatestArrivedAt != nil && r.LatestSeason != nil && r.LatestNumber != nil {
					f.Latest = &FollowLatest{Season: *r.LatestSeason, Number: *r.LatestNumber, Title: deref(r.LatestTitle), ArrivedAt: db.ParseTime(*r.LatestArrivedAt)}
				}
				out.Body.Follows = append(out.Body.Follows, f)
			}
			return out, nil
		})
	huma.Register(h, op("removeFollow", http.MethodDelete, "/me/follows/{tmdbId}", "Stop following a show by its TMDB id",
		"Works for a show that is no longer in any library, and for one not followed."),
		func(ctx context.Context, in *FollowIDInput) (*struct{}, error) {
			u, err := currentUser(ctx)
			if err != nil {
				return nil, err
			}
			return nil, unfollow(ctx, deps, u.ID, in.TMDBID)
		})
	huma.Register(h, op("listNewEpisodes", http.MethodGet, "/me/new-episodes", "Episodes that arrived for the shows the signed-in person follows",
		"What a device notifies about. Episodes that arrived after the cursor and after the person followed the show, that they haven't finished since it arrived, with a file still present, at most 7 days ago, grouped by show. An episode arrives once, in whichever library it reaches first, and is listed only when the file it came with was added in the last 7 days (the New badge's measure); a copy in another library, or in a library removed and added again, is not new. Each episode named has a present file, in any library. An arrival waits 2 minutes before it is listed, so episodes copied in together come as one group; the cursor never passes one that is waiting, nor one a full reply left out."),
		func(ctx context.Context, in *NewEpisodesInput) (*NewEpisodesOutput, error) {
			u, err := currentUser(ctx)
			if err != nil {
				return nil, err
			}
			out, err := newEpisodes(ctx, deps.Queries, u.ID, in.After, time.Now())
			if err != nil {
				return nil, err
			}
			return &NewEpisodesOutput{Body: out}, nil
		})
}

func unfollow(ctx context.Context, deps Deps, userID, tmdbID int64) error {
	n, err := deps.Queries.DeleteShowFollow(ctx, db.DeleteShowFollowParams{UserID: userID, TmdbID: tmdbID})
	if err != nil {
		return err
	}
	if n > 0 {
		publishFollowsChanged(deps, userID, tmdbID, false)
	}
	return nil
}

// publishFollowsChanged tells the person's other devices, and no one else,
// to redraw the bell and the list.
func publishFollowsChanged(deps Deps, userID, tmdbID int64, following bool) {
	if deps.Bus == nil {
		return
	}
	deps.Bus.Publish(events.Event{Type: FollowsChangedEvent, Data: FollowsChanged{TMDBID: tmdbID, Following: following},
		To: &events.Audience{UserID: userID, Private: true}})
}

// newEpisodes is the feed a device checks (PLAN-NEW-EPISODES.md §5.4) at
// now: what arrived for userID's followed shows after the cursor after.
func newEpisodes(ctx context.Context, q *db.Queries, userID int64, after string, now time.Time) (NewEpisodes, error) {
	out := NewEpisodes{Shows: []NewEpisodeGroup{}}
	// Arrivals made while this runs are left for the next check.
	upto, err := q.LatestArrivalID(ctx)
	if err != nil {
		return out, err
	}
	waitFrom := now.Add(-arrivalWait)
	if after == "" {
		// A device starts from now, short of an arrival still waiting.
		waiting, err := q.ListFollowedArrivals(ctx, db.ListFollowedArrivalsParams{UserID: userID, Upto: upto, Since: db.FormatTime(waitFrom), MaxRows: 1})
		if err != nil {
			return out, err
		}
		out.Cursor = strconv.FormatInt(upto, 10)
		if len(waiting) > 0 {
			out.Cursor = strconv.FormatInt(waiting[0].ID-1, 10)
		}
		return out, nil
	}
	cursor, err := strconv.ParseInt(after, 10, 64)
	if err != nil || cursor < 0 {
		return out, huma.Error422UnprocessableEntity("after is a cursor from an earlier reply")
	}
	if cursor >= upto {
		// Nothing new, or a cursor from before a restore: start from now.
		out.Cursor = strconv.FormatInt(upto, 10)
		return out, nil
	}
	rows, err := q.ListFollowedArrivals(ctx, db.ListFollowedArrivalsParams{UserID: userID, After: cursor, Upto: upto,
		Since: db.FormatTime(now.Add(-newWindow)), MaxRows: feedMaxArrivals + 1})
	if err != nil {
		return out, err
	}
	// An arrival is a TMDB episode's, once, so a group is a TMDB show's.
	type group struct {
		newest   int64
		episodes []db.ListFollowedArrivalsRow
	}
	groups := map[int64]*group{}
	var order []int64
	end, taken := upto, 0
	for _, r := range rows {
		g, ok := groups[r.TmdbID]
		// Stop at an arrival still waiting, or past the reply's limits: the
		// cursor stays short of it, and the next check starts there.
		if !db.ParseTime(r.ArrivedAt).Before(waitFrom) || taken == feedMaxArrivals || (!ok && len(order) == feedMaxShows) {
			end = r.ID - 1
			break
		}
		taken++
		if !ok {
			g = &group{}
			groups[r.TmdbID] = g
			order = append(order, r.TmdbID)
		}
		g.newest = r.ID
		g.episodes = append(g.episodes, r)
	}
	out.Cursor = strconv.FormatInt(end, 10)
	slices.SortFunc(order, func(a, b int64) int { return cmp.Compare(groups[b].newest, groups[a].newest) })
	for _, tmdbID := range order {
		eps := groups[tmdbID].episodes
		slices.SortFunc(eps, func(a, b db.ListFollowedArrivalsRow) int {
			return cmp.Or(cmp.Compare(seasonOrder(a.SeasonNumber), seasonOrder(b.SeasonNumber)), cmp.Compare(a.EpisodeNumber, b.EpisodeNumber))
		})
		first := eps[0]
		g := NewEpisodeGroup{
			Show:     NewEpisodeShow{ID: first.ShowID, TMDBID: tmdbID, Title: first.ShowTitle, PosterPath: deref(first.ShowPosterPath)},
			Count:    len(eps),
			Episodes: make([]NewEpisode, 0, min(len(eps), feedListed)),
			Open:     NewEpisodeOpen{EpisodeID: first.EpisodeID, Season: first.SeasonNumber, Number: first.EpisodeNumber},
		}
		for _, e := range eps[:min(len(eps), feedListed)] {
			g.Episodes = append(g.Episodes, NewEpisode{ID: e.EpisodeID, Season: e.SeasonNumber, Number: e.EpisodeNumber,
				Title: e.Title, StillPath: deref(e.StillPath), ArrivedAt: db.ParseTime(e.ArrivedAt)})
		}
		out.Shows = append(out.Shows, g)
	}
	return out, nil
}

// seasonOrder puts specials (season 0) after the other seasons.
func seasonOrder(n int64) int64 {
	if n == 0 {
		return math.MaxInt64
	}
	return n
}

// followedEpisodes is Home's row "New from shows you follow"
// (PLAN-NEW-EPISODES.md §5.5): what arrived for the person's followed shows
// in the last 7 days, by the feed's rules but without its wait, newest
// first.
func followedEpisodes(ctx context.Context, q *db.Queries, userID int64, now time.Time) ([]EpisodeCard, error) {
	rows, err := q.ListNewestFollowedArrivals(ctx, db.ListNewestFollowedArrivalsParams{UserID: userID,
		Since: db.FormatTime(now.Add(-newWindow)), MaxRows: followedRowLength})
	if err != nil {
		return nil, err
	}
	out := make([]EpisodeCard, 0, len(rows))
	for _, r := range rows {
		arrived := db.ParseTime(r.ArrivedAt)
		c := EpisodeCard{
			EpisodeID: r.EpisodeID, ShowID: r.ShowID, ShowTitle: r.ShowTitle, SeasonNumber: r.SeasonNumber, EpisodeNumber: r.EpisodeNumber,
			Title: r.Title, Overview: r.Overview, AirDate: deref(r.AirDate), StillPath: deref(r.StillPath),
			ShowPosterPath: deref(r.ShowPosterPath), ShowBackdropPath: deref(r.ShowBackdropPath),
			AddedAt: &arrived, IsNew: true,
		}
		if r.FileID > 0 {
			id := r.FileID
			c.HasFile, c.FileID = true, &id
		}
		out = append(out, c)
	}
	return out, nil
}
