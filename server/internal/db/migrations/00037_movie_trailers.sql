-- +goose Up
-- A movie's trailers from TMDB (PLAN-TRAILERS.md §5.1): the best three of
-- its YouTube trailers and teasers, as JSON, best first. They are written
-- when the movie is matched and refreshed by a background job.
-- trailers_checked_at is when TMDB was last asked; NULL means never, so the
-- job asks for every movie matched before this column.
ALTER TABLE movies ADD COLUMN trailers TEXT NOT NULL DEFAULT '[]';
ALTER TABLE movies ADD COLUMN trailers_checked_at TEXT;

-- +goose Down
ALTER TABLE movies DROP COLUMN trailers_checked_at;
ALTER TABLE movies DROP COLUMN trailers;
