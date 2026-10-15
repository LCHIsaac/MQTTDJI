package com.dji.dronecontrol.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Command message sent to the drone controller.
 * Schema version 1.0.
 */
@Serializable
data class CommandEnvelope(
    @SerialName("schema_version")
    val schemaVersion: String = "1.0",
    @SerialName("msg_id")
    val msgId: String,
    val ts: String,
    @SerialName("aircraft_id")
    val aircraftId: String,
    val cmd: String,
    val params: JsonObject? = null
)

/**
 * Common status or acknowledgment response sent back to MQTT gateway.
 */
@Serializable
data class AckResponse(
    val type: String = "ack",
    @SerialName("msg_id")
    val msgId: String,
    val ok: Boolean,
    val error: String? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    val result: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable
data class AircraftGpsInfo(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @SerialName("altitude_m")
    val altitudeM: Double = 0.0,
    @SerialName("satellites_count")
    val satellitesCount: Int = 0
)

@Serializable
data class AircraftStatusMessage(
    val type: String = "aircraft.status",
    val connected: Boolean,
    val product: String,
    @SerialName("flight_mode")
    val flightMode: String,
    @SerialName("battery_pct")
    val batteryPct: Int,
    val gps: AircraftGpsInfo,
    @SerialName("in_air")
    val inAir: Boolean = false,
    @SerialName("motors_on")
    val motorsOn: Boolean = false,
    @SerialName("registered")
    val registered: Boolean = false,
    @SerialName("telemetry_ready")
    val telemetryReady: Boolean = false,
    @SerialName("error_state")
    val errorState: String? = null
)

@Serializable
data class CameraPhotoDoneMessage(
    val type: String = "camera.photo_done",
    val ok: Boolean,
    @SerialName("file_index")
    val fileIndex: Int? = null,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("local_path")
    val localPath: String? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    val bytes: Long? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    val error: String? = null,
    val source: String? = null,
)

@Serializable
data class CameraSnapshotDoneMessage(
    val type: String = "camera.snapshot_done",
    val ok: Boolean,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("local_path")
    val localPath: String? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    val bytes: Long? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    val error: String? = null,
    val source: String = "video_frame",
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class CameraRecordStatusMessage(
    val type: String = "camera.record_status",
    val recording: Boolean,
    @SerialName("duration_seconds")
    val durationSeconds: Int = 0,
    val ok: Boolean? = null,
    @SerialName("file_index")
    val fileIndex: Int? = null,
    @SerialName("download_url")
    val downloadUrl: String? = null,
    @SerialName("local_path")
    val localPath: String? = null,
    @SerialName("content_type")
    val contentType: String? = null,
    val bytes: Long? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    val error: String? = null,
)

@Serializable
data class StreamStatusMessage(
    val type: String = "stream.status",
    /** starting | live | stopped | error */
    val state: String = "stopped",
    val streaming: Boolean,
    val protocol: String? = null,
    val channel: String? = null,
    @SerialName("viewer_url")
    val viewerUrl: String? = null,
    val url: String? = null,
    val quality: String? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    val error: String? = null,
    @SerialName("started_at")
    val startedAt: String? = null,
)

@Serializable
data class ConfigGetResponseMessage(
    val type: String = "config.response",
    @SerialName("schema_version")
    val schemaVersion: String = "1.0",
    @SerialName("aircraft_id")
    val aircraftId: String,
    @SerialName("mqtt_connected")
    val mqttConnected: Boolean,
    @SerialName("aircraft_connected")
    val aircraftConnected: Boolean,
    @SerialName("product_type")
    val productType: String,
    val topics: Map<String, String>
)
