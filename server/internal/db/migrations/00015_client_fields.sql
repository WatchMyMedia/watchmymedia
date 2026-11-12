-- +goose Up
-- Certification from TMDB (TV-14, R) for the United States. NULL means not
-- looked up yet, so titles matched before this column get it on the next
-- match run; '' means TMDB has none.
ALTER TABLE movies ADD COLUMN content_rating TEXT;
ALTER TABLE shows ADD COLUMN content_rating TEXT;
-- Dolby Vision profile of the main video stream (5, 7, 8…), from ffprobe's
-- DOVI configuration record. NULL for other files, and for Dolby Vision
-- files probed before it was read, which the next scan probes again; 0 when
-- the record has none.
ALTER TABLE media_files ADD COLUMN dv_profile INTEGER;
-- The device's model ("Pixel 9"), as the app reports it at sign-in.
ALTER TABLE sessions ADD COLUMN model TEXT NOT NULL DEFAULT '';

-- +goose Down
ALTER TABLE sessions DROP COLUMN model;
ALTER TABLE media_files DROP COLUMN dv_profile;
ALTER TABLE shows DROP COLUMN content_rating;
ALTER TABLE movies DROP COLUMN content_rating;
