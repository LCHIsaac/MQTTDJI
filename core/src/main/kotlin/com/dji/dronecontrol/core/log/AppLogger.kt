package com.dji.dronecontrol.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun formatted(): String = "[$timestamp] [${level.name.first()}] [$tag] $message"
}

/**
 * Shared in-app ring-buffer execution logger (500–1000 lines).
 * Captures SDK events, MQTT events, flight & camera adapter actions, errors.
 */
object AppLogger {
    private const val MAX_LOGS = 1000

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val lock = Any()

    fun log(level: LogLevel, tag: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = LogEntry(
            timestamp = timestamp,
            level = level,
            tag = tag,
            message = message
        )
        synchronized(lock) {
            val current = _logs.value
            val next = if (current.size >= MAX_LOGS) {
                current.drop(current.size - MAX_LOGS + 1) + entry
            } else {
                current + entry
            }
            _logs.value = next
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun clear() {
        synchronized(lock) {
            _logs.value = emptyList()
        }
    }
}
