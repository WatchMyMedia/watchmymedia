-- name: CreateOfflineDownload :one
INSERT INTO offline_downloads (
    public_id, user_id, device_key, device_name, media_file_id, item_type, item_id, item,
    file_size, file_mtime, mode, quality, request, size_bytes
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
RETURNING *;

-- name: GetOfflineDownload :one
SELECT * FROM offline_downloads WHERE public_id = ?;

-- name: DeleteOfflineDownload :exec
DELETE FROM offline_downloads WHERE id = ?;

-- name: DeleteDeviceFileDownloads :exec
-- A device downloading a file again replaces what it had of it.
DELETE FROM offline_downloads WHERE user_id = ? AND device_key = ? AND media_file_id = ?;

-- name: ListDeviceOfflineDownloads :many
SELECT * FROM offline_downloads WHERE user_id = ? AND device_key = ? ORDER BY id DESC;

-- name: ListUserOfflineDownloads :many
SELECT * FROM offline_downloads WHERE user_id = ? ORDER BY id DESC;

-- name: ListAllOfflineDownloads :many
-- Everyone's downloads for Activity, Downloads, newest first, with who
-- made and who revoked each.
SELECT sqlc.embed(o), u.name AS user_name, u.is_admin AS user_is_admin, COALESCE(r.name, '') AS revoked_by_name
FROM offline_downloads o
JOIN users u ON u.id = o.user_id
LEFT JOIN users r ON r.id = o.revoked_by_user
ORDER BY o.id DESC;

-- name: RevokeOfflineDownload :exec
-- revoked_by is admin, access or user; revoked_by_user the admin's account.
UPDATE offline_downloads
SET state = 'revoked', revoked_by = ?, revoked_by_user = ?,
    revoked_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ? AND state <> 'revoked';

-- name: RevokeUserOfflineDownloads :execrows
-- Everything a person downloaded, once they may no longer download.
UPDATE offline_downloads
SET state = 'revoked', revoked_by = 'access', revoked_by_user = NULL,
    revoked_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE user_id = ? AND state <> 'revoked';

-- name: SetOfflineDeviceState :exec
-- What the device says it holds. completed_at is when it first said downloaded.
UPDATE offline_downloads
SET device_state = sqlc.arg(device_state),
    size_bytes = CASE WHEN CAST(sqlc.arg(size_bytes) AS INTEGER) > 0 THEN CAST(sqlc.arg(size_bytes) AS INTEGER) ELSE size_bytes END,
    completed_at = CASE WHEN sqlc.arg(device_state) = 'downloaded' AND completed_at IS NULL
        THEN strftime('%Y-%m-%dT%H:%M:%fZ', 'now') ELSE completed_at END,
    synced_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = sqlc.arg(id);

-- name: TouchDeviceOfflineDownloads :exec
-- The device synced: every download it has here was heard from.
UPDATE offline_downloads SET synced_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE user_id = ? AND device_key = ?;

-- name: MarkOfflineDownloading :exec
-- The first fetch of a queued download.
UPDATE offline_downloads SET device_state = 'downloading' WHERE id = ? AND device_state = 'queued';

-- name: AddOfflineBytes :exec
UPDATE offline_downloads SET bytes_sent = bytes_sent + ? WHERE id = ?;

-- name: SetOfflineInitHash :exec
-- The first session's init segment, or a remade one's that differs (state
-- restart: the device starts over).
UPDATE offline_downloads SET init_hash = ?, state = ? WHERE id = ? AND state <> 'revoked';

-- name: RestartOfflineDownload :exec
-- The device started over after a restart.
UPDATE offline_downloads SET state = 'ready' WHERE id = ? AND state = 'restart';

-- name: ListPresentMovieFileIDs :many
-- A movie's files on disk, for a download whose file went: the newest first.
SELECT id FROM media_files WHERE movie_id = ? AND status = 'present' ORDER BY id DESC;

-- name: ListPresentEpisodeFileIDs :many
-- An episode's files on disk, the newest first.
SELECT f.id FROM episode_files ef
CROSS JOIN media_files f ON f.id = ef.media_file_id
WHERE ef.episode_id = ? AND f.status = 'present'
ORDER BY f.id DESC;
