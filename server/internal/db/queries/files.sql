-- name: ListMediaFilesByIDs :many
-- Several files at once, for acting on a selection.
SELECT * FROM media_files WHERE id IN (sqlc.slice(ids));

-- name: ListFileMatchStates :many
-- The library and match status of several files, to check a selection
-- before changing it.
SELECT id, library_id, match_status FROM media_files WHERE id IN (sqlc.slice(ids));

-- name: SetMediaFilesIgnored :execrows
UPDATE media_files SET match_ignored = sqlc.arg(match_ignored), updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE library_id = sqlc.arg(library_id) AND id IN (sqlc.slice(ids));

-- name: GetFileItem :one
-- What a file is, for history and replacing it: its movie, or else the
-- first episode it holds (a file can hold S01E01E02) with that episode's
-- season and show. Columns of the side that does not apply are NULL.
SELECT f.id, f.library_id, f.path, f.status,
    m.id AS movie_id, m.tmdb_id AS movie_tmdb_id, m.title AS movie_title, m.year AS movie_year,
    m.poster_path AS movie_poster_path, m.backdrop_path AS movie_backdrop_path,
    e.id AS episode_id, e.title AS episode_title, e.number AS episode_number, e.still_path AS episode_still_path,
    se.number AS season_number,
    s.id AS show_id, s.tmdb_id AS show_tmdb_id, s.title AS show_title, s.year AS show_year, s.poster_path AS show_poster_path
FROM media_files f
LEFT JOIN movies m ON m.id = f.movie_id
LEFT JOIN episodes e ON f.movie_id IS NULL AND e.id = (
    SELECT ef.episode_id FROM episode_files ef
    CROSS JOIN episodes e2 ON e2.id = ef.episode_id
    CROSS JOIN seasons se2 ON se2.id = e2.season_id
    WHERE ef.media_file_id = f.id
    ORDER BY se2.number, e2.number
    LIMIT 1)
LEFT JOIN seasons se ON se.id = e.season_id
LEFT JOIN shows s ON s.id = e.show_id
WHERE f.id = ?;
