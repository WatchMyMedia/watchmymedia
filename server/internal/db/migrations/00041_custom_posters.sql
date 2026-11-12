-- +goose Up
-- A poster an admin chose in place of the match's (PLAN-POSTERS.md).
-- poster_path stays the poster to show, so no query that reads it changes.
-- poster_source says where poster_path came from: '' for TMDB's own, 'tmdb'
-- for another of TMDB's posters, 'upload' or 'url' for a picture kept under
-- <data_dir>/artwork. While it is not '', a refresh leaves poster_path alone
-- and poster_tmdb holds TMDB's own poster to go back to; with no choice
-- poster_path is TMDB's and poster_tmdb is not read.
ALTER TABLE movies ADD COLUMN poster_tmdb TEXT;
ALTER TABLE movies ADD COLUMN poster_source TEXT NOT NULL DEFAULT '';
ALTER TABLE shows ADD COLUMN poster_tmdb TEXT;
ALTER TABLE shows ADD COLUMN poster_source TEXT NOT NULL DEFAULT '';
ALTER TABLE seasons ADD COLUMN poster_tmdb TEXT;
ALTER TABLE seasons ADD COLUMN poster_source TEXT NOT NULL DEFAULT '';

-- +goose Down
-- A server without these columns knows only TMDB's posters: put them back.
UPDATE movies SET poster_path = poster_tmdb WHERE poster_source <> '';
UPDATE shows SET poster_path = poster_tmdb WHERE poster_source <> '';
UPDATE seasons SET poster_path = poster_tmdb WHERE poster_source <> '';
ALTER TABLE movies DROP COLUMN poster_source;
ALTER TABLE movies DROP COLUMN poster_tmdb;
ALTER TABLE shows DROP COLUMN poster_source;
ALTER TABLE shows DROP COLUMN poster_tmdb;
ALTER TABLE seasons DROP COLUMN poster_source;
ALTER TABLE seasons DROP COLUMN poster_tmdb;
