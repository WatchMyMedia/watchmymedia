package media.watchmy.client.presentation.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PersonPage
import media.watchmy.client.model.attempt
import media.watchmy.client.model.errorMessage
import media.watchmy.client.presentation.Freshness
import media.watchmy.client.presentation.Load
import media.watchmy.client.presentation.ShownPage

/**
 * Where a cast member leads: their movies and shows in the library, each
 * with the part they play ([LibraryRepository.person]). [name] is what the
 * cast card said, shown while the page loads. A person no title here has any
 * more (404) reads as [State.gone], not as a failure.
 */
class PersonViewModel(
    private val library: LibraryRepository,
    private val personId: Long,
    val name: String,
) : ViewModel(), ShownPage {
    data class State(
        val person: Load<PersonPage> = Load.Loading,
        /** No title in the library has them now. */
        val gone: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val freshness = Freshness(viewModelScope) { load(quiet = true) }

    init {
        load()
        freshness.follow(library.libraryChanges)
    }

    override fun setShown(shown: Boolean) = freshness.setShown(shown)

    fun retry() = load()

    private fun load(quiet: Boolean = false) {
        if (!quiet || _state.value.person !is Load.Ready) _state.update { it.copy(person = Load.Loading, gone = false) }
        viewModelScope.launch {
            attempt { library.person(personId) }
                .onSuccess { p -> _state.update { it.copy(person = Load.Ready(p), gone = false) } }
                .onFailure { e ->
                    val gone = e is ApiException && e.status == 404
                    _state.update { if (quiet && it.person is Load.Ready) it else it.copy(person = Load.Failed(errorMessage(e)), gone = gone) }
                }
        }
    }
}
