package com.dji.dronecontrol.adapter.dji

import dji.v5.common.error.IDJIError

internal fun IDJIError.formatForLog(): String {
    val parts = buildList {
        description()?.takeIf { it.isNotBlank() }?.let { add(it) }
        errorCode()?.takeIf { it.isNotBlank() }?.let { add("errorCode=$it") }
        innerCode()?.takeIf { it.isNotBlank() }?.let { add("innerCode=$it") }
        hint()?.takeIf { it.isNotBlank() }?.let { add("hint=$it") }
    }
    return parts.joinToString("; ").ifBlank { "unknown SDK error" }
}
