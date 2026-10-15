package com.dji.dronecontrol.core.telemetry

import com.dji.dronecontrol.core.model.ConnectionDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetrySurfaceTest {

    @Test
    fun flightStateWaiting_whenFcNotLinked() {
        val diag = ConnectionDiagnostics(flightControllerLinked = false)
        assertTrue(shouldShowTelemetryWaiting(TelemetrySurface.FLIGHT_STATE, diag))
    }

    @Test
    fun flightStateReady_whenFcLinked() {
        val diag = ConnectionDiagnostics(flightControllerLinked = true)
        assertFalse(shouldShowTelemetryWaiting(TelemetrySurface.FLIGHT_STATE, diag))
    }

    @Test
    fun linkWaiting_whenNoRcAirLinkOrProduct() {
        val diag = ConnectionDiagnostics(
            flightControllerLinked = true,
            remoteControllerLinked = false,
            airLinkConnected = false,
            productConnected = false,
        )
        assertTrue(shouldShowTelemetryWaiting(TelemetrySurface.LINK_CONTROLLER, diag))
    }

    @Test
    fun drawerDestination_mapsToTelemetrySurface() {
        assertEquals(TelemetrySurface.FLIGHT_STATE, telemetrySurfaceFor(AppNavDestination.FlightState))
        assertEquals(TelemetrySurface.BATTERY, telemetrySurfaceFor(AppNavDestination.BatteryStatus))
        assertNull(telemetrySurfaceFor(AppNavDestination.Controls))
    }
}
