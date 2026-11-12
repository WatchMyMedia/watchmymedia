-- name: GetSetting :one
SELECT value FROM settings WHERE key = ?;

-- name: ListSettings :many
SELECT * FROM settings ORDER BY key;

-- name: SetSetting :exec
INSERT INTO settings (key, value) VALUES (?, ?)
ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: AddSetting :exec
-- Stores a value unless the key already has one.
INSERT INTO settings (key, value) VALUES (?, ?)
ON CONFLICT (key) DO NOTHING;

-- name: DeleteSetting :exec
DELETE FROM settings WHERE key = ?;

-- name: DeleteLibrary :exec
DELETE FROM libraries WHERE id = ?;

-- name: UpdateLibrary :one
UPDATE libraries SET name = ?, path = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ? RETURNING *;

-- name: CreateLibrary :one
INSERT INTO libraries (type, name, path) VALUES (?, ?, ?) RETURNING *;
