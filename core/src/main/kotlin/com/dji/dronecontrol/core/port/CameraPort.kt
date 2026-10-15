package com.dji.dronecontrol.core.port

import com.dji.dronecontrol.core.model.PhotoParams
import kotlinx.coroutines.flow.StateFlow

sealed class CameraResult {
    data class PhotoSuccess(val fileIndex: Int?) : CameraResult()
    object RecordStarted : CameraResult()
    object RecordStopped : CameraResult()
    data class Failure(val message: String, val cause: Throwable? = null) : CameraResult()
}

/**
 * Domain port for aircraft camera operations.
 * Pure Kotlin interface, no DJI imports.
 */
interface CameraPort {
    suspend fun takePhoto(params: PhotoParams = PhotoParams()): CameraResult
    suspend fun startRecord(): CameraResult
    suspend fun stopRecord(): CameraResult

    val isRecording: StateFlow<Boolean>
    val recordingDurationSeconds: StateFlow<Int>
    val cameraMode: StateFlow<String>
}
