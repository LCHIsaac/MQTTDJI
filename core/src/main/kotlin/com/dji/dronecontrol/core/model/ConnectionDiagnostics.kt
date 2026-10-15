package com.dji.dronecontrol.core.model

/**
 * Live SDK / USB / air-link diagnostics for the dashboard and Connection page.
 */
data class ConnectionDiagnostics(
    val sdkRegistered: Boolean = false,
    val sdkInitComplete: Boolean = false,
    val sdkRegistrationError: String? = null,
    val productDatabaseReady: Boolean = false,
    val productDatabaseProgress: String = "—",
    val usbAccessoryDetected: Boolean = false,
    val usbAccessoryDescription: String = "—",
    /** Phone: USB accessory; RC Pro: built-in link label from host. */
    val usbRowLabel: String = "—",
    val productConnected: Boolean = false,
    val productId: Int? = null,
    val productType: String = "UNKNOWN",
    val productKeyConnection: Boolean = false,
    val remoteControllerLinked: Boolean = false,
    val remoteControllerModel: String = "—",
    val airLinkConnected: Boolean = false,
    val upLinkQuality: Int? = null,
    val downLinkQuality: Int? = null,
    val signalQuality: Int? = null,
    val flightControllerLinked: Boolean = false,
    val fcFirmwareVersion: String = "—",
    val flightMode: String = "UNKNOWN",
    val gpsSatelliteCount: Int = 0,
    val batteryPercent: Int = 0,
    val lastSdkCallback: String = "—",
    val lastUpdatedEpochMs: Long = System.currentTimeMillis(),
    val accessoryConflictHint: String =
        "If link stays waiting: force-stop DJI Fly (Settings → Apps). Only one app may use the RC USB accessory.",
)
