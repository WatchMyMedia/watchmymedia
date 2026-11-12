-- name: CreateRequest :one
INSERT INTO requests (user_id, media_type, tmdb_id, title, year, poster_path, seasons, status)
VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')
RETURNING *;

-- name: GetRequest :one
SELECT r.*, u.name AS user_name
FROM requests r JOIN users u ON u.id = r.user_id
WHERE r.id = ?;

-- name: ListRequestsForMedia :many
-- An item's requests, newest first, cancelled ones left out.
SELECT r.*, u.name AS user_name
FROM requests r JOIN users u ON u.id = r.user_id
WHERE r.media_type = ? AND r.tmdb_id = ? AND r.status != 'cancelled'
ORDER BY r.created_at DESC, r.id DESC;

-- name: ListUserRequests :many
SELECT r.*, u.name AS user_name
FROM requests r JOIN users u ON u.id = r.user_id
WHERE r.user_id = ? AND r.status != 'cancelled'
ORDER BY r.created_at DESC, r.id DESC
LIMIT ?;

-- name: ListOpenRequests :many
-- Requests still waiting on someone: an admin's decision, a retry, or the
-- download.
SELECT r.*, u.name AS user_name
FROM requests r JOIN users u ON u.id = r.user_id
WHERE r.status IN ('pending', 'approved', 'failed')
ORDER BY r.created_at, r.id;

-- name: CountPendingRequests :one
SELECT COUNT(*) FROM requests WHERE status = 'pending';

-- name: SetRequestSeasons :exec
UPDATE requests SET seasons = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?;

-- name: SetRequestStatus :exec
UPDATE requests
SET status = sqlc.arg('status'), note = sqlc.narg('note'),
    integration_id = coalesce(sqlc.narg('integration_id'), integration_id),
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = sqlc.arg('id');

-- name: DecideRequest :exec
UPDATE requests
SET decided_by = ?, decided_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ?;

-- name: CountPresentMovieFilesByTMDB :one
-- Without statistics SQLite starts from every media file. Find the movie
-- first so its present files use media_files_movie_idx.
SELECT COUNT(*)
FROM movies m
CROSS JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.tmdb_id = ?;

-- name: GetLibraryMovieByTMDB :one
-- The library's movie for a TMDB ID with a present file, for search.
SELECT m.id, m.library_id
FROM movies m
WHERE m.tmdb_id = ? AND EXISTS (SELECT 1 FROM media_files f WHERE f.movie_id = m.id AND f.status = 'present')
ORDER BY m.id
LIMIT 1;

-- name: GetLibraryShowByTMDB :one
SELECT s.id, s.library_id
FROM shows s
WHERE s.tmdb_id = ? AND EXISTS (
    SELECT 1 FROM episodes e
    JOIN episode_files ef ON ef.episode_id = e.id
    JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
    WHERE e.show_id = s.id)
ORDER BY s.id
LIMIT 1;

-- name: ListSeasonAvailabilityByTMDB :many
-- Each season of a TMDB show, in every library that has it: how many of its
-- episodes have a present file, and how many have aired.
SELECT se.number, se.episode_count,
    (SELECT COUNT(DISTINCT e.id) FROM episodes e
       JOIN episode_files ef ON ef.episode_id = e.id
       JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
     WHERE e.season_id = se.id) AS available_count,
    (SELECT COUNT(*) FROM episodes e
     WHERE e.season_id = se.id AND e.air_date IS NOT NULL AND e.air_date <= sqlc.arg('today')) AS aired_count
FROM shows s
JOIN seasons se ON se.show_id = s.id
WHERE s.tmdb_id = sqlc.arg('tmdb_id');

-- name: CreateProblem :one
INSERT INTO problems (user_id, item_type, item_id, media_file_id, kind, note, details)
VALUES (?, ?, ?, ?, ?, ?, ?)
RETURNING *;

-- name: ListOpenProblems :many
SELECT p.*, u.name AS user_name
FROM problems p JOIN users u ON u.id = p.user_id
WHERE p.status = 'open'
ORDER BY p.created_at, p.id;

-- name: CountOpenProblems :one
SELECT COUNT(*) FROM problems WHERE status = 'open';

-- name: ResolveProblem :execrows
UPDATE problems
SET status = 'resolved', resolved_by = ?, resolved_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ? AND status = 'open';

-- name: ListShowFilePaths :many
-- A few of a show's present files, to find its series in Sonarr by folder.
SELECT f.path
FROM episodes e
CROSS JOIN episode_files ef ON ef.episode_id = e.id
CROSS JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE e.show_id = ?
LIMIT 3;
