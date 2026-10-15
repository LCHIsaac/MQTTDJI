package com.dji.dronecontrol.core.mock

import com.dji.dronecontrol.core.flight.VirtualStickConstants
import com.dji.dronecontrol.core.flight.VirtualStickStreamer
import com.dji.dronecontrol.core.flight.VirtualStickVelocity
import com.dji.dronecontrol.core.gimbal.GimbalRotateStreamer
import com.dji.dronecontrol.core.model.*
import com.dji.dronecontrol.core.port.*
import com.dji.dronecontrol.core.stream.StreamErrorCodes
import com.dji.dronecontrol.core.stream.StreamViewerUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-fidelity in-memory Mock Aircraft implementation.
 * Simulates aircraft connection, flight controller, camera, and RTMP streaming
 * for local testing, CI/CD, and RC Pro smoke tests without requiring real drone hardware.
 */
class MockAircraft(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    initialProduct: String = "Mavic 3E"
) : FlightControllerPort, CameraPort, StreamPort, AircraftConnectionPort, GimbalPort, PhotoDeliveryPort, MediaLibraryPort, SnapshotPort {

    /** Minimal JPEG-like payload for delivery tests (not a valid image). */
    var mockJpegPayload: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    var mockPullShouldFail: Boolean = false
    var mockLocalSaveShouldFail: Boolean = false
    var mockSnapshotShouldFail: Boolean = false
    var snapshotCount: Int = 0
    val savedLocalPaths = mutableListOf<String>()

    // --- AircraftConnectionPort ---
    private val _isConnected = MutableStateFlow(true)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isRegistered = MutableStateFlow(true)
    override val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _isTelemetryReady = MutableStateFlow(true)
    override val isTelemetryReady: StateFlow<Boolean> = _isTelemetryReady.asStateFlow()

    private val _productType = MutableStateFlow(initialProduct)
    override val productType: StateFlow<String> = _productType.asStateFlow()

    private val _batteryPercent = MutableStateFlow(88)
    override val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _gpsInfo = MutableStateFlow(
        AircraftGpsInfo(
            latitude = 22.3193,
            longitude = 114.1694,
            altitudeM = 0.0,
            satellitesCount = 18
        )
    )
    override val gpsInfo: StateFlow<AircraftGpsInfo> = _gpsInfo.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    override val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _connectionDiagnostics = MutableStateFlow(
        ConnectionDiagnostics(
            sdkRegistered = true,
            productConnected = true,
            flightControllerLinked = true,
            productType = initialProduct,
            lastSdkCallback = "mock"
        )
    )
    override val connectionDiagnostics: StateFlow<ConnectionDiagnostics> = _connectionDiagnostics.asStateFlow()

    private val _aircraftTelemetry = MutableStateFlow(
        AircraftTelemetrySnapshot(
            flightState = FlightStateTelemetry(
                flightMode = "P_GPS",
                isFlying = false,
                motorsOn = false,
                goHomeStatus = "IDLE",
                isHomeLocationSet = true,
                homeLatitude = 22.3193,
                homeLongitude = 114.1694,
                windWarning = "LEVEL_0",
                windSpeedMps = 2.5,
                windDirectionDeg = 90,
            ),
            battery = BatteryTelemetry(
                chargePercent = 88,
                chargeRemainingMah = 3520,
                voltageMv = 16400,
                currentMa = -1200,
                temperatureC = 28.0,
                cellCount = 3,
                cellVoltagesMv = listOf(4100, 4098, 4102),
                batteryConnected = true,
            ),
            positionMotion = PositionMotionTelemetry(
                latitude = 22.3193,
                longitude = 114.1694,
                altitudeM = 42.0,
                gpsSatelliteCount = 18,
                gpsSignalLevel = "LEVEL_5",
                pitchDeg = -2.0,
                rollDeg = 1.0,
                yawDeg = 180.0,
                velocityNorthMps = 0.0,
                velocityEastMps = 0.0,
                velocityDownMps = 0.0,
                ultrasonicHeightM = 0.35,
            ),
            obstacle = ObstacleTelemetry(
                forwardDistanceM = 8.5,
                backwardDistanceM = null,
                leftDistanceM = 12.0,
                rightDistanceM = 12.0,
                upwardDistanceM = null,
                downwardDistanceM = 0.4,
                overallAvoidanceEnabled = true,
                horizontalAvoidanceEnabled = true,
                obstacleAvoidanceType = "BYPASS",
                perceptionActive = true,
            ),
            linkController = LinkControllerTelemetry(
                airLinkConnected = true,
                signalQuality = 4,
                downLinkQuality = 4,
                upLinkQuality = 4,
                videoDataRateMbps = 4.2,
                remoteControllerModel = "RC_N2",
                productType = initialProduct,
                fcFirmwareVersion = "01.00.0000",
            ),
        ),
    )
    override val aircraftTelemetry: StateFlow<AircraftTelemetrySnapshot> = _aircraftTelemetry.asStateFlow()

    // --- FlightControllerPort ---
    private val _isFlying = MutableStateFlow(false)
    override val isFlying: StateFlow<Boolean> = _isFlying.asStateFlow()

    private val _flightMode = MutableStateFlow("P_GPS")
    override val flightMode: StateFlow<String> = _flightMode.asStateFlow()

    private val _motorsOn = MutableStateFlow(false)
    override val motorsOn: StateFlow<Boolean> = _motorsOn.asStateFlow()

    // Recorded commands and frames for verification
    val executedGimbalRotates = mutableListOf<GimbalRotateParams>()
    val executedGimbalSetAngles = mutableListOf<GimbalSetAngleParams>()
    var gimbalResetCount = 0

    private val gimbalRotateStreamer = GimbalRotateStreamer(
        frameSender = { _, _ -> },
        scope = scope,
    )

    val executedFlightCommands = mutableListOf<String>()
    val executedVirtualSticks = mutableListOf<VirtualStickCommand>()
    val recordedStickFrames = mutableListOf<VirtualStickVelocity>()

    val vsStreamer = VirtualStickStreamer(
        frameSender = { vx, vy, vz, yawRate ->
            val frame = VirtualStickVelocity(vx, vy, vz, yawRate)
            recordedStickFrames.add(frame)
            if (vx == 0.0 && vy == 0.0 && vz == 0.0 && yawRate == 0.0 && _isFlying.value) {
                _flightMode.value = "P_GPS"
            }
        },
        scope = scope
    )

    // --- CameraPort ---
    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    override val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    private val _cameraMode = MutableStateFlow("PHOTO_NORMAL")
    override val cameraMode: StateFlow<String> = _cameraMode.asStateFlow()

    private var recordTimerJob: Job? = null
    var photoCount = 0
    var videoCount = 0
    val mockMediaEntries = mutableListOf<MediaLibraryEntry>()

    // --- StreamPort ---
    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentStreamUrl = MutableStateFlow<String?>(null)
    override val currentStreamUrl: StateFlow<String?> = _currentStreamUrl.asStateFlow()

    private val _sessionState = MutableStateFlow(StreamSessionState())
    override val sessionState: StateFlow<StreamSessionState> = _sessionState.asStateFlow()

    private val _streamEvents = MutableSharedFlow<StreamSessionState>(extraBufferCapacity = 8)
    override val streamEvents: SharedFlow<StreamSessionState> = _streamEvents.asSharedFlow()

    var mockStreamShouldTimeout = false
    var mockStreamFailCode: String? = null

    override suspend fun connect() {
        _isConnected.value = true
        _errorState.value = null
    }

    override suspend fun disconnect() {
        vsStreamer.stopAndHover()
        _isConnected.value = false
        _isFlying.value = false
        _motorsOn.value = false
        _isStreaming.value = false
        stopRecord()
    }

    override suspend fun takeoff(params: TakeoffParams): FlightResult {
        if (!_isConnected.value) return FlightResult.Failure("Aircraft disconnected")
        if (_isFlying.value) return FlightResult.Failure("Aircraft already in air")

        vsStreamer.stopAndHover()
        executedFlightCommands.add("takeoff:targetHeight=${params.targetHeightM}")
        _motorsOn.value = true
        _isFlying.value = true
        _flightMode.value = "AUTO_TAKEOFF"
        _gpsInfo.value = _gpsInfo.value.copy(altitudeM = params.targetHeightM ?: 1.5)
        return FlightResult.Success
    }

    override suspend fun land(): FlightResult {
        if (!_isConnected.value) return FlightResult.Failure("Aircraft disconnected")
        if (!_isFlying.value) return FlightResult.Failure("Aircraft not in air")

        vsStreamer.stopAndHover()
        executedFlightCommands.add("land")
        _flightMode.value = "AUTO_LANDING"
        _isFlying.value = false
        _motorsOn.value = false
        _gpsInfo.value = _gpsInfo.value.copy(altitudeM = 0.0)
        return FlightResult.Success
    }

    override suspend fun goHome(): FlightResult {
        if (!_isConnected.value) return FlightResult.Failure("Aircraft disconnected")
        if (!_isFlying.value) return FlightResult.Failure("Aircraft not in air")

        vsStreamer.stopAndHover()
        executedFlightCommands.add("goHome")
        _flightMode.value = "GO_HOME"
        return FlightResult.Success
    }

    override suspend fun hover(): FlightResult {
        if (!_isConnected.value) return FlightResult.Failure("Aircraft disconnected")
        vsStreamer.stopAndHover()
        executedFlightCommands.add("hover")
        _flightMode.value = "P_GPS"
        return FlightResult.Success
    }

    override suspend fun move(command: VirtualStickCommand): FlightResult {
        if (!_isConnected.value) return FlightResult.Failure("Aircraft disconnected")
        if (!_isFlying.value) return FlightResult.Failure("Aircraft must take off before virtual stick move")

        executedFlightCommands.add("move:${command.mode}")
        executedVirtualSticks.add(command)
        _flightMode.value = "VIRTUAL_STICK"
        vsStreamer.startMove(command)
        return FlightResult.Success
    }

    override suspend fun rotate(params: RotateParams): FlightResult {
        if (!_isConnected.value) return FlightResult.Failure("Aircraft disconnected")
        if (!_isFlying.value) return FlightResult.Failure("Aircraft must take off before virtual stick rotate")

        executedFlightCommands.add("rotate:yawRate=${params.yawRate}")
        val vsCommand = VirtualStickCommand(
            mode = VirtualStickMode.VELOCITY,
            vx = 0.0,
            vy = 0.0,
            vz = 0.0,
            yawRate = params.yawRate,
            durationMs = params.durationMs
        )
        executedVirtualSticks.add(vsCommand)
        _flightMode.value = "VIRTUAL_STICK"
        vsStreamer.startRotate(params)
        return FlightResult.Success
    }

    override suspend fun emergencyStop(): FlightResult {
        gimbalRotateStreamer.cancelActive()
        vsStreamer.emergencyStop()
        executedFlightCommands.add("emergencyStop")
        _flightMode.value = "EMERGENCY_STOP"
        _isFlying.value = false
        _motorsOn.value = false
        _gpsInfo.value = _gpsInfo.value.copy(altitudeM = 0.0)
        return FlightResult.Success
    }

    override suspend fun rotate(params: GimbalRotateParams): GimbalResult {
        if (!_isConnected.value) return GimbalResult.Failure("Aircraft disconnected")
        executedGimbalRotates.add(params)
        gimbalRotateStreamer.startRotate(params)
        return GimbalResult.Success
    }

    override suspend fun setAngle(params: GimbalSetAngleParams): GimbalResult {
        if (!_isConnected.value) return GimbalResult.Failure("Aircraft disconnected")
        if (params.pitchDeg == null && params.yawDeg == null && params.rollDeg == null) {
            return GimbalResult.Failure("At least one angle required")
        }
        executedGimbalSetAngles.add(params)
        return GimbalResult.Success
    }

    override suspend fun reset(): GimbalResult {
        if (!_isConnected.value) return GimbalResult.Failure("Aircraft disconnected")
        gimbalResetCount++
        gimbalRotateStreamer.cancelActive()
        return GimbalResult.Success
    }

    override fun cancelActiveRotation() {
        gimbalRotateStreamer.cancelActive()
    }

    override suspend fun captureVideoFrameJpeg(quality: Int, timeoutMs: Long): SnapshotCaptureResult {
        if (!_isConnected.value) {
            return SnapshotCaptureResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.NO_VIDEO_FRAME,
                "Aircraft disconnected",
            )
        }
        if (mockSnapshotShouldFail) {
            return SnapshotCaptureResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.NO_VIDEO_FRAME,
                "Mock snapshot failed",
            )
        }
        snapshotCount++
        return SnapshotCaptureResult.Success(
            jpegBytes = mockJpegPayload,
            width = 640,
            height = 480,
        )
    }

    override suspend fun takePhoto(params: PhotoParams): CameraResult {
        if (!_isConnected.value) return CameraResult.Failure("Aircraft disconnected")
        photoCount++
        _cameraMode.value = "PHOTO_NORMAL"
        mockMediaEntries.add(
            0,
            MediaLibraryEntry(
                fileIndex = photoCount,
                fileName = "mock_$photoCount.jpg",
                type = MediaAssetType.PHOTO,
                sizeBytes = mockJpegPayload.size.toLong(),
            ),
        )
        return CameraResult.PhotoSuccess(fileIndex = photoCount)
    }

    override suspend fun pullLatestJpeg(preferredSize: PhotoSize, timeoutMs: Long): PhotoPullResult {
        if (!_isConnected.value) {
            return PhotoPullResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Aircraft disconnected",
            )
        }
        if (mockPullShouldFail) {
            return PhotoPullResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Mock pull failed",
            )
        }
        return PhotoPullResult.Success(
            jpegBytes = mockJpegPayload,
            fileIndex = photoCount,
            fileName = "mock_$photoCount.jpg",
        )
    }

    override suspend fun saveJpegLocally(
        jpegBytes: ByteArray,
        fileIndex: Int?,
        fileName: String?,
    ): PhotoLocalSaveResult {
        if (mockLocalSaveShouldFail) {
            return PhotoLocalSaveResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Mock local save failed",
            )
        }
        val path = "mock://photos/${fileName ?: "shot.jpg"}"
        savedLocalPaths.add(path)
        return PhotoLocalSaveResult.Success(path)
    }

    override suspend fun startRecord(): CameraResult {
        if (!_isConnected.value) return CameraResult.Failure("Aircraft disconnected")
        if (_isRecording.value) return CameraResult.Failure("Already recording")

        _cameraMode.value = "VIDEO_NORMAL"
        _isRecording.value = true
        _recordingDurationSeconds.value = 0
        recordTimerJob?.cancel()
        return CameraResult.RecordStarted
    }

    override suspend fun stopRecord(): CameraResult {
        if (!_isRecording.value) return CameraResult.Failure("Not recording")
        recordTimerJob?.cancel()
        recordTimerJob = null
        _isRecording.value = false
        videoCount++
        mockMediaEntries.add(
            0,
            MediaLibraryEntry(
                fileIndex = 1000 + videoCount,
                fileName = "mock_$videoCount.mp4",
                type = MediaAssetType.VIDEO,
                sizeBytes = 4096,
            ),
        )
        return CameraResult.RecordStopped
    }

    override suspend fun refreshList(filter: MediaListFilter): MediaListResult {
        if (!_isConnected.value) {
            return MediaListResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Aircraft disconnected",
            )
        }
        val filtered = mockMediaEntries.filter { entry ->
            when (filter) {
                MediaListFilter.ALL -> true
                MediaListFilter.PHOTOS -> entry.type == MediaAssetType.PHOTO
                MediaListFilter.VIDEOS -> entry.type == MediaAssetType.VIDEO
            }
        }
        return MediaListResult.Success(filtered)
    }

    override suspend fun downloadOriginal(entry: MediaLibraryEntry, timeoutMs: Long): MediaDownloadResult {
        if (!_isConnected.value) {
            return MediaDownloadResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Aircraft disconnected",
            )
        }
        val path = if (entry.type == MediaAssetType.PHOTO) {
            "mock://cache/${entry.fileName}"
        } else {
            "mock://cache/${entry.fileName}"
        }
        return MediaDownloadResult.Success(
            localPath = path,
            fileIndex = entry.fileIndex,
            fileName = entry.fileName,
            sizeBytes = entry.sizeBytes,
            contentType = if (entry.type == MediaAssetType.PHOTO) "image/jpeg" else "video/mp4",
        )
    }

    override suspend fun deleteEntries(entries: List<MediaLibraryEntry>): MediaDeleteResult {
        if (!_isConnected.value) {
            return MediaDeleteResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                "Aircraft disconnected",
            )
        }
        val indices = entries.map { it.fileIndex }.toSet()
        val removed = mockMediaEntries.removeAll { indices.contains(it.fileIndex) }
        return if (removed) MediaDeleteResult.Success(indices.size) else MediaDeleteResult.Success(0)
    }

    override suspend fun pullLatestVideo(timeoutMs: Long): MediaDownloadResult {
        val videos = mockMediaEntries.filter { it.type == MediaAssetType.VIDEO }
        val latest = videos.firstOrNull()
            ?: return MediaDownloadResult.Failure(
                com.dji.dronecontrol.core.photo.PhotoErrorCodes.MEDIA_PULL_FAILED,
                "No video on mock aircraft",
            )
        return downloadOriginal(latest, timeoutMs)
    }

    override suspend fun saveToAppStorage(
        sourcePath: String,
        type: MediaAssetType,
        fileIndex: Int?,
        fileName: String?,
    ): String? = "mock://${type.name.lowercase()}/${fileName ?: "file"}"

    override suspend fun startStream(params: StreamStartParams, context: StreamStartContext): StreamResult {
        if (!_isConnected.value) {
            return StreamResult.Failure(StreamErrorCodes.STREAM_ERROR, "Aircraft disconnected")
        }
        mockStreamFailCode?.let { code ->
            return StreamResult.Failure(code, "Mock stream failure")
        }
        if (!context.isNetworkAvailable()) {
            return StreamResult.Failure(StreamErrorCodes.NO_UPLINK, "No uplink")
        }
        if (!context.isCameraReady()) {
            return StreamResult.Failure(StreamErrorCodes.CAMERA_NOT_READY, "Camera not ready")
        }

        val protocol = params.protocol.lowercase()
        _sessionState.value = StreamSessionState(state = "starting", protocol = protocol)
        _streamEvents.tryEmit(_sessionState.value)

        if (mockStreamShouldTimeout) {
            delay(50)
            _sessionState.value = StreamSessionState(state = "error", protocol = protocol, errorCode = StreamErrorCodes.STREAM_TIMEOUT)
            return StreamResult.Failure(StreamErrorCodes.STREAM_TIMEOUT, "Timeout")
        }

        delay(20)
        return when (protocol) {
            "rtmp" -> {
                if (params.url.isBlank()) {
                    return StreamResult.Failure(StreamErrorCodes.INVALID_PARAMS, "Missing url")
                }
                _isStreaming.value = true
                _currentStreamUrl.value = params.url
                _sessionState.value = StreamSessionState(state = "live", protocol = "rtmp", rtmpUrl = params.url, startedAt = "2026-01-01T00:00:00Z")
                StreamResult.Success(protocol = "rtmp", url = params.url, startedAt = "2026-01-01T00:00:00Z")
            }
            "agora" -> {
                if (params.channel.isBlank() || params.token.isBlank()) {
                    return StreamResult.Failure(StreamErrorCodes.INVALID_PARAMS, "Missing channel/token")
                }
                val viewer = context.agoraWatchableViewerUrl
                    ?: StreamViewerUrl.build(context.viewerBaseUrl, params.channel)
                _isStreaming.value = true
                _currentStreamUrl.value = viewer
                _sessionState.value = StreamSessionState(
                    state = "live",
                    protocol = "agora",
                    channel = params.channel,
                    viewerUrl = viewer,
                    startedAt = "2026-01-01T00:00:00Z",
                )
                StreamResult.Success(
                    protocol = "agora",
                    channel = params.channel,
                    viewerUrl = viewer,
                    startedAt = "2026-01-01T00:00:00Z",
                )
            }
            else -> StreamResult.Failure(StreamErrorCodes.INVALID_PARAMS, "Bad protocol")
        }
    }

    override suspend fun stop(): StreamResult {
        _isStreaming.value = false
        _currentStreamUrl.value = null
        _sessionState.value = StreamSessionState(state = "stopped")
        _streamEvents.tryEmit(_sessionState.value)
        return StreamResult.Success(protocol = "unknown")
    }
}

/**
 * In-memory Mock MQTT transport for fast deterministic unit tests.
 */
class MockMqttTransport : MqttTransportPort {
    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val incomingCommands: SharedFlow<String> = _incomingCommands.asSharedFlow()

    val publishedMessages = mutableListOf<Pair<String, String>>() // (topic, payload)

    override suspend fun connect(config: MqttBrokerConfig) {
        _connectionState.value = MqttConnectionState.Connected
    }

    override suspend fun disconnect() {
        _connectionState.value = MqttConnectionState.Disconnected
    }

    override suspend fun publish(topic: String, payload: String, qos: Int, retained: Boolean) {
        publishedMessages.add(Pair(topic, payload))
    }

    suspend fun emitIncomingCommand(jsonPayload: String) {
        _incomingCommands.emit(jsonPayload)
    }
}
