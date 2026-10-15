package com.dji.dronecontrol.adapter.dji

import android.util.Log
import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.model.StreamQuality
import com.dji.dronecontrol.core.model.StreamSessionState
import com.dji.dronecontrol.core.model.StreamStartParams
import com.dji.dronecontrol.core.port.StreamPort
import com.dji.dronecontrol.core.port.StreamResult
import com.dji.dronecontrol.core.port.StreamStartContext
import com.dji.dronecontrol.core.stream.StreamErrorCodes
import com.dji.dronecontrol.core.stream.StreamViewerUrl
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.datacenter.livestream.LiveStreamManager
import dji.v5.manager.interfaces.ILiveStreamManager
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.settings.AgoraSettings
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DjiStreamAdapter(
    private val agoraAppIdProvider: () -> String,
) : StreamPort {

    private val tag = "DjiStreamAdapter"

    private fun logI(msg: String) {
        Log.i(tag, msg)
        AppLogger.i(tag, msg)
    }

    private fun logW(msg: String) {
        Log.w(tag, msg)
        AppLogger.w(tag, msg)
    }

    private fun logE(msg: String) {
        Log.e(tag, msg)
        AppLogger.e(tag, msg)
    }

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentStreamUrl = MutableStateFlow<String?>(null)
    override val currentStreamUrl: StateFlow<String?> = _currentStreamUrl.asStateFlow()

    private val _sessionState = MutableStateFlow(StreamSessionState())
    override val sessionState: StateFlow<StreamSessionState> = _sessionState.asStateFlow()

    private val _streamEvents = MutableSharedFlow<StreamSessionState>(extraBufferCapacity = 8)
    override val streamEvents: SharedFlow<StreamSessionState> = _streamEvents.asSharedFlow()

    private var statusListener: LiveStreamStatusListener? = null
    private var activeChannel: String? = null
    private var activeViewerUrl: String? = null
    private var activeProtocol: String? = null

    private fun utcNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun updateSession(state: StreamSessionState) {
        _sessionState.value = state
        _isStreaming.value = state.state == "live" || state.state == "starting"
        _streamEvents.tryEmit(state)
    }

    private fun ensureStatusListener(manager: ILiveStreamManager) {
        if (statusListener != null) return
        statusListener = object : LiveStreamStatusListener {
            override fun onLiveStreamStatusUpdate(status: LiveStreamStatus) {
                if (status.isStreaming) {
                    _isStreaming.value = true
                }
            }

            override fun onError(error: IDJIError) {
                val code = mapErrorCode(error.description(), error.errorCode())
                logE("LiveStream error: code=$code sdk=${error.errorCode()} desc=${error.description()}")
                val errState = StreamSessionState(
                    state = "error",
                    protocol = activeProtocol,
                    channel = activeChannel,
                    viewerUrl = activeViewerUrl,
                    errorCode = code,
                    errorMessage = error.description(),
                )
                updateSession(errState)
                _isStreaming.value = false
                _currentStreamUrl.value = null
            }
        }
        manager.addLiveStreamStatusListener(statusListener!!)
    }

    override suspend fun startStream(params: StreamStartParams, context: StreamStartContext): StreamResult =
        withContext(Dispatchers.IO) {
            val protocol = params.protocol.lowercase(Locale.US)
            if (isStreaming.value) {
                logI("Stopping existing stream before starting new session")
                stopInternal()
            }

            activeProtocol = protocol
            return@withContext when (protocol) {
                "rtmp" -> startRtmpInternal(params, context)
                "agora" -> startAgoraInternal(params, context)
                else -> StreamResult.Failure(
                    StreamErrorCodes.INVALID_PARAMS,
                    "Unsupported protocol: ${params.protocol}"
                )
            }
        }

    private suspend fun startRtmpInternal(params: StreamStartParams, context: StreamStartContext): StreamResult {
        if (params.url.isBlank()) {
            return StreamResult.Failure(StreamErrorCodes.INVALID_PARAMS, "Missing stream URL parameter")
        }
        if (!context.isNetworkAvailable()) {
            return StreamResult.Failure(StreamErrorCodes.NO_UPLINK, "No network uplink")
        }
        if (!context.isCameraReady()) {
            return StreamResult.Failure(StreamErrorCodes.CAMERA_NOT_READY, "Camera / aircraft not ready")
        }

        updateSession(
            StreamSessionState(state = "starting", protocol = "rtmp", rtmpUrl = params.url)
        )

        return try {
            val manager = LiveStreamManager.getInstance()
            ensureStatusListener(manager)
            manager.setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)

            val rtmpSettings = RtmpSettings.Builder().setUrl(params.url).build()
            manager.liveStreamSettings = LiveStreamSettings.Builder()
                .setLiveStreamType(LiveStreamType.RTMP)
                .setRtmpSettings(rtmpSettings)
                .build()
            applyQuality(manager, params.quality)

            val startOk = suspendCoroutine { cont ->
                manager.startStream(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        cont.resume(true)
                    }

                    override fun onFailure(error: IDJIError) {
                        logE("RTMP startStream failed: ${error.errorCode()} ${error.description()}")
                        cont.resume(false)
                    }
                })
            }
            if (!startOk) {
                return StreamResult.Failure(StreamErrorCodes.STREAM_ERROR, "Failed to start RTMP stream")
            }

            val started = waitUntilStreaming(manager, timeoutMs = 15_000L)
            if (!started) {
                updateSession(StreamSessionState(state = "error", protocol = "rtmp", errorCode = StreamErrorCodes.STREAM_TIMEOUT))
                return StreamResult.Failure(StreamErrorCodes.STREAM_TIMEOUT, "Timed out waiting for RTMP stream to go live")
            }

            val startedAt = utcNow()
            _currentStreamUrl.value = params.url
            updateSession(
                StreamSessionState(
                    state = "live",
                    protocol = "rtmp",
                    rtmpUrl = params.url,
                    startedAt = startedAt,
                )
            )
            StreamResult.Success(protocol = "rtmp", url = params.url, startedAt = startedAt)
        } catch (t: Throwable) {
            val code = StreamErrorCodes.STREAM_ERROR
            updateSession(StreamSessionState(state = "error", protocol = "rtmp", errorCode = code, errorMessage = t.message))
            StreamResult.Failure(code, t.message ?: "RTMP start failed", t)
        }
    }

    private suspend fun startAgoraInternal(params: StreamStartParams, context: StreamStartContext): StreamResult {
        val channel = params.channel.trim()
        val token = params.token.trim()
        if (channel.isBlank() || token.isBlank()) {
            return StreamResult.Failure(StreamErrorCodes.INVALID_PARAMS, "Missing channel or token")
        }
        val appId = params.appId?.trim()?.takeIf { it.isNotEmpty() }
            ?: agoraAppIdProvider().trim()
        if (appId.isBlank() || appId == "YOUR_AGORA_APP_ID_HERE") {
            return StreamResult.Failure(StreamErrorCodes.INVALID_PARAMS, "Missing Agora app id (BuildConfig or params.app_id)")
        }
        if (!context.isNetworkAvailable()) {
            return StreamResult.Failure(StreamErrorCodes.NO_UPLINK, "No network / video uplink")
        }
        if (!context.isCameraReady()) {
            return StreamResult.Failure(StreamErrorCodes.CAMERA_NOT_READY, "Camera / aircraft not ready for live stream")
        }

        val viewerUrl = context.agoraWatchableViewerUrl
            ?: StreamViewerUrl.build(context.viewerBaseUrl, channel)
        activeChannel = channel
        activeViewerUrl = viewerUrl

        updateSession(
            StreamSessionState(
                state = "starting",
                protocol = "agora",
                channel = channel,
                viewerUrl = viewerUrl,
            )
        )

        logI("Starting Agora live stream channel=$channel uid=${params.uid} (token redacted)")

        return try {
            val manager = LiveStreamManager.getInstance()
            ensureStatusListener(manager)
            manager.setCameraIndex(ComponentIndexType.LEFT_OR_MAIN)

            val agoraSettings = AgoraSettings.Builder()
                .setChannelId(channel)
                .setUid(params.uid.toString())
                .setToken(token)
                .setEnableSafety(true)
                .build()

            manager.liveStreamSettings = LiveStreamSettings.Builder()
                .setLiveStreamType(LiveStreamType.AGORA)
                .setAgoraSettings(agoraSettings)
                .build()
            applyQuality(manager, params.quality)

            val startOk = suspendCoroutine { cont ->
                manager.startStream(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        cont.resume(true)
                    }

                    override fun onFailure(error: IDJIError) {
                        val code = mapErrorCode(error.description(), error.errorCode())
                        logE("Agora startStream failed: $code sdk=${error.errorCode()}")
                        cont.resume(false)
                    }
                })
            }

            if (!startOk) {
                val code = StreamErrorCodes.STREAM_ERROR
                updateSession(
                    StreamSessionState(
                        state = "error",
                        protocol = "agora",
                        channel = channel,
                        viewerUrl = viewerUrl,
                        errorCode = code,
                    )
                )
                return StreamResult.Failure(code, "Failed to start Agora stream")
            }

            val live = waitUntilStreaming(manager, timeoutMs = 15_000L)
            if (!live) {
                stopInternal()
                updateSession(
                    StreamSessionState(
                        state = "error",
                        protocol = "agora",
                        channel = channel,
                        viewerUrl = viewerUrl,
                        errorCode = StreamErrorCodes.STREAM_TIMEOUT,
                    )
                )
                return StreamResult.Failure(StreamErrorCodes.STREAM_TIMEOUT, "Timed out waiting for Agora stream to go live")
            }

            val startedAt = utcNow()
            _currentStreamUrl.value = viewerUrl
            updateSession(
                StreamSessionState(
                    state = "live",
                    protocol = "agora",
                    channel = channel,
                    viewerUrl = viewerUrl,
                    startedAt = startedAt,
                )
            )
            StreamResult.Success(
                protocol = "agora",
                channel = channel,
                viewerUrl = viewerUrl,
                startedAt = startedAt,
            )
        } catch (t: Throwable) {
            val code = StreamErrorCodes.STREAM_ERROR
            updateSession(
                StreamSessionState(
                    state = "error",
                    protocol = "agora",
                    channel = channel,
                    viewerUrl = viewerUrl,
                    errorCode = code,
                    errorMessage = t.message,
                )
            )
            StreamResult.Failure(code, t.message ?: "Agora start exception", t)
        }
    }

    private suspend fun waitUntilStreaming(manager: ILiveStreamManager, timeoutMs: Long): Boolean {
        if (manager.isStreaming) return true
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (manager.isStreaming) return@withTimeoutOrNull true
                kotlinx.coroutines.delay(200)
            }
            false
        } ?: false
    }

    private fun applyQuality(manager: ILiveStreamManager, quality: StreamQuality) {
        val djiQuality = when (quality) {
            StreamQuality.SD -> dji.v5.manager.datacenter.livestream.StreamQuality.SD
            StreamQuality.HD -> dji.v5.manager.datacenter.livestream.StreamQuality.HD
            StreamQuality.FULL_HD -> dji.v5.manager.datacenter.livestream.StreamQuality.FULL_HD
            StreamQuality.AUTO -> dji.v5.manager.datacenter.livestream.StreamQuality.HD
        }
        manager.liveStreamQuality = djiQuality
    }

    private fun mapErrorCode(description: String?, sdkCode: String?): String {
        val text = "${description ?: ""} ${sdkCode ?: ""}".lowercase(Locale.US)
        return when {
            text.contains("token") && (text.contains("expir") || text.contains("invalid")) ->
                StreamErrorCodes.AGORA_TOKEN_EXPIRED
            text.contains("network") || text.contains("uplink") || text.contains("net_err") ->
                StreamErrorCodes.NO_UPLINK
            text.contains("not_ready") || text.contains("camera") ->
                StreamErrorCodes.CAMERA_NOT_READY
            else -> StreamErrorCodes.STREAM_ERROR
        }
    }

    private suspend fun stopInternal() {
        try {
            val manager = LiveStreamManager.getInstance()
            suspendCoroutine { cont ->
                manager.stopStream(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        cont.resume(Unit)
                    }

                    override fun onFailure(error: IDJIError) {
                        cont.resume(Unit)
                    }
                })
            }
        } catch (_: Throwable) {
        }
        _isStreaming.value = false
        _currentStreamUrl.value = null
    }

    override suspend fun stop(): StreamResult = withContext(Dispatchers.IO) {
        logI("Stopping live stream")
        stopInternal()
        val stopped = StreamSessionState(state = "stopped", protocol = activeProtocol, channel = activeChannel, viewerUrl = activeViewerUrl)
        activeChannel = null
        activeViewerUrl = null
        activeProtocol = null
        updateSession(stopped)
        StreamResult.Success(protocol = stopped.protocol ?: "unknown")
    }
}
