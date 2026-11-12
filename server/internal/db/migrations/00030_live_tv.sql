-- Live channels are independent of media files, watch state and history.
-- +goose Up
CREATE TABLE live_tv_sources (
 id TEXT PRIMARY KEY,
 kind TEXT NOT NULL DEFAULT 'hdhomerun',
 device_id TEXT NOT NULL,
 name TEXT NOT NULL,
 address TEXT NOT NULL,
 model TEXT NOT NULL,
 firmware TEXT NOT NULL,
 tuner_count INTEGER NOT NULL CHECK(tuner_count BETWEEN 1 AND 32),
 enabled INTEGER NOT NULL DEFAULT 1,
 refreshed_at TEXT NOT NULL DEFAULT '',
 last_error TEXT NOT NULL DEFAULT '',
 UNIQUE(kind, device_id)
);
CREATE TABLE live_tv_channels (
 id TEXT PRIMARY KEY,
 source_id TEXT NOT NULL REFERENCES live_tv_sources(id) ON DELETE CASCADE,
 provider_key TEXT NOT NULL,
 number TEXT NOT NULL,
 name TEXT NOT NULL,
 stream_path TEXT NOT NULL,
 enabled INTEGER NOT NULL DEFAULT 1,
 available INTEGER NOT NULL DEFAULT 1,
 protected INTEGER NOT NULL DEFAULT 0,
 major INTEGER NOT NULL,
 minor INTEGER NOT NULL,
 last_seen_at TEXT NOT NULL,
 UNIQUE(source_id, provider_key)
);
CREATE INDEX live_tv_channel_order ON live_tv_channels(major, minor, id);
-- +goose Down
DROP TABLE live_tv_channels;
DROP TABLE live_tv_sources;
