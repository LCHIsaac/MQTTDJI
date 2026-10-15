package com.dji.dronecontrol.core.router

import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.model.*
import com.dji.dronecontrol.core.port.*
import com.dji.dronecontrol.core.security.SensitiveDataRedactor
import com.dji.dronecontrol.core.photo.HttpPhotoUploadClient
import com.dji.dronecontrol.core.photo.PhotoErrorCodes
import com.dji.dronecontrol.core.photo.PhotoUploadClient
import com.dji.dronecontrol.core.photo.PhotoUploadResult
import com.dji.dronecontrol.core.video.HttpVideoUploadClient
import com.dji.dronecontrol.core.video.VideoUploadClient
import com.dji.dronecontrol.core.video.VideoUploadResult
import com.dji.dronecontrol.core.stream.HttpStreamTokenFetcher
import com.dji.dronecontrol.core.stream.StreamErrorCodes
import com.dji.dronecontrol.core.stream.StreamTokenFetchResult
import com.dji.dronecontrol.core.stream.StreamTokenFetcher
import com.dji.dronecontrol.core.stream.StreamViewerUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.*

class CommandRouter(
    private val flightPort: FlightControllerPort,
    private val cameraPort: CameraPort,
    private val gimbalPort: GimbalPort,
    private val streamPort: StreamPort,
    private val aircraftPort: AircraftConnectionPort,
    private val mqttTransport: MqttTransportPort,
    private val streamTokenFetcher: StreamTokenFetcher = HttpStreamTokenFetcher(),
    private val photoDeliveryPort: PhotoDeliveryPort = NoOpPhotoDeliveryPort(),
    private val snapshotPort: SnapshotPort = NoOpSnapshotPort(),
    private val mediaLibraryPort: MediaLibraryPort = NoOpMediaLibraryPort(),
    private val photoUploadClient: PhotoUploadClient = HttpPhotoUploadClient(),
    private val videoUploadClient: VideoUploadClient = HttpVideoUploadClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
) {
    private val tag = "Router"
    private var brokerConfig: MqttBrokerConfig = MqttBrokerConfig()
    private var defaultAgoraAppId: String = ""
    private var defaultStreamTokenUrl: String = HttpStreamTokenFetcher.DEFAULT_TOKEN_URL
    private var defaultPhotoUploadUrl: String = HttpPhotoUploadClient.DEFAULT_PHOTO_UPLOAD_URL
    private var defaultVideoUploadUrl: String = HttpVideoUploadClient.DEFAULT_VIDEO_UPLOAD_URL
    private var streamTokenSecretProvider: () -> String = { "" }
    private var telemetryJob: Job? = null
    private var streamEventsJob: Job? = null

    private val _commandLogs = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val commandLogs: SharedFlow<String> = _commandLogs.asSharedFlow()

    private val _photoDeliveryEvents = MutableSharedFlow<CameraPhotoDoneMessage>(extraBufferCapacity = 8)
    val photoDeliveryEvents: SharedFlow<CameraPhotoDoneMessage> = _photoDeliveryEvents.asSharedFlow()

    private val _snapshotDeliveryEvents = MutableSharedFlow<CameraSnapshotDoneMessage>(extraBufferCapacity = 8)
    val snapshotDeliveryEvents: SharedFlow<CameraSnapshotDoneMessage> = _snapshotDeliveryEvents.asSharedFlow()

    private val _recordDeliveryEvents = MutableSharedFlow<CameraRecordStatusMessage>(extraBufferCapacity = 8)
    val recordDeliveryEvents: SharedFlow<CameraRecordStatusMessage> = _recordDeliveryEvents.asSharedFlow()

    private var listenJob: Job? = null

    companion object {
        private const val PHOTO_SHOOT_TIMEOUT_MS = 50_000L
        private const val PHOTO_DELIVERY_TIMEOUT_MS = 180_000L
        private const val SNAPSHOT_CAPTURE_TIMEOUT_MS = 10_000L
        private const val SNAPSHOT_DELIVERY_TIMEOUT_MS = 180_000L
    }

    fun updateConfig(config: MqttBrokerConfig) {
        this.brokerConfig = config
    }

    fun updateStreamSecrets(defaultAgoraAppId: String) {
        this.defaultAgoraAppId = defaultAgoraAppId
    }

    fun updateStreamTokenConfig(
        defaultTokenUrl: String,
        secretProvider: () -> String,
    ) {
        defaultStreamTokenUrl = defaultTokenUrl.trim().ifBlank { HttpStreamTokenFetcher.DEFAULT_TOKEN_URL }
        streamTokenSecretProvider = secretProvider
    }

    fun updatePhotoUploadConfig(defaultUploadUrl: String) {
        defaultPhotoUploadUrl = defaultUploadUrl.trim()
            .ifBlank { HttpPhotoUploadClient.DEFAULT_PHOTO_UPLOAD_URL }
    }

    fun updateVideoUploadConfig(defaultUploadUrl: String) {
        defaultVideoUploadUrl = defaultUploadUrl.trim()
            .ifBlank { HttpVideoUploadClient.DEFAULT_VIDEO_UPLOAD_URL }
    }

    private fun resolvedStreamTokenUrl(): String {
        return brokerConfig.streamTokenUrl.trim()
            .ifBlank { defaultStreamTokenUrl }
            .ifBlank { HttpStreamTokenFetcher.DEFAULT_TOKEN_URL }
    }

    private fun resolvedPhotoUploadUrl(): String {
        return brokerConfig.photoUploadUrl.trim()
            .ifBlank { defaultPhotoUploadUrl }
            .ifBlank { HttpPhotoUploadClient.DEFAULT_PHOTO_UPLOAD_URL }
    }

    private fun resolvedVideoUploadUrl(): String {
        return brokerConfig.videoUploadUrl.trim()
            .ifBlank { defaultVideoUploadUrl }
            .ifBlank { HttpVideoUploadClient.DEFAULT_VIDEO_UPLOAD_URL }
    }

    private fun effectivePhotoSize(params: PhotoParams): PhotoSize {
        return when (params.size) {
            PhotoSize.DEFAULT -> PhotoSize.SIZE_12MP
            else -> params.size
        }
    }

    /**
     * Start listening to MQTT incoming commands and periodic telemetry streaming.
     */
    fun startListening(enableTelemetryPeriodic: Boolean = true) {
        listenJob?.cancel()
        listenJob = scope.launch {
            mqttTransport.incomingCommands.collect { rawPayload ->
                handleIncomingMessage(rawPayload)
            }
        }

        streamEventsJob?.cancel()
        streamEventsJob = scope.launch {
            streamPort.streamEvents.collect { event ->
                if (event.state == "error" && event.errorCode != null) {
                    publishStreamStatus(event)
                }
            }
        }

        if (enableTelemetryPeriodic) {
            // Periodic telemetry publisher (every 1 second when connected)
            telemetryJob?.cancel()
            telemetryJob = scope.launch {
                while (isActive) {
                    delay(1000)
                    try {
                        publishAircraftStatus()
                    } catch (e: Exception) {
                        // Ignore transient errors
                    }
                }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        streamEventsJob?.cancel()
        streamEventsJob = null
    }

    /**
     * Parse and dispatch a command string (from MQTT or UI manual smoke test).
     */
    suspend fun handleIncomingMessage(rawJson: String) {
        val safeLog = SensitiveDataRedactor.redactJsonPayload(rawJson)
        log("[RECV] $safeLog")
        AppLogger.i(tag, "[RECV] $safeLog")
        val envelope = try {
            json.decodeFromString<CommandEnvelope>(rawJson)
        } catch (e: Exception) {
            val errMsg = "JSON parse failed: ${e.message}"
            log("[ERROR] $errMsg")
            AppLogger.e(tag, errMsg)
            return
        }

        executeEnvelope(envelope)
    }

    /**
     * Execute a structured command envelope and publish corresponding ack/status.
     */
    suspend fun executeEnvelope(envelope: CommandEnvelope) {
        val msgId = envelope.msgId
        val cmd = envelope.cmd
        val params = envelope.params

        log("[EXEC] cmd=$cmd msg_id=$msgId")
        AppLogger.i(tag, "Executing cmd=$cmd (msg_id=$msgId)")

        when (cmd) {
            "mqtt.ping" -> {
                publishAck(msgId, ok = true)
                publishAircraftStatus()
            }

            "flight.takeoff" -> {
                val takeoffParams = params?.let {
                    try {
                        json.decodeFromJsonElement<TakeoffParams>(it)
                    } catch (e: Exception) {
                        TakeoffParams()
                    }
                } ?: TakeoffParams()

                val result = flightPort.takeoff(takeoffParams)
                when (result) {
                    is FlightResult.Success -> {
                        publishAck(msgId, ok = true)
                        publishAircraftStatus()
                    }
                    is FlightResult.Failure -> {
                        publishAck(msgId, ok = false, error = result.message)
                    }
                }
            }

            "flight.land" -> {
                val result = flightPort.land()
                handleFlightResult(msgId, result)
            }

            "flight.rth", "flight.go_home" -> {
                val result = flightPort.goHome()
                handleFlightResult(msgId, result)
            }

            "flight.hover" -> {
                val result = flightPort.hover()
                handleFlightResult(msgId, result)
            }

            "flight.move" -> {
                val vsCommand = params?.let {
                    try {
                        json.decodeFromJsonElement<VirtualStickCommand>(it)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (vsCommand == null) {
                    publishAck(msgId, ok = false, error = "Invalid flight.move params")
                } else {
                    val result = flightPort.move(vsCommand)
                    handleFlightResult(msgId, result)
                }
            }

            "flight.rotate" -> {
                val rotateParams = params?.let {
                    try {
                        json.decodeFromJsonElement<RotateParams>(it)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (rotateParams == null) {
                    publishAck(msgId, ok = false, error = "Invalid flight.rotate params")
                } else {
                    val result = flightPort.rotate(rotateParams)
                    handleFlightResult(msgId, result)
                }
            }

            "flight.emergency_stop" -> {
                gimbalPort.cancelActiveRotation()
                val result = flightPort.emergencyStop()
                handleFlightResult(msgId, result)
            }

            "gimbal.rotate" -> {
                val rotateParams = params?.let {
                    try {
                        json.decodeFromJsonElement<GimbalRotateParams>(it)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (rotateParams == null) {
                    publishAck(msgId, ok = false, error = "Invalid gimbal.rotate params")
                } else {
                    handleGimbalResult(msgId, gimbalPort.rotate(rotateParams))
                }
            }

            "gimbal.set_angle" -> {
                val angleParams = params?.let {
                    try {
                        json.decodeFromJsonElement<GimbalSetAngleParams>(it)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (angleParams == null) {
                    publishAck(msgId, ok = false, error = "Invalid gimbal.set_angle params")
                } else {
                    handleGimbalResult(msgId, gimbalPort.setAngle(angleParams))
                }
            }

            "gimbal.reset" -> {
                handleGimbalResult(msgId, gimbalPort.reset())
            }

            "camera.photo" -> {
                val photoParams = params?.let {
                    try {
                        json.decodeFromJsonElement<PhotoParams>(it)
                    } catch (e: Exception) {
                        PhotoParams()
                    }
                } ?: PhotoParams()
                val shootParams = photoParams.copy(size = effectivePhotoSize(photoParams))
                scope.launch {
                    handleCameraPhotoCommand(msgId, photoParams, shootParams)
                }
            }

            "camera.snapshot" -> {
                val snapshotParams = params?.let {
                    try {
                        json.decodeFromJsonElement<SnapshotParams>(it)
                    } catch (e: Exception) {
                        SnapshotParams()
                    }
                } ?: SnapshotParams()
                scope.launch {
                    handleCameraSnapshotCommand(msgId, snapshotParams)
                }
            }

            "camera.record_start" -> {
                val result = cameraPort.startRecord()
                when (result) {
                    is CameraResult.RecordStarted -> {
                        publishAck(msgId, ok = true)
                        publishCameraRecordStatus(recording = true)
                    }
                    is CameraResult.Failure -> {
                        publishAck(msgId, ok = false, error = result.message)
                    }
                    else -> publishAck(msgId, ok = false, error = "Failed to start record")
                }
            }

            "camera.record_stop" -> {
                val stopParams = params?.let {
                    try {
                        json.decodeFromJsonElement<RecordStopParams>(it)
                    } catch (e: Exception) {
                        RecordStopParams()
                    }
                } ?: RecordStopParams()

                val result = cameraPort.stopRecord()
                when (result) {
                    is CameraResult.RecordStopped -> {
                        publishAck(msgId, ok = true)
                        publishCameraRecordStatus(recording = false)
                        scope.launch {
                            completeVideoDelivery(msgId, stopParams)
                        }
                    }
                    is CameraResult.Failure -> {
                        publishAck(msgId, ok = false, error = result.message)
                    }
                    else -> publishAck(msgId, ok = false, error = "Failed to stop record")
                }
            }

            "stream.start" -> handleStreamStart(msgId, params)

            "stream.stop" -> {
                val result = streamPort.stop()
                when (result) {
                    is StreamResult.Success -> {
                        publishAck(msgId, ok = true)
                        publishStreamStatus(StreamSessionState(state = "stopped"))
                    }
                    is StreamResult.Failure -> {
                        publishAck(msgId, ok = false, error = result.message, errorCode = result.code)
                    }
                }
            }

            "config.get" -> {
                publishAck(msgId, ok = true)
                val configMsg = ConfigGetResponseMessage(
                    aircraftId = brokerConfig.aircraftId,
                    mqttConnected = mqttTransport.connectionState.value is MqttConnectionState.Connected,
                    aircraftConnected = aircraftPort.isConnected.value,
                    productType = aircraftPort.productType.value,
                    topics = mapOf(
                        "cmd" to brokerConfig.commandTopic,
                        "status" to brokerConfig.statusTopic,
                        "ack" to brokerConfig.ackTopic
                    )
                )
                publishStatus(json.encodeToString(configMsg))
            }

            else -> {
                publishAck(msgId, ok = false, error = "Unknown command: $cmd")
            }
        }
    }

    private suspend fun handleStreamStart(msgId: String, params: kotlinx.serialization.json.JsonElement?) {
        val streamParams = params?.let {
            try {
                json.decodeFromJsonElement<StreamStartParams>(it)
            } catch (e: Exception) {
                null
            }
        }
        if (streamParams == null) {
            publishAck(msgId, ok = false, error = "Invalid stream.start params", errorCode = StreamErrorCodes.INVALID_PARAMS)
            return
        }

        val protocol = streamParams.protocol.lowercase(Locale.US)
        if (protocol == "rtmp" && streamParams.url.isBlank()) {
            publishAck(msgId, ok = false, error = "Missing stream URL parameter", errorCode = StreamErrorCodes.INVALID_PARAMS)
            return
        }
        if (protocol == "agora" && streamParams.channel.isBlank()) {
            publishAck(msgId, ok = false, error = "Missing channel", errorCode = StreamErrorCodes.INVALID_PARAMS)
            return
        }

        var effectiveParams = streamParams
        var agoraWatchableViewerUrl: String? = null
        if (protocol == "agora" && streamParams.token.isBlank()) {
            when (
                val fetch = streamTokenFetcher.fetchPublisherToken(
                    resolvedStreamTokenUrl(),
                    streamTokenSecretProvider(),
                    streamParams.channel,
                    streamParams.uid,
                )
            ) {
                is StreamTokenFetchResult.Failure -> {
                    publishAck(msgId, ok = false, error = fetch.message, errorCode = fetch.code)
                    publishStreamStatus(
                        StreamSessionState(
                            state = "error",
                            protocol = protocol,
                            channel = streamParams.channel,
                            errorCode = fetch.code,
                            errorMessage = fetch.message,
                        )
                    )
                    return
                }
                is StreamTokenFetchResult.Success -> {
                    effectiveParams = streamParams.copy(
                        token = fetch.publisherToken.token,
                        uid = fetch.publisherToken.uid,
                    )
                }
            }
        }

        if (protocol == "agora") {
            when (
                val audience = streamTokenFetcher.fetchAudienceToken(
                    resolvedStreamTokenUrl(),
                    streamTokenSecretProvider(),
                    effectiveParams.channel,
                )
            ) {
                is StreamTokenFetchResult.Failure -> {
                    publishAck(msgId, ok = false, error = audience.message, errorCode = audience.code)
                    publishStreamStatus(
                        StreamSessionState(
                            state = "error",
                            protocol = protocol,
                            channel = effectiveParams.channel,
                            errorCode = audience.code,
                            errorMessage = audience.message,
                        )
                    )
                    return
                }
                is StreamTokenFetchResult.Success -> {
                    agoraWatchableViewerUrl = StreamViewerUrl.buildWatchable(
                        brokerConfig.viewerBaseUrl,
                        effectiveParams.channel,
                        audience.publisherToken.token,
                    )
                }
            }
        }

        publishStreamStatus(
            StreamSessionState(
                state = "starting",
                protocol = protocol,
                channel = effectiveParams.channel.takeIf { it.isNotBlank() },
                viewerUrl = agoraWatchableViewerUrl,
            )
        )

        val startContext = StreamStartContext(
            defaultAgoraAppId = defaultAgoraAppId,
            viewerBaseUrl = brokerConfig.viewerBaseUrl,
            agoraWatchableViewerUrl = agoraWatchableViewerUrl,
            isNetworkAvailable = { aircraftPort.isTelemetryReady.value },
            isCameraReady = { aircraftPort.isConnected.value && aircraftPort.isTelemetryReady.value },
        )

        val result = streamPort.startStream(effectiveParams, startContext)
        when (result) {
            is StreamResult.Success -> {
                val ackResult = buildJsonObject {
                    result.channel?.let { put("channel", it) }
                    result.viewerUrl?.let { put("viewer_url", it) }
                    result.startedAt?.let { put("started_at", it) }
                    result.url?.let { put("url", it) }
                }
                publishAck(msgId, ok = true, result = ackResult)
                publishStreamStatus(
                    StreamSessionState(
                        state = "live",
                        protocol = result.protocol,
                        channel = result.channel,
                        viewerUrl = result.viewerUrl,
                        rtmpUrl = result.url,
                        startedAt = result.startedAt,
                    )
                )
            }
            is StreamResult.Failure -> {
                publishAck(msgId, ok = false, error = result.message, errorCode = result.code)
                publishStreamStatus(
                    StreamSessionState(
                        state = "error",
                        protocol = protocol,
                        channel = effectiveParams.channel.takeIf { it.isNotBlank() },
                        errorCode = result.code,
                        errorMessage = result.message,
                    )
                )
            }
        }
    }

    private suspend fun publishStreamStatus(session: StreamSessionState) {
        val msg = StreamStatusMessage(
            state = session.state,
            streaming = session.state == "live" || session.state == "starting",
            protocol = session.protocol,
            channel = session.channel,
            viewerUrl = session.viewerUrl,
            url = session.rtmpUrl,
            errorCode = session.errorCode,
            error = session.errorMessage,
            startedAt = session.startedAt,
        )
        publishStatus(json.encodeToString(msg))
    }

    private suspend fun handleCameraSnapshotCommand(msgId: String, params: SnapshotParams) {
        val quality = params.quality.coerceIn(1, 100)
        val capture = withTimeoutOrNull(SNAPSHOT_CAPTURE_TIMEOUT_MS) {
            snapshotPort.captureVideoFrameJpeg(quality, SNAPSHOT_CAPTURE_TIMEOUT_MS)
        }
        when {
            capture == null -> {
                val message =
                    "No FPV video frame within ${SNAPSHOT_CAPTURE_TIMEOUT_MS}ms — " +
                        "ensure aircraft video link is active (DJI preview on phone; stream.start not required)"
                publishAck(msgId, ok = false, error = message, errorCode = PhotoErrorCodes.SNAPSHOT_TIMEOUT)
                publishSnapshotDone(
                    CameraSnapshotDoneMessage(
                        ok = false,
                        errorCode = PhotoErrorCodes.SNAPSHOT_TIMEOUT,
                        error = message,
                    ),
                )
            }
            capture is SnapshotCaptureResult.Failure -> {
                publishAck(msgId, ok = false, error = capture.message, errorCode = capture.code)
                publishSnapshotDone(
                    CameraSnapshotDoneMessage(
                        ok = false,
                        errorCode = capture.code,
                        error = capture.message,
                    ),
                )
            }
            capture is SnapshotCaptureResult.Success -> {
                publishAck(msgId, ok = true)
                scope.launch {
                    completeSnapshotDelivery(msgId, params, capture)
                }
            }
        }
    }

    private suspend fun completeSnapshotDelivery(
        msgId: String,
        params: SnapshotParams,
        captured: SnapshotCaptureResult.Success,
    ) {
        val delivered = withTimeoutOrNull(SNAPSHOT_DELIVERY_TIMEOUT_MS) {
            completeSnapshotDeliveryInternal(msgId, params, captured)
            true
        }
        if (delivered == null) {
            val message = "Snapshot delivery timed out after ${SNAPSHOT_DELIVERY_TIMEOUT_MS}ms"
            publishSnapshotDone(
                CameraSnapshotDoneMessage(
                    ok = false,
                    width = captured.width,
                    height = captured.height,
                    errorCode = PhotoErrorCodes.SNAPSHOT_TIMEOUT,
                    error = message,
                ),
            )
            publishAck(msgId, ok = false, error = message, errorCode = PhotoErrorCodes.SNAPSHOT_TIMEOUT)
        }
    }

    private suspend fun completeSnapshotDeliveryInternal(
        msgId: String,
        params: SnapshotParams,
        captured: SnapshotCaptureResult.Success,
    ) {
        val deliver = params.deliver
        val needsLocal = deliver == PhotoDeliverMode.LOCAL || deliver == PhotoDeliverMode.BOTH
        val needsUpload = deliver == PhotoDeliverMode.LINK || deliver == PhotoDeliverMode.BOTH

        val fileName = "snapshot_${System.currentTimeMillis()}.jpg"
        var localPath: String? = null
        var uploadError: PhotoUploadResult.Failure? = null
        var uploadSuccess: PhotoUploadResult.Success? = null

        if (needsLocal) {
            when (val saved = photoDeliveryPort.saveJpegLocally(captured.jpegBytes, null, fileName)) {
                is PhotoLocalSaveResult.Failure -> {
                    publishSnapshotDone(
                        CameraSnapshotDoneMessage(
                            ok = false,
                            width = captured.width,
                            height = captured.height,
                            errorCode = saved.code,
                            error = saved.message,
                        ),
                    )
                    publishAck(msgId, ok = false, error = saved.message, errorCode = saved.code)
                    return
                }
                is PhotoLocalSaveResult.Success -> localPath = saved.localPath
            }
        }

        if (needsUpload) {
            when (
                val upload = photoUploadClient.uploadJpeg(
                    uploadUrl = resolvedPhotoUploadUrl(),
                    bearerSecret = streamTokenSecretProvider(),
                    jpegBytes = captured.jpegBytes,
                    aircraftId = brokerConfig.aircraftId,
                    msgId = msgId,
                    fileIndex = null,
                )
            ) {
                is PhotoUploadResult.Failure -> uploadError = upload
                is PhotoUploadResult.Success -> uploadSuccess = upload
            }
        }

        val contentBytes = captured.jpegBytes.size.toLong()
        val localOk = !needsLocal || localPath != null
        val uploadOk = !needsUpload || uploadSuccess != null
        val overallOk = when (deliver) {
            PhotoDeliverMode.LOCAL -> localOk
            PhotoDeliverMode.LINK -> uploadOk
            PhotoDeliverMode.BOTH -> localOk && uploadOk
        }

        val done = CameraSnapshotDoneMessage(
            ok = overallOk,
            downloadUrl = uploadSuccess?.downloadUrl,
            localPath = localPath,
            contentType = "image/jpeg",
            bytes = uploadSuccess?.bytes?.takeIf { it > 0 } ?: contentBytes,
            expiresAt = uploadSuccess?.expiresAt,
            errorCode = uploadError?.code,
            error = uploadError?.message,
            width = captured.width,
            height = captured.height,
        )
        publishSnapshotDone(done)

        if (uploadError != null) {
            publishAck(
                msgId,
                ok = false,
                error = uploadError.message,
                errorCode = uploadError.code,
                result = buildJsonObject {
                    localPath?.let { put("local_path", it) }
                    put("content_type", "image/jpeg")
                    put("bytes", contentBytes)
                    put("source", "video_frame")
                    put("width", captured.width)
                    put("height", captured.height)
                },
            )
        } else if (uploadSuccess != null || localPath != null) {
            publishAck(
                msgId,
                ok = true,
                result = buildJsonObject {
                    uploadSuccess?.downloadUrl?.let { put("download_url", it) }
                    localPath?.let { put("local_path", it) }
                    put("content_type", "image/jpeg")
                    put("bytes", uploadSuccess?.bytes ?: contentBytes)
                    uploadSuccess?.expiresAt?.let { put("expires_at", it) }
                    put("source", "video_frame")
                    put("width", captured.width)
                    put("height", captured.height)
                },
            )
        }
    }

    private suspend fun publishSnapshotDone(msg: CameraSnapshotDoneMessage) {
        publishStatus(json.encodeToString(msg))
        _snapshotDeliveryEvents.tryEmit(msg)
        _photoDeliveryEvents.tryEmit(
            CameraPhotoDoneMessage(
                type = "camera.snapshot_done",
                ok = msg.ok,
                downloadUrl = msg.downloadUrl,
                localPath = msg.localPath,
                contentType = msg.contentType,
                bytes = msg.bytes,
                expiresAt = msg.expiresAt,
                errorCode = msg.errorCode,
                error = msg.error,
                source = msg.source,
            ),
        )
    }

    private suspend fun handleCameraPhotoCommand(
        msgId: String,
        photoParams: PhotoParams,
        shootParams: PhotoParams,
    ) {
        val result = withTimeoutOrNull(PHOTO_SHOOT_TIMEOUT_MS) {
            cameraPort.takePhoto(shootParams)
        }
        when {
            result == null -> {
                val message = "Photo shoot timed out after ${PHOTO_SHOOT_TIMEOUT_MS}ms"
                publishAck(msgId, ok = false, error = message, errorCode = PhotoErrorCodes.PHOTO_TIMEOUT)
                publishPhotoDone(
                    CameraPhotoDoneMessage(
                        ok = false,
                        errorCode = PhotoErrorCodes.PHOTO_TIMEOUT,
                        error = message,
                    ),
                )
            }
            result is CameraResult.PhotoSuccess -> {
                publishAck(msgId, ok = true)
                scope.launch {
                    completePhotoDelivery(msgId, photoParams, result.fileIndex)
                }
            }
            result is CameraResult.Failure -> {
                publishAck(msgId, ok = false, error = result.message)
                publishPhotoDone(
                    CameraPhotoDoneMessage(ok = false, error = result.message),
                )
            }
            else -> {
                publishAck(msgId, ok = false, error = "Unexpected camera response")
            }
        }
    }

    private suspend fun completePhotoDelivery(
        msgId: String,
        params: PhotoParams,
        shootFileIndex: Int?,
    ) {
        val delivered = withTimeoutOrNull(PHOTO_DELIVERY_TIMEOUT_MS) {
            completePhotoDeliveryInternal(msgId, params, shootFileIndex)
            true
        }
        if (delivered == null) {
            val message = "Photo delivery timed out after ${PHOTO_DELIVERY_TIMEOUT_MS}ms"
            publishPhotoDone(
                CameraPhotoDoneMessage(
                    ok = false,
                    fileIndex = shootFileIndex,
                    errorCode = PhotoErrorCodes.PHOTO_TIMEOUT,
                    error = message,
                ),
            )
            publishAck(msgId, ok = false, error = message, errorCode = PhotoErrorCodes.PHOTO_TIMEOUT)
        }
    }

    private suspend fun completePhotoDeliveryInternal(
        msgId: String,
        params: PhotoParams,
        shootFileIndex: Int?,
    ) {
        val deliver = params.deliver
        val needsLocal = deliver == PhotoDeliverMode.LOCAL || deliver == PhotoDeliverMode.BOTH
        val needsUpload = deliver == PhotoDeliverMode.LINK || deliver == PhotoDeliverMode.BOTH

        val preferredSize = effectivePhotoSize(params)
        val pull = photoDeliveryPort.pullLatestJpeg(preferredSize)
        if (pull is PhotoPullResult.Failure) {
            publishPhotoDone(
                CameraPhotoDoneMessage(
                    ok = false,
                    fileIndex = shootFileIndex,
                    errorCode = pull.code,
                    error = pull.message,
                ),
            )
            publishAck(
                msgId,
                ok = false,
                error = pull.message,
                errorCode = pull.code,
            )
            return
        }

        val pulled = pull as PhotoPullResult.Success
        val fileIndex = pulled.fileIndex.takeIf { it > 0 } ?: shootFileIndex
        var localPath: String? = null
        var uploadError: PhotoUploadResult.Failure? = null
        var uploadSuccess: PhotoUploadResult.Success? = null

        if (needsLocal) {
            when (val saved = photoDeliveryPort.saveJpegLocally(pulled.jpegBytes, fileIndex, pulled.fileName)) {
                is PhotoLocalSaveResult.Failure -> {
                    publishPhotoDone(
                        CameraPhotoDoneMessage(
                            ok = false,
                            fileIndex = fileIndex,
                            errorCode = saved.code,
                            error = saved.message,
                        ),
                    )
                    publishAck(msgId, ok = false, error = saved.message, errorCode = saved.code)
                    return
                }
                is PhotoLocalSaveResult.Success -> localPath = saved.localPath
            }
        }

        if (needsUpload) {
            when (
                val upload = photoUploadClient.uploadJpeg(
                    uploadUrl = resolvedPhotoUploadUrl(),
                    bearerSecret = streamTokenSecretProvider(),
                    jpegBytes = pulled.jpegBytes,
                    aircraftId = brokerConfig.aircraftId,
                    msgId = msgId,
                    fileIndex = fileIndex,
                )
            ) {
                is PhotoUploadResult.Failure -> uploadError = upload
                is PhotoUploadResult.Success -> uploadSuccess = upload
            }
        }

        val contentBytes = pulled.jpegBytes.size.toLong()
        val localOk = !needsLocal || localPath != null
        val uploadOk = !needsUpload || uploadSuccess != null
        val overallOk = when (deliver) {
            PhotoDeliverMode.LOCAL -> localOk
            PhotoDeliverMode.LINK -> uploadOk
            PhotoDeliverMode.BOTH -> localOk && uploadOk
        }

        val done = CameraPhotoDoneMessage(
            ok = overallOk,
            fileIndex = fileIndex,
            downloadUrl = uploadSuccess?.downloadUrl,
            localPath = localPath,
            contentType = "image/jpeg",
            bytes = uploadSuccess?.bytes?.takeIf { it > 0 } ?: contentBytes,
            expiresAt = uploadSuccess?.expiresAt,
            errorCode = uploadError?.code,
            error = uploadError?.message,
        )
        publishPhotoDone(done)

        if (uploadError != null) {
            publishAck(
                msgId,
                ok = false,
                error = uploadError.message,
                errorCode = uploadError.code,
                result = buildJsonObject {
                    fileIndex?.let { put("file_index", it) }
                    localPath?.let { put("local_path", it) }
                    put("content_type", "image/jpeg")
                    put("bytes", contentBytes)
                },
            )
        } else if (uploadSuccess != null || localPath != null) {
            publishAck(
                msgId,
                ok = true,
                result = buildJsonObject {
                    fileIndex?.let { put("file_index", it) }
                    uploadSuccess?.downloadUrl?.let { put("download_url", it) }
                    localPath?.let { put("local_path", it) }
                    put("content_type", "image/jpeg")
                    put("bytes", uploadSuccess?.bytes ?: contentBytes)
                    uploadSuccess?.expiresAt?.let { put("expires_at", it) }
                },
            )
        }
    }

    private suspend fun publishPhotoDone(msg: CameraPhotoDoneMessage) {
        publishStatus(json.encodeToString(msg))
        _photoDeliveryEvents.tryEmit(msg)
    }

    private suspend fun completeVideoDelivery(msgId: String, params: RecordStopParams) {
        val deliver = params.deliver
        val needsLocal = deliver == PhotoDeliverMode.LOCAL || deliver == PhotoDeliverMode.BOTH
        val needsUpload = deliver == PhotoDeliverMode.LINK || deliver == PhotoDeliverMode.BOTH

        if (!needsLocal && !needsUpload) {
            return
        }

        delay(1_200)

        val pull = mediaLibraryPort.pullLatestVideo()
        if (pull is MediaDownloadResult.Failure) {
            publishRecordDeliveryStatus(
                CameraRecordStatusMessage(
                    recording = false,
                    ok = false,
                    errorCode = pull.code,
                    error = pull.message,
                ),
            )
            publishAck(msgId, ok = false, error = pull.message, errorCode = pull.code)
            return
        }

        val pulled = pull as MediaDownloadResult.Success
        var localPath: String? = null
        var uploadError: VideoUploadResult.Failure? = null
        var uploadSuccess: VideoUploadResult.Success? = null

        if (needsLocal) {
            localPath = mediaLibraryPort.saveToAppStorage(
                pulled.localPath,
                MediaAssetType.VIDEO,
                pulled.fileIndex,
                pulled.fileName,
            )
            if (localPath == null) {
                publishRecordDeliveryStatus(
                    CameraRecordStatusMessage(
                        recording = false,
                        ok = false,
                        fileIndex = pulled.fileIndex,
                        errorCode = com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                        error = "Local video save failed",
                    ),
                )
                publishAck(
                    msgId,
                    ok = false,
                    error = "Local video save failed",
                    errorCode = com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                )
                return
            }
        }

        val uploadSource = localPath ?: pulled.localPath
        if (needsUpload) {
            when (
                val upload = videoUploadClient.uploadMp4File(
                    uploadUrl = resolvedVideoUploadUrl(),
                    bearerSecret = streamTokenSecretProvider(),
                    mp4FilePath = uploadSource,
                    aircraftId = brokerConfig.aircraftId,
                    msgId = msgId,
                    fileIndex = pulled.fileIndex,
                )
            ) {
                is VideoUploadResult.Failure -> uploadError = upload
                is VideoUploadResult.Success -> uploadSuccess = upload
            }
        }

        val localOk = !needsLocal || localPath != null
        val uploadOk = !needsUpload || uploadSuccess != null
        val overallOk = when (deliver) {
            PhotoDeliverMode.LOCAL -> localOk
            PhotoDeliverMode.LINK -> uploadOk
            PhotoDeliverMode.BOTH -> localOk && uploadOk
        }

        val status = CameraRecordStatusMessage(
            recording = false,
            ok = overallOk,
            fileIndex = pulled.fileIndex,
            downloadUrl = uploadSuccess?.downloadUrl,
            localPath = localPath,
            contentType = "video/mp4",
            bytes = uploadSuccess?.bytes?.takeIf { it > 0 } ?: pulled.sizeBytes,
            expiresAt = uploadSuccess?.expiresAt,
            errorCode = uploadError?.code,
            error = uploadError?.message,
        )
        publishRecordDeliveryStatus(status)

        if (uploadError != null) {
            publishAck(
                msgId,
                ok = false,
                error = uploadError.message,
                errorCode = uploadError.code,
                result = buildJsonObject {
                    put("file_index", pulled.fileIndex)
                    localPath?.let { put("local_path", it) }
                    put("content_type", "video/mp4")
                    put("bytes", pulled.sizeBytes)
                },
            )
        } else if (uploadSuccess != null || localPath != null) {
            publishAck(
                msgId,
                ok = true,
                result = buildJsonObject {
                    put("file_index", pulled.fileIndex)
                    uploadSuccess?.downloadUrl?.let { put("download_url", it) }
                    localPath?.let { put("local_path", it) }
                    put("content_type", "video/mp4")
                    put("bytes", uploadSuccess?.bytes ?: pulled.sizeBytes)
                    uploadSuccess?.expiresAt?.let { put("expires_at", it) }
                },
            )
        }
    }

    private suspend fun publishRecordDeliveryStatus(msg: CameraRecordStatusMessage) {
        publishStatus(json.encodeToString(msg))
        _recordDeliveryEvents.tryEmit(msg)
    }

    private suspend fun handleGimbalResult(msgId: String, result: GimbalResult) {
        when (result) {
            is GimbalResult.Success -> publishAck(msgId, ok = true)
            is GimbalResult.Failure -> publishAck(msgId, ok = false, error = result.message, errorCode = result.code)
        }
    }

    private suspend fun handleFlightResult(msgId: String, result: FlightResult) {
        when (result) {
            is FlightResult.Success -> {
                publishAck(msgId, ok = true)
                publishAircraftStatus()
            }
            is FlightResult.Failure -> {
                publishAck(msgId, ok = false, error = result.message)
            }
        }
    }

    suspend fun publishAck(
        msgId: String,
        ok: Boolean,
        error: String? = null,
        errorCode: String? = null,
        result: JsonObject? = null,
    ) {
        val ack = AckResponse(msgId = msgId, ok = ok, error = error, errorCode = errorCode, result = result)
        val payload = json.encodeToString(ack)
        val mqttConnected = mqttTransport.connectionState.value is MqttConnectionState.Connected
        val ackLog = if (mqttConnected) {
            "[ACK] ${SensitiveDataRedactor.redactForLogs(payload)}"
        } else {
            "[ACK] (MQTT offline) ${SensitiveDataRedactor.redactForLogs(payload)}"
        }
        log(ackLog)
        if (ok) {
            AppLogger.i(tag, "[ACK] msg_id=$msgId ok=true mqtt=$mqttConnected")
        } else {
            AppLogger.w(tag, "[ACK] msg_id=$msgId ok=false error=$error mqtt=$mqttConnected")
        }
        mqttTransport.publish(brokerConfig.ackTopic, payload, qos = 1)
        if (brokerConfig.statusTopic != brokerConfig.ackTopic) {
            mqttTransport.publish(brokerConfig.statusTopic, payload, qos = 1)
        }
    }

    suspend fun publishAircraftStatus() {
        val status = AircraftStatusMessage(
            connected = aircraftPort.isConnected.value,
            product = aircraftPort.productType.value,
            flightMode = flightPort.flightMode.value,
            batteryPct = aircraftPort.batteryPercent.value,
            gps = aircraftPort.gpsInfo.value,
            inAir = flightPort.isFlying.value,
            motorsOn = flightPort.motorsOn.value,
            registered = aircraftPort.isRegistered.value,
            telemetryReady = aircraftPort.isTelemetryReady.value,
            errorState = aircraftPort.errorState.value
        )
        val payload = json.encodeToString(status)
        publishStatus(payload)
    }

    suspend fun publishCameraRecordStatus(recording: Boolean) {
        val msg = CameraRecordStatusMessage(
            recording = recording,
            durationSeconds = cameraPort.recordingDurationSeconds.value,
        )
        publishStatus(json.encodeToString(msg))
    }

    suspend fun publishStatus(payload: String) {
        log("[STATUS] ${SensitiveDataRedactor.redactForLogs(payload)}")
        mqttTransport.publish(brokerConfig.statusTopic, payload, qos = 1)
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val logLine = "[$timestamp] ${SensitiveDataRedactor.redactPlainText(message)}"
        _commandLogs.tryEmit(logLine)
    }
}
