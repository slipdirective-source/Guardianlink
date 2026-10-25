package guardianlink.v2.app

import android.content.Context
import guardianlink.v2.core.JsonParser
import guardianlink.v2.core.LanguagePort
import guardianlink.v2.core.StructuredIntent
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The cloud language port (his lane choice, 2026-10-25): an
 * OpenAI-compatible chat-completions endpoint reads the utterance
 * and returns a structured intent as JSON. Works with OpenAI, xAI
 * (Grok), OpenRouter, or a local server speaking the same shape —
 * the sovereign brings their own key, sealed by [LlmConfigStore].
 *
 * The port's whole authority is the JSON it returns; the core
 * validates it against the registry before anything can become a
 * proposal. Network trouble, a missing key, or malformed output
 * all read as "no reading" — the deterministic floor carries on.
 * Latency is bounded (6s connect / 12s read); the conversation
 * already runs off the UI thread.
 */
class CloudLanguagePort(context: Context) : LanguagePort {

    private val appContext = context.applicationContext
    private val store = LlmConfigStore(appContext)

    override fun parse(utterance: String): StructuredIntent? {
        val config = store.load() ?: return null
        if (config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) return null
        return try {
            val raw = complete(config, utterance) ?: return null
            IntentJson.decode(raw)
        } catch (e: Exception) {
            null
        }
    }

    /** Test hook for Settings: reads a fixed utterance, returns a human line about what happened. */
    fun testReading(): String {
        val config = store.load() ?: return "No language model is connected."
        val intent = try {
            val raw = complete(config, "send Shannon a text telling her I love her") ?: return "The provider returned nothing — check the key and model."
            IntentJson.decode(raw) ?: return "The provider answered, but not in a readable intent shape."
        } catch (e: Exception) {
            return "Couldn't reach the provider: ${e.message ?: e.javaClass.simpleName}"
        }
        return "Reading works: that sentence parses as ${intent.actionType} " +
            "(to=${intent.params["to"]}, body=\"${intent.params["body"]}\", confidence ${intent.confidence})."
    }

    private fun complete(config: LlmConfig, utterance: String): String? {
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val body = buildRequestJson(config.model, utterance)
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 6_000
        conn.readTimeout = 12_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) return null
        val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val root = JsonParser.parse(text) as? Map<*, *> ?: return null
        val choices = root["choices"] as? List<*> ?: return null
        val first = choices.firstOrNull() as? Map<*, *> ?: return null
        val message = first["message"] as? Map<*, *> ?: return null
        return message["content"] as? String
    }

    private fun buildRequestJson(model: String, utterance: String): String {
        val system = IntentJson.SYSTEM_PROMPT
        
        return "{\"model\":${jsonString(model)},\"temperature\":0,\"messages\":[" +
            "{\"role\":\"system\",\"content\":${jsonString(system)}}," +
            "{\"role\":\"user\",\"content\":${jsonString(utterance)}}]}"
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
