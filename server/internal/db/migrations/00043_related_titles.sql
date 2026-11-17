-- +goose Up
-- Related titles at the end of movie and show pages (PLAN-RELATED.md 4).
-- A movie keeps its TMDB collection; both kinds keep when their related
-- data was last fetched (NULL: never, so the fill-in job fetches it).
ALTER TABLE movies ADD COLUMN collection_id INTEGER;
ALTER TABLE movies ADD COLUMN collection_name TEXT;
ALTER TABLE movies ADD COLUMN related_checked_at TEXT;
ALTER TABLE shows ADD COLUMN related_checked_at TEXT;
CREATE INDEX movies_collection_idx ON movies (collection_id) WHERE collection_id IS NOT NULL;
CREATE INDEX movies_related_checked_idx ON movies (related_checked_at);
CREATE INDEX shows_related_checked_idx ON shows (related_checked_at);

-- What a title is like. kind: rec (one of TMDB's recommendations: value is
-- its TMDB ID, rank TMDB's order), keyword (a TMDB keyword ID), genre (its
-- name), person (a TMDB person ID in the cast, rank the billing order),
-- director (movies) or creator (shows) (a TMDB person ID), language (the
-- original language's ISO 639-1 code). Values are text whatever their kind.
-- The value index serves both looking for titles that share a tag and the
-- titles whose recommendations name a TMDB ID.
CREATE TABLE movie_tags (
    movie_id INTEGER NOT NULL REFERENCES movies(id) ON DELETE CASCADE,
    kind     TEXT NOT NULL CHECK (kind IN ('rec', 'keyword', 'genre', 'person', 'director', 'language')),
    value    TEXT NOT NULL,
    rank     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (movie_id, kind, value)
) WITHOUT ROWID;
CREATE INDEX movie_tags_value_idx ON movie_tags (kind, value, movie_id);

CREATE TABLE show_tags (
    show_id INTEGER NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    kind    TEXT NOT NULL CHECK (kind IN ('rec', 'keyword', 'genre', 'person', 'creator', 'language')),
    value   TEXT NOT NULL,
    rank    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (show_id, kind, value)
) WITHOUT ROWID;
CREATE INDEX show_tags_value_idx ON show_tags (kind, value, show_id);

-- +goose Down
DROP TABLE show_tags;
DROP TABLE movie_tags;
DROP INDEX shows_related_checked_idx;
DROP INDEX movies_related_checked_idx;
DROP INDEX movies_collection_idx;
ALTER TABLE shows DROP COLUMN related_checked_at;
ALTER TABLE movies DROP COLUMN related_checked_at;
ALTER TABLE movies DROP COLUMN collection_name;
ALTER TABLE movies DROP COLUMN collection_id;
