-- +goose Up
-- Activity: every play of a file, who made it, on what, and how it streamed.
-- See PLAN.md §10b.

-- Whether a user's plays are kept in history (Now playing shows them either way).
ALTER TABLE users ADD COLUMN record_history INTEGER NOT NULL DEFAULT 1;

-- A browser or app a user plays on. device_key is the client's own ID
-- (random and kept in localStorage on the web), or a hash of the user agent
-- for clients that send none.
CREATE TABLE devices (
    id            INTEGER PRIMARY KEY,
    user_id       INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_key    TEXT NOT NULL,
    name          TEXT NOT NULL DEFAULT '',
    product       TEXT NOT NULL DEFAULT '',
    platform      TEXT NOT NULL DEFAULT '',
    version       TEXT NOT NULL DEFAULT '',
    first_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_seen_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_ip       TEXT NOT NULL DEFAULT '',
    UNIQUE (user_id, device_key)
);

-- One row per movie, episode or unmatched file ever played: a snapshot of
-- its names and artwork that outlives deleting or re-matching the item.
-- item_id is the movie, episode or media file ID at the time.
CREATE TABLE history_items (
    id             INTEGER PRIMARY KEY,
    item_type      TEXT NOT NULL CHECK (item_type IN ('movie', 'episode', 'file')),
    item_id        INTEGER NOT NULL,
    movie_id       INTEGER,
    show_id        INTEGER,
    library_id     INTEGER,
    title          TEXT NOT NULL,
    show_title     TEXT NOT NULL DEFAULT '',
    season_number  INTEGER,
    episode_number INTEGER,
    year           INTEGER,
    poster_path    TEXT NOT NULL DEFAULT '',
    art_path       TEXT NOT NULL DEFAULT '',
    updated_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    UNIQUE (item_type, item_id)
);
CREATE INDEX history_items_movie_idx ON history_items (movie_id);
CREATE INDEX history_items_show_idx ON history_items (show_id);

-- Trigrams match any substring of three or more characters, as LIKE '%x%'
-- does, but through an index.
CREATE VIRTUAL TABLE history_items_fts USING fts5 (
    title, show_title,
    content = 'history_items', content_rowid = 'id',
    tokenize = 'trigram remove_diacritics 1'
);

-- +goose StatementBegin
CREATE TRIGGER history_items_ai AFTER INSERT ON history_items BEGIN
    INSERT INTO history_items_fts (rowid, title, show_title) VALUES (new.id, new.title, new.show_title);
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER history_items_ad AFTER DELETE ON history_items BEGIN
    INSERT INTO history_items_fts (history_items_fts, rowid, title, show_title) VALUES ('delete', old.id, old.title, old.show_title);
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER history_items_au AFTER UPDATE OF title, show_title ON history_items BEGIN
    INSERT INTO history_items_fts (history_items_fts, rowid, title, show_title) VALUES ('delete', old.id, old.title, old.show_title);
    INSERT INTO history_items_fts (rowid, title, show_title) VALUES (new.id, new.title, new.show_title);
END;
-- +goose StatementEnd

-- One viewing of one file on one device by one user, from the playback
-- request until stop, close or timeout. A live play has stopped_at NULL and
-- no group yet. When it ends it joins the previous play of the same item by
-- the same user if that one was not finished and this one picks up where it
-- left off; group_id is the group's first row, and only the newest row of a
-- group has group_latest = 1, carrying the group's start, played time and
-- count, so the grouped history is a plain index scan.
CREATE TABLE plays (
    id                INTEGER PRIMARY KEY,
    public_id         TEXT NOT NULL UNIQUE,
    user_id           INTEGER REFERENCES users(id) ON DELETE SET NULL,
    user_name         TEXT NOT NULL,
    device_id         INTEGER REFERENCES devices(id) ON DELETE SET NULL,
    history_item_id   INTEGER NOT NULL REFERENCES history_items(id),
    media_file_id     INTEGER REFERENCES media_files(id) ON DELETE SET NULL,
    library_id        INTEGER,
    item_type         TEXT NOT NULL,
    show_id           INTEGER,

    state             TEXT NOT NULL DEFAULT 'playing' CHECK (state IN ('playing', 'paused', 'buffering', 'stopped')),
    started_at        TEXT NOT NULL,
    stopped_at        TEXT,
    last_seen_at      TEXT NOT NULL,
    played_ms         INTEGER NOT NULL DEFAULT 0,
    paused_ms         INTEGER NOT NULL DEFAULT 0,
    buffering_ms      INTEGER NOT NULL DEFAULT 0,
    start_position_ms INTEGER NOT NULL DEFAULT 0,
    end_position_ms   INTEGER NOT NULL DEFAULT 0,
    max_position_ms   INTEGER NOT NULL DEFAULT 0,
    duration_ms       INTEGER NOT NULL DEFAULT 0,
    completed         INTEGER NOT NULL DEFAULT 0,
    end_reason        TEXT CHECK (end_reason IN ('stopped', 'ended', 'closed', 'timeout', 'terminated')),

    -- How it streamed, as last decided: direct, remux, audio or transcode,
    -- with the reasons a cheaper mode did not work and JSON summaries of
    -- the source file and of what the client received.
    mode              TEXT NOT NULL,
    reasons           TEXT NOT NULL DEFAULT '[]',
    source            TEXT NOT NULL DEFAULT '{}',
    stream            TEXT NOT NULL DEFAULT '{}',
    encoder           TEXT NOT NULL DEFAULT '',
    quality           TEXT NOT NULL DEFAULT '',
    bytes_sent        INTEGER NOT NULL DEFAULT 0,

    ip                TEXT NOT NULL DEFAULT '',
    network           TEXT NOT NULL DEFAULT 'local' CHECK (network IN ('local', 'tailnet', 'remote')),

    -- When it started in the server's time zone, for charts by day and hour.
    local_day         TEXT NOT NULL,
    local_hour        INTEGER NOT NULL,
    local_weekday     INTEGER NOT NULL, -- 0 is Sunday

    group_id          INTEGER,
    group_latest      INTEGER NOT NULL DEFAULT 0,
    group_started_at  TEXT,
    group_played_ms   INTEGER NOT NULL DEFAULT 0,
    group_count       INTEGER NOT NULL DEFAULT 1
);
-- The grouped history and its searches read these partial indexes; each
-- carries group_played_ms so a capped count and its total time need no rows.
CREATE INDEX plays_latest_started_idx ON plays (started_at, id, group_played_ms) WHERE group_latest = 1;
CREATE INDEX plays_latest_item_idx ON plays (history_item_id, started_at, id, group_played_ms) WHERE group_latest = 1;
CREATE INDEX plays_latest_ip_idx ON plays (ip, started_at, id, group_played_ms) WHERE group_latest = 1;
CREATE INDEX plays_started_idx ON plays (started_at, id);
CREATE INDEX plays_user_idx ON plays (user_id, group_latest, started_at, group_played_ms);
CREATE INDEX plays_device_idx ON plays (device_id, group_latest, started_at, group_played_ms);
CREATE INDEX plays_group_idx ON plays (group_id);
CREATE INDEX plays_day_idx ON plays (local_day);
CREATE INDEX plays_open_idx ON plays (stopped_at) WHERE stopped_at IS NULL;
-- Covers the statistics (top items, users and devices, peak concurrent
-- streams) so they never read rows.
CREATE INDEX plays_stats_idx ON plays (started_at, user_id, history_item_id, show_id, device_id, item_type, mode, played_ms, group_latest, stopped_at, library_id);

-- Plays and played time per day, for charts and totals over long periods.
-- Rebuilt for a day whenever one of its plays ends or is deleted; trimming
-- old plays leaves it alone, so statistics outlive history. user_id 0 is a
-- deleted user.
CREATE TABLE activity_daily (
    local_day TEXT NOT NULL,
    user_id   INTEGER NOT NULL,
    item_type TEXT NOT NULL,
    mode      TEXT NOT NULL,
    network   TEXT NOT NULL,
    plays     INTEGER NOT NULL DEFAULT 0,
    played_ms INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (local_day, user_id, item_type, mode, network)
) WITHOUT ROWID;
CREATE INDEX activity_daily_user_idx ON activity_daily (user_id, local_day, plays, played_ms);

-- The same by month (YYYY-MM), rebuilt from the daily totals, for charts and
-- totals over years.
CREATE TABLE activity_monthly (
    local_month TEXT NOT NULL,
    user_id     INTEGER NOT NULL,
    item_type   TEXT NOT NULL,
    mode        TEXT NOT NULL,
    network     TEXT NOT NULL,
    plays       INTEGER NOT NULL DEFAULT 0,
    played_ms   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (local_month, user_id, item_type, mode, network)
) WITHOUT ROWID;
CREATE INDEX activity_monthly_user_idx ON activity_monthly (user_id, local_month, plays, played_ms);

-- +goose Down
DROP TABLE activity_monthly;
DROP TABLE activity_daily;
DROP TABLE plays;
DROP TRIGGER history_items_au;
DROP TRIGGER history_items_ad;
DROP TRIGGER history_items_ai;
DROP TABLE history_items_fts;
DROP TABLE history_items;
DROP TABLE devices;
ALTER TABLE users DROP COLUMN record_history;
