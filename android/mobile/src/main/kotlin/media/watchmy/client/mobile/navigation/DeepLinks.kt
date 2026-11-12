package media.watchmy.client.mobile.navigation

import media.watchmy.client.model.DeepLink
import media.watchmy.client.model.LibraryRepository
import media.watchmy.client.model.PlayRequest
import media.watchmy.client.model.attempt
import media.watchmy.client.model.playRequest

/**
 * What to play for a `play/{fileId}` link. The link carries only the file,
 * so the title comes from Home (Continue watching, Recently aired, the hero),
 * where a Watch Next entry will be; otherwise the request goes untitled and
 * the player names the file itself ([LibraryRepository.playRequest]).
 */
suspend fun resolvePlayLink(library: LibraryRepository, fileId: Long): PlayRequest {
    val home = attempt { library.home() }.getOrNull()
    return home?.continueWatching?.firstOrNull { it.fileId == fileId }?.playRequest()
        ?: home?.recentlyAired?.firstOrNull { it.fileId == fileId }?.playRequest()
        ?: home?.hero?.takeIf { it.fileId == fileId }?.playRequest(home.recentlyAired)
        ?: PlayRequest(fileId = fileId, title = "")
}

/**
 * The page a `play/{fileId}` link opens when it may not play
 * ([DeepLink.Details]): the movie's, or the episode's in its show; null when
 * the library can't say.
 */
suspend fun resolveDetailsLink(library: LibraryRepository, fileId: Long): DeepLink? =
    attempt { library.filePage(fileId) }.getOrNull()?.takeIf { it is DeepLink.Show || it is DeepLink.Movie }
