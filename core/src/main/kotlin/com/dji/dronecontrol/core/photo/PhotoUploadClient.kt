package com.dji.dronecontrol.core.photo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

const val MAX_PHOTO_UPLOAD_BYTES = 16 * 1024 * 1024

sealed class PhotoUploadResult {
    data class Success(
        val id: String,
        val downloadUrl: String,
        val expiresAt: String?,
        val bytes: Long,
    ) : PhotoUploadResult()

    data class Failure(val code: String, val message: String) : PhotoUploadResult()
}

@Serializable
data class PhotoUploadResponseBody(
    val id: String = "",
    @SerialName("download_url")
    val downloadUrl: String = "",
    @SerialName("expires_at")
    val expiresAt: String? = null,
    val bytes: Long = 0,
)

interface PhotoUploadClient {
    suspend fun uploadJpeg(
        uploadUrl: String,
        bearerSecret: String,
        jpegBytes: ByteArray,
        aircraftId: String,
        msgId: String,
        fileIndex: Int?,
    ): PhotoUploadResult
}

class HttpPhotoUploadClient(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
) : PhotoUploadClient {

    override suspend fun uploadJpeg(
        uploadUrl: String,
        bearerSecret: String,
        jpegBytes: ByteArray,
        aircraftId: String,
        msgId: String,
        fileIndex: Int?,
    ): PhotoUploadResult = withContext(Dispatchers.IO) {
        val url = uploadUrl.trim()
        if (url.isBlank()) {
            return@withContext PhotoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_NOT_CONFIGURED,
                "Photo upload URL is not configured",
            )
        }
        if (bearerSecret.isBlank()) {
            return@withContext PhotoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_NOT_CONFIGURED,
                "Stream token secret is not configured for photo upload auth",
            )
        }
        if (jpegBytes.size > MAX_PHOTO_UPLOAD_BYTES) {
            return@withContext PhotoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_TOO_LARGE,
                "JPEG exceeds 16 MiB upload limit (${jpegBytes.size} bytes)",
            )
        }
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "image/jpeg")
                setRequestProperty("Authorization", "Bearer $bearerSecret")
                if (aircraftId.isNotBlank()) setRequestProperty("X-Aircraft-Id", aircraftId)
                if (msgId.isNotBlank()) setRequestProperty("X-Msg-Id", msgId)
                fileIndex?.let { setRequestProperty("X-File-Index", it.toString()) }
            }
            connection.outputStream.use { it.write(jpegBytes) }
            val status = connection.responseCode
            val responseText = when {
                status in 200..299 -> connection.inputStream.bufferedReader().use(BufferedReader::readText)
                else -> connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            }
            when (status) {
                401 -> PhotoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_UNAUTHORIZED,
                    "Photo upload returned 401 Unauthorized",
                )
                413 -> PhotoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_TOO_LARGE,
                    "Photo upload returned 413 Payload Too Large",
                )
                200 -> parseSuccess(responseText)
                else -> PhotoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_FAILED,
                    "Photo upload HTTP $status",
                )
            }
        } catch (e: Exception) {
            PhotoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_FAILED,
                "Photo upload request failed: ${e.message}",
            )
        }
    }

    internal fun parseSuccess(responseText: String): PhotoUploadResult {
        return try {
            val parsed = json.decodeFromString<PhotoUploadResponseBody>(responseText)
            if (parsed.downloadUrl.isBlank()) {
                PhotoUploadResult.Failure(
                    PhotoErrorCodes.UPLOAD_FAILED,
                    "Photo upload returned empty download_url",
                )
            } else {
                PhotoUploadResult.Success(
                    id = parsed.id,
                    downloadUrl = parsed.downloadUrl,
                    expiresAt = parsed.expiresAt,
                    bytes = parsed.bytes,
                )
            }
        } catch (e: Exception) {
            PhotoUploadResult.Failure(
                PhotoErrorCodes.UPLOAD_FAILED,
                "Invalid photo upload JSON: ${e.message}",
            )
        }
    }

    companion object {
        const val DEFAULT_PHOTO_UPLOAD_URL = "https://tronico.kujuhk.com/live/photo"
    }
}
