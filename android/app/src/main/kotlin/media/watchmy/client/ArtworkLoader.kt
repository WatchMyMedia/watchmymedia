package media.watchmy.client

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture

/**
 * The media notification's and lock screen's pictures, for the media
 * session: through the app's image loader, so through the client that adds
 * the sign-in to requests to the current server and nowhere else, and from
 * its caches, where the player's backdrop usually is already. Software
 * bitmaps, as the session hands them to the system UI.
 */
@OptIn(UnstableApi::class)
internal class ArtworkLoader(
    private val context: Context,
    private val images: () -> ImageLoader = { SingletonImageLoader.get(context) },
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = load(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = load(uri.toString())

    private fun load(data: Any): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val request = ImageRequest.Builder(context)
            .data(data)
            .allowHardware(false)
            .listener(
                onCancel = { future.cancel(false) },
                onError = { _, result -> future.setException(result.throwable) },
                onSuccess = { _, result -> future.set(result.image.toBitmap()) },
            )
            .build()
        val job = images().enqueue(request)
        future.addListener({ if (future.isCancelled) job.dispose() }, MoreExecutors.directExecutor())
        return future
    }
}
