-- +goose Up
-- Where a season starts when TMDB numbers it differently from the files in
-- a show folder (PLAN-SEASON-MAPPING.md): files from from_season and
-- from_episode on are the TMDB show's to_season and to_episode on, in order.
-- The show may be another one than the folder's, as when TMDB lists a later
-- season as a show of its own. Matching uses it before it searches, so files
-- added to the folder later follow it.
CREATE TABLE season_maps (
    library_id   INTEGER NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    folder       TEXT    NOT NULL,
    from_season  INTEGER NOT NULL,
    from_episode INTEGER NOT NULL DEFAULT 1,
    tmdb_id      INTEGER NOT NULL,
    to_season    INTEGER NOT NULL,
    to_episode   INTEGER NOT NULL DEFAULT 1,
    created_at   TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (library_id, folder, from_season, from_episode)
);

-- +goose Down
DROP TABLE season_maps;
