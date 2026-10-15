package com.dji.dronecontrol.core.port

import com.dji.dronecontrol.core.model.MqttBrokerConfig
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class MqttConnectionState {
    object Disconnected : MqttConnectionState()
    object Connecting : MqttConnectionState()
    object Connected : MqttConnectionState()
    data class Error(val message: String, val cause: Throwable? = null) : MqttConnectionState()
}

/**
 * Domain port for MQTT broker transport.
 */
interface MqttTransportPort {
    val connectionState: StateFlow<MqttConnectionState>
    val incomingCommands: SharedFlow<String> // Raw JSON payloads from command topic

    suspend fun connect(config: MqttBrokerConfig)
    suspend fun disconnect()
    suspend fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false)
}
