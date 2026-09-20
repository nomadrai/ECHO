package com.echo.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed encryption for the user's provider API keys at rest.
 *
 * A single AES-256-GCM key lives in **AndroidKeyStore** (hardware-backed where
 * the device supports it) and never leaves it — the app only ever sees
 * ciphertext. Stored keys are envelopes:
 *
 * ```
 * enc1:<Base64(version byte ‖ IV-length byte ‖ IV ‖ GCM ciphertext+tag)>
 * ```
 *
 * The `enc1:` prefix marks the format (future migration path) and lets
 * [decrypt] reject anything that is not ours. The IV is random per
 * encryption, so encrypting the same plaintext twice yields different
 * ciphertexts. Tampering (any bit flip) fails the GCM tag check and decrypt
 * returns null — the caller drops the unreadable entry rather than failing
 * forever on it.
 *
 * The AndroidKeyStore lookup is injected as [keyProvider] so the crypto logic
 * itself is unit-testable on the plain JVM (the test injects a locally
 * generated JCE key; on device the default talks to the real Keystore).
 */
class KeyProtector(
    private val alias: String = DEFAULT_ALIAS,
    private val keyProvider: (String) -> SecretKey = { androidKey(it) },
) {
    private val key: SecretKey by lazy { keyProvider(alias) }

    /** Encrypts [plain] into an `enc1:` envelope; throws only on Keystore failures. */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val envelope = ByteArray(1 + 1 + iv.size + ciphertext.size)
        envelope[0] = VERSION
        envelope[1] = iv.size.toByte()
        iv.copyInto(envelope, 2)
        ciphertext.copyInto(envelope, 2 + iv.size)
        return PREFIX + Base64.getEncoder().encodeToString(envelope)
    }

    /**
     * Decrypts an `enc1:` envelope; null for malformed input, foreign format,
     * or a failed GCM integrity check (tamper / Keystore key invalidated).
     */
    fun decrypt(encoded: String): String? {
        if (!encoded.startsWith(PREFIX)) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded.removePrefix(PREFIX))
            require(bytes.size >= 3 && bytes[0] == VERSION)
            val ivLength = bytes[1].toInt()
            require(ivLength in MIN_IV_LEN..MAX_IV_LEN)
            require(bytes.size >= 2 + ivLength + GCM_TAG_BITS / 8)
            val iv = bytes.copyOfRange(2, 2 + ivLength)
            val ciphertext = bytes.copyOfRange(2 + ivLength, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        const val PREFIX = "enc1:"
        private const val DEFAULT_ALIAS = "echo_api_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val MIN_IV_LEN = 12
        private const val MAX_IV_LEN = 16
        private const val VERSION: Byte = 1

        /**
         * The AndroidKeyStore key: fetched once per process, generated on
         * first use. Never user-authentication-gated — ECHO must decrypt the
         * key for a question asked while the session service holds the
         * foreground, not only after a device unlock.
         */
        fun androidKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
