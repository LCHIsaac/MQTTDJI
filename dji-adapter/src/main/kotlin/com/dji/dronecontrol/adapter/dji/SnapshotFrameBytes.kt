package com.dji.dronecontrol.adapter.dji

internal fun extractFrameBytes(data: ByteArray, offset: Int, length: Int): ByteArray? {
    if (data.isEmpty() || length <= 0) return null
    val start = offset.coerceIn(0, data.size)
    val end = (start + length).coerceIn(0, data.size)
    if (end <= start) return null
    return if (start == 0 && end == data.size) data else data.copyOfRange(start, end)
}
