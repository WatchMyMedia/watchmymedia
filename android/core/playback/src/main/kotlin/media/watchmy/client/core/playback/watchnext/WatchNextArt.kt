package media.watchmy.client.core.playback.watchnext

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * What fetches a Watch Next card's picture for [WatchNextArtProvider]: the
 * app's [android.app.Application], which signs the request in to its
 * server. [account] is the account the card was published for; a source
 * fetches only while that account is the one signed in, and returns null
 * otherwise.
 */
interface WatchNextArtSource {
    suspend fun watchNextArt(account: String, path: String): ByteArray?
}

/**
 * The launcher's pictures for the Watch Next row.
 * The launcher can't sign in to the server, so a card's
 * picture is a content:// address this app answers ([WatchNextArtProvider])
 * rather than the server's: the server's address would need a signed token
 * in it. Each address names the account and the server's image path, and
 * carries a signature with a key that never leaves the app, so another app
 * on the TV can open only the addresses this one published.
 */
object WatchNextArt {
    private const val KEY_FILE = "watchnext-art.key"
    private const val SIGNATURE = "s"
    private const val PATH = "path"

    @Volatile private var key: ByteArray? = null

    fun authority(context: Context): String = "${context.packageName}.watchnext.art"

    /** The picture at the server's image [path], for [account]'s card. */
    fun uri(context: Context, account: String, path: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(authority(context))
        .appendPath(account)
        .appendQueryParameter(PATH, path)
        .appendQueryParameter(SIGNATURE, sign(context, account, path))
        .build()

    /** The account and image path [uri] names, if this app signed it; null for any other. */
    fun open(context: Context, uri: Uri): Pair<String, String>? {
        val account = uri.pathSegments.singleOrNull() ?: return null
        val path = uri.getQueryParameter(PATH) ?: return null
        val signature = uri.getQueryParameter(SIGNATURE) ?: return null
        val expected = sign(context, account, path)
        return (account to path).takeIf { MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray()) }
    }

    private fun sign(context: Context, account: String, path: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key(context), "HmacSHA256"))
        val bytes = mac.doFinal("$account\n$path".toByteArray())
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** The signing key: random, made on first use, kept out of backups. */
    @Synchronized
    private fun key(context: Context): ByteArray {
        key?.let { return it }
        val file = File(context.applicationContext.noBackupFilesDir, KEY_FILE)
        val stored = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size == 32 }
        val made = stored ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.also { bytes ->
            runCatching { file.writeBytes(bytes) }.onFailure { Log.w("WmmWatchNext", "Watch Next art key not saved", it) }
        }
        key = made
        return made
    }
}

/**
 * Answers [WatchNextArt]'s addresses for the launcher: read-only, a
 * picture at a time, fetched through the app ([WatchNextArtSource]) and
 * only for the account signed in now. An address this app didn't sign, or
 * one for an account that has signed out, finds nothing.
 */
class WatchNextArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Watch Next pictures are read-only")
        val context = context ?: throw FileNotFoundException(uri.toString())
        val (account, path) = WatchNextArt.open(context, uri) ?: throw FileNotFoundException("not this app's picture")
        val source = context.applicationContext as? WatchNextArtSource ?: throw FileNotFoundException("no picture source")
        // The launcher calls in on a binder thread and waits for the picture.
        val bytes = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(FETCH_TIMEOUT_MS) { runCatching { source.watchNextArt(account, path) }.getOrNull() }
        } ?: throw FileNotFoundException("picture unavailable")
        val (read, write) = ParcelFileDescriptor.createPipe()
        thread(name = "WatchNextArt") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(bytes) }
            } catch (_: IOException) {
                // The launcher stopped reading.
            }
        }
        return read
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    private companion object {
        const val FETCH_TIMEOUT_MS = 10_000L
    }
}
