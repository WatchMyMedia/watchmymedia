package media.watchmy.client.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.PlayRequest

/**
 * Where a tap on a poster card goes. Movies and shows open their page, and a
 * Recently added show that stands for one episode opens that episode's page
 * when [onOpenEpisode] is given (PLAN-EPISODE-PAGE.md §10). A file the server
 * hasn't matched yet has no page (its ID is the file's): a single file plays
 * as it is, and a folder of them (a show nobody has matched) waits for a
 * match in the web app, so [onNotMatched] says so.
 */
fun BrowseItem.open(
    onOpenShow: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onNotMatched: () -> Unit,
    onOpenEpisode: ((showId: Long, season: Int, episodeId: Long) -> Unit)? = null,
) {
    val episode = episodeId
    val season = episodeSeason
    when (kind) {
        ItemKind.Movie -> onOpenMovie(id)
        ItemKind.Show -> if (onOpenEpisode != null && episode != null && season != null) onOpenEpisode(id, season, episode) else onOpenShow(id)
        ItemKind.Unmatched -> if ((fileCount ?: 1) <= 1) onPlay(PlayRequest(fileId = id, title = title)) else onNotMatched()
    }
}

const val NOT_MATCHED_MESSAGE = "Not matched yet. Match it in WatchMyMedia on the web to watch it here."

/** A short notice for [open]'s `onNotMatched`, on phones and TVs alike. */
@Composable
fun rememberNotMatchedNotice(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { { Toast.makeText(context, NOT_MATCHED_MESSAGE, Toast.LENGTH_LONG).show() } }
}
