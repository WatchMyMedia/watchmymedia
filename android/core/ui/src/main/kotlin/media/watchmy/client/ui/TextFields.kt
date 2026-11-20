package media.watchmy.client.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

/**
 * The text of a field, held where it is typed:
 * a field driven through a StateFlow gets its value back a frame
 * late, so fast typing, a paste or a password manager could lose characters.
 * What is typed goes to [onChange] (the ViewModel), which never writes back
 * while someone types. When the ViewModel sets the text itself (a recent
 * search chosen, the form started over), it moves [revision] on, and the
 * field takes [value]. The TV's in-app keyboard edits the same state.
 */
@Composable
fun rememberModelTextField(value: String, revision: Int, onChange: (String) -> Unit): TextFieldState {
    val field = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState(value) }
    val report by rememberUpdatedState(onChange)
    var seen by rememberSaveable { mutableIntStateOf(revision) }
    LaunchedEffect(revision) {
        if (revision != seen) {
            seen = revision
            if (field.text.toString() != value) field.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { report(it) } }
    return field
}
