package com.echo.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal external-AI client: one HTTPS POST per question, no SDK, no streaming
 * (plan §10 — the external path is an explicitly user-triggered fallback, so
 * request/response simplicity beats token streaming).
 *
 * Supports the two wire shapes that matter:
 *  - OpenAI-compatible chat completions (Groq, OpenRouter)
 *  - Google generateContent (Google AI Studio)
 *
 * All I/O runs on [Dispatchers.IO]; timeouts are tight so a dead network fails
 * fast back to the deterministic local answer path.
 */
class ExternalAiClient(private val settings: ApiSettingsStore) {

    private val json = Json { ignoreUnknownKeys = true }

    /** One investigator turn. Returns the assistant's reply text. */
    suspend fun ask(digest: String, question: String, history: List<Pair<String, String>> = emptyList()): String =
        withContext(Dispatchers.IO) {
            val provider = settings.provider
            val key = settings.keyFor(provider)
            if (key.isBlank()) {
                throw IllegalStateException("No API key configured for ${provider.displayName}")
            }
            val body = when (provider.protocol) {
                ApiProvider.Protocol.OPENAI_COMPATIBLE ->
                    openAiBody(digest, question, history, settings.modelFor(provider))
                ApiProvider.Protocol.GOOGLE_GENERATE_CONTENT ->
                    googleBody(digest, question, history, settings.modelFor(provider))
            }
            val url = when (provider.protocol) {
                ApiProvider.Protocol.OPENAI_COMPATIBLE -> provider.endpoint
                ApiProvider.Protocol.GOOGLE_GENERATE_CONTENT ->
                    "${provider.endpoint}/${settings.modelFor(provider)}:generateContent?key=$key"
            }
            httpPost(url, body, provider, key)
        }

    private fun openAiBody(
        digest: String,
        question: String,
        history: List<Pair<String, String>>,
        model: String,
    ): String = buildJsonObject {
        put("model", model)
        put("temperature", 0.2)
        put("max_tokens", 1024)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt(digest))
            })
            history.forEach { (q, a) ->
                add(buildJsonObject {
                    put("role", "user")
                    put("content", q)
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", a)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", question)
            })
        })
    }.toString()

    private fun googleBody(
        digest: String,
        question: String,
        history: List<Pair<String, String>>,
        model: String,
    ): String {
        // The system instruction rides as the first user turn — the
        // generateContent shape has no separate system role on this endpoint.
        val contents = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", systemPrompt(digest)) }) })
            })
            add(buildJsonObject {
                put("role", "model")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", "Understood. I will cite only events from this digest.") }) })
            })
            history.forEach { (q, a) ->
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", q) }) })
                })
                add(buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", a) }) })
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", question) }) })
            })
        }
        return buildJsonObject {
            put("contents", contents)
            put("generationConfig", buildJsonObject {
                put("temperature", 0.2)
                put("maxOutputTokens", 1024)
            })
        }.toString()
    }

    private fun httpPost(url: String, body: String, provider: ApiProvider, key: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (provider.protocol == ApiProvider.Protocol.OPENAI_COMPATIBLE) {
                connection.setRequestProperty("Authorization", "Bearer $key")
            }
            // OpenRouter recommends (and some models require) app attribution.
            if (provider == ApiProvider.OPENROUTER) {
                connection.setRequestProperty("HTTP-Referer", "https://echo.local")
                connection.setRequestProperty("X-Title", "ECHO Incident Investigator")
            }
            connection.outputStream.use { it.write(body.toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from ${provider.displayName}: ${text.take(300)}")
            }
            return when (provider.protocol) {
                ApiProvider.Protocol.OPENAI_COMPATIBLE -> parseOpenAi(text)
                ApiProvider.Protocol.GOOGLE_GENERATE_CONTENT -> parseGoogle(text)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseOpenAi(text: String): String =
        json.parseToJsonElement(text).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Unexpected response shape from provider")

    private fun parseGoogle(text: String): String =
        json.parseToJsonElement(text).jsonObject["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")
            ?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Unexpected response shape from provider")

    private fun systemPrompt(digest: String): String = """
        You are ECHO's incident investigator. You reason ONLY over the event digest below.
        Rules:
        - Cite events as [E<id> @ +mm:ss.mmm] for every factual claim.
        - Use "may indicate / preceded / followed / co-occurred" — never "caused".
        - Separate what was OBSERVED from what you INFER. Say "insufficient evidence" when the digest does not support an answer.
        - Be concise and concrete; reference timestamps.

        $digest
    """.trimIndent()
}
