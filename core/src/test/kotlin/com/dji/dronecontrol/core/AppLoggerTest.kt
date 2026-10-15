package com.dji.dronecontrol.core

import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.log.LogLevel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppLoggerTest {

    @Before
    fun setUp() {
        AppLogger.clear()
    }

    @Test
    fun testAppLoggerBasicLogging() {
        AppLogger.i("TestTag", "Info message")
        AppLogger.w("TestTag", "Warning message")
        AppLogger.e("TestTag", "Error message")

        val logs = AppLogger.logs.value
        assertEquals(3, logs.size)

        assertEquals(LogLevel.INFO, logs[0].level)
        assertEquals("TestTag", logs[0].tag)
        assertEquals("Info message", logs[0].message)

        assertEquals(LogLevel.WARN, logs[1].level)
        assertEquals(LogLevel.ERROR, logs[2].level)
    }

    @Test
    fun testAppLoggerFormatting() {
        AppLogger.i("Conn", "SDK Registered")
        val entry = AppLogger.logs.value.first()
        val formatted = entry.formatted()

        assertTrue(formatted.contains("[I]"))
        assertTrue(formatted.contains("[Conn]"))
        assertTrue(formatted.contains("SDK Registered"))
    }

    @Test
    fun testAppLoggerRingBufferCapacity() {
        // Log 1050 items, should keep max 1000 items
        for (i in 1..1050) {
            AppLogger.d("Loop", "Log number $i")
        }

        val logs = AppLogger.logs.value
        assertEquals(1000, logs.size)
        // Oldest 50 should be discarded; first remaining should be log 51
        assertEquals("Log number 51", logs.first().message)
        assertEquals("Log number 1050", logs.last().message)
    }

    @Test
    fun testAppLoggerClear() {
        AppLogger.i("Test", "Message")
        assertEquals(1, AppLogger.logs.value.size)

        AppLogger.clear()
        assertEquals(0, AppLogger.logs.value.size)
    }
}
