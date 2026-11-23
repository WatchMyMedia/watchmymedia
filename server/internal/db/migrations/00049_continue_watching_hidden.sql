-- +goose Up
-- Removing a title from Continue watching keeps its place. hidden_at is
-- when the person removed it (NULL: shown); Continue watching leaves out
-- a row that has it. A progress report here, live or from offline, clears
-- it, so playing the title again brings it back; an import does not. The
-- flag lives with the place it hides: marking the title unwatched deletes
-- the row, and a finished title is off the list anyway.
ALTER TABLE watch_state ADD COLUMN hidden_at TEXT;

-- +goose Down
ALTER TABLE watch_state DROP COLUMN hidden_at;
