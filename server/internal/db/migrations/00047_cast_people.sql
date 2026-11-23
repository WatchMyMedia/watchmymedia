-- +goose Up
-- The people behind a title's cast, so a cast member opens their movies and
-- shows in the library. A person is told apart by TMDB's person ID, never
-- by name: two people can share one.
--
-- movie_cast and show_cast are the same billed cast as the credits JSON the
-- pages draw (the top 12, in billing order), each with its person ID. The
-- key is the billing, not the person: TMDB sometimes lists one person twice
-- in a title, for two parts. The person index answers "which titles have
-- this person". Related titles' person tags stay as they are: they keep the
-- top 10 for scoring likeness and have no part.
CREATE TABLE movie_cast (
    movie_id     INTEGER NOT NULL REFERENCES movies(id) ON DELETE CASCADE,
    billing      INTEGER NOT NULL,
    person_id    INTEGER NOT NULL,
    name         TEXT NOT NULL,
    character    TEXT NOT NULL DEFAULT '',
    profile_path TEXT,
    PRIMARY KEY (movie_id, billing)
) WITHOUT ROWID;
CREATE INDEX movie_cast_person_idx ON movie_cast (person_id, movie_id);

CREATE TABLE show_cast (
    show_id      INTEGER NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    billing      INTEGER NOT NULL,
    person_id    INTEGER NOT NULL,
    name         TEXT NOT NULL,
    character    TEXT NOT NULL DEFAULT '',
    profile_path TEXT,
    PRIMARY KEY (show_id, billing)
) WITHOUT ROWID;
CREATE INDEX show_cast_person_idx ON show_cast (person_id, show_id);

-- A few of the people behind the camera, as JSON [{personId, name, job}]:
-- a movie's directors, writers, composers and producers, a show's
-- creators.
ALTER TABLE movies ADD COLUMN crew TEXT NOT NULL DEFAULT '[]';
ALTER TABLE shows ADD COLUMN crew TEXT NOT NULL DEFAULT '[]';

-- When the title's cast was last stored with person IDs. NULL: matched
-- before they were kept, so the background fill-in fetches its credits
-- again, one title at a time.
ALTER TABLE movies ADD COLUMN cast_checked_at TEXT;
ALTER TABLE shows ADD COLUMN cast_checked_at TEXT;
CREATE INDEX movies_cast_unchecked_idx ON movies (id) WHERE cast_checked_at IS NULL;
CREATE INDEX shows_cast_unchecked_idx ON shows (id) WHERE cast_checked_at IS NULL;

-- +goose Down
DROP INDEX shows_cast_unchecked_idx;
DROP INDEX movies_cast_unchecked_idx;
ALTER TABLE shows DROP COLUMN cast_checked_at;
ALTER TABLE movies DROP COLUMN cast_checked_at;
ALTER TABLE shows DROP COLUMN crew;
ALTER TABLE movies DROP COLUMN crew;
DROP TABLE show_cast;
DROP TABLE movie_cast;
