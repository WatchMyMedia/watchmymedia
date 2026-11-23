-- +goose Up
-- What recordings lists say about each recording and section.
--
-- A recording keeps its listing in data (the listings themselves are pruned
-- once a programme is over), so whether it was new or a repeat and the
-- listing's categories join that snapshot, under the names the recording's
-- reply uses: new, repeat, categories. Recordings scheduled before this
-- take them from their listing while it is still kept; the rest have none.
--
-- bytes is the recording's size, kept beside data so the counts per section
-- (items and bytes) read one covering index rather than every row's JSON.
ALTER TABLE live_tv_recordings ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0;
UPDATE live_tv_recordings SET bytes = COALESCE(CAST(json_extract(data, '$.bytes') AS INTEGER), 0);
CREATE INDEX live_tv_recording_section_bytes ON live_tv_recordings(section, bytes);

UPDATE live_tv_recordings
SET data = json_set(data,
    '$.new', json(CASE WHEN (SELECT p.is_new FROM live_tv_programmes p WHERE p.id = live_tv_recordings.programme_id) != 0 THEN 'true' ELSE 'false' END),
    '$.repeat', json(CASE WHEN (SELECT p.is_repeat FROM live_tv_programmes p WHERE p.id = live_tv_recordings.programme_id) != 0 THEN 'true' ELSE 'false' END),
    '$.categories', json((SELECT p.categories FROM live_tv_programmes p WHERE p.id = live_tv_recordings.programme_id)))
WHERE programme_id != ''
  AND EXISTS (SELECT 1 FROM live_tv_programmes p WHERE p.id = live_tv_recordings.programme_id);

-- +goose Down
UPDATE live_tv_recordings SET data = json_remove(data, '$.new', '$.repeat', '$.categories');
DROP INDEX live_tv_recording_section_bytes;
ALTER TABLE live_tv_recordings DROP COLUMN bytes;
