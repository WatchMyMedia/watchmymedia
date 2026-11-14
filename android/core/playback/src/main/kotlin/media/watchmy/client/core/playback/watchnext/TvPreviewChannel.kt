package media.watchmy.client.core.playback.watchnext

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import media.watchmy.client.model.BrowseItem
import media.watchmy.client.model.HomeData
import media.watchmy.client.model.ItemKind
import media.watchmy.client.model.RowIds
import java.net.URLEncoder

/**
 * The app's one preview channel on the Android TV home screen
 * (PLAN-ANDROID-REVIEW.md §6.2): Recently added, the newest movies and
 * shows from Home, each with its poster (through [WatchNextArtProvider])
 * and opening its page. Like the Watch Next row, its programmes are the
 * signed-in account's: another account's, or none signed in, leave it
 * empty. The launcher shows the app's first channel by itself; people can
 * hide it there.
 */
// PreviewProgram's builder and getters are inherited from BasePreviewProgram,
// which tvprovider marks restricted; lint flags every call through it (a known
// false positive), so the class opts out, as TvWatchNext does.
@SuppressLint("RestrictedApi")
class TvPreviewChannel(
    context: Context,
    initialAccount: String? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val lock = Mutex()
    private val accounts = WatchNextScope(initialAccount)

    /** Drops what the channel holds for anyone but [account]. */
    fun selectAccount(account: String?) {
        accounts.select(account)
        scope.launch {
            lock.withLock {
                attempt("account change") {
                    val active = accounts.capture().account
                    val channel = find() ?: return@attempt
                    programmes(channel).filter { active == null || it.internalProviderId?.startsWith("$active:") != true }.forEach(::delete)
                }
            }
        }
    }

    /** A publisher bound to the account signed in now: a reply for someone who has since signed out changes nothing. */
    fun bind(): (HomeData) -> Unit {
        val ticket = accounts.capture()
        return { home ->
            val items = recentlyAdded(home)
            scope.launch {
                lock.withLock { if (accounts.permits(ticket)) attempt("sync") { keepInStep(ticket.account!!, items) } }
            }
        }
    }

    /** The channel's programmes become [items], newest first: added, kept, or taken off. */
    private fun keepInStep(account: String, items: List<BrowseItem>) {
        if (items.isEmpty() && find() == null) return
        val channel = find() ?: publish() ?: return
        val wanted = items.associateBy { "$account:${it.kind.name}:${it.id}" }
        val existing = programmes(channel)
        existing.filter { it.internalProviderId !in wanted }.forEach(::delete)
        val have = existing.mapNotNull { it.internalProviderId }.toSet()
        val helper = PreviewChannelHelper(app)
        wanted.entries.forEachIndexed { i, (id, item) ->
            if (id in have) return@forEachIndexed
            val builder = PreviewProgram.Builder()
                .setChannelId(channel)
                .setType(if (item.kind == ItemKind.Movie) TvContractCompat.PreviewPrograms.TYPE_MOVIE else TvContractCompat.PreviewPrograms.TYPE_TV_SERIES)
                .setTitle(item.title)
                .setInternalProviderId(id)
                .setWeight(items.size - i)
                .setIntentUri(pageLink(item, account))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
            item.year?.let { builder.setReleaseDate(it.toString()) }
            item.overview.takeIf { it.isNotBlank() }?.let { builder.setDescription(it) }
            item.posterPath?.let { builder.setPosterArtUri(WatchNextArt.uri(app, account, it)) }
            helper.publishPreviewProgram(builder.build())
        }
    }

    /** The channel, made the first time there is something for it; the launcher shows an app's first channel by itself. */
    private fun publish(): Long? {
        val channel = PreviewChannel.Builder()
            .setDisplayName(CHANNEL_NAME)
            .setInternalProviderId(CHANNEL_ID)
            .setAppLinkIntentUri("watchmymedia://home".toUri())
            .apply { logo()?.let { setLogo(it) } }
            .build()
        val id = PreviewChannelHelper(app).publishChannel(channel)
        TvContractCompat.requestChannelBrowsable(app, id)
        return id
    }

    private fun find(): Long? = PreviewChannelHelper(app).allChannels.firstOrNull { it.internalProviderId == CHANNEL_ID }?.id

    private fun programmes(channel: Long): List<PreviewProgram> = buildList {
        app.contentResolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channel), PreviewProgram.PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) add(PreviewProgram.fromCursor(c))
        }
    }

    private fun delete(p: PreviewProgram) {
        app.contentResolver.delete(TvContractCompat.buildPreviewProgramUri(p.id), null, null)
    }

    /** The app's icon as the channel's logo. */
    private fun logo(): Bitmap? {
        val drawable = ContextCompat.getDrawable(app, app.applicationInfo.icon) ?: return null
        val bitmap = createBitmap(LOGO_PX, LOGO_PX)
        drawable.setBounds(0, 0, LOGO_PX, LOGO_PX)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        // Devices without the TV provider (and its occasional failures) must not take the app down.
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Preview channel $what failed", e)
        }
    }

    companion object {
        private const val TAG = "WmmPreviewChannel"
        private const val CHANNEL_ID = "recently-added"
        private const val CHANNEL_NAME = "Recently added"
        private const val LOGO_PX = 160

        /** The most the channel holds. */
        const val MAX_PROGRAMMES = 12

        /**
         * Home's newest movies and shows, alternating, at most [MAX_PROGRAMMES]:
         * the fixed rows, or the arranged Home's rows of the same name.
         */
        fun recentlyAdded(home: HomeData): List<BrowseItem> {
            val rows = home.rows
            val movies = rows?.firstOrNull { it.id == RowIds.RECENTLY_ADDED_MOVIES }?.items ?: home.recentlyAddedMovies
            val shows = rows?.firstOrNull { it.id == RowIds.RECENTLY_ADDED_SHOWS }?.items ?: home.recentlyAddedShows
            val both = buildList {
                for (i in 0 until maxOf(movies.size, shows.size)) {
                    movies.getOrNull(i)?.let(::add)
                    shows.getOrNull(i)?.let(::add)
                }
            }
            return both.filter { it.kind != ItemKind.Unmatched }.distinctBy { it.kind to it.id }.take(MAX_PROGRAMMES)
        }

        /** The item's page, for [account] only: watchmymedia://movies/{id} or shows/{id}. */
        fun pageLink(item: BrowseItem, account: String): Uri {
            val kind = if (item.kind == ItemKind.Movie) "movies" else "shows"
            return "watchmymedia://$kind/${item.id}?source=channel&account=${URLEncoder.encode(account, "UTF-8")}".toUri()
        }
    }
}
