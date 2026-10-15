package com.dji.dronecontrol.adapter.dji

import android.content.Context
import com.dji.dronecontrol.core.model.PhotoSize
import com.dji.dronecontrol.core.port.PhotoDeliveryPort
import com.dji.dronecontrol.core.port.PhotoLocalSaveResult
import com.dji.dronecontrol.core.port.PhotoPullResult
import com.dji.dronecontrol.core.port.MediaAssetType
import com.dji.dronecontrol.core.port.MediaDownloadResult
import com.dji.dronecontrol.core.photo.PhotoErrorCodes
import java.io.File

/**
 * Photo delivery via shared [DjiMediaLibraryAdapter] media pull + app storage.
 */
class DjiPhotoDeliveryAdapter(
    context: Context,
    private val mediaLibrary: DjiMediaLibraryAdapter = DjiMediaLibraryAdapter(context),
) : PhotoDeliveryPort {

    override suspend fun pullLatestJpeg(preferredSize: PhotoSize, timeoutMs: Long): PhotoPullResult {
        return when (val result = mediaLibrary.pullLatestJpeg(timeoutMs)) {
            is MediaDownloadResult.Success -> PhotoPullResult.Success(
                jpegBytes = File(result.localPath).readBytes(),
                fileIndex = result.fileIndex,
                fileName = result.fileName,
            )
            is MediaDownloadResult.Failure -> PhotoPullResult.Failure(result.code, result.message)
        }
    }

    override suspend fun saveJpegLocally(
        jpegBytes: ByteArray,
        fileIndex: Int?,
        fileName: String?,
    ): PhotoLocalSaveResult {
        val cache = File.createTempFile("photo_upload_", ".jpg")
        cache.writeBytes(jpegBytes)
        val saved = mediaLibrary.saveToAppStorage(
            cache.absolutePath,
            MediaAssetType.PHOTO,
            fileIndex,
            fileName,
        )
        cache.delete()
        return if (saved != null) {
            PhotoLocalSaveResult.Success(saved)
        } else {
            PhotoLocalSaveResult.Failure(
                PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Local save failed",
            )
        }
    }

    fun mediaLibraryPort(): DjiMediaLibraryAdapter = mediaLibrary
}
