-- +goose Up
-- plays, watch_state and problems point at media files ON DELETE SET NULL
-- but had no index on media_file_id, so deleting each file read those tables
-- whole: removing a library of 4,000 files with 20,000 plays took 10 s, and
-- 0.11 s with these indexes. Lookups by TMDB ID across libraries
-- (GetLibraryMovieByTMDB and the others in queries/requests.sql) read every
-- movie or show, since UNIQUE (library_id, tmdb_id) puts tmdb_id second.
CREATE INDEX plays_media_file_idx ON plays (media_file_id);
CREATE INDEX watch_state_media_file_idx ON watch_state (media_file_id);
CREATE INDEX problems_media_file_idx ON problems (media_file_id);
CREATE INDEX movies_tmdb_idx ON movies (tmdb_id);
CREATE INDEX shows_tmdb_idx ON shows (tmdb_id);

-- +goose Down
DROP INDEX shows_tmdb_idx;
DROP INDEX movies_tmdb_idx;
DROP INDEX problems_media_file_idx;
DROP INDEX watch_state_media_file_idx;
DROP INDEX plays_media_file_idx;
