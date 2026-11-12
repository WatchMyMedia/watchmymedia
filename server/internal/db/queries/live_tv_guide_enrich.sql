-- name: ListEnrichProgrammes :many
-- Every programme TMDB may match: episodes and movies.
SELECT id, start_at, end_at, title, episode_title, season, episode, air_date, categories, kind, series_key,
description, description_source, feed_description, picture, picture_source, still, tmdb_kind, tmdb_id
FROM live_tv_programmes WHERE kind IN ('episode', 'movie');

-- name: SetProgrammeEnrichment :exec
UPDATE live_tv_programmes SET description = ?, description_source = ?, feed_description = ?, picture = ?,
picture_source = ?, still = ?, tmdb_kind = ?, tmdb_id = ?
WHERE id = ?;

-- name: ListGuideMatches :many
-- Matches checked since a time.
SELECT * FROM live_tv_guide_matches WHERE checked_at >= ?;

-- name: PutGuideMatch :exec
INSERT OR REPLACE INTO live_tv_guide_matches (series_key, tmdb_kind, tmdb_id, backdrop, overview, checked_at)
VALUES (?, ?, ?, ?, ?, ?);

-- name: ExpireGuideMatches :exec
DELETE FROM live_tv_guide_matches WHERE checked_at < ?;

-- name: GetGuideSeason :one
SELECT * FROM live_tv_guide_seasons WHERE tmdb_id = ? AND season = ?;

-- name: PutGuideSeason :exec
INSERT OR REPLACE INTO live_tv_guide_seasons (tmdb_id, season, episodes, checked_at) VALUES (?, ?, ?, ?);

-- name: ExpireGuideSeasons :exec
DELETE FROM live_tv_guide_seasons WHERE checked_at < ?;
