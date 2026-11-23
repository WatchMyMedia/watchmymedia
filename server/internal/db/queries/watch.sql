-- name: UpsertWatchState :one
-- A position or watched mark made here. Either brings back a title the
-- person removed from Continue watching (hidden_at).
INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, completed)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    media_file_id = excluded.media_file_id,
    position_ms   = excluded.position_ms,
    duration_ms   = excluded.duration_ms,
    completed     = excluded.completed,
    hidden_at     = NULL,
    updated_at    = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
RETURNING *;

-- name: GetWatchState :one
SELECT * FROM watch_state WHERE user_id = ? AND item_type = ? AND item_id = ?;

-- name: DeleteWatchState :exec
DELETE FROM watch_state WHERE user_id = ? AND item_type = ? AND item_id = ?;

-- name: ListWatchStatesByType :many
SELECT * FROM watch_state WHERE user_id = ? AND item_type = ?;

-- name: ListShowWatchStates :many
SELECT w.* FROM watch_state w
JOIN episodes e ON e.id = w.item_id
WHERE w.user_id = ? AND w.item_type = 'episode' AND e.show_id = ?;

-- name: MarkSeasonWatched :exec
-- Marks the season's episodes that can have been watched: those that have
-- aired by today, and those with a file present. An episode TMDB lists
-- that hasn't aired and isn't here is left alone, so it is unwatched and
-- new when it comes.
INSERT INTO watch_state (user_id, item_type, item_id, position_ms, completed)
SELECT sqlc.arg(user_id), 'episode', e.id, 0, 1 FROM episodes e
WHERE e.season_id = sqlc.arg(season_id)
  AND ((e.air_date <> '' AND e.air_date <= sqlc.arg(today))
    OR EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files f ON f.id = ef.media_file_id
        WHERE ef.episode_id = e.id AND f.status = 'present'))
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    completed = 1, position_ms = 0, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: MarkShowWatched :exec
-- MarkSeasonWatched for every season of a show, specials included.
INSERT INTO watch_state (user_id, item_type, item_id, position_ms, completed)
SELECT sqlc.arg(user_id), 'episode', e.id, 0, 1 FROM episodes e
WHERE e.show_id = sqlc.arg(show_id)
  AND ((e.air_date <> '' AND e.air_date <= sqlc.arg(today))
    OR EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files f ON f.id = ef.media_file_id
        WHERE ef.episode_id = e.id AND f.status = 'present'))
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    completed = 1, position_ms = 0, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: UnmarkShowWatched :exec
DELETE FROM watch_state WHERE user_id = ? AND item_type = 'episode'
  AND item_id IN (SELECT id FROM episodes WHERE show_id = ?);

-- name: ListShowEpisodeIDs :many
SELECT id FROM episodes WHERE show_id = ? ORDER BY id;

-- name: UnmarkSeasonWatched :exec
DELETE FROM watch_state WHERE user_id = ? AND item_type = 'episode'
  AND item_id IN (SELECT id FROM episodes WHERE season_id = ?);

-- name: ListContinueWatching :many
SELECT * FROM watch_state
WHERE user_id = ? AND completed = 0 AND position_ms > 0 AND hidden_at IS NULL
ORDER BY updated_at DESC
LIMIT ?;

-- name: ListWatchedMovieIDs :many
SELECT item_id FROM watch_state WHERE user_id = ? AND item_type = 'movie' AND completed = 1;

-- name: ListShowWatchedCounts :many
-- Each show's watched episodes that have a file present, as
-- ListSeasonWatchedCounts counts them: a show card is watched when they
-- reach its episodes on disk, so an episode marked watched that never
-- came can't make up for a new one that did.
SELECT e.show_id, COUNT(DISTINCT e.id) AS watched
FROM watch_state w JOIN episodes e ON e.id = w.item_id
WHERE w.user_id = ? AND w.item_type = 'episode' AND w.completed = 1
  AND EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files f ON f.id = ef.media_file_id
      WHERE ef.episode_id = e.id AND f.status = 'present')
GROUP BY e.show_id;

-- name: NextUnwatchedEpisode :one
-- A show's up-next episode for one person, among episodes
-- with a present file outside Specials: the one in progress that was played most recently;
-- else the first unfinished one after the most recently finished episode (ties, as when a
-- whole season is marked at once, go to the latest in order); else the first unfinished one.
WITH eps AS (
    SELECT e.id AS episode_id, e.number AS episode_number, e.title, se.number AS season_number,
           MAX(f.id) AS file_id,
           COALESCE(w.completed, 0) AS completed,
           COALESCE(w.position_ms, 0) AS position_ms,
           w.updated_at
    FROM episodes e
    JOIN seasons se ON se.id = e.season_id
    JOIN episode_files ef ON ef.episode_id = e.id
    JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
    LEFT JOIN watch_state w ON w.item_type = 'episode' AND w.item_id = e.id AND w.user_id = sqlc.arg(user_id)
    WHERE e.show_id = sqlc.arg(show_id) AND se.number > 0
    GROUP BY e.id
),
last_done AS (
    -- From the show's episodes, not the person's whole history (CROSS JOIN pins the order).
    SELECT se.number AS season_number, e.number AS episode_number
    FROM episodes e
    CROSS JOIN watch_state w ON w.user_id = sqlc.arg(user_id) AND w.item_type = 'episode' AND w.item_id = e.id
    JOIN seasons se ON se.id = e.season_id
    WHERE e.show_id = sqlc.arg(show_id) AND w.completed = 1 AND se.number > 0
    ORDER BY w.updated_at DESC, se.number DESC, e.number DESC
    LIMIT 1
)
SELECT eps.episode_id, eps.episode_number, eps.title, eps.season_number, eps.file_id
FROM eps
LEFT JOIN last_done l ON 1 = 1
WHERE eps.completed = 0
ORDER BY
    CASE WHEN eps.position_ms > 0 THEN 0 ELSE 1 END,
    CASE WHEN eps.position_ms > 0 THEN eps.updated_at END DESC,
    CASE WHEN l.season_number IS NULL
           OR eps.season_number > l.season_number
           OR (eps.season_number = l.season_number AND eps.episode_number > l.episode_number)
         THEN 0 ELSE 1 END,
    eps.season_number, eps.episode_number
LIMIT 1;

-- name: ListSeasonWatchedCounts :many
SELECT e.season_id, COUNT(DISTINCT e.id) AS watched
FROM watch_state w
JOIN episodes e ON e.id = w.item_id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE w.user_id = ? AND w.item_type = 'episode' AND w.completed = 1 AND e.show_id = ?
GROUP BY e.season_id;

-- name: NextEpisodeAfterFile :one
-- The episode after a file's last episode in season order that has a file on
-- disk. Specials (season 0) lead only to the next special, and regular
-- episodes never lead to specials.
SELECT e.id AS episode_id, e.number AS episode_number, e.title, e.still_path, e.runtime_min, se.number AS season_number,
    s.id AS show_id, s.title AS show_title, MAX(f.id) AS file_id, MAX(f.duration_ms) AS duration_ms,
    (se.number > 0 AND e.number = (SELECT MAX(e3.number) FROM episodes e3 WHERE e3.season_id = e.season_id)) AS season_finale
FROM episodes e
JOIN seasons se ON se.id = e.season_id
JOIN shows s ON s.id = e.show_id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
JOIN (
    SELECT e2.show_id, se2.number AS season_number, e2.number AS episode_number
    FROM episode_files ef2
    JOIN episodes e2 ON e2.id = ef2.episode_id
    JOIN seasons se2 ON se2.id = e2.season_id
    WHERE ef2.media_file_id = sqlc.arg(file_id)
    ORDER BY se2.number DESC, e2.number DESC
    LIMIT 1
) cur ON cur.show_id = e.show_id
WHERE (se.number > cur.season_number OR (se.number = cur.season_number AND e.number > cur.episode_number))
  AND (cur.season_number > 0 OR se.number = 0)
GROUP BY e.id
ORDER BY se.number, e.number
LIMIT 1;

-- name: ListContinueWatchingItems :many
-- Continue watching, newest first: the user's unfinished movies and
-- episodes whose file is still present and which they haven't removed
-- from the row, with what a card shows.
SELECT w.item_type, w.item_id, w.media_file_id, w.position_ms, w.duration_ms, w.updated_at,
    m.title AS movie_title, m.year AS movie_year, m.poster_path AS movie_poster_path, m.backdrop_path AS movie_backdrop_path,
    m.content_rating AS movie_content_rating, m.overview AS movie_overview,
    e.number AS episode_number, e.title AS episode_title, e.still_path AS episode_still_path, e.overview AS episode_overview,
    se.number AS season_number,
    s.id AS show_id, s.title AS show_title, s.poster_path AS show_poster_path, s.backdrop_path AS show_backdrop_path,
    s.content_rating AS show_content_rating
FROM watch_state w INDEXED BY watch_state_user_updated_idx
CROSS JOIN media_files f ON f.id = w.media_file_id
LEFT JOIN movies m ON w.item_type = 'movie' AND m.id = w.item_id
LEFT JOIN episodes e ON w.item_type = 'episode' AND e.id = w.item_id
LEFT JOIN seasons se ON se.id = e.season_id
LEFT JOIN shows s ON s.id = e.show_id
WHERE w.user_id = ? AND w.completed = 0 AND w.position_ms > 0 AND w.hidden_at IS NULL AND f.status = 'present'
  AND (m.id IS NOT NULL OR s.id IS NOT NULL AND se.id IS NOT NULL)
ORDER BY w.updated_at DESC
LIMIT ?;

-- name: ListShowsWithNewEpisodes :many
-- Shows with an episode whose present file arrived since the cutoff and
-- which the user has not finished: what keeps a show card's New badge.
-- Each library's added_at index is walked from
-- the cutoff, so only the window's files are read, not every episode.
-- Episode files are reached through episode_files, which leaves trailer
-- files out. Only the show id and how many episodes are needed, so the
-- watch_state lookup is a NOT EXISTS on its primary key.
SELECT e.show_id, COUNT(DISTINCT e.id) AS episodes
FROM libraries l
CROSS JOIN media_files f INDEXED BY media_files_added_idx
    ON f.library_id = l.id AND f.status = 'present' AND f.added_at >= sqlc.arg(since)
CROSS JOIN episode_files ef ON ef.media_file_id = f.id
CROSS JOIN episodes e ON e.id = ef.episode_id
WHERE l.type = 'tv'
  AND NOT EXISTS (SELECT 1 FROM watch_state w
      WHERE w.user_id = sqlc.arg(user_id) AND w.item_type = 'episode' AND w.item_id = e.id AND w.completed = 1)
GROUP BY e.show_id;

-- name: ListWatchedEpisodeIDs :many
-- Which of some episodes the user has finished.
SELECT item_id FROM watch_state
WHERE user_id = sqlc.arg(user_id) AND item_type = 'episode' AND completed = 1 AND item_id IN (sqlc.slice(ids));

-- name: NextWatchRevision :one
-- The revision for a change to watch state. As a transaction's first
-- statement it also takes SQLite's write lock, so nothing the change reads
-- afterwards can go stale before it commits.
UPDATE watch_revision SET last = last + 1 WHERE singleton = 1 RETURNING last;

-- name: GetWatchGuard :one
SELECT * FROM watch_guards WHERE user_id = ? AND item_type = ? AND item_id = ?;

-- name: ClaimWatchGuard :exec
-- The user's own choice decides the item from now on, whether or not it
-- leaves watch state.
INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision)
VALUES (?, ?, ?, 'local', ?)
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    owner = 'local', import_run = NULL, revision = excluded.revision,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: ClaimSeasonWatchGuards :exec
INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision)
SELECT sqlc.arg(user_id), 'episode', id, 'local', sqlc.arg(revision) FROM episodes WHERE season_id = sqlc.arg(season_id)
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    owner = 'local', import_run = NULL, revision = excluded.revision,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: ClaimShowWatchGuards :exec
INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision)
SELECT sqlc.arg(user_id), 'episode', id, 'local', sqlc.arg(revision) FROM episodes WHERE show_id = sqlc.arg(show_id)
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    owner = 'local', import_run = NULL, revision = excluded.revision,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: ClaimMarkedSeasonWatchGuards :exec
-- The guards for what MarkSeasonWatched marks, and only those: an episode
-- it leaves alone is not the person's choice, so an import may mark it.
INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision)
SELECT sqlc.arg(user_id), 'episode', e.id, 'local', sqlc.arg(revision) FROM episodes e
WHERE e.season_id = sqlc.arg(season_id)
  AND ((e.air_date <> '' AND e.air_date <= sqlc.arg(today))
    OR EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files f ON f.id = ef.media_file_id
        WHERE ef.episode_id = e.id AND f.status = 'present'))
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    owner = 'local', import_run = NULL, revision = excluded.revision,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: ClaimMarkedShowWatchGuards :exec
-- The guards for what MarkShowWatched marks.
INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision)
SELECT sqlc.arg(user_id), 'episode', e.id, 'local', sqlc.arg(revision) FROM episodes e
WHERE e.show_id = sqlc.arg(show_id)
  AND ((e.air_date <> '' AND e.air_date <= sqlc.arg(today))
    OR EXISTS (SELECT 1 FROM episode_files ef CROSS JOIN media_files f ON f.id = ef.media_file_id
        WHERE ef.episode_id = e.id AND f.status = 'present'))
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    owner = 'local', import_run = NULL, revision = excluded.revision,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: InsertImportedWatch :exec
-- An imported watched mark: no file, position or duration, as marking
-- an item watched by hand leaves.
INSERT INTO watch_state (user_id, item_type, item_id, position_ms, completed) VALUES (?, ?, ?, 0, 1);

-- name: InsertImportWatchGuard :exec
INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision, import_run)
VALUES (?, ?, ?, 'import', ?, ?);

-- name: ListItemWatchUsers :many
-- Everyone with watch state or a guard on an item.
SELECT user_id FROM watch_state WHERE watch_state.item_type = sqlc.arg(item_type) AND watch_state.item_id = sqlc.arg(item_id)
UNION
SELECT user_id FROM watch_guards WHERE watch_guards.item_type = sqlc.arg(item_type) AND watch_guards.item_id = sqlc.arg(item_id)
ORDER BY user_id;

-- name: ReplaceWatchState :exec
-- Puts a copy of state on another item, keeping when it was saved and
-- whether it was removed from Continue watching.
INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, completed, updated_at, hidden_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    media_file_id = excluded.media_file_id,
    position_ms   = excluded.position_ms,
    duration_ms   = excluded.duration_ms,
    completed     = excluded.completed,
    updated_at    = excluded.updated_at,
    hidden_at     = excluded.hidden_at;

-- name: DeleteWatchGuardsForItem :exec
DELETE FROM watch_guards WHERE item_type = ? AND item_id = ?;

-- name: GetEpisodeIdentity :one
-- What identifies an episode beyond its ID: its show's library and TMDB ID,
-- and its season and episode numbers.
SELECT sh.library_id, sh.tmdb_id, se.number AS season_number, e.number AS episode_number
FROM episodes e
JOIN seasons se ON se.id = e.season_id
JOIN shows sh ON sh.id = e.show_id
WHERE e.id = ?;

-- name: UpsertWatchStateAt :exec
-- A change made offline, saved with when it happened rather than now. As
-- a play here does, it brings back a title removed from Continue watching.
INSERT INTO watch_state (user_id, item_type, item_id, media_file_id, position_ms, duration_ms, completed, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    media_file_id = excluded.media_file_id,
    position_ms   = excluded.position_ms,
    duration_ms   = excluded.duration_ms,
    completed     = excluded.completed,
    hidden_at     = NULL,
    updated_at    = excluded.updated_at;

-- name: ClaimWatchGuardAt :exec
-- ClaimWatchGuard for a change made offline: the guard keeps when it
-- happened, which the next offline change is compared with.
INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision, updated_at)
VALUES (?, ?, ?, 'local', ?, ?)
ON CONFLICT (user_id, item_type, item_id) DO UPDATE SET
    owner = 'local', import_run = NULL, revision = excluded.revision,
    updated_at = excluded.updated_at;

-- name: HideFromContinueWatching :execrows
-- Takes an item off the person's Continue watching and keeps its place.
-- Nothing changes when it has no state or is off the row already.
UPDATE watch_state SET hidden_at = sqlc.arg(hidden_at)
WHERE user_id = sqlc.arg(user_id) AND item_type = sqlc.arg(item_type) AND item_id = sqlc.arg(item_id)
  AND hidden_at IS NULL;

-- name: UnhideFromContinueWatching :execrows
-- Puts an item the person took off Continue watching back on it.
UPDATE watch_state SET hidden_at = NULL
WHERE user_id = ? AND item_type = ? AND item_id = ? AND hidden_at IS NOT NULL;
