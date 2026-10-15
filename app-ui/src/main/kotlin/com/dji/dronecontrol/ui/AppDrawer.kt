package com.dji.dronecontrol.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dji.dronecontrol.core.telemetry.AppNavDestination
import com.dji.dronecontrol.core.telemetry.NavSection

@Composable
fun AppNavigationDrawerSheet(
    selected: AppNavDestination,
    onDestinationSelected: (AppNavDestination) -> Unit,
    onClose: () -> Unit,
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            Text(
                "Navigation",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
            DrawerSection(
                title = NavSection.OPS.title,
                destinations = AppNavDestination.entries.filter { it.section == NavSection.OPS },
                selected = selected,
                onSelect = { onDestinationSelected(it); onClose() },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerSection(
                title = NavSection.TELEMETRY.title,
                destinations = AppNavDestination.entries.filter { it.section == NavSection.TELEMETRY },
                selected = selected,
                onSelect = { onDestinationSelected(it); onClose() },
            )
        }
    }
}

@Composable
private fun DrawerSection(
    title: String,
    destinations: List<AppNavDestination>,
    selected: AppNavDestination,
    onSelect: (AppNavDestination) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
    )
    destinations.forEach { dest ->
        NavigationDrawerItem(
            label = {
                Text(dest.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            selected = selected == dest,
            onClick = { onSelect(dest) },
            icon = { IconForDestination(dest) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun IconForDestination(dest: AppNavDestination) {
    val icon = when (dest) {
        AppNavDestination.Controls -> Icons.Default.Dashboard
        AppNavDestination.Media -> Icons.Default.PermMedia
        AppNavDestination.Connection -> Icons.Default.Cable
        AppNavDestination.Mqtt -> Icons.Default.Settings
        AppNavDestination.MqttLog -> Icons.Default.SwapHoriz
        AppNavDestination.AppLogs -> Icons.Default.Terminal
        AppNavDestination.FlightState -> Icons.Default.Flight
        AppNavDestination.BatteryStatus -> Icons.Default.BatteryFull
        AppNavDestination.PositionMotion -> Icons.Default.LocationOn
        AppNavDestination.ObstacleSensing -> Icons.Default.Radar
        AppNavDestination.LinkController -> Icons.Default.SignalCellularAlt
    }
    androidx.compose.material3.Icon(icon, contentDescription = dest.label)
}
