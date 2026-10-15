package com.dji.dronecontrol.core.model

/**
 * Client certificate material for MQTT mTLS (bytes kept in app-private storage).
 */
data class MqttTlsConfig(
    val pkcs12Bytes: ByteArray,
    val pkcs12Password: String,
    val customCaPemBytes: ByteArray? = null,
) {
    val enabled: Boolean get() = pkcs12Bytes.isNotEmpty()
}
