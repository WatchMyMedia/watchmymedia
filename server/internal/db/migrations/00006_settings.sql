-- +goose Up
-- Settings changed from the UI. A stored value wins over config.yaml and
-- the environment, which only seed first-run defaults.
CREATE TABLE settings (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- +goose Down
DROP TABLE settings;
