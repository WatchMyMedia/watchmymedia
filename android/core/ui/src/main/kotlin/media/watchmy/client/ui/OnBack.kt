package media.watchmy.client.ui

import android.view.KeyEvent.KEYCODE_BACK
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireView
import androidx.compose.ui.platform.InspectorInfo
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcher.Companion.PRIORITY_DEFAULT
import androidx.navigationevent.NavigationEventDispatcher.Companion.PRIORITY_OVERLAY
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner

/**
 * Back for a screen or an overlay, however the device sends it.
 *
 * A remote's Back reaches the app as a key while something has focus, and
 * Compose would turn it into a focus move before a back callback saw it; on
 * Android 16 and later, an app that targets SDK 36 or higher gets no key at
 * all, only a back callback. This takes both: the key, on key-up after its
 * own key-down, before anything inside sees it, and a handler on the
 * window's back dispatcher (the one `BackHandler` and `NavHost` use) while
 * the element is attached and [enabled]. One press calls [onBack] once, by
 * whichever path it came.
 *
 * Overlays stack: an element registers again whenever it becomes enabled,
 * so the overlay that opened last takes Back first, as the key path gives it
 * to the outermost enabled element. Something that floats over every screen,
 * such as the reminder banner, passes [overlay]: it then takes Back before
 * any screen's, including one opened after it. Inside a `Dialog`, the
 * dialog's own dispatcher is used.
 */
fun Modifier.onBack(enabled: Boolean = true, overlay: Boolean = false, onBack: () -> Unit): Modifier =
    this then OnBackElement(enabled, overlay, onBack)

private data class OnBackElement(val enabled: Boolean, val overlay: Boolean, val onBack: () -> Unit) : ModifierNodeElement<OnBackNode>() {
    override fun create() = OnBackNode(enabled, overlay, onBack)

    override fun update(node: OnBackNode) = node.update(enabled, overlay, onBack)

    override fun InspectorInfo.inspectableProperties() {
        name = "onBack"
        properties["enabled"] = enabled
        properties["overlay"] = overlay
    }
}

private class OnBackNode(private var enabled: Boolean, private var overlay: Boolean, private var onBack: () -> Unit) :
    Modifier.Node(), KeyInputModifierNode {
    /** The window's back dispatcher; [legacy] only where a window has none (older hosts). */
    private var events: NavigationEventDispatcher? = null
    private var legacy: OnBackPressedDispatcher? = null

    /** A key-down this element took, so a key-up left over from the screen before is not a press. */
    private var down = false

    private val handler = object : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, isBackEnabled = false) {
        override fun onBackCompleted() = onBack()
    }

    private val callback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = onBack()
    }

    override fun onAttach() {
        // The view the node is attached in, not LocalView: a node can't follow a CompositionLocal from onAttach.
        val view = requireView()
        events = view.findViewTreeNavigationEventDispatcherOwner()?.navigationEventDispatcher
        legacy = if (events == null) view.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher else null
        register()
    }

    override fun onDetach() {
        handler.remove()
        callback.remove()
        events = null
        legacy = null
        down = false
    }

    fun update(enabled: Boolean, overlay: Boolean, onBack: () -> Unit) {
        this.onBack = onBack
        if (enabled == this.enabled && overlay == this.overlay) return
        this.enabled = enabled
        this.overlay = overlay
        down = false
        if (isAttached) register()
    }

    /** Adds the handler anew, so it comes before every one of its priority added earlier. */
    private fun register() {
        handler.remove()
        callback.remove()
        handler.isBackEnabled = enabled
        callback.isEnabled = enabled
        if (!enabled) return
        val events = events
        if (events != null) {
            events.addHandler(handler, if (overlay) PRIORITY_OVERLAY else PRIORITY_DEFAULT)
        } else {
            legacy?.addCallback(callback)
        }
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        if (!enabled || native.keyCode != KEYCODE_BACK) return false
        when (event.type) {
            KeyEventType.KeyDown -> if (native.repeatCount == 0) down = true
            KeyEventType.KeyUp -> if (down) {
                down = false
                onBack()
            }
        }
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false
}
