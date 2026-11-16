-- +goose Up
-- Following shows and the episodes that arrive for them
-- (PLAN-NEW-EPISODES.md section 5.1).
--
-- show_follows is each person's list, keyed by the show's TMDB id: a show's
-- row belongs to one library and can be made again, the TMDB id survives
-- that, and a show in two libraries is followed once. title is kept so the
-- list can name a show that has left the library. since_arrival is the
-- newest arrival's id when the person followed: only later arrivals count.
CREATE TABLE show_follows (
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tmdb_id       INTEGER NOT NULL,
    title         TEXT NOT NULL,
    since_arrival INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (user_id, tmdb_id)
);

-- episode_arrivals has one row per episode that has ever had a file: the
-- first time a file that was never linked before is linked to it
-- (Matcher.record, section 5.2). quiet rows are never announced. The id
-- counts up and is never reused (AUTOINCREMENT), so it is the order of
-- arrival and every device's cursor is one.
CREATE TABLE episode_arrivals (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    episode_id INTEGER NOT NULL UNIQUE REFERENCES episodes(id) ON DELETE CASCADE,
    show_id    INTEGER NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    arrived_at TEXT NOT NULL,
    quiet      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX episode_arrivals_show_idx ON episode_arrivals (show_id, id);

-- When the file was first linked to an episode; empty until then. A file
-- linked before is a better copy, a rename or a file moved by a rematch,
-- never an arrival.
ALTER TABLE media_files ADD COLUMN first_linked_at TEXT;

-- Nobody is told about the library as it stands: every episode with a link
-- has arrived, quietly, in the order its files came, and every linked file
-- has been linked.
INSERT INTO episode_arrivals (episode_id, show_id, arrived_at, quiet)
SELECT e.id, e.show_id, MIN(COALESCE(NULLIF(f.added_at, ''), f.first_seen_at)) AS arrived, 1
FROM episodes e
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id
GROUP BY e.id
ORDER BY arrived, e.id;

UPDATE media_files SET first_linked_at = COALESCE(matched_at, NULLIF(added_at, ''), first_seen_at)
WHERE id IN (SELECT media_file_id FROM episode_files);

-- +goose Down
ALTER TABLE media_files DROP COLUMN first_linked_at;
DROP TABLE episode_arrivals;
DROP TABLE show_follows;
