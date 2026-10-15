package com.dji.dronecontrol.core.port

import com.dji.dronecontrol.core.model.StreamSessionState
import com.dji.dronecontrol.core.model.StreamStartParams
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

sealed class StreamResult {
    data class Success(
        val protocol: String,
        val url: String? = null,
        val channel: String? = null,
        val viewerUrl: String? = null,
        val startedAt: String? = null,
    ) : StreamResult()

    data class Failure(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
    ) : StreamResult()
}

data class StreamStartContext(
    val defaultAgoraAppId: String,
    val viewerBaseUrl: String,
    /** When set (Agora), used for session/ack viewer link including `#token=` audience credential. */
    val agoraWatchableViewerUrl: String? = null,
    val isNetworkAvailable: () -> Boolean = { true },
    val isCameraReady: () -> Boolean = { true },
)

/**
 * Domain port for live video streaming (RTMP / Agora via DJI LiveStreamManager).
 */
interface StreamPort {
    suspend fun startStream(params: StreamStartParams, context: StreamStartContext): StreamResult
    suspend fun stop(): StreamResult

    val isStreaming: StateFlow<Boolean>
    val currentStreamUrl: StateFlow<String?>
    val sessionState: StateFlow<StreamSessionState>

    /** Mid-stream errors (e.g. token expiry) for MQTT status publishing. */
    val streamEvents: SharedFlow<StreamSessionState>
}
