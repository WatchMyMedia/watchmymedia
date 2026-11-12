-- +goose Up
-- Cached fingerprints of an episode's intro and credits windows, so adding
-- an episode to a season does not decode the others again. data is the
-- points as little-endian uint32s, deflated; silences is a JSON list of
-- silence starts (ms from the window start).
CREATE TABLE audio_fingerprints (
    media_file_id INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
    region        TEXT NOT NULL CHECK (region IN ('intro', 'credits')),
    algo_version  INTEGER NOT NULL,
    file_mtime    TEXT NOT NULL,
    file_size     INTEGER NOT NULL,
    start_ms      INTEGER NOT NULL,
    length_ms     INTEGER NOT NULL,
    data          BLOB NOT NULL,
    silences      TEXT NOT NULL DEFAULT '[]',
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (media_file_id, region)
);

-- +goose Down
DROP TABLE audio_fingerprints;
