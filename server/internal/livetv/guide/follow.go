package guide

import (
	"context"
	"database/sql"
	"errors"
	"slices"

	"watchmymedia/server/internal/db"
	"watchmymedia/server/internal/metadata"
)

// FollowIdentity is the small durable identity shared by reminders and DVR.
type FollowIdentity struct {
	ProgrammeID                    string
	StartAt                        int64
	SeriesKey, Title, EpisodeTitle string
	Season, Episode                *int64
}

func sameIdentity(r FollowIdentity, p db.LiveTvProgramme) bool {
	if r.SeriesKey != "" && p.SeriesKey == r.SeriesKey {
		if r.Season != nil && r.Episode != nil {
			return p.Season != nil && p.Episode != nil && *p.Season == *r.Season && *p.Episode == *r.Episode
		}
		return metadata.Normalize(p.EpisodeTitle) == metadata.Normalize(r.EpisodeTitle)
	}
	return metadata.Normalize(p.Title) == metadata.Normalize(r.Title) && metadata.Normalize(p.EpisodeTitle) == metadata.Normalize(r.EpisodeTitle)
}

// Follow first checks the stable listing id, then the closest matching
// programme within three hours on the same channel number. ErrNoRows means
// the listing disappeared; unrelated programmes never inherit a schedule.
func Follow(ctx context.Context, q *db.Queries, r FollowIdentity, channelIDs []string) (db.LiveTvProgramme, error) {
	p, err := q.GetProgramme(ctx, r.ProgrammeID)
	if err == nil && slices.Contains(channelIDs, p.ChannelID) && sameIdentity(r, p) {
		return p, nil
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return p, err
	}
	rows, err := q.ListProgrammesNear(ctx, db.ListProgrammesNearParams{FromAt: r.StartAt - int64(moveWindow.Seconds()), ToAt: r.StartAt + int64(moveWindow.Seconds()), ChannelIds: channelIDs})
	if err != nil {
		return p, err
	}
	best := -1
	for i, c := range rows {
		if sameIdentity(r, c) && (best < 0 || distance(c.StartAt, r.StartAt) < distance(rows[best].StartAt, r.StartAt)) {
			best = i
		}
	}
	if best < 0 {
		return db.LiveTvProgramme{}, sql.ErrNoRows
	}
	return rows[best], nil
}
