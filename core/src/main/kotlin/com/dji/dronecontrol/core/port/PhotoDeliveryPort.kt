package com.dji.dronecontrol.core.port

import com.dji.dronecontrol.core.model.PhotoSize

sealed class PhotoPullResult {
    data class Success(
        val jpegBytes: ByteArray,
        val fileIndex: Int,
        val fileName: String,
    ) : PhotoPullResult()

    data class Failure(val code: String, val message: String) : PhotoPullResult()
}

sealed class PhotoLocalSaveResult {
    data class Success(val localPath: String) : PhotoLocalSaveResult()
    data class Failure(val code: String, val message: String) : PhotoLocalSaveResult()
}

/**
 * Pulls captured media from the aircraft and saves JPEGs on the handset.
 */
interface PhotoDeliveryPort {
    suspend fun pullLatestJpeg(preferredSize: PhotoSize, timeoutMs: Long = 120_000L): PhotoPullResult

    suspend fun saveJpegLocally(jpegBytes: ByteArray, fileIndex: Int?, fileName: String?): PhotoLocalSaveResult
}

class NoOpPhotoDeliveryPort : PhotoDeliveryPort {
    override suspend fun pullLatestJpeg(preferredSize: PhotoSize, timeoutMs: Long): PhotoPullResult =
        PhotoPullResult.Failure(
            com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
            "Photo delivery port not configured",
        )

    override suspend fun saveJpegLocally(
        jpegBytes: ByteArray,
        fileIndex: Int?,
        fileName: String?,
    ): PhotoLocalSaveResult =
        PhotoLocalSaveResult.Failure(
            com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
            "Photo delivery port not configured",
        )
}
