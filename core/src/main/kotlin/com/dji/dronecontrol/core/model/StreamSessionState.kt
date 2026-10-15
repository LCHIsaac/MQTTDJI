package com.dji.dronecontrol.core.model

data class StreamSessionState(
    val state: String = "stopped",
    val protocol: String? = null,
    val channel: String? = null,
    val viewerUrl: String? = null,
    val rtmpUrl: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val startedAt: String? = null,
) {
    val isLive: Boolean get() = state == "live"
}
