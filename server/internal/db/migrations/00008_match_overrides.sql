-- +goose Up
-- A show or movie a person chose for a whole folder (parser.MatchFolder).
-- Automatic matching uses it instead of searching, so files added to the
-- folder later follow the person's choice.
CREATE TABLE match_overrides (
    library_id INTEGER NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    folder     TEXT NOT NULL,
    tmdb_id    INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (library_id, folder)
);

-- +goose Down
DROP TABLE match_overrides;
