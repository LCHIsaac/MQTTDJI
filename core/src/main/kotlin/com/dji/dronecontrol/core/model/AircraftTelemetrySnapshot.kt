package com.dji.dronecontrol.core.model

/**
 * Live aircraft telemetry for in-app UI (not published over MQTT).
 */
data class AircraftTelemetrySnapshot(
    val flightState: FlightStateTelemetry = FlightStateTelemetry(),
    val battery: BatteryTelemetry = BatteryTelemetry(),
    val positionMotion: PositionMotionTelemetry = PositionMotionTelemetry(),
    val obstacle: ObstacleTelemetry = ObstacleTelemetry(),
    val linkController: LinkControllerTelemetry = LinkControllerTelemetry(),
    val lastUpdatedEpochMs: Long = 0L,
)

data class FlightStateTelemetry(
    val flightMode: String? = null,
    val isFlying: Boolean? = null,
    val motorsOn: Boolean? = null,
    val goHomeStatus: String? = null,
    val isHomeLocationSet: Boolean? = null,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val windWarning: String? = null,
    val windSpeedMps: Double? = null,
    val windDirectionDeg: Int? = null,
)

data class BatteryTelemetry(
    val chargePercent: Int? = null,
    val chargeRemainingMah: Int? = null,
    val voltageMv: Int? = null,
    val currentMa: Int? = null,
    val temperatureC: Double? = null,
    val cellCount: Int? = null,
    val cellVoltagesMv: List<Int> = emptyList(),
    val batteryConnected: Boolean = false,
)

data class PositionMotionTelemetry(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Double? = null,
    val gpsSatelliteCount: Int? = null,
    val gpsSignalLevel: String? = null,
    val gpsConstellation: String? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    val yawDeg: Double? = null,
    val velocityNorthMps: Double? = null,
    val velocityEastMps: Double? = null,
    val velocityDownMps: Double? = null,
    val ultrasonicHeightM: Double? = null,
)

data class ObstacleTelemetry(
    val forwardDistanceM: Double? = null,
    val backwardDistanceM: Double? = null,
    val leftDistanceM: Double? = null,
    val rightDistanceM: Double? = null,
    val upwardDistanceM: Double? = null,
    val downwardDistanceM: Double? = null,
    val overallAvoidanceEnabled: Boolean? = null,
    val horizontalAvoidanceEnabled: Boolean? = null,
    val upwardAvoidanceEnabled: Boolean? = null,
    val downwardAvoidanceEnabled: Boolean? = null,
    val visionPositioningEnabled: Boolean? = null,
    val precisionLandingEnabled: Boolean? = null,
    val obstacleAvoidanceType: String? = null,
    val perceptionActive: Boolean = false,
)

data class LinkControllerTelemetry(
    val airLinkConnected: Boolean = false,
    val signalQuality: Int? = null,
    val downLinkQuality: Int? = null,
    val upLinkQuality: Int? = null,
    val videoDataRateMbps: Double? = null,
    val stickLeftHorizontal: Int? = null,
    val stickLeftVertical: Int? = null,
    val stickRightHorizontal: Int? = null,
    val stickRightVertical: Int? = null,
    val rcBatteryPercent: Int? = null,
    val remoteControllerModel: String? = null,
    val productType: String? = null,
    val fcFirmwareVersion: String? = null,
)
