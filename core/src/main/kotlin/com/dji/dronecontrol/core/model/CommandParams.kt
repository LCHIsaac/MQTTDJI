package com.dji.dronecontrol.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class VirtualStickMode {
    @SerialName("velocity")
    VELOCITY,
    @SerialName("stick")
    STICK
}

/**
 * Represents a virtual stick command payload.
 * When in VELOCITY mode: vx (m/s, positive forward), vy (m/s, positive right), vz (m/s, positive up), yawRate (deg/s, positive clockwise).
 * When in STICK mode: values normalized between -1.0 and 1.0.
 */
@Serializable
data class VirtualStickCommand(
    val mode: VirtualStickMode = VirtualStickMode.VELOCITY,
    val vx: Double = 0.0,
    val vy: Double = 0.0,
    val vz: Double = 0.0,
    @SerialName("yaw_rate")
    val yawRate: Double = 0.0,
    val roll: Double = 0.0,
    val pitch: Double = 0.0,
    val yaw: Double = 0.0,
    val throttle: Double = 0.0,
    @SerialName("duration_ms")
    val durationMs: Long? = 500L
)

@Serializable
enum class PhotoSize {
    @SerialName("12mp")
    SIZE_12MP,
    @SerialName("48mp")
    SIZE_48MP,
    @SerialName("default")
    DEFAULT
}

@Serializable
enum class PhotoFormat {
    @SerialName("jpeg")
    JPEG,
    @SerialName("dng")
    DNG,
    @SerialName("raw_jpeg")
    RAW_JPEG
}

@Serializable
enum class PhotoDeliverMode {
    @SerialName("link")
    LINK,
    @SerialName("local")
    LOCAL,
    @SerialName("both")
    BOTH,
}

@Serializable
data class RecordStopParams(
    val deliver: PhotoDeliverMode = PhotoDeliverMode.BOTH,
)

@Serializable
data class PhotoParams(
    val size: PhotoSize = PhotoSize.DEFAULT,
    val format: PhotoFormat = PhotoFormat.JPEG,
    /** Default: pull to handset and upload link (`both`). */
    val deliver: PhotoDeliverMode = PhotoDeliverMode.BOTH,
)

@Serializable
data class SnapshotParams(
    /** Default: local file + upload link (`both`). */
    val deliver: PhotoDeliverMode = PhotoDeliverMode.BOTH,
    /** JPEG encode quality 1–100 (default 85). */
    val quality: Int = 85,
)

@Serializable
enum class StreamQuality {
    @SerialName("SD")
    SD,
    @SerialName("HD")
    HD,
    @SerialName("FULL_HD")
    FULL_HD,
    @SerialName("AUTO")
    AUTO
}

@Serializable
data class StreamStartParams(
    val protocol: String = "rtmp",
    val url: String = "",
    val channel: String = "",
    val token: String = "",
    val uid: Int = 1001,
    @SerialName("app_id")
    val appId: String? = null,
    val quality: StreamQuality = StreamQuality.HD,
)

@Serializable
data class TakeoffParams(
    @SerialName("target_height_m")
    val targetHeightM: Double? = null
)

/**
 * Parameters for rotating the aircraft about its yaw axis.
 * yawRate: angular velocity in deg/s (+ clockwise / right, - counter-clockwise / left).
 * durationMs: rotation duration in milliseconds (default: 500ms, min: 50ms, max: 10,000ms).
 */
@Serializable
data class RotateParams(
    @SerialName("yaw_rate")
    val yawRate: Double = 0.0,
    @SerialName("duration_ms")
    val durationMs: Long? = 500L
)

