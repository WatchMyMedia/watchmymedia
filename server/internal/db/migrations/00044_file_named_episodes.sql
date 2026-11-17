-- +goose Up
-- Episodes and seasons named after a file, for a file whose show is known
-- but whose season or episode TMDB does not list (yet): Cunk on Cinema's
-- streaming release has six episodes where TMDB lists the BBC's three. The
-- file links to such an episode so it plays, keeps its watch state and sits
-- in order on the show's page, while it stays unmatched: listed for review
-- and tried on TMDB each day. When TMDB lists the episode, writing the
-- season fills in this same row (file_named goes back to 0), so the file's
-- link and everyone's watch state stay. One that loses its last file is
-- removed (Matcher.record, and the sweep after each match run).
--
-- The partial indexes let the sweep find the few file-named rows of a
-- library without reading every episode.
ALTER TABLE episodes ADD COLUMN file_named INTEGER NOT NULL DEFAULT 0;
ALTER TABLE seasons ADD COLUMN file_named INTEGER NOT NULL DEFAULT 0;
CREATE INDEX episodes_file_named_idx ON episodes (show_id) WHERE file_named = 1;
CREATE INDEX seasons_file_named_idx ON seasons (show_id) WHERE file_named = 1;

-- +goose Down
DROP INDEX seasons_file_named_idx;
DROP INDEX episodes_file_named_idx;
ALTER TABLE seasons DROP COLUMN file_named;
ALTER TABLE episodes DROP COLUMN file_named;
