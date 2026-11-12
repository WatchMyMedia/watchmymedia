-- +goose Up
-- Subtitles downloaded from an online provider for a file. The files live
-- in <data>/subtitles-downloaded; each download spends the owner's quota,
-- so the backup keeps them.
CREATE TABLE downloaded_subtitles (
    id               INTEGER PRIMARY KEY,
    media_file_id    INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
    provider         TEXT NOT NULL,
    provider_file_id TEXT NOT NULL,
    language         TEXT NOT NULL DEFAULT '',
    release          TEXT NOT NULL DEFAULT '',
    hearing_impaired INTEGER NOT NULL DEFAULT 0,
    forced           INTEGER NOT NULL DEFAULT 0,
    hash_match       INTEGER NOT NULL DEFAULT 0,
    path             TEXT NOT NULL,
    downloaded_by    INTEGER REFERENCES users(id) ON DELETE SET NULL,
    created_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE (media_file_id, provider, provider_file_id)
);

-- +goose Down
DROP TABLE downloaded_subtitles;
