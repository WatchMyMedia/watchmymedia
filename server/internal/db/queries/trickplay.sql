-- name: ListFilesNeedingTrickplay :many
-- Present files of a minute or more with video whose seek thumbnails are
-- missing or were made for another version of the file or of the
-- algorithm, or made with less tone mapping than ffmpeg now has (cpu:
-- zscale, for HDR made without it; dovi: tonemapx, for Dolby Vision 5 made
-- from its base layer), newest first, a page at a time after (before_seen,
-- before_id). Failed files have a row for their version, so they come back
-- only when the file or the algorithm changes. The walk reads the
-- media_files_trickplay_idx index, not the rows with their probes. Trailers
-- get no thumbnails.
SELECT f.id, f.path, f.size, f.mtime, f.duration_ms, f.first_seen_at, f.hdr, f.dv_profile
FROM media_files f INDEXED BY media_files_trickplay_idx
LEFT JOIN trickplay t ON t.media_file_id = f.id
WHERE f.status = 'present' AND f.extra_kind IS NULL AND f.duration_ms >= 60000 AND f.video_codec IS NOT NULL
  AND (t.media_file_id IS NULL OR t.algo_version != sqlc.arg(algo_version) OR t.file_size != f.size OR t.file_mtime != f.mtime
       OR (t.tone_map = 'plain' AND CAST(sqlc.arg(cpu) AS BOOLEAN)) OR (t.tone_map = 'dvbase' AND CAST(sqlc.arg(dovi) AS BOOLEAN)))
  AND (f.first_seen_at < sqlc.arg(before_seen) OR (f.first_seen_at = sqlc.arg(before_seen) AND f.id < sqlc.arg(before_id)))
ORDER BY f.first_seen_at DESC, f.id DESC
LIMIT sqlc.arg(page);

-- name: CountTrickplay :one
-- How far the thumbnails have got: files that get them, and how many of
-- those are done or could not be made for their current version. Tiles
-- the work list would make again with better tone mapping are not done.
SELECT
    COUNT(*) AS files,
    CAST(COALESCE(SUM(t.status = 'done' AND NOT ((t.tone_map = 'plain' AND CAST(sqlc.arg(cpu) AS BOOLEAN)) OR (t.tone_map = 'dvbase' AND CAST(sqlc.arg(dovi) AS BOOLEAN)))), 0) AS INTEGER) AS done,
    CAST(COALESCE(SUM(t.status = 'failed'), 0) AS INTEGER) AS failed
FROM media_files f INDEXED BY media_files_trickplay_idx
LEFT JOIN trickplay t ON t.media_file_id = f.id
    AND t.algo_version = sqlc.arg(algo_version) AND t.file_size = f.size AND t.file_mtime = f.mtime
WHERE f.status = 'present' AND f.extra_kind IS NULL AND f.duration_ms >= 60000 AND f.video_codec IS NOT NULL;

-- name: TrickplayBytes :one
-- What every file's thumbnails take on disk.
SELECT CAST(COALESCE(SUM(bytes), 0) AS INTEGER) FROM trickplay;

-- name: GetTrickplay :one
SELECT * FROM trickplay WHERE media_file_id = ?;

-- name: UpsertTrickplay :exec
INSERT INTO trickplay (media_file_id, version, algo_version, file_size, file_mtime, tone_map, status,
    interval_ms, width, height, columns, rows, tiles, sheets, bytes, error)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (media_file_id) DO UPDATE SET
    version      = excluded.version,
    algo_version = excluded.algo_version,
    file_size    = excluded.file_size,
    file_mtime   = excluded.file_mtime,
    tone_map     = excluded.tone_map,
    status       = excluded.status,
    interval_ms  = excluded.interval_ms,
    width        = excluded.width,
    height       = excluded.height,
    columns      = excluded.columns,
    rows         = excluded.rows,
    tiles        = excluded.tiles,
    sheets       = excluded.sheets,
    bytes        = excluded.bytes,
    error        = excluded.error,
    made_at      = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: DeleteTrickplay :exec
DELETE FROM trickplay WHERE media_file_id = ?;

-- name: DeleteAllTrickplay :exec
DELETE FROM trickplay;

-- name: ListTrickplayForSweep :many
-- Every file's thumbnails with whether the file is there, for the sweep:
-- a missing file's status changed when it went missing (updated_at).
SELECT t.media_file_id, t.version, t.status, f.status AS file_status, f.updated_at AS file_updated_at
FROM trickplay t
JOIN media_files f ON f.id = t.media_file_id;
