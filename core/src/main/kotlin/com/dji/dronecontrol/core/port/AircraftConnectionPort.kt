package com.dji.dronecontrol.core.port

import com.dji.dronecontrol.core.model.AircraftGpsInfo
import com.dji.dronecontrol.core.model.AircraftTelemetrySnapshot
import com.dji.dronecontrol.core.model.ConnectionDiagnostics
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain port for aircraft connection, health, battery, and telemetry.
 * Pure Kotlin interface, no DJI imports.
 */
interface AircraftConnectionPort {
    val isConnected: StateFlow<Boolean>
    val isRegistered: StateFlow<Boolean>
    val isTelemetryReady: StateFlow<Boolean>
    val productType: StateFlow<String>
    val batteryPercent: StateFlow<Int>
    val gpsInfo: StateFlow<AircraftGpsInfo>
    val errorState: StateFlow<String?>

    /** SDK registration, USB (phone), product link, and last SDKManager callback. */
    val connectionDiagnostics: StateFlow<ConnectionDiagnostics>

    /** Live KeyManager / perception metrics for telemetry UI pages. */
    val aircraftTelemetry: StateFlow<AircraftTelemetrySnapshot>

    /** Phone host: update USB accessory scan results for the diagnostics card. */
    fun updateUsbAccessoryStatus(detected: Boolean, description: String) {}

    /** Re-read DJI keys and refresh [connectionDiagnostics] (Connection page Refresh). */
    fun refreshConnectionCheck() {}

    suspend fun connect()
    suspend fun disconnect()
}
