-- name: ListIntegrations :many
SELECT * FROM integrations ORDER BY kind, name, id;

-- name: GetIntegration :one
SELECT * FROM integrations WHERE id = ?;

-- name: CreateIntegration :one
INSERT INTO integrations (kind, name, url, api_key, webhook_token, path_map, targets)
VALUES (?, ?, ?, ?, ?, ?, ?)
RETURNING *;

-- name: UpdateIntegration :one
UPDATE integrations
SET name = ?, url = ?, api_key = ?, path_map = ?, targets = ?,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ?
RETURNING *;

-- name: DeleteIntegration :exec
DELETE FROM integrations WHERE id = ?;
