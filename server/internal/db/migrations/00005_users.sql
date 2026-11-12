-- +goose Up
CREATE TABLE users (
    id            INTEGER PRIMARY KEY,
    name          TEXT NOT NULL COLLATE NOCASE UNIQUE,
    password_hash TEXT NOT NULL,
    is_admin      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE sessions (
    token_hash   TEXT PRIMARY KEY,
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_agent   TEXT NOT NULL DEFAULT '',
    created_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    expires_at   TEXT NOT NULL
);
CREATE INDEX sessions_user_idx ON sessions (user_id);

-- Per-user progress on a movie or episode. media_file_id is the version
-- the position refers to.
CREATE TABLE watch_state (
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_type     TEXT NOT NULL CHECK (item_type IN ('movie', 'episode')),
    item_id       INTEGER NOT NULL,
    media_file_id INTEGER REFERENCES media_files(id) ON DELETE SET NULL,
    position_ms   INTEGER NOT NULL DEFAULT 0,
    duration_ms   INTEGER NOT NULL DEFAULT 0,
    completed     INTEGER NOT NULL DEFAULT 0,
    updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (user_id, item_type, item_id)
);
CREATE INDEX watch_state_user_updated_idx ON watch_state (user_id, updated_at);

-- +goose Down
DROP TABLE watch_state;
DROP TABLE sessions;
DROP TABLE users;
