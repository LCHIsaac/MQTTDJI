package com.dji.dronecontrol.adapter.dji

import android.content.Context
import android.util.Log
import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.model.AircraftGpsInfo
import com.dji.dronecontrol.core.model.AircraftTelemetrySnapshot
import com.dji.dronecontrol.core.model.ConnectionDiagnostics
import com.dji.dronecontrol.core.model.LinkControllerTelemetry
import com.dji.dronecontrol.core.port.AircraftConnectionPort
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.product.ProductType
import dji.sdk.keyvalue.value.remotecontroller.RemoteControllerType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.network.DJINetworkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * DJI Mobile SDK V5 connection adapter.
 *
 * [SDKManagerCallback.onProductConnect] indicates the RC/USB product session is up — not necessarily
 * that the aircraft O4 air link or flight controller is linked. We track RC, air link, product, and FC
 * separately and bootstrap key values with getValue + listen(getOnce=true).
 */
class DjiAircraftConnectionAdapter(
    private val context: Context,
    private val appKey: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onLog: ((String) -> Unit)? = null
) : AircraftConnectionPort {

    private val tag = "DjiConn"
    private val keyListenerOwner = Any()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isRegistered = MutableStateFlow(false)
    override val isRegistered: StateFlow<Boolean> = _isRegistered.asStateFlow()

    private val _isTelemetryReady = MutableStateFlow(false)
    override val isTelemetryReady: StateFlow<Boolean> = _isTelemetryReady.asStateFlow()

    private val _productType = MutableStateFlow("UNKNOWN")
    override val productType: StateFlow<String> = _productType.asStateFlow()

    private val _batteryPercent = MutableStateFlow(0)
    override val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _gpsInfo = MutableStateFlow(AircraftGpsInfo())
    override val gpsInfo: StateFlow<AircraftGpsInfo> = _gpsInfo.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    override val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _connectionDiagnostics = MutableStateFlow(ConnectionDiagnostics())
    override val connectionDiagnostics: StateFlow<ConnectionDiagnostics> = _connectionDiagnostics.asStateFlow()

    private var usbDetected = false
    private var usbDescription = "—"
    private var usbRowLabel = "—"
    private var productId: Int? = null
    private var productConnectedFlag = false
    private var flightControllerLinked = false
    private var remoteControllerLinked = false
    private var airLinkConnected = false
    private var productKeyConnection = false
    private var upLinkQuality: Int? = null
    private var downLinkQuality: Int? = null
    private var signalQuality: Int? = null
    private var remoteControllerModel = "—"
    private var fcFirmwareVersion = "—"
    private var flightModeLabel = "UNKNOWN"
    private var lastSdkCallback = "—"
    private var sdkInitComplete = false
    private var sdkRegistrationError: String? = null
    private var productDatabaseReady = false
    private var productDatabaseProgress = "—"

    private var keyListenersActive = false
    private var keyPollJob: Job? = null

    private fun emitLog(message: String) {
        Log.i(tag, message)
        AppLogger.i(tag, message)
        onLog?.invoke(message)
    }

    private fun keyLog(message: String) {
        Log.d(tag, message)
        AppLogger.d(tag, message)
        onLog?.invoke(message)
    }

    private val keyLogSink: (String) -> Unit = { message -> keyLog(message) }

    private val telemetryCollector = DjiTelemetryCollector(scope, keyLogSink)
    override val aircraftTelemetry: StateFlow<AircraftTelemetrySnapshot> = telemetryCollector.snapshot

    private fun emitWarn(message: String) {
        Log.w(tag, message)
        AppLogger.w(tag, message)
        onLog?.invoke("[WARN] $message")
    }

    private fun emitError(message: String) {
        Log.e(tag, message)
        AppLogger.e(tag, message)
        onLog?.invoke("[ERROR] $message")
    }

    private fun recordSdkCallback(name: String) {
        lastSdkCallback = name
        emitLog("SDKManagerCallback: $name")
        publishDiagnostics()
    }

    private fun publishDiagnostics() {
        telemetryCollector.setLinkDiagnosticsProvider {
            LinkControllerTelemetry(
                airLinkConnected = airLinkConnected,
                signalQuality = signalQuality,
                downLinkQuality = downLinkQuality,
                upLinkQuality = upLinkQuality,
                remoteControllerModel = remoteControllerModel,
                productType = _productType.value,
                fcFirmwareVersion = fcFirmwareVersion,
            )
        }
        telemetryCollector.refreshLinkFromDiagnostics()
        _connectionDiagnostics.value = ConnectionDiagnostics(
            sdkRegistered = _isRegistered.value,
            sdkInitComplete = sdkInitComplete,
            sdkRegistrationError = sdkRegistrationError,
            productDatabaseReady = productDatabaseReady,
            productDatabaseProgress = productDatabaseProgress,
            usbAccessoryDetected = usbDetected,
            usbAccessoryDescription = usbDescription,
            usbRowLabel = usbRowLabel.ifBlank { usbDescription },
            productConnected = productConnectedFlag,
            productId = productId,
            productType = _productType.value,
            productKeyConnection = productKeyConnection,
            remoteControllerLinked = remoteControllerLinked,
            remoteControllerModel = remoteControllerModel,
            airLinkConnected = airLinkConnected,
            upLinkQuality = upLinkQuality,
            downLinkQuality = downLinkQuality,
            signalQuality = signalQuality,
            flightControllerLinked = flightControllerLinked,
            fcFirmwareVersion = fcFirmwareVersion,
            flightMode = flightModeLabel,
            gpsSatelliteCount = _gpsInfo.value.satellitesCount,
            batteryPercent = _batteryPercent.value,
            lastSdkCallback = lastSdkCallback,
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    override fun updateUsbAccessoryStatus(detected: Boolean, description: String) {
        usbDetected = detected
        usbDescription = description
        usbRowLabel = description
        emitLog("USB accessory: detected=$detected desc=$description")
        publishDiagnostics()
    }

    /** RC Pro host: show built-in USB row on Connection page. */
    fun setBuiltInRcLinkLabel(label: String = "Built-in (RC Pro)") {
        usbRowLabel = label
        usbDescription = label
        usbDetected = true
        publishDiagnostics()
    }

    override fun refreshConnectionCheck() {
        emitLog("Manual connection refresh — re-reading DJI keys...")
        scope.launch {
            if (productConnectedFlag) {
                bootstrapAllKeys()
            } else {
                publishDiagnostics()
            }
        }
    }

    private fun registerKeyListeners() {
        if (keyListenersActive) return
        keyListenersActive = true

        try {
            val keyManager = KeyManager.getInstance()
            emitLog("Product session up — registering KeyManager listeners (RC / AirLink / Product / FC)...")

            val fcIndex = ComponentIndexType.LEFT_OR_MAIN
            val fcConnectionKey = KeyTools.createKey(FlightControllerKey.KeyConnection, fcIndex)
            keyManager.listen(fcConnectionKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    applyFcConnection(newValue == true)
                }
            })

            val rcConnectionKey = KeyTools.createKey(RemoteControllerKey.KeyConnection)
            keyManager.listen(rcConnectionKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    remoteControllerLinked = newValue == true
                    keyLogSink("RemoteController KeyConnection: $remoteControllerLinked")
                    publishDiagnostics()
                }
            })

            val rcTypeKey = KeyTools.createKey(RemoteControllerKey.KeyRemoteControllerType)
            keyManager.listen(rcTypeKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<RemoteControllerType> {
                override fun onValueChange(oldValue: RemoteControllerType?, newValue: RemoteControllerType?) {
                    remoteControllerModel = newValue?.name ?: "—"
                    keyLogSink("RemoteController type: $remoteControllerModel")
                    publishDiagnostics()
                }
            })

            val airLinkKey = KeyTools.createKey(AirLinkKey.KeyConnection)
            keyManager.listen(airLinkKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    airLinkConnected = newValue == true
                    keyLogSink("AirLink KeyConnection: $airLinkConnected")
                    publishDiagnostics()
                    if (airLinkConnected) {
                        scope.launch { readAirLinkQuality() }
                    }
                }
            })

            val upLinkKey = KeyTools.createKey(AirLinkKey.KeyUpLinkQuality)
            keyManager.listen(upLinkKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    upLinkQuality = newValue
                    publishDiagnostics()
                }
            })

            val downLinkKey = KeyTools.createKey(AirLinkKey.KeyDownLinkQuality)
            keyManager.listen(downLinkKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    downLinkQuality = newValue
                    publishDiagnostics()
                }
            })

            val signalKey = KeyTools.createKey(AirLinkKey.KeySignalQuality)
            keyManager.listen(signalKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    signalQuality = newValue
                    publishDiagnostics()
                }
            })

            val productConnKey = KeyTools.createKey(ProductKey.KeyConnection)
            keyManager.listen(productConnKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    productKeyConnection = newValue == true
                    keyLogSink("Product KeyConnection: $productKeyConnection")
                    publishDiagnostics()
                }
            })

            val productTypeKey = KeyTools.createKey(ProductKey.KeyProductType)
            keyManager.listen(productTypeKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<ProductType> {
                override fun onValueChange(oldValue: ProductType?, newValue: ProductType?) {
                    val name = mapProductType(newValue)
                    _productType.value = name
                    keyLogSink("ProductKey.KeyProductType: $name ($newValue)")
                    publishDiagnostics()
                }
            })

            val batteryKey = KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent)
            keyManager.listen(batteryKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    newValue?.let { pct ->
                        _batteryPercent.value = pct
                        if (flightControllerLinked) _isTelemetryReady.value = true
                        keyLogSink("BatteryKey: $pct%")
                        publishDiagnostics()
                    }
                }
            })

            val locationKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D, fcIndex)
            keyManager.listen(locationKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<LocationCoordinate3D> {
                override fun onValueChange(oldValue: LocationCoordinate3D?, newValue: LocationCoordinate3D?) {
                    newValue?.let { loc ->
                        val lat = loc.latitude ?: 0.0
                        val lon = loc.longitude ?: 0.0
                        val alt = loc.altitude ?: 0.0
                        _gpsInfo.value = _gpsInfo.value.copy(
                            latitude = lat,
                            longitude = lon,
                            altitudeM = alt
                        )
                        if (flightControllerLinked && (lat != 0.0 || lon != 0.0 || alt != 0.0)) {
                            _isTelemetryReady.value = true
                        }
                    }
                }
            })

            val satelliteKey = KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount, fcIndex)
            keyManager.listen(satelliteKey, keyListenerOwner, true, object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    val sats = newValue ?: 0
                    _gpsInfo.value = _gpsInfo.value.copy(satellitesCount = sats)
                    if (flightControllerLinked && sats > 0) {
                        _isTelemetryReady.value = true
                    }
                    publishDiagnostics()
                }
            })

            emitLog("KeyManager listeners registered (getOnce=true for initial values)")
            telemetryCollector.register()
            scope.launch { bootstrapAllKeys() }
            startKeyPollBackoff()
        } catch (t: Throwable) {
            val msg = t.message ?: t.toString()
            if (DjiMsdkSession.isBenignKeyError(msg)) {
                keyLogSink("Key listener registration deferred: $msg")
            } else {
                emitError("Failed to register KeyManager listeners: $msg")
            }
        }
    }

    private fun applyFcConnection(linked: Boolean) {
        flightControllerLinked = linked
        _isConnected.value = linked
        DjiMsdkSession.setFlightControllerLinked(linked)
        keyLog("FlightController KeyConnection (LEFT_OR_MAIN): linked=$linked")
        if (linked) {
            _errorState.value = null
            scope.launch {
                readFcFirmwareAndFlightMode()
            }
        } else {
            _isTelemetryReady.value = false
            flightModeLabel = "UNKNOWN"
        }
        publishDiagnostics()
    }

    private suspend fun readAirLinkQuality() {
        val fcIndex = ComponentIndexType.LEFT_OR_MAIN
        upLinkQuality = DjiKeyValueHelper.getValueAsync(
            "AirLink.KeyUpLinkQuality",
            KeyTools.createKey(AirLinkKey.KeyUpLinkQuality),
            keyLogSink
        )
        downLinkQuality = DjiKeyValueHelper.getValueAsync(
            "AirLink.KeyDownLinkQuality",
            KeyTools.createKey(AirLinkKey.KeyDownLinkQuality),
            keyLogSink
        )
        signalQuality = DjiKeyValueHelper.getValueAsync(
            "AirLink.KeySignalQuality",
            KeyTools.createKey(AirLinkKey.KeySignalQuality),
            keyLogSink
        )
        publishDiagnostics()
    }

    private suspend fun readFcFirmwareAndFlightMode() {
        val fcIndex = ComponentIndexType.LEFT_OR_MAIN
        fcFirmwareVersion = DjiKeyValueHelper.getValueAsync(
            "FlightController.KeyFirmwareVersion",
            KeyTools.createKey(FlightControllerKey.KeyFirmwareVersion, fcIndex),
            keyLogSink
        ) ?: "—"
        val mode: FlightMode? = DjiKeyValueHelper.getValueAsync(
            "FlightController.KeyFlightMode",
            KeyTools.createKey(FlightControllerKey.KeyFlightMode, fcIndex),
            keyLogSink
        )
        flightModeLabel = mode?.name ?: "UNKNOWN"
        publishDiagnostics()
    }

    private suspend fun bootstrapAllKeys() {
        val fcIndex = ComponentIndexType.LEFT_OR_MAIN
        remoteControllerLinked = DjiKeyValueHelper.getValueAsync(
            "RemoteController.KeyConnection",
            KeyTools.createKey(RemoteControllerKey.KeyConnection),
            keyLogSink
        ) == true

        val rcType = DjiKeyValueHelper.getValueAsync(
            "RemoteController.KeyRemoteControllerType",
            KeyTools.createKey(RemoteControllerKey.KeyRemoteControllerType),
            keyLogSink
        )
        remoteControllerModel = rcType?.name ?: remoteControllerModel

        airLinkConnected = DjiKeyValueHelper.getValueAsync(
            "AirLink.KeyConnection",
            KeyTools.createKey(AirLinkKey.KeyConnection),
            keyLogSink
        ) == true

        productKeyConnection = DjiKeyValueHelper.getValueAsync(
            "Product.KeyConnection",
            KeyTools.createKey(ProductKey.KeyConnection),
            keyLogSink
        ) == true

        val pt = DjiKeyValueHelper.getValueAsync(
            "Product.KeyProductType",
            KeyTools.createKey(ProductKey.KeyProductType),
            keyLogSink
        )
        _productType.value = mapProductType(pt)

        val fcLinked = DjiKeyValueHelper.getValueAsync(
            "FlightController.KeyConnection",
            KeyTools.createKey(FlightControllerKey.KeyConnection, fcIndex),
            keyLogSink
        ) == true
        applyFcConnection(fcLinked)

        DjiKeyValueHelper.getValueAsync(
            "Battery.KeyChargeRemainingInPercent",
            KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent),
            keyLogSink
        )?.let { _batteryPercent.value = it }

        DjiKeyValueHelper.getValueAsync(
            "FlightController.KeyGPSSatelliteCount",
            KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount, fcIndex),
            keyLogSink
        )?.let { _gpsInfo.value = _gpsInfo.value.copy(satellitesCount = it) }

        if (airLinkConnected) {
            readAirLinkQuality()
        }
        if (flightControllerLinked) {
            readFcFirmwareAndFlightMode()
        }
        publishDiagnostics()
    }

    private fun startKeyPollBackoff() {
        keyPollJob?.cancel()
        keyPollJob = scope.launch {
            val delays = listOf(500L, 1_000L, 2_000L, 3_000L, 5_000L, 8_000L)
            for (delayMs in delays) {
                if (!productConnectedFlag) return@launch
                if (flightControllerLinked && airLinkConnected) return@launch
                delay(delayMs)
                if (!productConnectedFlag) return@launch
                emitLog("Connection backoff poll (delay=${delayMs}ms): FC=$flightControllerLinked air=$airLinkConnected")
                bootstrapAllKeys()
            }
        }
    }

    private fun unregisterKeyListeners() {
        if (!keyListenersActive) return
        keyListenersActive = false
        keyPollJob?.cancel()
        keyPollJob = null
        telemetryCollector.unregister()
        DjiMsdkSession.cancelKeyListeners(keyListenerOwner)
        emitLog("KeyManager listeners cancelled")
    }

    private fun mapProductType(productType: ProductType?): String {
        if (productType == null || productType == ProductType.UNKNOWN) {
            return "Unknown Aircraft"
        }
        val enumName = productType.name
        return when {
            enumName.contains("MINI_4", ignoreCase = true) -> "Mini 4 Pro"
            enumName.contains("MINI_3", ignoreCase = true) -> "Mini 3 Series"
            enumName.contains("MAVIC_3_ENTERPRISE", ignoreCase = true) -> "Mavic 3E"
            enumName.contains("MAVIC_3_THERMAL", ignoreCase = true) -> "Mavic 3T"
            enumName.contains("MAVIC_3_MULTISPECTRAL", ignoreCase = true) -> "Mavic 3M"
            enumName == "DJI_MAVIC_3" -> "Mavic 3"
            enumName.contains("MAVIC_3", ignoreCase = true) -> "Mavic 3 Series"
            enumName.contains("M30T", ignoreCase = true) -> "Matrice 30T"
            enumName.contains("M30", ignoreCase = true) -> "Matrice 30"
            enumName.contains("M300", ignoreCase = true) -> "Matrice 300 RTK"
            enumName.contains("M350", ignoreCase = true) -> "Matrice 350 RTK"
            else -> enumName
        }
    }

    private fun onProductConnectedInternal(id: Int) {
        productId = id
        productConnectedFlag = true
        registerKeyListeners()
        DjiMsdkSession.notifyProductConnected()
        publishDiagnostics()
    }

    private fun onProductDisconnectedInternal(id: Int) {
        emitWarn("Product disconnected productId=$id")
        productConnectedFlag = false
        productId = null
        flightControllerLinked = false
        remoteControllerLinked = false
        airLinkConnected = false
        productKeyConnection = false
        _isConnected.value = false
        _isTelemetryReady.value = false
        flightModeLabel = "UNKNOWN"
        unregisterKeyListeners()
        DjiMsdkSession.notifyProductDisconnected()
        publishDiagnostics()
    }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        suspendCoroutine { cont ->
            try {
                val maskedKey = if (appKey.length > 8) "${appKey.take(4)}...${appKey.takeLast(4)}" else "KEY_NOT_SET"
                emitLog("Initializing DJI Mobile SDK V5 (App Key: $maskedKey)...")
                SDKManager.getInstance().init(context, object : SDKManagerCallback {
                    override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                        recordSdkCallback("onInitProcess(${event?.name}, $totalProcess)")
                        if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                            sdkInitComplete = true
                            publishDiagnostics()
                            SDKManager.getInstance().registerApp()
                        }
                    }

                    override fun onRegisterSuccess() {
                        recordSdkCallback("onRegisterSuccess()")
                        _isRegistered.value = true
                        sdkRegistrationError = null
                        _errorState.value = null
                        publishDiagnostics()

                        DJINetworkManager.getInstance().addNetworkStatusListener { isAvailable ->
                            emitLog("DJINetworkManager: available=$isAvailable")
                            if (sdkInitComplete && isAvailable && !SDKManager.getInstance().isRegistered) {
                                SDKManager.getInstance().registerApp()
                            }
                        }
                        cont.resume(Unit)
                    }

                    override fun onRegisterFailure(error: IDJIError) {
                        recordSdkCallback("onRegisterFailure(${error.errorCode()})")
                        val errMsg = "DJI SDK Registration FAILED: ${error.description()} (errorCode: ${error.errorCode()})"
                        emitError(errMsg)
                        _isRegistered.value = false
                        sdkRegistrationError = errMsg
                        _errorState.value = errMsg
                        publishDiagnostics()
                        cont.resume(Unit)
                    }

                    override fun onProductConnect(productId: Int) {
                        recordSdkCallback("onProductConnect($productId) — RC/USB product session (aircraft may link later)")
                        onProductConnectedInternal(productId)
                    }

                    override fun onProductDisconnect(productId: Int) {
                        recordSdkCallback("onProductDisconnect($productId)")
                        onProductDisconnectedInternal(productId)
                    }

                    override fun onProductChanged(productId: Int) {
                        recordSdkCallback("onProductChanged($productId)")
                        unregisterKeyListeners()
                        onProductConnectedInternal(productId)
                    }

                    override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                        productDatabaseProgress = "$current / $total"
                        productDatabaseReady = total > 0L && current >= total
                        val pct = if (total > 0) (100 * current / total) else 0
                        recordSdkCallback("onDatabaseDownloadProgress($current/$total ~$pct%)")
                        if (!productDatabaseReady && total > 0) {
                            emitWarn("Product database downloading ($pct%). First run needs internet until complete.")
                        }
                        if (productDatabaseReady) {
                            emitLog("Product database download complete")
                        }
                        publishDiagnostics()
                    }
                })
            } catch (t: Throwable) {
                val errMsg = "DJI SDK initialization exception: ${t.message}"
                emitError(errMsg)
                _errorState.value = errMsg
                cont.resume(Unit)
            }
        }
    }

    override suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        unregisterKeyListeners()
        productConnectedFlag = false
        flightControllerLinked = false
        _isConnected.value = false
        _isTelemetryReady.value = false
        try {
            SDKManager.getInstance().destroy()
            emitLog("SDKManager destroyed")
        } catch (t: Throwable) {
            emitError("SDKManager destroy: ${t.message}")
        }
    }
}
