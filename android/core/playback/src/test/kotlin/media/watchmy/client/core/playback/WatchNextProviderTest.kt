package media.watchmy.client.core.playback.watchnext

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchNextProviderTest {
    @Test fun `startup removes legacy rows and account changes cannot revive queued old rows`() = runTest {
        val provider = Rows()
        ShadowContentResolver.registerProviderInternal(TvContractCompat.AUTHORITY, provider)
        provider.seed(1, "4")
        provider.seed(2, "viewer-a:5")
        provider.seed(3, "viewer-b:4")
        val publisher = TvWatchNext(RuntimeEnvironment.getApplication(), "viewer-a", StandardTestDispatcher(testScheduler), poster = { null })
        runCurrent()
        assertEquals(setOf(2L), provider.rows.keys)
        val old = publisher.bind()
        val entry = WatchNextEntry(4, "Fiction", null, false, 30000, 90000, 1)
        old.update(entry)
        publisher.selectAccount(null)
        runCurrent()
        assertTrue(provider.rows.isEmpty())
        publisher.selectAccount("viewer-b")
        publisher.bind().update(entry)
        old.update(entry.copy(title = "Previous viewer's title"))
        runCurrent()
        assertEquals(1, provider.rows.size)
        val row = provider.rows.values.single()
        assertEquals("viewer-b:4", row.getAsString("internal_provider_id"))
        assertEquals("Fiction", row.getAsString("title"))
        assertTrue(row.getAsString("intent_uri").contains("account=viewer-b"))
    }

    @Test fun `the row follows the server's Continue watching, keeps next episodes, and a mark takes its entries off`() = runTest {
        val provider = Rows()
        ShadowContentResolver.registerProviderInternal(TvContractCompat.AUTHORITY, provider)
        val publisher = TvWatchNext(RuntimeEnvironment.getApplication(), "viewer-a", StandardTestDispatcher(testScheduler), poster = { null })
        val row = publisher.bind()
        // Left partway here; finished elsewhere since.
        row.update(WatchNextEntry(4, "Fiction", null, false, 30_000, 90_000, 1))
        // The next episode after one finished here.
        row.update(WatchNextEntry(12, "Harbor Lights", "S03 E08 · Squall Line", true, 0, 52 * 60_000L, 2, next = true, itemId = 100038, showId = 100, season = 3))
        runCurrent()
        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT, typeOf(provider, 12))

        row.sync(listOf(WatchNextEntry(7, "Dead Reckoning Bay", null, false, 60_000, 103 * 60_000L, 5, itemId = 500)))
        runCurrent()
        assertEquals("finished elsewhere: off; the next episode stays", setOf("viewer-a:7", "viewer-a:12"), identities(provider))
        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, typeOf(provider, 7))

        // Started on another device: the next episode becomes one to finish.
        row.sync(listOf(WatchNextEntry(12, "Harbor Lights", "S03 E08 · Squall Line", true, 600_000, 52 * 60_000L, 6, itemId = 100038, showId = 100, season = 3)))
        runCurrent()
        assertEquals(setOf("viewer-a:12"), identities(provider))
        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, typeOf(provider, 12))
        // A next episode never takes the place of one started.
        row.update(WatchNextEntry(12, "Harbor Lights", "S03 E08 · Squall Line", true, 0, 52 * 60_000L, 7, next = true, itemId = 100038, showId = 100, season = 3))
        runCurrent()
        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, typeOf(provider, 12))

        row.removeMarked(media.watchmy.client.model.WatchTarget.Season(100, 2))
        runCurrent()
        assertEquals("another season", setOf("viewer-a:12"), identities(provider))
        row.removeMarked(media.watchmy.client.model.WatchTarget.Show(100))
        runCurrent()
        assertTrue(provider.rows.isEmpty())
    }

    private fun identities(provider: Rows) = provider.rows.values.map { it.getAsString("internal_provider_id") }.toSet()

    private fun typeOf(provider: Rows, fileId: Long) = provider.rows.values.single { it.getAsString("internal_provider_id") == "viewer-a:$fileId" }.getAsInteger("watch_next_type")

    class Rows : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        private var nextId = 10L
        fun seed(id: Long, identity: String) {
            rows[id] = ContentValues().apply { put("_id", id); put("internal_provider_id", identity); put("browsable", 1) }
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "vnd.android.cursor.dir/watch_next_program"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf("_id", "internal_provider_id")
            return MatrixCursor(columns).also { cursor -> rows.values.forEach { row -> cursor.addRow(columns.map { row[it] }.toTypedArray()) } }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = nextId++
            rows[id] = ContentValues(values).apply { put("_id", id); put("browsable", 1) }
            return TvContractCompat.buildWatchNextProgramUri(id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            rows[uri.lastPathSegment!!.toLong()]?.putAll(values)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = if (rows.remove(uri.lastPathSegment!!.toLong()) != null) 1 else 0
    }
}
