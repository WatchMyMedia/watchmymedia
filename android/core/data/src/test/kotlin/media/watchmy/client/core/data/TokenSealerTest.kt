package media.watchmy.client.core.data

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * Which Keystore failures sign an account out:
 * only a token that can never be opened. The sealer runs here on a key of the
 * JVM's own, standing in for the Android Keystore's: Robolectric's Conscrypt
 * is off, since the JDK's AES-GCM refuses to load beside it.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TokenSealerTest {
    private class Keys : SealingKeys {
        var key: SecretKey? = null
        var failure: Exception? = null

        override fun existing(): SecretKey? {
            failure?.let { throw it }
            return key
        }

        override fun create(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it }
    }

    private val keys = Keys()
    private val sealer = KeystoreSealer(keys)

    @Test
    fun `a sealed token opens with its key`() {
        val sealed = sealer.seal("tok")
        assertTrue(sealed.startsWith("k1:"))
        assertEquals("tok", sealer.open(sealed))
    }

    @Test
    fun `a missing key can never open it`() {
        val sealed = sealer.seal("tok")
        keys.key = null
        assertNull(sealer.open(sealed))
    }

    @Test
    fun `another key fails the tag, which is for good`() {
        val sealed = sealer.seal("tok")
        keys.create()
        assertNull(sealer.open(sealed))
    }

    @Test
    fun `an invalidated key is for good`() {
        val sealed = sealer.seal("tok")
        keys.failure = KeyPermanentlyInvalidatedException()
        assertNull(sealer.open(sealed))
    }

    @Test
    fun `text that isn't a sealed token can never be opened`() {
        assertNull(sealer.open("k1:not-base64!"))
        assertNull(sealer.open("k1:AAAA"))
    }

    @Test
    fun `any other Keystore error fails this time and keeps the token`() {
        val sealed = sealer.seal("tok")
        for (failure in listOf(KeyStoreException("busy"), ProviderException("keystore2 restarted"), UnrecoverableKeyException("Failed to obtain information about key"))) {
            keys.failure = failure
            try {
                sealer.open(sealed)
                fail("expected TokenUnavailableException for $failure")
            } catch (e: TokenUnavailableException) {
                assertEquals(failure, e.cause)
            }
        }
        keys.failure = null
        assertEquals("tok", sealer.open(sealed))
    }

    @Test
    fun `the classes that are for good, found where they are wrapped too`() {
        assertTrue(permanentKeystoreFailure(KeyPermanentlyInvalidatedException()))
        assertTrue(permanentKeystoreFailure(AEADBadTagException()))
        assertTrue(permanentKeystoreFailure(ProviderException("wrapped", AEADBadTagException())))
        assertFalse(permanentKeystoreFailure(KeyStoreException("busy")))
        assertFalse(permanentKeystoreFailure(ProviderException("keystore2 restarted")))
        assertFalse(permanentKeystoreFailure(UnrecoverableKeyException("Failed to obtain information about key")))
    }
}
