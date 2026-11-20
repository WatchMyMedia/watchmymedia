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
import media.watchmy.client.model.SaveFailure
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.SegmentKind
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.SettingsNotice
import media.watchmy.client.model.SkipMode
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.SubtitleChoice
import media.watchmy.client.model.SubtitleSize
import media.watchmy.client.model.SubtitlePreferences
import media.watchmy.client.model.SUBTITLE_PREFERENCES_API_LEVEL
import media.watchmy.client.model.User
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.model.isUnreachable
import media.watchmy.client.model.passwordChangedNotice
import media.watchmy.client.model.revokedNotice
import media.watchmy.client.model.sortSessions
import media.watchmy.client.presentation.Latest
import media.watchmy.client.presentation.Load

/**
 * Settings on the phone and the TV (the Apple apps' SettingsModel): this
 * device's name, quality and its other playback settings (kept on the
 * device: quality on mobile data, and on a TV frame rate, passthrough and
 * subtitle size), playback, skip, audio and subtitle preferences (kept on
 * the server), the account's signed-in devices, and signing out, which the
 * screen hears of through [events]. In Audio & subtitles
 * each change saves at once, and only what it changes.
 *
 * Settings as groups: each change is a
 * [SettingKey] whose row says Saving… while it goes and keeps its
 * [SaveFailure] until it is tried again ([retry]) or changed. The account's
 * settings can't be changed until they have loaded ([accountReady]), so a
 * default is never saved over someone's choice; this device's always can.
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
        /** Whether the account's settings have loaded; until they have, they show the defaults and can't be changed. */
        val account: AccountLoad = AccountLoad.Loading,
        /** Settings on their way to being saved. */
        val saving: Set<SettingKey> = emptySet(),
        /** Settings whose last change didn't save, with why; kept until tried again or changed. */
        val failed: Map<SettingKey, SaveFailure> = emptyMap(),
        /** Another device that couldn't be signed out, for its dialog's failed state. */
        val revokeFailure: RevokeFailure? = null,
        /** Why signing this device out failed, for its dialog's failed state. */
        val signOutFailure: String? = null,
        /** What was just done, on the page until [noticeShown]: a device signed out, the password changed. */
        val notice: SettingsNotice? = null,
        /** The session being signed out. */
        val revoking: String? = null,
        val signingOut: Boolean = false,
        /** Said once, then [messageShown]. */
        val message: String? = null,
        /**
         * The server turned on subtitles for foreign audio with the person's
         * first audio languages: Audio & subtitles says so, until they choose
         * how subtitles start or leave Settings.
         */
        val foreignAudioTurnedOn: Boolean = false,
    ) {
        private val apiLevel: Int get() = server?.server?.apiLevel ?: 0

        /** The account's settings have loaded and can be changed. */
        val accountReady: Boolean get() = account == AccountLoad.Ready

        /** Why the account's settings didn't load, for screens that say it in a line. */
        val prefsError: String? get() = (account as? AccountLoad.Failed)?.reason

        /** An audio or subtitle change is on its way to the server. */
        val subtitleSaving: Boolean get() = saving.any { it in SettingKey.LANGUAGES }

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
        val account: AccountLoad = AccountLoad.Loading,
        val writes: Map<SettingKey, Int> = emptyMap(),
        val failed: Map<SettingKey, SaveFailure> = emptyMap(),
        val revoking: String? = null,
        val revokeFailure: RevokeFailure? = null,
        val signingOut: Boolean = false,
        val signOutFailure: String? = null,
        val notice: SettingsNotice? = null,
        val message: String? = null,
        val foreignAudioTurnedOn: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    /** Each setting's latest change, which [retry] runs again, and its number, so an older change that ends late can't mark it failed. */
    private val latest = HashMap<SettingKey, Pair<Int, suspend () -> Unit>>()
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
            sessions = l.sessions, account = l.account, saving = l.writes.keys, failed = l.failed,
            revoking = l.revoking, revokeFailure = l.revokeFailure, signingOut = l.signingOut, signOutFailure = l.signOutFailure,
            notice = l.notice, message = l.message, foreignAudioTurnedOn = l.foreignAudioTurnedOn,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    init {
        reloadAccount()
        loadSessions()
    }

    /** Loads the account's settings, again after they failed. */
    fun reloadAccount() {
        local.update { it.copy(account = AccountLoad.Loading) }
        viewModelScope.launch {
            attempt { preferences.refresh() }
                .onSuccess { local.update { it.copy(account = AccountLoad.Ready) } }
                .onFailure { e -> local.update { it.copy(account = AccountLoad.Failed(errorMessage(e, "Couldn't load your settings."), e.isUnreachable())) } }
        }
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
        save(SettingKey.DeviceName, "Couldn't rename this device.") { auth.setDeviceName(trimmed) }
    }

    fun setQuality(quality: StreamQuality) = save(SettingKey.Quality, "Couldn't change the quality.") { preferences.setQuality(quality) }

    /** Null: on mobile data, the Default quality ("Same as on Wi-Fi"). */
    fun setMobileQuality(quality: StreamQuality?) = save(SettingKey.MobileQuality, "Couldn't change the quality.") { preferences.setMobileQuality(quality) }

    fun setMatchFrameRate(enabled: Boolean) = save(SettingKey.MatchFrameRate, "Couldn't save the setting.") { preferences.setMatchFrameRate(enabled) }

    fun setAudioPassthrough(mode: AudioPassthrough) = save(SettingKey.AudioPassthrough, "Couldn't save the setting.") { preferences.setAudioPassthrough(mode) }

    fun setSubtitleSize(size: SubtitleSize) = save(SettingKey.SubtitleSize, "Couldn't save the setting.") { preferences.setSubtitleSize(size) }

    fun setSkip(kind: SegmentKind, mode: SkipMode) {
        if (state.value.accountReady) save(SettingKey.Skip(kind), "Couldn't save the skip setting.") { preferences.setSkip(kind, mode) }
    }

    fun setAutoplayNext(enabled: Boolean) {
        if (state.value.accountReady) save(SettingKey.Autoplay, "Couldn't save autoplay.") { preferences.setAutoplayNext(enabled) }
    }

    /** Runs [key]'s last change again, after it failed. */
    fun retry(key: SettingKey) {
        val (_, block) = latest[key] ?: return
        save(key, GENERIC_SAVE_FAILURE, block)
    }

    /** How subtitles start; choosing one puts away the notice that the server chose it. */
    fun setSubtitleChoice(choice: SubtitleChoice) {
        if (choice !in state.value.subtitleChoices) return
        local.update { it.copy(foreignAudioTurnedOn = false) }
        changeSubtitles(SettingKey.SubtitleChoice) { it.withChoice(choice) }
    }

    fun setAutomaticSubtitles(enabled: Boolean) = changeSubtitles(SettingKey.AutomaticSubtitles) { it.copy(automaticDownload = enabled) }

    fun addSubtitleLanguage(tag: String) = changeSubtitles(SettingKey.SubtitleLanguages) { it.copy(languages = added(it.languages, tag)) }

    fun removeSubtitleLanguage(tag: String) = changeSubtitles(SettingKey.SubtitleLanguages) { it.copy(languages = it.languages.filterNot { language -> language == tag }) }

    fun moveSubtitleLanguage(tag: String, offset: Int) = changeSubtitles(SettingKey.SubtitleLanguages) { it.copy(languages = moved(it.languages, tag, offset)) }

    fun addAudioLanguage(tag: String) = changeAudio { added(it, tag) }

    fun removeAudioLanguage(tag: String) = changeAudio { it.filterNot { language -> language == tag } }

    fun moveAudioLanguage(tag: String, offset: Int) = changeAudio { moved(it, tag, offset) }

    /** The languages of [list], in order. */
    fun languages(list: LanguageList): List<String> = state.value.playback.let { if (list == LanguageList.Audio) it.audio.languages else it.subtitles.languages }

    fun addLanguage(list: LanguageList, tag: String) = if (list == LanguageList.Audio) addAudioLanguage(tag) else addSubtitleLanguage(tag)

    fun removeLanguage(list: LanguageList, tag: String) = if (list == LanguageList.Audio) removeAudioLanguage(tag) else removeSubtitleLanguage(tag)

    /** Moves [tag] [offset] places up (negative) or down [list]. */
    fun moveLanguage(list: LanguageList, tag: String, offset: Int) = if (list == LanguageList.Audio) moveAudioLanguage(tag, offset) else moveSubtitleLanguage(tag, offset)

    /** What can be done to [tag] in [list]: move up, move down (when it can), remove. */
    fun languageActions(list: LanguageList, tag: String): List<LanguageAction> {
        val languages = languages(list)
        val index = languages.indexOf(tag)
        return listOfNotNull(
            LanguageAction.MoveUp.takeIf { index > 0 },
            LanguageAction.MoveDown.takeIf { index >= 0 && index < languages.lastIndex },
            LanguageAction.Remove,
        )
    }

    fun act(list: LanguageList, tag: String, action: LanguageAction) = when (action) {
        LanguageAction.MoveUp -> moveLanguage(list, tag, -1)
        LanguageAction.MoveDown -> moveLanguage(list, tag, 1)
        LanguageAction.Remove -> removeLanguage(list, tag)
    }

    private fun changeSubtitles(key: SettingKey, change: (SubtitlePreferences) -> SubtitlePreferences) {
        if (!state.value.subtitlePreferencesAvailable || !state.value.accountReady) return
        save(key, "Couldn't save subtitle settings. Your previous settings are still in use.") {
            preferences.setSubtitles(change(preferences.playback.value.subtitles))
        }
    }

    private fun changeAudio(change: (List<String>) -> List<String>) {
        if (!state.value.audioLanguagesAvailable || !state.value.accountReady) return
        save(SettingKey.AudioLanguages, "Couldn't save audio languages. Your previous settings are still in use.") {
            // From the languages as they are when this change's turn comes, earlier changes included.
            val before = preferences.playback.value.audio.languages
            val after = change(before)
            if (after != before && preferences.setAudioLanguages(after)) local.update { it.copy(foreignAudioTurnedOn = true) }
        }
    }

    /** Signs another device out, after its dialog: the dialog shows [State.revoking], then closes, or shows [State.revokeFailure]. */
    fun revoke(session: DeviceSession) {
        if (local.value.revoking != null) return
        local.update { it.copy(revoking = session.id, revokeFailure = null) }
        viewModelScope.launch {
            attempt { auth.revoke(session.id) }
                .onSuccess {
                    local.update { l ->
                        val sessions = (l.sessions as? Load.Ready)?.let { Load.Ready(it.data.filter { s -> s.id != session.id }) } ?: l.sessions
                        l.copy(revoking = null, sessions = sessions, notice = revokedNotice(session.name))
                    }
                }
                .onFailure { e ->
                    local.update { it.copy(revoking = null, revokeFailure = RevokeFailure(session.id, errorMessage(e, "Couldn't sign ${session.name} out. It is still signed in."))) }
                }
        }
    }

    /** The password was changed (by its own screen), which signed [signedOut] other devices out: says so, and lists the devices again. */
    fun passwordChanged(signedOut: Int) {
        local.update { it.copy(notice = passwordChangedNotice(signedOut)) }
        loadSessions()
    }

    /** A confirmation's dialog closed: forget its failure. */
    fun clearConfirmation() = local.update { it.copy(revokeFailure = null, signOutFailure = null) }

    fun noticeShown() = local.update { it.copy(notice = null) }

    /** Signs this device out; [AuthRepository.user] turns null, and [events] says [Event.SignedOut]. */
    fun signOut() {
        if (local.value.signingOut) return
        local.update { it.copy(signingOut = true, signOutFailure = null) }
        viewModelScope.launch {
            attempt { auth.signOut() }
                .onSuccess {
                    local.update { it.copy(signingOut = false) }
                    _events.send(Event.SignedOut)
                }
                .onFailure { e -> local.update { it.copy(signingOut = false, signOutFailure = errorMessage(e, "Couldn't sign out. You're still signed in.")) } }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    /** Shows [message] once. */
    fun say(message: String) = local.update { it.copy(message = message) }

    private companion object {
        const val GENERIC_SAVE_FAILURE = "Couldn't save the setting."

        /** At most 16 of each, as the server takes. */
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

    /**
     * A change to [key], kept on the server or this device: the row says
     * Saving… until it ends, and a failure stays on the row with the server's
     * reason, else [failure]. A later change to the same setting supersedes it.
     */
    private fun save(key: SettingKey, failure: String, block: suspend () -> Unit) {
        val number = (latest[key]?.first ?: 0) + 1
        latest[key] = number to block
        local.update { it.copy(writes = it.writes + (key to (it.writes[key] ?: 0) + 1), failed = it.failed - key) }
        viewModelScope.launch {
            val result = attempt { block() }
            local.update { l ->
                val left = (l.writes[key] ?: 1) - 1
                val writes = if (left > 0) l.writes + (key to left) else l.writes - key
                val current = latest[key]?.first == number
                val e = result.exceptionOrNull()
                if (e != null && current) l.copy(writes = writes, failed = l.failed + (key to SaveFailure(errorMessage(e, failure), e.isUnreachable())))
                else l.copy(writes = writes)
            }
        }
    }
}

/**
 * A setting that saves on its own, for its row's Saving… and its failure.
 * [Skip] is one per part.
 */
sealed interface SettingKey {
    data object Quality : SettingKey
    data object MobileQuality : SettingKey
    data object MatchFrameRate : SettingKey
    data object AudioPassthrough : SettingKey
    data object SubtitleSize : SettingKey
    data object Autoplay : SettingKey
    data class Skip(val kind: SegmentKind) : SettingKey
    data object AudioLanguages : SettingKey
    data object SubtitleLanguages : SettingKey
    data object SubtitleChoice : SettingKey
    data object AutomaticSubtitles : SettingKey
    data object DeviceName : SettingKey

    companion object {
        /** The Audio & subtitles settings. */
        val LANGUAGES: Set<SettingKey> = setOf(AudioLanguages, SubtitleLanguages, SubtitleChoice, AutomaticSubtitles)
    }
}

/** Whether the account's settings have loaded. */
sealed interface AccountLoad {
    data object Loading : AccountLoad
    data object Ready : AccountLoad

    /** They didn't: [reason] in the repository's words, and whether the server couldn't be reached at all. */
    data class Failed(val reason: String, val unreachable: Boolean) : AccountLoad
}

/** Another device that couldn't be signed out, and why. */
data class RevokeFailure(val sessionId: String, val reason: String)

/** The two ordered language lists in Audio & subtitles, with their titles. */
enum class LanguageList(val title: String) { Audio("Audio languages"), Subtitles("Subtitle languages") }

/** What a language's options offer, in the order they are listed. */
enum class LanguageAction(val label: String) { MoveUp("Move up"), MoveDown("Move down"), Remove("Remove language") }
