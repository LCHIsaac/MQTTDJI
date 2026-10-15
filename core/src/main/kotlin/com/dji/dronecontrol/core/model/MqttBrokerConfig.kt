package com.dji.dronecontrol.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MqttBrokerConfig(
    val host: String = "192.168.1.100",
    val port: Int = 1883,
    val useTls: Boolean = false,
    val username: String = "",
    val password: String = "",
    val clientId: String = "dji-rcpro-01",
    val aircraftId: String = "m3e-01",
    val commandTopicOverride: String? = null,
    val statusTopicOverride: String? = null,
    val ackTopicOverride: String? = null,
    /** When true, connect with client certificate (material loaded separately). */
    val useMtls: Boolean = false,
    @SerialName("viewer_base_url")
    val viewerBaseUrl: String = "https://tronico.kujuhk.com/live",
    /** Blank uses app default https://tronico.kujuhk.com/live/token */
    @SerialName("stream_token_url")
    val streamTokenUrl: String = "",
    /** Blank uses app default https://tronico.kujuhk.com/live/photo */
    @SerialName("photo_upload_url")
    val photoUploadUrl: String = "",
    /** Blank uses app default https://tronico.kujuhk.com/live/video */
    @SerialName("video_upload_url")
    val videoUploadUrl: String = "",
) {
    val commandTopic: String
        get() = commandTopicOverride?.takeIf { it.isNotBlank() } ?: "dji/$aircraftId/cmd"

    val statusTopic: String
        get() = statusTopicOverride?.takeIf { it.isNotBlank() } ?: "dji/$aircraftId/status"

    val ackTopic: String
        get() = ackTopicOverride?.takeIf { it.isNotBlank() } ?: "dji/$aircraftId/ack"

    val brokerUrl: String
        get() {
            val scheme = if (useTls) "ssl://" else "tcp://"
            return "$scheme$host:$port"
        }
}
