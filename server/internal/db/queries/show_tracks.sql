-- name: GetShowTrackChoice :one
-- The sound and subtitles a person picked for a show, by its TMDB id.
SELECT * FROM show_track_choices WHERE user_id = ? AND tmdb_id = ?;

-- name: SetShowTrackChoice :exec
INSERT INTO show_track_choices (user_id, tmdb_id, audio_language, subtitle_language, subtitle_off, updated_at)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT (user_id, tmdb_id) DO UPDATE SET
    audio_language    = excluded.audio_language,
    subtitle_language = excluded.subtitle_language,
    subtitle_off      = excluded.subtitle_off,
    updated_at        = excluded.updated_at;

-- name: DeleteShowTrackChoice :exec
DELETE FROM show_track_choices WHERE user_id = ? AND tmdb_id = ?;

-- name: GetShowTrackChoiceForFile :one
-- The choice for the show an episode file belongs to, for a play that
-- names no track. From the file's first linked episode, through its show's
-- TMDB id; no row when the file is no episode or the person has no choice.
SELECT c.audio_language, c.subtitle_language, c.subtitle_off
FROM episode_files ef
CROSS JOIN episodes e ON e.id = ef.episode_id
CROSS JOIN shows s ON s.id = e.show_id
CROSS JOIN show_track_choices c ON c.user_id = sqlc.arg(user_id) AND c.tmdb_id = s.tmdb_id
WHERE ef.media_file_id = sqlc.arg(media_file_id)
ORDER BY e.id
LIMIT 1;
