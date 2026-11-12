-- +goose Up
-- Local watch choices must survive imports from another server
-- (PLAN-PLEX-SYNC.md, "Additive merge rules"). An unwatch deletes its
-- watch_state row, so each choice keeps a guard of its own: per user and
-- item, who decided it last. Every local change (a play, a mark, an unwatch,
-- a season or show action) takes the next revision and makes its guards
-- local; an import may only fill an item that has no guard at all.
CREATE TABLE watch_revision (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    last      INTEGER NOT NULL
);

CREATE TABLE watch_guards (
    user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_type  TEXT NOT NULL CHECK (item_type IN ('movie', 'episode')),
    item_id    INTEGER NOT NULL,
    -- local: the user's own play, mark or unwatch decides the item.
    -- import: an import wrote its state and nothing local has touched it since.
    owner      TEXT NOT NULL CHECK (owner IN ('local', 'import')),
    revision   INTEGER NOT NULL,
    -- The import run that applied an import-owned mark, so undoing that run
    -- removes only what it still owns. No foreign key: run details expire
    -- long before the mark does.
    import_run INTEGER,
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (user_id, item_type, item_id),
    CHECK ((owner = 'import') = (import_run IS NOT NULL))
);
-- Rematches and deletions find every user's state and guards on one item.
CREATE INDEX watch_guards_item_idx ON watch_guards (item_type, item_id);
CREATE INDEX watch_state_item_idx ON watch_state (item_type, item_id);

-- State on movies and episodes that no longer exist (a deleted library's)
-- is shown nowhere, but a new item that reuses the ID would inherit it.
-- Drop it; the triggers below delete state and guards with their item.
DELETE FROM watch_state WHERE item_type = 'movie' AND item_id NOT IN (SELECT id FROM movies);
DELETE FROM watch_state WHERE item_type = 'episode' AND item_id NOT IN (SELECT id FROM episodes);

-- Everything watched so far was watched here.
INSERT INTO watch_revision VALUES (1, 1);
INSERT INTO watch_guards (user_id, item_type, item_id, owner, revision, updated_at)
SELECT user_id, item_type, item_id, 'local', 1, updated_at FROM watch_state;

-- +goose StatementBegin
CREATE TRIGGER movies_delete_watch AFTER DELETE ON movies BEGIN
    DELETE FROM watch_state WHERE item_type = 'movie' AND item_id = OLD.id;
    DELETE FROM watch_guards WHERE item_type = 'movie' AND item_id = OLD.id;
END;
-- +goose StatementEnd

-- +goose StatementBegin
CREATE TRIGGER episodes_delete_watch AFTER DELETE ON episodes BEGIN
    DELETE FROM watch_state WHERE item_type = 'episode' AND item_id = OLD.id;
    DELETE FROM watch_guards WHERE item_type = 'episode' AND item_id = OLD.id;
END;
-- +goose StatementEnd

-- +goose Down
-- Watch state stays as it is; the orphaned rows the Up dropped stay gone.
DROP TRIGGER episodes_delete_watch;
DROP TRIGGER movies_delete_watch;
DROP INDEX watch_state_item_idx;
DROP TABLE watch_guards;
DROP TABLE watch_revision;
