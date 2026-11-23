-- +goose Up
-- Two small indexes for what the statistics and the files list count.
--
-- Why plays were converted: the statistics read the reasons of a period's
-- converted plays, which plays_stats_idx does not hold. Without this the
-- period's rows were read one by one (55 ms for a year of 50,000 plays);
-- this partial index holds only converted plays, each group's newest, with
-- everything the count reads (10 ms).
CREATE INDEX plays_conversions_idx ON plays (started_at, user_id, library_id, group_latest, mode, quality, auto, reasons)
    WHERE group_latest = 1 AND mode != 'direct';

-- Files ffprobe could not read: probe_error sits after the probe in a row,
-- so finding them without an index reads every file's probe. Few files are
-- unreadable, so the index is small, in the files list's order.
CREATE INDEX media_files_unreadable_idx ON media_files (library_id, path) WHERE probe_error IS NOT NULL;

-- +goose Down
DROP INDEX media_files_unreadable_idx;
DROP INDEX plays_conversions_idx;
