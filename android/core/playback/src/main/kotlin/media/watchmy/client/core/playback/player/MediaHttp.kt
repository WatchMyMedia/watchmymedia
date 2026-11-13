package media.watchmy.client.core.playback.player

import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * The players' HTTP client, made from the app's [shared] one (without the
 * API's sign-in, which media requests don't use: their token is in the URL).
 * It keeps the connection pool, so the first playlist request reuses the
 * connection the decision came over instead of opening its own. It has a
 * dispatcher of its own, so a stream's segments and the API's calls don't
 * queue behind each other, and no response cache. Reads wait 45 s: a VOD
 * segment can wait several seconds while the server starts ffmpeg at a new
 * place. A redirect between http and https is not followed: an https stream
 * sent to http would carry the media token in the clear.
 */
fun mediaHttpClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
    .dispatcher(Dispatcher())
    .cache(null)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build()
