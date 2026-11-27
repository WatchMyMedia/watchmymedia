-- +goose Up
-- How much later (or, negative, earlier) a person's subtitles show for one
-- track of one file, so every device of theirs plays it in step. A row with
-- no user is the timing an admin saved for everyone who plays the file; a
-- person's own row wins over it, 0 included: an own 0 over a shared timing
-- is that person's "in step". A shared row is never 0: saving 0 for
-- everyone deletes it.
--
-- track_key names the track so that it outlives what renumbers its id:
-- s<n> for the stream at ffprobe index n inside the file, x:<file name>
-- for a subtitle file beside it (the x<n> ids count the files beside the
-- video, so one more file moves them), d<id> for a downloaded one.
-- file_size is the file's size when the timing was saved: a file replaced
-- by one of another size has its streams elsewhere, so its timings are no
-- longer read, and saving again replaces them.
CREATE TABLE subtitle_timing (
    user_id    INTEGER REFERENCES users(id) ON DELETE CASCADE,
    file_id    INTEGER NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
    track_key  TEXT NOT NULL,
    timing_ms  INTEGER NOT NULL CHECK (timing_ms BETWEEN -60000 AND 60000 AND (timing_ms != 0 OR user_id IS NOT NULL)),
    file_size  INTEGER NOT NULL,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
-- One row a person and one shared row for each track. A UNIQUE constraint
-- takes NULLs as all different, so the shared row has an index of its own.
CREATE UNIQUE INDEX subtitle_timing_person_idx ON subtitle_timing (file_id, track_key, user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX subtitle_timing_shared_idx ON subtitle_timing (file_id, track_key) WHERE user_id IS NULL;
-- A person's rows for a file, which a play reads, and all of them, which
-- deleting the person deletes.
CREATE INDEX subtitle_timing_user_idx ON subtitle_timing (user_id, file_id) WHERE user_id IS NOT NULL;

-- +goose Down
DROP TABLE subtitle_timing;
