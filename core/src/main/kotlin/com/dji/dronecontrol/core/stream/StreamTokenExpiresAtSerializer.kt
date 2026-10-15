package com.dji.dronecontrol.core.stream

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Accepts `expires_at` as a JSON string (ISO or opaque) or Unix epoch seconds as a JSON number.
 * Stored internally as an optional string (numeric values use decimal digits).
 */
object StreamTokenExpiresAtSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StreamTokenExpiresAt", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return decoder.decodeString()
        return when (val element: JsonElement = jsonDecoder.decodeJsonElement()) {
            JsonNull -> null
            is JsonPrimitive -> {
                if (element.isString) {
                    element.content.takeIf { it.isNotBlank() }
                } else {
                    element.longOrNull?.toString()
                        ?: element.doubleOrNull?.toLong()?.toString()
                }
            }
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: String?) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder != null) {
            if (value == null) {
                jsonEncoder.encodeJsonElement(JsonNull)
            } else {
                jsonEncoder.encodeJsonElement(JsonPrimitive(value))
            }
        } else if (value != null) {
            encoder.encodeString(value)
        }
    }

    private val JsonPrimitive.longOrNull: Long?
        get() = content.toLongOrNull() ?: doubleOrNull?.toLong()
}
