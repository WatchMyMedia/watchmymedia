-- +goose Up
-- TMDB's collections that the library's movies belong to, with every film
-- TMDB lists in each, so a collection's page can offer the films the
-- library lacks to request, and a movie can say where it falls in its
-- collection. The related refresher fetches one when a movie names it and
-- it isn't kept or is over 30 days old; pages never wait for it. parts is
-- JSON [{id, title, overview, release_date, poster_path}] in TMDB's order.
CREATE TABLE tmdb_collections (
    id            INTEGER PRIMARY KEY,
    name          TEXT NOT NULL,
    poster_path   TEXT,
    backdrop_path TEXT,
    parts         TEXT NOT NULL DEFAULT '[]',
    fetched_at    TEXT NOT NULL
);

-- +goose Down
DROP TABLE tmdb_collections;
