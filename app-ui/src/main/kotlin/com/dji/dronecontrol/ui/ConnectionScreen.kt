package com.dji.dronecontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dji.dronecontrol.core.model.ConnectionDiagnostics
import com.dji.dronecontrol.core.model.ConnectionStepStatus
import com.dji.dronecontrol.core.model.MqttLinkDiagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionChecklistView(
    isPhoneHost: Boolean,
    connectionDiagnostics: ConnectionDiagnostics,
    flightMode: String,
    batteryPercent: Int,
    gpsSatellites: Int,
    mqttDiagnostics: MqttLinkDiagnostics,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val updated = formatTime(connectionDiagnostics.lastUpdatedEpochMs)

    val usbStatus = when {
        !isPhoneHost -> ConnectionStepStatus.OK
        connectionDiagnostics.usbAccessoryDetected -> ConnectionStepStatus.OK
        else -> ConnectionStepStatus.WAITING
    }
    val usbHint = when {
        !isPhoneHost -> "RC Pro uses the built-in radio link (no USB accessory row)."
        connectionDiagnostics.usbAccessoryDetected -> "USB accessory detected."
        else -> "Plug RC-N2 into the phone via USB; grant USB permission when prompted."
    }
    val usbValue = if (isPhoneHost) {
        connectionDiagnostics.usbRowLabel
    } else {
        "Built-in (RC Pro)"
    }

    val rcStatus = when {
        connectionDiagnostics.remoteControllerLinked -> ConnectionStepStatus.OK
        connectionDiagnostics.productConnected -> ConnectionStepStatus.WAITING
        else -> ConnectionStepStatus.WAITING
    }
    val rcHint = when {
        connectionDiagnostics.remoteControllerLinked -> "Remote controller link is up."
        else -> "Power on the RC; wait for USB session or use Refresh after pairing."
    }

    val sdkStatus = when {
        connectionDiagnostics.sdkRegistrationError != null -> ConnectionStepStatus.ERROR
        connectionDiagnostics.sdkRegistered && connectionDiagnostics.sdkInitComplete -> ConnectionStepStatus.OK
        else -> ConnectionStepStatus.WAITING
    }
    val sdkHint = when {
        connectionDiagnostics.sdkRegistrationError != null ->
            connectionDiagnostics.sdkRegistrationError!!
        !connectionDiagnostics.productDatabaseReady ->
            "Product DB: ${connectionDiagnostics.productDatabaseProgress}. First launch needs internet."
        else -> "SDK initialized and registered."
    }

    val airStatus = when {
        connectionDiagnostics.airLinkConnected && connectionDiagnostics.productConnected -> ConnectionStepStatus.OK
        connectionDiagnostics.productConnected -> ConnectionStepStatus.WAITING
        else -> ConnectionStepStatus.WAITING
    }
    val airHint = when {
        !connectionDiagnostics.airLinkConnected && connectionDiagnostics.productConnected ->
            "RC is linked but aircraft O4 air link is not up. Pair RC to drone in DJI Fly, then force-stop DJI Fly."
        connectionDiagnostics.airLinkConnected ->
            "Air link connected (uplink=${connectionDiagnostics.upLinkQuality ?: "?"}, signal=${connectionDiagnostics.signalQuality ?: "?"})."
        else -> "Connect aircraft and wait for product session."
    }

    val fcStatus = when {
        connectionDiagnostics.flightControllerLinked -> ConnectionStepStatus.OK
        connectionDiagnostics.airLinkConnected -> ConnectionStepStatus.WAITING
        connectionDiagnostics.productConnected -> ConnectionStepStatus.WAITING
        else -> ConnectionStepStatus.WAITING
    }
    val fcHint = when {
        connectionDiagnostics.flightControllerLinked ->
            "Flight controller linked. Mode: $flightMode, battery: $batteryPercent%, sats: $gpsSatellites."
        connectionDiagnostics.productConnected && !connectionDiagnostics.airLinkConnected ->
            "FC stays offline until air link is up — this is expected if the drone is not linked to the RC."
        else -> "Power on aircraft and ensure RC↔aircraft link."
    }

    val mqttStatus = when (mqttDiagnostics.connectionStateLabel) {
        "connected" -> if (mqttDiagnostics.subscribedToCommandTopic) ConnectionStepStatus.OK else ConnectionStepStatus.WAITING
        "error" -> ConnectionStepStatus.ERROR
        "connecting" -> ConnectionStepStatus.WAITING
        else -> ConnectionStepStatus.WAITING
    }
    val mqttHint = when {
        mqttDiagnostics.lastError != null -> mqttDiagnostics.lastError!!
        mqttDiagnostics.connectionStateLabel == "connected" && !mqttDiagnostics.subscribedToCommandTopic ->
            "Connected but not subscribed to command topic yet."
        else -> "Configure broker in MQTT tab and connect (or enable auto-connect on launch)."
    }

    val diagnosticsText = buildConnectionDiagnosticsText(
        isPhoneHost = isPhoneHost,
        d = connectionDiagnostics,
        flightMode = flightMode,
        batteryPercent = batteryPercent,
        gpsSatellites = gpsSatellites,
        mqtt = mqttDiagnostics,
    )

    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Last updated: $updated", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh")
                    }
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(diagnosticsText))
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy diagnostics")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                connectionDiagnostics.accessoryConflictHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.height(12.dp))

            ConnectionStepRow(
                title = "a. USB / Remote controller",
                status = if (!isPhoneHost && connectionDiagnostics.remoteControllerLinked) ConnectionStepStatus.OK else usbStatus,
                detail = "$usbValue · RC KeyConnection=${connectionDiagnostics.remoteControllerLinked} (${connectionDiagnostics.remoteControllerModel})",
                hint = if (!isPhoneHost) rcHint else "$usbHint $rcHint"
            )
            ConnectionStepRow(
                title = "b. DJI SDK",
                status = sdkStatus,
                detail = "init=${connectionDiagnostics.sdkInitComplete} registered=${connectionDiagnostics.sdkRegistered} dbReady=${connectionDiagnostics.productDatabaseReady}",
                hint = sdkHint
            )
            ConnectionStepRow(
                title = "c. Drone / air link",
                status = airStatus,
                detail = "productId=${connectionDiagnostics.productId} type=${connectionDiagnostics.productType} productKeyConn=${connectionDiagnostics.productKeyConnection} airLink=${connectionDiagnostics.airLinkConnected}",
                hint = airHint
            )
            ConnectionStepRow(
                title = "d. Flight controller",
                status = fcStatus,
                detail = "FC linked=${connectionDiagnostics.flightControllerLinked} fw=${connectionDiagnostics.fcFirmwareVersion} mode=$flightMode batt=$batteryPercent% sats=$gpsSatellites",
                hint = fcHint
            )
            ConnectionStepRow(
                title = "e. MQTT",
                status = mqttStatus,
                detail = "${mqttDiagnostics.brokerEndpoint} · ${mqttDiagnostics.connectionStateLabel} · topic=${mqttDiagnostics.commandTopic} subscribed=${mqttDiagnostics.subscribedToCommandTopic}",
                hint = mqttHint +
                    formatMqttTimes(mqttDiagnostics)
            )

            Spacer(Modifier.height(16.dp))
            Text("Last SDK callback: ${connectionDiagnostics.lastSdkCallback}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ConnectionStepRow(
    title: String,
    status: ConnectionStepStatus,
    detail: String,
    hint: String,
) {
    val label = when (status) {
        ConnectionStepStatus.OK -> "OK"
        ConnectionStepStatus.WAITING -> "Waiting"
        ConnectionStepStatus.ERROR -> "Error"
    }
    val color = when (status) {
        ConnectionStepStatus.OK -> MaterialTheme.colorScheme.primary
        ConnectionStepStatus.WAITING -> MaterialTheme.colorScheme.tertiary
        ConnectionStepStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Text(detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            if (status != ConnectionStepStatus.OK) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMs))
}

private fun formatMqttTimes(m: MqttLinkDiagnostics): String {
    val inT = m.lastMessageInEpochMs?.let { " last in: ${formatTime(it)}" } ?: ""
    val outT = m.lastMessageOutEpochMs?.let { " last out: ${formatTime(it)}" } ?: ""
    return inT + outT
}

fun buildConnectionDiagnosticsText(
    isPhoneHost: Boolean,
    d: ConnectionDiagnostics,
    flightMode: String,
    batteryPercent: Int,
    gpsSatellites: Int,
    mqtt: MqttLinkDiagnostics,
): String = buildString {
    appendLine("=== DJI Drone MQTT Bridge — Connection diagnostics ===")
    appendLine("Updated: ${formatTime(d.lastUpdatedEpochMs)}")
    appendLine()
    appendLine("[USB/RC] phone=$isPhoneHost usb=${d.usbRowLabel} rcLinked=${d.remoteControllerLinked} rcModel=${d.remoteControllerModel}")
    appendLine("[SDK] init=${d.sdkInitComplete} registered=${d.sdkRegistered} db=${d.productDatabaseProgress} ready=${d.productDatabaseReady}")
    d.sdkRegistrationError?.let { appendLine("  regError: $it") }
    appendLine("[Air] productId=${d.productId} type=${d.productType} productKey=${d.productKeyConnection} air=${d.airLinkConnected} uplink=${d.upLinkQuality} down=${d.downLinkQuality} signal=${d.signalQuality}")
    appendLine("[FC] linked=${d.flightControllerLinked} fw=${d.fcFirmwareVersion} mode=$flightMode battery=$batteryPercent% sats=$gpsSatellites")
    appendLine("[MQTT] ${mqtt.brokerEndpoint} state=${mqtt.connectionStateLabel} topic=${mqtt.commandTopic} sub=${mqtt.subscribedToCommandTopic}")
    mqtt.lastError?.let { appendLine("  mqttError: $it") }
    appendLine("Last SDK callback: ${d.lastSdkCallback}")
    appendLine(d.accessoryConflictHint)
}
