package media.watchmy.client.core.playback

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** Whether the app is in the foreground; [observe] reports changes until closed. */
fun interface AppVisibility {
    fun observe(onChange: (visible: Boolean) -> Unit): AutoCloseable

    companion object {
        val AlwaysVisible = AppVisibility { AutoCloseable {} }
    }
}

/** [AppVisibility] from [ProcessLifecycleOwner]: started and stopped as the app's activities are. Observe on the main thread. */
object ProcessVisibility : AppVisibility {
    override fun observe(onChange: (visible: Boolean) -> Unit): AutoCloseable {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onChange(true)

            override fun onStop(owner: LifecycleOwner) = onChange(false)
        }
        lifecycle.addObserver(observer)
        return AutoCloseable { lifecycle.removeObserver(observer) }
    }
}
