-- +goose Up
CREATE TABLE libraries (
    id         INTEGER PRIMARY KEY,
    type       TEXT NOT NULL CHECK (type IN ('movie', 'tv')),
    name       TEXT NOT NULL,
    path       TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- +goose Down
DROP TABLE libraries;
