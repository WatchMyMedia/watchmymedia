package media.watchmy.client.core.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import media.watchmy.client.model.SignedOutException
import media.watchmy.client.model.StreamQuality
import media.watchmy.client.model.User
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The settings an installed app has written must still read after an
 * update: when they don't, LocalStore forgets every server and its sign-in.
 * settings-0.4.6.json is what 0.4.6 writes, with a token
 * kept plain (`p1:`, a TV whose Keystore failed) and one sealed in the
 * Keystore (`k1:`), a signed-out server and a show listing missing episodes.
 */
class StoredSettingsFormatTest {
    private val h = Harness()

    @After
    fun tearDown() = h.close()

    @Test
    fun `the settings 0_4_6 wrote read back as the same servers and accounts`() {
        val stored = LocalStore.decode(prefs(release()))

        assertEquals("5f0c1d2e3a4b49c8a7d6e5f4c3b2a190", stored.deviceId)
        assertEquals("Den TV", stored.deviceName)
        assertEquals("1080", stored.quality)
        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90", stored.currentId)
        assertEquals(listOf("Golden Fixture", "Cabin", "Old NAS"), stored.servers.map { it.name })

        val home = stored.current!!
        assertEquals(StoredServer(
            id = "a1b2c3d4e5f60718293a4b5c6d7e8f90", name = "Golden Fixture", baseUrl = "http://192.168.1.20:27587", version = "0.4.6",
            apiLevel = 7, lastUsedAtMs = 1_793_404_800_000, lastUserName = "viewer",
            account = StoredAccount(2, "viewer", false, "86e0d90b273fc14a", "p1:tok-viewer"), showMissing = listOf(11, 42),
        ), home)

        val cabin = stored.servers[1]
        assertTrue(cabin.turnstile)
        assertEquals(StoredAccount(1, "admin", true, "0c2a9e7f1b3d5a68", "k1:q83vEjRWeJA0V2j7fPZ3wFJ3dW1hYmxlLXRva2VuLWJ5dGVz"), cabin.account)

        val nas = stored.servers[2]
        assertNull(nas.account)
        assertEquals("guest", nas.lastUserName)
        assertTrue(nas.setupRequired)
        assertEquals(emptyList<Long>(), nas.showMissing)
    }

    @Test
    fun `this version writes them back as 0_4_6 did`() {
        val release = release()
        val written = mutablePreferencesOf()
        LocalStore.encode(LocalStore.decode(prefs(release)), written)
        for ((key, value) in release) {
            assertEquals("$key changed format", value, written[stringPreferencesKey(key)])
        }
    }

    @Test
    fun `keys this version doesn't know don't lose the servers`() {
        val release = release()
        val newer = Json.parseToJsonElement(release.getValue("servers")).toString()
            .replace("\"showMissing\":[11,42]}", "\"showMissing\":[11,42],\"pinned\":true,\"lastSync\":{\"atMs\":1}}")
            .replace("\"sessionId\":\"86e0d90b273fc14a\",", "\"sessionId\":\"86e0d90b273fc14a\",\"scopes\":[\"play\"],")
        val stored = LocalStore.decode(prefs(release + ("servers" to newer) + ("theme" to "dark")))
        assertEquals(LocalStore.decode(prefs(release)), stored)
    }

    @Test
    fun `a list that doesn't read starts the servers afresh but keeps the device`() {
        // What LocalStore does today with servers it can't read: they are gone, the device ID is not.
        val stored = LocalStore.decode(prefs(release() + ("servers" to """[{"id":"x","name":"No base URL"}]""")))
        assertEquals(emptyList<StoredServer>(), stored.servers)
        assertEquals("5f0c1d2e3a4b49c8a7d6e5f4c3b2a190", stored.deviceId)
    }

    @Test
    fun `an updated app opens on the same server and account`() = runBlocking {
        val services = installed(release())

        assertEquals("Golden Fixture", services.servers.current.value!!.server.name)
        assertEquals(User(2, "viewer", false), services.auth.user.value)
        assertEquals("Den TV", services.auth.deviceName.value)
        assertEquals(StreamQuality.FullHd, services.preferences.quality.value)
        assertTrue(services.requests.showMissing(11))
        assertFalse(services.requests.showMissing(12))
        // Most recently used first, the signed-out one says who was last there.
        val saved = services.servers.saved.first()
        assertEquals(listOf("Golden Fixture", "Cabin", "Old NAS"), saved.map { it.server.name })
        assertEquals(listOf(true, true, false), saved.map { it.signedIn })
        assertEquals("guest", saved[2].userName)
    }

    @Test
    fun `a token whose Keystore key is gone signs that server out, not the others`() = runBlocking {
        // The harness opens tokens with PlainSealer, which can't open a k1: token: as when the key is lost.
        val services = installed(release())
        services.servers.select(services.servers.saved.first().first { it.server.name == "Cabin" }.server)
        assertEquals("Cabin", services.servers.current.value!!.server.name)
        try {
            services.library.libraries()
            fail("A token that can't be opened is no sign-in")
        } catch (_: SignedOutException) {
        }
        // Cleared as a rejected token is, so the app shows sign-in rather than failing every call.
        assertNull(services.auth.user.value)
        val saved = services.servers.saved.first().associateBy { it.server.name }
        assertFalse(saved.getValue("Cabin").signedIn)
        assertEquals("Who was there stays for the server list", "admin", saved.getValue("Cabin").userName)
        assertTrue("The other server's sign-in is untouched", saved.getValue("Golden Fixture").signedIn)
    }

    /** DataServices opened on a DataStore holding [values], as after an update. */
    private suspend fun installed(values: Map<String, String>): DataServices {
        h.editStorage { prefs -> values.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value } }
        return h.reopen()
    }

    private fun release(): Map<String, String> {
        val text = javaClass.getResource("/stored/settings-0.4.6.json")!!.readText()
        return (Json.parseToJsonElement(text) as JsonObject).mapValues { (_, v) -> v.jsonPrimitive.content }
    }

    private fun prefs(values: Map<String, String>): MutablePreferences =
        mutablePreferencesOf(*values.map { (k, v) -> stringPreferencesKey(k) to v }.toTypedArray())
}
