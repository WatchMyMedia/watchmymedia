package api

import (
	"context"
	"net/http"
	"time"

	"github.com/danielgtaylor/huma/v2"
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
	ArrivedAt time.Time `json:"arrivedAt" doc:"When its first file was linked to it."`
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

func registerFollows(h huma.API, deps Deps) {
	op := func(id, method, path, summary, description string) huma.Operation {
		return huma.Operation{OperationID: id, Method: method, Path: Prefix + path, Summary: summary, Description: description, Tags: []string{"follows"}}
	}
	huma.Register(h, op("followShow", http.MethodPut, "/shows/{id}/follow", "Follow a show for the signed-in person",
		"Following is kept by the show's TMDB id, so it covers the show in every library. At most 500 a person: past that the reply is 409."),
		func(ctx context.Context, in *ShowFollowInput) (*struct{}, error) {
			return nil, huma.Error501NotImplemented("not built yet")
		})
	huma.Register(h, op("unfollowShow", http.MethodDelete, "/shows/{id}/follow", "Stop following a show for the signed-in person", ""),
		func(ctx context.Context, in *ShowFollowInput) (*struct{}, error) {
			return nil, huma.Error501NotImplemented("not built yet")
		})
	huma.Register(h, op("listFollows", http.MethodGet, "/me/follows", "The shows the signed-in person follows", ""),
		func(ctx context.Context, _ *struct{}) (*FollowListOutput, error) {
			return &FollowListOutput{Body: FollowList{Follows: []Follow{}}}, nil
		})
	huma.Register(h, op("removeFollow", http.MethodDelete, "/me/follows/{tmdbId}", "Stop following a show by its TMDB id",
		"Works for a show that is no longer in any library."),
		func(ctx context.Context, in *FollowIDInput) (*struct{}, error) {
			return nil, huma.Error501NotImplemented("not built yet")
		})
	huma.Register(h, op("listNewEpisodes", http.MethodGet, "/me/new-episodes", "Episodes that arrived for the shows the signed-in person follows",
		"What a device notifies about. Episodes that arrived after the cursor and after the person followed the show, that they haven't finished, with a file still present, at most 7 days ago, grouped by show. An arrival waits 2 minutes before it is listed, so episodes copied in together come as one group; the cursor never passes one that is waiting."),
		func(ctx context.Context, in *NewEpisodesInput) (*NewEpisodesOutput, error) {
			return &NewEpisodesOutput{Body: NewEpisodes{Cursor: "0", Shows: []NewEpisodeGroup{}}}, nil
		})
}
