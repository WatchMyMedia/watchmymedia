-- +goose Up
-- When a file counts as added: its modified date on disk when a scan first
-- found it, or that scan's time if the date is later. Rows like "Recently
-- added" sort by this, so a first scan of a whole library keeps the order
-- the files arrived in instead of the order the scan happened to read them.
ALTER TABLE media_files ADD COLUMN added_at TEXT NOT NULL DEFAULT '';
UPDATE media_files SET added_at = MIN(mtime, first_seen_at);
CREATE INDEX media_files_added_idx ON media_files (library_id, status, added_at);

-- +goose Down
DROP INDEX media_files_added_idx;
ALTER TABLE media_files DROP COLUMN added_at;
