-- name: CountUsers :one
SELECT COUNT(*) FROM users;

-- name: CreateUser :one
INSERT INTO users (id, name, password_hash, is_admin)
VALUES ((SELECT last_id + 1 FROM account_sequence WHERE singleton = 1), ?, ?, ?) RETURNING *;

-- name: GetUser :one
SELECT * FROM users WHERE id = ?;

-- name: GetUserByName :one
SELECT * FROM users WHERE name = ?;

-- name: ListUsers :many
SELECT * FROM users ORDER BY name COLLATE NOCASE;

-- name: DeleteUser :exec
DELETE FROM users WHERE id = ?;

-- name: UpdateUserPassword :exec
UPDATE users SET password_hash = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?;

-- name: CreateSession :exec
INSERT INTO sessions (token_hash, public_id, user_id, user_agent, expires_at, kind, device_key, name, product, platform, version, last_ip, model)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

-- name: GetSession :one
SELECT s.token_hash, s.public_id, s.user_id, s.kind, s.last_seen_at, s.last_ip, s.expires_at, u.name, u.is_admin
FROM sessions s JOIN users u ON u.id = s.user_id
WHERE s.token_hash = ? AND s.expires_at > ?;

-- name: TouchSession :exec
UPDATE sessions SET last_seen_at = ?, expires_at = ?, last_ip = ? WHERE token_hash = ?;

-- name: ListSessions :many
SELECT s.token_hash, s.public_id, s.user_id, s.user_agent, s.created_at, s.last_seen_at, s.expires_at,
       s.kind, s.device_key, s.name, s.product, s.platform, s.version, s.last_ip, s.model, u.name AS user_name
FROM sessions s JOIN users u ON u.id = s.user_id
WHERE s.expires_at > ?
ORDER BY s.last_seen_at DESC;

-- name: ListUserSessions :many
SELECT s.token_hash, s.public_id, s.user_id, s.user_agent, s.created_at, s.last_seen_at, s.expires_at,
       s.kind, s.device_key, s.name, s.product, s.platform, s.version, s.last_ip, s.model, u.name AS user_name
FROM sessions s JOIN users u ON u.id = s.user_id
WHERE s.user_id = ? AND s.expires_at > ?
ORDER BY s.last_seen_at DESC;

-- name: GetSessionOwner :one
SELECT user_id FROM sessions WHERE public_id = ?;

-- name: DeleteSessionByPublicID :exec
DELETE FROM sessions WHERE public_id = ?;

-- name: DeleteUserSessionsExcept :exec
DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?;

-- name: DeleteSession :exec
DELETE FROM sessions WHERE token_hash = ?;

-- name: DeleteUserSessions :exec
DELETE FROM sessions WHERE user_id = ?;

-- name: DeleteExpiredSessions :exec
DELETE FROM sessions WHERE expires_at <= ?;

-- name: SetUserRecordHistory :exec
UPDATE users SET record_history = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?;

-- name: SetUserSeerrUser :exec
UPDATE users SET seerr_user_id = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ?;

-- name: GetActiveSessionByID :one
SELECT s.public_id, s.user_id, u.name, u.is_admin
FROM sessions s JOIN users u ON u.id = s.user_id
WHERE s.public_id = ? AND s.expires_at > ?;
