package com.dji.dronecontrol.core.gimbal

import com.dji.dronecontrol.core.model.GimbalRotateParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun interface GimbalSpeedFrameSender {
    suspend fun sendSpeed(pitchDps: Double, yawDps: Double)
}

/**
 * Streams gimbal speed rotation frames for [durationMs], then sends stop (0,0).
 */
class GimbalRotateStreamer(
    private val frameSender: GimbalSpeedFrameSender,
    private val scope: CoroutineScope,
    private val frameIntervalMs: Long = GimbalConstants.ROTATE_FRAME_INTERVAL_MS,
) {
    private var activeJob: Job? = null

    fun cancelActive() {
        activeJob?.cancel()
        activeJob = null
        scope.launch {
            frameSender.sendSpeed(0.0, 0.0)
        }
    }

    fun startRotate(params: GimbalRotateParams): Long {
        activeJob?.cancel()
        val duration = (params.durationMs ?: GimbalConstants.DEFAULT_ROTATE_DURATION_MS)
            .coerceIn(GimbalConstants.MIN_ROTATE_DURATION_MS, GimbalConstants.MAX_ROTATE_DURATION_MS)
        val pitch = params.pitchRateDps
        val yaw = params.yawRateDps
        activeJob = scope.launch {
            val endAt = System.currentTimeMillis() + duration
            while (isActive && System.currentTimeMillis() < endAt) {
                frameSender.sendSpeed(pitch, yaw)
                delay(frameIntervalMs)
            }
            frameSender.sendSpeed(0.0, 0.0)
        }
        return duration
    }
}
