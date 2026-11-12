-- +goose Up
-- A connected Plex server, and which of its people and libraries are linked
-- to ours (PLAN-PLEX-SYNC.md, P2). Nothing here imports anything: sync stays
-- off (sync_enabled = 0) until the import itself exists (P3).
--
-- The connection's Plex grant, a device key, is a private file under the
-- data folder, not a value here: credential_ref names the file. Backups
-- clear credential_ref in their copy of the database, so a restored server
-- has to be connected again.
CREATE TABLE plex_connections (
    id                 INTEGER PRIMARY KEY,
    machine_identifier TEXT NOT NULL UNIQUE,
    -- The server's name in Plex, for people to recognise; not an identity.
    name               TEXT NOT NULL DEFAULT '',
    -- The address chosen when connecting; credentials go to no other.
    origin             TEXT NOT NULL,
    -- NULL after Plex withdrew the grant or after a restore: connect again.
    credential_ref     TEXT UNIQUE,
    -- The plex.tv user ID of the account that owns the server and signed in.
    owner_plex_id      INTEGER NOT NULL CHECK (owner_plex_id > 0),
    server_version     TEXT NOT NULL DEFAULT '',
    -- Whether the server let its owner read play history when connected.
    history_capability TEXT NOT NULL DEFAULT 'unknown' CHECK (history_capability IN ('available', 'unavailable', 'unknown')),
    -- The admin whose sign-in authorized the connection. Deleting them
    -- leaves it paused (NULL) until another admin connects it again.
    authorized_by      INTEGER REFERENCES users(id) ON DELETE SET NULL,
    -- Only ever goes up: with every change to the links or the grant, so
    -- a preview made under an older configuration is known to be stale.
    generation         INTEGER NOT NULL DEFAULT 1,
    sync_enabled       INTEGER NOT NULL DEFAULT 0 CHECK (sync_enabled IN (0, 1)),
    -- Why the connection can't be used now, as a reason code; '' if it can.
    problem            TEXT NOT NULL DEFAULT '',
    created_at         TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at         TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- A person on the Plex server linked to a local account. They are linked
-- by Plex user ID, never by name: the owner, an invited plex.tv account or
-- a managed Home profile, which has no plex.tv sign-in of its own.
CREATE TABLE plex_user_links (
    connection_id  INTEGER NOT NULL REFERENCES plex_connections(id) ON DELETE CASCADE,
    plex_user_id   INTEGER NOT NULL CHECK (plex_user_id > 0),
    kind           TEXT NOT NULL CHECK (kind IN ('owner', 'account', 'managed')),
    -- Their account in the Plex server's own history (/accounts), a separate
    -- namespace: the owner's is 1. NULL when the server doesn't list them.
    pms_account_id INTEGER CHECK (pms_account_id > 0),
    user_id        INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at     TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    PRIMARY KEY (connection_id, plex_user_id),
    -- One Plex person per local account; leading with user_id also serves
    -- the cascade when an account is deleted.
    UNIQUE (user_id, connection_id)
);

-- A Plex library section linked to a local library of the same type.
CREATE TABLE plex_library_links (
    connection_id INTEGER NOT NULL REFERENCES plex_connections(id) ON DELETE CASCADE,
    section_id    INTEGER NOT NULL CHECK (section_id > 0),
    -- The section's UUID when linked. Another UUID under the same ID means
    -- Plex made a new section, and the link no longer applies.
    section_uuid  TEXT NOT NULL,
    library_id    INTEGER NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
    PRIMARY KEY (connection_id, section_id)
);
CREATE INDEX plex_library_links_library_idx ON plex_library_links (library_id);

-- Preview matching resolves a Plex movie's IMDb ID within one library.
CREATE INDEX movies_library_imdb_idx ON movies (library_id, imdb_id);

-- Losing a link, including through deleting the account or library it
-- points at, or losing the admin who authorized the connection, makes
-- every preview made before it stale.
-- +goose StatementBegin
CREATE TRIGGER plex_user_links_deleted AFTER DELETE ON plex_user_links BEGIN
    UPDATE plex_connections SET generation = generation + 1 WHERE id = OLD.connection_id;
END;
-- +goose StatementEnd
-- +goose StatementBegin
CREATE TRIGGER plex_library_links_deleted AFTER DELETE ON plex_library_links BEGIN
    UPDATE plex_connections SET generation = generation + 1 WHERE id = OLD.connection_id;
END;
-- +goose StatementEnd
-- +goose StatementBegin
CREATE TRIGGER plex_connections_unauthorized AFTER UPDATE OF authorized_by ON plex_connections
WHEN NEW.authorized_by IS NULL AND OLD.authorized_by IS NOT NULL BEGIN
    UPDATE plex_connections SET generation = generation + 1 WHERE id = NEW.id;
END;
-- +goose StatementEnd

-- +goose Down
DROP TRIGGER plex_connections_unauthorized;
DROP TRIGGER plex_library_links_deleted;
DROP TRIGGER plex_user_links_deleted;
DROP INDEX movies_library_imdb_idx;
DROP TABLE plex_library_links;
DROP TABLE plex_user_links;
DROP TABLE plex_connections;
