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

/**
 * Back for a screen or an overlay, however the device sends it.
 *
 * A remote's Back reaches the app as a key while something has focus, and
 * Compose would turn it into a focus move before a back callback saw it; on
 * Android 16 and later, an app that targets SDK 36 or higher gets no key at
 * all, only a back callback. This takes both: the key, on key-up after its
 * own key-down, before anything inside sees it, and a callback on the
 * window's `OnBackPressedDispatcher` while the element is attached and
 * [enabled]. One press calls [onBack] once, by whichever path it came.
 *
 * Overlays stack: an element registers its callback again whenever it
 * becomes enabled, so the overlay that opened last takes Back first, as the
 * key path gives it to the outermost enabled element. Inside a `Dialog`,
 * the dialog's own dispatcher is used.
 */
fun Modifier.onBack(enabled: Boolean = true, onBack: () -> Unit): Modifier = this then OnBackElement(enabled, onBack)

private data class OnBackElement(val enabled: Boolean, val onBack: () -> Unit) : ModifierNodeElement<OnBackNode>() {
    override fun create() = OnBackNode(enabled, onBack)

    override fun update(node: OnBackNode) = node.update(enabled, onBack)

    override fun InspectorInfo.inspectableProperties() {
        name = "onBack"
        properties["enabled"] = enabled
    }
}

private class OnBackNode(private var enabled: Boolean, private var onBack: () -> Unit) :
    Modifier.Node(), KeyInputModifierNode {
    private var dispatcher: OnBackPressedDispatcher? = null

    /** A key-down this element took, so a key-up left over from the screen before is not a press. */
    private var down = false

    private val callback = object : OnBackPressedCallback(enabled) {
        override fun handleOnBackPressed() = onBack()
    }

    override fun onAttach() {
        // The view the node is attached in, not LocalView: a node can't follow a CompositionLocal from onAttach.
        dispatcher = requireView().findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
        register()
    }

    override fun onDetach() {
        callback.remove()
        dispatcher = null
        down = false
    }

    fun update(enabled: Boolean, onBack: () -> Unit) {
        this.onBack = onBack
        if (enabled == this.enabled) return
        this.enabled = enabled
        down = false
        if (isAttached) register()
    }

    /** Adds the callback anew, so it comes before every one added earlier. */
    private fun register() {
        callback.remove()
        callback.isEnabled = enabled
        if (enabled) dispatcher?.addCallback(callback)
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
