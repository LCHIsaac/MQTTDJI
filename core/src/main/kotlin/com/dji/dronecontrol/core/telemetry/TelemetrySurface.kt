package com.dji.dronecontrol.core.telemetry

import com.dji.dronecontrol.core.model.ConnectionDiagnostics

enum class TelemetrySurface {
    FLIGHT_STATE,
    BATTERY,
    POSITION_MOTION,
    OBSTACLE,
    LINK_CONTROLLER,
}

enum class AppNavDestination(val label: String, val section: NavSection) {
    Controls("Controls", NavSection.OPS),
    Media("Media", NavSection.OPS),
    Connection("Connection", NavSection.OPS),
    Mqtt("MQTT", NavSection.OPS),
    MqttLog("MQTT Log", NavSection.OPS),
    AppLogs("App Logs", NavSection.OPS),
    FlightState("Flight State", NavSection.TELEMETRY),
    BatteryStatus("Battery Status", NavSection.TELEMETRY),
    PositionMotion("Position & Motion", NavSection.TELEMETRY),
    ObstacleSensing("Obstacle Sensing", NavSection.TELEMETRY),
    LinkController("Link & Controller", NavSection.TELEMETRY),
}

enum class NavSection(val title: String) {
    OPS("Ops"),
    TELEMETRY("Telemetry"),
}

fun shouldShowTelemetryWaiting(surface: TelemetrySurface, diagnostics: ConnectionDiagnostics): Boolean {
    return when (surface) {
        TelemetrySurface.FLIGHT_STATE,
        TelemetrySurface.POSITION_MOTION,
        TelemetrySurface.OBSTACLE,
        -> !diagnostics.flightControllerLinked

        TelemetrySurface.BATTERY ->
            !diagnostics.flightControllerLinked && !diagnostics.productConnected

        TelemetrySurface.LINK_CONTROLLER ->
            !diagnostics.remoteControllerLinked &&
                !diagnostics.airLinkConnected &&
                !diagnostics.productConnected
    }
}

fun telemetrySurfaceFor(destination: AppNavDestination): TelemetrySurface? = when (destination) {
    AppNavDestination.FlightState -> TelemetrySurface.FLIGHT_STATE
    AppNavDestination.BatteryStatus -> TelemetrySurface.BATTERY
    AppNavDestination.PositionMotion -> TelemetrySurface.POSITION_MOTION
    AppNavDestination.ObstacleSensing -> TelemetrySurface.OBSTACLE
    AppNavDestination.LinkController -> TelemetrySurface.LINK_CONTROLLER
    else -> null
}
