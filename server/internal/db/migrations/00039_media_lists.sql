-- +goose Up
-- Lists that rows on Home and the library screens are made from
-- (PLAN-ROWS.md 7.2): from TMDB, MDBList, a saved library filter, or an
-- import. A list belongs to one person (owner_id), or to the household when
-- owner_id is NULL; deleting a person deletes their lists.
--
-- screen is where its row was made and first shows (home or library-<id>).
-- params holds the source's options as JSON. Fetched lists keep their last
-- good entries: fetched_at is the last fetch that worked, error and error_at
-- the last that failed, failures how many failed in a row (for the backoff),
-- next_fetch_at when the refresh job fetches it again (NULL: never, for
-- library lists). wanted_at is when a screen last showed its row or someone
-- opened it: the job leaves lists nobody looks at alone.
CREATE TABLE media_lists (
    id            INTEGER PRIMARY KEY,
    owner_id      INTEGER REFERENCES users(id) ON DELETE CASCADE,
    created_by    INTEGER REFERENCES users(id) ON DELETE SET NULL,
    screen        TEXT NOT NULL,
    name          TEXT NOT NULL,
    source_kind   TEXT NOT NULL,
    params        TEXT NOT NULL DEFAULT '{}',
    media_type    TEXT NOT NULL CHECK (media_type IN ('movie', 'tv', 'all')),
    entry_limit   INTEGER NOT NULL DEFAULT 50,
    source_url    TEXT NOT NULL DEFAULT '',
    fetched_at    TEXT,
    next_fetch_at TEXT,
    error         TEXT,
    error_at      TEXT,
    failures      INTEGER NOT NULL DEFAULT 0,
    wanted_at     TEXT,
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX media_lists_owner_idx ON media_lists (owner_id);
CREATE INDEX media_lists_created_by_idx ON media_lists (created_by);
CREATE INDEX media_lists_due_idx ON media_lists (next_fetch_at);

-- A list's titles in its order. tmdb_id is NULL for an imported title TMDB
-- could not find, which is kept with its title. Rows match entries to the
-- library's movies and shows on tmdb_id each time they are read.
CREATE TABLE media_list_entries (
    list_id     INTEGER NOT NULL REFERENCES media_lists(id) ON DELETE CASCADE,
    rank        INTEGER NOT NULL,
    media_type  TEXT NOT NULL CHECK (media_type IN ('movie', 'tv')),
    tmdb_id     INTEGER,
    imdb_id     TEXT,
    title       TEXT NOT NULL DEFAULT '',
    year        INTEGER,
    poster_path TEXT,
    PRIMARY KEY (list_id, rank)
);
CREATE INDEX media_list_entries_tmdb_idx ON media_list_entries (media_type, tmdb_id);

-- +goose Down
DROP TABLE media_list_entries;
DROP TABLE media_lists;
