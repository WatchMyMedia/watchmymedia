package media.watchmy.client.core.playback.watchnext

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.watchmy.client.model.WatchTarget

/**
 * [WatchNext] through the TV provider (`androidx.tvprovider`). Entries are
 * found again by their internal provider ID, the file ID, and open
 * `watchmymedia://play/{fileId}` with the title and subtitle as query
 * parameters, so the player is named before the server answers. Their
 * internal flags keep the movie or episode (1), the show (2) and the
 * season (3), so a mark can find them. One for the app.
 *
 * [poster] gives the card's image. The launcher fetches it without the
 * app's sign-in, so the default is the app's TV banner rather than the
 * server's artwork.
 */
// WatchNextProgram's public builder and getters are inherited from
// BasePreviewProgram, which tvprovider marks restricted; lint flags every
// call through it (a known false positive), so the class opts out.
@SuppressLint("RestrictedApi")
class TvWatchNext(
    context: Context,
    initialAccount: String? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
    private val poster: (WatchNextEntry) -> Uri? = defaultPoster(context.applicationContext),
) : WatchNext {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val lock = Mutex()
    private val accounts = WatchNextScope(initialAccount)

    init { selectAccount(initialAccount) }

    /** Drop previous viewers' rows and unscoped rows from older app versions. */
    fun selectAccount(account: String?) {
        accounts.select(account)
        scope.launch {
            lock.withLock {
                attempt("account change") {
                    val active = accounts.capture().account
                    val obsolete = mutableListOf<Long>()
                    app.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val p = WatchNextProgram.fromCursor(c)
                            if (active == null || p.internalProviderId?.startsWith("$active:") != true) {
                                obsolete += p.id
                            }
                        }
                    }
                    obsolete.forEach { app.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
                }
            }
        }
    }

    override fun bind(): WatchNext {
        val ticket = accounts.capture()
        return object : WatchNext {
            override fun update(entry: WatchNextEntry) {
                scope.launch { lock.withLock {
                    if (accounts.permits(ticket)) attempt("update") { upsert(entry.copy(account = ticket.account)) }
                } }
            }
            override fun remove(fileId: Long) {
                scope.launch { lock.withLock {
                    if (accounts.permits(ticket)) attempt("remove") {
                        find("${ticket.account}:$fileId")?.let { delete(it) }
                    }
                } }
            }
            override fun sync(continueWatching: List<WatchNextEntry>) {
                scope.launch { lock.withLock {
                    if (accounts.permits(ticket)) attempt("sync") { keepInStep(ticket.account!!, continueWatching.map { it.copy(account = ticket.account, next = false) }) }
                } }
            }
            override fun removeMarked(target: WatchTarget) {
                scope.launch { lock.withLock {
                    if (accounts.permits(ticket)) attempt("remove marked") { mine(ticket.account!!).filter { it.isFor(target) }.forEach(::delete) }
                } }
            }
        }
    }

    override fun update(entry: WatchNextEntry) {
        bind().update(entry)
    }

    override fun remove(fileId: Long) {
        bind().remove(fileId)
    }

    override fun sync(continueWatching: List<WatchNextEntry>) {
        bind().sync(continueWatching)
    }

    override fun removeMarked(target: WatchTarget) {
        bind().removeMarked(target)
    }

    /**
     * Makes [account]'s partway entries those of [entries]: adds or moves
     * each, and takes off any the server no longer lists (finished or
     * marked elsewhere). Next episodes stay unless the server now has them
     * partway. An entry already where it should be isn't written again.
     */
    private fun keepInStep(account: String, entries: List<WatchNextEntry>) {
        val listed = entries.associateBy { "$account:${it.fileId}" }
        val rows = mine(account)
        rows.filter { it.watchNextType != TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT && it.internalProviderId !in listed }.forEach(::delete)
        val byId = rows.associateBy { it.internalProviderId }
        for ((id, e) in listed) {
            val row = byId[id]
            // The player's entries have no IDs; the first sync adds them, so a mark can find them.
            val current = row != null && row.watchNextType == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE &&
                row.lastPlaybackPositionMillis.toLong() == e.positionMs.coerceIn(0, Int.MAX_VALUE.toLong()) &&
                (e.itemId == null || row.internalProviderFlag1 == e.itemId)
            if (!current) upsert(e)
        }
    }

    private fun upsert(e: WatchNextEntry) {
        val providerId = "${e.account}:${e.fileId}"
        val existing = find(providerId)
        // Removed by the viewer from the row: leave it removed.
        if (existing != null && !existing.isBrowsable) return
        // Started already: it stays a play to finish rather than the next one to start.
        if (e.next && existing != null && existing.watchNextType == TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE) return
        val builder = WatchNextProgram.Builder()
            .setWatchNextType(if (e.next) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(e.engagedAtMs)
            .setType(if (e.episode) TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
            .setTitle(e.title)
            .setDurationMillis(e.durationMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .setLastPlaybackPositionMillis(e.positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .setIntentUri(playLink(e))
            .setInternalProviderId(providerId)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        if (e.subtitle != null) builder.setEpisodeTitle(e.subtitle).setDescription(e.subtitle)
        e.itemId?.let { builder.setInternalProviderFlag1(it) }
        e.showId?.let { builder.setInternalProviderFlag2(it) }
        e.season?.let { builder.setInternalProviderFlag3(it.toLong()) }
        poster(e)?.let { builder.setPosterArtUri(it) }
        val helper = PreviewChannelHelper(app)
        if (existing == null) helper.publishWatchNextProgram(builder.build()) else helper.updateWatchNextProgram(builder.build(), existing.id)
    }

    // Apps see only their own Watch Next rows.
    private fun find(id: String): WatchNextProgram? = all().firstOrNull { it.internalProviderId == id }

    /** [account]'s entries on the row. */
    private fun mine(account: String): List<WatchNextProgram> = all().filter { it.internalProviderId?.startsWith("$account:") == true }

    private fun all(): List<WatchNextProgram> = buildList {
        app.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) add(WatchNextProgram.fromCursor(c))
        }
    }

    private fun delete(p: WatchNextProgram) {
        app.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(p.id), null, null)
    }

    /** Whether this entry is [target]'s: by the IDs kept in its flags (entries from before they were kept have none). */
    private fun WatchNextProgram.isFor(target: WatchTarget): Boolean {
        val movie = type == TvContractCompat.PreviewPrograms.TYPE_MOVIE
        return when (target) {
            is WatchTarget.Movie -> movie && internalProviderFlag1 == target.id
            is WatchTarget.Episode -> !movie && internalProviderFlag1 == target.id
            is WatchTarget.Season -> !movie && internalProviderFlag2 == target.showId && internalProviderFlag3 == target.number.toLong()
            is WatchTarget.Show -> !movie && internalProviderFlag2 == target.id
        }
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        // Devices without the TV provider (and its occasional failures) must not take the player down.
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Watch Next $what failed", e)
        }
    }

    companion object {
        private const val TAG = "WmmWatchNext"

        /** `watchmymedia://play/{fileId}?title=…&subtitle=…`, as the TV's deep links read it. */
        fun playLink(e: WatchNextEntry): Uri = Uri.Builder()
            .scheme("watchmymedia")
            .authority("play")
            .appendPath(e.fileId.toString())
            .apply {
                appendQueryParameter("source", "watch-next")
                e.account?.let { appendQueryParameter("account", it) }
                e.title.takeIf { it.isNotBlank() }?.let { appendQueryParameter("title", it) }
                e.subtitle?.takeIf { it.isNotBlank() }?.let { appendQueryParameter("subtitle", it) }
            }
            .build()

        private fun defaultPoster(app: Context): (WatchNextEntry) -> Uri? {
            val res = app.applicationInfo.banner.takeIf { it != 0 } ?: app.applicationInfo.icon
            val uri = if (res == 0) null else "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${app.packageName}/$res".toUri()
            return { uri }
        }
    }
}
