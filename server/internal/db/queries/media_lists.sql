-- name: CreateMediaList :one
-- A list a row is made from (PLAN-ROWS.md 7.2).
INSERT INTO media_lists (owner_id, created_by, screen, name, source_kind, params, media_type, entry_limit, source_url,
    fetched_at, next_fetch_at, wanted_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
RETURNING *;

-- name: GetMediaList :one
SELECT * FROM media_lists WHERE id = ?;

-- name: ListVisibleMediaLists :many
-- The lists a person sees: their own, newest first, then the household's.
SELECT * FROM media_lists
WHERE owner_id = sqlc.arg(user_id) OR owner_id IS NULL
ORDER BY owner_id IS NULL, id DESC;

-- name: ListHouseholdMediaLists :many
SELECT * FROM media_lists WHERE owner_id IS NULL ORDER BY id DESC;

-- name: ListAllMediaLists :many
SELECT * FROM media_lists ORDER BY id;

-- name: CountOwnedMediaLists :one
SELECT COUNT(*) FROM media_lists WHERE owner_id = ?;

-- name: CountMediaLists :one
SELECT COUNT(*) FROM media_lists;

-- name: UpdateMediaList :one
UPDATE media_lists
SET name = sqlc.arg(name), source_kind = sqlc.arg(source_kind), params = sqlc.arg(params),
    entry_limit = sqlc.arg(entry_limit), owner_id = sqlc.narg(owner_id), source_url = sqlc.arg(source_url),
    next_fetch_at = sqlc.narg(next_fetch_at),
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = sqlc.arg(id)
RETURNING *;

-- name: DeleteMediaList :execrows
DELETE FROM media_lists WHERE id = ?;

-- name: SetMediaListFetched :exec
-- A fetch worked: its time, the next one, and no error.
UPDATE media_lists
SET fetched_at = sqlc.arg(fetched_at), next_fetch_at = sqlc.narg(next_fetch_at), source_url = sqlc.arg(source_url),
    error = NULL, error_at = NULL, failures = 0
WHERE id = sqlc.arg(id);

-- name: SetMediaListFailed :exec
-- A fetch failed: the list keeps its entries and is tried again later.
UPDATE media_lists
SET error = sqlc.arg(error), error_at = sqlc.arg(error_at), failures = sqlc.arg(failures),
    next_fetch_at = sqlc.narg(next_fetch_at)
WHERE id = sqlc.arg(id);

-- name: SetMediaListsWanted :exec
-- A screen showed these lists' rows, or someone opened them.
UPDATE media_lists SET wanted_at = sqlc.arg(wanted_at) WHERE id IN (sqlc.slice(ids));

-- name: ListDueMediaLists :many
-- Lists the refresh job fetches now: due, shown or opened lately, and of
-- a kind it can fetch now; the job takes a batch of them. The slice
-- comes last, so the limit is written out rather than a parameter.
SELECT * FROM media_lists
WHERE next_fetch_at IS NOT NULL AND next_fetch_at <= sqlc.arg(now)
    AND wanted_at IS NOT NULL AND wanted_at >= sqlc.arg(wanted_since)
    AND source_kind IN (sqlc.slice(kinds))
ORDER BY next_fetch_at, id
LIMIT 100;

-- name: MoveMediaListsToHome :exec
-- A library was deleted: its lists' rows first show on Home.
UPDATE media_lists SET screen = 'home', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE screen = ?;

-- name: DeleteMediaListEntries :exec
DELETE FROM media_list_entries WHERE list_id = ?;

-- name: InsertMediaListEntry :exec
INSERT INTO media_list_entries (list_id, rank, media_type, tmdb_id, imdb_id, title, year, poster_path)
VALUES (?, ?, ?, ?, ?, ?, ?, ?);

-- name: ListMediaListEntries :many
SELECT * FROM media_list_entries WHERE list_id = ? ORDER BY rank;

-- name: CountMediaListEntries :many
-- Each list's entries of each media type, and how many TMDB couldn't find.
SELECT list_id, media_type, COUNT(*) AS entries, SUM(CASE WHEN tmdb_id IS NULL THEN 1 ELSE 0 END) AS not_found
FROM media_list_entries
WHERE list_id IN (sqlc.slice(ids))
GROUP BY list_id, media_type;

-- name: ListMediaListMovieRanks :many
-- Each list's movie entries the library has with a present file, in rank
-- order: a movie in two libraries comes once per library. The entries
-- come first, so the planner looks movies up by TMDB ID rather than
-- reading every movie; the cards come from ListMovieCardsByIDs for the
-- few a row draws. The + keeps media_type from being an index term: with
-- two or more lists in the slice and no statistics, the planner otherwise
-- reads every list's movie entries through media_list_entries_tmdb_idx
-- rather than these lists' through the primary key.
SELECT e.list_id, e.rank, m.id, m.library_id
FROM media_list_entries e
CROSS JOIN movies m ON m.tmdb_id = e.tmdb_id
WHERE e.list_id IN (sqlc.slice(ids)) AND +e.media_type = 'movie'
    AND EXISTS (SELECT 1 FROM media_files f WHERE f.movie_id = m.id AND f.status = 'present')
ORDER BY e.list_id, e.rank, m.library_id, m.id;

-- name: ListMediaListShowRanks :many
-- ListMediaListMovieRanks for shows: a show counts once it has an episode
-- with a present file, and the same + on media_type.
SELECT e.list_id, e.rank, s.id, s.library_id
FROM media_list_entries e
CROSS JOIN shows s ON s.tmdb_id = e.tmdb_id
WHERE e.list_id IN (sqlc.slice(ids)) AND +e.media_type = 'tv'
    AND EXISTS (
        SELECT 1 FROM episodes ep
        CROSS JOIN episode_files ef ON ef.episode_id = ep.id
        CROSS JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
        WHERE ep.show_id = s.id)
ORDER BY e.list_id, e.rank, s.library_id, s.id;

-- name: ListMovieCardsByIDs :many
-- ListMovieItems for some movies, in any library, for list rows.
SELECT m.id, m.library_id, m.title, m.year, m.release_date, m.overview, m.runtime_min, m.genres, m.rating,
    m.poster_path, m.backdrop_path, m.content_rating,
    COUNT(f.id) AS file_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM movies m
CROSS JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.id IN (sqlc.slice(ids))
GROUP BY m.id;

-- name: ListShowCardsByIDs :many
-- ListShowItems for some shows, in any library, for list rows.
SELECT s.id, s.library_id, s.title, s.year, s.overview, s.status, s.genres, s.rating, s.poster_path, s.backdrop_path,
    s.last_air_date, s.next_air_date, s.content_rating,
    COUNT(DISTINCT e.id) AS episode_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM shows s
CROSS JOIN episodes e ON e.show_id = s.id
CROSS JOIN episode_files ef ON ef.episode_id = e.id
CROSS JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE s.id IN (sqlc.slice(ids))
GROUP BY s.id;

-- name: ListMovieItemsByTMDB :many
-- ListMovieItems for some TMDB IDs, in every library, for a list preview.
SELECT m.tmdb_id,
    m.id, m.library_id, m.title, m.year, m.release_date, m.overview, m.runtime_min, m.genres, m.rating,
    m.poster_path, m.backdrop_path, m.content_rating,
    COUNT(f.id) AS file_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM movies m
CROSS JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.tmdb_id IN (sqlc.slice(tmdb_ids))
GROUP BY m.id
ORDER BY m.library_id, m.id;

-- name: ListShowItemsByTMDB :many
-- ListShowItems for some TMDB IDs, in every library, for a list preview.
SELECT s.tmdb_id,
    s.id, s.library_id, s.title, s.year, s.overview, s.status, s.genres, s.rating, s.poster_path, s.backdrop_path,
    s.last_air_date, s.next_air_date, s.content_rating,
    COUNT(DISTINCT ep.id) AS episode_count,
    MAX(f.added_at) AS added_at,
    MAX(f.height) AS max_height,
    MAX(CASE WHEN f.hdr != '' THEN 1 ELSE 0 END) AS has_hdr,
    SUM(f.size) AS total_size
FROM shows s
CROSS JOIN episodes ep ON ep.show_id = s.id
CROSS JOIN episode_files ef ON ef.episode_id = ep.id
CROSS JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE s.tmdb_id IN (sqlc.slice(tmdb_ids))
GROUP BY s.id
ORDER BY s.library_id, s.id;

-- name: ListMoviesByIMDb :many
-- The library's matched movies with these IMDb IDs, so an import of tt IDs
-- finds them without asking TMDB.
SELECT imdb_id, tmdb_id, title, year, poster_path
FROM movies
WHERE imdb_id IN (sqlc.slice(imdb_ids))
ORDER BY id;

-- name: GetUserPreference :one
SELECT value FROM user_preferences WHERE user_id = ? AND key = ?;

-- name: DeleteUserPreference :exec
DELETE FROM user_preferences WHERE user_id = ? AND key = ?;

-- name: DeleteUserPreferencesByKey :exec
-- A library was deleted: everyone's layout of its screen goes.
DELETE FROM user_preferences WHERE key = ?;
