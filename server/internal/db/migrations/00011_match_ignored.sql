-- +goose Up
-- Set when a person says a file is not library content (a sample, an extra,
-- a stray clip). Matching skips it and it leaves the unmatched count; a
-- person matching it by hand clears the flag.
ALTER TABLE media_files ADD COLUMN match_ignored INTEGER NOT NULL DEFAULT 0;

-- +goose Down
ALTER TABLE media_files DROP COLUMN match_ignored;
