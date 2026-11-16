-- name: GetMediaFileFirstLinked :one
-- When a file was first linked to an episode, for the arrival rule
-- (PLAN-NEW-EPISODES.md section 5.2); empty for a file never linked.
SELECT first_linked_at FROM media_files WHERE id = ?;

-- name: SetMediaFileFirstLinked :exec
UPDATE media_files SET first_linked_at = sqlc.arg(first_linked_at)
WHERE id = sqlc.arg(id) AND first_linked_at IS NULL;

-- name: RecordEpisodeArrival :exec
-- An episode's arrival, unless it has one: an episode arrives once.
INSERT INTO episode_arrivals (episode_id, show_id, arrived_at, quiet)
SELECT e.id, e.show_id, CAST(sqlc.arg(arrived_at) AS TEXT), CAST(sqlc.arg(quiet) AS INTEGER)
FROM episodes e WHERE e.id = sqlc.arg(episode_id)
ON CONFLICT (episode_id) DO NOTHING;

-- name: LatestArrivalID :one
-- The newest arrival's id, 0 when nothing has arrived: where a follow and
-- a new device's cursor start.
SELECT CAST(COALESCE(MAX(id), 0) AS INTEGER) AS id FROM episode_arrivals;

-- name: CountShowFollows :one
SELECT COUNT(*) FROM show_follows WHERE user_id = ?;

-- name: InsertShowFollow :execrows
-- Following a show already followed changes nothing, its start included.
INSERT INTO show_follows (user_id, tmdb_id, title, since_arrival, created_at)
VALUES (sqlc.arg(user_id), sqlc.arg(tmdb_id), sqlc.arg(title), sqlc.arg(since_arrival), sqlc.arg(created_at))
ON CONFLICT (user_id, tmdb_id) DO NOTHING;

-- name: DeleteShowFollow :execrows
DELETE FROM show_follows WHERE user_id = ? AND tmdb_id = ?;

-- name: IsFollowingShow :one
SELECT EXISTS (SELECT 1 FROM show_follows WHERE user_id = ? AND tmdb_id = ?) AS is_following;

-- name: ListShowFollows :many
-- A person's follows, newest first, each with a show row that has its TMDB
-- id (the lowest, for a show in two libraries) and its newest arrival
-- across those rows, announced or not. Each show row's newest arrival is
-- the last entry of its (show_id, id) index.
SELECT f.tmdb_id, f.title, f.created_at,
    s.id AS show_id, s.title AS show_title, s.poster_path AS show_poster_path,
    a.arrived_at AS latest_arrived_at, se.number AS latest_season, e.number AS latest_number, e.title AS latest_title
FROM show_follows f
LEFT JOIN shows s ON s.id = (SELECT MIN(s2.id) FROM shows s2 WHERE s2.tmdb_id = f.tmdb_id)
LEFT JOIN episode_arrivals a ON a.id = (
    SELECT MAX((SELECT MAX(a2.id) FROM episode_arrivals a2 WHERE a2.show_id = s3.id))
    FROM shows s3 WHERE s3.tmdb_id = f.tmdb_id)
LEFT JOIN episodes e ON e.id = a.episode_id
LEFT JOIN seasons se ON se.id = e.season_id
WHERE f.user_id = sqlc.arg(user_id)
ORDER BY f.created_at DESC, f.since_arrival DESC, f.tmdb_id DESC;

-- name: ListFollowedArrivals :many
-- What a person hears about (section 5.4), in the order it arrived: the
-- announced arrivals of shows they follow, after both the device's cursor
-- and the follow and up to upto, since a date (the week, or the wait), of
-- episodes with a present file that they haven't finished. The join is
-- pinned: the person's follows (tens of rows), each one's show rows by
-- TMDB id, then each show's arrivals from its (show_id, id) index.
SELECT a.id, a.episode_id, a.show_id, a.arrived_at,
    s.tmdb_id, s.title AS show_title, s.poster_path AS show_poster_path,
    se.number AS season_number, e.number AS episode_number, e.title, e.still_path
FROM show_follows f
CROSS JOIN shows s INDEXED BY shows_tmdb_idx ON s.tmdb_id = f.tmdb_id
CROSS JOIN episode_arrivals a INDEXED BY episode_arrivals_show_idx
    ON a.show_id = s.id AND a.id > MAX(f.since_arrival, CAST(sqlc.arg(after) AS INTEGER)) AND a.id <= sqlc.arg(upto)
CROSS JOIN episodes e ON e.id = a.episode_id
CROSS JOIN seasons se ON se.id = e.season_id
WHERE f.user_id = sqlc.arg(user_id) AND a.quiet = 0 AND a.arrived_at >= sqlc.arg(since)
  AND EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files mf ON mf.id = ef.media_file_id
      WHERE ef.episode_id = a.episode_id AND mf.status = 'present')
  AND NOT EXISTS (SELECT 1 FROM watch_state w
      WHERE w.user_id = f.user_id AND w.item_type = 'episode' AND w.item_id = a.episode_id AND w.completed = 1)
ORDER BY a.id
LIMIT sqlc.arg(max_rows);

-- name: ListNewestFollowedArrivals :many
-- Home's row "New from shows you follow" (section 5.5): the same
-- arrivals as ListFollowedArrivals since a date, newest first, with what
-- an episode card shows and the episode's tallest present file.
SELECT a.id, a.episode_id, a.show_id, a.arrived_at,
    s.tmdb_id, s.title AS show_title, s.poster_path AS show_poster_path, s.backdrop_path AS show_backdrop_path,
    se.number AS season_number, e.number AS episode_number, e.title, e.overview, e.air_date, e.still_path,
    CAST(COALESCE((SELECT mf.id FROM episode_files ef CROSS JOIN media_files mf ON mf.id = ef.media_file_id
        WHERE ef.episode_id = a.episode_id AND mf.status = 'present' ORDER BY mf.height DESC LIMIT 1), 0) AS INTEGER) AS file_id
FROM show_follows f
CROSS JOIN shows s INDEXED BY shows_tmdb_idx ON s.tmdb_id = f.tmdb_id
CROSS JOIN episode_arrivals a INDEXED BY episode_arrivals_show_idx
    ON a.show_id = s.id AND a.id > f.since_arrival
CROSS JOIN episodes e ON e.id = a.episode_id
CROSS JOIN seasons se ON se.id = e.season_id
WHERE f.user_id = sqlc.arg(user_id) AND a.quiet = 0 AND a.arrived_at >= sqlc.arg(since)
  AND EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files mf ON mf.id = ef.media_file_id
      WHERE ef.episode_id = a.episode_id AND mf.status = 'present')
  AND NOT EXISTS (SELECT 1 FROM watch_state w
      WHERE w.user_id = f.user_id AND w.item_type = 'episode' AND w.item_id = a.episode_id AND w.completed = 1)
ORDER BY a.id DESC
LIMIT sqlc.arg(max_rows);
