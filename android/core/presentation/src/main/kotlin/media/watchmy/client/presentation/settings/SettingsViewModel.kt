package media.watchmy.client.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AUDIO_LANGUAGES_API_LEVEL
import media.watchmy.client.model.AudioPassthrough
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.DevicePlayback
import media.watchmy.client.model.DeviceSession
import media.watchmy.client.model.PlaybackPreferences
import media.watchmy.client.model.PreferencesRepository
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.SubtitlePreferences
import media.watchmy.client.model.SUBTITLE_PREFERENCES_API_LEVEL
import media.watchmy.client.model.User
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.sortSessions
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load

/**
 * Settings on the phone and the TV (the Apple apps' SettingsModel): this
 * device's name, quality and its other playback settings (kept on the
 * device: quality on mobile data, and on a TV frame rate, passthrough and
 * subtitle size), playback, skip, audio and subtitle preferences (kept on
 * the server), the account's signed-in devices, and signing out, which the
 * screen hears of through [events]. Audio & subtitles is PLAN-LANGUAGES.md
 * §5.6: each change saves at once, and only what it changes.
 */
class SettingsViewModel(
    private val auth: AuthRepository,
    servers: ServerRepository,
    private val preferences: PreferencesRepository,
) : ViewModel() {
    data class State(
        val user: User? = null,
        val server: SavedServer? = null,
        val deviceName: String = "",
        val playback: PlaybackPreferences = PlaybackPreferences(),
        val quality: StreamQuality = StreamQuality.Original,
        /** The Default quality list: Automatic first where the server can do it. */
        val qualities: List<StreamQuality> = StreamQuality.offered(auto = false),
        /** This device's own settings beside the quality. */
        val device: DevicePlayback = DevicePlayback(),
        /** This account's signed-in devices, this one first. */
        val sessions: Load<List<DeviceSession>> = Load.Loading,
        /** The server's playback settings could not be loaded; cleared by a save that works. */
        val prefsError: String? = null,
        /** The session being signed out. */
        val revoking: String? = null,
        val signingOut: Boolean = false,
        /** Said once, then [messageShown]. */
        val message: String? = null,
        /** An audio or subtitle change is on its way to the server. */
        val subtitleSaving: Boolean = false,
        /**
         * The server turned on subtitles for foreign audio with the person's
         * first audio languages: Audio & subtitles says so, until they choose
         * how subtitles start or leave Settings.
         */
        val foreignAudioTurnedOn: Boolean = false,
    ) {
        private val apiLevel: Int get() = server?.server?.apiLevel ?: 0

        val subtitlePreferencesAvailable: Boolean get() = apiLevel >= SUBTITLE_PREFERENCES_API_LEVEL

        /** Audio languages and the foreign-audio choice (API level [AUDIO_LANGUAGES_API_LEVEL]). */
        val audioLanguagesAvailable: Boolean get() = apiLevel >= AUDIO_LANGUAGES_API_LEVEL

        /** The ways subtitles can start that this server saves, in the page's order. */
        val subtitleChoices: List<SubtitleChoice> get() = SubtitleChoice.offered(apiLevel)

        /** The one-time notice under Audio languages. */
        val showForeignAudioNotice: Boolean
            get() = foreignAudioTurnedOn && playback.audio.languages.isNotEmpty() && playback.subtitles.choice == SubtitleChoice.ForeignAudio

        /** "Until you add one, subtitles use your audio languages." under an empty Subtitle languages. */
        val subtitlesFollowAudio: Boolean
            get() = playback.subtitles.languages.isEmpty() && playback.subtitles.choice == SubtitleChoice.ForeignAudio

        /** Under Automatic download when it can't be on. */
        val automaticBlockedNote: String
            get() = if (audioLanguagesAvailable) "Choose On or When the audio isn’t in my languages, and add a subtitle language."
            else "Choose On, and add a subtitle language."

        /** The settings page's name: Audio & subtitles where audio languages can be chosen. */
        val languagesTitle: String get() = if (audioLanguagesAvailable) "Audio & subtitles" else "Subtitles"
    }

    /** What happened that the screen acts on once. */
    sealed interface Event {
        /** This device is signed out. */
        data object SignedOut : Event
    }

    private data class Local(
        val sessions: Load<List<DeviceSession>> = Load.Loading,
        val prefsError: String? = null,
        val revoking: String? = null,
        val signingOut: Boolean = false,
        val message: String? = null,
        val subtitleWrites: Int = 0,
        val foreignAudioTurnedOn: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val sessionLoads = Latest(viewModelScope)
    private val _events = Channel<Event>(Channel.BUFFERED)

    /** One-shot events, each delivered to one collector. */
    val events: Flow<Event> = _events.receiveAsFlow()

    val state: StateFlow<State> = combine(
        combine(auth.user, servers.current, auth.deviceName) { user, server, name -> Triple(user, server, name) },
        preferences.playback,
        combine(preferences.quality, preferences.autoQuality, preferences.device) { q, auto, device -> Triple(q, StreamQuality.offered(auto), device) },
        local,
    ) { (user, server, name), playback, (quality, qualities, device), l ->
        State(
            user = user, server = server, deviceName = name, playback = playback, quality = quality, qualities = qualities, device = device,
            sessions = l.sessions, prefsError = l.prefsError, revoking = l.revoking, signingOut = l.signingOut, message = l.message,
            subtitleSaving = l.subtitleWrites > 0, foreignAudioTurnedOn = l.foreignAudioTurnedOn,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    init {
        viewModelScope.launch {
            attempt { preferences.refresh() }.onFailure { e -> local.update { it.copy(prefsError = errorMessage(e, "Couldn't load your playback settings.")) } }
        }
        loadSessions()
    }

    /** Loads the signed-in devices; a list already up stays while it does, and a failure is said over it. */
    fun loadSessions() {
        val shown = local.value.sessions is Load.Ready
        if (!shown) local.update { it.copy(sessions = Load.Loading) }
        sessionLoads.launch {
            attempt { sortSessions(auth.sessions()) }
                .onSuccess { list -> local.update { it.copy(sessions = Load.Ready(list)) } }
                .onFailure { e -> local.update { if (it.sessions is Load.Ready) it.copy(message = errorMessage(e)) else it.copy(sessions = Load.Failed(errorMessage(e))) } }
        }
    }

    /** Renames this device; an empty name keeps the old one. */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == state.value.deviceName) return
        save("Couldn't rename this device.") { auth.setDeviceName(trimmed) }
    }

    fun setQuality(quality: StreamQuality) = save("Couldn't change the quality.") { preferences.setQuality(quality) }

    /** Null: on mobile data, the Default quality ("Same as on Wi-Fi"). */
    fun setMobileQuality(quality: StreamQuality?) = save("Couldn't change the quality.") { preferences.setMobileQuality(quality) }

    fun setMatchFrameRate(enabled: Boolean) = save("Couldn't save the setting.") { preferences.setMatchFrameRate(enabled) }

    fun setAudioPassthrough(mode: AudioPassthrough) = save("Couldn't save the setting.") { preferences.setAudioPassthrough(mode) }

    fun setSubtitleSize(size: SubtitleSize) = save("Couldn't save the setting.") { preferences.setSubtitleSize(size) }

    fun setSkip(kind: SegmentKind, mode: SkipMode) = save("Couldn't save the skip setting.") { preferences.setSkip(kind, mode) }

    fun setAutoplayNext(enabled: Boolean) = save("Couldn't save autoplay.") { preferences.setAutoplayNext(enabled) }

    /** How subtitles start; choosing one puts away the notice that the server chose it. */
    fun setSubtitleChoice(choice: SubtitleChoice) {
        if (choice !in state.value.subtitleChoices) return
        local.update { it.copy(foreignAudioTurnedOn = false) }
        changeSubtitles { it.withChoice(choice) }
    }

    fun setAutomaticSubtitles(enabled: Boolean) = changeSubtitles { it.copy(automaticDownload = enabled) }

    fun addSubtitleLanguage(tag: String) = changeSubtitles { it.copy(languages = added(it.languages, tag)) }

    fun removeSubtitleLanguage(tag: String) = changeSubtitles { it.copy(languages = it.languages.filterNot { language -> language == tag }) }

    fun moveSubtitleLanguage(tag: String, offset: Int) = changeSubtitles { it.copy(languages = moved(it.languages, tag, offset)) }

    fun addAudioLanguage(tag: String) = changeAudio { added(it, tag) }

    fun removeAudioLanguage(tag: String) = changeAudio { it.filterNot { language -> language == tag } }

    fun moveAudioLanguage(tag: String, offset: Int) = changeAudio { moved(it, tag, offset) }

    private fun changeSubtitles(change: (SubtitlePreferences) -> SubtitlePreferences) {
        if (!state.value.subtitlePreferencesAvailable) return
        saveLanguages("Couldn't save subtitle settings. Your previous settings are still in use.") {
            preferences.setSubtitles(change(preferences.playback.value.subtitles))
        }
    }

    private fun changeAudio(change: (List<String>) -> List<String>) {
        if (!state.value.audioLanguagesAvailable) return
        saveLanguages("Couldn't save audio languages. Your previous settings are still in use.") {
            // From the languages as they are when this change's turn comes, earlier changes included.
            val before = preferences.playback.value.audio.languages
            val after = change(before)
            if (after != before && preferences.setAudioLanguages(after)) local.update { it.copy(foreignAudioTurnedOn = true) }
        }
    }

    private fun saveLanguages(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            local.update { it.copy(subtitleWrites = it.subtitleWrites + 1) }
            try {
                attempt { block() }
                    .onSuccess { local.update { it.copy(prefsError = null) } }
                    .onFailure { say(errorMessage(it, failure)) }
            } finally {
                local.update { it.copy(subtitleWrites = (it.subtitleWrites - 1).coerceAtLeast(0)) }
            }
        }
    }

    /** Signs another device out. */
    fun revoke(session: DeviceSession) {
        if (local.value.revoking != null) return
        local.update { it.copy(revoking = session.id) }
        viewModelScope.launch {
            attempt { auth.revoke(session.id) }
                .onSuccess {
                    local.update { l ->
                        val sessions = (l.sessions as? Load.Ready)?.let { Load.Ready(it.data.filter { s -> s.id != session.id }) } ?: l.sessions
                        l.copy(revoking = null, sessions = sessions, message = "${session.name} is signed out")
                    }
                }
                .onFailure { e -> local.update { it.copy(revoking = null, message = errorMessage(e)) } }
        }
    }

    /** Signs this device out; [AuthRepository.user] turns null, and [events] says [Event.SignedOut]. */
    fun signOut() {
        if (local.value.signingOut) return
        local.update { it.copy(signingOut = true) }
        viewModelScope.launch {
            attempt { auth.signOut() }
                .onSuccess {
                    local.update { it.copy(signingOut = false) }
                    _events.send(Event.SignedOut)
                }
                .onFailure { e -> local.update { it.copy(signingOut = false, message = errorMessage(e, "Couldn't sign out. Try again.")) } }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    /** Shows [message] once. */
    fun say(message: String) = local.update { it.copy(message = message) }

    private companion object {
        /** At most 16 of each, as the server takes (PLAN-LANGUAGES.md §4). */
        const val MAX_LANGUAGES = 16

        fun added(list: List<String>, tag: String): List<String> =
            if (list.size >= MAX_LANGUAGES || list.any { it.equals(tag, ignoreCase = true) }) list else list + tag

        fun moved(list: List<String>, tag: String, offset: Int): List<String> {
            val index = list.indexOf(tag)
            val target = index + offset
            if (index < 0 || target !in list.indices) return list
            return list.toMutableList().apply { add(target, removeAt(index)) }
        }
    }

    /** A change kept on the server or this device; a failure says [failure], or the server's reason. */
    private fun save(failure: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            attempt { block() }
                .onSuccess { local.update { it.copy(prefsError = null) } }
                .onFailure { e -> say(errorMessage(e, failure)) }
        }
    }
}
