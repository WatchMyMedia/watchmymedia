-- name: GetMediaFileAddedAt :one
-- When a file counts as added, as the New badge reads it, for the arrival
-- rule.
SELECT added_at FROM media_files WHERE id = ?;

-- name: RecordEpisodeArrival :exec
-- An episode's arrival, unless its TMDB episode (the show's TMDB id, the
-- season and the number) has one, in any library: it arrives once. It
-- looks first rather than leaning on the unique index, since a conflict
-- still uses up an AUTOINCREMENT id and a cursor would name no arrival.
-- An episode named after a file is no TMDB episode: it would take the
-- number's arrival from the episode TMDB lists there later, so it arrives
-- when TMDB lists it and the file is linked to it again.
INSERT INTO episode_arrivals (tmdb_id, season, number, episode_id, arrived_at, quiet)
SELECT s.tmdb_id, se.number, e.number, e.id, CAST(sqlc.arg(arrived_at) AS TEXT), CAST(sqlc.arg(quiet) AS INTEGER)
FROM episodes e
CROSS JOIN seasons se ON se.id = e.season_id
CROSS JOIN shows s ON s.id = e.show_id
WHERE e.id = sqlc.arg(episode_id) AND e.file_named = 0
  AND NOT EXISTS (SELECT 1 FROM episode_arrivals a WHERE a.tmdb_id = s.tmdb_id AND a.season = se.number AND a.number = e.number);

-- name: AdoptEpisodeArrival :exec
-- An arrival whose episode row has gone, with its library, moves to this
-- episode row of the same TMDB episode (not to one named after a file).
UPDATE episode_arrivals SET episode_id = sqlc.arg(episode_id)
WHERE episode_id IS NULL AND id = (
    SELECT a.id FROM episodes e
    CROSS JOIN seasons se ON se.id = e.season_id
    CROSS JOIN shows s ON s.id = e.show_id
    CROSS JOIN episode_arrivals a ON a.tmdb_id = s.tmdb_id AND a.season = se.number AND a.number = e.number
    WHERE e.id = sqlc.arg(episode_id) AND e.file_named = 0);

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
-- id (the lowest, for a show in two libraries) and its newest arrival,
-- announced or not, the last entry for the TMDB id in (tmdb_id, id). The
-- arrival outlives a library removed; its title is its episode's while
-- the episode is there.
SELECT f.tmdb_id, f.title, f.created_at,
    s.id AS show_id, s.title AS show_title, s.poster_path AS show_poster_path,
    a.arrived_at AS latest_arrived_at, a.season AS latest_season, a.number AS latest_number, e.title AS latest_title
FROM show_follows f
LEFT JOIN shows s ON s.id = (SELECT MIN(s2.id) FROM shows s2 WHERE s2.tmdb_id = f.tmdb_id)
LEFT JOIN episode_arrivals a ON a.id = (SELECT MAX(a2.id) FROM episode_arrivals a2 WHERE a2.tmdb_id = f.tmdb_id)
LEFT JOIN episodes e ON e.id = a.episode_id
WHERE f.user_id = sqlc.arg(user_id)
ORDER BY f.created_at DESC, f.since_arrival DESC, f.tmdb_id DESC;

-- name: ListFollowedArrivals :many
-- What a person hears about, in the order it arrived: the
-- announced arrivals of shows they follow, after both the device's cursor
-- and the follow and up to upto, since a date (the week, or the wait),
-- that they haven't finished in any library, as the show page says.
-- (Mark show watched leaves episodes that haven't aired unmarked, so they
-- are unfinished when they come.) Each comes with an episode row of its
-- TMDB episode that has a present file, in any library: its own row
-- first. One with none is left out. The join is pinned: the person's follows (tens of
-- rows), then each one's arrivals from (tmdb_id, id); an episode row is
-- found by show TMDB id, season and number.
SELECT a.id, a.tmdb_id, a.arrived_at, a.season AS season_number, a.number AS episode_number,
    e.id AS episode_id, e.show_id, e.title, e.still_path, s.title AS show_title, s.poster_path AS show_poster_path
FROM show_follows f
CROSS JOIN episode_arrivals a INDEXED BY episode_arrivals_follow_idx
    ON a.tmdb_id = f.tmdb_id AND a.id > MAX(f.since_arrival, CAST(sqlc.arg(after) AS INTEGER)) AND a.id <= sqlc.arg(upto)
CROSS JOIN episodes e ON e.id = (
    SELECT e2.id FROM shows s2
    CROSS JOIN seasons se2 ON se2.show_id = s2.id AND se2.number = a.season
    CROSS JOIN episodes e2 ON e2.season_id = se2.id AND e2.number = a.number
    WHERE s2.tmdb_id = a.tmdb_id
      AND EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files mf ON mf.id = ef.media_file_id
          WHERE ef.episode_id = e2.id AND mf.status = 'present')
    ORDER BY e2.id = a.episode_id DESC, e2.id
    LIMIT 1)
CROSS JOIN shows s ON s.id = e.show_id
WHERE f.user_id = sqlc.arg(user_id) AND a.quiet = 0 AND a.arrived_at >= sqlc.arg(since)
  AND NOT EXISTS (SELECT 1 FROM shows s3
      CROSS JOIN seasons se3 ON se3.show_id = s3.id AND se3.number = a.season
      CROSS JOIN episodes e3 ON e3.season_id = se3.id AND e3.number = a.number
      CROSS JOIN watch_state w ON w.user_id = f.user_id AND w.item_type = 'episode' AND w.item_id = e3.id AND w.completed = 1
      WHERE s3.tmdb_id = a.tmdb_id)
ORDER BY a.id
LIMIT sqlc.arg(max_rows);

-- name: ListNewestFollowedArrivals :many
-- Home's row "New from shows you follow": the same
-- arrivals as ListFollowedArrivals since a date, newest first, with what
-- an episode card shows and the episode row's tallest present file.
SELECT a.id, a.tmdb_id, a.arrived_at, a.season AS season_number, a.number AS episode_number,
    e.id AS episode_id, e.show_id, e.title, e.overview, e.air_date, e.still_path,
    s.title AS show_title, s.poster_path AS show_poster_path, s.backdrop_path AS show_backdrop_path,
    CAST(COALESCE((SELECT mf.id FROM episode_files ef CROSS JOIN media_files mf ON mf.id = ef.media_file_id
        WHERE ef.episode_id = e.id AND mf.status = 'present' ORDER BY mf.height DESC LIMIT 1), 0) AS INTEGER) AS file_id
FROM show_follows f
CROSS JOIN episode_arrivals a INDEXED BY episode_arrivals_follow_idx
    ON a.tmdb_id = f.tmdb_id AND a.id > f.since_arrival
CROSS JOIN episodes e ON e.id = (
    SELECT e2.id FROM shows s2
    CROSS JOIN seasons se2 ON se2.show_id = s2.id AND se2.number = a.season
    CROSS JOIN episodes e2 ON e2.season_id = se2.id AND e2.number = a.number
    WHERE s2.tmdb_id = a.tmdb_id
      AND EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files mf ON mf.id = ef.media_file_id
          WHERE ef.episode_id = e2.id AND mf.status = 'present')
    ORDER BY e2.id = a.episode_id DESC, e2.id
    LIMIT 1)
CROSS JOIN shows s ON s.id = e.show_id
WHERE f.user_id = sqlc.arg(user_id) AND a.quiet = 0 AND a.arrived_at >= sqlc.arg(since)
  AND NOT EXISTS (SELECT 1 FROM shows s3
      CROSS JOIN seasons se3 ON se3.show_id = s3.id AND se3.number = a.season
      CROSS JOIN episodes e3 ON e3.season_id = se3.id AND e3.number = a.number
      CROSS JOIN watch_state w ON w.user_id = f.user_id AND w.item_type = 'episode' AND w.item_id = e3.id AND w.completed = 1
      WHERE s3.tmdb_id = a.tmdb_id)
ORDER BY a.id DESC
LIMIT sqlc.arg(max_rows);
