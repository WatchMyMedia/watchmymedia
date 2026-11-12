-- name: ListFileSegments :many
SELECT * FROM media_segments WHERE media_file_id = ? ORDER BY start_ms, id;

-- name: InsertSegment :exec
INSERT INTO media_segments (media_file_id, kind, start_ms, end_ms, final, source, confidence)
VALUES (?, ?, ?, ?, ?, ?, ?);

-- name: DeleteActiveSegmentsFromSource :exec
-- Clears one detector's segments before it writes new ones; suppressed rows
-- (deleted by a person) stay so the detector cannot bring them back.
DELETE FROM media_segments WHERE media_file_id = ? AND source = ? AND state = 'active';

-- name: GetSegmentAnalysis :one
SELECT * FROM segment_analyses WHERE media_file_id = ? AND source = ?;

-- name: UpsertSegmentAnalysis :exec
INSERT INTO segment_analyses (media_file_id, source, algo_version, file_mtime, file_size)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (media_file_id, source) DO UPDATE SET
    algo_version = excluded.algo_version,
    file_mtime   = excluded.file_mtime,
    file_size    = excluded.file_size,
    analyzed_at  = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: GetFingerprint :one
SELECT * FROM audio_fingerprints WHERE media_file_id = ? AND region = ?;

-- name: UpsertFingerprint :exec
INSERT INTO audio_fingerprints (media_file_id, region, algo_version, file_mtime, file_size, start_ms, length_ms, data, silences)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (media_file_id, region) DO UPDATE SET
    algo_version = excluded.algo_version,
    file_mtime   = excluded.file_mtime,
    file_size    = excluded.file_size,
    start_ms     = excluded.start_ms,
    length_ms    = excluded.length_ms,
    data         = excluded.data,
    silences     = excluded.silences,
    created_at   = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: ListSeasonsToFingerprint :many
-- Regular seasons with a file that has no fingerprint analysis for its
-- current version. Specials rarely share an intro, so they are left out.
SELECT DISTINCT se.id
FROM seasons se
JOIN episodes e ON e.season_id = se.id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
LEFT JOIN segment_analyses a ON a.media_file_id = f.id AND a.source = 'fingerprint'
WHERE se.number > 0
  AND (a.media_file_id IS NULL OR a.algo_version != sqlc.arg(algo_version) OR a.file_mtime != f.mtime OR a.file_size != f.size)
ORDER BY se.id;

-- name: ListSeasonFilesForFingerprint :many
-- A season's files in airing order; a file holding two episodes appears
-- once, at its first.
SELECT f.id, f.path, f.mtime, f.size, f.probe, MIN(e.number) AS episode_number
FROM episodes e
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE e.season_id = ?
GROUP BY f.id
ORDER BY episode_number, f.id;

-- name: DeleteManualSegments :exec
DELETE FROM media_segments WHERE media_file_id = ? AND source = 'manual';

-- name: SetSegmentState :exec
UPDATE media_segments SET state = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ? AND media_file_id = ? AND source != 'manual';
