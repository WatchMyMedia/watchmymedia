-- +goose Up
-- Files a scan found after a library's first scan now count as added when
-- found, not at their date on disk, which copies keep (Finder, cp -p, rsync
-- -a) and Sonarr and Radarr can set to the air date. Files found in the last
-- 14 days under the old rule go to the top of Recently added where they
-- belong, unless they look like a move: a missing file of the same library
-- with the same size and date.
UPDATE media_files
SET added_at = first_seen_at
WHERE first_seen_at >= strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '-14 days')
  AND added_at < strftime('%Y-%m-%dT%H:%M:%fZ', first_seen_at, '-1 day')
  AND first_seen_at > (
      SELECT strftime('%Y-%m-%dT%H:%M:%fZ', MIN(f.first_seen_at), '+1 day')
      FROM media_files f WHERE f.library_id = media_files.library_id)
  AND NOT EXISTS (
      SELECT 1 FROM media_files m
      WHERE m.library_id = media_files.library_id AND m.status = 'missing'
        AND m.size = media_files.size AND m.mtime = media_files.mtime);

-- +goose Down
-- The old dates are not kept; going back leaves the new ones.
SELECT 1;
