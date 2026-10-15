package com.dji.dronecontrol.core.telemetry

fun formatTelemetry(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "—"

fun formatTelemetry(value: Int?): String = value?.toString() ?: "—"

fun formatTelemetry(value: Double?): String = value?.let { "%.2f".format(it) } ?: "—"

fun formatTelemetry(value: Boolean?): String = when (value) {
    true -> "YES"
    false -> "NO"
    null -> "—"
}

fun formatCoord(value: Double?): String = value?.let { "%.6f".format(it) } ?: "—"
