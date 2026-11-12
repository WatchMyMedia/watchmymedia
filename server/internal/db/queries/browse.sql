-- name: ListMovieItems :many
-- A library's movies with present files, for its grid. The columns are the
-- ones a poster card shows: credits, the largest, stay out.
SELECT m.id, m.library_id, m.title, m.year, m.release_date, m.overview, m.runtime_min, m.genres, m.rating,
    m.poster_path, m.backdrop_path, m.content_rating,
    COUNT(f.id) AS file_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM movies m
JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.library_id = ?
GROUP BY m.id;

-- name: ListRecentlyAddedMovieItems :many
-- ListMovieItems' newest few, by their newest file, for Recently added.
SELECT m.id, m.library_id, m.title, m.year, m.release_date, m.overview, m.runtime_min, m.genres, m.rating,
    m.poster_path, m.backdrop_path, m.content_rating,
    COUNT(f.id) AS file_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM movies m
JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.library_id = ?
GROUP BY m.id
ORDER BY added_at DESC, m.id
LIMIT ?;

-- name: ListRecentlyReleasedMovieItems :many
-- ListMovieItems' latest few releases, for Recently released; among movies
-- released the same day the newest added comes first.
SELECT m.id, m.library_id, m.title, m.year, m.release_date, m.overview, m.runtime_min, m.genres, m.rating,
    m.poster_path, m.backdrop_path, m.content_rating,
    COUNT(f.id) AS file_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM movies m
JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.library_id = ? AND m.release_date IS NOT NULL AND m.release_date != ''
GROUP BY m.id
ORDER BY m.release_date DESC, added_at DESC, m.id
LIMIT ?;

-- name: CountMovieItems :one
-- How many movies ListMovieItems lists, for Home's totals.
SELECT COUNT(*) FROM movies m
WHERE m.library_id = ? AND EXISTS (SELECT 1 FROM media_files f WHERE f.movie_id = m.id AND f.status = 'present');

-- name: ListShowItems :many
-- A library's shows with present episode files, for its grid; as
-- ListMovieItems, without credits.
SELECT s.id, s.library_id, s.title, s.year, s.overview, s.status, s.genres, s.rating, s.poster_path, s.backdrop_path,
    s.last_air_date, s.next_air_date, s.content_rating,
    COUNT(DISTINCT e.id) AS episode_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM shows s
JOIN episodes e ON e.show_id = s.id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE s.library_id = ?
GROUP BY s.id;

-- name: ListShowItemsByIDs :many
-- ListShowItems for a few shows, such as Recently added's.
SELECT s.id, s.library_id, s.title, s.year, s.overview, s.status, s.genres, s.rating, s.poster_path, s.backdrop_path,
    s.last_air_date, s.next_air_date, s.content_rating,
    COUNT(DISTINCT e.id) AS episode_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM shows s
JOIN episodes e ON e.show_id = s.id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE s.library_id = sqlc.arg(library_id) AND s.id IN (sqlc.slice(ids))
GROUP BY s.id;

-- name: CountShowItems :one
-- How many shows ListShowItems lists and their episodes with a present
-- file, for Home's totals: from the library's files, which are fewer than
-- its shows' episodes on TMDB.
SELECT COUNT(DISTINCT e.show_id) AS shows, COUNT(DISTINCT e.id) AS episodes
FROM media_files f
CROSS JOIN episode_files ef ON ef.media_file_id = f.id
CROSS JOIN episodes e ON e.id = ef.episode_id
CROSS JOIN shows s ON s.id = e.show_id
WHERE f.library_id = sqlc.arg(library_id) AND f.status = 'present' AND s.library_id = sqlc.arg(library_id);

-- name: CountEpisodesByShows :many
-- Every episode TMDB lists for each of a few shows.
SELECT show_id, COUNT(*) AS episodes FROM episodes WHERE show_id IN (sqlc.slice(ids)) GROUP BY show_id;

-- name: GetShowFileStats :one
-- ListShowItems' file columns for one show, so the show page reads its own
-- files rather than every show's in the library.
SELECT MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM episodes e
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE e.show_id = ?;

-- name: ListShowSeasons :many
-- A show's seasons, each with how many of its episodes have a present file.
SELECT sqlc.embed(s),
    (SELECT COUNT(DISTINCT e.id) FROM episodes e
       JOIN episode_files ef ON ef.episode_id = e.id
       JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
     WHERE e.season_id = s.id) AS available_count
FROM seasons s
WHERE s.show_id = ?
ORDER BY s.number;

-- name: ListSeasonFiles :many
-- The files of a season's episodes, whole, for the season page.
SELECT ef.episode_id, sqlc.embed(f)
FROM episode_files ef
JOIN media_files f ON f.id = ef.media_file_id
JOIN episodes e ON e.id = ef.episode_id
WHERE e.season_id = ?
ORDER BY f.path;

-- name: ListRecentlyAddedEpisodeFiles :many
SELECT e.id AS episode_id, e.show_id, e.number AS episode_number, e.title AS episode_title, e.air_date, e.still_path,
    se.id AS season_id, se.number AS season_number, se.episode_count AS season_episode_count,
    f.id AS file_id, f.added_at, f.height
-- Newest files first from the added_at index, so only the first few hundred
-- are read rather than every episode file sorted. Without statistics SQLite
-- would as soon take the first_seen_at index, hence INDEXED BY.
FROM media_files f INDEXED BY media_files_added_idx
CROSS JOIN episode_files ef ON ef.media_file_id = f.id
CROSS JOIN episodes e ON e.id = ef.episode_id
CROSS JOIN seasons se ON se.id = e.season_id
WHERE f.library_id = ? AND f.status = 'present'
ORDER BY f.added_at DESC, e.show_id, se.number DESC, e.number DESC
LIMIT ?;

-- name: ListRecentlyAiredEpisodes :many
-- The latest episodes aired on or before today and on or after since, from
-- the shows' (show_id, air_date) index: callers try a short window first,
-- which holds enough episodes in a library with running shows, and widen
-- it when not. The newest are picked before their files are looked up, so
-- the lookups run for those only.
SELECT e.*, top.season_number, top.show_title, s.poster_path AS show_poster_path, s.backdrop_path AS show_backdrop_path,
    COALESCE((SELECT f.id FROM episode_files ef JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
       WHERE ef.episode_id = e.id ORDER BY f.height DESC LIMIT 1), 0) AS file_id,
    COALESCE((SELECT MAX(f.added_at) FROM episode_files ef JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
       WHERE ef.episode_id = e.id), '') AS added_at
FROM (
    SELECT e2.id, e2.air_date, s2.title AS show_title, se.number AS season_number, e2.number
    FROM shows s2
    CROSS JOIN episodes e2 ON e2.show_id = s2.id
    CROSS JOIN seasons se ON se.id = e2.season_id
    WHERE s2.library_id = sqlc.arg(library_id) AND e2.air_date >= sqlc.arg(since) AND e2.air_date <= sqlc.arg(today)
    ORDER BY e2.air_date DESC, s2.title, se.number, e2.number
    LIMIT sqlc.arg(limit)
) top
CROSS JOIN episodes e ON e.id = top.id
CROSS JOIN shows s ON s.id = e.show_id
ORDER BY top.air_date DESC, top.show_title, top.season_number, top.number;

-- name: ListNewEpisodeFiles :many
-- Home's banner candidates: files added since a date for episodes that aired
-- between that date and today, newest file first. seen is whether the person
-- has watched or started the episode; follows, whether they have watched any
-- episode of its show. Like ListRecentlyAddedEpisodeFiles it reads the
-- added_at index from the newest end, so only the last few weeks' files are
-- read. follows walks the show's episodes and looks each up in watch_state,
-- rather than every episode the person has watched.
SELECT e.id AS episode_id, e.show_id, e.number AS episode_number, e.title, e.overview, e.air_date,
    se.number AS season_number, s.title AS show_title, s.poster_path AS show_poster_path, s.backdrop_path AS show_backdrop_path,
    f.id AS file_id, f.added_at, f.height,
    EXISTS (SELECT 1 FROM watch_state w
        WHERE w.user_id = sqlc.arg('user_id') AND w.item_type = 'episode' AND w.item_id = e.id
          AND (w.completed = 1 OR w.position_ms > 0)) AS seen,
    EXISTS (SELECT 1 FROM episodes e2 INDEXED BY episodes_show_air_date_idx
        CROSS JOIN watch_state w ON w.user_id = sqlc.arg('user_id') AND w.item_type = 'episode' AND w.item_id = e2.id
        WHERE e2.show_id = e.show_id AND w.completed = 1) AS follows
FROM media_files f INDEXED BY media_files_added_idx
CROSS JOIN episode_files ef ON ef.media_file_id = f.id
CROSS JOIN episodes e ON e.id = ef.episode_id
CROSS JOIN seasons se ON se.id = e.season_id
CROSS JOIN shows s ON s.id = e.show_id
WHERE f.library_id = sqlc.arg('library_id') AND f.status = 'present' AND f.added_at >= sqlc.arg('since')
  AND e.air_date >= sqlc.arg('since') AND e.air_date <= sqlc.arg('today')
ORDER BY f.added_at DESC
LIMIT sqlc.arg('limit');

-- name: SumLibrarySize :one
-- Without trailers, which the library index leaves out without reading them.
SELECT COALESCE(SUM(size), 0) AS total FROM media_files WHERE library_id = ? AND status = 'present' AND extra_kind IS NULL;

-- name: ListShowFiles :one
SELECT COUNT(DISTINCT f.id) FROM episode_files ef
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
JOIN episodes e ON e.id = ef.episode_id
WHERE e.show_id = ?;
