-- +goose Up
-- Watchlists: the movies and shows a person means to watch. Each is one of
-- their own lists, of source kind watchlist, never fetched (next_fetch_at
-- stays NULL), so rows, layouts and the list page carry it as they carry
-- any list.
--
-- remove_watched is its "Remove titles once watched" switch: on, a title
-- leaves once the person finishes it after saving it. Other lists keep 0.
--
-- added_at is when a title was saved to a watchlist; entries of other
-- lists have none. The partial index holds a title on a watchlist once,
-- and leaves other lists alone: an import may hold a title twice.
ALTER TABLE media_lists ADD COLUMN remove_watched INTEGER NOT NULL DEFAULT 0;
ALTER TABLE media_list_entries ADD COLUMN added_at TEXT;
CREATE UNIQUE INDEX media_list_entries_saved_idx ON media_list_entries (list_id, media_type, tmdb_id)
    WHERE added_at IS NOT NULL;

-- +goose Down
DROP INDEX media_list_entries_saved_idx;
ALTER TABLE media_list_entries DROP COLUMN added_at;
ALTER TABLE media_lists DROP COLUMN remove_watched;
