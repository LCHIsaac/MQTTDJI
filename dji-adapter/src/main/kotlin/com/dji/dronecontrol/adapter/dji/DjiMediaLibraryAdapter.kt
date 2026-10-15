package com.dji.dronecontrol.adapter.dji

import android.content.Context
import android.util.Log
import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.photo.PhotoErrorCodes
import com.dji.dronecontrol.core.port.MediaAssetType
import com.dji.dronecontrol.core.port.MediaDeleteResult
import com.dji.dronecontrol.core.port.MediaDownloadResult
import com.dji.dronecontrol.core.port.MediaLibraryEntry
import com.dji.dronecontrol.core.port.MediaLibraryPort
import com.dji.dronecontrol.core.port.MediaListFilter
import com.dji.dronecontrol.core.port.MediaListResult
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.file.FileListRequestTimeOrderType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileFilter
import dji.v5.manager.datacenter.media.MediaFileListState
import dji.v5.manager.datacenter.media.MediaFileListStateListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DjiMediaLibraryAdapter(
    private val appContext: Context,
) : MediaLibraryPort {

    private val tag = "DjiMediaLibrary"

    private val mediaFileByIndex = mutableMapOf<Int, MediaFile>()

    private fun logI(msg: String) {
        Log.i(tag, msg)
        AppLogger.i(tag, msg)
    }

    private fun logE(msg: String) {
        Log.e(tag, msg)
        AppLogger.e(tag, msg)
    }

    override suspend fun refreshList(filter: MediaListFilter): MediaListResult = withContext(Dispatchers.IO) {
        try {
            return@withContext refreshListInternal(filter)
        } catch (t: Throwable) {
            logE("refreshList crashed: ${t.message}")
            return@withContext MediaListResult.Failure(
                PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Media list failed: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private suspend fun refreshListInternal(filter: MediaListFilter): MediaListResult {
        val mediaManager = try {
            MediaDataCenter.getInstance().mediaManager
        } catch (t: Throwable) {
            logE("MediaDataCenter unavailable: ${t.message}")
            return MediaListResult.Failure(
                PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Media center unavailable: ${t.message ?: "not ready"}",
            )
        }
        if (!enableMediaManager(mediaManager)) {
            return MediaListResult.Failure(
                PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Media manager could not be enabled",
            )
        }
        val sdkFilter = when (filter) {
            MediaListFilter.PHOTOS -> MediaFileFilter.PHOTO
            MediaListFilter.VIDEOS -> MediaFileFilter.VIDEO
            MediaListFilter.ALL -> MediaFileFilter.ALL
        }
        val ready = withTimeoutOrNull(120_000L) {
            pullMediaList(mediaManager, sdkFilter)
        } ?: false
        if (!ready) {
            return MediaListResult.Failure(
                PhotoErrorCodes.PHOTO_TIMEOUT,
                "Timed out refreshing media list",
            )
        }
        delay(300)
        val raw = try {
            mediaManager.mediaFileListData?.data ?: emptyList()
        } catch (t: Throwable) {
            logE("mediaFileListData read failed: ${t.message}")
            emptyList()
        }
        mediaFileByIndex.clear()
        val entries = raw.mapNotNull { file ->
            val type = file.toAssetType() ?: return@mapNotNull null
            if (filter == MediaListFilter.PHOTOS && type != MediaAssetType.PHOTO) return@mapNotNull null
            if (filter == MediaListFilter.VIDEOS && type != MediaAssetType.VIDEO) return@mapNotNull null
            mediaFileByIndex[file.fileIndex] = file
            MediaLibraryEntry(
                fileIndex = file.fileIndex,
                fileName = file.fileName ?: "DJI_${file.fileIndex}",
                type = type,
                sizeBytes = file.fileSize,
                createdAtLabel = file.date?.toString(),
            )
        }.sortedByDescending { it.fileIndex }
        return MediaListResult.Success(entries)
    }

    override suspend fun downloadOriginal(entry: MediaLibraryEntry, timeoutMs: Long): MediaDownloadResult =
        withContext(Dispatchers.IO) {
            try {
                downloadOriginalInternal(entry, timeoutMs)
            } catch (t: Throwable) {
                logE("downloadOriginal crashed: ${t.message}")
                MediaDownloadResult.Failure(
                    PhotoErrorCodes.MEDIA_PULL_FAILED,
                    "Download failed: ${t.message ?: t.javaClass.simpleName}",
                )
            }
        }

    private suspend fun downloadOriginalInternal(entry: MediaLibraryEntry, timeoutMs: Long): MediaDownloadResult {
            val mediaFile = resolveMediaFile(entry)
                ?: return MediaDownloadResult.Failure(
                    PhotoErrorCodes.MEDIA_PULL_FAILED,
                    "Media file not found on aircraft (index ${entry.fileIndex})",
                )
            return downloadMediaFileToCache(mediaFile, entry.type, timeoutMs)
        }

    override suspend fun pullLatestVideo(timeoutMs: Long): MediaDownloadResult = withContext(Dispatchers.IO) {
        try {
        when (val list = refreshList(MediaListFilter.VIDEOS)) {
            is MediaListResult.Failure -> MediaDownloadResult.Failure(list.code, list.message)
            is MediaListResult.Success -> {
                val latest = list.entries.firstOrNull()
                    ?: return@withContext MediaDownloadResult.Failure(
                        PhotoErrorCodes.MEDIA_PULL_FAILED,
                        "No video media file found on aircraft",
                    )
                downloadOriginal(latest, timeoutMs)
            }
        }
        } catch (t: Throwable) {
            logE("pullLatestVideo crashed: ${t.message}")
            MediaDownloadResult.Failure(
                PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Video pull failed: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    override suspend fun deleteEntries(entries: List<MediaLibraryEntry>): MediaDeleteResult =
        withContext(Dispatchers.IO) {
            try {
            if (entries.isEmpty()) return@withContext MediaDeleteResult.Success(0)
            val mediaManager = MediaDataCenter.getInstance().mediaManager
            val files = entries.mapNotNull { resolveMediaFile(it) }
            if (files.isEmpty()) {
                return@withContext MediaDeleteResult.Failure(
                    PhotoErrorCodes.MEDIA_PULL_FAILED,
                    "No matching aircraft files to delete",
                )
            }
            val ok = suspendCoroutine { cont ->
                mediaManager.deleteMediaFiles(files, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() = cont.resume(true)
                    override fun onFailure(error: IDJIError) {
                        logE("deleteMediaFiles failed: ${error.description()}")
                        cont.resume(false)
                    }
                })
            }
            if (ok) {
                entries.forEach { mediaFileByIndex.remove(it.fileIndex) }
                MediaDeleteResult.Success(files.size)
            } else {
                MediaDeleteResult.Failure(
                    PhotoErrorCodes.MEDIA_PULL_FAILED,
                    "Delete failed on aircraft",
                )
            }
            } catch (t: Throwable) {
                logE("deleteEntries crashed: ${t.message}")
                MediaDeleteResult.Failure(
                    PhotoErrorCodes.MEDIA_PULL_FAILED,
                    "Delete failed: ${t.message ?: t.javaClass.simpleName}",
                )
            }
        }

    override suspend fun saveToAppStorage(
        sourcePath: String,
        type: MediaAssetType,
        fileIndex: Int?,
        fileName: String?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val sub = when (type) {
                MediaAssetType.PHOTO -> "photos"
                MediaAssetType.VIDEO -> "videos"
            }
            val dir = File(appContext.getExternalFilesDir(null), sub).apply { mkdirs() }
            val safeName = (fileName ?: "DJI_${fileIndex ?: System.currentTimeMillis()}")
                .replace(Regex("""[^\w.\-]"""), "_")
            val dest = File(dir, safeName)
            File(sourcePath).copyTo(dest, overwrite = true)
            logI("Saved $type to ${dest.absolutePath}")
            dest.absolutePath
        } catch (e: Exception) {
            logE("saveToAppStorage failed: ${e.message}")
            null
        }
    }

    suspend fun pullLatestJpeg(timeoutMs: Long = 120_000L): MediaDownloadResult = withContext(Dispatchers.IO) {
        when (val list = refreshList(MediaListFilter.PHOTOS)) {
            is MediaListResult.Failure -> MediaDownloadResult.Failure(list.code, list.message)
            is MediaListResult.Success -> {
                val latest = list.entries.firstOrNull()
                    ?: return@withContext MediaDownloadResult.Failure(
                        PhotoErrorCodes.MEDIA_PULL_FAILED,
                        "No JPEG media file found on aircraft",
                    )
                downloadOriginal(latest, timeoutMs)
            }
        }
    }

    private suspend fun resolveMediaFile(entry: MediaLibraryEntry): MediaFile? {
        mediaFileByIndex[entry.fileIndex]?.let { return it }
        refreshList(MediaListFilter.ALL)
        return mediaFileByIndex[entry.fileIndex]
    }

    private suspend fun enableMediaManager(
        mediaManager: dji.v5.manager.interfaces.IMediaManager,
    ): Boolean = suspendCoroutine { cont ->
        mediaManager.enable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = cont.resume(true)
            override fun onFailure(error: IDJIError) = cont.resume(false)
        })
    }

    private suspend fun pullMediaList(
        mediaManager: dji.v5.manager.interfaces.IMediaManager,
        filter: MediaFileFilter,
    ): Boolean = suspendCoroutine { cont ->
        var resumed = false
        val listener = object : MediaFileListStateListener {
            override fun onUpdate(state: MediaFileListState) {
                if (state == MediaFileListState.UP_TO_DATE && !resumed) {
                    resumed = true
                    mediaManager.removeMediaFileListStateListener(this)
                    cont.resume(true)
                }
            }
        }
        mediaManager.addMediaFileListStateListener(listener)
        val param = PullMediaFileListParam.Builder()
            .filter(filter)
            .count(200)
            .orderType(FileListRequestTimeOrderType.NEW_FIRST)
            .build()
        mediaManager.pullMediaFileListFromCamera(param, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (mediaManager.mediaFileListState == MediaFileListState.UP_TO_DATE && !resumed) {
                    resumed = true
                    mediaManager.removeMediaFileListStateListener(listener)
                    cont.resume(true)
                }
            }

            override fun onFailure(error: IDJIError) {
                if (!resumed) {
                    resumed = true
                    mediaManager.removeMediaFileListStateListener(listener)
                    cont.resume(false)
                }
            }
        })
    }

    private suspend fun downloadMediaFileToCache(
        mediaFile: MediaFile,
        type: MediaAssetType,
        timeoutMs: Long,
    ): MediaDownloadResult = withContext(Dispatchers.IO) {
        val cacheDir = File(appContext.cacheDir, "media_pull").apply { mkdirs() }
        val ext = when (type) {
            MediaAssetType.PHOTO -> "jpg"
            MediaAssetType.VIDEO -> "mp4"
        }
        val outFile = File(cacheDir, "DJI_${mediaFile.fileIndex}_${System.currentTimeMillis()}.$ext")
        val done = withTimeoutOrNull(timeoutMs) {
            suspendCoroutine { cont ->
                mediaFile.pullOriginalMediaFileFromCamera(
                    0L,
                    object : MediaFileDownloadListener {
                        override fun onStart() {
                            outFile.delete()
                        }

                        override fun onProgress(total: Long, current: Long) {}

                        override fun onRealtimeDataUpdate(data: ByteArray?, position: Long) {
                            if (data != null && data.isNotEmpty()) {
                                FileOutputStream(outFile, true).use { it.write(data) }
                            }
                        }

                        override fun onFinish() = cont.resume(true)

                        override fun onFailure(error: IDJIError?) {
                            logE("Download failed: ${error?.description()}")
                            cont.resume(false)
                        }
                    },
                )
            }
        } ?: false
        if (!done || !outFile.isFile || outFile.length() == 0L) {
            outFile.delete()
            MediaDownloadResult.Failure(
                PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Failed to download media from aircraft",
            )
        } else {
            val contentType = when (type) {
                MediaAssetType.PHOTO -> "image/jpeg"
                MediaAssetType.VIDEO -> "video/mp4"
            }
            MediaDownloadResult.Success(
                localPath = outFile.absolutePath,
                fileIndex = mediaFile.fileIndex,
                fileName = mediaFile.fileName ?: outFile.name,
                sizeBytes = outFile.length(),
                contentType = contentType,
            )
        }
    }

    private fun MediaFile.toAssetType(): MediaAssetType? {
        return when (fileType) {
            MediaFileType.JPEG -> MediaAssetType.PHOTO
            MediaFileType.MOV, MediaFileType.MP4 -> MediaAssetType.VIDEO
            else -> {
                val name = fileName?.lowercase(Locale.US) ?: return null
                when {
                    name.endsWith(".jpg") || name.endsWith(".jpeg") -> MediaAssetType.PHOTO
                    name.endsWith(".mp4") || name.endsWith(".mov") -> MediaAssetType.VIDEO
                    else -> null
                }
            }
        }
    }
}
