package com.dji.dronecontrol.core

import com.dji.dronecontrol.core.mock.MockAircraft
import com.dji.dronecontrol.core.mock.MockMqttTransport
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import com.dji.dronecontrol.core.model.VirtualStickMode
import com.dji.dronecontrol.core.router.CommandRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterTest {

    private lateinit var testScope: TestScope
    private lateinit var mockAircraft: MockAircraft
    private lateinit var mockTransport: MockMqttTransport
    private lateinit var router: CommandRouter
    private val config = MqttBrokerConfig(
        aircraftId = "m3e-01",
        host = "localhost"
    )

    @Before
    fun setUp() {
        val dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        mockAircraft = MockAircraft(testScope)
        mockTransport = MockMqttTransport()
        router = CommandRouter(
            flightPort = mockAircraft,
            cameraPort = mockAircraft,
            gimbalPort = mockAircraft,
            streamPort = mockAircraft,
            aircraftPort = mockAircraft,
            mqttTransport = mockTransport,
            photoDeliveryPort = mockAircraft,
            mediaLibraryPort = mockAircraft,
            scope = testScope
        )
        router.updateStreamTokenConfig(
            defaultTokenUrl = "https://example.com/live/token",
            secretProvider = { "test-secret" },
        )
        router.updateConfig(config)
        // Note: We don't call startListening() with infinite collection in unit tests to avoid UncompletedCoroutinesError
    }

    @Test
    fun testMqttPingCommand() = testScope.runTest {
        val payload = """
            {
              "schema_version": "1.0",
              "msg_id": "test-ping-01",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "mqtt.ping",
              "params": {}
            }
        """.trimIndent()

        router.handleIncomingMessage(payload)

        assertTrue(mockTransport.publishedMessages.any { it.first == config.ackTopic && it.second.contains(""""msg_id":"test-ping-01"""") && it.second.contains(""""ok":true""") })
    }

    @Test
    fun testFlightTakeoffAndLand() = testScope.runTest {
        val takeoffPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "takeoff-01",
              "ts": "2026-09-24T12:00:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.takeoff",
              "params": { "target_height_m": 2.5 }
            }
        """.trimIndent()

        router.handleIncomingMessage(takeoffPayload)

        assertTrue(mockAircraft.isFlying.value)
        assertEquals(2.5, mockAircraft.gpsInfo.value.altitudeM, 0.01)
        assertTrue(mockTransport.publishedMessages.any { it.first == config.ackTopic && it.second.contains(""""msg_id":"takeoff-01"""") && it.second.contains(""""ok":true""") })

        val landPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "land-01",
              "ts": "2026-09-24T12:01:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.land",
              "params": {}
            }
        """.trimIndent()

        router.handleIncomingMessage(landPayload)

        assertFalse(mockAircraft.isFlying.value)
        assertEquals(0.0, mockAircraft.gpsInfo.value.altitudeM, 0.01)
        assertTrue(mockTransport.publishedMessages.any { it.first == config.ackTopic && it.second.contains(""""msg_id":"land-01"""") && it.second.contains(""""ok":true""") })
    }

    @Test
    fun testFlightMoveVirtualStick() = testScope.runTest {
        // Takeoff first
        mockAircraft.takeoff()

        val movePayload = """
            {
              "schema_version": "1.0",
              "msg_id": "move-01",
              "ts": "2026-09-24T12:02:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.move",
              "params": {
                "mode": "velocity",
                "vx": 2.0,
                "vy": -1.0,
                "vz": 0.5,
                "yaw_rate": 15.0,
                "duration_ms": 1000
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(movePayload)

        assertEquals(1, mockAircraft.executedVirtualSticks.size)
        val vs = mockAircraft.executedVirtualSticks.first()
        assertEquals(VirtualStickMode.VELOCITY, vs.mode)
        assertEquals(2.0, vs.vx, 0.001)
        assertEquals(-1.0, vs.vy, 0.001)
        assertEquals(0.5, vs.vz, 0.001)
        assertEquals(15.0, vs.yawRate, 0.001)
    }

    @Test
    fun testFlightMoveHonorsDurationAndAutoHovers() = testScope.runTest {
        mockAircraft.takeoff()
        mockAircraft.recordedStickFrames.clear()

        val movePayload = """
            {
              "schema_version": "1.0",
              "msg_id": "move-duration-01",
              "ts": "2026-09-24T12:02:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.move",
              "params": {
                "mode": "velocity",
                "vx": 1.5,
                "vy": 0.0,
                "vz": 0.0,
                "yaw_rate": 0.0,
                "duration_ms": 1000
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(movePayload)
        testScope.testScheduler.runCurrent()

        assertEquals("VIRTUAL_STICK", mockAircraft.flightMode.value)
        assertEquals(1, mockAircraft.recordedStickFrames.size)
        assertEquals(1.5, mockAircraft.recordedStickFrames.first().vx, 0.001)

        // Advance 500ms
        testScope.testScheduler.advanceTimeBy(500)
        testScope.testScheduler.runCurrent()
        assertEquals(11, mockAircraft.recordedStickFrames.size)
        assertEquals("VIRTUAL_STICK", mockAircraft.flightMode.value)

        // Advance to full 1000ms completion
        testScope.testScheduler.advanceTimeBy(500)
        testScope.testScheduler.advanceUntilIdle()

        // 20 active velocity frames + 1 hover zero frame = 21 frames
        assertEquals(21, mockAircraft.recordedStickFrames.size)
        val finalFrame = mockAircraft.recordedStickFrames.last()
        assertEquals(0.0, finalFrame.vx, 0.001)
        assertEquals(0.0, finalFrame.vy, 0.001)
        assertEquals("P_GPS", mockAircraft.flightMode.value)
    }

    @Test
    fun testFlightHoverInterruptsActiveMove() = testScope.runTest {
        mockAircraft.takeoff()
        mockAircraft.recordedStickFrames.clear()

        val movePayload = """
            {
              "schema_version": "1.0",
              "msg_id": "move-interrupt-01",
              "ts": "2026-09-24T12:02:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.move",
              "params": {
                "mode": "velocity",
                "vx": 3.0,
                "duration_ms": 3000
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(movePayload)
        testScope.testScheduler.advanceTimeBy(200)
        testScope.testScheduler.runCurrent()
        assertEquals("VIRTUAL_STICK", mockAircraft.flightMode.value)
        val framesBeforeHover = mockAircraft.recordedStickFrames.size
        assertEquals(5, framesBeforeHover)

        // Hover command arrives
        val hoverPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "hover-01",
              "ts": "2026-09-24T12:02:01Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.hover",
              "params": {}
            }
        """.trimIndent()

        router.handleIncomingMessage(hoverPayload)
        testScope.testScheduler.runCurrent()

        assertEquals("P_GPS", mockAircraft.flightMode.value)
        assertEquals(framesBeforeHover + 1, mockAircraft.recordedStickFrames.size)
        assertEquals(0.0, mockAircraft.recordedStickFrames.last().vx, 0.001)

        // Advance past original 3000ms to ensure no remaining frames fire
        testScope.testScheduler.advanceTimeBy(4000)
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(framesBeforeHover + 1, mockAircraft.recordedStickFrames.size)
    }

    @Test
    fun testConsecutiveMoveCommandsPreemptPrevious() = testScope.runTest {
        mockAircraft.takeoff()
        mockAircraft.recordedStickFrames.clear()

        // Move 1
        val move1Payload = """
            {
              "schema_version": "1.0",
              "msg_id": "move-preempt-01",
              "ts": "2026-09-24T12:02:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.move",
              "params": {
                "mode": "velocity",
                "vx": 2.0,
                "duration_ms": 2000
              }
            }
        """.trimIndent()
        router.handleIncomingMessage(move1Payload)
        testScope.testScheduler.advanceTimeBy(150)
        testScope.testScheduler.runCurrent()

        val move1Count = mockAircraft.recordedStickFrames.size
        assertEquals(4, move1Count) // t=0, 50, 100, 150

        // Move 2 preempts Move 1
        val move2Payload = """
            {
              "schema_version": "1.0",
              "msg_id": "move-preempt-02",
              "ts": "2026-09-24T12:02:01Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.move",
              "params": {
                "mode": "velocity",
                "vx": -1.5,
                "duration_ms": 200
              }
            }
        """.trimIndent()
        router.handleIncomingMessage(move2Payload)
        testScope.testScheduler.advanceTimeBy(200)
        testScope.testScheduler.advanceUntilIdle()

        // Move 2 frames: 4 frames (t=0, 50, 100, 150) + 1 hover frame (t=200) = 5 frames
        val move2Frames = mockAircraft.recordedStickFrames.drop(move1Count)
        assertEquals(5, move2Frames.size)
        assertEquals(-1.5, move2Frames[0].vx, 0.001)
        assertEquals(-1.5, move2Frames[3].vx, 0.001)
        assertEquals(0.0, move2Frames.last().vx, 0.001)
        assertEquals("P_GPS", mockAircraft.flightMode.value)
    }

    @Test
    fun testFlightRotateHonorsDurationAndAutoHovers() = testScope.runTest {
        mockAircraft.takeoff()
        mockAircraft.recordedStickFrames.clear()

        val rotatePayload = """
            {
              "schema_version": "1.0",
              "msg_id": "rotate-01",
              "ts": "2026-09-24T12:02:30Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.rotate",
              "params": {
                "yaw_rate": 30.0,
                "duration_ms": 1000
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(rotatePayload)
        testScope.testScheduler.runCurrent()

        assertEquals("VIRTUAL_STICK", mockAircraft.flightMode.value)
        assertEquals(1, mockAircraft.recordedStickFrames.size)
        val firstFrame = mockAircraft.recordedStickFrames.first()
        assertEquals(0.0, firstFrame.vx, 0.001)
        assertEquals(0.0, firstFrame.vy, 0.001)
        assertEquals(0.0, firstFrame.vz, 0.001)
        assertEquals(30.0, firstFrame.yawRate, 0.001)

        // Advance 500ms -> 11 frames total
        testScope.testScheduler.advanceTimeBy(500)
        testScope.testScheduler.runCurrent()
        assertEquals(11, mockAircraft.recordedStickFrames.size)
        assertTrue(mockAircraft.recordedStickFrames.all { it.vx == 0.0 && it.vy == 0.0 && it.vz == 0.0 && it.yawRate == 30.0 })

        // Complete 1000ms duration
        testScope.testScheduler.advanceTimeBy(500)
        testScope.testScheduler.advanceUntilIdle()

        // 20 active rotation frames + 1 hover zero frame = 21 frames
        assertEquals(21, mockAircraft.recordedStickFrames.size)
        val finalFrame = mockAircraft.recordedStickFrames.last()
        assertEquals(0.0, finalFrame.vx, 0.001)
        assertEquals(0.0, finalFrame.vy, 0.001)
        assertEquals(0.0, finalFrame.vz, 0.001)
        assertEquals(0.0, finalFrame.yawRate, 0.001)
        assertEquals("P_GPS", mockAircraft.flightMode.value)

        assertTrue(mockTransport.publishedMessages.any { it.first == config.ackTopic && it.second.contains(""""msg_id":"rotate-01"""") && it.second.contains(""""ok":true""") })
    }

    @Test
    fun testFlightRotatePreemptsActiveMove() = testScope.runTest {
        mockAircraft.takeoff()
        mockAircraft.recordedStickFrames.clear()

        // Move command (2000ms)
        val movePayload = """
            {
              "schema_version": "1.0",
              "msg_id": "move-before-rot",
              "ts": "2026-09-24T12:02:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.move",
              "params": {
                "mode": "velocity",
                "vx": 2.0,
                "duration_ms": 2000
              }
            }
        """.trimIndent()
        router.handleIncomingMessage(movePayload)
        testScope.testScheduler.advanceTimeBy(100) // t=0, 50, 100 -> 3 frames
        testScope.testScheduler.runCurrent()
        val moveFramesCount = mockAircraft.recordedStickFrames.size
        assertEquals(3, moveFramesCount)

        // Rotate command preempts move
        val rotatePayload = """
            {
              "schema_version": "1.0",
              "msg_id": "rotate-preempt",
              "ts": "2026-09-24T12:02:01Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.rotate",
              "params": {
                "yaw_rate": -45.0,
                "duration_ms": 200
              }
            }
        """.trimIndent()
        router.handleIncomingMessage(rotatePayload)
        testScope.testScheduler.advanceTimeBy(200)
        testScope.testScheduler.advanceUntilIdle()

        // Rotate frames: 4 active (t=0, 50, 100, 150) + 1 hover (t=200) = 5 frames
        val rotateFrames = mockAircraft.recordedStickFrames.drop(moveFramesCount)
        assertEquals(5, rotateFrames.size)
        assertEquals(0.0, rotateFrames[0].vx, 0.001)
        assertEquals(-45.0, rotateFrames[0].yawRate, 0.001)
        assertEquals(-45.0, rotateFrames[3].yawRate, 0.001)
        assertEquals(0.0, rotateFrames.last().yawRate, 0.001)
        assertEquals("P_GPS", mockAircraft.flightMode.value)
    }

    @Test
    fun testCameraPhotoAndRecord() = testScope.runTest {
        val photoPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "photo-01",
              "ts": "2026-09-24T12:03:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "camera.photo",
              "params": { "size": "48mp", "format": "jpeg", "deliver": "local" }
            }
        """.trimIndent()

        router.handleIncomingMessage(photoPayload)
        testScope.testScheduler.advanceUntilIdle()

        assertEquals(1, mockAircraft.photoCount)
        assertTrue(
            mockTransport.publishedMessages.any {
                it.first == config.statusTopic &&
                    it.second.contains(""""type":"camera.photo_done"""") &&
                    it.second.contains(""""local_path"""")
            },
        )

        // Start record
        val recordStartPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "record-start-01",
              "ts": "2026-09-24T12:03:30Z",
              "aircraft_id": "m3e-01",
              "cmd": "camera.record_start",
              "params": {}
            }
        """.trimIndent()
        router.handleIncomingMessage(recordStartPayload)

        assertTrue(mockAircraft.isRecording.value)

        // Stop record
        val recordStopPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "record-stop-01",
              "ts": "2026-09-24T12:04:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "camera.record_stop",
              "params": {}
            }
        """.trimIndent()
        router.handleIncomingMessage(recordStopPayload)

        assertFalse(mockAircraft.isRecording.value)
    }

    @Test
    fun testStreamStartAndStop() = testScope.runTest {
        val streamStartPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "stream-01",
              "ts": "2026-09-24T12:05:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "stream.start",
              "params": {
                "protocol": "rtmp",
                "url": "rtmp://live.stream.server/live/drone01",
                "quality": "HD"
              }
            }
        """.trimIndent()

        router.handleIncomingMessage(streamStartPayload)

        assertTrue(mockAircraft.isStreaming.value)
        assertEquals("rtmp://live.stream.server/live/drone01", mockAircraft.currentStreamUrl.value)

        val streamStopPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "stream-stop-01",
              "ts": "2026-09-24T12:06:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "stream.stop",
              "params": {}
            }
        """.trimIndent()

        router.handleIncomingMessage(streamStopPayload)

        assertFalse(mockAircraft.isStreaming.value)
    }

    @Test
    fun testConfigGet() = testScope.runTest {
        val configPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "cfg-01",
              "ts": "2026-09-24T12:07:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "config.get",
              "params": {}
            }
        """.trimIndent()

        router.handleIncomingMessage(configPayload)

        assertTrue(mockTransport.publishedMessages.any { it.first == config.statusTopic && it.second.contains(""""type":"config.response"""") && it.second.contains(""""product_type":"Mavic 3E"""") })
    }

    @Test
    fun testEmergencyStop() = testScope.runTest {
        mockAircraft.takeoff()
        assertTrue(mockAircraft.isFlying.value)

        val emStopPayload = """
            {
              "schema_version": "1.0",
              "msg_id": "em-01",
              "ts": "2026-09-24T12:08:00Z",
              "aircraft_id": "m3e-01",
              "cmd": "flight.emergency_stop",
              "params": {}
            }
        """.trimIndent()

        router.handleIncomingMessage(emStopPayload)

        assertFalse(mockAircraft.isFlying.value)
        assertEquals("EMERGENCY_STOP", mockAircraft.flightMode.value)
    }
}
