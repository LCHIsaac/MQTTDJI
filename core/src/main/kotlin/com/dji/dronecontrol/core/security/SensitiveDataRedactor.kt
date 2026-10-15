package com.dji.dronecontrol.core.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Redacts Agora publisher tokens (and similar secrets) from JSON/log text.
 */
object SensitiveDataRedactor {

    private val tokenFieldNames = setOf(
        "token",
        "publisher_token",
        "agora_token",
        "stream_token_secret",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun redactJsonPayload(rawJson: String): String {
        return try {
            val element = json.parseToJsonElement(rawJson)
            val redacted = redactElement(element)
            json.encodeToString(JsonElement.serializer(), redacted)
        } catch (_: Exception) {
            redactPlainText(rawJson)
        }
    }

    private val viewerUrlFragmentToken = Regex("""#token=[^"'\s}]+""", RegexOption.IGNORE_CASE)

    fun redactPlainText(text: String): String {
        var out = text
        out = redactAudienceTokenFragment(out)
        tokenFieldNames.forEach { field ->
            out = out.replace(
                Regex("""("$field"\s*:\s*)"[^"]*"""", RegexOption.IGNORE_CASE),
                """$1"***""""
            )
        }
        out = out.replace(
            Regex("""Authorization\s*:\s*Bearer\s+[^\s"']+""", RegexOption.IGNORE_CASE),
            "Authorization: Bearer ***",
        )
        out = out.replace(
            Regex(""""Bearer\s+[^"]+"""", RegexOption.IGNORE_CASE),
            "\"Bearer ***\"",
        )
        return out
    }

    /** Redacts secrets for log/console mirrors; MQTT acks publish the full watchable viewer_url separately. */
    fun redactForLogs(rawJson: String): String {
        val redacted = redactJsonPayload(rawJson)
        return redactAudienceTokenFragment(redacted)
    }

    fun redactAudienceTokenFragment(text: String): String =
        viewerUrlFragmentToken.replace(text, "#token=***")

    fun redactElement(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        return buildJsonObject {
            element.forEach { (key, value) ->
                if (tokenFieldNames.any { it.equals(key, ignoreCase = true) } && value is JsonPrimitive && value.isString) {
                    put(key, JsonPrimitive("***"))
                } else if (value is JsonObject) {
                    put(key, redactElement(value))
                } else {
                    put(key, value)
                }
            }
        }
    }

    fun containsTokenSecret(payload: String): Boolean {
        if (payload.contains("\"***\"")) return false
        return tokenFieldNames.any { field ->
            val pattern = Regex("""\"$field\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
            pattern.containsMatchIn(payload) && !pattern.find(payload)?.groupValues?.get(1).isNullOrBlank()
        }
    }
}
