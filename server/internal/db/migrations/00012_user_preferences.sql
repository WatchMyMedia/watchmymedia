-- +goose Up
-- Per-user settings that follow a person across devices: how each kind of
-- skippable segment is handled ("skip.intro" = none|ask|auto|auto-delay)
-- and whether the next episode plays by itself ("autoplayNext").
CREATE TABLE user_preferences (
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key        TEXT NOT NULL,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (user_id, key)
);

-- +goose Down
DROP TABLE user_preferences;
