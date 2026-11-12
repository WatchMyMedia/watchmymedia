-- name: ListDownloadedSubtitles :many
SELECT * FROM downloaded_subtitles WHERE media_file_id = ? ORDER BY id;

-- name: GetDownloadedSubtitle :one
SELECT * FROM downloaded_subtitles WHERE id = ? AND media_file_id = ?;

-- name: GetDownloadedSubtitleByProviderFile :one
SELECT * FROM downloaded_subtitles WHERE media_file_id = ? AND provider = ? AND provider_file_id = ?;

-- name: InsertDownloadedSubtitle :one
-- A row kept for the same result meanwhile wins: nothing is inserted and
-- no row comes back (sql.ErrNoRows), so read that one instead.
INSERT INTO downloaded_subtitles (media_file_id, provider, provider_file_id, language, release, hearing_impaired, forced, hash_match, path, downloaded_by)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (media_file_id, provider, provider_file_id) DO NOTHING
RETURNING *;

-- name: DeleteDownloadedSubtitle :exec
DELETE FROM downloaded_subtitles WHERE id = ? AND media_file_id = ?;

-- name: GetFileSubtitleTarget :one
-- What a subtitle search needs about a file: its movie, or its first
-- episode with the show.
SELECT mf.id, mf.path,
    m.tmdb_id AS movie_tmdb_id, m.imdb_id AS movie_imdb_id, m.title AS movie_title, m.year AS movie_year,
    sh.tmdb_id AS show_tmdb_id, sh.title AS show_title, se.number AS season_number, e.number AS episode_number
FROM media_files mf
LEFT JOIN movies m ON m.id = mf.movie_id
LEFT JOIN episode_files ef ON ef.media_file_id = mf.id
LEFT JOIN episodes e ON e.id = ef.episode_id
LEFT JOIN seasons se ON se.id = e.season_id
LEFT JOIN shows sh ON sh.id = e.show_id
WHERE mf.id = ?
ORDER BY se.number, e.number
LIMIT 1;
