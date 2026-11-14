package media.watchmy.client.mobile.navigation

import android.view.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A keyboard's app-wide shortcuts on a phone or tablet (PLAN-ANDROID-REVIEW
 * §6.3, PLAN-TABLETS §8): Ctrl+F, / or a keyboard's Search key opens
 * Search. The activity passes on the keys no screen took ([onKeyUp]), so a
 * field being typed in keeps its /, and Compose needs no focus to see them.
 * The signed-in screens set [onSearch] while they show.
 */
class KeyboardShortcuts {
    /** Opens Search; false when it can't now (in a player). */
    internal var onSearch: (() -> Boolean)? = null

    /** True when [keyCode] was a shortcut and something took it. */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isSearch(keyCode, event.isCtrlPressed, event.isAltPressed || event.isMetaPressed)) return false
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
