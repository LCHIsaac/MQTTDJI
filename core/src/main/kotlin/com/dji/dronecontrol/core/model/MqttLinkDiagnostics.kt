package com.dji.dronecontrol.core.model

/**
 * MQTT link snapshot for the Connection checklist (populated by the transport layer).
 */
data class MqttLinkDiagnostics(
    val brokerEndpoint: String = "—",
    val connectionStateLabel: String = "disconnected",
    val lastError: String? = null,
    val commandTopic: String = "—",
    val subscribedToCommandTopic: Boolean = false,
    val lastMessageInEpochMs: Long? = null,
    val lastMessageOutEpochMs: Long? = null,
)
