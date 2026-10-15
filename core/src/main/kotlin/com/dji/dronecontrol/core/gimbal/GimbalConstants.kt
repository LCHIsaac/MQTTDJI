package com.dji.dronecontrol.core.gimbal

object GimbalConstants {
    const val DEFAULT_ROTATE_DURATION_MS: Long = 500L
    const val MIN_ROTATE_DURATION_MS: Long = 50L
    const val MAX_ROTATE_DURATION_MS: Long = 10_000L
    const val ROTATE_FRAME_INTERVAL_MS: Long = 50L
    const val DEFAULT_PITCH_RATE_DPS: Double = 10.0
    /** Gimbal rotation duration for KeyRotateByAngle (SDK unit: seconds). */
    const val SET_ANGLE_DURATION_SEC: Double = 0.5
    /** Action timeout for KeyRotateByAngle (SDK unit: seconds, per DJI samples). */
    const val SET_ANGLE_ACTION_TIMEOUT_SEC: Int = 10
    const val PITCH_MIN_DEG: Double = -90.0
    const val PITCH_MAX_DEG: Double = 35.0
}
