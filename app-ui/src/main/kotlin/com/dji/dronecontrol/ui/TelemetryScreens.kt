package com.dji.dronecontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dji.dronecontrol.core.model.AircraftTelemetrySnapshot
import com.dji.dronecontrol.core.model.ConnectionDiagnostics
import com.dji.dronecontrol.core.telemetry.TelemetrySurface
import com.dji.dronecontrol.core.telemetry.formatCoord
import com.dji.dronecontrol.core.telemetry.formatTelemetry
import com.dji.dronecontrol.core.telemetry.shouldShowTelemetryWaiting

@Composable
fun TelemetryWaitingCard(
    surfaceLabel: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Waiting for $surfaceLabel", fontWeight = FontWeight.Bold)
            Text(
                "Link the aircraft on the Connection tab first. FC and sensor data appear here when the component is connected.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun TelemetryPageScaffold(
    title: String,
    surface: TelemetrySurface,
    diagnostics: ConnectionDiagnostics,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (shouldShowTelemetryWaiting(surface, diagnostics)) {
            TelemetryWaitingCard(surfaceLabel = title)
        } else {
            content()
        }
    }
}

@Composable
fun FlightStateTelemetryPage(
    telemetry: AircraftTelemetrySnapshot,
    diagnostics: ConnectionDiagnostics,
) {
    val t = telemetry.flightState
    TelemetryPageScaffold("Flight State", TelemetrySurface.FLIGHT_STATE, diagnostics) {
        TelemetryMetricsCard {
            TelemetryRow("Flight mode", formatTelemetry(t.flightMode))
            TelemetryRow("Flying", formatTelemetry(t.isFlying))
            TelemetryRow("Motors on", formatTelemetry(t.motorsOn))
            TelemetryRow("Go-home status", formatTelemetry(t.goHomeStatus))
            TelemetryRow("Home set", formatTelemetry(t.isHomeLocationSet))
            TelemetryRow("Home lat", formatCoord(t.homeLatitude))
            TelemetryRow("Home lon", formatCoord(t.homeLongitude))
            TelemetryRow("Wind warning", formatTelemetry(t.windWarning))
            TelemetryRow("Wind speed (m/s)", formatTelemetry(t.windSpeedMps))
            TelemetryRow("Wind direction", formatTelemetry(t.windDirectionDeg))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatteryStatusTelemetryPage(
    telemetry: AircraftTelemetrySnapshot,
    diagnostics: ConnectionDiagnostics,
) {
    val t = telemetry.battery
    TelemetryPageScaffold("Battery Status", TelemetrySurface.BATTERY, diagnostics) {
        TelemetryMetricsCard {
            TelemetryRow("Charge %", formatTelemetry(t.chargePercent))
            TelemetryRow("Remaining (mAh)", formatTelemetry(t.chargeRemainingMah))
            TelemetryRow("Voltage (mV)", formatTelemetry(t.voltageMv))
            TelemetryRow("Current (mA)", formatTelemetry(t.currentMa))
            TelemetryRow("Temperature (°C)", formatTelemetry(t.temperatureC))
            TelemetryRow("Cell count", formatTelemetry(t.cellCount))
            if (t.cellVoltagesMv.isNotEmpty()) {
                Text("Cell voltages (mV)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    t.cellVoltagesMv.forEachIndexed { index, mv ->
                        Text("C${index + 1}: $mv", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PositionMotionTelemetryPage(
    telemetry: AircraftTelemetrySnapshot,
    diagnostics: ConnectionDiagnostics,
) {
    val t = telemetry.positionMotion
    TelemetryPageScaffold("Position & Motion", TelemetrySurface.POSITION_MOTION, diagnostics) {
        TelemetryMetricsCard {
            TelemetryRow("Latitude", formatCoord(t.latitude))
            TelemetryRow("Longitude", formatCoord(t.longitude))
            TelemetryRow("Altitude (m)", formatTelemetry(t.altitudeM))
            TelemetryRow("GPS satellites", formatTelemetry(t.gpsSatelliteCount))
            TelemetryRow("GPS signal", formatTelemetry(t.gpsSignalLevel))
            TelemetryRow("Constellation", formatTelemetry(t.gpsConstellation))
            TelemetryRow("Pitch (°)", formatTelemetry(t.pitchDeg))
            TelemetryRow("Roll (°)", formatTelemetry(t.rollDeg))
            TelemetryRow("Yaw (°)", formatTelemetry(t.yawDeg))
            TelemetryRow("Vel north (m/s)", formatTelemetry(t.velocityNorthMps))
            TelemetryRow("Vel east (m/s)", formatTelemetry(t.velocityEastMps))
            TelemetryRow("Vel down (m/s)", formatTelemetry(t.velocityDownMps))
            TelemetryRow("Ultrasonic height (m)", formatTelemetry(t.ultrasonicHeightM))
        }
    }
}

@Composable
fun ObstacleSensingTelemetryPage(
    telemetry: AircraftTelemetrySnapshot,
    diagnostics: ConnectionDiagnostics,
    isPhoneHost: Boolean,
) {
    val t = telemetry.obstacle
    TelemetryPageScaffold("Obstacle Sensing", TelemetrySurface.OBSTACLE, diagnostics) {
        if (isPhoneHost) {
            Text(
                "Mini 4 Pro: obstacle avoidance type is reported below; full multi-direction distance bins may be limited compared to enterprise models.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        }
        TelemetryMetricsCard {
            TelemetryRow("Forward (m)", formatTelemetry(t.forwardDistanceM))
            TelemetryRow("Backward (m)", formatTelemetry(t.backwardDistanceM))
            TelemetryRow("Left (m)", formatTelemetry(t.leftDistanceM))
            TelemetryRow("Right (m)", formatTelemetry(t.rightDistanceM))
            TelemetryRow("Up (m)", formatTelemetry(t.upwardDistanceM))
            TelemetryRow("Down (m)", formatTelemetry(t.downwardDistanceM))
            TelemetryRow("Overall avoidance", formatTelemetry(t.overallAvoidanceEnabled))
            TelemetryRow("Horizontal avoidance", formatTelemetry(t.horizontalAvoidanceEnabled))
            TelemetryRow("Upward avoidance", formatTelemetry(t.upwardAvoidanceEnabled))
            TelemetryRow("Downward avoidance", formatTelemetry(t.downwardAvoidanceEnabled))
            TelemetryRow("Vision positioning", formatTelemetry(t.visionPositioningEnabled))
            TelemetryRow("Precision landing", formatTelemetry(t.precisionLandingEnabled))
            TelemetryRow("Avoidance type", formatTelemetry(t.obstacleAvoidanceType))
        }
    }
}

@Composable
fun LinkControllerTelemetryPage(
    telemetry: AircraftTelemetrySnapshot,
    diagnostics: ConnectionDiagnostics,
) {
    val t = telemetry.linkController
    TelemetryPageScaffold("Link & Controller", TelemetrySurface.LINK_CONTROLLER, diagnostics) {
        TelemetryMetricsCard {
            TelemetryRow("Air link", formatTelemetry(t.airLinkConnected))
            TelemetryRow("Signal quality", formatTelemetry(t.signalQuality))
            TelemetryRow("Downlink quality", formatTelemetry(t.downLinkQuality))
            TelemetryRow("Uplink quality", formatTelemetry(t.upLinkQuality))
            TelemetryRow("Video data rate", formatTelemetry(t.videoDataRateMbps))
            TelemetryRow("Stick L H", formatTelemetry(t.stickLeftHorizontal))
            TelemetryRow("Stick L V", formatTelemetry(t.stickLeftVertical))
            TelemetryRow("Stick R H", formatTelemetry(t.stickRightHorizontal))
            TelemetryRow("Stick R V", formatTelemetry(t.stickRightVertical))
            TelemetryRow("RC battery %", formatTelemetry(t.rcBatteryPercent))
            TelemetryRow("RC model", formatTelemetry(t.remoteControllerModel ?: diagnostics.remoteControllerModel))
            TelemetryRow("Product type", formatTelemetry(t.productType ?: diagnostics.productType))
            TelemetryRow("FC firmware", formatTelemetry(t.fcFirmwareVersion ?: diagnostics.fcFirmwareVersion))
        }
    }
}

@Composable
private fun TelemetryMetricsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
