-- name: ListLibraries :many
SELECT * FROM libraries ORDER BY type, name;

-- name: GetLibrary :one
SELECT * FROM libraries WHERE id = ?;

-- name: UpsertLibrary :one
INSERT INTO libraries (type, name, path)
VALUES (?, ?, ?)
ON CONFLICT (path) DO UPDATE SET
    type = excluded.type,
    name = excluded.name,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
RETURNING *;

-- name: HasLibraries :one
SELECT EXISTS (SELECT 1 FROM libraries);

-- A full scan that finished clears the last scan's error.
-- name: SetLibraryScannedAt :exec
UPDATE libraries
SET last_scanned_at = ?, last_scan_error = NULL, last_scan_error_at = NULL,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ?;

-- name: SetLibraryScanError :exec
UPDATE libraries
SET last_scan_error = ?, last_scan_error_at = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ?;

-- name: CountLibraryRemoval :one
-- What removing a library takes with it: its movies, shows and episodes,
-- and its video files, present or missing (trailer files go too but are
-- not counted, as the library's own counts leave them out). Each count
-- reads an index that starts with library_id or show_id, never the rows.
SELECT
    (SELECT COUNT(*) FROM movies WHERE movies.library_id = sqlc.arg(library_id)) AS movies,
    (SELECT COUNT(*) FROM shows WHERE shows.library_id = sqlc.arg(library_id)) AS shows,
    (SELECT COUNT(*) FROM shows AS s CROSS JOIN episodes AS e ON e.show_id = s.id
        WHERE s.library_id = sqlc.arg(library_id)) AS episodes,
    (SELECT COUNT(*) FROM media_files INDEXED BY media_files_library_status_idx
        WHERE media_files.library_id = sqlc.arg(library_id) AND media_files.extra_kind IS NULL) AS files;

-- name: CountLibraryWatchState :one
-- The watched marks and resume points removing a library deletes (by the
-- movies_delete_watch and episodes_delete_watch triggers), and how many
-- people they belong to. Each item seeks watch_state_item_idx.
SELECT
    CAST(COALESCE(SUM(w.completed != 0), 0) AS INTEGER) AS watched,
    CAST(COALESCE(SUM(w.completed = 0), 0) AS INTEGER) AS in_progress,
    COUNT(DISTINCT w.user_id) AS people
FROM (
    SELECT ws.user_id, ws.completed
    FROM movies AS m CROSS JOIN watch_state AS ws INDEXED BY watch_state_item_idx
        ON ws.item_type = 'movie' AND ws.item_id = m.id
    WHERE m.library_id = sqlc.arg(library_id)
    UNION ALL
    SELECT ws.user_id, ws.completed
    FROM shows AS s CROSS JOIN episodes AS e ON e.show_id = s.id
        CROSS JOIN watch_state AS ws INDEXED BY watch_state_item_idx
        ON ws.item_type = 'episode' AND ws.item_id = e.id
    WHERE s.library_id = sqlc.arg(library_id)
) AS w;
