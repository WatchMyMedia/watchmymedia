-- +goose Up
-- Seek thumbnails made for each file (PLAN-TRICKPLAY.md). One row per file:
-- the version the tiles are for, and what came of making them. Rows go with
-- their file; the folders on disk are swept to match. file_size and
-- file_mtime are what the version was made from, so the work list can find
-- changed files in SQL, as segment_analyses does. tone_map is how HDR was
-- mapped to SDR ('' for SDR files), so tiles made with less than the
-- server can do now (no zscale, or Dolby Vision 5 without tonemapx) are
-- made again when it can.
CREATE TABLE trickplay (
    media_file_id INTEGER PRIMARY KEY REFERENCES media_files(id) ON DELETE CASCADE,
    version       TEXT NOT NULL,
    algo_version  INTEGER NOT NULL,
    file_size     INTEGER NOT NULL,
    file_mtime    TEXT NOT NULL,
    tone_map      TEXT NOT NULL DEFAULT '',
    status        TEXT NOT NULL CHECK (status IN ('done', 'failed')),
    interval_ms   INTEGER NOT NULL DEFAULT 0,
    width         INTEGER NOT NULL DEFAULT 0,
    height        INTEGER NOT NULL DEFAULT 0,
    columns       INTEGER NOT NULL DEFAULT 0,
    rows          INTEGER NOT NULL DEFAULT 0,
    tiles         INTEGER NOT NULL DEFAULT 0,
    sheets        INTEGER NOT NULL DEFAULT 0,
    bytes         INTEGER NOT NULL DEFAULT 0,
    error         TEXT NOT NULL DEFAULT '',
    made_at       TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- The work list walks present files newest first and the settings page
-- counts them. The columns those read come after the probe JSON in a
-- media_files row, which is kilobytes and spills onto overflow pages, so
-- reading them from the table read every probe; this index holds them.
CREATE INDEX media_files_trickplay_idx ON media_files (status, first_seen_at, id, duration_ms, video_codec, size, mtime);

-- +goose Down
DROP INDEX media_files_trickplay_idx;
DROP TABLE trickplay;
