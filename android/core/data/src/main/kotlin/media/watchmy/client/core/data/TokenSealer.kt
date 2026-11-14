package media.watchmy.client.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps bearer tokens before they are written to disk. */
internal interface TokenSealer {
    fun seal(token: String): String

    /**
     * The token, or null when it can never be opened again (its key is gone
     * or was invalidated, or it doesn't decrypt): sign in again. Throws
     * [TokenUnavailableException] when the Keystore fails in a way that may
     * pass, which fails the one request and keeps the sign-in.
     */
    fun open(sealed: String): String?

    /**
     * [sealed] sealed better, now that it can be: a token kept unwrapped while
     * the Keystore failed, wrapped once it works again. Null leaves it as it is.
     */
    fun reseal(sealed: String): String? = null
}

/**
 * The Keystore couldn't open a saved token this time (busy, or a provider
 * error), though the token and its key may still be good: the request fails
 * and the sign-in stays. An [IOException], so OkHttp and the event stream
 * treat it as a failed request.
 */
internal class TokenUnavailableException(cause: Throwable) : IOException("the Keystore couldn't open a saved sign-in", cause)

/** Keeps tokens as they are: for tests, and the fallback when the Keystore fails. */
internal object PlainSealer : TokenSealer {
    const val PREFIX = "p1:"

    override fun seal(token: String) = PREFIX + token

    override fun open(sealed: String) = if (sealed.startsWith(PREFIX)) sealed.removePrefix(PREFIX) else null
}

/** Where [KeystoreSealer]'s key lives: the Android Keystore, or a test's own. */
internal interface SealingKeys {
    /** The key, or null when there is none (never made, or deleted with the app's data or a lock-screen reset). */
    fun existing(): SecretKey?

    fun create(): SecretKey
}

/**
 * AES-GCM with a key in the Android Keystore, so a copy of the app's files
 * (a backup, a rooted device's dump) does not carry usable tokens. Some TV
 * boxes have a broken Keystore; there it falls back to [PlainSealer], since
 * the files are app-private either way.
 *
 * Only a token that can never be opened signs its account out: no key, an
 * invalidated key, or one that fails to decrypt ([permanentKeystoreFailure]).
 * Any other Keystore error was signing people out for good on a busy or
 * flaky Keystore; now it fails that one request ([TokenUnavailableException]).
 */
internal class KeystoreSealer(private val keys: SealingKeys = AndroidKeystoreKeys) : TokenSealer {
    override fun seal(token: String): String = try {
        wrap(token)
    } catch (e: Exception) {
        Log.w(TAG, "Keystore unavailable; keeping the token unwrapped", e)
        PlainSealer.seal(token)
    }

    /** A token kept unwrapped by an earlier [seal], wrapped now; null while the Keystore still fails. */
    override fun reseal(sealed: String): String? {
        if (!sealed.startsWith(PlainSealer.PREFIX)) return null
        val token = PlainSealer.open(sealed) ?: return null
        return try {
            wrap(token)
        } catch (e: Exception) {
            null
        }
    }

    private fun wrap(token: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.existing() ?: keys.create())
        val out = cipher.iv + cipher.doFinal(token.toByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(out)
    }

    override fun open(sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return PlainSealer.open(sealed)
        // What was written can't be read: no key will ever open it.
        val bytes = runCatching { Base64.getDecoder().decode(sealed.removePrefix(PREFIX)) }.getOrNull()
        if (bytes == null || bytes.size <= IV_BYTES) return lost("it is not a sealed token", null)
        return try {
            val key = keys.existing() ?: return lost("its key is gone", null)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (e: Exception) {
            if (permanentKeystoreFailure(e)) return lost("it can't be opened", e)
            Log.w(TAG, "the Keystore failed to open a saved token; keeping the sign-in", e)
            throw TokenUnavailableException(e)
        }
    }

    private fun lost(why: String, e: Exception?): String? {
        Log.w(TAG, "could not open a saved token ($why); signing in again", e)
        return null
    }

    private object AndroidKeystoreKeys : SealingKeys {
        private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        override fun existing(): SecretKey? = (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

        override fun create(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val TAG = "WatchMyMedia"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "watchmymedia.tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val PREFIX = "k1:"
    }
}

/**
 * True when [e] (or what it wraps) means a sealed token can never be opened:
 * its key was invalidated (a lock-screen change, biometric enrolment), or the
 * token fails its authentication tag (another key, or corrupted). Anything
 * else, such as a Keystore that is busy or restarting, may pass.
 */
internal fun permanentKeystoreFailure(e: Throwable): Boolean =
    generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.take(8)
        .any { it is KeyPermanentlyInvalidatedException || it is AEADBadTagException }
