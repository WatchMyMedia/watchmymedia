package media.watchmy.client.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What a screen shows of something it loads: loading, failed with a message
 * to show, or ready. The Apple apps' `Loadable` (WMMModel Loadable.swift).
 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Failed(val message: String) : Load<Nothing>

    data class Ready<T>(val data: T) : Load<T>
}

/** The data once it is ready, else null. */
val <T> Load<T>.value: T? get() = (this as? Load.Ready)?.data

/**
 * The newest of a kind of load. Starting one cancels the one before, so an
 * older answer can't land over a newer one: every coroutine here runs on
 * the main thread, where a cancelled load never resumes past its call.
 */
internal class Latest(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch(block = block)
    }
}
