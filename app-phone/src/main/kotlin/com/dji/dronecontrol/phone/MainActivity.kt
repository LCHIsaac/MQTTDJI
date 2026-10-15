package com.dji.dronecontrol.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dji.dronecontrol.adapter.dji.DjiAircraftConnectionAdapter
import com.dji.dronecontrol.adapter.dji.DjiCameraAdapter
import com.dji.dronecontrol.adapter.dji.DjiFlightControllerAdapter
import com.dji.dronecontrol.adapter.dji.DjiGimbalAdapter
import com.dji.dronecontrol.adapter.dji.DjiMediaLibraryAdapter
import com.dji.dronecontrol.adapter.dji.DjiCameraSnapshotAdapter
import com.dji.dronecontrol.adapter.dji.DjiPhotoDeliveryAdapter
import com.dji.dronecontrol.core.photo.HttpPhotoUploadClient
import com.dji.dronecontrol.core.video.HttpVideoUploadClient
import com.dji.dronecontrol.core.photo.PhotoUploadResult
import com.dji.dronecontrol.core.video.VideoUploadResult
import java.io.File
import com.dji.dronecontrol.adapter.dji.DjiStreamAdapter
import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.log.LogEntry
import com.dji.dronecontrol.core.model.CommandEnvelope
import com.dji.dronecontrol.core.model.AircraftTelemetrySnapshot
import com.dji.dronecontrol.core.model.ConnectionDiagnostics
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import com.dji.dronecontrol.core.mqtt.PahoMqttTransport
import com.dji.dronecontrol.persistence.MqttSettingsRepository
import com.dji.dronecontrol.persistence.MqttTlsMaterialStore
import com.dji.dronecontrol.core.port.*
import com.dji.dronecontrol.core.router.CommandRouter
import com.dji.dronecontrol.ui.DroneControlTheme
import com.dji.dronecontrol.ui.MainScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel : ViewModel() {

    private val _appVersion = MutableStateFlow("v1.0.17 (18)")
    val appVersion = _appVersion.asStateFlow()

    private val _autoConnectMqttOnLaunch = MutableStateFlow(true)
    val autoConnectMqttOnLaunch = _autoConnectMqttOnLaunch.asStateFlow()

    private var mqttSettingsRepository: MqttSettingsRepository? = null
    private var mqttTlsStore: MqttTlsMaterialStore? = null

    private val _streamTokenSecret = MutableStateFlow("")
    val streamTokenSecret = _streamTokenSecret.asStateFlow()

    val djiAppKey: String = BuildConfig.DJI_APP_KEY

    private val _brokerConfig = MutableStateFlow(
        MqttBrokerConfig(
            host = "192.168.1.100",
            port = 1883,
            aircraftId = "mini4pro-01",
            clientId = "dji-phone-${UUID.randomUUID().toString().take(6)}"
        )
    )
    val brokerConfig = _brokerConfig.asStateFlow()

    private val mqttTransport = PahoMqttTransport(viewModelScope)
    val mqttState = mqttTransport.connectionState
    val mqttLinkDiagnostics = mqttTransport.linkDiagnostics

    private var flightPort: FlightControllerPort = DjiFlightControllerAdapter()
    private var cameraPort: CameraPort = DjiCameraAdapter()
    private val snapshotPort: SnapshotPort = DjiCameraSnapshotAdapter()
    private lateinit var photoDeliveryPort: DjiPhotoDeliveryAdapter
    private lateinit var mediaLibrary: DjiMediaLibraryAdapter
    private val photoUploadClient = HttpPhotoUploadClient()
    private val videoUploadClient = HttpVideoUploadClient()
    private val streamPort: StreamPort = DjiStreamAdapter(agoraAppIdProvider = { BuildConfig.AGORA_APP_ID })
    private val gimbalPort: com.dji.dronecontrol.core.port.GimbalPort = DjiGimbalAdapter(viewModelScope)
    private var aircraftPort: AircraftConnectionPort? = null
    private var connAdapter: DjiAircraftConnectionAdapter? = null

    private lateinit var commandRouter: CommandRouter

    private val _lastPhotoDownloadUrl = MutableStateFlow<String?>(null)
    val lastPhotoDownloadUrl = _lastPhotoDownloadUrl.asStateFlow()
    private val _lastPhotoLocalPath = MutableStateFlow<String?>(null)
    val lastPhotoLocalPath = _lastPhotoLocalPath.asStateFlow()

    val mqttLogs = MutableStateFlow<List<String>>(emptyList())
    val appLogs: StateFlow<List<LogEntry>> = AppLogger.logs

    fun initialize(application: PhoneApplication) {
        try {
            val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
            val vName = pInfo.versionName ?: "1.0.9"
            val vCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            _appVersion.value = "v$vName ($vCode)"
        } catch (e: Exception) {
            _appVersion.value = "v1.0.9 (10)"
        }

        mqttSettingsRepository = MqttSettingsRepository(application)
        mqttTlsStore = MqttTlsMaterialStore(application)

        val connAdapter = DjiAircraftConnectionAdapter(
            context = application,
            appKey = djiAppKey,
            onLog = { msg ->
                val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                mqttLogs.value = (mqttLogs.value + "[$timestamp] $msg").takeLast(200)
            }
        )
        aircraftPort = connAdapter
        this.connAdapter = connAdapter
        mediaLibrary = DjiMediaLibraryAdapter(application)
        photoDeliveryPort = DjiPhotoDeliveryAdapter(application, mediaLibrary)

        commandRouter = CommandRouter(
            flightPort = flightPort,
            cameraPort = cameraPort,
            gimbalPort = gimbalPort,
            streamPort = streamPort,
            aircraftPort = connAdapter,
            mqttTransport = mqttTransport,
            photoDeliveryPort = photoDeliveryPort,
            snapshotPort = snapshotPort,
            mediaLibraryPort = mediaLibrary,
            scope = viewModelScope
        )
        commandRouter.updateConfig(_brokerConfig.value)
        commandRouter.updateStreamSecrets(BuildConfig.AGORA_APP_ID)
        syncStreamTokenConfigToRouter()
        syncPhotoUploadConfigToRouter()
        syncVideoUploadConfigToRouter()
        commandRouter.startListening()
        mqttTlsStore?.loadTlsConfig()?.let { mqttTransport.setTlsConfig(it) }

        viewModelScope.launch {
            commandRouter.commandLogs.collect { logLine ->
                mqttLogs.value = (mqttLogs.value + logLine).takeLast(200)
            }
        }

        viewModelScope.launch {
            commandRouter.photoDeliveryEvents.collect { done ->
                _lastPhotoDownloadUrl.value = done.downloadUrl
                _lastPhotoLocalPath.value = done.localPath
            }
        }

        viewModelScope.launch {
            connAdapter.connect()
        }

        viewModelScope.launch {
            mqttSettingsRepository?.load()?.let { snap ->
                _brokerConfig.value = snap.config
                _autoConnectMqttOnLaunch.value = snap.autoConnectOnLaunch
                if (::commandRouter.isInitialized) {
                    commandRouter.updateConfig(snap.config)
                    syncStreamTokenConfigToRouter()
                    syncPhotoUploadConfigToRouter()
                }
                val persistedSecret = mqttSettingsRepository?.getStreamTokenSecret() ?: ""
                _streamTokenSecret.value = persistedSecret.ifBlank { BuildConfig.STREAM_TOKEN_SECRET }
                if (snap.autoConnectOnLaunch) {
                    connectMqttInternal(persistOnSuccess = false)
                }
            }
        }
    }

    val aircraftConnected get() = aircraftPort?.isConnected ?: MutableStateFlow(false)
    val isRegistered get() = aircraftPort?.isRegistered ?: MutableStateFlow(false)
    val isTelemetryReady get() = aircraftPort?.isTelemetryReady ?: MutableStateFlow(false)
    val errorState get() = aircraftPort?.errorState ?: MutableStateFlow<String?>(null)
    val connectionDiagnostics get() = aircraftPort?.connectionDiagnostics
        ?: MutableStateFlow(ConnectionDiagnostics())
    private val emptyAircraftTelemetry = MutableStateFlow(AircraftTelemetrySnapshot())
    val aircraftTelemetry get() = aircraftPort?.aircraftTelemetry ?: emptyAircraftTelemetry
    val productType get() = aircraftPort?.productType ?: MutableStateFlow("UNKNOWN")
    val batteryPercent get() = aircraftPort?.batteryPercent ?: MutableStateFlow(0)
    val flightMode get() = flightPort.flightMode
    val isFlying get() = flightPort.isFlying
    val isRecording get() = cameraPort.isRecording
    val isStreaming get() = streamPort.isStreaming
    val streamSession get() = streamPort.sessionState
    val gpsInfo get() = aircraftPort?.gpsInfo ?: MutableStateFlow(com.dji.dronecontrol.core.model.AircraftGpsInfo())

    fun mediaLibraryOrNull(): com.dji.dronecontrol.core.port.MediaLibraryPort? =
        if (::mediaLibrary.isInitialized) mediaLibrary else null

    fun updateConfig(newConfig: MqttBrokerConfig) {
        _brokerConfig.value = newConfig
        if (::commandRouter.isInitialized) {
            commandRouter.updateConfig(newConfig)
            syncStreamTokenConfigToRouter()
            syncPhotoUploadConfigToRouter()
            syncVideoUploadConfigToRouter()
        }
    }

    fun setStreamTokenSecret(secret: String) {
        _streamTokenSecret.value = secret
        syncStreamTokenConfigToRouter()
    }

    private fun syncStreamTokenConfigToRouter() {
        if (!::commandRouter.isInitialized) return
        commandRouter.updateStreamTokenConfig(
            defaultTokenUrl = BuildConfig.STREAM_TOKEN_URL,
            secretProvider = { resolvedStreamTokenSecret() },
        )
    }

    private fun syncPhotoUploadConfigToRouter() {
        if (!::commandRouter.isInitialized) return
        commandRouter.updatePhotoUploadConfig(BuildConfig.PHOTO_UPLOAD_URL)
    }

    private fun syncVideoUploadConfigToRouter() {
        if (!::commandRouter.isInitialized) return
        commandRouter.updateVideoUploadConfig(BuildConfig.VIDEO_UPLOAD_URL)
    }

    private fun resolvedPhotoUploadUrl(): String {
        return _brokerConfig.value.photoUploadUrl.trim()
            .ifBlank { BuildConfig.PHOTO_UPLOAD_URL.trim() }
    }

    private fun resolvedVideoUploadUrl(): String {
        return _brokerConfig.value.videoUploadUrl.trim()
            .ifBlank { BuildConfig.VIDEO_UPLOAD_URL.trim() }
    }

    suspend fun uploadPhotoLink(localPath: String, fileIndex: Int): Result<String> {
        val bytes = File(localPath).readBytes()
        return when (
            val result = photoUploadClient.uploadJpeg(
                uploadUrl = resolvedPhotoUploadUrl(),
                bearerSecret = resolvedStreamTokenSecret(),
                jpegBytes = bytes,
                aircraftId = _brokerConfig.value.aircraftId,
                msgId = "ui-photo-$fileIndex",
                fileIndex = fileIndex,
            )
        ) {
            is PhotoUploadResult.Success -> Result.success(result.downloadUrl)
            is PhotoUploadResult.Failure -> Result.failure(Exception(result.message))
        }
    }

    suspend fun uploadVideoLink(localPath: String, fileIndex: Int): Result<String> {
        return when (
            val result = videoUploadClient.uploadMp4File(
                uploadUrl = resolvedVideoUploadUrl(),
                bearerSecret = resolvedStreamTokenSecret(),
                mp4FilePath = localPath,
                aircraftId = _brokerConfig.value.aircraftId,
                msgId = "ui-video-$fileIndex",
                fileIndex = fileIndex,
            )
        ) {
            is VideoUploadResult.Success -> Result.success(result.downloadUrl)
            is VideoUploadResult.Failure -> Result.failure(Exception(result.message))
        }
    }

    private fun resolvedStreamTokenSecret(): String {
        return _streamTokenSecret.value.trim()
            .ifBlank { mqttSettingsRepository?.getStreamTokenSecret()?.trim() ?: "" }
            .ifBlank { BuildConfig.STREAM_TOKEN_SECRET.trim() }
    }

    fun saveMqttSettings() {
        viewModelScope.launch {
            mqttSettingsRepository?.save(
                _brokerConfig.value,
                _autoConnectMqttOnLaunch.value,
                streamTokenSecret = _streamTokenSecret.value.takeIf { it.isNotBlank() },
            )
            syncStreamTokenConfigToRouter()
            syncPhotoUploadConfigToRouter()
            syncVideoUploadConfigToRouter()
        }
    }

    fun setAutoConnectMqttOnLaunch(enabled: Boolean) {
        _autoConnectMqttOnLaunch.value = enabled
        viewModelScope.launch {
            mqttSettingsRepository?.setAutoConnectOnLaunch(enabled)
        }
    }

    fun refreshConnectionCheck() {
        connAdapter?.refreshConnectionCheck()
    }

    fun importMqttPkcs12(bytes: ByteArray, password: String) {
        mqttTlsStore?.savePkcs12(bytes, password)
        mqttTlsStore?.loadTlsConfig()?.let { mqttTransport.setTlsConfig(it) }
        updateConfig(_brokerConfig.value.copy(useMtls = true))
    }

    fun importMqttCa(pemBytes: ByteArray) {
        mqttTlsStore?.saveCustomCa(pemBytes)
        mqttTlsStore?.loadTlsConfig()?.let { mqttTransport.setTlsConfig(it) }
    }

    fun startAgoraManual(channel: String, optionalToken: String?) {
        val params = mutableMapOf<String, Any?>(
            "protocol" to "agora",
            "channel" to channel,
            "uid" to 1001,
        )
        if (!optionalToken.isNullOrBlank()) {
            params["token"] = optionalToken
        }
        executeManualCommand("stream.start", params)
    }

    fun stopStreamManual() {
        executeManualCommand("stream.stop", emptyMap())
    }

    fun connectMqtt() {
        viewModelScope.launch {
            connectMqttInternal(persistOnSuccess = true)
        }
    }

    private suspend fun connectMqttInternal(persistOnSuccess: Boolean) {
        try {
            mqttTransport.connect(_brokerConfig.value)
            if (persistOnSuccess) {
                mqttSettingsRepository?.save(
                    _brokerConfig.value,
                    _autoConnectMqttOnLaunch.value,
                    streamTokenSecret = _streamTokenSecret.value.takeIf { it.isNotBlank() },
                )
            }
        } catch (_: Exception) {
        }
    }

    fun disconnectMqtt() {
        viewModelScope.launch {
            mqttTransport.disconnect()
        }
    }

    fun executeManualCommand(cmd: String, paramsMap: Map<String, Any?>) {
        viewModelScope.launch {
            val jsonParams = buildJsonObject {
                paramsMap.forEach { (k, v) ->
                    when (v) {
                        is String -> put(k, v)
                        is Number -> put(k, v)
                        is Boolean -> put(k, v)
                        null -> {}
                    }
                }
            }
            val envelope = CommandEnvelope(
                schemaVersion = "1.0",
                msgId = "manual-${UUID.randomUUID().toString().take(8)}",
                ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date()),
                aircraftId = _brokerConfig.value.aircraftId,
                cmd = cmd,
                params = if (paramsMap.isNotEmpty()) jsonParams else null
            )
            commandRouter.executeEnvelope(envelope)
        }
    }

    fun clearMqttLogs() {
        mqttLogs.value = emptyList()
    }

    fun clearAppLogs() {
        AppLogger.clear()
    }

    fun refreshUsbAccessory(context: Context) {
        val (detected, desc) = DjiUsbAccessoryHelper.scanAccessories(context)
        connAdapter?.updateUsbAccessoryStatus(detected, desc)
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_USB_ACCESSORY_ATTACHED = "extra_usb_accessory_attached"
    }

    private val viewModel: MainViewModel by viewModels()
    private var usbDetachReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleUsbAccessoryIntent(intent)

        viewModel.initialize(application as PhoneApplication)
        viewModel.refreshUsbAccessory(this)
        usbDetachReceiver = DjiUsbAccessoryHelper.registerDetachReceiver(this) {
            viewModel.refreshUsbAccessory(this)
        }

        setContent {
            DroneControlTheme(darkTheme = isSystemInDarkTheme()) {
                val brokerConfig by viewModel.brokerConfig.collectAsState()
                val mqttState by viewModel.mqttState.collectAsState()
                val aircraftConnected by viewModel.aircraftConnected.collectAsState()
                val isRegistered by viewModel.isRegistered.collectAsState()
                val isTelemetryReady by viewModel.isTelemetryReady.collectAsState()
                val errorState by viewModel.errorState.collectAsState()
                val connectionDiagnostics by viewModel.connectionDiagnostics.collectAsState()
                val aircraftTelemetry by viewModel.aircraftTelemetry.collectAsState()
                val productType by viewModel.productType.collectAsState()
                val batteryPercent by viewModel.batteryPercent.collectAsState()
                val flightMode by viewModel.flightMode.collectAsState()
                val isFlying by viewModel.isFlying.collectAsState()
                val isRecording by viewModel.isRecording.collectAsState()
                val isStreaming by viewModel.isStreaming.collectAsState()
                val gpsInfo by viewModel.gpsInfo.collectAsState()
                val mqttLogs by viewModel.mqttLogs.collectAsState()
                val appLogs by viewModel.appLogs.collectAsState()
                val appVersion by viewModel.appVersion.collectAsState()
                val mqttLinkDiagnostics by viewModel.mqttLinkDiagnostics.collectAsState()
                val autoConnect by viewModel.autoConnectMqttOnLaunch.collectAsState()
                val streamTokenSecret by viewModel.streamTokenSecret.collectAsState()
                val streamSession by viewModel.streamSession.collectAsState()
                val lastPhotoDownloadUrl by viewModel.lastPhotoDownloadUrl.collectAsState()
                val lastPhotoLocalPath by viewModel.lastPhotoLocalPath.collectAsState()
                var mtlsPkcs12Password by remember { mutableStateOf("") }
                val pkcs12Picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    uri?.let {
                        contentResolver.openInputStream(it)?.use { input ->
                            viewModel.importMqttPkcs12(input.readBytes(), mtlsPkcs12Password)
                        }
                    }
                }
                val caPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    uri?.let {
                        contentResolver.openInputStream(it)?.use { input ->
                            viewModel.importMqttCa(input.readBytes())
                        }
                    }
                }

                MainScreen(
                    hostBadge = "Phone / Mini 4 Pro (RC-N2)",
                    isPhoneHost = true,
                    aircraftIdExample = "mini4pro-01",
                    djiAppKey = viewModel.djiAppKey,
                    brokerConfig = brokerConfig,
                    mqttState = mqttState,
                    mqttLinkDiagnostics = mqttLinkDiagnostics,
                    aircraftConnected = aircraftConnected,
                    isRegistered = isRegistered,
                    isTelemetryReady = isTelemetryReady,
                    errorState = errorState,
                    connectionDiagnostics = connectionDiagnostics,
                    aircraftTelemetry = aircraftTelemetry,
                    productType = productType,
                    batteryPercent = batteryPercent,
                    flightMode = flightMode,
                    isFlying = isFlying,
                    isRecording = isRecording,
                    isStreaming = isStreaming,
                    gpsInfo = gpsInfo,
                    streamSession = streamSession,
                    mqttLogs = mqttLogs,
                    appLogs = appLogs,
                    appVersion = appVersion,
                    lastPhotoDownloadUrl = lastPhotoDownloadUrl,
                    lastPhotoLocalPath = lastPhotoLocalPath,
                    mediaLibrary = viewModel.mediaLibraryOrNull(),
                    onUploadPhotoLink = { path, idx -> viewModel.uploadPhotoLink(path, idx) },
                    onUploadVideoLink = { path, idx -> viewModel.uploadVideoLink(path, idx) },
                    autoConnectMqttOnLaunch = autoConnect,
                    onConfigChange = { viewModel.updateConfig(it) },
                    onSaveMqttSettings = { viewModel.saveMqttSettings() },
                    onAutoConnectMqttChange = { viewModel.setAutoConnectMqttOnLaunch(it) },
                    onRefreshConnection = { viewModel.refreshConnectionCheck() },
                    onPickMqttPkcs12 = { pkcs12Picker.launch("application/x-pkcs12") },
                    onPickMqttCaCert = { caPicker.launch("*/*") },
                    mtlsPkcs12Password = mtlsPkcs12Password,
                    onMtlsPkcs12PasswordChange = { mtlsPkcs12Password = it },
                    streamTokenSecret = streamTokenSecret,
                    onStreamTokenSecretChange = { viewModel.setStreamTokenSecret(it) },
                    onStartAgoraStreamTest = { ch, tok -> viewModel.startAgoraManual(ch, tok) },
                    onStopStream = { viewModel.stopStreamManual() },
                    onConnectMqtt = { viewModel.connectMqtt() },
                    onDisconnectMqtt = { viewModel.disconnectMqtt() },
                    onExecuteCommand = { cmd, params -> viewModel.executeManualCommand(cmd, params) },
                    onClearMqttLogs = { viewModel.clearMqttLogs() },
                    onClearAppLogs = { viewModel.clearAppLogs() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshUsbAccessory(this)
    }

    override fun onDestroy() {
        usbDetachReceiver?.let { unregisterReceiver(it) }
        usbDetachReceiver = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAccessoryIntent(intent)
        viewModel.refreshUsbAccessory(this)
    }

    private fun handleUsbAccessoryIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED ||
            intent?.getBooleanExtra(EXTRA_USB_ACCESSORY_ATTACHED, false) == true
        ) {
            AppLogger.i("MainActivity", "USB accessory attached (RC-N2/N1)")
            viewModel.refreshUsbAccessory(this)
        }
    }
}
