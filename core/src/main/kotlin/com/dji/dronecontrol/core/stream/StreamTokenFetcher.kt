package com.dji.dronecontrol.core.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

sealed class StreamTokenFetchResult {
    data class Success(val publisherToken: StreamPublisherToken) : StreamTokenFetchResult()
    data class Failure(val code: String, val message: String) : StreamTokenFetchResult()
}

interface StreamTokenFetcher {
    suspend fun fetchPublisherToken(
        tokenUrl: String,
        bearerSecret: String,
        channel: String,
        uid: Int,
    ): StreamTokenFetchResult

    suspend fun fetchAudienceToken(
        tokenUrl: String,
        bearerSecret: String,
        channel: String,
    ): StreamTokenFetchResult
}

class HttpStreamTokenFetcher(
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
) : StreamTokenFetcher {

    override suspend fun fetchPublisherToken(
        tokenUrl: String,
        bearerSecret: String,
        channel: String,
        uid: Int,
    ): StreamTokenFetchResult = fetchRoleToken(tokenUrl, bearerSecret, channel, "publisher", uid)

    override suspend fun fetchAudienceToken(
        tokenUrl: String,
        bearerSecret: String,
        channel: String,
    ): StreamTokenFetchResult = fetchRoleToken(tokenUrl, bearerSecret, channel, "audience", AUDIENCE_UID)

    private suspend fun fetchRoleToken(
        tokenUrl: String,
        bearerSecret: String,
        channel: String,
        role: String,
        uid: Int,
    ): StreamTokenFetchResult = withContext(Dispatchers.IO) {
        if (bearerSecret.isBlank()) {
            return@withContext StreamTokenFetchResult.Failure(
                StreamErrorCodes.TOKEN_ENDPOINT_NOT_CONFIGURED,
                "Stream token endpoint secret is not configured",
            )
        }
        val url = tokenUrl.trim().ifBlank { DEFAULT_TOKEN_URL }
        try {
            val body = when (role) {
                "publisher" -> encodePublisherRequestBody(channel, uid)
                else -> encodeRoleRequestBody(channel, role, uid)
            }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $bearerSecret")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val responseText = when {
                status in 200..299 -> connection.inputStream.bufferedReader().use(BufferedReader::readText)
                else -> connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            }
            when (status) {
                401 -> StreamTokenFetchResult.Failure(
                    StreamErrorCodes.TOKEN_ENDPOINT_UNAUTHORIZED,
                    "Token endpoint returned 401 Unauthorized",
                )
                200 -> parseSuccess(responseText, channel, uid)
                else -> StreamTokenFetchResult.Failure(
                    StreamErrorCodes.TOKEN_ENDPOINT_FAILED,
                    "Token endpoint HTTP $status",
                )
            }
        } catch (e: Exception) {
            StreamTokenFetchResult.Failure(
                StreamErrorCodes.TOKEN_ENDPOINT_FAILED,
                "Token endpoint request failed: ${e.message}",
            )
        }
    }

    private fun parseSuccess(responseText: String, requestedChannel: String, requestedUid: Int): StreamTokenFetchResult {
        return try {
            val parsed = json.decodeFromString<StreamTokenResponseBody>(responseText)
            if (parsed.token.isBlank()) {
                return StreamTokenFetchResult.Failure(
                    StreamErrorCodes.TOKEN_ENDPOINT_FAILED,
                    "Token endpoint returned empty token",
                )
            }
            if (parsed.channel != requestedChannel) {
                return StreamTokenFetchResult.Failure(
                    StreamErrorCodes.TOKEN_ENDPOINT_FAILED,
                    "Token endpoint channel mismatch (expected $requestedChannel, got ${parsed.channel})",
                )
            }
            StreamTokenFetchResult.Success(
                StreamPublisherToken(
                    token = parsed.token,
                    channel = parsed.channel,
                    uid = parsed.uid,
                    expiresAt = parsed.expiresAt,
                )
            )
        } catch (e: Exception) {
            StreamTokenFetchResult.Failure(
                StreamErrorCodes.TOKEN_ENDPOINT_FAILED,
                "Invalid token endpoint JSON: ${e.message}",
            )
        }
    }

    internal fun parseSuccessResponse(
        responseText: String,
        requestedChannel: String,
        requestedUid: Int,
    ): StreamTokenFetchResult = parseSuccess(responseText, requestedChannel, requestedUid)

    companion object {
        const val DEFAULT_TOKEN_URL = "https://tronico.kujuhk.com/live/token"
        const val AUDIENCE_UID = 0

        private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        internal fun encodePublisherRequestBody(channel: String, uid: Int): String =
            requestJson.encodeToString(
                StreamTokenRequestBody(channel = channel, role = "publisher", uid = uid),
            )

        internal fun encodeRoleRequestBody(channel: String, role: String, uid: Int): String =
            requestJson.encodeToString(
                StreamTokenRequestBody(channel = channel, role = role, uid = uid),
            )
    }
}
