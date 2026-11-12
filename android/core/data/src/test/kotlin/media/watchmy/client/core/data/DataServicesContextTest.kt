package media.watchmy.client.core.data

import android.content.Context
import kotlinx.coroutines.runBlocking
import media.watchmy.client.model.Server
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The public constructor and helpers on a real (Robolectric) Context: DataStore, NSD and the Keystore fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
// This test exercises the missing Android Keystore fallback, not native TLS.
@ConscryptMode(ConscryptMode.Mode.OFF)
class DataServicesContextTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `clientInfo makes a device ID once and keeps it, and DataServices shares it`() = runBlocking {
        val tv = DataServices.clientInfo(context, tv = true, version = "0.1.0")
        assertEquals(32, tv.deviceId.length)
        assertEquals("androidtv", tv.platform)
        assertEquals("WatchMyMedia for Android TV", tv.product)
        assertTrue(tv.name.isNotBlank())
        assertEquals(tv.deviceId, DataServices.clientInfo(context, tv = true, version = "0.1.0").deviceId)
        val phone = DataServices.clientInfo(context, tv = false, version = "0.1.0")
        assertEquals("android", phone.platform)
        assertEquals(tv.deviceId, phone.deviceId)

        val data = DataServices(context, tv)
        assertEquals(tv.deviceId, data.deviceId)
        assertEquals(tv, data.client)
        assertNull(data.servers.current.value)
        assertNull(data.auth.user.value)
        assertNotNull(data.imageHttpClient)

        data.auth.setDeviceName("Den TV")
        assertEquals("Den TV", data.client.name)
        assertEquals("Den TV", DataServices.clientInfo(context, tv = true, version = "0.1.0").name)

        data.servers.select(Server("s", "S", "http://10.0.2.2:27587", "1", 1))
        assertEquals("s", data.servers.current.value!!.server.id)
    }

    @Test
    fun `create reads storage once and keeps what it made, as clientInfo and the constructor do`() = runBlocking {
        val first = DataServices.create(context, tv = false, version = "0.1.0")
        assertEquals(32, first.deviceId.length)
        assertEquals("android", first.client.platform)
        first.auth.setDeviceName("Attic phone")

        // A restart: the same device, with what was saved, from the one read.
        val again = DataServices.create(context, tv = false, version = "0.1.0")
        assertEquals(first.deviceId, again.deviceId)
        assertEquals("Attic phone", again.client.name)
        assertEquals(DataServices.clientInfo(context, tv = false, version = "0.1.0"), again.client)
    }

    @Test
    fun `the Keystore sealer falls back to plain where there is no Keystore`() {
        val sealer = KeystoreSealer()
        val sealed = sealer.seal("tok")
        assertEquals("tok", sealer.open(sealed))
        assertNull(sealer.open("k1:not-base64!"))
    }
}
