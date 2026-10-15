package com.dji.dronecontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dji.dronecontrol.core.log.LogEntry
import com.dji.dronecontrol.core.log.LogLevel
import com.dji.dronecontrol.core.model.*
import com.dji.dronecontrol.core.port.MediaLibraryPort
import com.dji.dronecontrol.core.port.MqttConnectionState
import com.dji.dronecontrol.core.telemetry.AppNavDestination
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    hostBadge: String,
    isPhoneHost: Boolean,
    aircraftIdExample: String,
    djiAppKey: String,
    brokerConfig: MqttBrokerConfig,
    mqttState: MqttConnectionState,
    mqttLinkDiagnostics: MqttLinkDiagnostics,
    aircraftConnected: Boolean,
    isRegistered: Boolean,
    isTelemetryReady: Boolean,
    errorState: String?,
    connectionDiagnostics: ConnectionDiagnostics,
    aircraftTelemetry: AircraftTelemetrySnapshot,
    productType: String,
    batteryPercent: Int,
    flightMode: String,
    isFlying: Boolean,
    isRecording: Boolean,
    isStreaming: Boolean,
    gpsInfo: AircraftGpsInfo,
    mqttLogs: List<String>,
    appLogs: List<LogEntry>,
    autoConnectMqttOnLaunch: Boolean,
    streamSession: StreamSessionState,
    appVersion: String = "v1.0.17 (18)",
    lastPhotoDownloadUrl: String? = null,
    lastPhotoLocalPath: String? = null,
    mediaLibrary: MediaLibraryPort? = null,
    onUploadPhotoLink: (suspend (localPath: String, fileIndex: Int) -> Result<String>)? = null,
    onUploadVideoLink: (suspend (localPath: String, fileIndex: Int) -> Result<String>)? = null,
    onConfigChange: (MqttBrokerConfig) -> Unit,
    onPickMqttPkcs12: () -> Unit,
    onPickMqttCaCert: () -> Unit,
    mtlsPkcs12Password: String,
    onMtlsPkcs12PasswordChange: (String) -> Unit,
    onStartAgoraStreamTest: (channel: String, optionalToken: String?) -> Unit,
    streamTokenSecret: String,
    onStreamTokenSecretChange: (String) -> Unit,
    onStopStream: () -> Unit,
    onSaveMqttSettings: () -> Unit,
    onAutoConnectMqttChange: (Boolean) -> Unit,
    onConnectMqtt: () -> Unit,
    onDisconnectMqtt: () -> Unit,
    onRefreshConnection: () -> Unit,
    onExecuteCommand: (String, Map<String, Any?>) -> Unit,
    onClearMqttLogs: () -> Unit,
    onClearAppLogs: () -> Unit
) {
    var selectedDestination by remember { mutableStateOf(AppNavDestination.Controls) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppNavigationDrawerSheet(
                selected = selectedDestination,
                onDestinationSelected = { selectedDestination = it },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DJI Drone MQTT Bridge", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = hostBadge,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = appVersion,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    // SDK Registration Badge
                    val userVisibleError = errorState?.takeUnless {
                        it.contains("REQUEST_HANDLER_NOT_FOUND", ignoreCase = true)
                    }
                    val regText = if (isRegistered) "SDK Reg OK" else if (userVisibleError != null) "SDK Reg Failed" else "SDK Reg Pending"
                    val regColor = if (isRegistered) Color(0xFF4CAF50) else if (userVisibleError != null) Color(0xFFE53935) else Color(0xFFFF9800)
                    StatusBadge(text = regText, color = regColor)
                    Spacer(modifier = Modifier.width(6.dp))

                    // Aircraft status badge
                    val droneText = when {
                        aircraftConnected && isTelemetryReady -> "$productType (Ready)"
                        aircraftConnected -> "$productType (Telemetry Sync...)"
                        isRegistered && connectionDiagnostics.productConnected -> "Product linked (FC sync...)"
                        isRegistered -> "Awaiting aircraft link"
                        else -> "Drone Offline"
                    }
                    val droneColor = when {
                        aircraftConnected && isTelemetryReady -> Color(0xFF4CAF50)
                        aircraftConnected || connectionDiagnostics.productConnected -> Color(0xFFFF9800)
                        isRegistered -> Color(0xFFFF9800)
                        else -> Color(0xFFE53935)
                    }
                    StatusBadge(text = droneText, color = droneColor)
                    Spacer(modifier = Modifier.width(6.dp))

                    // MQTT status badge
                    val mqttText = when (mqttState) {
                        is MqttConnectionState.Connected -> "MQTT Online"
                        is MqttConnectionState.Connecting -> "MQTT Connecting..."
                        is MqttConnectionState.Error -> "MQTT Error"
                        is MqttConnectionState.Disconnected -> "MQTT Offline"
                    }
                    val mqttColor = when (mqttState) {
                        is MqttConnectionState.Connected -> Color(0xFF4CAF50)
                        is MqttConnectionState.Connecting -> Color(0xFFFF9800)
                        is MqttConnectionState.Error -> Color(0xFFE53935)
                        is MqttConnectionState.Disconnected -> Color.Gray
                    }
                    StatusBadge(text = mqttText, color = mqttColor)
                    Spacer(modifier = Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(12.dp)
            ) {
                when (selectedDestination) {
                    AppNavDestination.Connection -> ConnectionChecklistView(
                        isPhoneHost = isPhoneHost,
                        connectionDiagnostics = connectionDiagnostics,
                        flightMode = flightMode,
                        batteryPercent = batteryPercent,
                        gpsSatellites = gpsInfo.satellitesCount,
                        mqttDiagnostics = mqttLinkDiagnostics,
                        onRefresh = onRefreshConnection,
                        modifier = Modifier.fillMaxSize()
                    )
                    AppNavDestination.Media -> {
                        val library = mediaLibrary
                        val uploadPhoto = onUploadPhotoLink
                        val uploadVideo = onUploadVideoLink
                        if (library != null && uploadPhoto != null && uploadVideo != null) {
                            MediaLibraryScreen(
                                aircraftReady = aircraftConnected && isRegistered,
                                mediaLibrary = library,
                                onOpenConnection = { selectedDestination = AppNavDestination.Connection },
                                onUploadPhoto = uploadPhoto,
                                onUploadVideo = uploadVideo,
                            )
                        } else {
                            Text("Media library not available", modifier = Modifier.padding(16.dp))
                        }
                    }
                    AppNavDestination.Controls -> DashboardView(
                        djiAppKey = djiAppKey,
                        aircraftConnected = aircraftConnected,
                        isRegistered = isRegistered,
                        isTelemetryReady = isTelemetryReady,
                        errorState = errorState,
                        connectionDiagnostics = connectionDiagnostics,
                        productType = productType,
                        batteryPercent = batteryPercent,
                        flightMode = flightMode,
                        isFlying = isFlying,
                        isRecording = isRecording,
                        isStreaming = isStreaming,
                        gpsInfo = gpsInfo,
                        streamSession = streamSession,
                        appVersion = appVersion,
                        lastPhotoDownloadUrl = lastPhotoDownloadUrl,
                        lastPhotoLocalPath = lastPhotoLocalPath,
                        onExecuteCommand = onExecuteCommand,
                        onStartAgoraStreamTest = onStartAgoraStreamTest,
                        onStopStream = onStopStream,
                    )
                    AppNavDestination.Mqtt -> SettingsView(
                        djiAppKey = djiAppKey,
                        aircraftIdExample = aircraftIdExample,
                        config = brokerConfig,
                        mqttState = mqttState,
                        autoConnectOnLaunch = autoConnectMqttOnLaunch,
                        onConfigChange = onConfigChange,
                        onSaveSettings = onSaveMqttSettings,
                        onAutoConnectChange = onAutoConnectMqttChange,
                        onConnectMqtt = onConnectMqtt,
                        onDisconnectMqtt = onDisconnectMqtt,
                        onPickMqttPkcs12 = onPickMqttPkcs12,
                        onPickMqttCaCert = onPickMqttCaCert,
                        mtlsPkcs12Password = mtlsPkcs12Password,
                        onMtlsPkcs12PasswordChange = onMtlsPkcs12PasswordChange,
                        streamTokenSecret = streamTokenSecret,
                        onStreamTokenSecretChange = onStreamTokenSecretChange,
                    )
                    AppNavDestination.MqttLog -> MqttConsoleView(
                        logs = mqttLogs,
                        onClearLogs = onClearMqttLogs
                    )
                    AppNavDestination.AppLogs -> AppLogsView(
                        logs = appLogs,
                        onClearLogs = onClearAppLogs
                    )
                    AppNavDestination.FlightState -> FlightStateTelemetryPage(aircraftTelemetry, connectionDiagnostics)
                    AppNavDestination.BatteryStatus -> BatteryStatusTelemetryPage(aircraftTelemetry, connectionDiagnostics)
                    AppNavDestination.PositionMotion -> PositionMotionTelemetryPage(aircraftTelemetry, connectionDiagnostics)
                    AppNavDestination.ObstacleSensing -> ObstacleSensingTelemetryPage(aircraftTelemetry, connectionDiagnostics, isPhoneHost)
                    AppNavDestination.LinkController -> LinkControllerTelemetryPage(aircraftTelemetry, connectionDiagnostics)
                }
            }
    }
    }
}

@Composable
fun StatusBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, shape = RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun DjiAppKeyCard(appKey: String, modifier: Modifier = Modifier) {
    var isRevealed by remember { mutableStateOf(false) }
    val isUnset = appKey.isBlank() || appKey == "YOUR_DJI_APP_KEY_HERE"

    if (isUnset) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        "⚠️ App Key not set",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 14.sp
                    )
                    Text(
                        "DJI SDK will fail registration without a registered DJI App Key. Set DJI_APP_KEY in local.properties, gradle.properties, or env before assembling APK.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    } else {
        val maskedDisplay = if (appKey.length > 8) {
            "${appKey.take(4)}••••••••${appKey.takeLast(4)}"
        } else {
            "••••••••"
        }
        val displayedKey = if (isRevealed) appKey else maskedDisplay

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable { isRevealed = !isRevealed },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "DJI App Key",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "(tap to ${if (isRevealed) "mask" else "reveal"})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                    Text(
                        text = displayedKey,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { isRevealed = !isRevealed },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isRevealed) "Hide App Key" else "Reveal App Key",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardView(
    djiAppKey: String,
    aircraftConnected: Boolean,
    isRegistered: Boolean,
    isTelemetryReady: Boolean,
    errorState: String?,
    connectionDiagnostics: ConnectionDiagnostics,
    productType: String,
    batteryPercent: Int,
    flightMode: String,
    isFlying: Boolean,
    isRecording: Boolean,
    isStreaming: Boolean,
    gpsInfo: AircraftGpsInfo,
    streamSession: StreamSessionState,
    appVersion: String = "v1.0.17 (18)",
    lastPhotoDownloadUrl: String? = null,
    lastPhotoLocalPath: String? = null,
    onExecuteCommand: (String, Map<String, Any?>) -> Unit,
    onStartAgoraStreamTest: (channel: String, optionalToken: String?) -> Unit,
    onStopStream: () -> Unit,
) {
    var streamUrlInput by remember { mutableStateOf("rtmp://192.168.1.100:1935/live/drone01") }

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Left Column: Telemetry & Safety
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Aircraft Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // DJI App Key verification card
                DjiAppKeyCard(appKey = djiAppKey, modifier = Modifier.padding(bottom = 8.dp))

                val userVisibleError = errorState?.takeUnless {
                    it.contains("REQUEST_HANDLER_NOT_FOUND", ignoreCase = true)
                }
                if (userVisibleError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "SDK Error: $userVisibleError",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                StreamCard(
                    session = streamSession,
                    onStartAgoraStream = onStartAgoraStreamTest,
                    onStopStream = onStopStream,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text("Connection diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                TelemetryRow(label = "SDK registered", value = if (connectionDiagnostics.sdkRegistered) "YES" else "NO")
                TelemetryRow(label = "USB accessory", value = connectionDiagnostics.usbAccessoryDescription)
                TelemetryRow(
                    label = "Product connected",
                    value = if (connectionDiagnostics.productConnected) {
                        "YES (id=${connectionDiagnostics.productId ?: "?"})"
                    } else {
                        "NO"
                    }
                )
                TelemetryRow(label = "RC KeyConnection", value = if (connectionDiagnostics.remoteControllerLinked) "LINKED" else "NOT LINKED")
                TelemetryRow(label = "AirLink", value = if (connectionDiagnostics.airLinkConnected) "LINKED" else "NOT LINKED")
                TelemetryRow(label = "FC KeyConnection", value = if (connectionDiagnostics.flightControllerLinked) "LINKED" else "NOT LINKED")
                TelemetryRow(label = "Product type", value = connectionDiagnostics.productType)
                TelemetryRow(label = "Last SDK callback", value = connectionDiagnostics.lastSdkCallback)

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                TelemetryRow(label = "App Build", value = appVersion)
                TelemetryRow(label = "SDK Registered", value = if (isRegistered) "YES" else "NO / PENDING")
                TelemetryRow(label = "Hardware Link", value = if (aircraftConnected) "CONNECTED" else "OFFLINE")
                TelemetryRow(label = "Telemetry Sync", value = if (isTelemetryReady) "LIVE READY" else "AWAITING SYNC")
                TelemetryRow(label = "Model", value = productType)
                TelemetryRow(label = "Battery", value = "$batteryPercent%")
                TelemetryRow(label = "Flight Mode", value = flightMode)
                TelemetryRow(label = "In Air", value = if (isFlying) "YES (Flying)" else "NO (Grounded)")
                TelemetryRow(label = "Satellites", value = "${gpsInfo.satellitesCount} Sats")
                TelemetryRow(label = "Altitude", value = "%.1f m".format(gpsInfo.altitudeM))
                TelemetryRow(label = "GPS Lat/Lon", value = "%.5f, %.5f".format(gpsInfo.latitude, gpsInfo.longitude))

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text("Safety Reminders", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                Text(
                    "• RC Physical Sticks always retain priority override.\n• Maintain Visual Line of Sight (VLOS) at all times.\n• Virtual Stick velocity mode timeout: 500ms safety window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        // Right Column: Smoke Test Controls (Same CommandRouter pipeline as MQTT)
        Card(
            modifier = Modifier
                .weight(1.3f)
                .fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Smoke Test Controls (CommandRouter)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Simulates MQTT commands hitting the unified CommandRouter port", style = MaterialTheme.typography.bodySmall)

                Spacer(modifier = Modifier.height(12.dp))

                // Flight Actions
                Text("Flight Controls", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onExecuteCommand("flight.takeoff", mapOf("target_height_m" to 1.5)) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Takeoff")
                    }
                    Button(
                        onClick = { onExecuteCommand("flight.land", emptyMap()) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Land")
                    }
                    Button(
                        onClick = { onExecuteCommand("flight.rth", emptyMap()) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("RTH")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onExecuteCommand("flight.hover", emptyMap()) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Hover")
                    }
                    Button(
                        onClick = { onExecuteCommand("flight.emergency_stop", emptyMap()) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Emergency Stop")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Direction (velocity ±0.5 m/s, 1 s)", style = MaterialTheme.typography.labelMedium)
                val moveVel = 0.5
                val moveDur = 1000
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        OutlinedButton(
                            onClick = {
                                onExecuteCommand(
                                    "flight.move",
                                    mapOf("mode" to "velocity", "vx" to moveVel, "vy" to 0.0, "vz" to 0.0, "yaw_rate" to 0.0, "duration_ms" to moveDur),
                                )
                            },
                        ) { Text("Forward") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        OutlinedButton(
                            onClick = {
                                onExecuteCommand(
                                    "flight.move",
                                    mapOf("mode" to "velocity", "vx" to 0.0, "vy" to -moveVel, "vz" to 0.0, "yaw_rate" to 0.0, "duration_ms" to moveDur),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Left") }
                        OutlinedButton(
                            onClick = {
                                onExecuteCommand(
                                    "flight.move",
                                    mapOf("mode" to "velocity", "vx" to 0.0, "vy" to 0.0, "vz" to moveVel, "yaw_rate" to 0.0, "duration_ms" to moveDur),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Up") }
                        OutlinedButton(
                            onClick = {
                                onExecuteCommand(
                                    "flight.move",
                                    mapOf("mode" to "velocity", "vx" to 0.0, "vy" to moveVel, "vz" to 0.0, "yaw_rate" to 0.0, "duration_ms" to moveDur),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Right") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        OutlinedButton(
                            onClick = {
                                onExecuteCommand(
                                    "flight.move",
                                    mapOf("mode" to "velocity", "vx" to -moveVel, "vy" to 0.0, "vz" to 0.0, "yaw_rate" to 0.0, "duration_ms" to moveDur),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Back") }
                        OutlinedButton(
                            onClick = {
                                onExecuteCommand(
                                    "flight.move",
                                    mapOf("mode" to "velocity", "vx" to 0.0, "vy" to 0.0, "vz" to -moveVel, "yaw_rate" to 0.0, "duration_ms" to moveDur),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Down") }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onExecuteCommand(
                                "flight.rotate",
                                mapOf("yaw_rate" to 20.0, "duration_ms" to 1000)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Rotate CW (+20°/s)")
                    }
                    OutlinedButton(
                        onClick = {
                            onExecuteCommand(
                                "flight.rotate",
                                mapOf("yaw_rate" to -20.0, "duration_ms" to 1000)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Rotate CCW (-20°/s)")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text("Gimbal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onExecuteCommand(
                                "gimbal.rotate",
                                mapOf("pitch_rate_dps" to 10.0, "yaw_rate_dps" to 0.0, "duration_ms" to 500),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Pitch ↑") }
                    OutlinedButton(
                        onClick = {
                            onExecuteCommand(
                                "gimbal.rotate",
                                mapOf("pitch_rate_dps" to -10.0, "yaw_rate_dps" to 0.0, "duration_ms" to 500),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Pitch ↓") }
                    OutlinedButton(
                        onClick = { onExecuteCommand("gimbal.reset", emptyMap()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Reset") }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onExecuteCommand(
                                "gimbal.set_angle",
                                mapOf("pitch_deg" to 0.0, "yaw_deg" to 0.0, "roll_deg" to 0.0),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Horizon 0°") }
                    OutlinedButton(
                        onClick = {
                            onExecuteCommand(
                                "gimbal.set_angle",
                                mapOf("pitch_deg" to -45.0, "yaw_deg" to 0.0, "roll_deg" to 0.0),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Down −45°") }
                    OutlinedButton(
                        onClick = {
                            onExecuteCommand(
                                "gimbal.set_angle",
                                mapOf("pitch_deg" to -90.0, "yaw_deg" to 0.0, "roll_deg" to 0.0),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Down −90°") }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Camera Actions
                Text("Payload / Camera Controls", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onExecuteCommand(
                                "camera.photo",
                                mapOf("size" to "12mp", "format" to "jpeg", "deliver" to "both"),
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Shoot Photo")
                    }
                    OutlinedButton(
                        onClick = {
                            onExecuteCommand(
                                "camera.snapshot",
                                mapOf("deliver" to "both", "quality" to 85),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Snapshot (no SD)")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isRecording) {
                        Button(
                            onClick = { onExecuteCommand("camera.record_start", emptyMap()) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Start Record")
                        }
                    } else {
                        Button(
                            onClick = {
                            onExecuteCommand(
                                "camera.record_stop",
                                mapOf("deliver" to "both"),
                            )
                        },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Stop Record")
                        }
                    }
                }
                if (lastPhotoDownloadUrl != null || lastPhotoLocalPath != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        lastPhotoDownloadUrl?.let { url ->
                            Text("Last link", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        lastPhotoLocalPath?.let { path ->
                            Text("Saved on device", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Live Streaming Actions
                Text("RTMP Livestream", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = streamUrlInput,
                    onValueChange = { streamUrlInput = it },
                    label = { Text("RTMP Stream Target URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onExecuteCommand("stream.start", mapOf("protocol" to "rtmp", "url" to streamUrlInput, "quality" to "HD"))
                        },
                        enabled = !isStreaming,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isStreaming) "Streaming Active" else "Start RTMP")
                    }
                    Button(
                        onClick = { onExecuteCommand("stream.stop", emptyMap()) },
                        enabled = isStreaming,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop RTMP")
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun SettingsView(
    djiAppKey: String,
    aircraftIdExample: String,
    config: MqttBrokerConfig,
    mqttState: MqttConnectionState,
    autoConnectOnLaunch: Boolean,
    onConfigChange: (MqttBrokerConfig) -> Unit,
    onSaveSettings: () -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onConnectMqtt: () -> Unit,
    onDisconnectMqtt: () -> Unit,
    onPickMqttPkcs12: () -> Unit,
    onPickMqttCaCert: () -> Unit,
    mtlsPkcs12Password: String,
    onMtlsPkcs12PasswordChange: (String) -> Unit,
    streamTokenSecret: String,
    onStreamTokenSecretChange: (String) -> Unit,
) {
    var host by remember(config) { mutableStateOf(config.host) }
    var port by remember(config) { mutableStateOf(config.port.toString()) }
    var useTls by remember(config) { mutableStateOf(config.useTls) }
    var username by remember(config) { mutableStateOf(config.username) }
    var password by remember(config) { mutableStateOf(config.password) }
    var aircraftId by remember(config) { mutableStateOf(config.aircraftId) }
    var clientId by remember(config) { mutableStateOf(config.clientId) }
    var viewerBaseUrl by remember(config) { mutableStateOf(config.viewerBaseUrl) }
    var streamTokenUrl by remember(config) { mutableStateOf(config.streamTokenUrl) }
    var photoUploadUrl by remember(config) { mutableStateOf(config.photoUploadUrl) }
    var videoUploadUrl by remember(config) { mutableStateOf(config.videoUploadUrl) }
    var useMtls by remember(config) { mutableStateOf(config.useMtls) }

    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Settings & App Configuration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Verify DJI App Key and configure upstream MQTT broker connection details", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(14.dp))

            // DJI App Key Section
            Text("DJI SDK App Key", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            DjiAppKeyCard(appKey = djiAppKey)

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("MQTT Broker Configuration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        onConfigChange(config.copy(host = it))
                    },
                    label = { Text("Broker Host / IP") },
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it
                        it.toIntOrNull()?.let { p -> onConfigChange(config.copy(port = p)) }
                    },
                    label = { Text("Port") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = aircraftId,
                    onValueChange = {
                        aircraftId = it
                        onConfigChange(config.copy(aircraftId = it))
                    },
                    label = { Text("Aircraft ID (e.g. $aircraftIdExample)") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = clientId,
                    onValueChange = {
                        clientId = it
                        onConfigChange(config.copy(clientId = it))
                    },
                    label = { Text("MQTT Client ID") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        onConfigChange(config.copy(username = it))
                    },
                    label = { Text("Username (Optional)") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        onConfigChange(config.copy(password = it))
                    },
                    label = { Text("Password (Optional)") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = viewerBaseUrl,
                onValueChange = {
                    viewerBaseUrl = it
                    onConfigChange(config.copy(viewerBaseUrl = it))
                },
                label = { Text("Agora viewer base URL (no token)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = streamTokenUrl,
                onValueChange = {
                    streamTokenUrl = it
                    onConfigChange(config.copy(streamTokenUrl = it))
                },
                label = { Text("Stream token URL (POST publisher token)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = photoUploadUrl,
                onValueChange = {
                    photoUploadUrl = it
                    onConfigChange(config.copy(photoUploadUrl = it))
                },
                label = { Text("Photo upload URL (POST JPEG)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = videoUploadUrl,
                onValueChange = {
                    videoUploadUrl = it
                    onConfigChange(config.copy(videoUploadUrl = it))
                },
                label = { Text("Video upload URL (POST MP4)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = streamTokenSecret,
                onValueChange = onStreamTokenSecretChange,
                label = { Text("Stream token endpoint secret (Bearer)") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("MQTT TLS (server)")
                Switch(checked = config.useTls, onCheckedChange = { onConfigChange(config.copy(useTls = it)) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("MQTT mTLS (client cert)")
                Switch(checked = useMtls, onCheckedChange = {
                    useMtls = it
                    onConfigChange(config.copy(useMtls = it))
                })
            }
            if (useMtls) {
                OutlinedTextField(
                    value = mtlsPkcs12Password,
                    onValueChange = onMtlsPkcs12PasswordChange,
                    label = { Text("PKCS#12 password") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickMqttPkcs12) { Text("Import PKCS#12") }
                    OutlinedButton(onClick = onPickMqttCaCert) { Text("Import CA (optional)") }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Computed MQTT Topics:", style = MaterialTheme.typography.labelMedium)
            Text("• Command Topic: ${config.commandTopic}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("• Status Topic:  ${config.statusTopic}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("• Ack Topic:     ${config.ackTopic}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-connect MQTT on launch", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "After settings are saved, reconnect when the app starts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoConnectOnLaunch, onCheckedChange = onAutoConnectChange)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(onClick = onSaveSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save MQTT settings")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mqttState !is MqttConnectionState.Connected) {
                    Button(
                        onClick = onConnectMqtt,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Connect MQTT Broker")
                    }
                } else {
                    Button(
                        onClick = onDisconnectMqtt,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect MQTT")
                    }
                }
            }
        }
    }
}

@Composable
fun MqttConsoleView(
    logs: List<String>,
    onClearLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "MQTT Traffic & Command Console",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        "Inbound command envelopes, status broadcasts, and acknowledgments (${logs.size} lines)",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onClearLogs) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = Color.LightGray)
                }
            }

            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 8.dp))

            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No MQTT message traffic recorded yet.", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true
                ) {
                    items(logs.reversed()) { logLine ->
                        val color = when {
                            logLine.contains("[ERROR]") -> Color(0xFFFF5252)
                            logLine.contains("[RECV]") -> Color(0xFF64B5F6)
                            logLine.contains("[ACK]") -> Color(0xFF81C784)
                            logLine.contains("[STATUS]") -> Color(0xFFFFB74D)
                            else -> Color(0xFFE0E0E0)
                        }
                        Text(
                            text = logLine,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppLogsView(
    logs: List<LogEntry>,
    onClearLogs: () -> Unit
) {
    var selectedLevelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var filterText by remember { mutableStateOf("") }

    val filteredLogs = remember(logs, selectedLevelFilter, filterText) {
        logs.filter { entry ->
            val matchesLevel = selectedLevelFilter == null || entry.level == selectedLevelFilter
            val matchesQuery = filterText.isBlank() ||
                    entry.tag.contains(filterText, ignoreCase = true) ||
                    entry.message.contains(filterText, ignoreCase = true)
            matchesLevel && matchesQuery
        }
    }

    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101216))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Android App Execution Logs",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        "Ring buffer: SDK registration, KeyManager polls, hardware connect, and adapter events (${filteredLogs.size}/${logs.size} lines)",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onClearLogs) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = Color.LightGray)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filter Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Filter chips
                FilterChip(
                    selected = selectedLevelFilter == null,
                    onClick = { selectedLevelFilter = null },
                    label = { Text("ALL (${logs.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )

                FilterChip(
                    selected = selectedLevelFilter == LogLevel.INFO,
                    onClick = { selectedLevelFilter = if (selectedLevelFilter == LogLevel.INFO) null else LogLevel.INFO },
                    label = { Text("INFO (${logs.count { it.level == LogLevel.INFO }})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2E7D32),
                        selectedLabelColor = Color.White
                    )
                )

                FilterChip(
                    selected = selectedLevelFilter == LogLevel.WARN,
                    onClick = { selectedLevelFilter = if (selectedLevelFilter == LogLevel.WARN) null else LogLevel.WARN },
                    label = { Text("WARN (${logs.count { it.level == LogLevel.WARN }})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFEF6C00),
                        selectedLabelColor = Color.White
                    )
                )

                FilterChip(
                    selected = selectedLevelFilter == LogLevel.ERROR,
                    onClick = { selectedLevelFilter = if (selectedLevelFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                    label = { Text("ERR (${logs.count { it.level == LogLevel.ERROR }})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFC62828),
                        selectedLabelColor = Color.White
                    )
                )

                Spacer(modifier = Modifier.weight(1f))

                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    placeholder = { Text("Filter tag / message...", fontSize = 11.sp, color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.width(220.dp).height(44.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = Color.White)
                )
            }

            HorizontalDivider(color = Color(0xFF2B303A), modifier = Modifier.padding(vertical = 8.dp))

            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No execution log entries found matching filter.", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true
                ) {
                    items(filteredLogs.reversed()) { entry ->
                        AppLogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
fun AppLogEntryRow(entry: LogEntry) {
    val (levelBadgeColor, levelBgColor, levelText) = when (entry.level) {
        LogLevel.ERROR -> Triple(Color(0xFFFF5252), Color(0x33FF5252), "E")
        LogLevel.WARN -> Triple(Color(0xFFFFB74D), Color(0x33FFB74D), "W")
        LogLevel.INFO -> Triple(Color(0xFF81C784), Color(0x3381C784), "I")
        LogLevel.DEBUG -> Triple(Color(0xFF90CAF9), Color(0x3390CAF9), "D")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.timestamp,
            color = Color(0xFF888888),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 6.dp)
        )
        Surface(
            color = levelBgColor,
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier.padding(end = 6.dp)
        ) {
            Text(
                text = "[$levelText]",
                color = levelBadgeColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Text(
            text = "[${entry.tag}]",
            color = Color(0xFF64B5F6),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = entry.message,
            color = when (entry.level) {
                LogLevel.ERROR -> Color(0xFFFF8A80)
                LogLevel.WARN -> Color(0xFFFFD180)
                LogLevel.INFO -> Color(0xFFECEFF1)
                LogLevel.DEBUG -> Color(0xFFB0BEC5)
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

