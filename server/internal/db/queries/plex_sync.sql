-- name: InsertPlexRun :one
INSERT INTO plex_sync_runs(connection_id,generation,machine_identifier,source_epoch,actor_id,status,scheduled,enable_sync,started_at)
VALUES(?,?,?,?,?,'queued',?,?,?) RETURNING *;

-- name: GetPlexRun :one
SELECT * FROM plex_sync_runs WHERE id=?;

-- name: ListPlexRuns :many
SELECT * FROM plex_sync_runs WHERE connection_id=? ORDER BY id DESC LIMIT 30;

-- name: NextPlexRun :one
SELECT r.* FROM plex_sync_runs r WHERE r.id IN (
 SELECT ready.id FROM plex_sync_runs ready INDEXED BY plex_run_ready_idx WHERE ready.status='queued' AND ready.retry_at<=sqlc.arg(now)
 UNION ALL
 SELECT leased.id FROM plex_sync_runs leased INDEXED BY plex_run_leased_idx WHERE leased.status='running' AND leased.lease_until<=sqlc.arg(now)
) ORDER BY r.id LIMIT 1;

-- name: ClaimPlexRun :one
UPDATE plex_sync_runs SET status='running',phase='reading',fence=fence+1,attempt=attempt+1,lease_until=sqlc.arg(lease_until)
WHERE id=sqlc.arg(id) AND ((status='queued' AND retry_at <= sqlc.arg(now)) OR (status='running' AND lease_until <= sqlc.arg(now))) RETURNING *;

-- name: RenewPlexRun :execrows
UPDATE plex_sync_runs SET lease_until=? WHERE id=? AND fence=? AND status='running';

-- name: FinishPlexRun :execrows
UPDATE plex_sync_runs SET status=?,phase='finished',reason=?,history=?,finished_at=?,lease_until=''
WHERE id=? AND fence=? AND status='running';

-- name: CancelPlexRun :execrows
UPDATE plex_sync_runs SET status='cancelled',phase='finished',reason='cancelled',finished_at=?,fence=fence+1,lease_until=''
WHERE id=? AND status IN ('queued','running');

-- name: RetryPlexRun :execrows
UPDATE plex_sync_runs SET status='queued',phase='queued',reason='',finished_at=NULL,retry_at=?,attempt=0,fence=fence+1
WHERE id=? AND status IN ('failed','partial','cancelled');

-- name: BackoffPlexRun :execrows
UPDATE plex_sync_runs SET status='queued',phase='waiting',reason=?,retry_at=?,lease_until=''
WHERE id=? AND fence=? AND status='running';

-- name: SetPlexSync :execrows
UPDATE plex_connections SET sync_enabled=?,next_sync_at=?,problem=''
WHERE id=? AND generation=?;

-- name: SchedulePlexNext :exec
UPDATE plex_connections SET next_sync_at=? WHERE id=? AND generation=?;

-- name: PausePlexConnection :exec
UPDATE plex_connections SET sync_enabled=0,problem=? WHERE id=? AND generation=?;

-- name: RebaselinePlexConnection :execrows
UPDATE plex_connections SET source_epoch=?,generation=generation+1,sync_enabled=0,problem='',next_sync_at=''
WHERE id=? AND generation=?;

-- name: InsertPlexScope :exec
INSERT INTO plex_run_scope(run_id,user_id,plex_user_id,kind,section_id,section_uuid,remote_key,fingerprint,item_type,item_id,library_id,tmdb_id,season,episode,category,source_known)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);

-- name: ListPlexScope :many
SELECT * FROM plex_run_scope WHERE run_id=? ORDER BY user_id,section_id,remote_key;

-- name: SetPlexScopeOutcome :execrows
UPDATE plex_run_scope SET outcome=? WHERE run_id=? AND user_id=? AND section_id=? AND remote_key=? AND outcome='';

-- name: CountPlexOutcomes :many
SELECT outcome,COUNT(*) AS n FROM plex_run_scope WHERE run_id=? AND outcome<>'' GROUP BY outcome;

-- name: AddPlexOutcomeCounts :exec
UPDATE plex_sync_runs SET
 marked=marked+CASE WHEN sqlc.arg(outcome)='applied' THEN 1 ELSE 0 END,
 already_watched=already_watched+CASE WHEN sqlc.arg(outcome)='already_watched' THEN 1 ELSE 0 END,
 local_kept=local_kept+CASE WHEN (sqlc.arg(outcome)='local_kept' OR sqlc.arg(outcome)='excluded') THEN 1 ELSE 0 END,
 unmatched=unmatched+CASE WHEN sqlc.arg(outcome)='unmatched' THEN 1 ELSE 0 END,
 ambiguous=ambiguous+CASE WHEN sqlc.arg(outcome)='ambiguous' THEN 1 ELSE 0 END,
 unavailable=unavailable+CASE WHEN sqlc.arg(outcome)='unavailable' THEN 1 ELSE 0 END
 WHERE id=sqlc.arg(id);

-- name: GetPlexSourceFingerprint :one
SELECT fingerprint FROM plex_source_items WHERE machine_identifier=? AND source_epoch=? AND user_id=? AND section_id=? AND remote_key=?;

-- name: InsertPlexSourceFingerprint :exec
INSERT INTO plex_source_items(machine_identifier,source_epoch,user_id,section_id,remote_key,fingerprint) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING;

-- name: ListPlexExclusions :many
SELECT * FROM plex_exclusions WHERE connection_id=? ORDER BY user_id,item_type,item_id;

-- name: DeletePlexExclusions :exec
DELETE FROM plex_exclusions WHERE connection_id=?;

-- name: InsertPlexExclusion :exec
INSERT INTO plex_exclusions(connection_id,user_id,item_type,item_id) VALUES(?,?,?,?) ON CONFLICT DO NOTHING;

-- name: HasPlexExclusion :one
SELECT EXISTS(SELECT 1 FROM plex_exclusions WHERE connection_id=? AND user_id=? AND item_type=? AND item_id=?);

-- name: GetPlexHistoryCutoff :one
SELECT COALESCE((SELECT cutoff FROM plex_history_policy WHERE user_id=?),'1970-01-01T00:00:00Z');

-- name: SetPlexHistoryCutoff :exec
INSERT INTO plex_history_policy(user_id,cutoff) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET cutoff=excluded.cutoff;

-- name: GetPlexHistory :one
SELECT * FROM plex_history_events WHERE id=? AND user_id=?;

-- name: ListPlexHistory :many
SELECT he.* FROM plex_history_events he JOIN users u ON u.id=he.user_id AND u.record_history=1
WHERE he.user_id=sqlc.arg(user_id) AND he.id<sqlc.arg(before_id) AND he.viewed_at>sqlc.arg(cutoff)
ORDER BY he.id DESC LIMIT sqlc.arg(page_size);

-- name: GetPlexHistoryFingerprint :one
SELECT fingerprint FROM plex_history_events WHERE user_id=? AND machine_identifier=? AND source_epoch=? AND source_key=?;

-- name: InsertPlexHistory :execrows
INSERT INTO plex_history_events(machine_identifier,source_epoch,user_id,plex_user_id,source_key,fingerprint,item_type,item_id,title,show_title,season,episode,viewed_at,imported_at,run_id)
SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
WHERE NOT EXISTS(SELECT 1 FROM plex_history_suppressions hs WHERE hs.user_id=sqlc.arg(user_id) AND hs.machine_identifier=sqlc.arg(machine_identifier) AND hs.source_epoch=sqlc.arg(source_epoch) AND hs.source_key=sqlc.arg(source_key))
ON CONFLICT DO NOTHING;

-- name: DeletePlexHistory :exec
DELETE FROM plex_history_events WHERE id=? AND user_id=?;

-- name: ClearPlexHistory :exec
DELETE FROM plex_history_events WHERE user_id=?;

-- name: SuppressPlexHistory :exec
INSERT INTO plex_history_suppressions(user_id,machine_identifier,source_epoch,source_key,viewed_at)
SELECT user_id,machine_identifier,source_epoch,source_key,viewed_at FROM plex_history_events he WHERE he.id=? AND he.user_id=? ON CONFLICT DO NOTHING;

-- name: AddPlexHistoryCount :exec
UPDATE plex_sync_runs SET history_imported=history_imported+?,history_skipped=history_skipped+? WHERE id=?;

-- name: ListRunWatchGuards :many
SELECT * FROM watch_guards WHERE owner='import' AND import_run=? ORDER BY user_id,item_type,item_id;

-- name: DeletePlexOldHistory :exec
DELETE FROM plex_history_events WHERE viewed_at < ?;

-- name: DeletePlexOldSuppressions :exec
DELETE FROM plex_history_suppressions WHERE viewed_at < ?;

-- name: DeletePlexPrivateScope :exec
DELETE FROM plex_run_scope WHERE run_id IN (SELECT id FROM plex_sync_runs pr WHERE pr.status NOT IN ('queued','running') AND (pr.finished_at < ? OR pr.watch_complete=1))
OR (user_id IN (SELECT id FROM users WHERE record_history=0) AND run_id IN (SELECT id FROM plex_sync_runs WHERE status NOT IN ('queued','running')));

-- name: UnlinkPlexRunTargets :exec
UPDATE plex_history_events SET item_id=NULL WHERE item_type=? AND item_id=?;

-- name: ValidPlexRun :one
SELECT EXISTS(SELECT 1 FROM plex_sync_runs r
JOIN plex_connections c ON c.id=r.connection_id
JOIN users admin ON admin.id=c.authorized_by AND admin.is_admin=1
JOIN users actor ON actor.id=r.actor_id AND actor.is_admin=1
WHERE r.id=? AND r.fence=? AND r.status='running' AND c.generation=r.generation
AND c.credential_ref IS NOT NULL AND c.source_epoch=r.source_epoch AND r.lease_until > sqlc.arg(now));

-- name: HasPlexUserBinding :one
SELECT EXISTS(SELECT 1 FROM plex_user_links WHERE connection_id=? AND user_id=? AND plex_user_id=? AND kind=?);

-- name: HasPlexLibraryBinding :one
SELECT EXISTS(SELECT 1 FROM plex_library_links WHERE connection_id=? AND section_id=? AND section_uuid=? AND library_id=?);


-- name: GetPlexHistoryCheckpoint :one
SELECT * FROM plex_history_checkpoints WHERE connection_id=? AND user_id=? AND section_id=?;

-- name: SavePlexHistoryCheckpoint :exec
INSERT INTO plex_history_checkpoints(connection_id,user_id,section_id,generation,since_time,until_time,last_full,offset,total,complete,full_pass)
VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(connection_id,user_id,section_id) DO UPDATE SET
 generation=excluded.generation,since_time=excluded.since_time,until_time=excluded.until_time,last_full=excluded.last_full,
 offset=excluded.offset,total=excluded.total,complete=excluded.complete,full_pass=excluded.full_pass;


-- name: ListPlexHistoryCoverage :many
SELECT cp.* FROM plex_run_history_coverage cp JOIN users u ON u.id=cp.user_id AND u.record_history=1
WHERE cp.run_id=? ORDER BY cp.user_id,cp.section_id;

-- name: SavePlexRunHistoryCoverage :exec
INSERT INTO plex_run_history_coverage(run_id,user_id,section_id,from_time,through_time,complete)
VALUES(?,?,?,?,?,?) ON CONFLICT(run_id,user_id,section_id) DO UPDATE SET
 from_time=excluded.from_time,through_time=excluded.through_time,complete=excluded.complete;

-- name: DeletePlexPrivateCoverage :exec
DELETE FROM plex_run_history_coverage WHERE run_id IN (SELECT id FROM plex_sync_runs pr WHERE pr.status NOT IN ('queued','running') AND pr.finished_at < ?)
OR (user_id IN (SELECT id FROM users WHERE record_history=0) AND run_id IN (SELECT id FROM plex_sync_runs WHERE status NOT IN ('queued','running')));

-- name: MovePlexExclusions :exec
INSERT INTO plex_exclusions(connection_id,user_id,item_type,item_id)
SELECT pe.connection_id,pe.user_id,pe.item_type,sqlc.arg(to_id) FROM plex_exclusions pe
WHERE pe.item_type=sqlc.arg(item_type) AND pe.item_id=sqlc.arg(from_id) ON CONFLICT DO NOTHING;


-- name: MarkPlexExpiredScope :exec
UPDATE plex_sync_runs SET scope_expired=1 WHERE status NOT IN ('queued','running') AND watch_complete=0 AND (
 finished_at < ? OR EXISTS(SELECT 1 FROM plex_run_scope ps JOIN users u ON u.id=ps.user_id WHERE ps.run_id=plex_sync_runs.id AND u.record_history=0));

-- name: ListPlexExclusionDisplay :many
SELECT ex.connection_id,ex.user_id,ex.item_type,ex.item_id,u.name AS user_name,
 COALESCE(m.title,e.title,'') AS title,COALESCE(sh.title,'') AS show_title,
 COALESCE(se.number,0) AS season,COALESCE(e.number,0) AS episode
FROM plex_exclusions ex JOIN users u ON u.id=ex.user_id
LEFT JOIN movies m ON ex.item_type='movie' AND m.id=ex.item_id
LEFT JOIN episodes e ON ex.item_type='episode' AND e.id=ex.item_id
LEFT JOIN seasons se ON se.id=e.season_id
LEFT JOIN shows sh ON sh.id=e.show_id
WHERE ex.connection_id=? ORDER BY ex.user_id,ex.item_type,ex.item_id;


-- name: SetPlexScheduleActor :execrows
UPDATE plex_connections SET enabled_by=sqlc.arg(actor_id) WHERE plex_connections.id=sqlc.arg(id) AND plex_connections.generation=sqlc.arg(generation)
AND EXISTS(SELECT 1 FROM users u WHERE u.id=sqlc.arg(actor_id) AND u.is_admin=1);

-- name: ListActivePlexRuns :many
SELECT * FROM plex_sync_runs WHERE connection_id=? AND status IN ('queued','running');

-- name: HasApprovedPlexRun :one
SELECT EXISTS(SELECT 1 FROM plex_sync_runs WHERE connection_id=? AND generation=? AND status IN ('complete','partial'));

-- name: ListPlexRunsBefore :many
SELECT * FROM plex_sync_runs WHERE connection_id=sqlc.arg(connection_id) AND id<sqlc.arg(before_id)
ORDER BY id DESC LIMIT sqlc.arg(page_size);


-- name: CompletePlexWatchScope :execrows
UPDATE plex_sync_runs SET watch_complete=1 WHERE id=? AND fence=? AND status='running';

-- name: SetPlexHistoryCursor :execrows
-- Commit the next run's rotation with the page effects and checkpoint.
UPDATE plex_connections SET history_cursor_user=sqlc.arg(plex_user_id),history_cursor_section=sqlc.arg(section_id)
WHERE id=sqlc.arg(id) AND generation=sqlc.arg(generation);

-- name: ListPlexSourceFingerprints :many
-- One person's known source items in one Plex section, by the primary key.
SELECT remote_key,fingerprint FROM plex_source_items
WHERE machine_identifier=? AND source_epoch=? AND user_id=? AND section_id=?;

-- name: AddPlexRunCounts :execrows
-- Counts for the items a scheduled sync found unchanged and did not write.
UPDATE plex_sync_runs SET
 already_watched=already_watched+sqlc.arg(already_watched),
 local_kept=local_kept+sqlc.arg(local_kept),
 unmatched=unmatched+sqlc.arg(unmatched),
 ambiguous=ambiguous+sqlc.arg(ambiguous),
 unavailable=unavailable+sqlc.arg(unavailable)
WHERE id=sqlc.arg(id) AND fence=sqlc.arg(fence) AND status='running';
