package media.watchmy.client.model

import java.net.URI
import java.net.URLDecoder

/**
 * A watchmymedia:// link, as the phone and the TV read it (and the Apple
 * apps' WMMFeatures DeepLink.swift): `play/{fileId}` (Watch Next; `t`, the
 * start in ms, and `title` and `subtitle` fill in the player's header until
 * it loads), `shows/{id}` (optionally `?season=`, and `&episode=` for an
 * episode's page), `movies/{id}`, `link?code=` (a TV's pairing code) and
 * `live/{channelId}` (a Live TV reminder's Watch; `live` alone opens the
 * guide), the phone launcher's shortcuts' `search` and `downloads`, and
 * `home` (the new-episode notifications' "more shows").
 * Open links with [open],
 * which plays only what a launcher or reminder published for the account
 * signed in. A launcher's link for another registered account switches to
 * its server first ([serverFor]).
 */
sealed interface DeepLink {
    data class Play(val fileId: Long, val startMs: Long? = null, val title: String? = null, val subtitle: String? = null) : DeepLink
    data class Show(val id: Long, val season: Int? = null, val episode: Long? = null) : DeepLink
    data class Movie(val id: Long) : DeepLink
    data class LinkTv(val code: String?) : DeepLink

    /** Watch the Live TV channel [channelId]: a reminder's Watch for the account signed in. */
    data class Live(val channelId: String) : DeepLink

    /** The Live TV guide: what a `live/` link opens when no reminder published it for the account signed in. */
    data object LiveGuide : DeepLink

    /**
     * The page of the movie or episode the file [fileId] plays
     * ([LibraryRepository.filePage]): what a play link opens when no
     * launcher published it for the account signed in.
     */
    data class Details(val fileId: Long) : DeepLink

    /** Search, from the phone launcher's shortcut (`search`). The TV opens nothing for it. */
    data object Search : DeepLink

    /** Downloads, from the phone launcher's shortcut (`downloads`). The TV opens nothing for it. */
    data object Downloads : DeepLink

    /** Home, from the notification for the shows past the first five (`home`). */
    data object Home : DeepLink

    companion object {
        const val SCHEME = "watchmymedia"

        /** Null for anything that is not one of the app's links. */
        fun parse(link: String?): DeepLink? {
            val (uri, all) = read(link) ?: return null
            val query = all.filterValues { it.isNotEmpty() }
            val segments = listOfNotNull(uri.host) + uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
            val id = segments.getOrNull(1)?.toLongOrNull()
            return when (segments.firstOrNull()?.lowercase()) {
                "live" -> segments.getOrNull(1)?.takeIf { it.length <= MAX_CHANNEL_ID }?.let(::Live) ?: LiveGuide
                "play" -> id?.let { Play(it, query["t"]?.toLongOrNull(), query["title"], query["subtitle"]) }
                "shows" -> id?.let { Show(it, query["season"]?.toIntOrNull(), query["episode"]?.toLongOrNull()) }
                "movies" -> id?.let { Movie(it) }
                "link" -> LinkTv(query["code"])
                "search" -> Search
                "downloads" -> Downloads
                "home" -> Home
                else -> null
            }
        }

        /**
         * [link] as the app opens it with [account] signed in
         * ([watchNextAccount]; null when no one is). A launcher's link for
         * another account (or for none) opens nothing. A play link plays
         * only when a launcher (the TV's Watch Next row) published it for
         * [account]; any other play link, from a web page, a QR code or
         * another app, opens the item's page instead ([Details]) and its
         * `t`, `title` and `subtitle` are ignored, so a link can't start
         * playback, or put words in the player, by itself.
         */
        fun open(link: String?, account: String?): DeepLink? {
            val launcher = launcherAccount(link)
            if (launcher != null && (account == null || launcher != account)) return null
            return when (val l = parse(link)) {
                is Play -> if (launcher != null) l else Details(l.fileId)
                // Tuning takes a tuner: only a reminder's own link may.
                is Live -> if (launcher != null) l else LiveGuide
                else -> l
            }
        }

        /** A reminder's Watch: [channelId] for [account] ([watchNextAccount]). */
        fun live(channelId: String, account: String): String =
            "$SCHEME://live/${java.net.URLEncoder.encode(channelId, "UTF-8")}?source=reminder&account=${java.net.URLEncoder.encode(account, "UTF-8")}"

        /**
         * A new-episode notification's link: the
         * show's page with [episodeId] of [season] on top, for [account]
         * ([watchNextAccount]) alone.
         */
        fun newEpisode(showId: Long, season: Int, episodeId: Long, account: String): String =
            "$SCHEME://shows/$showId?season=$season&episode=$episodeId&source=new-episodes&account=${java.net.URLEncoder.encode(account, "UTF-8")}"

        /** The notification for the shows past the first five opens Home, for [account] alone. */
        fun home(account: String): String = "$SCHEME://home?source=new-episodes&account=${java.net.URLEncoder.encode(account, "UTF-8")}"

        private const val MAX_CHANNEL_ID = 128

        /** The sources whose links open only for the account they name. */
        private val LAUNCHER_SOURCES = setOf("watch-next", "reminder", "new-episodes")

        /**
         * The account a launcher's link was published for (Watch Next marks
         * its links `source=watch-next` and `account=`, a reminder
         * `source=reminder` and `account=`, a new-episode notification
         * `source=new-episodes` and `account=`), "" when it names
         * none; null for any other link. The TV opens such a link only for
         * the account signed in now.
         */
        fun launcherAccount(link: String?): String? {
            val (_, query) = read(link) ?: return null
            if (query["source"] !in LAUNCHER_SOURCES && "account" !in query) return null
            return query["account"].orEmpty()
        }

        /**
         * The registered server a launcher's [link] was published for: the
         * one among [saved] whose signed-in account it names
         * ([linkAccount]). Null for a link that names no account, or one for
         * an account that isn't signed in on this device. The app switches
         * to it before opening the link when it isn't the one in use.
         */
        fun serverFor(link: String?, saved: List<SavedServer>): SavedServer? {
            val account = launcherAccount(link)?.takeIf { it.isNotEmpty() } ?: return null
            return saved.firstOrNull { it.linkAccount() == account }
        }

        /** The link and its query, keys and values decoded; null when it is not a watchmymedia:// link. */
        private fun read(link: String?): Pair<URI, Map<String, String>>? {
            val uri = link?.trim()?.let { runCatching { URI(it) }.getOrNull() } ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.mapNotNull { part ->
                val key = decode(part.substringBefore('='))
                val value = decode(part.substringAfter('=', ""))
                if (key == null || value == null) null else key to value
            }.toMap()
            return uri to query
        }

        private fun decode(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()
    }
}
