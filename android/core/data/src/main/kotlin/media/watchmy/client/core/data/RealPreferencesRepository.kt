package media.watchmy.client.core.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.watchmy.client.core.api.apis.UsersApi
import media.watchmy.client.core.api.models.Preferences
import media.watchmy.client.core.api.models.UpdateAudioPreferences
import media.watchmy.client.core.api.models.UpdatePreferencesInputBody
import media.watchmy.client.core.api.models.UpdateSubtitlePreferences
import media.watchmy.client.model.AUDIO_LANGUAGES_API_LEVEL
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.AudioPreferences
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.SubtitlePreferences
import media.watchmy.client.model.SubtitleMode
import media.watchmy.client.model.SUBTITLE_PREFERENCES_API_LEVEL
import media.watchmy.client.model.ApiException

/**
 * Skip modes, autoplay and the audio and subtitle preferences live on the
 * server, per user (changes show at once and roll back if the server refuses
 * them); the streaming quality and the [device] settings are this device's
 * own. A change sends only the fields it alters: the server turns on
 * subtitles for foreign audio for someone saving their first audio languages
 * only while they have never sent a subtitle mode (PLAN-LANGUAGES.md §5.3).
 */
internal class RealPreferencesRepository(private val store: LocalStore, private val http: Http) : PreferencesRepository {
    private val prefs = MutableStateFlow(cachedPreferences())
    override val playback: StateFlow<PlaybackPreferences> = prefs.asStateFlow()

    override val quality: StateFlow<StreamQuality> = store.quality

    override val autoQuality: StateFlow<Boolean> = store.autoQuality

    /**
     * Bumped by every change and reset, and by a load sent with no change
     * [unanswered]: a reply applies only if nothing started after its request,
     * so a slow load never undoes a newer change.
     */
    private val generation = AtomicInteger()

    /**
     * The changes still waiting for the server (by generation). A load sent
     * meanwhile may be read before the server makes them, so its reply neither
     * applies nor stops theirs, which carry all the preferences anyway.
     */
    private val unanswered = mutableSetOf<Int>()
    private val writes = Mutex()

    override suspend fun refresh() {
        val revision = store.revision
        val mine = synchronized(generation) { if (unanswered.isEmpty()) generation.incrementAndGet() else null }
        val loaded = http.signedIn { base, client -> UsersApi(base, client).getPreferences() }.toModel()
        // An ignored refresh must not overwrite the offline cache either.
        if (mine != null) cache(revision, loaded, mine)
        if (mine != null) prefs.setIfLatest(mine, loaded)
    }

    override suspend fun setSkip(kind: SegmentKind, mode: SkipMode) = change(
        { it.copy(skip = it.skip + (kind to mode)) },
        UpdatePreferencesInputBody(skip = mapOf(kind.apiValue to mode.apiValue)),
    )

    override suspend fun setAutoplayNext(enabled: Boolean) = change(
        { it.copy(autoplayNext = enabled) },
        UpdatePreferencesInputBody(autoplayNext = enabled),
    )

    override suspend fun setSubtitles(value: SubtitlePreferences) {
        val level = http.apiLevel()
        if (level < SUBTITLE_PREFERENCES_API_LEVEL) throw ApiException(503, "Update the server to use subtitle preferences.")
        val immutable = value.copy(languages = value.languages.toList())
        change({ it.copy(subtitles = immutable) }) { before ->
            subtitlePatch(before.subtitles, immutable, level)?.let { UpdatePreferencesInputBody(subtitles = it) }
        }
    }

    override suspend fun setAudioLanguages(languages: List<String>): Boolean {
        if (http.apiLevel() < AUDIO_LANGUAGES_API_LEVEL) throw ApiException(503, "Update the server to choose audio languages.")
        val list = languages.toList()
        var basedOn: SubtitleChoice? = null
        val saved = change({ it.copy(audio = AudioPreferences(list)) }) { before ->
            basedOn = before.subtitles.choice
            if (before.audio.languages == list) null else UpdatePreferencesInputBody(audio = UpdateAudioPreferences(languages = list))
        } ?: return false
        return basedOn != SubtitleChoice.ForeignAudio && saved.subtitles.choice == SubtitleChoice.ForeignAudio
    }

    override suspend fun setQuality(quality: StreamQuality) {
        store.updateAndSave { it.copy(quality = quality.id) }
    }

    override val device: StateFlow<DevicePlayback> = store.device

    override suspend fun setMobileQuality(quality: StreamQuality?) {
        store.updateAndSave { it.copy(mobileQuality = quality?.id) }
    }

    override suspend fun setMatchFrameRate(enabled: Boolean) {
        store.updateAndSave { it.copy(matchFrameRate = enabled) }
    }

    override suspend fun setAudioPassthrough(mode: AudioPassthrough) {
        store.updateAndSave { it.copy(passthrough = mode.name.takeIf { mode != AudioPassthrough.Auto }) }
    }

    override suspend fun setSubtitleSize(size: SubtitleSize) {
        store.updateAndSave { it.copy(subtitleSize = size.name.takeIf { size != SubtitleSize.Normal }) }
    }

    /** Back to the defaults, for a sign-out or another user. */
    fun reset() {
        synchronized(generation) {
            generation.incrementAndGet()
            unanswered.clear()
            prefs.value = cachedPreferences()
        }
    }

    private suspend fun change(local: (PlaybackPreferences) -> PlaybackPreferences, body: UpdatePreferencesInputBody) {
        change(local) { body }
    }

    /**
     * Shows [local] at once and sends what [body] makes of the preferences it
     * changes (those shown, earlier changes still on their way included), so
     * each request carries only its own fields. Null sends nothing. Returns
     * what the server saved, or null when nothing was sent.
     */
    private suspend fun change(local: (PlaybackPreferences) -> PlaybackPreferences, body: (PlaybackPreferences) -> UpdatePreferencesInputBody?): PlaybackPreferences? {
        // Bind before waiting for a preceding write; queued work must never become another user's request.
        val (session, revision) = store.requestSnapshot()
        val access = http.bind(session)
        val before = prefs.value
        val request = body(before) ?: return null
        val mine = synchronized(generation) { generation.incrementAndGet().also { unanswered += it } }
        prefs.value = local(before)
        try {
            val saved = writes.withLock {
                if (store.revision != revision) throw CancellationException("The signed-in account changed.")
                access.call { base, client -> UsersApi(base, client).updatePreferences(request) }.toModel()
            }
            if (store.revision != revision) throw CancellationException("The signed-in account changed.")
            cache(revision, saved)
            prefs.setIfLatest(mine, saved)
            return saved
        } catch (e: Exception) {
            prefs.setIfLatest(mine, before)
            throw e
        } finally {
            synchronized(generation) { unanswered -= mine }
        }
    }

    private fun MutableStateFlow<PlaybackPreferences>.setIfLatest(expected: Int, value: PlaybackPreferences) {
        synchronized(generation) {
            if (generation.get() == expected) this.value = value
        }
    }

    /** The defaults, with the audio and subtitle preferences last heard for this account, which offline plays use. */
    private fun cachedPreferences(): PlaybackPreferences {
        val session = store.session() ?: return PlaybackPreferences()
        val saved = session.server.subtitlePreferences?.takeIf { it.userId == session.account?.userId && it.baseUrl == session.server.baseUrl }
            ?: return PlaybackPreferences()
        return PlaybackPreferences(
            subtitles = SubtitlePreferences(saved.languages, SubtitleMode.entries.firstOrNull { it.id == saved.mode } ?: SubtitleMode.Off, saved.automaticDownload, saved.foreignAudio),
            audio = AudioPreferences(saved.audioLanguages),
        )
    }

    private suspend fun cache(revision: Long, value: PlaybackPreferences, expectedGeneration: Int? = null) {
        val session = store.session() ?: return
        val user = session.account ?: return
        val subtitles = value.subtitles
        store.updateAndSave { current ->
            if (store.revision != revision || (expectedGeneration != null && generation.get() != expectedGeneration)) current else current.withServer(session.server.id) { server ->
                server.copy(subtitlePreferences = StoredSubtitlePreferences(
                    user.userId, server.baseUrl, subtitles.languages, subtitles.mode.id, subtitles.automaticDownload, subtitles.foreignAudio, value.audio.languages,
                ))
            }
        }
    }
}

/**
 * The subtitle fields [after] changes from [before], or null for none. A
 * changed choice sends the mode (and, from [AUDIO_LANGUAGES_API_LEVEL],
 * foreignAudio); an unchanged one sends neither, since a mode sent counts as
 * chosen (PLAN-LANGUAGES.md §5.3).
 */
internal fun subtitlePatch(before: SubtitlePreferences, after: SubtitlePreferences, apiLevel: Int): UpdateSubtitlePreferences? {
    val flag = apiLevel >= AUDIO_LANGUAGES_API_LEVEL
    val choiceChanged = if (flag) before.choice != after.choice else before.mode != after.mode
    val patch = UpdateSubtitlePreferences(
        automaticDownload = after.automaticDownload.takeIf { it != before.automaticDownload },
        foreignAudio = after.foreignAudio.takeIf { flag && choiceChanged },
        languages = after.languages.takeIf { it != before.languages },
        mode = after.mode.id.takeIf { choiceChanged },
    )
    return patch.takeIf { it != UpdateSubtitlePreferences() }
}

internal fun Preferences.toModel() = PlaybackPreferences(
    skip = PlaybackPreferences.DEFAULT_SKIP + skip.mapNotNull { (k, v) ->
        val kind = segmentKind(k) ?: return@mapNotNull null
        val mode = skipMode(v) ?: return@mapNotNull null
        kind to mode
    },
    autoplayNext = autoplayNext,
    subtitles = subtitles?.let { value -> SubtitlePreferences(
        languages = value.languages,
        mode = SubtitleMode.entries.firstOrNull { it.id == value.mode } ?: SubtitleMode.Off,
        automaticDownload = value.automaticDownload,
        foreignAudio = value.foreignAudio == true,
    ) } ?: SubtitlePreferences(),
    // Servers before audio languages send none: every file plays its default track.
    audio = AudioPreferences(audio?.languages.orEmpty()),
)
