-- name: UpsertSeasonMap :exec
INSERT INTO season_maps (library_id, folder, from_season, from_episode, tmdb_id, to_season, to_episode)
VALUES (?, ?, ?, ?, ?, ?, ?)
ON CONFLICT (library_id, folder, from_season, from_episode) DO UPDATE SET tmdb_id = excluded.tmdb_id,
    to_season = excluded.to_season, to_episode = excluded.to_episode,
    created_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now');

-- name: GetSeasonMap :one
-- The line-up a file numbered season and episode uses: the one for its
-- season that starts latest at or before its episode.
SELECT * FROM season_maps
WHERE library_id = sqlc.arg(library_id) AND folder = sqlc.arg(folder)
  AND from_season = sqlc.arg(season) AND from_episode <= sqlc.arg(episode)
ORDER BY from_episode DESC LIMIT 1;

-- name: ListFolderSeasonMaps :many
SELECT * FROM season_maps WHERE library_id = ? AND folder = ? ORDER BY from_season, from_episode;

-- name: ListSeasonMaps :many
SELECT * FROM season_maps WHERE library_id = ? ORDER BY folder, from_season, from_episode;

-- name: DeleteSeasonMap :execrows
DELETE FROM season_maps WHERE library_id = ? AND folder = ? AND from_season = ? AND from_episode = ?;
