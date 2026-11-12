-- name: GetMediaFile :one
SELECT * FROM media_files WHERE id = ?;

-- name: GetMediaFileByPath :one
SELECT * FROM media_files WHERE path = ?;

-- name: UpsertMediaFile :one
INSERT INTO media_files (
    library_id, path, size, mtime, status, parsed, probe, probe_error,
    container, video_codec, audio_codec, width, height, duration_ms, last_seen_at, seen_in_scan, hdr, added_at, dv_profile, extra_kind
)
VALUES (?, ?, ?, ?, 'present', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
-- added_at is kept: a file that changes on disk keeps its place in Recently
-- added. A change of extra_kind is the scanner's to finish (SetMediaFileExtra).
ON CONFLICT (path) DO UPDATE SET
    library_id   = excluded.library_id,
    size         = excluded.size,
    mtime        = excluded.mtime,
    status       = 'present',
    parsed       = excluded.parsed,
    probe        = excluded.probe,
    probe_error  = excluded.probe_error,
    container    = excluded.container,
    video_codec  = excluded.video_codec,
    audio_codec  = excluded.audio_codec,
    width        = excluded.width,
    height       = excluded.height,
    duration_ms  = excluded.duration_ms,
    last_seen_at = excluded.last_seen_at,
    seen_in_scan = excluded.seen_in_scan,
    hdr          = excluded.hdr,
    dv_profile   = excluded.dv_profile,
    extra_kind   = excluded.extra_kind,
    updated_at   = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
RETURNING *;

-- name: MarkMediaFilesSeen :execrows
-- Files that were missing and are back unchanged, in chunks as
-- MarkMediaFilesMissing.
UPDATE media_files
SET status = 'present', last_seen_at = sqlc.arg('last_seen_at'), seen_in_scan = sqlc.arg('seen_in_scan'),
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE status <> 'present' AND id IN (sqlc.slice('ids'));

-- name: ListScanFiles :many
-- What a scan compares the disk with: every file of a library, without the
-- probe JSON (kilobytes per file), so a library loads in one query.
-- dv_outdated is 1 for a Dolby Vision file whose stored probe lacks the
-- record's base layer and level (PLAN-HDR-PROFILE.md, The probe), which the
-- scan probes again; only those rows' probes are read, inside SQLite.
SELECT id, path, size, mtime, status, parsed, hdr, dv_profile, extra_kind,
    CAST(CASE WHEN hdr <> 'dolbyvision' THEN 0
              WHEN probe IS NULL OR NOT json_valid(probe) THEN 1
              WHEN json_type(probe, '$.extra.video[0]') IS NULL THEN 1
              ELSE 0 END AS INTEGER) AS dv_outdated
FROM media_files
WHERE library_id = ?;

-- name: ListScanFilesUnder :many
-- The same for one path: the file at it, or every file below it. lo and hi
-- bound the path range below it (path + separator, path + the next byte), so
-- "Show" does not take in "Show 2". The + keeps SQLite, which has no
-- statistics here, from reading the whole library off its library_id index
-- instead of the range off the path index.
SELECT id, path, size, mtime, status, parsed, hdr, dv_profile, extra_kind,
    CAST(CASE WHEN hdr <> 'dolbyvision' THEN 0
              WHEN probe IS NULL OR NOT json_valid(probe) THEN 1
              WHEN json_type(probe, '$.extra.video[0]') IS NULL THEN 1
              ELSE 0 END AS INTEGER) AS dv_outdated
FROM media_files
WHERE +library_id = sqlc.arg('library_id')
  AND (path = sqlc.arg('path') OR (path >= sqlc.arg('lo') AND path < sqlc.arg('hi')));

-- name: MarkMediaFilesMissing :execrows
UPDATE media_files
SET status = 'missing', updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE status = 'present' AND id IN (sqlc.slice('ids'));

-- name: ListRecentlyMissingFiles :many
-- Files that went missing lately, for spotting a new file as one that moved.
SELECT size, mtime, added_at FROM media_files
WHERE library_id = ? AND status = 'missing' AND updated_at >= ?
ORDER BY updated_at DESC;

-- name: SetMediaFileAddedAt :exec
UPDATE media_files SET added_at = ? WHERE id = ?;

-- name: ListMediaFiles :many
-- A library's files, without trailers (they belong to their movie).
SELECT * FROM media_files
WHERE library_id = sqlc.arg('library_id') AND extra_kind IS NULL
  AND (sqlc.narg('status') IS NULL OR status = sqlc.narg('status'))
  AND (sqlc.narg('match_status') IS NULL OR match_status = sqlc.narg('match_status'))
ORDER BY path
LIMIT sqlc.arg('limit') OFFSET sqlc.arg('offset');

-- name: CountMediaFiles :one
SELECT COUNT(*) FROM media_files
WHERE library_id = sqlc.arg('library_id') AND extra_kind IS NULL
  AND (sqlc.narg('status') IS NULL OR status = sqlc.narg('status'))
  AND (sqlc.narg('match_status') IS NULL OR match_status = sqlc.narg('match_status'));

-- name: CountMediaFilesByStatus :many
-- Each library's files, without trailers: from the library index alone,
-- which holds extra_kind.
SELECT library_id, status, COUNT(*) AS count
FROM media_files
WHERE extra_kind IS NULL
GROUP BY library_id, status;

-- name: UpdateMediaFileParsed :exec
-- A file whose name now reads differently (the parser learned something):
-- store the new reading, and send it back to matching unless it is matched
-- or a person chose its match.
UPDATE media_files
SET parsed = ?,
    match_error  = CASE WHEN match_locked = 0 AND match_status IN ('unmatched', 'failed') THEN NULL ELSE match_error END,
    match_status = CASE WHEN match_locked = 0 AND match_status IN ('unmatched', 'failed') THEN 'pending' ELSE match_status END,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ?;

-- name: GetFileEpisode :one
-- The first episode a file holds (a file can hold S01E01E02), for naming it.
SELECT e.id AS episode_id, e.show_id, se.number AS season_number, e.number AS episode_number
FROM episode_files ef
JOIN episodes e ON e.id = ef.episode_id
JOIN seasons se ON se.id = e.season_id
WHERE ef.media_file_id = ?
ORDER BY se.number, e.number
LIMIT 1;

-- name: SetMediaFileExtra :exec
-- A file whose name now says it is an extra (a trailer.mp4 matched as a
-- second version of its movie before the server read trailers), or no longer
-- is: it leaves its match, or goes back to matching. Its episode_files rows
-- go separately (DeleteEpisodeFilesForFile).
UPDATE media_files
SET extra_kind = sqlc.narg('extra_kind'), extra_of = NULL, movie_id = NULL,
    match_status = 'pending', match_locked = 0, match_candidates = NULL, match_error = NULL, matched_at = NULL,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = sqlc.arg('id');

-- name: ListLibraryTrailers :many
-- A library's trailers on disk, for linking each to its movie. The library
-- index holds extra_kind, so only the trailers' rows are read.
SELECT id, path, extra_of FROM media_files
WHERE library_id = ? AND status = 'present' AND extra_kind = 'trailer'
ORDER BY path;

-- name: ListPathsUnder :many
-- Every file below a folder, from the path index alone (no row is read): lo
-- and hi bound the range, the folder and a separator up to the folder and
-- the byte after the separator.
SELECT id, path FROM media_files
WHERE path >= sqlc.arg('lo') AND path < sqlc.arg('hi')
ORDER BY path;

-- name: ListLinkTargets :many
-- What linking a trailer needs of some files: the movie of each present
-- library file among them. Files a person set aside (match_ignored) don't
-- count.
SELECT id, path, movie_id FROM media_files
WHERE status = 'present' AND extra_kind IS NULL AND match_ignored = 0 AND id IN (sqlc.slice('ids'))
ORDER BY path;

-- name: SetMediaFileExtraOf :exec
UPDATE media_files SET extra_of = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ?;

-- name: ListMovieTrailerFiles :many
-- A movie's trailer files on disk, for its page and for deleting it. Without
-- statistics SQLite would rather read every trailer on the server off the
-- (status, extra_kind) of the trickplay index, hence INDEXED BY.
SELECT id, path, size, duration_ms FROM media_files INDEXED BY media_files_extra_of_idx
WHERE extra_of = ? AND extra_kind = 'trailer' AND status = 'present'
ORDER BY path;
