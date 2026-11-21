package media.watchmy.client.core.cast

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import media.watchmy.client.model.ApiException
import media.watchmy.client.model.CastGateway
import media.watchmy.client.model.CastServer
import media.watchmy.client.model.CastState
import media.watchmy.client.model.CastToken
import media.watchmy.client.model.SavedServer
import media.watchmy.client.model.Server
import media.watchmy.client.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.google.android.gms.cast.framework.CastState as Sdk

/** What the phone's Cast button shows ([castState]) and whether the server casts ([serverCasts]). */
class CastStateTest {
    @Test
    fun `a cast ends when the sign-in in use changes, and not when one begins`() {
        val living = SavedServer(Server("srv-living", "Living room server", "http://192.168.1.20:27587", "1.0.0", 30), "Sam", true, 0, 1)
        val cabin = SavedServer(Server("srv-cabin", "Cabin server", "http://192.168.4.31:27587", "1.0.0", 30), "Sam", true, 0, 1)
        val sam = User(1, "Sam", true)
        val onLiving = castIdentity(living, sam)
        assertEquals("the same person's id on another server is another sign-in", false, onLiving == castIdentity(cabin, sam))
        assertTrue("a switch to another server", endsCast(onLiving, castIdentity(cabin, sam)))
        assertTrue("another person on the same server", endsCast(onLiving, castIdentity(living, User(2, "Alex", false))))
        assertTrue("signing out", endsCast(onLiving, castIdentity(living, null)))
        assertFalse("nothing changed", endsCast(onLiving, castIdentity(living, sam)))
        assertFalse("a rename is the same sign-in", endsCast(onLiving, castIdentity(living, sam.copy(name = "Samira"))))
        assertFalse("signing in from signed out", endsCast(null, onLiving))
        assertFalse(endsCast(castIdentity(null, null), castIdentity(living, null)))
    }

    @Test
    fun `examples`() {
        assertEquals(CastState.Unavailable, state(sdkReady = false))
        assertEquals(CastState.Unavailable, state(serverCasts = false))
        assertEquals(CastState.NoDevices, state(sdkState = Sdk.NO_DEVICES_AVAILABLE))
        assertEquals(CastState.Available, state(sdkState = Sdk.NOT_CONNECTED))
        assertEquals(CastState.Connecting(null), state(sdkState = Sdk.CONNECTING))
        assertEquals("The session names the device before the SDK says connecting", CastState.Connecting("Den TV"), state(connecting = "Den TV"))
        assertEquals(CastState.Connected("Den TV"), state(attached = true, deviceName = "Den TV"))
        assertEquals("A nameless device", CastState.Connected("TV"), state(attached = true, deviceName = " "))
        assertEquals(CastState.Connected("TV"), state(attached = true, deviceName = null))
        assertEquals("The SDK connected, but the remote isn't attached yet", CastState.Available, state(sdkState = Sdk.CONNECTED))
    }

    @Test
    fun `every combination follows the rules in order`() {
        for (attached in BOOLS) for (name in listOf(null, "", "Den TV")) for (ready in BOOLS) for (casts in BOOLS)
            for (sdk in listOf(Sdk.NO_DEVICES_AVAILABLE, Sdk.NOT_CONNECTED, Sdk.CONNECTING, Sdk.CONNECTED)) for (connecting in listOf(null, "Den TV")) {
                val s = castState(attached, name, ready, casts, sdk, connecting)
                val case = "attached=$attached name=$name ready=$ready casts=$casts sdk=$sdk connecting=$connecting"
                when {
                    attached -> assertEquals(case, CastState.Connected(name?.takeIf { it.isNotBlank() } ?: "TV"), s)
                    !ready || !casts -> assertEquals(case, CastState.Unavailable, s)
                    sdk == Sdk.CONNECTING || connecting != null -> assertEquals(case, CastState.Connecting(connecting), s)
                    sdk == Sdk.NO_DEVICES_AVAILABLE -> assertEquals(case, CastState.NoDevices, s)
                    else -> assertEquals(case, CastState.Available, s)
                }
            }
    }

    @Test
    fun `only a server that says casting casts`() = runTest {
        assertTrue(gateway { CastServer("s", "http://s", casting = true) }.serverCasts())
        assertFalse(gateway { CastServer("s", "http://s", casting = false) }.serverCasts())
        assertFalse("No server in use", gateway { null }.serverCasts())
        assertFalse("Unreachable", gateway { throw ApiException(null, "Couldn't reach the server.", IOException()) }.serverCasts())
        assertFalse("Anything else", gateway { error("bad reply") }.serverCasts())
        try {
            gateway { throw CancellationException("left") }.serverCasts()
            fail("Cancellation goes through")
        } catch (_: CancellationException) {
        }
    }

    private fun state(
        attached: Boolean = false,
        deviceName: String? = null,
        sdkReady: Boolean = true,
        serverCasts: Boolean = true,
        sdkState: Int = Sdk.NOT_CONNECTED,
        connecting: String? = null,
    ) = castState(attached, deviceName, sdkReady, serverCasts, sdkState, connecting)

    private fun gateway(server: suspend () -> CastServer?) = object : CastGateway {
        override suspend fun castServer() = server()

        override suspend fun castToken(): CastToken = error("not used")
    }

    private companion object {
        val BOOLS = listOf(false, true)
    }
}
