-- name: ListGuideChannels :many
SELECT * FROM live_tv_guide_channels;

-- name: UpsertGuideChannel :exec
-- A refresh's match for a channel. The admin's listings switch is kept.
INSERT INTO live_tv_guide_channels (channel_id, provider_id, feed_name, logo, matched_by, flagged)
VALUES (?, ?, ?, ?, ?, ?)
ON CONFLICT(channel_id) DO UPDATE SET provider_id = excluded.provider_id, feed_name = excluded.feed_name,
logo = excluded.logo, matched_by = excluded.matched_by, flagged = excluded.flagged;

-- name: SetGuideChannelListings :exec
INSERT INTO live_tv_guide_channels (channel_id, listings) VALUES (?, ?)
ON CONFLICT(channel_id) DO UPDATE SET listings = excluded.listings;

-- name: DeleteChannelProgrammesAfter :exec
-- Clears what a refresh replaces: the channel's programmes still running at
-- or starting after the new listings' first start.
DELETE FROM live_tv_programmes WHERE channel_id = ? AND end_at > ?;

-- name: InsertProgramme :exec
INSERT OR REPLACE INTO live_tv_programmes (id, channel_id, start_at, end_at, title, episode_title, season, episode,
episode_label, air_date, is_new, is_live, is_repeat, hd, rating, categories, kind, series_key, description,
description_source, picture, poster, picture_source, search)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

-- name: ExpireProgrammes :execrows
DELETE FROM live_tv_programmes WHERE end_at < ?;

-- name: DeleteAllProgrammes :exec
DELETE FROM live_tv_programmes;

-- name: ListProgrammesWindow :many
-- Programmes on these channels overlapping [from, to). lower_at is from
-- less the longest programme (24 hours), which bounds start_at so the
-- window index serves each channel's range. The slice comes last: sqlc
-- numbers the other parameters, and before it their numbers would point
-- into the slice.
SELECT * FROM live_tv_programmes INDEXED BY live_tv_programme_window
WHERE start_at >= sqlc.arg(lower_at) AND start_at < sqlc.arg(to_at) AND end_at > sqlc.arg(from_at)
  AND channel_id IN (sqlc.slice(channel_ids))
ORDER BY channel_id, start_at;

-- name: GuideProgrammeSummary :one
-- How many programmes there are and when the last one ends.
SELECT COUNT(*) AS programmes, CAST(COALESCE(MAX(end_at), 0) AS INTEGER) AS listed_through FROM live_tv_programmes;

-- name: ListedChannels :many
-- Channels with at least one programme.
SELECT DISTINCT channel_id FROM live_tv_programmes;

-- name: UpsertGuideArt :exec
-- A picture the feed names. used_until only moves later.
INSERT INTO live_tv_guide_art (id, url, used_until) VALUES (?, ?, ?)
ON CONFLICT(id) DO UPDATE SET used_until = MAX(used_until, excluded.used_until);

-- name: GetGuideArt :one
SELECT * FROM live_tv_guide_art WHERE id = ?;

-- name: GuideArtFetched :exec
UPDATE live_tv_guide_art SET bytes = ?, content_type = ?, width = ?, height = ?, last_used_at = ? WHERE id = ?;

-- name: TouchGuideArt :exec
UPDATE live_tv_guide_art SET last_used_at = ? WHERE id = ?;

-- name: ListGuideArtUnused :many
-- Pictures no programme or channel has used since before.
SELECT id FROM live_tv_guide_art WHERE used_until < ?;

-- name: ListGuideArtCached :many
-- Pictures on disk, least recently served first.
SELECT id, bytes FROM live_tv_guide_art WHERE bytes > 0 ORDER BY last_used_at, id;

-- name: GuideArtBytes :one
SELECT CAST(COALESCE(SUM(bytes), 0) AS INTEGER) FROM live_tv_guide_art;

-- name: EvictGuideArt :exec
UPDATE live_tv_guide_art SET bytes = 0 WHERE id = ?;

-- name: DeleteGuideArt :exec
DELETE FROM live_tv_guide_art WHERE id = ?;

-- name: DeleteAllGuideArt :exec
DELETE FROM live_tv_guide_art;

-- name: GetProgramme :one
SELECT * FROM live_tv_programmes WHERE id = ?;

-- name: ListProgrammesNear :many
-- Programmes on these channels starting in [from, to], for moving a
-- reminder whose programme a refresh moved. The slice comes last (see
-- ListProgrammesWindow).
SELECT * FROM live_tv_programmes INDEXED BY live_tv_programme_window
WHERE start_at >= sqlc.arg(from_at) AND start_at <= sqlc.arg(to_at)
  AND channel_id IN (sqlc.slice(channel_ids))
ORDER BY start_at, channel_id;

-- name: SearchProgrammes :many
-- Programmes still to end whose folded title and episode title hold the
-- word, soonest first. The word is folded too, so it has no % or _ in it.
-- The caller checks the other words and which channels the viewer may see.
SELECT * FROM live_tv_programmes
WHERE end_at > sqlc.arg(now_at) AND search LIKE '%' || CAST(sqlc.arg(word) AS TEXT) || '%'
ORDER BY start_at, channel_id
LIMIT sqlc.arg(row_limit);

-- name: ListProgrammesUnsearchable :many
-- Programmes listed before the search column existed.
SELECT id, title, episode_title FROM live_tv_programmes WHERE search = '';

-- name: SetProgrammeSearch :exec
UPDATE live_tv_programmes SET search = ? WHERE id = ?;

-- name: ListLiveFavourites :many
SELECT number FROM live_tv_favourites WHERE user_id = ? ORDER BY number;

-- name: AddLiveFavourite :exec
INSERT INTO live_tv_favourites (user_id, number, created_at) VALUES (?, ?, ?)
ON CONFLICT(user_id, number) DO NOTHING;

-- name: RemoveLiveFavourite :exec
DELETE FROM live_tv_favourites WHERE user_id = ? AND number = ?;

-- name: ListLiveReminders :many
-- A person's reminders that haven't ended, soonest first.
SELECT * FROM live_tv_reminders INDEXED BY live_tv_reminder_user
WHERE user_id = ? AND end_at > ? ORDER BY start_at, id;

-- name: ListUpcomingReminders :many
-- Everyone's reminders that haven't ended, for a refresh to check.
SELECT * FROM live_tv_reminders WHERE end_at > ? ORDER BY start_at, id;

-- name: UpsertLiveReminder :one
INSERT INTO live_tv_reminders (id, user_id, number, programme_id, start_at, end_at, title, episode_title, season,
episode, series_key, state, created_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'set', ?)
ON CONFLICT(user_id, programme_id) DO UPDATE SET state = 'set'
RETURNING *;

-- name: MoveLiveReminder :execrows
-- OR IGNORE: the person may already have a reminder for the programme it
-- moved to; the caller then deletes this one.
UPDATE OR IGNORE live_tv_reminders SET programme_id = ?, start_at = ?, end_at = ?, state = ? WHERE id = ?;

-- name: SetLiveReminderState :exec
UPDATE live_tv_reminders SET state = ? WHERE id = ?;

-- name: DeleteLiveReminder :execrows
DELETE FROM live_tv_reminders WHERE id = ? AND user_id = ?;

-- name: DeleteLiveReminderByID :exec
DELETE FROM live_tv_reminders WHERE id = ?;

-- name: ExpireLiveReminders :execrows
DELETE FROM live_tv_reminders WHERE end_at < ?;
