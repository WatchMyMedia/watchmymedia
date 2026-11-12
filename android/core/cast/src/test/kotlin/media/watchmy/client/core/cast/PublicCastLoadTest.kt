package media.watchmy.client.core.cast

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PublicCastLoadTest {
    @Test fun `status preserves playback controls but drops legacy load credentials`() {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply { putString(MediaMetadata.KEY_TITLE, "Fiction") }
        val original = MediaLoadRequestData.Builder()
            .setMediaInfo(MediaInfo.Builder("wmm:file:4").setContentType("video/mp4")
                .setMetadata(metadata).setContentUrl("https://example.test/video?access_token=c1.secret")
                .setCustomData(JSONObject().put("token", "c1.secret")).build())
            .setCustomData(JSONObject().put("token", "c1.secret"))
            .setCredentials("c1.secret").setAtvCredentials("c1.secret").setCurrentTime(42000).build()
        val public = publicCastLoad(original, 4)
        assertNull(public.credentials)
        assertEquals(false, public.toJson().toString().contains("c1.secret"))
        assertNull(public.customData)
        assertNull(public.queueData)
        assertNull(public.mediaInfo!!.customData)
        assertNull(public.mediaInfo!!.contentUrl)
        assertEquals("Fiction", public.mediaInfo!!.metadata!!.getString(MediaMetadata.KEY_TITLE))
        assertEquals(42000L, public.currentTime)
        assertEquals("c1.secret", original.mediaInfo!!.customData!!.getString("token"))
    }
}
