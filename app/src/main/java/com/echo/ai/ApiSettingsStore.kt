package com.echo.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-provider API credentials and model selection, stored in app-private
 * `SharedPreferences`. Keys never leave the device except inside the request
 * to the provider the user chose (plan §10: consent-gated, user-triggered).
 *
 * Keys are **encrypted at rest** with [KeyProtector] (AES-256-GCM, key held
 * in AndroidKeyStore): the XML file carries only `enc1:` ciphertext envelopes
 * under `enc_key_<id>`, never plaintext. Plaintext keys written by earlier
 * app versions are migrated once per process in [init] and their plaintext
 * entries deleted — no user action, nothing lost. A key that no longer
 * decrypts (Keystore invalidated by the OS) is dropped rather than wedged:
 * the user re-enters it, which beats a permanently failing chat.
 *
 * Deliberately SharedPreferences, not SQLite: three tiny string rows, no
 * queries, and secrets should not sit in the timeline DB that export shares.
 */
class ApiSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("echo_api_settings", Context.MODE_PRIVATE)
    private val protector = KeyProtector()

    init {
        migrateLegacyPlaintextKeys()
    }

    var provider: ApiProvider
        get() = ApiProvider.from(prefs.getString(KEY_PROVIDER, ApiProvider.GROQ.id) ?: "groq")
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.id).apply()

    fun keyFor(provider: ApiProvider): String {
        val encrypted = prefs.getString(encryptedKeyName(provider.id), null) ?: return ""
        val plain = protector.decrypt(encrypted)
        if (plain != null) return plain
        // Undecryptable: corrupt or invalidated by the OS. Remove it so the
        // next save writes fresh ciphertext instead of failing forever.
        prefs.edit().remove(encryptedKeyName(provider.id)).apply()
        return ""
    }

    fun setKey(provider: ApiProvider, key: String) {
        val trimmed = key.trim()
        prefs.edit()
            .putString(
                encryptedKeyName(provider.id),
                if (trimmed.isBlank()) null else protector.encrypt(trimmed),
            )
            .remove(legacyKeyName(provider.id))
            .apply()
    }

    fun modelFor(provider: ApiProvider): String =
        prefs.getString("model_${provider.id}", provider.defaultModel) ?: provider.defaultModel

    fun setModel(provider: ApiProvider, model: String) {
        prefs.edit().putString("model_${provider.id}", model.trim()).apply()
    }

    /** True when the currently selected provider has a key configured. */
    fun isConfigured(): Boolean = keyFor(provider).isNotBlank()

    /**
     * One-time upgrade: any pre-encryption plaintext `key_<id>` entries are
     * encrypted under `enc_key_<id>` and the plaintext removed. Idempotent —
     * after the first run there is nothing legacy left to find.
     */
    private fun migrateLegacyPlaintextKeys() {
        val edit = prefs.edit()
        var found = false
        for (provider in ApiProvider.entries) {
            val plain = prefs.getString(legacyKeyName(provider.id), null) ?: continue
            found = true
            val trimmed = plain.trim()
            if (trimmed.isNotBlank() && prefs.getString(encryptedKeyName(provider.id), null) == null) {
                runCatching { protector.encrypt(trimmed) }
                    .getOrNull()
                    ?.let { edit.putString(encryptedKeyName(provider.id), it) }
            }
            edit.remove(legacyKeyName(provider.id))
        }
        if (found) edit.apply()
    }

    private fun encryptedKeyName(providerId: String) = "enc_key_$providerId"

    private fun legacyKeyName(providerId: String) = "key_$providerId"

    private companion object {
        const val KEY_PROVIDER = "provider"
    }
}

/** External AI providers ECHO can talk to, with wire protocol details. */
enum class ApiProvider(
    val id: String,
    val displayName: String,
    val endpoint: String,
    /** OpenAI-compatible chat completions vs Google's generateContent shape. */
    val protocol: Protocol,
    val defaultModel: String,
    val keyHint: String,
) {
    GROQ(
        id = "groq",
        displayName = "Groq",
        endpoint = "https://api.groq.com/openai/v1/chat/completions",
        protocol = Protocol.OPENAI_COMPATIBLE,
        defaultModel = "llama-3.3-70b-versatile",
        keyHint = "console.groq.com/keys",
    ),
    GOOGLE_AI_STUDIO(
        id = "google",
        displayName = "Google AI Studio",
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models",
        protocol = Protocol.GOOGLE_GENERATE_CONTENT,
        defaultModel = "gemini-2.0-flash",
        keyHint = "aistudio.google.com/apikey",
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        endpoint = "https://openrouter.ai/api/v1/chat/completions",
        protocol = Protocol.OPENAI_COMPATIBLE,
        defaultModel = "meta-llama/llama-3.3-70b-instruct",
        keyHint = "openrouter.ai/keys",
    ),
    ;

    enum class Protocol { OPENAI_COMPATIBLE, GOOGLE_GENERATE_CONTENT }

    companion object {
        fun from(id: String): ApiProvider =
            entries.firstOrNull { it.id == id } ?: GROQ
    }
}
