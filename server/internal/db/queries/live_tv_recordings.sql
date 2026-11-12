-- name: PutDVRRecording :exec
-- A rule or person deleted while a recording runs leaves the writer holding
-- the old id: store NULL for it, as ON DELETE SET NULL did, rather than
-- failing every later write with a foreign key error.
INSERT INTO live_tv_recordings(id,rule_id,user_id,source_id,channel_id,number,programme_id,series_key,start_at,end_at,priority,state,section,path,media_file_id,data,created_at,updated_at)
VALUES (sqlc.arg(id),
 (SELECT rr.id FROM live_tv_recording_rules rr WHERE rr.id=sqlc.narg(rule_id)),
 (SELECT u.id FROM users u WHERE u.id=sqlc.narg(user_id)),
 sqlc.arg(source_id),sqlc.arg(channel_id),sqlc.arg(number),sqlc.arg(programme_id),sqlc.arg(series_key),sqlc.arg(start_at),sqlc.arg(end_at),sqlc.arg(priority),sqlc.arg(state),sqlc.arg(section),sqlc.narg(path),sqlc.narg(media_file_id),sqlc.arg(data),sqlc.arg(created_at),sqlc.arg(updated_at))
ON CONFLICT(id) DO UPDATE SET rule_id=excluded.rule_id,user_id=excluded.user_id,source_id=excluded.source_id,channel_id=excluded.channel_id,number=excluded.number,programme_id=excluded.programme_id,series_key=excluded.series_key,start_at=excluded.start_at,end_at=excluded.end_at,priority=excluded.priority,state=excluded.state,section=excluded.section,path=excluded.path,media_file_id=excluded.media_file_id,data=excluded.data,updated_at=excluded.updated_at;

-- name: GetDVRRecording :one
SELECT * FROM live_tv_recordings WHERE id=?;

-- name: GetDVRRecordingByPath :one
SELECT * FROM live_tv_recordings WHERE path=?;

-- name: GetDVRRecordingByFile :one
SELECT * FROM live_tv_recordings WHERE media_file_id=?;

-- name: ListDVRRecordingSection :many
SELECT * FROM live_tv_recordings INDEXED BY live_tv_recording_section
WHERE section=sqlc.arg(section) AND start_at >= sqlc.arg(after_at)
 AND (start_at > sqlc.arg(after_at) OR id > CAST(sqlc.arg(after_id) AS TEXT))
ORDER BY start_at,id LIMIT sqlc.arg(row_limit);

-- name: ListDVRRecordedSection :many
-- Recorded lists the newest first: a keyset on (start_at, id) descending.
SELECT * FROM live_tv_recordings INDEXED BY live_tv_recording_section
WHERE section='recorded' AND start_at <= sqlc.arg(before_at)
 AND (start_at < sqlc.arg(before_at) OR id < CAST(sqlc.arg(before_id) AS TEXT))
ORDER BY start_at DESC,id DESC LIMIT sqlc.arg(row_limit);

-- name: ListDVRPending :many
SELECT * FROM live_tv_recordings WHERE state IN ('scheduled','conflict','recording','gone') ORDER BY start_at,id;

-- name: ListDVRManualCandidates :many
SELECT * FROM live_tv_recordings INDEXED BY live_tv_recording_manual
WHERE user_id=? AND channel_id=? AND start_at=? AND end_at=? AND programme_id=''
 AND state IN ('scheduled','conflict','recording','gone');

-- name: ListDVRByRule :many
SELECT * FROM live_tv_recordings WHERE rule_id=? ORDER BY start_at,id;

-- name: ListDVRBySeries :many
SELECT * FROM live_tv_recordings WHERE series_key=? ORDER BY start_at,id;

-- name: ListDVRUnlinked :many
SELECT * FROM live_tv_recordings WHERE media_file_id IS NULL AND state IN ('done','partial') AND path IS NOT NULL;

-- name: ListDVRProgrammeMarks :many
SELECT * FROM live_tv_recordings INDEXED BY live_tv_recording_programme
WHERE programme_id IN (sqlc.slice(programme_ids));

-- name: PutDVRRule :exec
INSERT INTO live_tv_recording_rules(id,user_id,kind,series_key,data,created_at) VALUES (?,?,?,?,?,?)
ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id,data=excluded.data;

-- name: GetDVRRule :one
SELECT * FROM live_tv_recording_rules WHERE id=?;

-- name: ListDVRRules :many
SELECT * FROM live_tv_recording_rules ORDER BY created_at,id;

-- name: DeleteDVRRule :exec
DELETE FROM live_tv_recording_rules WHERE id=?;

-- name: ListDVRSeriesProgrammes :many
SELECT * FROM live_tv_programmes WHERE series_key=? AND end_at>? ORDER BY start_at,channel_id;

-- name: PutDVRProgress :exec
INSERT INTO live_tv_recording_progress(recording_id,user_id,position_ms,duration_ms,updated_at) VALUES (?,?,?,?,?)
ON CONFLICT(recording_id,user_id) DO UPDATE SET position_ms=excluded.position_ms,duration_ms=excluded.duration_ms,updated_at=excluded.updated_at;

-- name: GetDVRProgress :one
SELECT * FROM live_tv_recording_progress WHERE recording_id=? AND user_id=?;

-- name: ListDVRProgress :many
SELECT * FROM live_tv_recording_progress WHERE recording_id=?;

-- name: DeleteDVRProgress :exec
DELETE FROM live_tv_recording_progress WHERE recording_id=?;

-- name: DeleteDVRProgressVersion :exec
-- Only retire the exact checkpoint transferred; a later heartbeat survives.
DELETE FROM live_tv_recording_progress WHERE recording_id=? AND user_id=? AND updated_at=? AND position_ms=? AND duration_ms=?;

-- name: ListDVRProgressRecordingIDs :many
SELECT DISTINCT p.recording_id FROM live_tv_recording_progress p
CROSS JOIN live_tv_recordings r ON r.id=p.recording_id AND r.media_file_id IS NOT NULL
ORDER BY p.recording_id LIMIT 100;

-- name: DVRFileAtPath :one
SELECT id FROM media_files WHERE path=? AND status='present';

-- name: DVRHasEpisode :one
SELECT EXISTS(SELECT 1 FROM shows s
CROSS JOIN seasons se ON se.show_id=s.id AND se.number=sqlc.arg(season_number)
CROSS JOIN episodes e ON e.season_id=se.id AND e.number=sqlc.arg(episode_number)
CROSS JOIN episode_files ef ON ef.episode_id=e.id
CROSS JOIN media_files f ON f.id=ef.media_file_id AND f.status='present'
WHERE s.tmdb_id=sqlc.arg(tmdb_id));

-- name: ListDVRFileMetadata :many
SELECT sqlc.embed(r), a.media_file_id AS analyzed,
 EXISTS(SELECT 1 FROM media_segments ms WHERE ms.media_file_id=r.media_file_id AND ms.kind='commercial' AND ms.state='active') AS has_ads
FROM live_tv_recordings r INDEXED BY live_tv_recording_file
LEFT JOIN segment_analyses a ON a.media_file_id=r.media_file_id AND a.source='blackframe'
WHERE r.media_file_id IN (sqlc.slice(file_ids));

-- name: ListDVRAdCandidates :many
-- Only recorder-owned finished files enter the detector. CROSS JOIN pins the
-- recording index before file lookup on databases without statistics.
SELECT f.* FROM live_tv_recordings r INDEXED BY live_tv_recording_state
CROSS JOIN media_files f ON f.id=r.media_file_id AND f.status='present'
LEFT JOIN segment_analyses a ON a.media_file_id=f.id AND a.source='blackframe'
WHERE r.state IN ('done','partial') AND r.id > sqlc.arg(after_id)
 AND (a.media_file_id IS NULL OR a.algo_version!=sqlc.arg(algo_version) OR a.file_size!=f.size OR a.file_mtime!=f.mtime)
ORDER BY r.id LIMIT sqlc.arg(row_limit);

-- name: HasDVRFiles :one
SELECT EXISTS(SELECT 1 FROM live_tv_recordings WHERE state IN ('done','partial') AND media_file_id IS NOT NULL);
