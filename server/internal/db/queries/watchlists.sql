-- Watchlists: a person's own lists of the movies and shows they mean to
-- watch, as media_lists of source kind watchlist. internal/lists holds
-- their rules; these are its reads and writes.

-- name: CreateWatchlist :one
-- One of a person's watchlists: movies and shows, never fetched.
INSERT INTO media_lists (owner_id, created_by, screen, name, source_kind, media_type, entry_limit, remove_watched,
    fetched_at, created_at, updated_at)
VALUES (sqlc.arg(owner_id), sqlc.arg(owner_id), 'home', sqlc.arg(name), 'watchlist', 'all', sqlc.arg(entry_limit),
    sqlc.arg(remove_watched), sqlc.arg(now), sqlc.arg(now), sqlc.arg(now))
RETURNING *;

-- name: ListWatchlists :many
-- A person's watchlists in the order they were made.
SELECT * FROM media_lists
WHERE owner_id = sqlc.arg(owner_id) AND source_kind = 'watchlist'
ORDER BY id;

-- name: UpdateWatchlist :one
UPDATE media_lists
SET name = sqlc.arg(name), remove_watched = sqlc.arg(remove_watched), updated_at = sqlc.arg(now), fetched_at = sqlc.arg(now)
WHERE id = sqlc.arg(id)
RETURNING *;

-- name: TouchMediaList :exec
-- A watchlist's titles changed. Rows keep a list's matches until its
-- updated_at changes, and apps that don't know watchlists say when it was
-- last fetched.
UPDATE media_lists SET updated_at = sqlc.arg(now), fetched_at = sqlc.arg(now) WHERE id = sqlc.arg(id);

-- name: GetWatchlistEntry :one
SELECT * FROM media_list_entries
WHERE list_id = sqlc.arg(list_id) AND media_type = sqlc.arg(media_type) AND tmdb_id = sqlc.arg(tmdb_id)
    AND added_at IS NOT NULL;

-- name: WatchlistRoom :one
-- How many titles a list holds, and its lowest rank: a new title on a
-- watchlist goes below it.
SELECT COUNT(*) AS entries, CAST(COALESCE(MIN(rank), 1) AS INTEGER) AS lowest
FROM media_list_entries WHERE list_id = ?;

-- name: InsertWatchlistEntry :exec
INSERT INTO media_list_entries (list_id, rank, media_type, tmdb_id, imdb_id, title, year, poster_path, added_at)
VALUES (sqlc.arg(list_id), sqlc.arg(rank), sqlc.arg(media_type), sqlc.arg(tmdb_id), sqlc.narg(imdb_id), sqlc.arg(title),
    sqlc.narg(year), sqlc.narg(poster_path), sqlc.arg(added_at));

-- name: DeleteWatchlistEntry :execrows
DELETE FROM media_list_entries
WHERE list_id = sqlc.arg(list_id) AND media_type = sqlc.arg(media_type) AND tmdb_id = sqlc.arg(tmdb_id)
    AND added_at IS NOT NULL;

-- name: DeleteMediaListEntry :exec
DELETE FROM media_list_entries WHERE list_id = ? AND rank = ?;

-- name: ListWatchlistEntries :many
-- Every title on a person's watchlists, newest saved first on each: for
-- the watchlisted marks, the pickers and the watchlists' posters.
SELECT e.list_id, e.rank, e.media_type, CAST(e.tmdb_id AS INTEGER) AS tmdb_id, e.poster_path,
    CAST(e.added_at AS TEXT) AS added_at
FROM media_lists l
CROSS JOIN media_list_entries e ON e.list_id = l.id
WHERE l.owner_id = sqlc.arg(owner_id) AND l.source_kind = 'watchlist' AND e.added_at IS NOT NULL
ORDER BY l.id, e.rank;

-- name: ListWatchlistsHolding :many
-- Which of a person's watchlists hold a title, in the order they were made.
SELECT l.id
FROM media_lists l
CROSS JOIN media_list_entries e ON e.list_id = l.id AND e.media_type = sqlc.arg(media_type)
    AND e.tmdb_id = sqlc.arg(tmdb_id) AND e.added_at IS NOT NULL
WHERE l.owner_id = sqlc.arg(owner_id) AND l.source_kind = 'watchlist'
ORDER BY l.id;

-- name: ListLeavingWatchlistEntries :many
-- A person's saved entries for some titles of one type, on their
-- watchlists that remove what they watch: what finishing those titles may
-- take off. The entries are found by title, then their lists by key.
SELECT e.list_id, e.rank, CAST(e.tmdb_id AS INTEGER) AS tmdb_id, CAST(e.added_at AS TEXT) AS added_at
FROM media_list_entries e INDEXED BY media_list_entries_tmdb_idx
CROSS JOIN media_lists l ON l.id = e.list_id
WHERE e.media_type = sqlc.arg(media_type) AND e.added_at IS NOT NULL
    AND l.owner_id = sqlc.arg(owner_id) AND l.source_kind = 'watchlist' AND l.remove_watched = 1
    AND e.tmdb_id IN (sqlc.slice(tmdb_ids));

-- name: SavedMovieDetails :one
-- What to keep for a movie saved to a watchlist, from the library's own
-- record of it.
SELECT title, year, poster_path, imdb_id FROM movies WHERE tmdb_id = ? ORDER BY library_id, id LIMIT 1;

-- name: SavedShowDetails :one
-- SavedMovieDetails for a show.
SELECT title, year, poster_path FROM shows WHERE tmdb_id = ? ORDER BY library_id, id LIMIT 1;

-- name: ListedTitleDetails :one
-- What another list (from TMDB, MDBList or an import) keeps for a title,
-- for one saved to a watchlist that the library doesn't have. Watchlists'
-- own entries, which may hold what an app sent, are left out.
SELECT title, year, poster_path, imdb_id FROM media_list_entries INDEXED BY media_list_entries_tmdb_idx
WHERE media_type = ? AND tmdb_id = ? AND added_at IS NULL AND title != ''
LIMIT 1;

-- name: ListMovieTMDBIDs :many
-- Some movies' TMDB ids.
SELECT DISTINCT tmdb_id FROM movies WHERE id IN (sqlc.slice(ids));

-- name: ListEpisodeShowTMDBIDs :many
-- The TMDB ids of the shows some episodes belong to.
SELECT DISTINCT s.tmdb_id
FROM episodes e
CROSS JOIN shows s ON s.id = e.show_id
WHERE e.id IN (sqlc.slice(ids));

-- name: ListFinishedMovies :many
-- Which of some movies a person has finished, by TMDB id, with when: the
-- watch's own time, which an offline play brings from the phone.
SELECT m.tmdb_id, w.updated_at
FROM watch_state w
CROSS JOIN movies m ON m.id = w.item_id
WHERE w.user_id = sqlc.arg(user_id) AND w.item_type = 'movie' AND w.completed = 1
    AND w.item_id IN (sqlc.slice(ids));

-- name: ListFinishedShows :many
-- Of the shows with some TMDB ids, in every library, the ones whose card
-- says the person watched them: every episode with a present file
-- finished, as ListShowWatchedCounts counts them. finished_at is when the
-- latest of those episodes was finished.
SELECT s.tmdb_id, CAST(MAX(w.updated_at) AS TEXT) AS finished_at
FROM shows s
CROSS JOIN episodes e ON e.show_id = s.id
CROSS JOIN episode_files ef ON ef.episode_id = e.id
CROSS JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
LEFT JOIN watch_state w ON w.user_id = sqlc.arg(user_id) AND w.item_type = 'episode' AND w.item_id = e.id
    AND w.completed = 1
WHERE s.tmdb_id IN (sqlc.slice(tmdb_ids))
GROUP BY s.id
HAVING COUNT(DISTINCT e.id) = COUNT(DISTINCT w.item_id);
