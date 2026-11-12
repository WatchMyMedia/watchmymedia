-- +goose Up
-- Skippable parts of a file, in Jellyfin's kinds so clients can share logic.
-- A file can have several credits blocks; final marks the last one (a
-- mid-credits scene between blocks still plays). end_ms NULL means to the
-- end of the file. A deleted automatic segment stays as a suppressed row so
-- re-detection does not bring it back.
CREATE TABLE media_segments (
    id            INTEGER PRIMARY KEY,
    media_file_id INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
    kind          TEXT NOT NULL CHECK (kind IN ('intro', 'recap', 'credits', 'preview', 'commercial')),
    start_ms      INTEGER NOT NULL,
    end_ms        INTEGER,
    final         INTEGER NOT NULL DEFAULT 0,
    source        TEXT NOT NULL CHECK (source IN ('manual', 'chapter', 'fingerprint', 'blackframe', 'subtitle', 'external')),
    confidence    REAL NOT NULL DEFAULT 1,
    state         TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'suppressed')),
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX media_segments_file_idx ON media_segments (media_file_id);

-- Which detector has looked at which version of a file, so a file with no
-- segments is not analysed again and a changed or re-tuned one is.
CREATE TABLE segment_analyses (
    media_file_id INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
    source        TEXT NOT NULL,
    algo_version  INTEGER NOT NULL,
    file_mtime    TEXT NOT NULL,
    file_size     INTEGER NOT NULL,
    analyzed_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (media_file_id, source)
);

-- +goose Down
DROP TABLE segment_analyses;
DROP TABLE media_segments;
