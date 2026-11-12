-- +goose Up
ALTER TABLE libraries ADD COLUMN last_scanned_at TEXT;

CREATE TABLE media_files (
    id            INTEGER PRIMARY KEY,
    library_id    INTEGER NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    path          TEXT NOT NULL UNIQUE,
    size          INTEGER NOT NULL,
    mtime         TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'present' CHECK (status IN ('present', 'missing')),
    parsed        TEXT NOT NULL DEFAULT '{}',
    probe         TEXT,
    probe_error   TEXT,
    container     TEXT,
    video_codec   TEXT,
    audio_codec   TEXT,
    width         INTEGER,
    height        INTEGER,
    duration_ms   INTEGER,
    first_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_seen_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    -- Token of the scan that last saw the file; files the current scan did
    -- not touch are the missing ones. Avoids comparing timestamps.
    seen_in_scan  INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX media_files_library_status_idx ON media_files (library_id, status);

-- +goose Down
DROP TABLE media_files;
ALTER TABLE libraries DROP COLUMN last_scanned_at;
