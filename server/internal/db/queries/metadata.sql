-- name: UpsertMovie :one
INSERT INTO movies (library_id, tmdb_id, imdb_id, title, original_title, year, release_date, overview, tagline, runtime_min, genres, rating, poster_path, backdrop_path, credits, content_rating, trailers, trailers_checked_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (library_id, tmdb_id) DO UPDATE SET
    imdb_id = excluded.imdb_id, title = excluded.title, original_title = excluded.original_title,
    year = excluded.year, release_date = excluded.release_date, overview = excluded.overview,
    tagline = excluded.tagline, runtime_min = excluded.runtime_min, genres = excluded.genres,
    rating = excluded.rating, backdrop_path = excluded.backdrop_path,
    -- A poster an admin chose stays; TMDB's latest is kept beside it.
    poster_tmdb = excluded.poster_path,
    poster_path = CASE WHEN movies.poster_source = '' THEN excluded.poster_path ELSE movies.poster_path END,
    credits = excluded.credits, content_rating = excluded.content_rating,
    trailers = excluded.trailers, trailers_checked_at = excluded.trailers_checked_at,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
RETURNING *;

-- name: ListMoviesForTrailers :many
-- Movies with a file on disk whose TMDB trailers are due a check
-- (PLAN-TRAILERS.md 5.1), never checked first, then the longest since.
-- Movies released on or after recent_since, or not yet released, are due
-- once last checked before recent_before; every movie is due once last
-- checked before older_before, which is earlier. Never checked ('') is
-- before both. It reads every movie (a few thousand rows), a few times a
-- day.
SELECT m.id, m.tmdb_id FROM movies m
WHERE (COALESCE(m.trailers_checked_at, '') < CAST(sqlc.arg(older_before) AS TEXT)
       OR (m.release_date >= CAST(sqlc.arg(recent_since) AS TEXT)
           AND COALESCE(m.trailers_checked_at, '') < CAST(sqlc.arg(recent_before) AS TEXT)))
  AND EXISTS (SELECT 1 FROM media_files f WHERE f.movie_id = m.id AND f.status = 'present')
ORDER BY m.trailers_checked_at, m.id
LIMIT sqlc.arg(max_rows);

-- name: SetMovieTrailers :exec
UPDATE movies SET trailers = ?, trailers_checked_at = ? WHERE id = ?;

-- name: GetMovie :one
SELECT * FROM movies WHERE id = ?;

-- name: ListMovies :many
SELECT sqlc.embed(m), COUNT(f.id) AS file_count
FROM movies m
JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.library_id = ?
GROUP BY m.id
ORDER BY m.title COLLATE NOCASE
LIMIT ? OFFSET ?;

-- name: CountMovies :one
SELECT COUNT(DISTINCT m.id)
FROM movies m
JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.library_id = ?;

-- name: ListMovieFiles :many
SELECT * FROM media_files WHERE movie_id = ? ORDER BY path;

-- name: UpsertShow :one
INSERT INTO shows (library_id, tmdb_id, title, original_title, year, first_air_date, overview, status, genres, rating, poster_path, backdrop_path, last_air_date, next_air_date, credits, content_rating)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (library_id, tmdb_id) DO UPDATE SET
    title = excluded.title, original_title = excluded.original_title, year = excluded.year,
    first_air_date = excluded.first_air_date, overview = excluded.overview, status = excluded.status,
    genres = excluded.genres, rating = excluded.rating,
    poster_tmdb = excluded.poster_path,
    poster_path = CASE WHEN shows.poster_source = '' THEN excluded.poster_path ELSE shows.poster_path END,
    backdrop_path = excluded.backdrop_path, last_air_date = excluded.last_air_date,
    next_air_date = excluded.next_air_date, credits = excluded.credits, content_rating = excluded.content_rating,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
RETURNING *;

-- name: GetShow :one
SELECT * FROM shows WHERE id = ?;

-- name: GetShowByTMDB :one
SELECT * FROM shows WHERE library_id = ? AND tmdb_id = ?;

-- name: ListShows :many
SELECT sqlc.embed(s), COUNT(DISTINCT e.id) AS episode_count
FROM shows s
JOIN episodes e ON e.show_id = s.id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE s.library_id = ?
GROUP BY s.id
ORDER BY s.title COLLATE NOCASE
LIMIT ? OFFSET ?;

-- name: CountShows :one
SELECT COUNT(DISTINCT s.id)
FROM shows s
JOIN episodes e ON e.show_id = s.id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE s.library_id = ?;

-- name: ListMoviesWithoutContentRating :many
-- Movies matched before content ratings were kept.
SELECT id, tmdb_id FROM movies WHERE library_id = ? AND content_rating IS NULL;

-- name: SetMovieContentRating :exec
UPDATE movies SET content_rating = ? WHERE id = ?;

-- name: ListShowsWithoutContentRating :many
SELECT id, tmdb_id FROM shows WHERE library_id = ? AND content_rating IS NULL;

-- name: SetShowContentRating :exec
UPDATE shows SET content_rating = ? WHERE id = ?;

-- name: UpsertSeason :one
INSERT INTO seasons (show_id, number, name, overview, air_date, poster_path, episode_count)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (show_id, number) DO UPDATE SET
    name = excluded.name, overview = excluded.overview, air_date = excluded.air_date,
    poster_tmdb = excluded.poster_path,
    poster_path = CASE WHEN seasons.poster_source = '' THEN excluded.poster_path ELSE seasons.poster_path END,
    episode_count = excluded.episode_count
RETURNING *;

-- name: GetSeason :one
SELECT * FROM seasons WHERE id = ?;

-- name: GetSeasonByNumber :one
SELECT * FROM seasons WHERE show_id = ? AND number = ?;

-- name: ListSeasons :many
SELECT s.*,
    (SELECT COUNT(DISTINCT e.id) FROM episodes e
       JOIN episode_files ef ON ef.episode_id = e.id
       JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
     WHERE e.season_id = s.id) AS available_count
FROM seasons s
WHERE s.show_id = ?
ORDER BY s.number;

-- name: UpsertEpisode :one
INSERT INTO episodes (season_id, show_id, number, title, overview, air_date, still_path, runtime_min, rating)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (season_id, number) DO UPDATE SET
    title = excluded.title, overview = excluded.overview, air_date = excluded.air_date,
    still_path = excluded.still_path, runtime_min = excluded.runtime_min, rating = excluded.rating
RETURNING *;

-- name: GetEpisodeByNumber :one
SELECT * FROM episodes WHERE season_id = ? AND number = ?;

-- name: ListSeasonEpisodes :many
SELECT * FROM episodes WHERE season_id = ? ORDER BY number;

-- name: ListSeasonEpisodeFiles :many
-- The files of a season's episodes, for deleting: their paths, not the
-- whole rows with each file's probe.
SELECT ef.episode_id, f.path, f.status
FROM episode_files ef
JOIN media_files f ON f.id = ef.media_file_id
JOIN episodes e ON e.id = ef.episode_id
WHERE e.season_id = ?
ORDER BY f.path;

-- name: ListEpisodeIDsForFile :many
SELECT episode_id FROM episode_files WHERE media_file_id = ?;

-- name: DeleteEpisodeFilesForFile :exec
DELETE FROM episode_files WHERE media_file_id = ?;

-- name: InsertEpisodeFile :exec
INSERT OR IGNORE INTO episode_files (episode_id, media_file_id) VALUES (?, ?);

-- name: ListPendingMatches :many
-- Trailers (extra_kind) are never matched; the match index holds the kind,
-- so their rows are not read.
SELECT * FROM media_files
WHERE library_id = ? AND status = 'present' AND match_status = 'pending' AND extra_kind IS NULL
  AND match_locked = 0 AND match_ignored = 0
ORDER BY path;

-- name: ListFilesToRematch :many
SELECT * FROM media_files
WHERE library_id = ? AND status = 'present' AND match_locked = 0 AND match_ignored = 0
  AND match_status IN ('pending', 'unmatched', 'failed') AND extra_kind IS NULL
ORDER BY path;

-- name: SetMediaFileMatch :exec
UPDATE media_files
SET match_status = ?, movie_id = ?, match_candidates = ?, match_error = ?, matched_at = ?, match_locked = ?,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ?;

-- name: CountMediaFilesByMatch :many
-- Present files still to match, per library and status. Matched files,
-- most of a library, are skipped on the match index rather than read, and
-- so are trailers, which are never matched.
SELECT library_id, match_status, COUNT(*) AS count
FROM media_files INDEXED BY media_files_match_idx
WHERE match_status IN ('pending', 'unmatched', 'failed') AND extra_kind IS NULL AND status = 'present' AND match_ignored = 0
GROUP BY library_id, match_status;

-- name: GetEpisode :one
SELECT * FROM episodes WHERE id = ?;

-- name: UpsertMatchOverride :exec
INSERT INTO match_overrides (library_id, folder, tmdb_id) VALUES (?, ?, ?)
ON CONFLICT (library_id, folder) DO UPDATE SET tmdb_id = excluded.tmdb_id,
    created_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: GetMatchOverride :one
SELECT tmdb_id FROM match_overrides WHERE library_id = ? AND folder = ?;

-- name: ListMatchOverrides :many
SELECT * FROM match_overrides WHERE library_id = ?;

-- name: ListFilesNeedingMatch :many
-- The files the unmatched groups are made of, with the columns they show:
-- not each file's probe, which is the largest. Without statistics SQLite
-- would read the whole library off its (library_id, status) index rather
-- than the few files the match index finds, hence INDEXED BY. Trailers are
-- left out on the index too.
SELECT id, path, size, parsed, height, duration_ms, added_at, match_status, match_error, match_candidates, match_ignored
FROM media_files INDEXED BY media_files_match_idx
WHERE library_id = ? AND match_status IN ('pending', 'unmatched', 'failed') AND extra_kind IS NULL AND status = 'present'
ORDER BY path;

-- name: GetShowForFolder :one
-- The show that files under a folder prefix are already linked to. The
-- paths are a range, prefix up to prefix_end, so the path index finds them;
-- CROSS JOIN keeps that order, since without statistics SQLite would start
-- from the library's shows and read every episode file once per folder.
SELECT s.* FROM media_files f
CROSS JOIN episode_files ef ON ef.media_file_id = f.id
CROSS JOIN episodes e ON e.id = ef.episode_id
CROSS JOIN shows s ON s.id = e.show_id
WHERE f.path >= sqlc.arg(prefix) AND f.path < sqlc.arg(prefix_end) AND s.library_id = sqlc.arg(library_id)
LIMIT 1;

-- name: ListShowFileEpisodes :many
-- Every file linked to a show, with the season and episode numbers it covers.
SELECT ef.media_file_id, s.number AS season_number, e.number AS episode_number
FROM episode_files ef
JOIN episodes e ON e.id = ef.episode_id
JOIN seasons s ON s.id = e.season_id
WHERE e.show_id = ?
ORDER BY ef.media_file_id, s.number, e.number;

-- name: ListShowWatchedEpisodes :many
-- A show's episodes that anyone has watch state on. CROSS JOIN starts from
-- the show's episodes; without statistics SQLite would read every user's
-- state on every episode instead.
SELECT DISTINCT e.id, s.number AS season_number, e.number AS episode_number
FROM episodes e
CROSS JOIN watch_state w ON w.item_type = 'episode' AND w.item_id = e.id
JOIN seasons s ON s.id = e.season_id
WHERE e.show_id = ?;

-- name: CountMovieWatchStates :one
SELECT COUNT(*) FROM watch_state WHERE item_type = 'movie' AND item_id = ?;

-- name: ListShowGuardedEpisodes :many
-- A show's episodes that anyone has a watch guard on, which includes
-- unwatches that left no watch state. Pinned as ListShowWatchedEpisodes is.
SELECT DISTINCT e.id, s.number AS season_number, e.number AS episode_number
FROM episodes e
CROSS JOIN watch_guards g ON g.item_type = 'episode' AND g.item_id = e.id
JOIN seasons s ON s.id = e.season_id
WHERE e.show_id = ?;

-- name: DeleteWatchStatesForItem :exec
DELETE FROM watch_state WHERE item_type = ? AND item_id = ?;

-- name: DeleteShow :exec
DELETE FROM shows WHERE id = ?;

-- name: DeleteMovie :exec
DELETE FROM movies WHERE id = ?;

-- name: SetMediaFileIgnored :exec
UPDATE media_files SET match_ignored = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ?;

-- name: ListShowEpisodesByAirDate :many
SELECT * FROM episodes WHERE show_id = ? AND air_date = ? ORDER BY number;

-- Chosen posters (PLAN-POSTERS.md). poster_tmdb is TMDB's own poster while
-- a choice is set: taken from poster_path when the first choice is made,
-- kept up to date by the upserts, and put back by an empty source. SQLite
-- reads the row as it was for every expression of an UPDATE.

-- name: SetMoviePoster :one
UPDATE movies SET
    poster_tmdb = CASE WHEN poster_source = '' THEN poster_path ELSE poster_tmdb END,
    poster_path = CASE
        WHEN sqlc.arg(source) <> '' THEN sqlc.arg(path)
        WHEN poster_source = '' THEN poster_path
        ELSE poster_tmdb END,
    poster_source = sqlc.arg(source),
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = sqlc.arg(id)
RETURNING *;

-- name: SetShowPoster :one
UPDATE shows SET
    poster_tmdb = CASE WHEN poster_source = '' THEN poster_path ELSE poster_tmdb END,
    poster_path = CASE
        WHEN sqlc.arg(source) <> '' THEN sqlc.arg(path)
        WHEN poster_source = '' THEN poster_path
        ELSE poster_tmdb END,
    poster_source = sqlc.arg(source),
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = sqlc.arg(id)
RETURNING *;

-- name: SetSeasonPoster :one
UPDATE seasons SET
    poster_tmdb = CASE WHEN poster_source = '' THEN poster_path ELSE poster_tmdb END,
    poster_path = CASE
        WHEN sqlc.arg(source) <> '' THEN sqlc.arg(path)
        WHEN poster_source = '' THEN poster_path
        ELSE poster_tmdb END,
    poster_source = sqlc.arg(source)
WHERE id = sqlc.arg(id)
RETURNING *;

-- name: ListCustomPosters :many
-- The pictures kept under <data_dir>/artwork that an item still shows.
SELECT poster_path FROM movies WHERE poster_source IN ('upload', 'url')
UNION
SELECT poster_path FROM shows WHERE poster_source IN ('upload', 'url')
UNION
SELECT poster_path FROM seasons WHERE poster_source IN ('upload', 'url');
