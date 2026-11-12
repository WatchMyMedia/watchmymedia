-- +goose Up
-- Files people downloaded to watch offline (PLAN-DOWNLOADS.md): who, on which
-- device, which version of the file, and what was asked for, so a transcoding
-- download's session can be made again. item_type and item_id name the movie
-- or (first) episode the file was, so a download whose file went can still
-- find the item's new file; item is a snapshot of its titles and artwork for
-- Activity › Downloads, which lists a download after its file is gone.
CREATE TABLE offline_downloads (
    id              INTEGER PRIMARY KEY,
    public_id       TEXT NOT NULL UNIQUE,
    user_id         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_key      TEXT NOT NULL,
    device_name     TEXT NOT NULL DEFAULT '',
    media_file_id   INTEGER REFERENCES media_files(id) ON DELETE SET NULL,
    item_type       TEXT NOT NULL DEFAULT '',  -- movie, episode, or '' for an unmatched file
    item_id         INTEGER NOT NULL DEFAULT 0,
    item            TEXT NOT NULL DEFAULT '{}',
    file_size       INTEGER NOT NULL,
    file_mtime      TEXT NOT NULL,
    mode            TEXT NOT NULL,             -- direct, remux, audio, transcode
    quality         TEXT NOT NULL DEFAULT '',
    request         TEXT NOT NULL,             -- JSON: the playback request
    init_hash       TEXT NOT NULL DEFAULT '',
    size_bytes      INTEGER NOT NULL DEFAULT 0,
    bytes_sent      INTEGER NOT NULL DEFAULT 0,
    state           TEXT NOT NULL DEFAULT 'ready' CHECK (state IN ('ready', 'restart', 'revoked')),
    device_state    TEXT NOT NULL DEFAULT 'queued' CHECK (device_state IN ('queued', 'downloading', 'downloaded', 'removed')),
    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    completed_at    TEXT,
    synced_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    revoked_at      TEXT,
    revoked_by      TEXT NOT NULL DEFAULT '',  -- admin, access, user
    -- The admin who revoked it, for "Revoked by Robin".
    revoked_by_user INTEGER REFERENCES users(id) ON DELETE SET NULL
);
CREATE INDEX offline_downloads_user_device_idx ON offline_downloads (user_id, device_key);
CREATE INDEX offline_downloads_file_idx ON offline_downloads (media_file_id);
CREATE INDEX offline_downloads_revoker_idx ON offline_downloads (revoked_by_user);

-- +goose Down
DROP TABLE offline_downloads;
