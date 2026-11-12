-- +goose Up
-- Requests and problem reports (PLAN-ARR.md §7.2, §9), kept by the server
-- when Radarr and Sonarr carry requests out without Seerr. Deleting an
-- account deletes what it asked for and reported, as it deletes its history.
CREATE TABLE requests (
    id             INTEGER PRIMARY KEY,
    user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_type     TEXT NOT NULL CHECK (media_type IN ('movie', 'tv')),
    tmdb_id        INTEGER NOT NULL,
    title          TEXT NOT NULL,
    year           INTEGER,
    poster_path    TEXT,
    -- Season numbers, TV only.
    seasons        TEXT NOT NULL DEFAULT '[]',
    status         TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'declined', 'failed', 'available', 'cancelled')),
    -- The admin's reason for declining, or why carrying it out failed.
    note           TEXT,
    integration_id INTEGER REFERENCES integrations(id) ON DELETE SET NULL,
    decided_by     INTEGER REFERENCES users(id) ON DELETE SET NULL,
    decided_at     TEXT,
    created_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX requests_media_idx ON requests (media_type, tmdb_id);
CREATE INDEX requests_status_idx ON requests (status, created_at);
CREATE INDEX requests_user_idx ON requests (user_id, created_at);

CREATE TABLE problems (
    id            INTEGER PRIMARY KEY,
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_type     TEXT NOT NULL CHECK (item_type IN ('movie', 'episode')),
    item_id       INTEGER NOT NULL,
    media_file_id INTEGER REFERENCES media_files(id) ON DELETE SET NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('video', 'audio', 'subtitles', 'other')),
    note          TEXT NOT NULL DEFAULT '',
    -- What the server knew about the file: name, codecs, resolution.
    details       TEXT NOT NULL DEFAULT '',
    status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved')),
    resolved_by   INTEGER REFERENCES users(id) ON DELETE SET NULL,
    resolved_at   TEXT,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX problems_status_idx ON problems (status, created_at);

-- +goose Down
DROP TABLE problems;
DROP TABLE requests;
