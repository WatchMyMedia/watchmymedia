package media.watchmy.client.tv.navigation

import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.attempt

/**
 * The route for a watchmymedia:// link ([DeepLink.open]), or null for one the
 * TV does not open (such as the phone's link?code=). A launcher's link (Watch
 * Next) opens only for the [account] it was published for, so another
 * viewer's row can't start their player, and it is the only link that plays.
 * Any other play link opens the item's page, which [library] looks up; null
 * when it can't say.
 */
suspend fun deepLinkRoute(link: String, account: String?, library: LibraryRepository): Any? =
    when (val l = DeepLink.open(link, account)) {
        is DeepLink.Play -> TvRoutes.Player(fileId = l.fileId, startMs = l.startMs, title = l.title.orEmpty(), subtitle = l.subtitle)
        is DeepLink.Details -> pageRoute(attempt { library.filePage(l.fileId) }.getOrNull())
        DeepLink.LiveGuide -> TvRoutes.LiveTV
        else -> pageRoute(l)
    }

/** A show's, season's or episode's page, or a movie's. */
private fun pageRoute(link: DeepLink?): Any? = when (link) {
    is DeepLink.Show -> TvRoutes.Show(id = link.id, season = link.season, episode = link.episode)
    is DeepLink.Movie -> TvRoutes.Movie(id = link.id)
    else -> null
}
