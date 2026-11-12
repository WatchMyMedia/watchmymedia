package media.watchmy.client.core.data

import media.watchmy.client.core.api.models.LiveTVRecording
import media.watchmy.client.core.api.models.LiveTVRecordingRule
import media.watchmy.client.core.api.models.LiveTVRecordings
import media.watchmy.client.model.LiveRecording
import media.watchmy.client.model.LiveRecordingRule
import media.watchmy.client.model.RecordingConflict
import media.watchmy.client.model.RecordingGap
import media.watchmy.client.model.RecordingPage

/** Invalid times are omitted rather than drawing a fabricated schedule. Unknown states remain inert. */
internal fun LiveTVRecording.toModel(): LiveRecording? {
    val startMs = epochMs(start) ?: return null
    val endMs = epochMs(end)?.takeIf { it > startMs } ?: return null
    return LiveRecording(
        id = id, title = title, channelId = channelId, number = number, channelName = channelName,
        startMs = startMs, endMs = endMs, state = state,
        ruleId = ruleId?.takeIf { it.isNotBlank() }, userName = userName, sourceId = sourceId,
        programmeId = programmeId, episodeTitle = episodeTitle, season = season?.toInt(), episode = episode?.toInt(),
        series = series, reason = reason, startedAtMs = epochMs(startedAt), endedAtMs = epochMs(endedAt),
        padBefore = padBefore.coerceIn(0, 60).toInt(), padAfter = padAfter.coerceIn(0, 60).toInt(), bytes = bytes.coerceAtLeast(0),
        gaps = gaps.mapNotNull { gap -> val start = epochMs(gap.start) ?: return@mapNotNull null; val end = epochMs(gap.end)?.takeIf { it > start } ?: return@mapNotNull null; RecordingGap(start, end, gap.reason) },
        mediaFileId = mediaFileId?.takeIf { it > 0 }, canCancel = canCancel, picture = pictureUrl, canDelete = canDelete == true,
        conflictsWith = conflictsWith.orEmpty().mapNotNull { holder ->
            val from = epochMs(holder.start) ?: return@mapNotNull null
            val to = epochMs(holder.end)?.takeIf { it > from } ?: return@mapNotNull null
            RecordingConflict(holder.id, holder.title, holder.number, holder.channelName, from, to, holder.userName, holder.running)
        },
    )
}

internal fun LiveTVRecordingRule.toModel() = LiveRecordingRule(
    id = id, title = title, kind = kind, newOnly = newOnly, anyChannel = anyChannel, keep = keep.coerceIn(0, 10).toInt(),
    padBefore = padBefore.coerceIn(0, 60).toInt(), padAfter = padAfter.coerceIn(0, 60).toInt(), userName = userName,
    channelId = channelId, number = number, repeat = repeat, canCancel = canCancel,
    startMs = epochMs(start), zone = zone?.takeIf { it.isNotBlank() }, days = days?.coerceIn(0, 127)?.toInt(),
)

internal fun LiveTVRecordings.toModel() = RecordingPage(
    items = items.mapNotNull { it.toModel() }, rules = rules.orEmpty().map { it.toModel() },
    nextCursor = nextCursor?.takeIf { it.isNotBlank() }, tuners = tuners?.coerceIn(0, 128)?.toInt(), freeBytes = freeBytes?.coerceAtLeast(0),
)
