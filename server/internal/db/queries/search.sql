-- name: SearchMovies :many
SELECT m.* FROM movies m
JOIN media_files f ON f.movie_id = m.id AND f.status = 'present'
WHERE m.title LIKE ? OR m.original_title LIKE ?
GROUP BY m.id
ORDER BY m.title COLLATE NOCASE
LIMIT ?;

-- name: SearchShows :many
SELECT s.* FROM shows s
JOIN episodes e ON e.show_id = s.id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE s.title LIKE ? OR s.original_title LIKE ?
GROUP BY s.id
ORDER BY s.title COLLATE NOCASE
LIMIT ?;

-- name: SearchEpisodes :many
SELECT e.*, se.number AS season_number, s.title AS show_title, s.poster_path AS show_poster_path, MAX(f.id) AS file_id
FROM episodes e
JOIN seasons se ON se.id = e.season_id
JOIN shows s ON s.id = e.show_id
JOIN episode_files ef ON ef.episode_id = e.id
JOIN media_files f ON f.id = ef.media_file_id AND f.status = 'present'
WHERE e.title LIKE ?
GROUP BY e.id
ORDER BY s.title COLLATE NOCASE, se.number, e.number
LIMIT ?;
