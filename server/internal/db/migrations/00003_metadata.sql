-- +goose Up
CREATE TABLE movies (
    id             INTEGER PRIMARY KEY,
    library_id     INTEGER NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    tmdb_id        INTEGER NOT NULL,
    imdb_id        TEXT,
    title          TEXT NOT NULL,
    original_title TEXT,
    year           INTEGER,
    release_date   TEXT,
    overview       TEXT NOT NULL DEFAULT '',
    tagline        TEXT,
    runtime_min    INTEGER,
    genres         TEXT NOT NULL DEFAULT '[]',
    rating         REAL,
    poster_path    TEXT,
    backdrop_path  TEXT,
    created_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE (library_id, tmdb_id)
);

CREATE TABLE shows (
    id             INTEGER PRIMARY KEY,
    library_id     INTEGER NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    tmdb_id        INTEGER NOT NULL,
    title          TEXT NOT NULL,
    original_title TEXT,
    year           INTEGER,
    first_air_date TEXT,
    overview       TEXT NOT NULL DEFAULT '',
    status         TEXT,
    genres         TEXT NOT NULL DEFAULT '[]',
    rating         REAL,
    poster_path    TEXT,
    backdrop_path  TEXT,
    created_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE (library_id, tmdb_id)
);

CREATE TABLE seasons (
    id            INTEGER PRIMARY KEY,
    show_id       INTEGER NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    number        INTEGER NOT NULL,
    name          TEXT NOT NULL DEFAULT '',
    overview      TEXT NOT NULL DEFAULT '',
    air_date      TEXT,
    poster_path   TEXT,
    episode_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE (show_id, number)
);

CREATE TABLE episodes (
    id          INTEGER PRIMARY KEY,
    season_id   INTEGER NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
    show_id     INTEGER NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    number      INTEGER NOT NULL,
    title       TEXT NOT NULL DEFAULT '',
    overview    TEXT NOT NULL DEFAULT '',
    air_date    TEXT,
    still_path  TEXT,
    runtime_min INTEGER,
    rating      REAL,
    UNIQUE (season_id, number)
);

-- A file covers one or more episodes (S01E01E02).
CREATE TABLE episode_files (
    episode_id    INTEGER NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
    media_file_id INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
    PRIMARY KEY (episode_id, media_file_id)
);
CREATE INDEX episode_files_media_file_idx ON episode_files (media_file_id);

ALTER TABLE media_files ADD COLUMN movie_id INTEGER REFERENCES movies(id) ON DELETE SET NULL;
-- pending: not tried yet; matched; unmatched: no confident candidate; failed: lookup error.
ALTER TABLE media_files ADD COLUMN match_status TEXT NOT NULL DEFAULT 'pending'
    CHECK (match_status IN ('pending', 'matched', 'unmatched', 'failed'));
-- Set when a person chose the match; automatic re-matching leaves it alone.
ALTER TABLE media_files ADD COLUMN match_locked INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_files ADD COLUMN match_candidates TEXT;
ALTER TABLE media_files ADD COLUMN match_error TEXT;
ALTER TABLE media_files ADD COLUMN matched_at TEXT;
CREATE INDEX media_files_match_idx ON media_files (library_id, match_status);
CREATE INDEX media_files_movie_idx ON media_files (movie_id);

-- +goose Down
DROP INDEX media_files_movie_idx;
DROP INDEX media_files_match_idx;
ALTER TABLE media_files DROP COLUMN matched_at;
ALTER TABLE media_files DROP COLUMN match_error;
ALTER TABLE media_files DROP COLUMN match_candidates;
ALTER TABLE media_files DROP COLUMN match_locked;
ALTER TABLE media_files DROP COLUMN match_status;
ALTER TABLE media_files DROP COLUMN movie_id;
DROP TABLE episode_files;
DROP TABLE episodes;
DROP TABLE seasons;
DROP TABLE shows;
DROP TABLE movies;
