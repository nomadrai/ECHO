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

    /**
     * One investigator turn. Returns the assistant's reply text. The
     * [mode] selects the system prompt: single-session forensics (default)
     * or cross-session trends.
     */
    suspend fun ask(
        digest: String,
        question: String,
        history: List<Pair<String, String>> = emptyList(),
        mode: Mode = Mode.SESSION,
    ): String =
        withContext(Dispatchers.IO) {
            val provider = settings.provider
            val key = settings.keyFor(provider)
            if (key.isBlank()) {
                throw IllegalStateException("No API key configured for ${provider.displayName}")
            }
            val body = when (provider.protocol) {
                ApiProvider.Protocol.OPENAI_COMPATIBLE ->
                    openAiBody(digest, question, history, settings.modelFor(provider), mode)
                ApiProvider.Protocol.GOOGLE_GENERATE_CONTENT ->
                    googleBody(digest, question, history, settings.modelFor(provider), mode)
            }
            val url = when (provider.protocol) {
                ApiProvider.Protocol.OPENAI_COMPATIBLE -> provider.endpoint
                ApiProvider.Protocol.GOOGLE_GENERATE_CONTENT ->
                    "${provider.endpoint}/${settings.modelFor(provider)}:generateContent?key=$key"
            }
            httpPost(url, body, provider, key)
        }

    /** Which investigator role the model plays for this request. */
    enum class Mode { SESSION, TRENDS }

    private fun openAiBody(
        digest: String,
        question: String,
        history: List<Pair<String, String>>,
        model: String,
        mode: Mode,
    ): String = buildJsonObject {
        put("model", model)
        put("temperature", 0.2)
        put("max_tokens", 1024)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", if (mode == Mode.TRENDS) trendsPrompt(digest) else systemPrompt(digest))
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
        mode: Mode,
    ): String {
        // The system instruction rides as the first user turn — the
        // generateContent shape has no separate system role on this endpoint.
        val system = if (mode == Mode.TRENDS) trendsPrompt(digest) else systemPrompt(digest)
        val contents = buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
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
        You are ECHO's incident investigator — a forensic analyst answering questions about a
        physical experiment, on-device, from the event digest below. The digest is your ONLY
        data source. Everything else is speculation and must be labelled as such.

        HOW TO ANSWER
        1. Answer the question that was asked, in the first sentence. No preamble, no
           restating the question, no "based on the digest" filler.
        2. Be direct and concrete: name the events, the times, and the numbers
           ([E<id> @ +mm:ss.mmm] citation for every factual claim). A reader with the phone
           in hand should be able to scrub to that moment.
        3. Use correct technical terms for the domain in WORK CONTEXT (impacts, transients,
           RMS, σ-deviation, sustained elevation, fused multi-modal incidents). Do not
           invent measurements that are not in the digest.
        4. Conclude from evidence: your explanation must reference the cited events and
           their measured relations. If the evidence supports an interpretation, state it
           plainly with the reasoning chain ("the impact at +00:42.6 co-occurred with the
           frame-motion spike 100 ms earlier"). Keep non-causal language: co-occurred /
           preceded / followed / may indicate — never "caused", "because", or "due to"
           unless a PRECEDES edge in RELATIONS supports the ordering.
        5. Filter by work context: EXPECTED-NORMAL lists the signals that are part of the
           work itself (motor hum and vibration in mechanical work, stirrer noise in
           chemistry, tool noise in construction). Do NOT mention these unless the question
           is about them or they are materially relevant to the answer. Events tagged
           [baseline-calibration] are the device learning the room in the first 30 s —
           normal by definition; never cite them as evidence of a fault.
        6. Separate OBSERVED (cited events) from INFERRED (your interpretation, one clear
           sentence on what it may indicate). Say "insufficient evidence in this session's
           data for X" when the digest does not support an answer — that is a valid,
           useful answer. Never pad; never list unrelated sensor channels to look thorough.

        RESPONSE SHAPE (keep it tight)
        - One-line direct answer.
        - Evidence: cited events with times and the relevant relation edges.
        - Interpretation: what it may indicate, grounded in the citations (omit if the
          question was purely factual).
        - If evidence is missing: say so in one sentence and name what data would settle it.

        $digest
    """.trimIndent()

    /**
     * The trends analyst: reasons ACROSS sessions over the pre-computed stats
     * and per-session summaries. Same evidentiary discipline as the
     * single-session prompt (cite or abstain), different citations: S# for
     * sessions, S#E# for events, S#T# for user tags.
     */
    private fun trendsPrompt(context: String): String = """
        You are ECHO's trends analyst — a forensic analyst comparing SEVERAL recorded
        sessions of an experiment, on-device, from the cross-session context below. The
        context is your ONLY data source: a CROSS-SESSION STATS block (pre-computed,
        deterministic — quote those numbers, never re-derive or contradict them) and one
        compact summary block per session. Everything else is speculation.

        HOW TO ANSWER
        1. Answer the question in the first sentence, with the direction and the numbers
           ("events per session rose from 4 to 11 across the week — rising trend").
        2. Cite evidence per session: S<id> with its date, and specific events as
           S<session>#E<event> (tags as S<session>#T<tag>). A reader should be able to
           open that exact session and scrub to that moment.
        3. Compare like with like: the summaries state each session's work type and
           duration. If sessions differ in duration, normalize counts per hour before
           claiming a trend; say when a difference is explained by duration alone.
        4. If the comparison set is marked INTENTIONAL CROSS-TYPE, treat the work-type
           difference as part of the question. Otherwise compare only same-type sessions
           and say so when the set is mixed.
        5. Separate OBSERVED (cited numbers and sessions) from INFERRED (one clear
           sentence on what the pattern may indicate). If the selected sessions cannot
           support the question (too few, too different, missing data), say so in one
           sentence and name what would settle it. Never invent sessions or events.

        RESPONSE SHAPE (keep it tight)
        - One-line direct answer with the trend direction and numbers.
        - Evidence: per-session numbers/citations that establish it.
        - Interpretation: what the pattern may indicate (omit for purely factual asks).
        - If evidence is missing: say so in one sentence and name what data would settle it.

        $context
    """.trimIndent()
}
