package com.dji.dronecontrol.core.port

enum class MediaAssetType {
    PHOTO,
    VIDEO,
}

enum class MediaListFilter {
    ALL,
    PHOTOS,
    VIDEOS,
}

data class MediaLibraryEntry(
    val fileIndex: Int,
    val fileName: String,
    val type: MediaAssetType,
    val sizeBytes: Long,
    val createdAtLabel: String? = null,
)

sealed class MediaListResult {
    data class Success(val entries: List<MediaLibraryEntry>) : MediaListResult()
    data class Failure(val code: String, val message: String) : MediaListResult()
}

sealed class MediaDownloadResult {
    data class Success(
        val localPath: String,
        val fileIndex: Int,
        val fileName: String,
        val sizeBytes: Long,
        val contentType: String,
    ) : MediaDownloadResult()

    data class Failure(val code: String, val message: String) : MediaDownloadResult()
}

sealed class MediaDeleteResult {
    data class Success(val deletedCount: Int) : MediaDeleteResult()
    data class PartialFailure(
        val deletedCount: Int,
        val failures: List<Pair<MediaLibraryEntry, String>>,
    ) : MediaDeleteResult()

    data class Failure(val code: String, val message: String) : MediaDeleteResult()
}

interface MediaLibraryPort {
    suspend fun refreshList(filter: MediaListFilter = MediaListFilter.ALL): MediaListResult

    suspend fun downloadOriginal(
        entry: MediaLibraryEntry,
        timeoutMs: Long = 300_000L,
    ): MediaDownloadResult

    suspend fun deleteEntries(entries: List<MediaLibraryEntry>): MediaDeleteResult

    /** Newest video on aircraft SD (after record stop). */
    suspend fun pullLatestVideo(timeoutMs: Long = 300_000L): MediaDownloadResult

    suspend fun saveToAppStorage(
        sourcePath: String,
        type: MediaAssetType,
        fileIndex: Int?,
        fileName: String?,
    ): String?
}

class NoOpMediaLibraryPort : MediaLibraryPort {
    override suspend fun refreshList(filter: MediaListFilter): MediaListResult =
        MediaListResult.Failure(
            com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
            "Media library not configured",
        )

    override suspend fun downloadOriginal(entry: MediaLibraryEntry, timeoutMs: Long): MediaDownloadResult =
        MediaDownloadResult.Failure(
            com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
            "Media library not configured",
        )

    override suspend fun deleteEntries(entries: List<MediaLibraryEntry>): MediaDeleteResult =
        MediaDeleteResult.Failure(
            com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
            "Media library not configured",
        )

    override suspend fun pullLatestVideo(timeoutMs: Long): MediaDownloadResult =
        MediaDownloadResult.Failure(
            com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
            "Media library not configured",
        )

    override suspend fun saveToAppStorage(
        sourcePath: String,
        type: MediaAssetType,
        fileIndex: Int?,
        fileName: String?,
    ): String? = null
}
