-- Plex connections and their links (PLAN-PLEX-SYNC.md, P2), and the exact
-- lookups that match Plex items to local ones.

-- name: ListPlexConnections :many
SELECT * FROM plex_connections ORDER BY id;

-- name: GetPlexConnection :one
SELECT * FROM plex_connections WHERE id = ?;

-- name: GetPlexConnectionByMachine :one
SELECT * FROM plex_connections WHERE machine_identifier = ?;

-- name: InsertPlexConnection :one
INSERT INTO plex_connections (machine_identifier, name, origin, credential_ref, owner_plex_id, server_version, history_capability, authorized_by)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
RETURNING *;

-- name: ReconnectPlexConnection :execrows
-- A new grant for a connected server, from the same Plex owner. The links
-- stay; the generation moves on.
UPDATE plex_connections SET
 name=sqlc.arg(name),origin=sqlc.arg(origin),credential_ref=sqlc.narg(credential_ref),
 server_version=sqlc.arg(server_version),history_capability=sqlc.arg(history_capability),
 authorized_by=sqlc.narg(authorized_by),enabled_by=sqlc.narg(authorized_by),problem='',generation=generation+1,
 updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now')
WHERE id=sqlc.arg(id) AND owner_plex_id=sqlc.arg(owner_plex_id);

-- name: ClearPlexCredential :exec
-- The grant is gone (Plex withdrew it, or it could not be read): the
-- connection waits for a new sign-in.
UPDATE plex_connections SET
    credential_ref = NULL, problem = ?, generation = generation + 1,
    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ? AND credential_ref = ?;

-- name: DeletePlexConnection :exec
DELETE FROM plex_connections WHERE id = ?;

-- name: AdvancePlexGeneration :execrows
-- Moves the generation on only from the one the caller last saw.
UPDATE plex_connections SET generation = generation + 1, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
WHERE id = ? AND generation = ?;

-- name: ListPlexCredentialRefs :many
SELECT credential_ref FROM plex_connections WHERE credential_ref IS NOT NULL;

-- name: ListPlexUserLinks :many
SELECT * FROM plex_user_links WHERE connection_id = ? ORDER BY plex_user_id;

-- name: DeletePlexUserLinks :exec
DELETE FROM plex_user_links WHERE connection_id = ?;

-- name: InsertPlexUserLink :exec
INSERT INTO plex_user_links (connection_id, plex_user_id, kind, pms_account_id, user_id)
VALUES (?, ?, ?, ?, ?);

-- name: ListPlexLibraryLinks :many
SELECT l.connection_id, l.section_id, l.section_uuid, l.library_id, lib.type AS library_type
FROM plex_library_links l
JOIN libraries lib ON lib.id = l.library_id
WHERE l.connection_id = ?
ORDER BY l.section_id;

-- name: DeletePlexLibraryLinks :exec
DELETE FROM plex_library_links WHERE connection_id = ?;

-- name: InsertPlexLibraryLink :exec
INSERT INTO plex_library_links (connection_id, section_id, section_uuid, library_id)
VALUES (?, ?, ?, ?);

-- name: MatchMovieByTMDB :many
-- The library's movie with this TMDB ID (UNIQUE (library_id, tmdb_id)).
SELECT id FROM movies WHERE library_id = ? AND tmdb_id = ?;

-- name: MatchMoviesByIMDb :many
SELECT id, tmdb_id FROM movies WHERE library_id = ? AND imdb_id = ?;

-- name: MatchEpisode :many
-- The library's episode for a show's TMDB ID and its season and episode
-- numbers, each step through a unique index: show, then season, then
-- episode.
SELECT e.id
FROM shows sh
CROSS JOIN seasons se
CROSS JOIN episodes e
WHERE sh.library_id = ? AND sh.tmdb_id = ?
  AND se.show_id = sh.id AND se.number = sqlc.arg(season)
  AND e.season_id = se.id AND e.number = sqlc.arg(episode);
