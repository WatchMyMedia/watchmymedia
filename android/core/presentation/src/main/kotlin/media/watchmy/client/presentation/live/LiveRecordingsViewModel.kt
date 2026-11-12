package media.watchmy.client.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.AuthRepository
import media.watchmy.client.model.LIVE_RECORDING_API_LEVEL
import media.watchmy.client.model.LivePersonalChange
import media.watchmy.client.model.LiveProgramme
import media.watchmy.client.model.LiveRecording
import media.watchmy.client.model.LiveRecordingRule
import media.watchmy.client.model.LiveRepository
import media.watchmy.client.model.ManualRecording
import media.watchmy.client.model.RecordingConflict
import media.watchmy.client.model.RecordingOptions
import media.watchmy.client.model.RecordingPage
import media.watchmy.client.model.RecordingSection
import media.watchmy.client.model.ServerRepository
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage

/** Household schedule; one indexed page per section. Refreshes on SSE, never a timer. */
class LiveRecordingsViewModel(
    private val live: LiveRepository,
    private val servers: ServerRepository,
    private val auth: AuthRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val loadSections: Boolean = true,
) : ViewModel() {
    data class State(
        val available: Boolean = false,
        val canRecord: Boolean = false,
        val loading: Boolean = false,
        val busy: Boolean = false,
        val pages: Map<RecordingSection, RecordingPage> = emptyMap(),
        val error: String? = null,
        val saved: Boolean = false,
        val notice: String? = null,
        val noticeProgrammeId: String? = null,
        /**
         * A recording that won't record because the tuners are taken, whose
         * clash shows to choose which recording gives way: one just set, or
         * a Won't record row picked in the list.
         */
        val clash: LiveRecording? = null,
        /** Why New recording's Save didn't save: its own, so the list's errors stay out of the form. */
        val manualError: String? = null,
        /** The section whose next page is being fetched; other actions stay available meanwhile. */
        val paging: RecordingSection? = null,
    ) {
        fun page(section: RecordingSection) = pages[section] ?: RecordingPage()
        fun recordingFor(programme: LiveProgramme): LiveRecording? = pages.values.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.programmeId == programme.id }
        val empty: Boolean get() = pages.values.all { it.items.isEmpty() && it.rules.isEmpty() }
    }
    private data class Identity(val server: String?, val address: String?, val user: Long?, val level: Int)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loading: Job? = null
    private var mutation: Job? = null
    private var paging: Job? = null
    private var generation = 0L

    /** The signed-in server's level; capabilities are asked again on every refresh, so recording turned on later shows. */
    private var level = 0

    /** How many pages of each section the person has shown, which a refresh fetches again. */
    private val shownPages = mutableMapOf<RecordingSection, Int>()

    init {
        viewModelScope.launch {
            combine(servers.current, auth.user) { session, user -> Identity(session?.server?.id, session?.server?.baseUrl, user?.id, session?.server?.apiLevel ?: 0) }
                .distinctUntilChanged().collectLatest { identity ->
                    level = identity.level
                    generation++
                    loading?.cancel()
                    mutation?.cancel()
                    paging?.cancel()
                    shownPages.clear()
                    _state.value = State(available = level >= LIVE_RECORDING_API_LEVEL)
                    refresh()
                }
        }
        // The repository's own change and the server's event for it arrive together: one refresh answers both.
        viewModelScope.launch { live.personalChanges.collect { if (it == LivePersonalChange.Recordings) load(REFRESH_COALESCE_MS) } }
    }

    fun refresh() = load(0)

    private fun load(afterMs: Long) {
        if (level < LIVE_RECORDING_API_LEVEL) return
        loading?.cancel()
        val token = generation
        loading = viewModelScope.launch {
            if (afterMs > 0) delay(afterMs)
            _state.update { it.copy(loading = it.pages.isEmpty(), error = null) }
            attempt {
                val capabilities = live.capabilities()
                val pages = if (capabilities.canRecord && loadSections) RecordingSection.entries.map { section -> async { section to pagesOf(section) } }.awaitAll().toMap() else emptyMap()
                capabilities to pages
            }.onSuccess { (capabilities, pages) ->
                if (token == generation) _state.update { it.copy(available = capabilities.recording, canRecord = capabilities.canRecord, pages = pages, loading = false) }
            }.onFailure { error -> if (token == generation) _state.update { it.copy(loading = false, error = errorMessage(error)) } }
        }
    }

    /** A section's first page, then as many more as were shown, so Show more's pages stay through a refresh. */
    private suspend fun pagesOf(section: RecordingSection): RecordingPage {
        var page = live.recordings(section)
        repeat((shownPages[section] ?: 1) - 1) {
            val cursor = page.nextCursor ?: return page
            page = page.plus(live.recordings(section, cursor))
        }
        return page
    }

    private fun RecordingPage.plus(next: RecordingPage) = next.copy(items = (items + next.items).distinctBy { it.id }, rules = (rules + next.rules).distinctBy { it.id })

    fun next(section: RecordingSection) {
        val state = _state.value
        val cursor = state.page(section).nextCursor ?: return
        if (!state.canRecord || state.paging != null) return
        val token = generation
        paging = viewModelScope.launch {
            _state.update { it.copy(paging = section, error = null) }
            attempt { live.recordings(section, cursor) }
                .onSuccess { page ->
                    if (token != generation) return@onSuccess
                    shownPages[section] = (shownPages[section] ?: 1) + 1
                    _state.update { it.copy(pages = it.pages + (section to it.page(section).plus(page))) }
                }
                .onFailure { error -> if (token == generation) _state.update { it.copy(error = errorMessage(error)) } }
            if (token == generation) _state.update { it.copy(paging = null) }
        }
    }

    fun record(programme: LiveProgramme, options: RecordingOptions = RecordingOptions()) {
        if (!options.valid || programme.endMs <= clock()) return
        mutate {
            val recording = live.record(programme, options)
            _state.update { it.copy(saved = true, noticeProgrammeId = programme.id, notice = recording?.let { row -> "${row.title}: ${row.label}." }, clash = recording?.takeIf { row -> row.state == "conflict" }) }
        }
    }

    fun manual(request: ManualRecording) {
        request.validation(clock())?.let { message -> _state.update { it.copy(manualError = message) }; return }
        _state.update { it.copy(manualError = null) }
        mutate(manual = true) {
            val recording = live.recordManual(request)
            _state.update { it.copy(saved = true, clash = recording?.takeIf { row -> row.state == "conflict" }) }
        }
    }

    /** Shows the clash for a Won't record row, to choose which recording gives way. */
    fun chooseClash(recording: LiveRecording) { if (recording.state == "conflict") _state.update { it.copy(clash = recording) } }

    /** Keeps the recordings that hold the tuners: the clashing one stays under Won't record. */
    fun keepClash() = _state.update { it.copy(clash = null) }

    fun cancel(recording: LiveRecording) { if (recording.canCancel) cancel(recording.id) }
    fun cancel(id: String) = mutate { live.cancelRecording(id) }
    fun deleteFile(recording: LiveRecording, recordAgain: Boolean = false) { if (recording.canDelete) mutate { live.deleteRecordingFile(recording.id, recordAgain) } }
    /**
     * Record this instead: [over] gives way, one of the recordings holding the
     * tuners and not yet recording. Without it (a server that names none) this
     * one goes ahead of every recording not yet recording.
     */
    fun prefer(recording: LiveRecording, over: RecordingConflict? = null) {
        if (!recording.canCancel || recording.state != "conflict" || over?.running == true) return
        if (over != null && recording.conflictsWith.none { it.id == over.id }) return
        mutate {
            live.updateRecording(recording.id, prefer = true, over = over?.id)
            _state.update { it.copy(clash = null, noticeProgrammeId = recording.programmeId, notice = "${recording.title} will record." + (over?.let { yielded -> " ${yielded.title} won’t." } ?: "")) }
        }
    }
    fun update(recording: LiveRecording, options: RecordingOptions) { if (recording.canCancel && options.valid) mutate { live.updateRecording(recording.id, options) } }
    fun update(rule: LiveRecordingRule, options: RecordingOptions) { if (rule.canCancel && options.valid) mutate { live.updateRecordingRule(rule.id, options) } }
    fun delete(rule: LiveRecordingRule) { if (rule.canCancel) mutate { live.deleteRecordingRule(rule.id) } }
    fun clearSaved() = _state.update { it.copy(saved = false, manualError = null) }
    fun clearError() = _state.update { it.copy(error = null, notice = null, noticeProgrammeId = null) }

    /** [manual]: New recording's Save, whose refusal shows in its form rather than on the list. */
    private fun mutate(manual: Boolean = false, action: suspend () -> Unit) {
        if (!_state.value.canRecord || _state.value.busy) return
        val token = generation
        mutation = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, saved = false) }
            attempt { action() }.onSuccess { if (token == generation) load(REFRESH_COALESCE_MS) }
                .onFailure { error -> if (token == generation) _state.update { if (manual) it.copy(manualError = errorMessage(error)) else it.copy(error = errorMessage(error)) } }
            if (token == generation) _state.update { it.copy(busy = false) }
        }
    }

    private companion object {
        /** A change, the repository's note of it and the server's event come together; one refresh after the last answers them all. */
        const val REFRESH_COALESCE_MS = 400L
    }
}
