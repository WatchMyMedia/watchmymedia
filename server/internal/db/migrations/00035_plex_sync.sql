-- +goose Up
-- Durable, opt-in Plex imports. Watch marks remain owned by watchstate; history
-- stays outside plays and survives disconnect without changing its local owner.
ALTER TABLE plex_connections ADD COLUMN source_epoch TEXT NOT NULL DEFAULT '';
ALTER TABLE plex_connections ADD COLUMN next_sync_at TEXT NOT NULL DEFAULT '';
-- Last successfully read history scope. Rotate after it on the next bounded run
-- so large backfills or many mapped libraries cannot starve later people.
ALTER TABLE plex_connections ADD COLUMN history_cursor_user INTEGER NOT NULL DEFAULT 0;
ALTER TABLE plex_connections ADD COLUMN history_cursor_section INTEGER NOT NULL DEFAULT 0;
ALTER TABLE plex_connections ADD COLUMN enabled_by INTEGER REFERENCES users(id) ON DELETE SET NULL;
UPDATE plex_connections SET source_epoch = lower(hex(randomblob(16)));
-- +goose StatementBegin
CREATE TRIGGER plex_source_epoch_created AFTER INSERT ON plex_connections WHEN NEW.source_epoch='' BEGIN
 UPDATE plex_connections SET source_epoch=lower(hex(randomblob(16))) WHERE id=NEW.id;
END;
-- +goose StatementEnd
CREATE TABLE plex_sync_runs (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 connection_id INTEGER REFERENCES plex_connections(id) ON DELETE SET NULL,
 generation INTEGER NOT NULL,
 machine_identifier TEXT NOT NULL,
 source_epoch TEXT NOT NULL,
 actor_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
 status TEXT NOT NULL CHECK(status IN ('queued','running','complete','partial','failed','cancelled')),
 phase TEXT NOT NULL DEFAULT 'queued', reason TEXT NOT NULL DEFAULT '',
 attempt INTEGER NOT NULL DEFAULT 0, fence INTEGER NOT NULL DEFAULT 0,
 lease_until TEXT NOT NULL DEFAULT '', retry_at TEXT NOT NULL DEFAULT '',
 scheduled INTEGER NOT NULL DEFAULT 0, enable_sync INTEGER NOT NULL DEFAULT 0, scope_expired INTEGER NOT NULL DEFAULT 0, watch_complete INTEGER NOT NULL DEFAULT 0,
 history TEXT NOT NULL DEFAULT 'not_read',
 started_at TEXT NOT NULL, finished_at TEXT,
 marked INTEGER NOT NULL DEFAULT 0, already_watched INTEGER NOT NULL DEFAULT 0,
 local_kept INTEGER NOT NULL DEFAULT 0, unmatched INTEGER NOT NULL DEFAULT 0,
 ambiguous INTEGER NOT NULL DEFAULT 0, unavailable INTEGER NOT NULL DEFAULT 0,
 history_imported INTEGER NOT NULL DEFAULT 0, history_skipped INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX plex_sync_active_idx ON plex_sync_runs(connection_id) WHERE status IN ('queued','running');
CREATE INDEX plex_sync_connection_idx ON plex_sync_runs(connection_id,id DESC);
CREATE INDEX plex_sync_approved_idx ON plex_sync_runs(connection_id,generation) WHERE status IN ('complete','partial');
CREATE INDEX plex_sync_actor_idx ON plex_sync_runs(actor_id);
CREATE INDEX plex_run_ready_idx ON plex_sync_runs(retry_at,id) WHERE status='queued';
CREATE INDEX plex_run_leased_idx ON plex_sync_runs(lease_until,id) WHERE status='running';
-- Approved scope contains only source keys and canonical target identities, no
-- provider responses or viewing timestamps. User deletion cascades every row.
CREATE INDEX watch_guards_import_run_idx ON watch_guards(import_run,user_id,item_type,item_id) WHERE owner='import';
CREATE TABLE plex_run_scope (
 run_id INTEGER NOT NULL REFERENCES plex_sync_runs(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 plex_user_id INTEGER NOT NULL, kind TEXT NOT NULL, section_id INTEGER NOT NULL,
 section_uuid TEXT NOT NULL, remote_key TEXT NOT NULL, fingerprint TEXT NOT NULL,
 item_type TEXT NOT NULL, item_id INTEGER NOT NULL, library_id INTEGER NOT NULL,
 tmdb_id INTEGER NOT NULL, season INTEGER NOT NULL, episode INTEGER NOT NULL,
 category TEXT NOT NULL, source_known INTEGER NOT NULL DEFAULT 0, outcome TEXT NOT NULL DEFAULT '',
 PRIMARY KEY(run_id,user_id,section_id,remote_key)
);
CREATE INDEX plex_run_scope_user_idx ON plex_run_scope(user_id);
CREATE TABLE plex_source_items (
 machine_identifier TEXT NOT NULL, source_epoch TEXT NOT NULL,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 section_id INTEGER NOT NULL, remote_key TEXT NOT NULL, fingerprint TEXT NOT NULL,
 PRIMARY KEY(machine_identifier,source_epoch,user_id,section_id,remote_key)
);
CREATE INDEX plex_source_items_user_idx ON plex_source_items(user_id);
CREATE TABLE plex_exclusions (
 connection_id INTEGER NOT NULL REFERENCES plex_connections(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 item_type TEXT NOT NULL CHECK(item_type IN ('movie','episode')), item_id INTEGER NOT NULL,
 PRIMARY KEY(connection_id,user_id,item_type,item_id)
);
CREATE INDEX plex_exclusions_user_idx ON plex_exclusions(user_id);
CREATE INDEX plex_exclusions_item_idx ON plex_exclusions(item_type,item_id);
CREATE TABLE plex_history_checkpoints (
 connection_id INTEGER NOT NULL REFERENCES plex_connections(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 section_id INTEGER NOT NULL, generation INTEGER NOT NULL,
 since_time TEXT NOT NULL, until_time TEXT NOT NULL, last_full TEXT NOT NULL,
 offset INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT -1,
 complete INTEGER NOT NULL DEFAULT 0, full_pass INTEGER NOT NULL DEFAULT 1,
 PRIMARY KEY(connection_id,user_id,section_id)
);
CREATE INDEX plex_history_checkpoints_user_idx ON plex_history_checkpoints(user_id);
CREATE TABLE plex_run_history_coverage (
 run_id INTEGER NOT NULL REFERENCES plex_sync_runs(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 section_id INTEGER NOT NULL, from_time TEXT NOT NULL, through_time TEXT NOT NULL,
 complete INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(run_id,user_id,section_id)
);
CREATE INDEX plex_run_history_coverage_user_idx ON plex_run_history_coverage(user_id);
CREATE TABLE plex_history_policy (
 user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 cutoff TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'
);
CREATE TABLE plex_history_events (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 machine_identifier TEXT NOT NULL, source_epoch TEXT NOT NULL,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 plex_user_id INTEGER NOT NULL, source_key TEXT NOT NULL, fingerprint TEXT NOT NULL,
 item_type TEXT NOT NULL CHECK(item_type IN ('movie','episode')), item_id INTEGER,
 title TEXT NOT NULL, show_title TEXT NOT NULL DEFAULT '', season INTEGER NOT NULL DEFAULT 0,
 episode INTEGER NOT NULL DEFAULT 0, viewed_at TEXT NOT NULL, imported_at TEXT NOT NULL,
 run_id INTEGER REFERENCES plex_sync_runs(id) ON DELETE SET NULL,
 UNIQUE(user_id,machine_identifier,source_epoch,source_key)
);
CREATE INDEX plex_history_user_idx ON plex_history_events(user_id,id DESC);
CREATE INDEX plex_history_viewed_idx ON plex_history_events(viewed_at);
CREATE INDEX plex_history_item_idx ON plex_history_events(item_type,item_id);
CREATE TABLE plex_history_suppressions (
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 machine_identifier TEXT NOT NULL, source_epoch TEXT NOT NULL, source_key TEXT NOT NULL,
 viewed_at TEXT NOT NULL,
 PRIMARY KEY(user_id,machine_identifier,source_epoch,source_key)
);
CREATE INDEX plex_history_suppressions_time_idx ON plex_history_suppressions(viewed_at);
-- Changing history privacy while an external read is in flight is authoritative.
-- +goose StatementBegin
CREATE TRIGGER plex_history_recording_off AFTER UPDATE OF record_history ON users
WHEN NEW.record_history = 0 BEGIN
 DELETE FROM plex_history_events WHERE user_id = NEW.id;
 DELETE FROM plex_run_history_coverage WHERE user_id=NEW.id;
 DELETE FROM plex_history_suppressions WHERE user_id = NEW.id;
 INSERT INTO plex_history_policy(user_id,cutoff) VALUES(NEW.id,strftime('%Y-%m-%dT%H:%M:%fZ','now'))
 ON CONFLICT(user_id) DO UPDATE SET cutoff=excluded.cutoff;
END;
-- +goose StatementEnd
-- An approved schedule never silently expands to newly mapped scope or a new
-- credential generation. Reconnect/mapping changes require another preview.
-- +goose StatementBegin
CREATE TRIGGER plex_generation_stops_sync AFTER UPDATE OF generation ON plex_connections
WHEN NEW.generation<>OLD.generation BEGIN
 UPDATE plex_connections SET sync_enabled=0,next_sync_at='',history_cursor_user=0,history_cursor_section=0 WHERE id=NEW.id;
END;
-- +goose StatementEnd
-- A demotion permanently revokes the integration, even if the account is
-- promoted again before the next poll. Another admin must explicitly reconnect.
-- +goose StatementBegin
CREATE TRIGGER plex_authorizer_demoted AFTER UPDATE OF is_admin ON users
WHEN OLD.is_admin=1 AND NEW.is_admin=0 BEGIN
 UPDATE plex_connections SET authorized_by=NULL,sync_enabled=0 WHERE authorized_by=NEW.id OR enabled_by=NEW.id;
 UPDATE plex_sync_runs SET status='cancelled',phase='finished',reason='needs_authorization',fence=fence+1,
 finished_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE actor_id=NEW.id AND status IN ('queued','running');
END;
-- +goose StatementEnd
-- Losing the enabling admin also revokes a previously approved schedule.
-- +goose StatementBegin
CREATE TRIGGER plex_enabler_deleted AFTER UPDATE OF enabled_by ON plex_connections
WHEN OLD.enabled_by IS NOT NULL AND NEW.enabled_by IS NULL BEGIN
 UPDATE plex_connections SET authorized_by=NULL,sync_enabled=0 WHERE id=NEW.id;
END;
-- +goose StatementEnd
-- Retained history is still readable after a library item disappears; a local
-- numeric ID must never silently link an old event or exclusion to its reuse.
-- +goose StatementBegin
CREATE TRIGGER plex_movie_deleted AFTER DELETE ON movies BEGIN
 UPDATE plex_history_events SET item_id=NULL WHERE item_type='movie' AND item_id=OLD.id;
 DELETE FROM plex_exclusions WHERE item_type='movie' AND item_id=OLD.id;
END;
-- +goose StatementEnd
-- +goose StatementBegin
CREATE TRIGGER plex_episode_deleted AFTER DELETE ON episodes BEGIN
 UPDATE plex_history_events SET item_id=NULL WHERE item_type='episode' AND item_id=OLD.id;
 DELETE FROM plex_exclusions WHERE item_type='episode' AND item_id=OLD.id;
END;
-- +goose StatementEnd
-- +goose Down
DROP TRIGGER plex_episode_deleted;
DROP TRIGGER plex_movie_deleted;
DROP TRIGGER plex_authorizer_demoted;
DROP TRIGGER plex_generation_stops_sync;
DROP TRIGGER plex_enabler_deleted;
DROP TRIGGER plex_history_recording_off;
DROP TABLE plex_history_suppressions;
DROP TABLE plex_history_events;
DROP TABLE plex_history_policy;
DROP TABLE plex_history_checkpoints;
DROP TABLE plex_run_history_coverage;
DROP TABLE plex_exclusions;
DROP TABLE plex_source_items;
DROP TABLE plex_run_scope;
DROP TABLE plex_sync_runs;
DROP INDEX watch_guards_import_run_idx;
DROP TRIGGER plex_source_epoch_created;
ALTER TABLE plex_connections DROP COLUMN enabled_by;
ALTER TABLE plex_connections DROP COLUMN history_cursor_section;
ALTER TABLE plex_connections DROP COLUMN history_cursor_user;
ALTER TABLE plex_connections DROP COLUMN next_sync_at;
ALTER TABLE plex_connections DROP COLUMN source_epoch;
