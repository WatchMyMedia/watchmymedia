-- +goose Up
ALTER TABLE shows ADD COLUMN last_air_date TEXT;
ALTER TABLE shows ADD COLUMN next_air_date TEXT;
-- Top-billed cast as JSON [{name, character, profilePath}].
ALTER TABLE shows ADD COLUMN credits TEXT NOT NULL DEFAULT '[]';
ALTER TABLE movies ADD COLUMN credits TEXT NOT NULL DEFAULT '[]';
-- HDR kind of the main video stream ('', 'hdr10', 'hlg', 'dolbyvision'), from ffprobe.
ALTER TABLE media_files ADD COLUMN hdr TEXT NOT NULL DEFAULT '';

CREATE INDEX episodes_show_air_date_idx ON episodes (show_id, air_date);
CREATE INDEX media_files_first_seen_idx ON media_files (library_id, status, first_seen_at);

-- +goose Down
DROP INDEX media_files_first_seen_idx;
DROP INDEX episodes_show_air_date_idx;
ALTER TABLE media_files DROP COLUMN hdr;
ALTER TABLE movies DROP COLUMN credits;
ALTER TABLE shows DROP COLUMN credits;
ALTER TABLE shows DROP COLUMN next_air_date;
ALTER TABLE shows DROP COLUMN last_air_date;
