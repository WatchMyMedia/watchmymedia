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

-- episode_arrivals has one row per TMDB episode (the show's TMDB id, the
-- season and episode number) that has ever had a file in any library,
-- made the first time a file is linked to it (Matcher.record, section
-- 5.2). It belongs to the TMDB episode, not to an episode row, so a copy in
-- a second library (a 4K one) is not another arrival, and the row
-- outlives a library removed and added again: episode_id is the row it
-- arrived on, or the next one linked once that row is gone. A quiet row,
-- made by a file older than the New badge's week, is never announced. The
-- id counts up and is never reused (AUTOINCREMENT), so it is the order of
-- arrival and every device's cursor is one. Follows are by TMDB id, so the
-- feed reads (tmdb_id, id).
CREATE TABLE episode_arrivals (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    tmdb_id    INTEGER NOT NULL,
    season     INTEGER NOT NULL,
    number     INTEGER NOT NULL,
    episode_id INTEGER REFERENCES episodes(id) ON DELETE SET NULL,
    arrived_at TEXT NOT NULL,
    quiet      INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX episode_arrivals_episode_idx ON episode_arrivals (tmdb_id, season, number);
CREATE INDEX episode_arrivals_follow_idx ON episode_arrivals (tmdb_id, id);
-- Deleting an episode finds its arrival by this, rather than reading them
-- all, when a library is removed.
CREATE INDEX episode_arrivals_episode_id_idx ON episode_arrivals (episode_id);

-- Nobody is told about the library as it stands: every TMDB episode with
-- a link in any library has arrived, quietly, on the row linked first and
-- in the order the files came. (With one MIN(), SQLite takes e.id from the
-- row that has it.)
INSERT INTO episode_arrivals (tmdb_id, season, number, episode_id, arrived_at, quiet)
SELECT s.tmdb_id, se.number, e.number, e.id, MIN(COALESCE(NULLIF(f.added_at, ''), f.first_seen_at)) AS arrived, 1
FROM episodes e
JOIN seasons se ON se.id = e.season_id
JOIN shows s ON s.id = e.show_id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id
GROUP BY s.tmdb_id, se.number, e.number
ORDER BY arrived, s.tmdb_id, se.number, e.number;

-- Mark show watched and Mark season watched marked every episode TMDB
-- listed, including ones that hadn't aired, so a new episode came already
-- watched. They now leave those alone; clear the marks they made that
-- can't be true: on an episode no library has ever had a file for (its
-- TMDB episode has no arrival) that hadn't aired by the day of the mark
-- (no air date, or a later one), and with the mark's guard, so an import
-- may mark it later. Imported history (an import's guard) is left as it
-- is. Going down doesn't put these marks back.
DELETE FROM watch_guards
WHERE owner = 'local' AND item_type = 'episode' AND EXISTS (
    SELECT 1 FROM watch_state w
    JOIN episodes e ON e.id = w.item_id
    JOIN seasons se ON se.id = e.season_id
    JOIN shows s ON s.id = e.show_id
    WHERE w.user_id = watch_guards.user_id AND w.item_type = 'episode' AND w.item_id = watch_guards.item_id
      AND w.completed = 1
      AND (e.air_date IS NULL OR e.air_date = '' OR e.air_date > substr(w.updated_at, 1, 10))
      AND NOT EXISTS (SELECT 1 FROM episode_arrivals a WHERE a.tmdb_id = s.tmdb_id AND a.season = se.number AND a.number = e.number));

DELETE FROM watch_state
WHERE item_type = 'episode' AND completed = 1
  AND NOT EXISTS (SELECT 1 FROM watch_guards g
      WHERE g.user_id = watch_state.user_id AND g.item_type = 'episode' AND g.item_id = watch_state.item_id AND g.owner = 'import')
  AND EXISTS (
    SELECT 1 FROM episodes e
    JOIN seasons se ON se.id = e.season_id
    JOIN shows s ON s.id = e.show_id
    WHERE e.id = watch_state.item_id
      AND (e.air_date IS NULL OR e.air_date = '' OR e.air_date > substr(watch_state.updated_at, 1, 10))
      AND NOT EXISTS (SELECT 1 FROM episode_arrivals a WHERE a.tmdb_id = s.tmdb_id AND a.season = se.number AND a.number = e.number));

-- +goose Down
DROP TABLE episode_arrivals;
DROP TABLE show_follows;
