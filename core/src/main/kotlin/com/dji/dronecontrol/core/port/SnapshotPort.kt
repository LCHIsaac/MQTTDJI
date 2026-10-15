package com.dji.dronecontrol.core.port

sealed class SnapshotCaptureResult {
    data class Success(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
    ) : SnapshotCaptureResult()

    data class Failure(val code: String, val message: String) : SnapshotCaptureResult()
}

/**
 * Captures a single JPEG from the live camera video feed on the handset (no aircraft SD).
 */
interface SnapshotPort {
    suspend fun captureVideoFrameJpeg(
        quality: Int = 85,
        timeoutMs: Long = 8_000L,
    ): SnapshotCaptureResult
}

class NoOpSnapshotPort : SnapshotPort {
    override suspend fun captureVideoFrameJpeg(quality: Int, timeoutMs: Long): SnapshotCaptureResult =
        SnapshotCaptureResult.Failure(
            com.dji.dronecontrol.core.photo.PhotoErrorCodes.NO_VIDEO_FRAME,
            "Snapshot port not configured",
        )
}
