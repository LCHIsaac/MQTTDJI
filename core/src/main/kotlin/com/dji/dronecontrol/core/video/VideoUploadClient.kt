package com.dji.dronecontrol.core.video

import com.dji.dronecontrol.core.photo.PhotoErrorCodes
import com.dji.dronecontrol.core.photo.PhotoUploadResponseBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

const val MAX_VIDEO_UPLOAD_BYTES = 512L * 1024 * 1024

sealed class VideoUploadResult {
    data class Success(
        val id: String,
        val downloadUrl: String,
        val expiresAt: String?,
        val bytes: Long,
    ) : VideoUploadResult()

    data class Failure(val code: String, val message: String) : VideoUploadResult()
}

interface VideoUploadClient {
    suspend fun uploadMp4File(
        uploadUrl: String,
        bearerSecret: String,
        mp4FilePath: String,
        aircraftId: String,
        msgId: String,
        fileIndex: Int?,
    ): VideoUploadResult
}

class HttpVideoUploadClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
) : VideoUploadClient {

    override suspend fun uploadMp4File(
        uploadUrl: String,
        bearerSecret: String,
        mp4FilePath: String,
        aircraftId: String,
        msgId: String,
        fileIndex: Int?,
    ): VideoUploadResult = withContext(Dispatchers.IO) {
        val url = uploadUrl.trim()
        if (url.isBlank()) {
            return@withContext VideoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_NOT_CONFIGURED,
                "Video upload URL is not configured",
            )
        }
        if (bearerSecret.isBlank()) {
            return@withContext VideoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_NOT_CONFIGURED,
                "Stream token secret is not configured for video upload auth",
            )
        }
        val file = File(mp4FilePath)
        if (!file.isFile) {
            return@withContext VideoUploadResult.Failure(
                PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Video file not found for upload",
            )
        }
        val size = file.length()
        if (size > MAX_VIDEO_UPLOAD_BYTES) {
            return@withContext VideoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_TOO_LARGE,
                "Video exceeds 512 MiB upload limit ($size bytes)",
            )
        }
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setFixedLengthStreamingMode(size)
                setRequestProperty("Content-Type", "video/mp4")
                setRequestProperty("Authorization", "Bearer $bearerSecret")
                if (aircraftId.isNotBlank()) setRequestProperty("X-Aircraft-Id", aircraftId)
                if (msgId.isNotBlank()) setRequestProperty("X-Msg-Id", msgId)
                fileIndex?.let { setRequestProperty("X-File-Index", it.toString()) }
            }
            connection.outputStream.use { out ->
                FileInputStream(file).use { input ->
                    input.copyTo(out)
                }
            }
            val status = connection.responseCode
            val responseText = when {
                status in 200..299 -> connection.inputStream.bufferedReader().use(BufferedReader::readText)
                else -> connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            }
            when (status) {
                401 -> VideoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_UNAUTHORIZED,
                    "Video upload returned 401 Unauthorized",
                )
                413 -> VideoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_TOO_LARGE,
                    "Video upload returned 413 Payload Too Large",
                )
                200 -> parseSuccess(responseText)
                else -> VideoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_FAILED,
                    "Video upload HTTP $status",
                )
            }
        } catch (e: Exception) {
            VideoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_FAILED,
                "Video upload request failed: ${e.message}",
            )
        }
    }

    internal fun parseSuccess(responseText: String): VideoUploadResult {
        return try {
            val parsed = json.decodeFromString<PhotoUploadResponseBody>(responseText)
            if (parsed.downloadUrl.isBlank()) {
                VideoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_FAILED,
                    "Video upload returned empty download_url",
                )
            } else {
                VideoUploadResult.Success(
                    id = parsed.id,
                    downloadUrl = parsed.downloadUrl,
                    expiresAt = parsed.expiresAt,
                    bytes = parsed.bytes,
                )
            }
        } catch (e: Exception) {
            VideoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_FAILED,
                "Invalid video upload JSON: ${e.message}",
            )
        }
    }

    companion object {
        const val DEFAULT_VIDEO_UPLOAD_URL = "https://tronico.kujuhk.com/live/video"
    }
}
