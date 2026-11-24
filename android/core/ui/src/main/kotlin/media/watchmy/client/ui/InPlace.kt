package media.watchmy.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import media.watchmy.client.model.DeepLink

/**
 * Opens a title on another registered server in place, without switching
 * the app: a card merged from several servers whose copy is elsewhere, a
 * search result, a link. The signed-in screens' nav host sets where it
 * goes ([ProvideInPlace]); [open] is false when nothing is set, as for a
 * screen on its own in a test, and the caller does what it did before.
 */
object InPlace {
    @Volatile
    private var opener: ((serverId: String, page: DeepLink) -> Unit)? = null

    /** Opens [page] on [serverId] in place; false for the server in use (null), or with no nav host to open it. */
    fun open(serverId: String?, page: DeepLink): Boolean {
        if (serverId == null) return false
        val go = opener ?: return false
        go(serverId, page)
        return true
    }

    internal fun set(go: ((String, DeepLink) -> Unit)?) {
        opener = go
    }
}

/** The nav host's way to open another server's title in place, while it is on screen. */
@Composable
fun ProvideInPlace(open: (serverId: String, page: DeepLink) -> Unit) {
    val latest = rememberUpdatedState(open)
    DisposableEffect(Unit) {
        val go: (String, DeepLink) -> Unit = { id, page -> latest.value(id, page) }
        InPlace.set(go)
        onDispose { InPlace.set(null) }
    }
}

/** A card's picture from the server its copy is on ([Where.serverId]), signed for that server alone. */
fun media.watchmy.client.model.LibraryRepository.imageFor(where: media.watchmy.client.model.Where, path: String?, size: media.watchmy.client.model.ImageSize): String? =
    (at(where.serverId) ?: this).imageUrl(path, size)

private fun media.watchmy.client.model.LibraryRepository.at(serverId: String?) = if (serverId == null) this else on(serverId)
