package com.echo.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-provider API credentials and model selection, stored in app-private
 * `SharedPreferences`. Keys never leave the device except inside the request
 * to the provider the user chose (plan §10: consent-gated, user-triggered).
 *
 * Deliberately SharedPreferences, not SQLite: three tiny string rows, no
 * queries, and secrets should not sit in the timeline DB that export shares.
 */
class ApiSettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("echo_api_settings", Context.MODE_PRIVATE)

    var provider: ApiProvider
        get() = ApiProvider.from(prefs.getString(KEY_PROVIDER, ApiProvider.GROQ.id) ?: "groq")
        set(value) = prefs.edit().putString(KEY_PROVIDER, value.id).apply()

    fun keyFor(provider: ApiProvider): String =
        prefs.getString("key_${provider.id}", "") ?: ""

    fun setKey(provider: ApiProvider, key: String) {
        prefs.edit().putString("key_${provider.id}", key.trim()).apply()
    }

    fun modelFor(provider: ApiProvider): String =
        prefs.getString("model_${provider.id}", provider.defaultModel) ?: provider.defaultModel

    fun setModel(provider: ApiProvider, model: String) {
        prefs.edit().putString("model_${provider.id}", model.trim()).apply()
    }

    /** True when the currently selected provider has a key configured. */
    fun isConfigured(): Boolean = keyFor(provider).isNotBlank()

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
