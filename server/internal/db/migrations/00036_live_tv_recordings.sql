-- Durable DVR schedules and owned outputs. Listing snapshots stay after a
-- provider refresh; only files named by these rows may be removed by DVR.
-- +goose Up
CREATE TABLE live_tv_recording_rules (
 id TEXT PRIMARY KEY,
 user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
 kind TEXT NOT NULL CHECK(kind IN ('series', 'manual')),
 series_key TEXT NOT NULL DEFAULT '',
 data TEXT NOT NULL,
 created_at INTEGER NOT NULL
);
CREATE INDEX live_tv_recording_rule_series ON live_tv_recording_rules(series_key);
CREATE TABLE live_tv_recordings (
 id TEXT PRIMARY KEY,
 rule_id TEXT REFERENCES live_tv_recording_rules(id) ON DELETE SET NULL,
 user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
 source_id TEXT NOT NULL,
 channel_id TEXT NOT NULL,
 number TEXT NOT NULL,
 programme_id TEXT NOT NULL DEFAULT '',
 series_key TEXT NOT NULL DEFAULT '',
 start_at INTEGER NOT NULL,
 end_at INTEGER NOT NULL CHECK(end_at > start_at),
 priority INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('scheduled','conflict','skipped','recording','done','partial','failed','cancelled','gone','deleted')),
 section TEXT NOT NULL,
 path TEXT,
 media_file_id INTEGER REFERENCES media_files(id) ON DELETE SET NULL,
 -- The immutable listing and the small operational details. Indexed fields
 -- above are authoritative; no list query parses JSON or reads file data.
 data TEXT NOT NULL,
 created_at INTEGER NOT NULL,
 updated_at INTEGER NOT NULL
);
CREATE INDEX live_tv_recording_state ON live_tv_recordings(state,start_at,id);
CREATE INDEX live_tv_recording_section ON live_tv_recordings(section,start_at,id);
CREATE INDEX live_tv_recording_programme ON live_tv_recordings(programme_id);
CREATE INDEX live_tv_recording_rule ON live_tv_recordings(rule_id,start_at,id);
CREATE INDEX live_tv_recording_series ON live_tv_recordings(series_key,start_at,id);
CREATE UNIQUE INDEX live_tv_recording_path ON live_tv_recordings(path) WHERE path IS NOT NULL;
CREATE INDEX live_tv_recording_file ON live_tv_recordings(media_file_id);
CREATE INDEX live_tv_recording_manual ON live_tv_recordings(user_id,channel_id,start_at,end_at) WHERE programme_id='';
CREATE INDEX live_tv_programme_series ON live_tv_programmes(series_key,start_at,channel_id);
CREATE TABLE live_tv_recording_progress (
 recording_id TEXT NOT NULL REFERENCES live_tv_recordings(id) ON DELETE CASCADE,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 position_ms INTEGER NOT NULL CHECK(position_ms >= 0),
 duration_ms INTEGER NOT NULL CHECK(duration_ms >= 0),
 updated_at INTEGER NOT NULL,
 PRIMARY KEY(recording_id,user_id)
);
-- +goose Down
DROP TABLE live_tv_recording_progress;
DROP INDEX live_tv_programme_series;
DROP TABLE live_tv_recordings;
DROP TABLE live_tv_recording_rules;
