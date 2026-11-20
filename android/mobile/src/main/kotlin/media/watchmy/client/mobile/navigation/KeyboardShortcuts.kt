package media.watchmy.client.mobile.navigation

import android.view.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A keyboard's app-wide shortcuts on a phone or tablet:
 * Ctrl+F, / or a keyboard's Search key opens
 * Search. The activity passes on the key presses no screen took
 * ([onKeyDown]), so a field being typed in keeps its /, and Compose needs no
 * focus to see them. It reads the press, not the release: Ctrl may be let go
 * before F. The signed-in screens set [onSearch] while they show.
 */
class KeyboardShortcuts {
    /** Opens Search; false when it can't now (in a player). */
    internal var onSearch: (() -> Boolean)? = null

    /** True when [keyCode], first pressed, was a shortcut and something took it. */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount != 0 || !isSearch(keyCode, event.isCtrlPressed, event.isAltPressed || event.isMetaPressed)) return false
        return onSearch?.invoke() == true
    }

    companion object {
        internal fun isSearch(keyCode: Int, ctrl: Boolean, otherModifier: Boolean): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_SEARCH -> true
            KeyEvent.KEYCODE_F -> ctrl && !otherModifier
            KeyEvent.KEYCODE_SLASH -> !ctrl && !otherModifier
            else -> false
        }
    }
}

/** The activity's [KeyboardShortcuts]; null where there is none (previews, tests). */
val LocalKeyboardShortcuts = staticCompositionLocalOf<KeyboardShortcuts?> { null }
