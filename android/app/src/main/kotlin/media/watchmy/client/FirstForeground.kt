package media.watchmy.client

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * The process's first time in the foreground: [lifecycle] (the
 * ProcessLifecycleOwner's) reaching ON_START. What only someone using the
 * app needs, such as the Cast SDK, starts then, rather than whenever the
 * process starts: a reminder's alarm, the download service or a WorkManager
 * job start it too, with nobody looking. Used on the main thread.
 */
class FirstForeground(private val lifecycle: Lifecycle) {
    private val waiting = mutableListOf<() -> Unit>()
    private var reached = false

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            reached = true
            lifecycle.removeObserver(this)
            waiting.toList().also { waiting.clear() }.forEach { it() }
        }
    }

    init {
        lifecycle.addObserver(observer)
    }

    /** Runs [block] at the first ON_START, or now when that has been. */
    fun then(block: () -> Unit) {
        if (reached) block() else waiting += block
    }
}
