-- name: ListLiveSources :many
SELECT * FROM live_tv_sources ORDER BY name, id;
-- name: GetLiveSource :one
SELECT * FROM live_tv_sources WHERE id = ?;
-- name: CreateLiveSource :one
INSERT INTO live_tv_sources (id, device_id, name, address, model, firmware, tuner_count)
VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *;
-- name: UpdateLiveSource :exec
UPDATE live_tv_sources SET name = ?, address = ?, enabled = ? WHERE id = ?;
-- name: RefreshedLiveSource :exec
UPDATE live_tv_sources SET model = ?, firmware = ?, tuner_count = ?, refreshed_at = ?, last_error = '' WHERE id = ?;
-- name: FailedLiveSource :exec
UPDATE live_tv_sources SET last_error = ? WHERE id = ?;
-- name: DeleteLiveSource :exec
DELETE FROM live_tv_sources WHERE id = ?;
-- name: ListLiveChannels :many
SELECT c.* FROM live_tv_channels c ORDER BY major, minor, id;
-- name: GetLiveChannel :one
SELECT * FROM live_tv_channels WHERE id = ?;
-- name: UnavailableLiveChannels :exec
UPDATE live_tv_channels SET available = 0 WHERE source_id = ?;
-- name: UpsertLiveChannel :exec
INSERT INTO live_tv_channels (id, source_id, provider_key, number, name, stream_path, protected, major, minor, last_seen_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(source_id, provider_key) DO UPDATE SET number = excluded.number, name = excluded.name,
stream_path = excluded.stream_path, protected = excluded.protected, major = excluded.major,
minor = excluded.minor, available = 1, last_seen_at = excluded.last_seen_at;
-- name: EnableLiveChannel :exec
UPDATE live_tv_channels SET enabled = ? WHERE id = ?;
