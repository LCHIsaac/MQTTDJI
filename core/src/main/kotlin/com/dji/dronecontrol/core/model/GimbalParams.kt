package com.dji.dronecontrol.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GimbalRotateParams(
    @SerialName("pitch_rate_dps")
    val pitchRateDps: Double = 0.0,
    @SerialName("yaw_rate_dps")
    val yawRateDps: Double = 0.0,
    @SerialName("duration_ms")
    val durationMs: Long? = 500L,
)

@Serializable
data class GimbalSetAngleParams(
    @SerialName("pitch_deg")
    val pitchDeg: Double? = null,
    @SerialName("yaw_deg")
    val yawDeg: Double? = null,
    @SerialName("roll_deg")
    val rollDeg: Double? = null,
)
