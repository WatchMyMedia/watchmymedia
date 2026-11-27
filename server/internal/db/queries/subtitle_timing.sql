-- name: ListSubtitleTimings :many
-- A person's own timings and the shared ones for a file as it is now: rows
-- saved for another size of the file are left out.
SELECT user_id, track_key, timing_ms FROM subtitle_timing
WHERE file_id = sqlc.arg(file_id) AND file_size = sqlc.arg(file_size)
    AND (user_id = sqlc.arg(user_id) OR user_id IS NULL);

-- name: DeleteSubtitleTiming :exec
-- A person's own timing for a track, whatever size it was saved for.
DELETE FROM subtitle_timing WHERE file_id = ? AND track_key = ? AND user_id = ?;

-- name: DeleteSharedSubtitleTiming :exec
-- The timing saved for everyone for a track.
DELETE FROM subtitle_timing WHERE file_id = ? AND track_key = ? AND user_id IS NULL;

-- name: InsertSubtitleTiming :exec
-- user_id NULL saves it for everyone (never 0). Delete the track's row
-- first: there is one row a person and one shared row.
INSERT INTO subtitle_timing (user_id, file_id, track_key, timing_ms, file_size) VALUES (?, ?, ?, ?, ?);
