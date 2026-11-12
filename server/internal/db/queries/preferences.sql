-- name: ListUserPreferences :many
SELECT key, value FROM user_preferences WHERE user_id = ?;

-- name: UpsertUserPreference :exec
INSERT INTO user_preferences (user_id, key, value) VALUES (?, ?, ?)
ON CONFLICT (user_id, key) DO UPDATE SET value = excluded.value,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');
