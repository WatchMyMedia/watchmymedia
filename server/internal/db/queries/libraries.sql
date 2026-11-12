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
