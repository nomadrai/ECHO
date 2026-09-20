package com.echo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Key-envelope crypto on a plain JVM: AES-256-GCM round-trips, tamper
 * rejection, IV uniqueness and format guards. The AndroidKeyStore lookup is
 * injected (a locally generated JCE key stands in for the hardware key), so
 * on-device the only untested line is the Keystore fetch itself.
 */
class KeyProtectorTest {

    private lateinit var key: SecretKey

    @Before
    fun generateKey() {
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private fun protector() = KeyProtector(alias = "test") { key }

    @Test
    fun `round trip returns the original key material`() {
        val p = protector()
        val secret = "gsk_a1B2c3D4e5F6g7H8i9J0"
        assertEquals(secret, p.decrypt(p.encrypt(secret)))
    }

    @Test
    fun `unicode and whitespace survive the envelope`() {
        val p = protector()
        val secret = "AIzaŠk—or-… clé🔑\n\t "
        assertEquals(secret, p.decrypt(p.encrypt(secret)))
    }

    @Test
    fun `envelopes carry the enc1 format prefix`() {
        assertTrue(protector().encrypt("secret").startsWith(KeyProtector.PREFIX))
    }

    @Test
    fun `encrypting twice yields different ciphertexts - random IV per encryption`() {
        val p = protector()
        assertNotEquals(p.encrypt("same-key"), p.encrypt("same-key"))
        // Both still decrypt to the same plaintext.
        assertEquals("same-key", p.decrypt(p.encrypt("same-key")))
    }

    @Test
    fun `tampered ciphertext fails the GCM check and returns null`() {
        val p = protector()
        val encoded = p.encrypt("gsk_abc123")
        val payload = encoded.removePrefix(KeyProtector.PREFIX)
        val bytes = java.util.Base64.getDecoder().decode(payload)
        // Flip one bit in the middle of the ciphertext body.
        bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 0x40).toByte()
        val tampered = KeyProtector.PREFIX +
            java.util.Base64.getEncoder().encodeToString(bytes)
        assertNull(p.decrypt(tampered))
    }

    @Test
    fun `foreign formats and garbage return null instead of throwing`() {
        val p = protector()
        assertNull(p.decrypt("sk-plain-legacy-key"))
        assertNull(p.decrypt("enc1:not-base64!!!"))
        assertNull(p.decrypt("enc1:"))
        assertNull(p.decrypt(""))
        assertNull(p.decrypt("enc1:AAAA")) // version byte wrong / too short
    }

    @Test
    fun `envelope written with one key instance decrypts with another holding the same key`() {
        // Simulates process restarts: a fresh KeyProtector must read what an
        // earlier one wrote, as long as the Keystore key is the same.
        val written = KeyProtector(alias = "test") { key }.encrypt("gsk_restart")
        val read = KeyProtector(alias = "test") { key }.decrypt(written)
        assertEquals("gsk_restart", read)
    }

    @Test
    fun `a different Keystore key cannot read the envelope`() {
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val written = protector().encrypt("gsk_private")
        assertNull(KeyProtector(alias = "other") { otherKey }.decrypt(written))
    }

    @Test
    fun `empty plaintext round-trips`() {
        val p = protector()
        assertEquals("", p.decrypt(p.encrypt("")))
    }
}
