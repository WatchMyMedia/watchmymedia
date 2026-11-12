package media.watchmy.client.core.data

import java.time.OffsetDateTime
import java.util.Locale
import media.watchmy.client.model.AudioOption
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ContinueItem
import media.watchmy.client.model.DOLBY_VISION
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.EpisodeCard
import media.watchmy.client.model.EpisodeDetail
import media.watchmy.client.model.Hero
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.LibrarySummary
import media.watchmy.client.model.LibraryTotals
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.MovieDetail
import media.watchmy.client.model.Trailer
import media.watchmy.client.model.TrailerText
import media.watchmy.client.model.isYouTubeKey
import media.watchmy.client.model.NextUp
import media.watchmy.client.model.PendingDevice
import media.watchmy.client.model.Person
import media.watchmy.client.model.PlaybackMode
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.SeasonDetail
import media.watchmy.client.model.SeasonSummary
import media.watchmy.client.model.Segment
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.ShowDetail
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamVideo
import media.watchmy.client.model.OPEN_SUBTITLES_CREDIT
import media.watchmy.client.model.OnlineSubtitle
import media.watchmy.client.model.OnlineSubtitles
import media.watchmy.client.model.SubtitleOption
import media.watchmy.client.model.SubtitleSource
import media.watchmy.client.model.onlineLanguageLabel
import media.watchmy.client.model.UpNext
import media.watchmy.client.model.User
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.subtitleTrackLabel
import media.watchmy.client.model.subtitleFormatName
import media.watchmy.client.model.languageName
import media.watchmy.client.model.codecName
import media.watchmy.client.model.channelLayout
import media.watchmy.client.model.audioTrackLabel
import media.watchmy.client.core.api.models.AudioStream as ApiAudioStream
import media.watchmy.client.core.api.models.AudioTrack as ApiAudioTrack
import media.watchmy.client.core.api.models.BrowseItem as ApiBrowseItem
import media.watchmy.client.core.api.models.ContinueItem as ApiContinueItem
import media.watchmy.client.core.api.models.Credit as ApiCredit
import media.watchmy.client.core.api.models.Episode as ApiEpisode
import media.watchmy.client.core.api.models.EpisodeCard as ApiEpisodeCard
import media.watchmy.client.core.api.models.HomeData as ApiHomeData
import media.watchmy.client.core.api.models.HomeHero as ApiHomeHero
import media.watchmy.client.core.api.models.ItemTotals as ApiItemTotals
import media.watchmy.client.core.api.models.MediaFile as ApiMediaFile
import media.watchmy.client.model.RecordingProvenance
import media.watchmy.client.core.api.models.MediaFileDetail as ApiMediaFileDetail
import media.watchmy.client.core.api.models.Movie as ApiMovie
import media.watchmy.client.core.api.models.MovieDetail as ApiMovieDetail
import media.watchmy.client.core.api.models.MovieTrailer as ApiMovieTrailer
import media.watchmy.client.core.api.models.PairingInfo as ApiPairingInfo
import media.watchmy.client.core.api.models.PlaybackPlan as ApiPlaybackPlan
import media.watchmy.client.core.api.models.SearchEpisode as ApiSearchEpisode
import media.watchmy.client.core.api.models.Season as ApiSeason
import media.watchmy.client.core.api.models.SeasonDetail as ApiSeasonDetail
import media.watchmy.client.core.api.models.Segment as ApiSegment
import media.watchmy.client.core.api.models.Session as ApiSession
import media.watchmy.client.core.api.models.SubtitleStream as ApiSubtitleStream
import media.watchmy.client.core.api.models.VideoPlan as ApiVideoPlan
import media.watchmy.client.core.api.models.Show as ApiShow
import media.watchmy.client.core.api.models.ShowDetail as ApiShowDetail
import media.watchmy.client.core.api.models.SubtitleSearch as ApiSubtitleSearch
import media.watchmy.client.core.api.models.SubtitleTrack as ApiSubtitleTrack
import media.watchmy.client.core.api.models.UpNext as ApiUpNext
import media.watchmy.client.core.api.models.User as ApiUser
import media.watchmy.client.core.api.models.WatchState as ApiWatchState

/*
 * The API's types as the app's model. Values a newer server adds to an enum
 * decode as unknown_default_open_api; items of an unknown kind are left out
 * rather than shown wrongly.
 */

private const val UNKNOWN = "unknown_default_open_api"

/** An RFC 3339 time as epoch milliseconds, or null. */
internal fun epochMs(time: String?): Long? =
    time?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

private fun String?.orNullIfBlank() = this?.takeIf { it.isNotBlank() }

/** "4K", "1080p", "720p", "SD", or null for "" and values this app does not know. */
private fun qualityLabel(value: String?) = value?.takeIf { it.isNotEmpty() && it != UNKNOWN }

internal fun ApiUser.toModel() = User(id, name, isAdmin)

internal fun ApiBrowseItem.toModel(): BrowseItem? = BrowseItem(
    kind = when (kind) {
        ApiBrowseItem.Kind.movie -> ItemKind.Movie
        ApiBrowseItem.Kind.show -> ItemKind.Show
        ApiBrowseItem.Kind.unmatched -> ItemKind.Unmatched
        ApiBrowseItem.Kind.unknown_default_open_api -> return null
    },
    id = id,
    libraryId = libraryId,
    title = title,
    year = year?.toInt()?.takeIf { it > 0 },
    overview = overview,
    genres = genres,
    rating = rating?.takeIf { it > 0 },
    posterPath = posterPath.orNullIfBlank(),
    backdropPath = backdropPath.orNullIfBlank(),
    quality = qualityLabel(quality?.value),
    hdr = hdr,
    isNew = isNew,
    subtitle = subtitle.orNullIfBlank(),
    watched = watched,
    progress = progress?.toFloat()?.takeIf { it > 0f },
    episodeCount = episodeCount?.toInt(),
    runtimeMin = runtimeMin?.toInt()?.takeIf { it > 0 },
    releaseDate = releaseDate.orNullIfBlank(),
    fileName = fileName.orNullIfBlank(),
    fileCount = fileCount?.toInt(),
    episodeId = episodeId?.takeIf { seasonNumber != null },
    episodeSeason = seasonNumber?.toInt()?.takeIf { episodeId != null },
)

internal fun ApiMovie.toBrowseItem() = BrowseItem(
    kind = ItemKind.Movie, id = id, libraryId = libraryId, title = title, year = year?.toInt()?.takeIf { it > 0 },
    overview = overview, genres = genres, rating = rating?.takeIf { it > 0 }, posterPath = posterPath.orNullIfBlank(),
    backdropPath = backdropPath.orNullIfBlank(), quality = null, hdr = false, isNew = false, subtitle = null,
    watched = false, progress = null, episodeCount = null, runtimeMin = runtimeMin?.toInt()?.takeIf { it > 0 },
    releaseDate = releaseDate.orNullIfBlank(),
)

internal fun ApiShow.toBrowseItem() = BrowseItem(
    kind = ItemKind.Show, id = id, libraryId = libraryId, title = title, year = year?.toInt()?.takeIf { it > 0 },
    overview = overview, genres = genres, rating = rating?.takeIf { it > 0 }, posterPath = posterPath.orNullIfBlank(),
    backdropPath = backdropPath.orNullIfBlank(), quality = null, hdr = false, isNew = false, subtitle = null,
    watched = false, progress = null, episodeCount = episodeCount.toInt(), runtimeMin = null, releaseDate = null,
)

internal fun ApiEpisodeCard.toModel() = EpisodeCard(
    episodeId = episodeId,
    showId = showId,
    showTitle = showTitle,
    season = seasonNumber.toInt(),
    episode = episodeNumber.toInt(),
    title = title,
    overview = overview,
    airDate = airDate.orNullIfBlank(),
    stillPath = stillPath.orNullIfBlank(),
    showPosterPath = showPosterPath.orNullIfBlank(),
    showBackdropPath = showBackdropPath.orNullIfBlank(),
    hasFile = hasFile,
    fileId = fileId,
    isNew = isNew,
    watched = watched == true,
)

internal fun ApiSearchEpisode.toCard() = EpisodeCard(
    episodeId = id, showId = showId, showTitle = showTitle, season = seasonNumber.toInt(), episode = number.toInt(),
    title = title, overview = "", airDate = airDate.orNullIfBlank(), stillPath = stillPath.orNullIfBlank(),
    showPosterPath = showPosterPath.orNullIfBlank(), showBackdropPath = null, hasFile = true, fileId = fileId, isNew = false,
    watched = watched == true,
)

internal fun ApiContinueItem.toModel(): ContinueItem? = ContinueItem(
    kind = when (kind) {
        ApiContinueItem.Kind.movie -> ContinueItem.Kind.Movie
        ApiContinueItem.Kind.episode -> ContinueItem.Kind.Episode
        ApiContinueItem.Kind.unknown_default_open_api -> return null
    },
    title = title,
    subtitle = subtitle.orNullIfBlank(),
    posterPath = posterPath.orNullIfBlank(),
    backdropPath = backdropPath.orNullIfBlank(),
    stillPath = stillPath.orNullIfBlank(),
    fileId = fileId,
    movieId = movieId,
    showId = showId,
    episodeId = episodeId,
    season = seasonNumber?.toInt(),
    episode = episodeNumber?.toInt(),
    positionMs = positionMs,
    durationMs = durationMs,
)

internal fun ApiHomeHero.toModel(): Hero? = Hero(
    kind = when (kind) {
        ApiHomeHero.Kind.episode -> Hero.Kind.Episode
        ApiHomeHero.Kind.movie -> Hero.Kind.Movie
        ApiHomeHero.Kind.show -> Hero.Kind.Show
        ApiHomeHero.Kind.unknown_default_open_api -> return null
    },
    kicker = kicker,
    title = title,
    facts = facts,
    overview = overview,
    backdropPath = backdropPath.orNullIfBlank(),
    posterPath = posterPath.orNullIfBlank(),
    showId = showId,
    movieId = movieId,
    episodeId = episodeId,
    fileId = fileId,
    contentRating = contentRating.orNullIfBlank(),
    quality = qualityLabel(quality?.value),
    hdr = hdr == true,
)

/** [arranged]: asked for with arranged=true, so [HomeData.rows] is the person's rows (an empty list when every row is hidden). */
internal fun ApiHomeData.toModel(arranged: Boolean = false) = HomeData(
    hero = hero?.toModel(),
    continueWatching = continueWatching.mapNotNull { it.toModel() },
    recentlyAddedShows = recentlyAddedShows.mapNotNull { it.toModel() },
    recentlyAired = recentlyAired.map { it.toModel() },
    recentlyAddedMovies = recentlyAddedMovies.mapNotNull { it.toModel() },
    recentlyReleased = recentlyReleased.mapNotNull { it.toModel() },
    totals = LibraryTotals(totals.movies.toInt(), totals.shows.toInt(), totals.episodes.toInt(), totals.files.toInt(), totals.totalSize),
    lastScannedAtMs = epochMs(lastScannedAt),
    movieLibraryId = movieLibraryId,
    tvLibraryId = tvLibraryId,
    rows = if (arranged) rows.toRows() else null,
)

internal fun ApiItemTotals.toModel() = LibrarySummary(items.toInt(), episodes.toInt(), continuing.toInt(), unmatched.toInt(), totalSize)

internal fun ApiCredit.toModel() = Person(name, character.orNullIfBlank(), profilePath.orNullIfBlank())

internal fun ApiWatchState.toModel() = WatchProgress(positionMs, durationMs, completed)

internal fun ApiSeason.toModel() = SeasonSummary(
    id = id,
    number = number.toInt(),
    name = name,
    overview = overview,
    airDate = airDate.orNullIfBlank(),
    posterPath = posterPath.orNullIfBlank(),
    episodeCount = episodeCount.toInt(),
    availableCount = availableCount.toInt(),
    watchedCount = watchedCount.toInt(),
    monitored = monitored,
    request = request?.toModel(),
)

internal fun ApiShowDetail.toModel() = ShowDetail(
    id = id,
    libraryId = libraryId,
    title = title,
    year = year?.toInt()?.takeIf { it > 0 },
    overview = overview,
    status = status.orNullIfBlank(),
    genres = genres,
    rating = rating?.takeIf { it > 0 },
    posterPath = posterPath.orNullIfBlank(),
    backdropPath = backdropPath.orNullIfBlank(),
    quality = qualityLabel(quality?.value),
    hdr = hdr,
    seasons = seasons.map { it.toModel() },
    totalEpisodes = totalEpisodes.toInt(),
    episodesOnDisk = episodeCount.toInt(),
    totalSizeBytes = totalSize,
    nextAirDate = nextAirDate.orNullIfBlank(),
    nextUp = nextEpisode?.let { NextUp(it.episodeId, it.seasonNumber.toInt(), it.episodeNumber.toInt(), it.title, it.fileId) },
    cast = credits.map { it.toModel() },
    contentRating = contentRating.orNullIfBlank(),
    tmdbId = tmdbId.takeIf { it > 0 },
    managedBy = managedBy.orNullIfBlank(),
)

internal fun ApiSeasonDetail.toModel() = SeasonDetail(
    showId = showId,
    showTitle = show.title,
    season = SeasonSummary(
        id = id,
        number = number.toInt(),
        name = name,
        overview = overview,
        airDate = airDate.orNullIfBlank(),
        posterPath = posterPath.orNullIfBlank(),
        episodeCount = episodeCount.toInt(),
        availableCount = availableCount.toInt(),
        watchedCount = watchedCount.toInt(),
        monitored = monitored,
        request = request?.toModel(),
    ),
    episodes = episodes.map { it.toModel(number.toInt()) },
    showBackdropPath = show.backdropPath.orNullIfBlank(),
)

internal fun ApiEpisode.toModel(season: Int) = EpisodeDetail(
    id = id,
    season = season,
    number = number.toInt(),
    title = title,
    overview = overview,
    airDate = airDate.orNullIfBlank(),
    stillPath = stillPath.orNullIfBlank(),
    runtimeMin = runtimeMin?.toInt()?.takeIf { it > 0 },
    rating = rating?.takeIf { it > 0 },
    hasFile = hasFile,
    aired = aired,
    isNew = isNew,
    versions = files.map { it.toVersion() },
    watch = watch?.toModel(),
    monitored = monitored,
    download = download?.toModel(),
    request = request?.toModel(),
)

internal fun ApiMovieDetail.toModel() = MovieDetail(
    id = id,
    libraryId = libraryId,
    title = title,
    year = year?.toInt()?.takeIf { it > 0 },
    tagline = tagline.orNullIfBlank(),
    overview = overview,
    releaseDate = releaseDate.orNullIfBlank(),
    runtimeMin = runtimeMin?.toInt()?.takeIf { it > 0 },
    genres = genres,
    rating = rating?.takeIf { it > 0 },
    posterPath = posterPath.orNullIfBlank(),
    backdropPath = backdropPath.orNullIfBlank(),
    quality = qualityLabel(quality?.value),
    hdr = hdr,
    versions = files.map { it.toVersion() },
    cast = credits.map { it.toModel() },
    watch = watch?.toModel(),
    contentRating = contentRating.orNullIfBlank(),
    tmdbId = tmdbId.takeIf { it > 0 },
    download = download?.toModel(),
    managedBy = managedBy.orNullIfBlank(),
    trailers = trailers.orEmpty().mapNotNull { it.toModel() },
)

/**
 * A trailer the app knows how to play; null for a source added later, or an
 * entry missing what its source needs (a file without an id, a key that is not
 * a YouTube id), which the app skips.
 */
internal fun ApiMovieTrailer.toModel(): Trailer? {
    val name = name.ifBlank { TrailerText.BUTTON }
    return when (source) {
        "file" -> fileId?.takeIf { it > 0 }?.let { Trailer.File(name, it, durationMs?.takeIf { d -> d > 0 }) }
        "youtube" -> key?.takeIf(::isYouTubeKey)?.let { Trailer.YouTube(name, it) }
        else -> null
    }
}

internal fun fileName(relativePath: String) = relativePath.substringAfterLast('/')

/** An episode's file: the listing has no stream details, so the audio line is the codec alone. */
internal fun ApiMediaFile.toVersion() = MediaVersion(
    fileId = id,
    label = fileName(relativePath),
    sizeBytes = propertySize,
    container = container.orNullIfBlank()?.let(::containerName),
    videoCodec = videoCodec.orNullIfBlank(),
    width = width?.toInt(),
    height = height?.toInt(),
    hdr = hdr?.value?.let(::hdrName),
    audioSummary = audioCodec.orNullIfBlank()?.let(::codecName),
    durationMs = durationMs,
    dvProfile = dvProfile?.toInt()?.takeIf { it > 0 },
    recording = recording?.let { source -> epochMs(source.startedAt)?.let { RecordingProvenance(source.channel, source.number, it, source.gaps.size, source.adBreaks) } },
)

internal fun ApiMediaFileDetail.toVersion(): MediaVersion {
    val video = probe?.video?.firstOrNull { it.default } ?: probe?.video?.firstOrNull()
    val audio = probe?.audio?.firstOrNull { it.default } ?: probe?.audio?.firstOrNull()
    return MediaVersion(
        fileId = id,
        label = fileName(relativePath),
        sizeBytes = propertySize,
        container = container.orNullIfBlank()?.let(::containerName),
        videoCodec = videoCodec.orNullIfBlank() ?: video?.codec,
        width = width?.toInt() ?: video?.width?.toInt(),
        height = height?.toInt() ?: video?.height?.toInt(),
        hdr = hdr?.value?.let(::hdrName) ?: video?.hdr?.value?.let(::hdrName),
        audioSummary = audio?.summary() ?: audioCodec.orNullIfBlank()?.let(::codecName),
        durationMs = durationMs ?: probe?.durationMs,
        audio = probe?.audio.orEmpty().map { it.toOption() },
        subtitles = probe?.subtitles.orEmpty().map { it.toOption() },
        dvProfile = (dvProfile ?: video?.dvProfile)?.toInt()?.takeIf { it > 0 },
        recording = recording?.let { source -> epochMs(source.startedAt)?.let { RecordingProvenance(source.channel, source.number, it, source.gaps.size, source.adBreaks) } },
    )
}

/** A file's audio stream as the player's audio picker would list it. */
internal fun ApiAudioStream.toOption() = AudioOption(
    index = index.toInt(),
    label = audioTrackLabel(language, codec, channels.toInt(), title, default),
    language = language.orNullIfBlank(),
    isDefault = default,
    codec = codec.orNullIfBlank(),
    channels = channels.toInt(),
    title = title.orNullIfBlank(),
)

/** A file's own subtitle stream, with the ID the decision gives it ("s" and the stream index). */
internal fun ApiSubtitleStream.toOption() = SubtitleOption(
    id = "s$index",
    label = subtitleTrackLabel(language, title, forced),
    language = language.orNullIfBlank(),
    forced = forced,
    burnInOnly = image,
    format = subtitleFormatName(codec),
)

/** "TrueHD 7.1 · English". */
private fun ApiAudioStream.summary(): String =
    listOfNotNull("${codecName(codec)} ${channelLayout(channels.toInt())}".trim(), languageName(language)).joinToString(" · ")

/** "matroska" → "MKV", "mov,mp4,m4a" → "MP4". */
internal fun containerName(value: String): String {
    val first = value.lowercase(Locale.ROOT).split(',').map { it.trim() }
    return when {
        "matroska" in first || "mkv" in first -> "MKV"
        "mp4" in first || "mov" in first -> "MP4"
        "mpegts" in first || "ts" in first -> "TS"
        else -> first.first().uppercase(Locale.ROOT)
    }
}

internal fun hdrName(value: String): String? = when (value) {
    "", UNKNOWN -> null
    "hdr10" -> "HDR10"
    "hlg" -> "HLG"
    "dolbyvision" -> DOLBY_VISION
    else -> value.uppercase(Locale.ROOT)
}

internal fun ApiSession.toModel() = DeviceSession(
    id = id,
    kind = if (kind == ApiSession.Kind.device) DeviceSession.Kind.Device else DeviceSession.Kind.Browser,
    name = name,
    product = product.orNullIfBlank(),
    platform = platform.orNullIfBlank(),
    version = version.orNullIfBlank(),
    lastIp = lastIp.orNullIfBlank(),
    createdAtMs = epochMs(createdAt) ?: 0,
    lastSeenAtMs = epochMs(lastSeenAt) ?: 0,
    current = current,
    userName = userName,
    model = model.orNullIfBlank(),
)

internal fun ApiPairingInfo.toModel() = PendingDevice(
    code = code,
    name = name,
    product = product.orNullIfBlank(),
    platform = platform.orNullIfBlank(),
    version = version.orNullIfBlank(),
    ip = ip.orNullIfBlank(),
    approved = approved,
    model = model.orNullIfBlank(),
)

internal fun ApiSegment.toModel(): Segment? = segmentKind(kind.value)?.let { Segment(it, startMs, endMs, final) }

/** The video the decision sends; HDR mapped to SDR is SDR. */
internal fun ApiVideoPlan.toModel(): StreamVideo? {
    val w = width?.toInt()?.takeIf { it > 0 } ?: return null
    val h = height?.toInt()?.takeIf { it > 0 } ?: return null
    return StreamVideo(w, h, hdr.takeUnless { toneMap }?.let(::hdrName))
}

internal fun ApiUpNext.toModel() = UpNext(
    fileId = fileId,
    episodeId = episodeId,
    showId = showId,
    showTitle = showTitle,
    season = seasonNumber.toInt(),
    episode = episodeNumber.toInt(),
    title = title,
    stillPath = stillPath.orNullIfBlank(),
    runtimeMs = runtimeMin?.takeIf { it > 0 }?.let { it * 60_000L },
    seasonFinale = seasonFinale == true,
)

/** A plan; the video and audio say what they would be sent as. */
internal fun ApiPlaybackPlan.toModel() = PlaybackPlan(
    fileId = fileId,
    mode = when (mode) {
        ApiPlaybackPlan.Mode.direct -> PlaybackMode.Direct
        ApiPlaybackPlan.Mode.remux -> PlaybackMode.Remux
        ApiPlaybackPlan.Mode.audio -> PlaybackMode.AudioTranscode
        ApiPlaybackPlan.Mode.transcode, ApiPlaybackPlan.Mode.unknown_default_open_api -> PlaybackMode.Transcode
    },
    video = video.toModel(),
    videoCodec = video.codec.orNullIfBlank()?.takeUnless { video.copy }?.let(::videoCodecName),
    audioCodec = audio.codec.orNullIfBlank()?.takeUnless { audio.copy }?.let(::codecName),
    audioChannels = audio.channels?.toInt()?.takeIf { it > 0 && !audio.copy },
    reasons = reasons,
)

/** "h264" → "H.264", "hevc" → "HEVC". */
internal fun videoCodecName(codec: String): String = when (val c = codec.lowercase(Locale.ROOT)) {
    "h264" -> "H.264"
    "hevc" -> "HEVC"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    else -> c.uppercase(Locale.ROOT)
}

internal fun ApiAudioTrack.toModel() = AudioOption(
    index = index.toInt(),
    label = audioTrackLabel(language, codec, channels.toInt(), title, default),
    language = language.orNullIfBlank(),
    isDefault = default,
    codec = codec.orNullIfBlank(),
    channels = channels.toInt(),
    title = title.orNullIfBlank(),
)

internal fun ApiSubtitleTrack.toOption() = SubtitleOption(
    id = id,
    label = subtitleTrackLabel(language, title, forced),
    language = language.orNullIfBlank(),
    forced = forced,
    burnInOnly = burnInOnly,
    format = subtitleFormatName(codec),
    source = subtitleSource(source?.value, id, external),
    release = release.orNullIfBlank(),
    hearingImpaired = hearingImpaired == true,
)

/** The track's source, or from its ID when an older server says none: "d" is a download, "x" a file next to the video. */
internal fun subtitleSource(source: String?, id: String, external: Boolean): SubtitleSource = when (source) {
    "opensubtitles" -> SubtitleSource.Online
    "sidecar" -> SubtitleSource.Sidecar
    "embedded" -> SubtitleSource.Embedded
    else -> when {
        id.startsWith("d") -> SubtitleSource.Online
        external || id.startsWith("x") -> SubtitleSource.Sidecar
        else -> SubtitleSource.Embedded
    }
}

/** A search's results, or null when the server has no online provider. */
internal fun ApiSubtitleSearch.toModel(): OnlineSubtitles? {
    if (!available) return null
    return OnlineSubtitles(
        results = results.map {
            OnlineSubtitle(
                id = it.id, language = it.language.orNullIfBlank(), label = onlineLanguageLabel(it.language), release = it.release,
                hashMatch = it.hashMatch, hearingImpaired = it.hearingImpaired, forced = it.forced, aiTranslated = it.aiTranslated,
                downloads = it.downloads, trackId = it.trackId.orNullIfBlank(),
            )
        },
        attribution = attribution.ifBlank { OPEN_SUBTITLES_CREDIT },
        canDownload = canDownload,
        remaining = quota.remaining.toInt().takeIf { it >= 0 },
        resetAtMs = epochMs(quota.resetAt),
        error = error.orNullIfBlank(),
    )
}

internal fun segmentKind(value: String): SegmentKind? = when (value) {
    "intro" -> SegmentKind.Intro
    "recap" -> SegmentKind.Recap
    "credits" -> SegmentKind.Credits
    "preview" -> SegmentKind.Preview
    "commercial" -> SegmentKind.Commercial
    else -> null
}

internal val SegmentKind.apiValue: String
    get() = when (this) {
        SegmentKind.Intro -> "intro"
        SegmentKind.Recap -> "recap"
        SegmentKind.Credits -> "credits"
        SegmentKind.Preview -> "preview"
        SegmentKind.Commercial -> "commercial"
    }

internal fun skipMode(value: String): SkipMode? = when (value) {
    "none" -> SkipMode.None
    "ask" -> SkipMode.Ask
    "auto" -> SkipMode.Auto
    "auto-delay" -> SkipMode.AutoDelay
    else -> null
}

internal val SkipMode.apiValue: String
    get() = when (this) {
        SkipMode.None -> "none"
        SkipMode.Ask -> "ask"
        SkipMode.Auto -> "auto"
        SkipMode.AutoDelay -> "auto-delay"
    }

