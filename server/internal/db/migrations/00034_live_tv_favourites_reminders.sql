-- Live TV favourites, reminders and listings search (PLAN-LIVE-TV-GUIDE.md
-- §5.7, G6). Favourites and reminders belong to a person and name the
-- channel by its number, not its id: removing and re-adding a tuner gives
-- its channels new ids and would otherwise lose them. A reminder keeps its
-- own copy of the programme, since every refresh replaces
-- live_tv_programmes; the refresh moves a reminder whose programme moved
-- and marks one whose programme is gone. search holds a programme's title
-- and episode title folded as library names are (metadata.Normalize), so
-- what is typed matches whatever case or accent form the feed used.

-- +goose Up
CREATE TABLE live_tv_favourites (
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 number TEXT NOT NULL,
 -- Unix seconds.
 created_at INTEGER NOT NULL,
 PRIMARY KEY (user_id, number)
);
CREATE TABLE live_tv_reminders (
 id TEXT PRIMARY KEY,
 user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 number TEXT NOT NULL,
 -- The listing it was set from, or the one a refresh moved it to.
 programme_id TEXT NOT NULL,
 -- Unix seconds.
 start_at INTEGER NOT NULL,
 end_at INTEGER NOT NULL,
 title TEXT NOT NULL,
 episode_title TEXT NOT NULL DEFAULT '',
 season INTEGER,
 episode INTEGER,
 series_key TEXT NOT NULL DEFAULT '',
 -- 'set'; 'moved' when a refresh moved the programme; 'gone' when it is no
 -- longer listed.
 state TEXT NOT NULL DEFAULT 'set',
 created_at INTEGER NOT NULL,
 UNIQUE (user_id, programme_id)
);
CREATE INDEX live_tv_reminder_user ON live_tv_reminders(user_id, start_at);
CREATE INDEX live_tv_reminder_end ON live_tv_reminders(end_at);
ALTER TABLE live_tv_programmes ADD COLUMN search TEXT NOT NULL DEFAULT '';

-- +goose Down
ALTER TABLE live_tv_programmes DROP COLUMN search;
DROP TABLE live_tv_reminders;
DROP TABLE live_tv_favourites;
