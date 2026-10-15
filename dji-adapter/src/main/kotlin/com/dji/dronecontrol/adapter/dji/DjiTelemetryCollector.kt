package com.dji.dronecontrol.adapter.dji

import com.dji.dronecontrol.core.model.AircraftTelemetrySnapshot
import com.dji.dronecontrol.core.model.BatteryTelemetry
import com.dji.dronecontrol.core.model.FlightStateTelemetry
import com.dji.dronecontrol.core.model.LinkControllerTelemetry
import com.dji.dronecontrol.core.model.ObstacleTelemetry
import com.dji.dronecontrol.core.model.PositionMotionTelemetry
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.GoHomeState
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleData
import dji.v5.manager.aircraft.perception.data.PerceptionInfo
import dji.v5.manager.aircraft.perception.listener.ObstacleDataListener
import dji.v5.manager.aircraft.perception.listener.PerceptionInformationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * KeyManager + PerceptionManager listeners for telemetry UI pages.
 */
class DjiTelemetryCollector(
    private val scope: CoroutineScope,
    private val keyLog: (String) -> Unit,
) {
    private val owner = Any()
    private val fcIndex = ComponentIndexType.LEFT_OR_MAIN
    private val batteryIndex = ComponentIndexType.LEFT_OR_MAIN

    private val _snapshot = MutableStateFlow(AircraftTelemetrySnapshot())
    val snapshot: StateFlow<AircraftTelemetrySnapshot> = _snapshot.asStateFlow()

    private var listenersActive = false
    private var perceptionListener: PerceptionInformationListener? = null
    private var obstacleListener: ObstacleDataListener? = null

    private var linkDiagProvider: () -> LinkControllerTelemetry = { LinkControllerTelemetry() }

    fun setLinkDiagnosticsProvider(provider: () -> LinkControllerTelemetry) {
        linkDiagProvider = provider
    }

    fun register() {
        if (listenersActive) return
        listenersActive = true
        try {
            val keyManager = KeyManager.getInstance()
            listenFlightState(keyManager)
            listenBattery(keyManager)
            listenPositionMotion(keyManager)
            listenRcAndAirLink(keyManager)
            registerPerception()
            scope.launch { bootstrapOnce(keyManager) }
        } catch (t: Throwable) {
            val msg = t.message ?: t.toString()
            if (!DjiMsdkSession.isBenignKeyError(msg)) {
                keyLog("Telemetry collector registration failed: $msg")
            }
        }
    }

    fun unregister() {
        if (!listenersActive) return
        listenersActive = false
        DjiMsdkSession.cancelKeyListeners(owner)
        try {
            perceptionListener?.let { PerceptionManager.getInstance().removePerceptionInformationListener(it) }
            obstacleListener?.let { PerceptionManager.getInstance().removeObstacleDataListener(it) }
        } catch (_: Throwable) {
        }
        perceptionListener = null
        obstacleListener = null
    }

    private fun listenFlightState(keyManager: KeyManager) {
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyFlightMode, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<FlightMode> {
                override fun onValueChange(oldValue: FlightMode?, newValue: FlightMode?) {
                    patchFlight { copy(flightMode = newValue?.name) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyIsFlying, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    patchFlight { copy(isFlying = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    patchFlight { copy(motorsOn = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyGoHomeStatus, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<GoHomeState> {
                override fun onValueChange(oldValue: GoHomeState?, newValue: GoHomeState?) {
                    patchFlight { copy(goHomeStatus = newValue?.name) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyIsHomeLocationSet, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    patchFlight { copy(isHomeLocationSet = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyHomeLocation, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<LocationCoordinate2D> {
                override fun onValueChange(oldValue: LocationCoordinate2D?, newValue: LocationCoordinate2D?) {
                    patchFlight {
                        copy(
                            homeLatitude = newValue?.latitude,
                            homeLongitude = newValue?.longitude,
                        )
                    }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyWindSpeed, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    patchFlight { copy(windSpeedMps = newValue?.toDouble()) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyWindWarning, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<dji.sdk.keyvalue.value.flightcontroller.WindWarning> {
                override fun onValueChange(
                    oldValue: dji.sdk.keyvalue.value.flightcontroller.WindWarning?,
                    newValue: dji.sdk.keyvalue.value.flightcontroller.WindWarning?,
                ) {
                    patchFlight { copy(windWarning = newValue?.name) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyWindDirection, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<dji.sdk.keyvalue.value.flightcontroller.WindDirection> {
                override fun onValueChange(
                    oldValue: dji.sdk.keyvalue.value.flightcontroller.WindDirection?,
                    newValue: dji.sdk.keyvalue.value.flightcontroller.WindDirection?,
                ) {
                    patchFlight { copy(windDirectionDeg = newValue?.value()) }
                }
            },
        )
    }

    private fun listenBattery(keyManager: KeyManager) {
        keyManager.listen(
            KeyTools.createKey(BatteryKey.KeyConnection, batteryIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    patchBattery { copy(batteryConnected = newValue == true) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent, batteryIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    patchBattery { copy(chargePercent = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(BatteryKey.KeyChargeRemaining, batteryIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    patchBattery { copy(chargeRemainingMah = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(BatteryKey.KeyVoltage, batteryIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    patchBattery { copy(voltageMv = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(BatteryKey.KeyCurrent, batteryIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    patchBattery { copy(currentMa = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(BatteryKey.KeyBatteryTemperature, batteryIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Double> {
                override fun onValueChange(oldValue: Double?, newValue: Double?) {
                    patchBattery { copy(temperatureC = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(BatteryKey.KeyNumberOfCells, batteryIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    patchBattery { copy(cellCount = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(BatteryKey.KeyCellVoltages, batteryIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<List<Int>> {
                override fun onValueChange(oldValue: List<Int>?, newValue: List<Int>?) {
                    patchBattery { copy(cellVoltagesMv = newValue ?: emptyList()) }
                }
            },
        )
    }

    private fun listenPositionMotion(keyManager: KeyManager) {
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<LocationCoordinate3D> {
                override fun onValueChange(oldValue: LocationCoordinate3D?, newValue: LocationCoordinate3D?) {
                    patchPosition {
                        copy(
                            latitude = newValue?.latitude,
                            longitude = newValue?.longitude,
                            altitudeM = newValue?.altitude,
                        )
                    }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    patchPosition { copy(gpsSatelliteCount = newValue) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<GPSSignalLevel> {
                override fun onValueChange(oldValue: GPSSignalLevel?, newValue: GPSSignalLevel?) {
                    patchPosition { copy(gpsSignalLevel = newValue?.name) }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Attitude> {
                override fun onValueChange(oldValue: Attitude?, newValue: Attitude?) {
                    patchPosition {
                        copy(
                            pitchDeg = newValue?.pitch,
                            rollDeg = newValue?.roll,
                            yawDeg = newValue?.yaw,
                        )
                    }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Velocity3D> {
                override fun onValueChange(oldValue: Velocity3D?, newValue: Velocity3D?) {
                    patchPosition {
                        copy(
                            velocityNorthMps = newValue?.x,
                            velocityEastMps = newValue?.y,
                            velocityDownMps = newValue?.z,
                        )
                    }
                }
            },
        )
        keyManager.listen(
            KeyTools.createKey(FlightControllerKey.KeyUltrasonicHeight, fcIndex),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    val meters = newValue?.takeIf { it > 0 }?.let { it / 1000.0 }
                    patchPosition { copy(ultrasonicHeightM = meters) }
                }
            },
        )
    }

    private fun listenRcAndAirLink(keyManager: KeyManager) {
        keyManager.listen(
            KeyTools.createKey(AirLinkKey.KeyVideoDataRate),
            owner,
            true,
            object : CommonCallbacks.KeyListener<Double> {
                override fun onValueChange(oldValue: Double?, newValue: Double?) {
                    patchLink { copy(videoDataRateMbps = newValue) }
                }
            },
        )
    }

    private fun registerPerception() {
        val manager = PerceptionManager.getInstance()
        val infoListener = object : PerceptionInformationListener {
            override fun onUpdate(info: PerceptionInfo) {
                patchObstacle {
                    copy(
                        perceptionActive = true,
                        overallAvoidanceEnabled = info.isOverallObstacleAvoidanceEnabled,
                        horizontalAvoidanceEnabled = info.isHorizontalObstacleAvoidanceEnabled,
                        upwardAvoidanceEnabled = info.isUpwardObstacleAvoidanceEnabled,
                        downwardAvoidanceEnabled = info.isDownwardObstacleAvoidanceEnabled,
                        visionPositioningEnabled = info.isVisionPositioningEnabled,
                        precisionLandingEnabled = info.isPrecisionLandingEnabled,
                        obstacleAvoidanceType = info.obstacleAvoidanceType?.name,
                    )
                }
            }
        }
        val obsListener = object : ObstacleDataListener {
            override fun onUpdate(data: ObstacleData) {
                val horizontal = data.horizontalObstacleDistance ?: emptyList()
                val sectors = mapHorizontalDistances(horizontal)
                patchObstacle {
                    copy(
                        perceptionActive = true,
                        forwardDistanceM = sectors.forward,
                        backwardDistanceM = sectors.backward,
                        leftDistanceM = sectors.left,
                        rightDistanceM = sectors.right,
                        upwardDistanceM = data.upwardObstacleDistance.takeIf { it > 0 }?.let { it / 1000.0 },
                        downwardDistanceM = data.downwardObstacleDistance.takeIf { it > 0 }?.let { it / 1000.0 },
                    )
                }
            }
        }
        perceptionListener = infoListener
        obstacleListener = obsListener
        manager.addPerceptionInformationListener(infoListener)
        manager.addObstacleDataListener(obsListener)
    }

    private data class HorizontalSectors(
        val forward: Double?,
        val backward: Double?,
        val left: Double?,
        val right: Double?,
    )

    private fun mapHorizontalDistances(horizontal: List<Int>): HorizontalSectors {
        if (horizontal.isEmpty()) {
            return HorizontalSectors(null, null, null, null)
        }
        fun at(index: Int): Double? {
            if (index < 0 || index >= horizontal.size) return null
            val raw = horizontal[index]
            if (raw <= 0) return null
            return raw / 1000.0
        }
        val n = horizontal.size
        return HorizontalSectors(
            forward = at(0),
            right = at(n / 4),
            backward = at(n / 2),
            left = at((3 * n) / 4),
        )
    }

    private suspend fun bootstrapOnce(keyManager: KeyManager) {
        // Initial getValue calls are best-effort; listeners will stream updates.
        DjiKeyValueHelper.getValueAsync(
            "FlightController.KeyFlightMode",
            KeyTools.createKey(FlightControllerKey.KeyFlightMode, fcIndex),
            keyLog,
        )?.let { mode: FlightMode -> patchFlight { copy(flightMode = mode.name) } }
        publishLinkFromProvider()
    }

    private fun publishLinkFromProvider() {
        val merged = linkDiagProvider().copy(
            videoDataRateMbps = _snapshot.value.linkController.videoDataRateMbps,
            stickLeftHorizontal = _snapshot.value.linkController.stickLeftHorizontal,
            stickLeftVertical = _snapshot.value.linkController.stickLeftVertical,
            stickRightHorizontal = _snapshot.value.linkController.stickRightHorizontal,
            stickRightVertical = _snapshot.value.linkController.stickRightVertical,
            rcBatteryPercent = _snapshot.value.linkController.rcBatteryPercent,
        )
        _snapshot.value = _snapshot.value.copy(
            linkController = merged,
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    fun refreshLinkFromDiagnostics() {
        publishLinkFromProvider()
    }

    private fun patchFlight(transform: FlightStateTelemetry.() -> FlightStateTelemetry) {
        _snapshot.value = _snapshot.value.copy(
            flightState = transform(_snapshot.value.flightState),
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    private fun patchBattery(transform: BatteryTelemetry.() -> BatteryTelemetry) {
        _snapshot.value = _snapshot.value.copy(
            battery = transform(_snapshot.value.battery),
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    private fun patchPosition(transform: PositionMotionTelemetry.() -> PositionMotionTelemetry) {
        _snapshot.value = _snapshot.value.copy(
            positionMotion = transform(_snapshot.value.positionMotion),
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    private fun patchObstacle(transform: ObstacleTelemetry.() -> ObstacleTelemetry) {
        _snapshot.value = _snapshot.value.copy(
            obstacle = transform(_snapshot.value.obstacle),
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }

    private fun patchLink(transform: LinkControllerTelemetry.() -> LinkControllerTelemetry) {
        _snapshot.value = _snapshot.value.copy(
            linkController = transform(_snapshot.value.linkController),
            lastUpdatedEpochMs = System.currentTimeMillis(),
        )
    }
}
