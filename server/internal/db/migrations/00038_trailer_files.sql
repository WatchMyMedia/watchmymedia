-- +goose Up
-- Trailer files in the library (PLAN-TRAILERS.md §5.2). A trailer is a
-- media_files row, so playback, Cast and every player work on it by file
-- id, but it is not library content: extra_kind says what it is (NULL for
-- library content, 'trailer' for trailers) and extra_of which movie it
-- belongs to. Trailer rows have no movie_id and are never matched; every
-- query that reads media_files other than through movie_id or episode_files
-- leaves them out with extra_kind IS NULL. No CHECK on extra_kind, so later
-- kinds of extras need no rebuild of the table (match_status's CHECK does).
ALTER TABLE media_files ADD COLUMN extra_kind TEXT;
ALTER TABLE media_files ADD COLUMN extra_of INTEGER REFERENCES movies(id) ON DELETE SET NULL;

-- A movie's trailers, for its page.
CREATE INDEX media_files_extra_of_idx ON media_files (extra_of) WHERE extra_of IS NOT NULL;

-- The new columns come after the probe JSON in a row, which spills onto
-- overflow pages, so a filter on extra_kind read from the table would read
-- every probe (see media_files_trickplay_idx in 00026). These indexes hold
-- it instead, after their equality columns, so a seek on extra_kind IS NULL
-- skips trailers without reading their rows.
DROP INDEX media_files_library_status_idx;
CREATE INDEX media_files_library_status_idx ON media_files (library_id, status, extra_kind);
DROP INDEX media_files_match_idx;
CREATE INDEX media_files_match_idx ON media_files (library_id, match_status, extra_kind);
DROP INDEX media_files_trickplay_idx;
CREATE INDEX media_files_trickplay_idx ON media_files (status, extra_kind, first_seen_at, id, duration_ms, video_codec, size, mtime);

-- +goose Down
DROP INDEX media_files_trickplay_idx;
CREATE INDEX media_files_trickplay_idx ON media_files (status, first_seen_at, id, duration_ms, video_codec, size, mtime);
DROP INDEX media_files_match_idx;
CREATE INDEX media_files_match_idx ON media_files (library_id, match_status);
DROP INDEX media_files_library_status_idx;
CREATE INDEX media_files_library_status_idx ON media_files (library_id, status);
DROP INDEX media_files_extra_of_idx;
-- Older servers skip trailer files, apart from a bare trailer.mp4, which
-- they find again as library content.
DELETE FROM media_files WHERE extra_kind IS NOT NULL;
ALTER TABLE media_files DROP COLUMN extra_of;
ALTER TABLE media_files DROP COLUMN extra_kind;
