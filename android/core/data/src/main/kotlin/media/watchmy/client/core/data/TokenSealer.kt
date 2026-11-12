package media.watchmy.client.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps bearer tokens before they are written to disk. */
internal interface TokenSealer {
    fun seal(token: String): String

    /** The token, or null when it can no longer be opened (the key is gone): sign in again. */
    fun open(sealed: String): String?

    /**
     * [sealed] sealed better, now that it can be: a token kept unwrapped while
     * the Keystore failed, wrapped once it works again. Null leaves it as it is.
     */
    fun reseal(sealed: String): String? = null
}

/** Keeps tokens as they are: for tests, and the fallback when the Keystore fails. */
internal object PlainSealer : TokenSealer {
    const val PREFIX = "p1:"

    override fun seal(token: String) = PREFIX + token

    override fun open(sealed: String) = if (sealed.startsWith(PREFIX)) sealed.removePrefix(PREFIX) else null
}

/**
 * AES-GCM with a key in the Android Keystore, so a copy of the app's files
 * (a backup, a rooted device's dump) does not carry usable tokens. Some TV
 * boxes have a broken Keystore; there it falls back to [PlainSealer], since
 * the files are app-private either way.
 */
internal class KeystoreSealer : TokenSealer {
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
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(token.toByteArray())
        return PREFIX + Base64.getEncoder().encodeToString(out)
    }

    override fun open(sealed: String): String? {
        if (!sealed.startsWith(PREFIX)) return PlainSealer.open(sealed)
        return try {
            val bytes = Base64.getDecoder().decode(sealed.removePrefix(PREFIX))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(128, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
        } catch (e: Exception) {
            Log.w(TAG, "could not open a saved token; signing in again", e)
            null
        }
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generateKey()
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
